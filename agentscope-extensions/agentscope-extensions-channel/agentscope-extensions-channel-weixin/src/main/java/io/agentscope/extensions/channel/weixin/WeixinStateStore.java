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

import java.util.List;
import java.util.Optional;

/**
 * 账户作用域的至少一次消息处理状态。实现必须在每次变更时原子验证租约仍未过期。外部智能体和服务方副作用不属于该事务，崩溃后可能重复执行。
 *
 * <p>【学习提示】“消息加游标原子保存”解决本地接收进度一致性；租约代次与 claimId 分别防止旧消费者和旧尝试迟到写回。它们不提供外部副作用的恰好一次保证。
 */
public interface WeixinStateStore {
    String loadCursor(String accountId);

    String loadContextToken(String accountId, String peerId);

    boolean saveContextToken(String accountId, WeixinLease lease, String peerId, String token);

    /**
 * 已释放或过期租约绝不能复用其代次，即使持有者相同。
 */
    Optional<WeixinLease> acquireLease(String accountId, String holderId, long leaseMs);

    boolean renewLease(String accountId, WeixinLease lease, long leaseMs);

    boolean isLeaseCurrent(String accountId, WeixinLease lease);

    void releaseLease(String accountId, WeixinLease lease);

    /**
 * 在同一事务保存整个批次与游标，并按消息 ID 去重。
 */
    boolean acceptBatch(
            String accountId,
            WeixinLease lease,
            String nextCursor,
            List<WeixinInboxMessage> messages);

    /**
 * 按接收顺序认领；新租约可立即恢复前持有者的未完成工作。
 */
    List<WeixinInboxClaim> claimMessages(
            String accountId, WeixinLease lease, int limit, long claimMs);

    /**
 * 只有完全匹配且未过期的认领才能完成；已完成载荷必须移除。
 */
    boolean completeMessage(String accountId, WeixinLease lease, WeixinInboxClaim claim);

    boolean failMessage(String accountId, WeixinLease lease, WeixinInboxClaim claim);

    /**
     * 丢弃消费者不再重试的已认领消息：分发持续失败，或服务方持续拒绝回复。为消息保留墓碑，防止重复投递批次使其复活，但不将其报告为已完成。
     */
    boolean abandonMessage(String accountId, WeixinLease lease, WeixinInboxClaim claim);

    /**
     * 遗忘 {@code accountId} 持有的全部状态：游标、对端上下文令牌和收件箱。
     *
     * <p>宿主在账户停用时调用，避免长期运行进程累积不再服务的账户状态。不持有账户状态的实现可保留默认空操作。
     */
    default void removeAccount(String accountId) {}

    static WeixinStateStore inMemory() {
        return new InMemoryWeixinStateStore();
    }
}
