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
package io.agentscope.harness.agent.tool;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.harness.agent.workspace.plan.PlanModeManager;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

/**
 * 驱动计划模式的三个模型侧工具，共享一个 {@link PlanModeManager}。
 * <ul>
 *   <li>{@code plan_enter}：切换到只读计划模式，在修改之前先调查和设计。
 *   <li>{@code plan_write}：创建／覆盖 Markdown 计划蓝图（计划模式中允许）。
 *   <li>{@code plan_exit}：请求离开计划模式并开始执行。由 {@code ASK} 自检控制，
 *       用户通过现有权限 HITL 流程确认计划后，智能体才恢复写权限。
 * </ul>
 *
 * <p>{@code PlanModeMiddleware} 始终允许 {@code plan_enter} / {@code plan_write} /
 * {@code plan_exit}；计划模式启用时拒绝其他所有修改性工具。
 */
public final class PlanModeTools {

    /**
     * {@code PlanModeMiddleware} 始终允许的工具名，即使在计划模式中也允许。
     */
    public static final String PLAN_ENTER = "plan_enter";

    public static final String PLAN_WRITE = "plan_write";
    public static final String PLAN_EXIT = "plan_exit";

    private PlanModeTools() {}

    private static AgentState stateOf(ToolCallParam param) {
        return RuntimeContext.resolveAgentState(param.getRuntimeContext(), param.getAgent());
    }

    private static ToolResultBlock result(ToolCallParam param, String text) {
        return ToolResultBlock.text(text)
                .withIdAndName(param.getToolUseBlock().getId(), param.getToolUseBlock().getName());
    }

    /**
     * {@code plan_enter}：进入只读计划模式。
     */
    public static final class PlanEnterTool extends ToolBase {
        private final PlanModeManager manager;

        public PlanEnterTool(PlanModeManager manager) {
            super(
                    ToolBase.builder()
                            .name(PLAN_ENTER)
                            .description(
                                    "Enter PLAN mode: a read-only phase for investigating the"
                                        + " codebase and designing an approach before making any"
                                        + " changes. While in plan mode you cannot edit files or"
                                        + " run mutating commands; use plan_write to record your"
                                        + " plan and plan_exit when you are ready to execute. Use"
                                        + " this for non-trivial, multi-step, or ambiguous tasks.")
                            .inputSchema(Map.of("type", "object", "properties", Map.of()))
                            .readOnly(false)
                            .concurrencySafe(false));
            this.manager = manager;
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            AgentState state = stateOf(param);
            if (state == null) {
                return Mono.just(result(param, "Error: agent state unavailable."));
            }
            String path = manager.enter(state);
            return Mono.just(
                    result(
                            param,
                            "Entered PLAN mode (read-only). Investigate freely, then call"
                                    + " plan_write to record your plan at \""
                                    + path
                                    + "\". When the plan is ready, call plan_exit to request"
                                    + " approval and begin executing."));
        }
    }

    /**
     * {@code plan_write}：创建／覆盖 Markdown 计划文件。
     */
    public static final class PlanWriteTool extends ToolBase {
        private final PlanModeManager manager;

        public PlanWriteTool(PlanModeManager manager) {
            super(
                    ToolBase.builder()
                            .name(PLAN_WRITE)
                            .description(
                                    "Create or overwrite the current plan as a markdown document."
                                        + " Pass the COMPLETE plan content; it replaces the file."
                                        + " Use clear sections (goal, steps, risks, verification).")
                            .inputSchema(
                                    Map.of(
                                            "type",
                                            "object",
                                            "properties",
                                            Map.of(
                                                    "content",
                                                    Map.of(
                                                            "type",
                                                            "string",
                                                            "description",
                                                            "The full markdown plan content.")),
                                            "required",
                                            List.of("content")))
                            .readOnly(false)
                            .concurrencySafe(false));
            this.manager = manager;
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            AgentState state = stateOf(param);
            if (state == null) {
                return Mono.just(result(param, "Error: agent state unavailable."));
            }
            Object content = param.getInput().get("content");
            String text = content == null ? "" : content.toString();
            RuntimeContext rc = param.getRuntimeContext();
            String path = manager.writePlan(rc, state, text);
            return Mono.just(result(param, "Plan saved to \"" + path + "\"."));
        }
    }

    /**
     * {@code plan_exit}：请求批准，然后离开计划模式进入 BUILD。
     */
    public static final class PlanExitTool extends ToolBase {
        private final PlanModeManager manager;

        public PlanExitTool(PlanModeManager manager) {
            super(
                    ToolBase.builder()
                            .name(PLAN_EXIT)
                            .description(
                                    "Finish planning and request permission to start executing the"
                                        + " plan. This pauses for the user to approve your plan. On"
                                        + " approval you return to BUILD mode and may modify files;"
                                        + " on rejection you stay in PLAN mode and should revise.")
                            .inputSchema(
                                    Map.of(
                                            "type",
                                            "object",
                                            "properties",
                                            Map.of(
                                                    "summary",
                                                    Map.of(
                                                            "type",
                                                            "string",
                                                            "description",
                                                            "Optional short summary of the plan for"
                                                                    + " the user to approve."))))
                            .readOnly(false)
                            .concurrencySafe(false));
            this.manager = manager;
        }

        /**
         * 始终返回 ASK：退出计划模式是需要用户确认的明确交接。
         * 即使未配置权限规则，轻量级权限路径也遵循显式 ASK 自检，因此开箱即用。
         */
        @Override
        public Mono<PermissionDecision> checkPermissions(
                Map<String, Object> toolInput, PermissionContextState context) {
            return Mono.just(
                    PermissionDecision.ask(
                            "The agent wants to finish planning and start executing. Approve to"
                                    + " continue in BUILD mode, or reject to keep planning."));
        }

        /**
         * 仅在用户批准上面的 ASK 后执行。
         */
        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            AgentState state = stateOf(param);
            if (state == null) {
                return Mono.just(result(param, "Error: agent state unavailable."));
            }
            // 【学习提示】写权限恢复发生在工具真正执行时；前面的 checkPermissions
            // 负责提出 ASK，权限管线批准后才应调用此方法。不要只靠提示文本模拟审批。
            manager.exit(state);
            return Mono.just(
                    result(
                            param,
                            "Plan approved. You are now in BUILD mode and may modify files and run"
                                + " commands. Start executing the plan now. Seed your task list"
                                + " with todo_write (one item per plan step), keep exactly one task"
                                + " in_progress, and update it as you go."));
        }
    }
}
