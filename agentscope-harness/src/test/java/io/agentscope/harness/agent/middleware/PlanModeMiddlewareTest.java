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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.context.ContextRenderer;
import io.agentscope.harness.agent.context.WorkspaceContextMaterials;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.skill.runtime.SkillLoadTool;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import io.agentscope.harness.agent.workspace.plan.PlanModeManager;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class PlanModeMiddlewareTest {
    private static String renderPrompt(PlanModeMiddleware middleware, Agent agent, String base) {
        RuntimeContext rc = RuntimeContext.empty();
        String result = middleware.onSystemPrompt(agent, rc, base).block();
        assertEquals(base, result);
        return result + ContextRenderer.render(rc.get(WorkspaceContextMaterials.class).items());
    }

    private static final Predicate<String> READ_ONLY = name -> name.equals("read_file");

    private static ToolUseBlock call(String id, String name) {
        return ToolUseBlock.builder().id(id).name(name).input(java.util.Map.of()).build();
    }

    private final List<WorkspaceManager> openManagers = new ArrayList<>();

    @AfterEach
    void closeOpenManagers() {
        // 释放 SQLite 句柄，使 Windows 上的 @TempDir 能删除工作区。
        for (WorkspaceManager wm : openManagers) {
            wm.close();
        }
        openManagers.clear();
    }

    private PlanModeManager manager(Path project, Path workspace) {
        AbstractFilesystem fs =
                new LocalFilesystemSpec().project(project).toFilesystem(workspace, null);
        WorkspaceManager wm = new WorkspaceManager(workspace, fs);
        openManagers.add(wm);
        return new PlanModeManager(wm, null);
    }

    @Test
    void deniesMutatingToolsButAllowsReadOnlyAndPlanTools(
            @TempDir Path project, @TempDir Path workspace) {
        PlanModeManager manager = manager(project, workspace);
        AgentState state = AgentState.builder().build();
        manager.enter(state);
        StubAgent agent = new StubAgent("tester", state);

        PlanModeMiddleware mw = new PlanModeMiddleware(manager, READ_ONLY);

        List<ToolUseBlock> calls =
                List.of(
                        call("1", "read_file"),
                        call("2", "write_file"),
                        call("3", "plan_exit"),
                        call("4", "todo_write"));

        AtomicReference<ActingInput> forwarded = new AtomicReference<>();
        List<AgentEvent> events =
                mw.onActing(
                                agent,
                                null,
                                new ActingInput(calls),
                                ai -> {
                                    forwarded.set(ai);
                                    return Flux.empty();
                                })
                        .collectList()
                        .block();

        // 只有 read_file、plan_exit、todo_write 进入核心行动逻辑。
        List<String> forwardedNames =
                forwarded.get().toolCalls().stream().map(ToolUseBlock::getName).toList();
        assertEquals(List.of("read_file", "plan_exit", "todo_write"), forwardedNames);

        // 被拒绝的唯一调用在上下文中产生拒绝工具结果消息。
        long denialMsgs = state.contextMutable().stream().filter(m -> m.getRole() != null).count();
        assertEquals(1, denialMsgs);

        // 该拒绝调用对应开始、增量和结束事件。
        assertEquals(3, events.size());
    }

    @Test
    void passesThroughWhenPlanModeInactive(@TempDir Path project, @TempDir Path workspace) {
        PlanModeManager manager = manager(project, workspace);
        AgentState state = AgentState.builder().build();
        StubAgent agent = new StubAgent("tester", state);
        PlanModeMiddleware mw = new PlanModeMiddleware(manager, READ_ONLY);

        List<ToolUseBlock> calls = List.of(call("1", "write_file"));
        AtomicReference<ActingInput> forwarded = new AtomicReference<>();
        mw.onActing(
                        agent,
                        null,
                        new ActingInput(calls),
                        ai -> {
                            forwarded.set(ai);
                            return Flux.empty();
                        })
                .collectList()
                .block();

        assertEquals(1, forwarded.get().toolCalls().size());
        assertTrue(state.contextMutable().isEmpty());
    }

    @Test
    void systemPromptBannerOnlyWhenActive(@TempDir Path project, @TempDir Path workspace) {
        PlanModeManager manager = manager(project, workspace);
        AgentState state = AgentState.builder().build();
        StubAgent agent = new StubAgent("tester", state);
        PlanModeMiddleware mw = new PlanModeMiddleware(manager, READ_ONLY);

        String inactive = renderPrompt(mw, agent, "base");
        assertFalse(inactive.contains("PLAN MODE"));

        manager.enter(state);
        String active = renderPrompt(mw, agent, "base");
        assertTrue(active.contains("PLAN MODE"));
        assertTrue(active.startsWith("base"));
        // 横幅包含计划文件路径
        assertTrue(active.contains("plans/PLAN.md"), "banner should include plan file path");
    }

    @Test
    void modelBoundaryRefreshesModeWithinSameCall(@TempDir Path project, @TempDir Path workspace) {
        PlanModeManager manager = manager(project, workspace);
        AgentState state = AgentState.builder().build();
        StubAgent agent = new StubAgent("tester", state);
        var middleware = new PlanModeMiddleware(manager, READ_ONLY);
        var rc = RuntimeContext.builder().agentState(state).build();
        var input = new ModelCallInput(List.of(), List.of(), null, null);
        middleware.onSystemPrompt(agent, rc, "base").block();
        manager.enter(state);
        middleware.onModelCall(agent, rc, input, ignored -> Flux.empty()).blockLast();
        String active = ContextRenderer.render(rc.get(WorkspaceContextMaterials.class).items());
        assertTrue(active.contains("PLAN MODE is active"));
        manager.exit(state);
        middleware.onModelCall(agent, rc, input, ignored -> Flux.empty()).blockLast();
        String build = ContextRenderer.render(rc.get(WorkspaceContextMaterials.class).items());
        assertFalse(build.contains("PLAN MODE is active"));
        assertTrue(build.contains("switched from PLAN to BUILD"));
        assertEquals(1, rc.get(WorkspaceContextMaterials.class).items().size());
    }

    @Test
    void buildModeHintSurfacesPlanFilePath(@TempDir Path project, @TempDir Path workspace) {
        PlanModeManager manager = manager(project, workspace);
        AgentState state = AgentState.builder().build();
        StubAgent agent = new StubAgent("tester", state);
        PlanModeMiddleware mw = new PlanModeMiddleware(manager, READ_ONLY);

        // 先进入再退出计划模式，模拟 plan_enter → plan_write → plan_exit
        manager.enter(state);
        manager.exit(state);
        assertFalse(state.getPlanModeContext().isPlanActive());

        String prompt = renderPrompt(mw, agent, "base");
        // BUILD 模式应显示计划文件路径
        assertTrue(prompt.contains("plans/PLAN.md"), "build mode should hint plan path");
        assertFalse(prompt.contains("PLAN MODE is active"), "should not show plan mode banner");
    }

    @Test
    void modeRulesRefreshAcrossModelCallsUnderRealScheduling(@TempDir Path workspaceDir)
            throws Exception {
        // 捕获每次模型请求的拼接文本，每次调用一个条目。
        List<String> modelPrompts = new CopyOnWriteArrayList<>();
        RuntimeContext rc = RuntimeContext.builder().sessionId("plan-sched").build();
        MockModel model =
                new MockModel(
                        msgs -> {
                            modelPrompts.add(
                                    msgs.stream()
                                            .map(Msg::getTextContent)
                                            .collect(Collectors.joining("\n")));
                            if (modelPrompts.size() == 1) {
                                return List.of(
                                        new ChatResponse(
                                                "tool-1",
                                                List.of(
                                                        ToolUseBlock.builder()
                                                                .id("pw-1")
                                                                .name("plan_write")
                                                                .input(Map.of("content", "# Plan"))
                                                                .build()),
                                                null,
                                                Map.of(),
                                                "stop"));
                            }
                            return List.of(
                                    new ChatResponse(
                                            "text-" + modelPrompts.size(),
                                            List.of(TextBlock.builder().text("done").build()),
                                            null,
                                            Map.of(),
                                            "stop"));
                        });

        // 首轮行动阶段关闭计划模式，修改的活动运行状态
        // 与生产中间件使用相同方式解析。运行中不能调用
        // 公开 exitPlanMode API，因为会话日志已有活动写入者；
        // 真实 plan_exit 工具调用也在此位置执行同样修改。
        AtomicBoolean flipped = new AtomicBoolean();
        MiddlewareBase midRunExit =
                new MiddlewareBase() {
                    @Override
                    public Set<MiddlewareBase.ExtensionPoint> activePoints() {
                        return EnumSet.of(MiddlewareBase.ExtensionPoint.ON_ACTING);
                    }

                    @Override
                    public Flux<AgentEvent> onActing(
                            Agent agent,
                            RuntimeContext ctx,
                            ActingInput input,
                            Function<ActingInput, Flux<AgentEvent>> next) {
                        AgentState live = RuntimeContext.resolveAgentState(ctx, agent);
                        assertNotNull(live, "acting phase must carry the live state");
                        if (flipped.compareAndSet(false, true)) {
                            live.getPlanModeContext().setPlanActive(false);
                        }
                        return next.apply(input);
                    }
                };

        try (HarnessAgent agent =
                HarnessAgent.builder()
                        .name("plan-agent")
                        .model(model)
                        .workspace(workspaceDir)
                        .abstractFilesystem(new LocalFilesystem(workspaceDir))
                        .stateStore(new InMemoryAgentStateStore())
                        .enablePlanMode()
                        .middleware(midRunExit)
                        .build()) {

            agent.enterPlanMode(rc);
            agent.call(UserMessage.builder().textContent("plan then execute").build(), rc).block();
        }

        assertTrue(
                modelPrompts.size() >= 2,
                () -> "script must drive at least two model calls: " + modelPrompts.size());
        assertTrue(
                modelPrompts.get(0).contains("PLAN MODE is active"),
                () -> "seed request should carry the plan banner: " + modelPrompts.get(0));
        // 同一 MockModel 还处理回复后的维护请求，如记忆
        // 提取，因此对首次调用之后的全部请求检查。
        for (int i = 1; i < modelPrompts.size(); i++) {
            String prompt = modelPrompts.get(i);
            assertFalse(
                    prompt.contains("PLAN MODE is active"),
                    "stale plan banner at request " + i + ": " + prompt);
        }
        assertTrue(
                modelPrompts.stream()
                        .skip(1)
                        .anyMatch(p -> p.contains("switched from PLAN to BUILD")),
                () -> "BUILD mode rules should reach the next request: " + modelPrompts);
    }

    @Test
    void buildModeNoHintWhenNoPlanFile(@TempDir Path project, @TempDir Path workspace) {
        PlanModeManager manager = manager(project, workspace);
        AgentState state = AgentState.builder().build();
        StubAgent agent = new StubAgent("tester", state);
        PlanModeMiddleware mw = new PlanModeMiddleware(manager, READ_ONLY);

        // 从未进入计划模式，不应记录计划文件
        String prompt = renderPrompt(mw, agent, "base");
        assertEquals("base", prompt);
    }

    @Test
    void allowsSubagentToolsInPlanMode(@TempDir Path project, @TempDir Path workspace) {
        PlanModeManager manager = manager(project, workspace);
        AgentState state = AgentState.builder().build();
        manager.enter(state);
        StubAgent agent = new StubAgent("tester", state);
        PlanModeMiddleware mw = new PlanModeMiddleware(manager, READ_ONLY);

        List<ToolUseBlock> calls =
                List.of(
                        call("1", "agent_spawn"),
                        call("2", "agent_send"),
                        call("3", "agent_list"),
                        call("4", "task_output"),
                        call("5", "task_list"));

        AtomicReference<ActingInput> forwarded = new AtomicReference<>();
        List<AgentEvent> events =
                mw.onActing(
                                agent,
                                null,
                                new ActingInput(calls),
                                ai -> {
                                    forwarded.set(ai);
                                    return Flux.empty();
                                })
                        .collectList()
                        .block();

        // 全部子智能体／任务工具应正常通过，不被拒绝。
        List<String> forwardedNames =
                forwarded.get().toolCalls().stream().map(ToolUseBlock::getName).toList();
        assertEquals(
                List.of("agent_spawn", "agent_send", "agent_list", "task_output", "task_list"),
                forwardedNames);
        assertTrue(events.isEmpty(), "no denied events expected");
    }

    @Test
    void skillLoadToolIsReadOnlyAndPermittedInPlanMode(
            @TempDir Path project, @TempDir Path workspace) {
        // SkillLoadTool 实现 AgentTool 而非 ToolBase，验证其 isReadOnly() 为 true。
        SkillLoadTool loadTool = new SkillLoadTool();
        assertTrue(loadTool.isReadOnly(), "SkillLoadTool must be read-only");

        // 构建含 SkillLoadTool 的工具集，并按
        // HarnessAgent.Builder.build() 的模式装配解析器。
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(loadTool);
        Predicate<String> resolver =
                toolName -> {
                    AgentTool t = toolkit.getTool(toolName);
                    return t != null && t.isReadOnly();
                };

        PlanModeManager manager = manager(project, workspace);
        AgentState state = AgentState.builder().build();
        manager.enter(state);
        StubAgent agent = new StubAgent("tester", state);

        PlanModeMiddleware mw = new PlanModeMiddleware(manager, resolver);

        List<ToolUseBlock> calls =
                List.of(call("1", SkillLoadTool.TOOL_NAME), call("2", "write_file"));

        AtomicReference<ActingInput> forwarded = new AtomicReference<>();
        List<AgentEvent> events =
                mw.onActing(
                                agent,
                                null,
                                new ActingInput(calls),
                                ai -> {
                                    forwarded.set(ai);
                                    return Flux.empty();
                                })
                        .collectList()
                        .block();

        // load_skill_through_path 应通过，write_file 应被拒绝。
        List<String> forwardedNames =
                forwarded.get().toolCalls().stream().map(ToolUseBlock::getName).toList();
        assertEquals(List.of(SkillLoadTool.TOOL_NAME), forwardedNames);
        assertEquals(3, events.size(), "denied write_file should produce start+delta+end events");
    }

    /** 仅暴露名称与状态的最小 Agent 占位实现。 */
    private static final class StubAgent implements Agent {
        private final String name;
        private final AgentState state;

        StubAgent(String name, AgentState state) {
            this.name = name;
            this.state = state;
        }

        @Override
        public AgentState getAgentState() {
            return state;
        }

        @Override
        public String getAgentId() {
            return "id-" + name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public void interrupt() {}

        @Override
        public void interrupt(Msg msg) {}

        @Override
        public Mono<Msg> call(List<Msg> msgs) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Mono<Msg> call(List<Msg> msgs, Class<?> structuredModel) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Mono<Msg> call(List<Msg> msgs, JsonNode schema) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Flux<Event> stream(List<Msg> msgs, StreamOptions options) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Flux<Event> stream(List<Msg> msgs, StreamOptions options, Class<?> structuredModel) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Flux<Event> stream(List<Msg> msgs, StreamOptions options, JsonNode schema) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Mono<Void> observe(Msg msg) {
            return Mono.empty();
        }

        @Override
        public Mono<Void> observe(List<Msg> msgs) {
            return Mono.empty();
        }
    }
}
