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
package io.agentscope.spring.boot.admin.dto;

import io.agentscope.core.state.Task;
import java.util.List;
import java.util.Map;

/**
 * {@link Task} 的只读传输视图：它是每个 {@link io.agentscope.core.state.AgentState} 内的任务记录，即智能体自行维护的 TodoWrite 风格工作项列表。
 *
 * <p>不同于 {@link SubagentTaskView}，后者描述通过 {@code TaskRepository} 分发给子智能体的 {@code BackgroundTask}。
 */
public record AgentTaskView(
        String id,
        String subject,
        String description,
        String state,
        String owner,
        String createdAt,
        List<String> blocks,
        List<String> blockedBy,
        Map<String, Object> metadata) {

    public static AgentTaskView of(Task t) {
        if (t == null) return null;
        return new AgentTaskView(
                t.getId(),
                t.getSubject(),
                t.getDescription(),
                t.getState() == null ? null : t.getState().name(),
                t.getOwner(),
                t.getCreatedAt(),
                t.getBlocks(),
                t.getBlockedBy(),
                t.getMetadata());
    }
}
