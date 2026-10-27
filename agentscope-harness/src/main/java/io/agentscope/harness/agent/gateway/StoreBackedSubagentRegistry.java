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
package io.agentscope.harness.agent.gateway;

import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 由 {@link BaseStore} 支持的分布式 {@link SubagentRegistry}。
 * 存储由 {@link io.agentscope.harness.agent.DistributedStore} 提供（Redis／OSS／MySQL），
 * 因此公开记录对每个节点可见，任意副本都能解析 {@code subagentId} 并重建子智能体。
 *
 * <p>记录位于命名空间 {@code ["subagents", "exposed"]}，以 {@code subagentId} 为键。
 * TTL 在 {@link #find} 时惰性执行：过期记录会被删除并视为不存在。
 *
 * <p>此注册表有意只保存路由／身份元数据。子智能体可变对话状态的并发安全
 * 由分布式 {@link io.agentscope.core.state.AgentStateStore} 负责，
 * 需要显式乐观保护时使用 {@link BaseStore#putIfVersion}，而不是依赖此注册表。
 */
public final class StoreBackedSubagentRegistry implements SubagentRegistry {

    private static final Logger log = LoggerFactory.getLogger(StoreBackedSubagentRegistry.class);

    private static final List<String> NAMESPACE = List.of("subagents", "exposed");
    private static final int SCAN_PAGE_SIZE = 1000;

    private final BaseStore store;

    public StoreBackedSubagentRegistry(BaseStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override
    public void register(SubagentRecord record) {
        if (record == null || record.subagentId() == null) {
            return;
        }
        try {
            store.put(NAMESPACE, record.subagentId(), record.toMap());
        } catch (RuntimeException e) {
            log.warn(
                    "Failed to persist exposed-subagent record {}: {}",
                    record.subagentId(),
                    e.getMessage());
        }
    }

    @Override
    public Optional<SubagentRecord> find(String subagentId) {
        if (subagentId == null) {
            return Optional.empty();
        }
        StoreItem item;
        try {
            item = store.get(NAMESPACE, subagentId);
        } catch (RuntimeException e) {
            log.warn("Failed to read exposed-subagent record {}: {}", subagentId, e.getMessage());
            return Optional.empty();
        }
        if (item == null || item.value() == null) {
            return Optional.empty();
        }
        SubagentRecord record = SubagentRecord.fromMap(item.value());
        if (record == null) {
            return Optional.empty();
        }
        if (record.isExpired(Instant.now())) {
            revoke(subagentId);
            return Optional.empty();
        }
        return Optional.of(record);
    }

    @Override
    public void revoke(String subagentId) {
        if (subagentId == null) {
            return;
        }
        try {
            store.delete(NAMESPACE, subagentId);
        } catch (RuntimeException e) {
            log.warn("Failed to revoke exposed-subagent record {}: {}", subagentId, e.getMessage());
        }
    }

    @Override
    public void revokeByParentSession(String parentSessionId) {
        if (parentSessionId == null) {
            return;
        }
        try {
            List<StoreItem> items = store.search(NAMESPACE, SCAN_PAGE_SIZE, 0);
            if (items == null) {
                return;
            }
            for (StoreItem item : items) {
                SubagentRecord r = SubagentRecord.fromMap(item.value());
                if (r != null && parentSessionId.equals(r.parentSessionId())) {
                    revoke(r.subagentId());
                }
            }
        } catch (RuntimeException e) {
            log.warn(
                    "Failed to revoke exposed subagents for parent session {}: {}",
                    parentSessionId,
                    e.getMessage());
        }
    }
}
