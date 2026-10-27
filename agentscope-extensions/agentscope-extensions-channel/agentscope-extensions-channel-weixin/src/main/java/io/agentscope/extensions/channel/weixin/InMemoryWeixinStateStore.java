/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.extensions.channel.weixin;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 单机／测试适配器，使用与持久化宿主相同的隔离令牌和认领规则。
 *
 * <p><b>不可用于生产。</b>所有状态都在当前进程内：重启会丢失游标、对端上下文令牌和租约；第二个宿主实例使用不同存储，因此单消费者保证仅在一个 JVM 内成立。
 * 要支持重启恢复和水平扩展，需使用持久化 {@link WeixinStateStore}，参见模块 README。
 *
 * <p>已完成消息的墓碑标记在 {@link #RETENTION_MS} 后过期；{@link #acceptBatch} 期间，存储会移除完全无状态的账户（无游标、无对端上下文、无收件箱、无活动租约），例如仅在验证请求时出现的 ID。
 * 实际消费过消息的账户会保留游标和对端上下文，直至宿主用 {@link #removeAccount} 停用；丢弃这些信息会使消费者从头重放服务方积压消息。
 */
final class InMemoryWeixinStateStore implements WeixinStateStore {
    private static final long RETENTION_MS = Duration.ofDays(7).toMillis();
    private final Clock clock;
    private final Map<String, Account> accounts = new HashMap<>();

    InMemoryWeixinStateStore() {
        this(Clock.systemUTC());
    }

    InMemoryWeixinStateStore(Clock clock) {
        this.clock = clock;
    }

    /**
 * 按需创建账户；仅变更操作可使用。
 */
    private Account account(String id) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("accountId is required");
        return accounts.computeIfAbsent(id, ignored -> new Account());
    }

    /**
 * 当前保留的账户数量，用于测试和诊断。
 */
    int retainedAccounts() {
        return accounts.size();
    }

    @Override
    public synchronized void removeAccount(String accountId) {
        if (accountId == null || accountId.isBlank())
            throw new IllegalArgumentException("accountId is required");
        accounts.remove(accountId);
    }

    /**
 * 查找账户但不创建；验证路径必须使用此方法。
 */
    private Account existing(String id) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("accountId is required");
        return accounts.get(id);
    }

    @Override
    public synchronized String loadCursor(String accountId) {
        Account a = existing(accountId);
        return a == null ? "" : a.cursor;
    }

    @Override
    public synchronized String loadContextToken(String accountId, String peerId) {
        Account a = existing(accountId);
        return a == null ? null : a.contexts.get(peerId);
    }

    @Override
    public synchronized boolean saveContextToken(
            String accountId, WeixinLease lease, String peerId, String token) {
        if (!isLeaseCurrent(accountId, lease)) return false;
        if (peerId != null && token != null && !token.isBlank()) {
            account(accountId).contexts.put(peerId, token);
        }
        return true;
    }

    @Override
    public synchronized Optional<WeixinLease> acquireLease(
            String accountId, String holderId, long leaseMs) {
        if (holderId == null || holderId.isBlank() || leaseMs <= 0)
            throw new IllegalArgumentException("holderId and positive leaseMs are required");
        Account a = account(accountId);
        if (a.lease != null && a.expiresAt > clock.millis()) {
            if (!a.lease.holderId().equals(holderId)) return Optional.empty();
        } else {
            a.lease = new WeixinLease(holderId, ++a.generation);
        }
        a.expiresAt = clock.millis() + leaseMs;
        return Optional.of(a.lease);
    }

    @Override
    public synchronized boolean renewLease(String accountId, WeixinLease lease, long leaseMs) {
        if (leaseMs <= 0) throw new IllegalArgumentException("leaseMs must be positive");
        if (!isLeaseCurrent(accountId, lease)) return false;
        account(accountId).expiresAt = clock.millis() + leaseMs;
        return true;
    }

    @Override
    public synchronized boolean isLeaseCurrent(String accountId, WeixinLease lease) {
        Account a = existing(accountId);
        return a != null && lease != null && lease.equals(a.lease) && a.expiresAt > clock.millis();
    }

    @Override
    public synchronized void releaseLease(String accountId, WeixinLease lease) {
        Account a = existing(accountId);
        if (a != null && lease != null && lease.equals(a.lease)) a.expiresAt = 0;
    }

    @Override
    public synchronized boolean acceptBatch(
            String accountId,
            WeixinLease lease,
            String nextCursor,
            List<WeixinInboxMessage> messages) {
        List<WeixinInboxMessage> batch = List.copyOf(messages);
        if (!isLeaseCurrent(accountId, lease)) return false;
        Account a = account(accountId);
        for (WeixinInboxMessage message : batch) {
            a.inbox.putIfAbsent(message.messageId(), new Message(message.payload()));
        }
        if (nextCursor != null && !nextCursor.isBlank()) a.cursor = nextCursor;
        prune(accountId);
        return true;
    }

    @Override
    public synchronized List<WeixinInboxClaim> claimMessages(
            String accountId, WeixinLease lease, int limit, long claimMs) {
        if (limit <= 0 || claimMs <= 0)
            throw new IllegalArgumentException("positive claim bounds required");
        if (!isLeaseCurrent(accountId, lease)) return List.of();
        List<WeixinInboxClaim> claims = new ArrayList<>();
        for (var entry : account(accountId).inbox.entrySet()) {
            Message m = entry.getValue();
            if (m.completed || m.abandoned) continue;
            if (lease.equals(m.lease) && m.claimUntil > clock.millis()) break;
            m.lease = lease;
            m.claimId = UUID.randomUUID().toString();
            m.claimUntil = clock.millis() + claimMs;
            claims.add(new WeixinInboxClaim(entry.getKey(), m.payload, m.claimId));
            if (claims.size() >= limit) break;
        }
        return List.copyOf(claims);
    }

    @Override
    public synchronized boolean completeMessage(
            String accountId, WeixinLease lease, WeixinInboxClaim claim) {
        Message m = validClaim(accountId, lease, claim);
        if (m == null) return false;
        m.completed = true;
        m.completedAt = clock.millis();
        m.payload = null;
        m.claimId = null;
        return true;
    }

    @Override
    public synchronized boolean failMessage(
            String accountId, WeixinLease lease, WeixinInboxClaim claim) {
        Message m = validClaim(accountId, lease, claim);
        if (m == null) return false;
        m.claimUntil = 0;
        m.claimId = null;
        return true;
    }

    @Override
    public synchronized boolean abandonMessage(
            String accountId, WeixinLease lease, WeixinInboxClaim claim) {
        Message m = validClaim(accountId, lease, claim);
        if (m == null) return false;
        m.abandoned = true;
        m.completedAt = clock.millis();
        m.payload = null;
        m.claimId = null;
        return true;
    }

    /**
     * 清理每个账户过期的墓碑，并移除完全无状态的账户。每次接受批次时调用，因此成本与进程运行期间见过的账户数成正比。
     *
     * <p>此处绝不移除持有游标或对端上下文的账户：这些状态表示消费者位置，遗忘它们会重放服务方积压消息。
     */
    private void prune(String activeAccountId) {
        long now = clock.millis();
        for (Account account : accounts.values()) {
            account.inbox
                    .values()
                    .removeIf(
                            m ->
                                    (m.completed || m.abandoned)
                                            && m.completedAt < now - RETENTION_MS);
        }
        accounts.entrySet()
                .removeIf(
                        entry ->
                                !entry.getKey().equals(activeAccountId)
                                        && entry.getValue().expiresAt <= now
                                        && entry.getValue().cursor.isEmpty()
                                        && entry.getValue().contexts.isEmpty()
                                        && entry.getValue().inbox.isEmpty());
    }

    private Message validClaim(String accountId, WeixinLease lease, WeixinInboxClaim claim) {
        if (!isLeaseCurrent(accountId, lease)) return null;
        Account a = existing(accountId);
        Message m = a == null ? null : a.inbox.get(claim.messageId());
        return m != null
                        && !m.completed
                        && lease.equals(m.lease)
                        && claim.claimId().equals(m.claimId)
                        && m.claimUntil > clock.millis()
                ? m
                : null;
    }

    private static final class Account {
        String cursor = "";
        long generation;
        long expiresAt;
        WeixinLease lease;
        final Map<String, String> contexts = new HashMap<>();
        final Map<String, Message> inbox = new LinkedHashMap<>();
    }

    private static final class Message {
        String payload;
        WeixinLease lease;
        String claimId;
        long claimUntil;
        boolean completed;
        boolean abandoned;
        long completedAt;

        Message(String payload) {
            this.payload = payload;
        }
    }
}
