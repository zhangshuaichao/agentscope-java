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
package io.agentscope.core.tool.builtin;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.Task;
import io.agentscope.core.state.TaskContextState;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内置任务列表工具，允许智能体维护当前会话的结构化待办列表，与 opencode 的 {@code todowrite} 对应。
 *
 * <p>【学习提示】整表替换降低了模型维护增量补丁的负担，但调用方必须带上仍需保留的项目。
 * 待办状态表达计划进度，不应把模型写入的“完成”状态直接等同于外部任务已通过验证。
 *
 * <p>单个 {@code todo_write} 工具采用<b>整表替换</b>语义：模型每次传入更新后的<i>完整</i>列表，工具据此重建 {@link AgentState#getTasksContext()}。此设计有意比逐项增删改更简单：模型无需推理 ID 或局部差异，最新完整列表始终是唯一事实来源，并由 {@code TaskReminderMiddleware} 每轮重新呈现。
 *
 * <p>列表自动持久化：{@code AgentState.tasksContext} 属于智能体状态，{@code ReActAgent} 每次调用后将其写入 {@code AgentStateStore}。
 *
 * <p>此工具独立于 Harness 的 {@code TaskTool}（{@code task_output} / {@code task_cancel} / {@code task_list}）；后者管理后台<i>子智能体</i>运行，属于另一个层次，此工具既不读取也不写入它。
 */
public class TodoTools {

    private static final String DESCRIPTION =
            """
            Create and maintain a structured task list for the current session. Tracks progress,
            organizes multi-step work, and surfaces status to the user. Pass the COMPLETE updated
            list every call — this tool replaces the whole list (it does not merge).

            ## When to use
            Use proactively when:
            - The task needs 3+ distinct steps or actions
            - The work is non-trivial and benefits from planning
            - The user gives multiple tasks (numbered or comma-separated) or asks for a todo list
            - New instructions arrive — capture them as todos
            - You start a task — mark it in_progress (only one at a time) before working
            - You finish a task — mark it completed and add any follow-ups you discovered

            ## When NOT to use
            Skip when the work is a single straightforward step (<3 trivial steps), or the request
            is purely informational/conversational, or tracking adds no value.

            ## States
            - pending     — not started
            - in_progress — actively working (exactly ONE at a time)
            - completed   — finished successfully

            ## Rules
            - Update status in real time; do not batch completions.
            - Mark completed only after the work is actually done, including any required
              verification — never based on intent.
            - Keep exactly one task in_progress while work remains.
            - If blocked or partial, keep the task in_progress and add a follow-up todo describing
              the blocker.
            - Items must be specific and actionable; break large work into smaller steps.
            - To drop a task that is no longer needed, simply omit it from the list.
            """;

    /**
     * 用 {@code todos} 替换会话任务列表。
     *
     * <p>{@code state} 由框架自动注入（{@code stateInjected = true}），不属于模型可见模式。
     */
    @Tool(
            name = "todo_write",
            description = DESCRIPTION,
            stateInjected = true,
            readOnly = false,
            concurrencySafe = false)
    public ToolResultBlock write(
            @ToolParam(
                            name = "todos",
                            description =
                                    "The COMPLETE updated todo list. Replaces the existing list"
                                            + " entirely.")
                    List<TodoItem> todos,
            AgentState state) {
        if (state == null) {
            return ToolResultBlock.error("agent state unavailable; cannot persist todo list.");
        }
        List<TodoItem> items = todos == null ? List.of() : todos;

        // 修改任何内容前，验证仅有一个 in_progress 的不变量（拒绝而不是
        // 降级）：模型负责保证恰有一个活动任务。
        int inProgress = 0;
        for (TodoItem item : items) {
            if (item == null) return ToolResultBlock.error("todos must not contain null items.");
            Task.State parsed = parseState(item.getStatus());
            if (parsed == null) {
                return ToolResultBlock.error(
                        "invalid status '"
                                + item.getStatus()
                                + "' for todo '"
                                + safe(item.getContent())
                                + "'. Allowed: pending, in_progress, completed.");
            }
            if (parsed == Task.State.IN_PROGRESS) {
                inProgress++;
            }
            if (item.getContent() == null || item.getContent().isBlank()) {
                return ToolResultBlock.error("every todo must have non-blank content.");
            }
        }
        if (inProgress > 1) {
            return ToolResultBlock.error(
                    "at most one task may be in_progress at a time, but "
                            + inProgress
                            + " were provided. Keep exactly one in_progress.");
        }

        TaskContextState ctx = state.getTasksContext();
        TaskContextState snapshot = ctx.snapshot();
        // 对内容匹配现有任务的项目，尽力保留 id 和 created_at，
        // 使整表重写后仍保有稳定标识符。
        Map<String, Task> byContent = new LinkedHashMap<>();
        for (Task existing : snapshot.getTasks()) {
            byContent.putIfAbsent(existing.getSubject(), existing);
        }

        List<Task> rebuilt = new ArrayList<>(items.size());
        for (TodoItem item : items) {
            Task.State parsed = parseState(item.getStatus());
            Task prior = byContent.get(item.getContent());
            Map<String, Object> metadata = new LinkedHashMap<>();
            if (prior != null && prior.getMetadata() != null) {
                metadata.putAll(prior.getMetadata());
            }
            String priority = normalizePriority(item.getPriority());
            if (priority != null) {
                metadata.put("priority", priority);
            }
            Task.Builder b =
                    Task.builder()
                            .subject(item.getContent())
                            .description(item.getContent())
                            .state(parsed)
                            .metadata(metadata.isEmpty() ? null : metadata);
            if (prior != null) {
                b.id(prior.getId()).createdAt(prior.getCreatedAt());
            }
            rebuilt.add(b.build());
        }

        ctx.replaceTasks(rebuilt, snapshot.getRevision());

        return ToolResultBlock.of(
                        TextBlock.builder().text(render(rebuilt)).build(),
                        Map.of(
                                "context.state_key",
                                "tasksContext",
                                "context.state_revision",
                                ctx.getRevision(),
                                "context.representation",
                                "full"))
                .withState(ToolResultState.SUCCESS);
    }

    /**
     * 渲染结构化更新回执的兼容入口。
     */
    public String todoWrite(List<TodoItem> todos, AgentState state) {
        return ((TextBlock) write(todos, state).getOutput().get(0)).getText();
    }

    public static String render(List<Task> tasks) {
        if (tasks.isEmpty()) {
            return "Todo list cleared (0 items).";
        }
        long open = tasks.stream().filter(t -> t.getState() != Task.State.COMPLETED).count();
        StringBuilder sb = new StringBuilder();
        sb.append(open).append(" open todo(s):\n");
        for (Task t : tasks) {
            sb.append(marker(t.getState())).append(' ').append(t.getSubject());
            Object priority = t.getMetadata() == null ? null : t.getMetadata().get("priority");
            if (priority != null) {
                sb.append(" (priority: ").append(priority).append(')');
            }
            sb.append('\n');
        }
        return sb.toString().stripTrailing();
    }

    static String marker(Task.State state) {
        return switch (state) {
            case COMPLETED -> "- [x]";
            case IN_PROGRESS -> "- [~]";
            case PENDING -> "- [ ]";
        };
    }

    private static Task.State parseState(String wire) {
        if (wire == null || wire.isBlank()) {
            return Task.State.PENDING;
        }
        try {
            return Task.State.fromWire(wire.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String normalizePriority(String priority) {
        if (priority == null || priority.isBlank()) {
            return null;
        }
        String p = priority.trim().toLowerCase();
        return switch (p) {
            case "high", "medium", "low" -> p;
            default -> null;
        };
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    /**
     * 模型提供的单个待办项。
     */
    public static final class TodoItem {
        private final String content;
        private final String status;
        private final String priority;

        @JsonCreator
        public TodoItem(
                @JsonProperty("content") String content,
                @JsonProperty("status") String status,
                @JsonProperty("priority") String priority) {
            this.content = content;
            this.status = status;
            this.priority = priority;
        }

        @JsonProperty("content")
        @JsonPropertyDescription("Brief, specific, actionable description of the task.")
        public String getContent() {
            return content;
        }

        @JsonProperty("status")
        @JsonPropertyDescription("One of: pending, in_progress, completed.")
        public String getStatus() {
            return status;
        }

        @JsonProperty("priority")
        @JsonPropertyDescription("Optional priority: high, medium, or low.")
        public String getPriority() {
            return priority;
        }
    }
}
