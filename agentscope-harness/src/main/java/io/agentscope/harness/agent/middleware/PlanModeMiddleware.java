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
package io.agentscope.harness.agent.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.ToolResultMessageBuilder;
import io.agentscope.harness.agent.context.ContextItem;
import io.agentscope.harness.agent.context.WorkspaceContextMaterials;
import io.agentscope.harness.agent.tool.PlanModeTools;
import io.agentscope.harness.agent.workspace.plan.PlanModeManager;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 强制执行规划模式：当 {@code AgentState.planModeContext.planActive} 为 {@code true} 时，
 * 拒绝修改型工具调用，使智能体只能读取、调研和起草计划。
 *
 * <p>规则确定地在 {@link #onActing} 中执行：仅允许只读工具或规划控制工具
 * （{@code plan_enter} / {@code plan_write} / {@code plan_exit} / {@code todo_write}）；
 * 其余调用不执行，而是得到写入上下文并流式发出的合成 {@code DENIED} 工具结果。
 * 这里刻意不复用 {@code PermissionMode.EXPLORE}：该模式在
 * {@code PermissionEngine} 构造时被不可变地快照，无法在运行时切换，
 * 而规划模式必须支持动态切换。
 *
 * <p>{@link #onSystemPrompt} 为最终上下文构建器收集模式指令。
 * {@link #onModelCall} 在同一次调用中模式发生变化时刷新指令。
 *
 * <p>【学习提示】提示词告诉模型应该怎么做，执行门控则决定实际能不能做。
 * 两者职责不同；不能只靠提示词实现工具权限边界。
 */
public class PlanModeMiddleware implements HarnessRuntimeMiddleware {

    private static final Set<String> ALWAYS_ALLOWED =
            Set.of(
                    PlanModeTools.PLAN_ENTER,
                    PlanModeTools.PLAN_WRITE,
                    PlanModeTools.PLAN_EXIT,
                    "todo_write",
                    "agent_spawn",
                    "agent_send",
                    "agent_list",
                    "task_output",
                    "task_list");

    private static final String DENY_MESSAGE =
            "Blocked: you are in PLAN mode (read-only). You may investigate and run read-only"
                    + " tools, record your plan with plan_write, and call plan_exit when ready to"
                    + " execute. Do not modify files or run mutating commands until the plan is"
                    + " approved.";

    private static final String PLAN_BANNER_TEMPLATE =
            """

            PLAN MODE is active (read-only). Plan file: %s
            Investigate the problem and draft a plan, but do NOT modify files, run mutating commands,
            or otherwise change state. Record your plan with the plan_write tool. When the plan is
            complete, call plan_exit to ask the user for approval; only after approval will you return
            to BUILD mode and be able to make changes.
            ACT, do not just narrate: when you decide to record or finish the plan, call plan_write
            (or plan_exit) in the SAME step — never say you "will write the plan" without actually
            calling the tool, and never claim a plan exists unless you have called plan_write.
            If you cannot produce a concrete plan because you lack information (for example the system
            you were asked to work on is not present in this workspace), STOP and ask the user one
            specific clarifying question instead of inventing a plan or assuming details.
            """;

    private static final String BUILD_MODE_PLAN_HINT =
            "\n\nYou have switched from PLAN to BUILD mode; the read-only"
                    + " restriction is lifted. A plan reference exists at %s — this path alone is"
                    + " not proof of approval. Read it for the"
                    + " details, then EXECUTE it step by step until the task is complete. Do NOT"
                    + " stop after merely producing the plan. If a todo list is available, capture"
                    + " the plan's steps with the todo_write tool and keep exactly one task"
                    + " in_progress as you work through them.";

    private static final String PLAN_EXTRA_TOOLS_HINT =
            "\n\nThe following tool(s) are additionally available during PLAN"
                    + " mode for read-only investigation: %s. Use them ONLY to read/inspect (e.g."
                    + " cat, ls, grep, git log/diff/show/status). Do NOT run mutating commands"
                    + " (file writes, installs, git commit, rm, mv, network side effects, etc.)"
                    + " until the plan is approved.";

    private final PlanModeManager manager;
    private final Predicate<String> readOnlyResolver;
    private final Set<String> additionalAllowed;

    /**
     * @param manager 共享的规划模式状态／文件协调器
     * @param readOnlyResolver 按工具名称判断其是否只读；用于决定规划模式下允许哪些调用
     */
    public PlanModeMiddleware(PlanModeManager manager, Predicate<String> readOnlyResolver) {
        this(manager, readOnlyResolver, Set.of());
    }

    /**
     * @param manager 共享的规划模式状态／文件协调器
     * @param readOnlyResolver 按工具名称判断其是否只读；用于决定规划模式下允许哪些调用
     * @param additionalAllowed 规划模式下额外允许的非只读工具名称（显式选择的例外通道，
     *     例如用于 shell 调研的 {@code execute}）；规划提示会要求模型仅以只读方式使用这些工具
     */
    public PlanModeMiddleware(
            PlanModeManager manager,
            Predicate<String> readOnlyResolver,
            Set<String> additionalAllowed) {
        this.manager = manager;
        this.readOnlyResolver = readOnlyResolver != null ? readOnlyResolver : name -> false;
        this.additionalAllowed =
                additionalAllowed == null || additionalAllowed.isEmpty()
                        ? Set.of()
                        : new LinkedHashSet<>(additionalAllowed);
    }

    /** 仅声明所需扩展点；子类若重写更多钩子，必须扩展此集合。 */
    @Override
    public Set<ExtensionPoint> activePoints() {
        return EnumSet.of(
                ExtensionPoint.ON_SYSTEM_PROMPT,
                ExtensionPoint.ON_ACTING,
                ExtensionPoint.ON_MODEL_CALL);
    }

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
        return Mono.fromSupplier(
                () -> {
                    RuntimeContext rc = ctx == null ? RuntimeContext.empty() : ctx;
                    WorkspaceContextMaterials.register(
                            rc,
                            List.of(
                                    ContextItem.instruction(
                                            "mode_rules",
                                            "harness:plan-mode",
                                            modeInstructions(agent, rc))));
                    return currentPrompt == null ? "" : currentPrompt;
                });
    }

    /** 在每个模型调用边界刷新模式规则，包括同一次智能体调用中的模式切换。 */
    @Override
    public Flux<AgentEvent> onModelCall(
            Agent agent,
            RuntimeContext ctx,
            ModelCallInput input,
            Function<ModelCallInput, Flux<AgentEvent>> next) {
        return onSystemPrompt(agent, ctx, "").flatMapMany(ignored -> next.apply(input));
    }

    private String modeInstructions(Agent agent, RuntimeContext ctx) {
        AgentState state = RuntimeContext.resolveAgentState(ctx, agent);

        if (manager.isPlanActive(state)) {
            String path = manager.planFilePath(state);
            String banner = PLAN_BANNER_TEMPLATE.formatted(path);
            if (!additionalAllowed.isEmpty()) {
                String tools = additionalAllowed.stream().collect(Collectors.joining(", "));
                banner += PLAN_EXTRA_TOOLS_HINT.formatted(tools);
            }
            return banner;
        }

        // BUILD 模式：如果之前写过计划文件，则显示其路径，让模型在
        // 上下文压缩后可以重读，而不必记住最初的工具调用。
        String planFile = state != null ? state.getPlanModeContext().getCurrentPlanFile() : null;
        if (planFile != null && !planFile.isBlank()) {
            return BUILD_MODE_PLAN_HINT.formatted(planFile);
        }
        return "";
    }

    @Override
    public Flux<AgentEvent> onActing(
            Agent agent,
            RuntimeContext ctx,
            ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        AgentState state = RuntimeContext.resolveAgentState(ctx, agent);
        if (!manager.isPlanActive(state) || input.toolCalls() == null) {
            return next.apply(input);
        }

        List<ToolUseBlock> allowed = new ArrayList<>();
        List<ToolUseBlock> denied = new ArrayList<>();
        for (ToolUseBlock call : input.toolCalls()) {
            if (isPermitted(call.getName())) {
                allowed.add(call);
            } else {
                denied.add(call);
            }
        }

        if (denied.isEmpty()) {
            return next.apply(input);
        }

        String replyId = state.getReplyId();
        String agentName = agent.getName();

        // 延迟到订阅时执行，确保上下文修改与事件只运行一次。
        Flux<AgentEvent> deniedFlux =
                Flux.defer(
                        () -> {
                            List<AgentEvent> events = new ArrayList<>();
                            for (ToolUseBlock call : denied) {
                                ToolResultBlock result =
                                        ToolResultBlock.text(DENY_MESSAGE)
                                                .withIdAndName(call.getId(), call.getName())
                                                .withState(ToolResultState.DENIED);
                                Msg msg =
                                        ToolResultMessageBuilder.buildToolResultMsg(
                                                result, call, agentName);
                                state.contextMutable().add(msg);
                                events.add(
                                        new ToolResultStartEvent(
                                                replyId, call.getId(), call.getName()));
                                events.add(
                                        new ToolResultTextDeltaEvent(
                                                replyId,
                                                call.getId(),
                                                call.getName(),
                                                DENY_MESSAGE));
                                events.add(
                                        new ToolResultEndEvent(
                                                replyId,
                                                call.getId(),
                                                call.getName(),
                                                ToolResultState.DENIED));
                            }
                            return Flux.fromIterable(events);
                        });

        if (allowed.isEmpty()) {
            return deniedFlux;
        }
        return deniedFlux.concatWith(next.apply(new ActingInput(allowed)));
    }

    private boolean isPermitted(String toolName) {
        if (toolName == null) {
            return false;
        }
        return ALWAYS_ALLOWED.contains(toolName)
                || additionalAllowed.contains(toolName)
                || readOnlyResolver.test(toolName);
    }
}
