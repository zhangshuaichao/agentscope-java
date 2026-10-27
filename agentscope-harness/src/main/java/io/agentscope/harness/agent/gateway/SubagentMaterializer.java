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

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import java.util.Optional;

/**
 * 按类型 ID 重建子智能体 {@link Agent} 实例。
 * {@link HarnessGateway} 用它在没有活跃实例的节点上恢复已公开子智能体
 * （重启后，或请求路由到其他副本时）。
 *
 * <p>通常由 {@code DefaultAgentManager::createAgentIfPresent} 支持。
 * 调用重建智能体时传入 {@link SubagentRecord} 携带的 {@code sessionId}，
 * 由分布式 {@link io.agentscope.core.state.AgentStateStore} 恢复之前的对话历史。
 */
@FunctionalInterface
public interface SubagentMaterializer {

    /**
     * 重建指定类型的智能体。
     *
     * @param agentId 子智能体类型标识
     * @param parentRc 至少携带目标会话 ID 的运行上下文
     * @return 重建的智能体；类型未知或不可创建时为 {@link Optional#empty()}
     */
    Optional<Agent> materialize(String agentId, RuntimeContext parentRc);
}
