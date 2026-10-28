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

import io.agentscope.harness.agent.subagent.task.BackgroundTask;
import java.time.Instant;

/**
 * 分发给子智能体的 {@link BackgroundTask} 传输视图，不同于 {@link AgentTaskView} 所表示的 {@code AgentState} 内 TodoWrite 风格记录。
 *
 * @param taskId 后端分配的任务标识
 * @param subagentId 执行任务的子智能体类型
 * @param status {@link io.agentscope.harness.agent.subagent.task.TaskStatus} 名称之一
 * @param createdAt 任务提交时间
 * @param lastCheckedAt 最近状态读取时间
 * @param completed 便捷完成标记
 * @param resultPreview {@code completed} 时结果前 500 个字符，否则为 null
 * @param errorMessage 任务失败时非 null
 */
public record SubagentTaskView(
        String taskId,
        String subagentId,
        String status,
        Instant createdAt,
        Instant lastCheckedAt,
        boolean completed,
        String resultPreview,
        String errorMessage) {

    public static SubagentTaskView of(BackgroundTask t) {
        if (t == null) return null;
        String preview = null;
        String error = null;
        if (t.isCompleted()) {
            String r = t.getResult();
            if (r != null && !r.isEmpty()) {
                preview = r.length() > 500 ? r.substring(0, 500) + "…" : r;
            }
            Exception e = t.getError();
            if (e != null) {
                error = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
        }
        return new SubagentTaskView(
                t.getTaskId(),
                t.getAgentId(),
                t.getTaskStatus() == null ? "UNKNOWN" : t.getTaskStatus().name(),
                t.getCreatedAt(),
                t.getLastCheckedAt(),
                t.isCompleted(),
                preview,
                error);
    }
}
