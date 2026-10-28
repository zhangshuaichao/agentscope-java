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
package io.agentscope.extensions.controlplane.adapter;

import io.agentscope.core.agent.Agent;
import io.agentscope.extensions.controlplane.model.Inventory;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 可选的 Harness／应用专用钩子，保持在 {@code agentscope-extensions-controlplane} 核心适配边界之外，使扩展无需通过这些钩子直接依赖 {@code agentscope-harness}。宿主应用（例如 paw）在 {@link AgentScopeAdapter} bean 创建后接线。
 */
public interface AgentRuntimeSource {

    default List<Inventory.SubagentInfo> listSubagents(Agent agent) {
        return List.of();
    }

    default List<Inventory.WorkspaceInfo> listWorkspaces(Agent agent) {
        return List.of();
    }

    default List<Map<String, Object>> listSubagentTasks(
            Agent agent, String sessionId, String userId) {
        return List.of();
    }

    default boolean cancelSubagentTask(
            Agent agent, String sessionId, String userId, String taskId) {
        return false;
    }

    /**
 * 补充 Definition 快照（{@code GET /agentscope/info} → {@code agentConfig}）。
 */
    default void enrichAgentConfig(Agent agent, Map<String, Object> agentConfig) {}

    /**
 * Context {@code frameworkState} 的可选计划文件摘录。
 */
    default Optional<String> readPlanExcerpt(
            Agent agent, String sessionId, String userId, String planFile) {
        return Optional.empty();
    }

    /**
     * 为会话进入／退出计划模式。已处理时返回 {@code true}，包括本就未激活时的空操作退出。
     */
    default boolean setPlanMode(Agent agent, String sessionId, String userId, boolean active) {
        return false;
    }
}
