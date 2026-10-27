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

import java.util.Optional;

/**
 * 以 {@code subagentId} 为键的已公开子智能体持久化注册表。
 *
 * <p>将子智能体的用户句柄与单一进程解耦：内存中的 {@link HarnessGateway}
 * 保留活跃智能体缓存以加速同节点访问，此注册表则持久化 {@link SubagentRecord} 配方，
 * 使任意节点（或重启后的原节点）都可解析句柄并重建智能体。
 *
 * <p>实现：
 *
 * <ul>
 *   <li>{@link InMemorySubagentRegistry} — 默认单进程实现，与旧行为一致。
 *   <li>{@link StoreBackedSubagentRegistry} — 由分布式
 *       {@link io.agentscope.harness.agent.filesystem.remote.store.BaseStore} 支持，
 *       通过 {@link io.agentscope.harness.agent.DistributedStore#baseStore()} 接入 Redis／OSS／MySQL。
 * </ul>
 *
 * <p>实现必须线程安全。
 */
public interface SubagentRegistry {

    /**
     * 持久化公开记录。按 {@link SubagentRecord#subagentId()} 幂等：
     * 重复注册同一 ID 会覆盖旧记录。
     *
     * @param record 要持久化的记录（必须带非 null 的 {@code subagentId}）
     */
    void register(SubagentRecord record);

    /**
     * 解析子智能体句柄。实现应将过期记录（见 {@link SubagentRecord#isExpired}）视为不存在，
     * 并可惰性移除。
     *
     * @param subagentId 要解析的句柄
     * @return 对应记录；未知或过期时为 {@link Optional#empty()}
     */
    Optional<SubagentRecord> find(String subagentId);

    /**
     * 移除单条公开记录。未知 ID 不执行任何操作。
     */
    void revoke(String subagentId);

    /**
     * 移除关联指定父会话的全部公开记录，用于父对话结束时清理。
     * 无法按父会话枚举的实现可以不执行操作。
     *
     * @param parentSessionId 要撤销其已公开子智能体的父会话
     */
    default void revokeByParentSession(String parentSessionId) {
        // 可选能力
    }
}
