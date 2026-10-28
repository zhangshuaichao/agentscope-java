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
package io.agentscope.spring.boot.admin.service;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.spring.boot.admin.dto.AgentTaskView;
import io.agentscope.spring.boot.admin.dto.CompactRequest;
import io.agentscope.spring.boot.admin.dto.CompactResponse;
import io.agentscope.spring.boot.admin.dto.MessageView;
import io.agentscope.spring.boot.admin.dto.PlanModeView;
import io.agentscope.spring.boot.admin.properties.AdminProperties;
import io.agentscope.spring.boot.admin.registry.AgentRegistry;
import io.agentscope.spring.boot.admin.registry.AgentResolver;
import io.agentscope.spring.boot.admin.snapshot.AgentStateRestorer;
import io.agentscope.spring.boot.admin.snapshot.SnapshotStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 逐会话数据平面管理操作的业务逻辑。
 *
 * <p>REST 控制器只是薄层：解析请求、委托本类方法并序列化结果。阻塞 I/O（AgentStateStore 读取、JSON 序列化）通过 {@link Schedulers#boundedElastic()} 路径从请求线程移开；具体方法仍须沿调度链阅读。
 */
public final class SessionOperations {

    private final AgentRegistry registry;
    private final SummarizationStrategy summarizer;
    private final AdminProperties properties;
    private final SnapshotStore snapshots;

    public SessionOperations(
            AgentRegistry registry,
            SummarizationStrategy summarizer,
            AdminProperties properties,
            SnapshotStore snapshots) {
        this.registry = registry;
        this.summarizer = summarizer;
        this.properties = properties;
        this.snapshots = snapshots;
    }

    /**
 * 只读列出 {@link AgentStateStore#listSessionIds(String)} 已知会话 ID。
 */
    public Mono<List<String>> listSessions(AgentStateStore stateStore) {
        if (stateStore == null) {
            return Mono.just(List.of());
        }
        return Mono.fromCallable(
                        () -> {
                            Set<?> keys = stateStore.listSessionIds(null);
                            List<String> out = new ArrayList<>(keys.size());
                            for (Object k : keys) {
                                out.add(String.valueOf(k));
                            }
                            Collections.sort(out);
                            return out;
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
 * 返回已注册智能体的内存消息列表。
 */
    public Mono<List<MessageView>> listMessages(String agentIdOrSessionId) {
        return resolveReact(agentIdOrSessionId)
                .map(
                        react -> {
                            List<Msg> ctx = react.getAgentState().getContext();
                            List<MessageView> out = new ArrayList<>(ctx.size());
                            for (Msg msg : ctx) {
                                out.add(MessageView.of(msg));
                            }
                            return out;
                        });
    }

    /**
 * 将对话渲染为 Markdown 记录。
 */
    public Mono<String> exportMarkdown(String agentIdOrSessionId) {
        return resolveReact(agentIdOrSessionId)
                .map(
                        react -> {
                            AgentState state = react.getAgentState();
                            StringBuilder sb = new StringBuilder();
                            sb.append("# AgentStateStore ")
                                    .append(state.getSessionId())
                                    .append("\n\n");
                            if (state.getSummary() != null && !state.getSummary().isBlank()) {
                                sb.append("## Rolling summary\n\n")
                                        .append(state.getSummary())
                                        .append("\n\n");
                            }
                            sb.append("## Messages\n\n");
                            for (Msg msg : state.getContext()) {
                                String role =
                                        msg.getRole() == null
                                                ? "?"
                                                : msg.getRole().name().toLowerCase();
                                sb.append("### ")
                                        .append(role)
                                        .append(" — ")
                                        .append(msg.getName() == null ? "" : msg.getName())
                                        .append("\n\n");
                                String text;
                                try {
                                    text = msg.getTextContent();
                                } catch (RuntimeException e) {
                                    text = "";
                                }
                                sb.append(text == null ? "" : text).append("\n\n");
                            }
                            return sb.toString();
                        });
    }

    /**
 * 导出会话的完整 {@link AgentState} JSON。
 */
    public Mono<String> dumpStateJson(String agentIdOrSessionId) {
        return resolveReact(agentIdOrSessionId).map(react -> react.getAgentState().toJson());
    }

    /**
 * 协作式中断智能体。
 */
    public Mono<Void> abort(String agentIdOrSessionId) {
        return resolveAgent(agentIdOrSessionId).doOnNext(Agent::interrupt).then();
    }

    /**
     * 压缩会话：为较早上下文生成摘要，保留最后 {@code keepLast} 条消息，并通过智能体 {@link AgentStateStore} 持久化。
     *
     * <p>返回描述变更的指标。
     *
     * <p>【学习提示】不能按上游旧说明假设“摘要失败时上下文一定不变”。默认摘要策略将模型流错误转换为旧摘要的正常结果，此处随后仍合并摘要、截断上下文并保存。需要区分错误信号与回退值；本学习版仅说明现有行为，不修改它。
     */
    public Mono<CompactResponse> compact(String agentIdOrSessionId, CompactRequest request) {
        CompactRequest req = request == null ? CompactRequest.defaults() : request;
        int keepLast =
                req.keepLastMessages() == null
                        ? properties.getCompactKeepLastMessages()
                        : Math.max(0, req.keepLastMessages());
        boolean replace = req.replaceSummary() != null && req.replaceSummary();

        return resolveReact(agentIdOrSessionId)
                .flatMap(
                        react -> {
                            AgentState state = react.getAgentState();
                            List<Msg> context = state.contextMutable();
                            int before = context.size();
                            String previousSummary =
                                    state.getSummary() == null ? "" : state.getSummary();
                            int summaryBefore = previousSummary.length();
                            if (before <= keepLast) {
                                return Mono.just(
                                        new CompactResponse(
                                                state.getSessionId(),
                                                before,
                                                before,
                                                summaryBefore,
                                                summaryBefore));
                            }

                            List<Msg> toFold =
                                    new ArrayList<>(context.subList(0, before - keepLast));
                            // 修改实时状态之前先保存快照，以便撤销。
                            snapshots.push(state.getSessionId(), state.toJson());
                            return summarizer
                                    .summarize(react.getModel(), previousSummary, toFold)
                                    .defaultIfEmpty(previousSummary)
                                    .flatMap(
                                            newSummary -> {
                                                String merged =
                                                        replace || previousSummary.isBlank()
                                                                ? newSummary
                                                                : previousSummary
                                                                        + "\n\n"
                                                                        + newSummary;
                                                state.setSummary(merged);
                                                // 原地截断
                                                List<Msg> kept =
                                                        new ArrayList<>(
                                                                context.subList(
                                                                        before - keepLast, before));
                                                context.clear();
                                                context.addAll(kept);
                                                return persist(react, state)
                                                        .thenReturn(
                                                                new CompactResponse(
                                                                        state.getSessionId(),
                                                                        before,
                                                                        context.size(),
                                                                        summaryBefore,
                                                                        merged.length()));
                                            });
                        });
    }

    /**
     * 将智能体状态恢复到最近一次变更前保存的快照。没有撤销历史时返回空，不视为错误。
     */
    public Mono<UndoResult> undo(String agentIdOrSessionId) {
        return resolveReact(agentIdOrSessionId)
                .flatMap(
                        react -> {
                            String sessionId = react.getAgentState().getSessionId();
                            String currentJson = react.getAgentState().toJson();
                            Optional<String> snap = snapshots.undo(sessionId, currentJson);
                            if (snap.isEmpty()) {
                                return Mono.just(
                                        new UndoResult(
                                                sessionId,
                                                false,
                                                snapshots.undoDepth(sessionId),
                                                snapshots.redoDepth(sessionId)));
                            }
                            AgentStateRestorer.restore(react.getAgentState(), snap.get());
                            return persist(react, react.getAgentState())
                                    .thenReturn(
                                            new UndoResult(
                                                    sessionId,
                                                    true,
                                                    snapshots.undoDepth(sessionId),
                                                    snapshots.redoDepth(sessionId)));
                        });
    }

    /**
 * 撤回先前的 {@link #undo(String)}。
 */
    public Mono<UndoResult> redo(String agentIdOrSessionId) {
        return resolveReact(agentIdOrSessionId)
                .flatMap(
                        react -> {
                            String sessionId = react.getAgentState().getSessionId();
                            String currentJson = react.getAgentState().toJson();
                            Optional<String> snap = snapshots.redo(sessionId, currentJson);
                            if (snap.isEmpty()) {
                                return Mono.just(
                                        new UndoResult(
                                                sessionId,
                                                false,
                                                snapshots.undoDepth(sessionId),
                                                snapshots.redoDepth(sessionId)));
                            }
                            AgentStateRestorer.restore(react.getAgentState(), snap.get());
                            return persist(react, react.getAgentState())
                                    .thenReturn(
                                            new UndoResult(
                                                    sessionId,
                                                    true,
                                                    snapshots.undoDepth(sessionId),
                                                    snapshots.redoDepth(sessionId)));
                        });
    }

    /**
 * 撤销／重做调用的结果。
 */
    public record UndoResult(String sessionId, boolean restored, int undoDepth, int redoDepth) {}

    // ------------------------------------------------------------------------
    // 计划模式
    // ------------------------------------------------------------------------

    private static final String PLAN_MIDDLEWARE_CLASS =
            "io.agentscope.harness.agent.middleware.PlanModeMiddleware";

    /**
 * 读取会话当前计划模式状态。
 */
    public Mono<PlanModeView> planState(String agentIdOrSessionId) {
        return resolveReact(agentIdOrSessionId)
                .map(
                        react ->
                                PlanModeView.of(
                                        react.getAgentState().getSessionId(),
                                        react.getAgentState().getPlanModeContext(),
                                        hasPlanModeMiddleware(react)));
    }

    /**
     * 使智能体进入计划模式，仅支持 {@link HarnessAgent}。执行只读约束的 {@code PlanModeManager} 由 HarnessAgent.Builder 配置，仅修改裸 ReActAgent 的 AgentState 标记会悄然绕过执行链。
     */
    public Mono<PlanModeView> enterPlanMode(String agentIdOrSessionId) {
        return resolveAgent(agentIdOrSessionId)
                .flatMap(
                        agent -> {
                            if (!(agent instanceof HarnessAgent harness)) {
                                return Mono.error(planModeUnsupported(agent));
                            }
                            ReActAgent delegate = harness.getDelegate();
                            AgentState state = delegate.getAgentState();
                            // 切换标记前先保存撤销快照。
                            snapshots.push(state.getSessionId(), state.toJson());
                            harness.enterPlanMode(null, state.getSessionId());
                            return persist(delegate, state)
                                    .thenReturn(
                                            PlanModeView.of(
                                                    state.getSessionId(),
                                                    state.getPlanModeContext(),
                                                    hasPlanModeMiddleware(delegate)));
                        });
    }

    /**
 * 退出计划模式；仅支持 HarnessAgent 的限制参见 {@link #enterPlanMode(String)}。
 */
    public Mono<PlanModeView> exitPlanMode(String agentIdOrSessionId) {
        return resolveAgent(agentIdOrSessionId)
                .flatMap(
                        agent -> {
                            if (!(agent instanceof HarnessAgent harness)) {
                                return Mono.error(planModeUnsupported(agent));
                            }
                            ReActAgent delegate = harness.getDelegate();
                            AgentState state = delegate.getAgentState();
                            snapshots.push(state.getSessionId(), state.toJson());
                            harness.exitPlanMode(null, state.getSessionId());
                            return persist(delegate, state)
                                    .thenReturn(
                                            PlanModeView.of(
                                                    state.getSessionId(),
                                                    state.getPlanModeContext(),
                                                    hasPlanModeMiddleware(delegate)));
                        });
    }

    private static IllegalStateException planModeUnsupported(Agent agent) {
        return new IllegalStateException(
                "Plan-mode admin op requires HarnessAgent, got "
                        + agent.getClass().getSimpleName());
    }

    // ------------------------------------------------------------------------
    // 逐会话 AgentState 内任务列表（TodoWrite 风格工作项）
    // ------------------------------------------------------------------------

    /**
 * 列出会话每个 {@link io.agentscope.core.state.AgentState} 的任务记录。
 */
    public Mono<List<AgentTaskView>> listAgentTasks(String agentIdOrSessionId) {
        return resolveReact(agentIdOrSessionId)
                .map(
                        react -> {
                            var tasksCtx = react.getAgentState().getTasksContext();
                            if (tasksCtx == null) return List.<AgentTaskView>of();
                            List<AgentTaskView> out = new ArrayList<>();
                            for (var t : tasksCtx.getTasks()) {
                                AgentTaskView view = AgentTaskView.of(t);
                                if (view != null) out.add(view);
                            }
                            return out;
                        });
    }

    private static boolean hasPlanModeMiddleware(ReActAgent react) {
        try {
            return react.getMiddlewares().stream()
                    .anyMatch(m -> PLAN_MIDDLEWARE_CLASS.equals(m.getClass().getName()));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    // ------------------------------------------------------------------------
    // 内部辅助方法
    // ------------------------------------------------------------------------

    private Mono<Agent> resolveAgent(String agentIdOrSessionId) {
        return Mono.defer(
                () -> {
                    // 先尝试按 agentId 直接查找。
                    return registry.find(agentIdOrSessionId)
                            .map(Mono::just)
                            .orElseGet(
                                    () -> {
                                        // 回退：扫描已注册智能体，在其 AgentState
                                        // 中寻找匹配的 sessionId，让调用方无需
                                        // 区分自己持有的是 agentId 还是 sessionId。
                                        // 解包 HarnessAgent，让被包装的智能体也能
                                        // 通过 sessionId 被发现。
                                        for (Agent agent : registry.list()) {
                                            ReActAgent react =
                                                    AgentResolver.unwrapReActAgent(agent);
                                            if (react == null) continue;
                                            AgentState state = react.getAgentState();
                                            if (state != null
                                                    && agentIdOrSessionId.equals(
                                                            state.getSessionId())) {
                                                return Mono.just(agent);
                                            }
                                        }
                                        return Mono.error(
                                                new NoSuchElementException(
                                                        "No agent or session found for id="
                                                                + agentIdOrSessionId));
                                    });
                });
    }

    /**
     * 解析给定 ID 对应的 {@link ReActAgent}，按需将 {@link HarnessAgent} 解包为委托对象。管理只读访问器（模型、AgentState、中间件、会话、sessionKey）定义于委托对象，因此经解包实例读取与经 Harness 包装器读取语义相同。
     */
    private Mono<ReActAgent> resolveReact(String agentIdOrSessionId) {
        return resolveAgent(agentIdOrSessionId)
                .flatMap(
                        a -> {
                            ReActAgent react = AgentResolver.unwrapReActAgent(a);
                            if (react != null) {
                                return Mono.just(react);
                            }
                            return Mono.error(
                                    new IllegalStateException(
                                            "Admin op requires ReActAgent or HarnessAgent, found: "
                                                    + a.getClass().getSimpleName()));
                        });
    }

    private Mono<Void> persist(ReActAgent react, AgentState state) {
        AgentStateStore stateStore = react.getStateStore();
        if (stateStore == null) {
            return Mono.empty();
        }
        return Mono.fromRunnable(
                        () ->
                                stateStore.save(
                                        state.getUserId(),
                                        react.getDefaultSessionId(),
                                        "agent_state",
                                        state))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }
}
