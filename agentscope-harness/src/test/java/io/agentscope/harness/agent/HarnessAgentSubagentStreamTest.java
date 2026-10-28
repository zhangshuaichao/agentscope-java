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
package io.agentscope.harness.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventSource;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.testing.HarnessQuiescence;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/**
 * 验证同步本地子智能体调用会将子级 {@link Event} 转发到父级 {@link HarnessAgent#stream(List, StreamOptions, RuntimeContext)} 流程，并带非空 {@link EventSource}，其中包含正确的 {@code agentId} 和 {@code path}。
 *
 * <p>同时验证非流式 {@code call()} 路径不受影响，仍以单个 {@link Msg} 返回最终回复。
 *
 * <p><b>模型调用顺序：</b>父子智能体共享同一 {@code Model} mock，以 {@code thenReturn} 链为连续调用（父第 1 轮 → 子 → 父第 2 轮）配置相应 {@link ChatResponse}，对应 {@code buildDeclaredFactory} 为子智能体捕获 {@code this.model} 的方式。
 */
@HarnessQuiescence
class HarnessAgentSubagentStreamTest {

    @TempDir Path workspace;

    // 每个 @Test 使用新的 JsonFileAgentStateStore 根目录，避免
    // parent 智能体的持久 AgentState 泄漏到其他用例。
    @TempDir Path stateHome;

    private HarnessAgent parent;
    private HarnessAgent agent;
    private String previousStateHome;

    @BeforeEach
    void overrideStateHome() {
        previousStateHome = System.getProperty("agentscope.state.home");
        System.setProperty("agentscope.state.home", stateHome.toString());
    }

    @AfterEach
    void restoreStateHome() {
        if (parent != null) {
            parent.close();
        }
        if (agent != null) {
            agent.close();
        }
        if (previousStateHome != null) {
            System.setProperty("agentscope.state.home", previousStateHome);
        } else {
            System.clearProperty("agentscope.state.home");
        }
    }

    // -----------------------------------------------------------------
    // 辅助方法
    // -----------------------------------------------------------------

    /** 不含工具调用、结束原因为 stop 的 ChatResponse。 */
    private static ChatResponse stopChunk(String id, String text) {
        return new ChatResponse(
                id, List.of(TextBlock.builder().text(text).build()), null, Map.of(), "stop");
    }

    /** 请求一次工具调用、结束原因为 "tool_use" 的 ChatResponse。 */
    private static ChatResponse toolCallChunk(String id, String toolName, Map<String, Object> in) {
        // ToolValidator.validateInput 使用 toolCall.getContent() 原始 JSON 字符串执行模式
        // 校验；缺少 content 字符串时会看到 null 输入并拒绝调用。
        String contentJson = io.agentscope.core.util.JsonUtils.getJsonCodec().toJson(in);
        ToolUseBlock tc =
                ToolUseBlock.builder()
                        .id("tc-" + id)
                        .name(toolName)
                        .input(in)
                        .content(contentJson)
                        .build();
        return new ChatResponse(id, List.of(tc), null, Map.of(), "tool_use");
    }

    // -----------------------------------------------------------------
    // 1. stream()：转发带 EventSource 标记的子事件
    // -----------------------------------------------------------------

    /**
     * 场景：
     *
     * <ol>
     *   <li>父模型第 1 轮 → {@code agent_spawn(researcher, …, timeout_seconds=60)}。
     *   <li>子模型 researcher → 输出 REASONING 分块及最终回复。
     *   <li>父模型第 2 轮 → 最终回复 "summary done"。
     * </ol>
     *
     * <p>收集到的 {@code Flux<Event>} 应满足：
     *
     * <ul>
     *   <li>事件总数大于 1，因为已合并子事件。
     *   <li>部分事件满足 {@code source.agentId == "researcher"}。
     *   <li>{@code source.path} 包含 {@code "researcher"}。
     *   <li>存在没有 {@code source} 的父级自身事件。
     * </ul>
     */
    @Test
    void stream_localSubagentEventsForwardedToParentFlux() throws Exception {
        String childId = "researcher";
        Files.createDirectories(workspace.resolve("subagents"));
        Files.writeString(
                workspace.resolve("subagents/" + childId + ".md"),
                """
                ---
                description: Research specialist
                ---
                You are a researcher.
                """);

        // 父子共享的 mock 模型。
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("stub");
        when(model.stream(anyList(), any(), any()))
                // 第 1 次模型调用（父）：派生 researcher
                .thenReturn(
                        Flux.just(
                                toolCallChunk(
                                        "p1",
                                        "agent_spawn",
                                        Map.of(
                                                "agent_id",
                                                childId,
                                                "task",
                                                "research X",
                                                "timeout_seconds",
                                                60))))
                // 第 2 次模型调用（子）：推理分块及停止
                .thenReturn(
                        Flux.just(
                                new ChatResponse(
                                        "c1",
                                        List.of(TextBlock.builder().text("researching…").build()),
                                        null,
                                        Map.of(),
                                        null),
                                stopChunk("c1", "research complete")))
                // 第 3 次模型调用（父）：最终摘要
                .thenReturn(Flux.just(stopChunk("p2", "summary done")));

        parent =
                HarnessAgent.builder()
                        .name("parent")
                        .model(model)
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystem(workspace))
                        .build();

        RuntimeContext ctx = RuntimeContext.builder().sessionId("sess-stream").build();

        List<Event> events =
                parent.stream(
                                List.of(
                                        Msg.builder()
                                                .role(MsgRole.USER)
                                                .textContent("start")
                                                .build()),
                                StreamOptions.defaults(),
                                ctx)
                        .collectList()
                        .block();

        assertNotNull(events);
        assertFalse(events.isEmpty(), "at least one event expected");

        // 子事件：source 非空
        List<Event> childEvents =
                events.stream().filter(e -> e.getSource() != null).collect(Collectors.toList());
        assertFalse(
                childEvents.isEmpty(),
                "expected child events forwarded to parent Flux; got: "
                        + events.stream().map(Event::toString).collect(Collectors.joining(", ")));

        Event firstChild = childEvents.get(0);
        assertEquals(childId, firstChild.getSource().getAgentId(), "agentId mismatch");
        assertTrue(
                firstChild.getSource().getPath().contains(childId),
                "path should contain childId; got: " + firstChild.getSource().getPath());
        assertTrue(firstChild.getSource().getDepth() >= 1, "depth must be >= 1");
        assertNotNull(firstChild.getSource().getAgentKey(), "agentKey must not be null");
        assertNotNull(firstChild.getSource().getSessionId(), "sessionId must not be null");

        // 父事件：source 为空
        assertTrue(
                events.stream().anyMatch(e -> e.getSource() == null),
                "expected at least one parent event with source == null");

        // 总数应大于仅父事件的分块数：父收到 1 个 stop 分块，产生 1 个 REASONING 和 1 个 AGENT_RESULT
        assertTrue(events.size() > 2, "total events should exceed parent-only count");
    }

    // -----------------------------------------------------------------
    // 2. 事件顺序：父 → 子 → 父
    // -----------------------------------------------------------------

    @Test
    void stream_eventOrdering_parentChildParent() throws Exception {
        String childId = "formatter";
        Files.createDirectories(workspace.resolve("subagents"));
        Files.writeString(
                workspace.resolve("subagents/" + childId + ".md"),
                """
                ---
                description: Text formatter
                ---
                Format everything.
                """);

        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("stub");
        Map<String, Object> spawnInput =
                Map.of("agent_id", childId, "task", "format data", "timeout_seconds", 60);
        when(model.stream(anyList(), any(), any()))
                .thenReturn(
                        Flux.just(
                                // 父级首轮同时包含推理文本和工具调用
                                new ChatResponse(
                                        "p1",
                                        List.of(
                                                TextBlock.builder().text("will format").build(),
                                                ToolUseBlock.builder()
                                                        .id("tc-p1")
                                                        .name("agent_spawn")
                                                        .input(spawnInput)
                                                        .content(
                                                                io.agentscope.core.util.JsonUtils
                                                                        .getJsonCodec()
                                                                        .toJson(spawnInput))
                                                        .build()),
                                        null,
                                        Map.of(),
                                        "tool_use")))
                // 子级轮次
                .thenReturn(Flux.just(stopChunk("c1", "formatted")))
                // 父级最终回复
                .thenReturn(Flux.just(stopChunk("p2", "all done")));

        parent =
                HarnessAgent.builder()
                        .name("parent")
                        .model(model)
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystem(workspace))
                        .build();

        List<Event> events =
                parent.stream(
                                List.of(Msg.builder().role(MsgRole.USER).textContent("go").build()),
                                StreamOptions.defaults(),
                                RuntimeContext.builder().sessionId("sess-order").build())
                        .collectList()
                        .block();

        assertNotNull(events);

        // 必须存在子事件
        assertTrue(
                events.stream().anyMatch(e -> e.getSource() != null),
                "child events should be present");

        // 验证交错顺序：至少一个子事件出现在最终 AGENT_RESULT 之前
        int firstChildIdx =
                events.stream()
                        .filter(e -> e.getSource() != null)
                        .mapToInt(events::indexOf)
                        .min()
                        .orElse(-1);
        int lastAgentResultIdx =
                events.stream()
                        .filter(e -> e.getType() == EventType.AGENT_RESULT && e.isLast())
                        .mapToInt(events::indexOf)
                        .max()
                        .orElse(-1);

        assertTrue(firstChildIdx >= 0, "child event index should be found");
        assertTrue(lastAgentResultIdx >= 0, "AGENT_RESULT should be found");
        assertTrue(
                firstChildIdx < lastAgentResultIdx,
                "child events should appear before final AGENT_RESULT");
    }

    // -----------------------------------------------------------------
    // 3. call() 路径：没有总线，非流式行为不变
    // -----------------------------------------------------------------

    /** 使用 {@code call()} 时，Reactor Context 中没有 {@link io.agentscope.core.agent.SubagentEventBus}。子智能体走普通阻塞路径，父级返回单个最终 {@link Msg}。 */
    @Test
    void call_localSubagent_returnsReplyWithoutStreaming() throws Exception {
        String childId = "analyst";
        Files.createDirectories(workspace.resolve("subagents"));
        Files.writeString(
                workspace.resolve("subagents/" + childId + ".md"),
                """
                ---
                description: Data analyst
                ---
                You are an analyst.
                """);

        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("stub");
        when(model.stream(anyList(), any(), any()))
                .thenReturn(
                        Flux.just(
                                toolCallChunk(
                                        "p1",
                                        "agent_spawn",
                                        Map.of(
                                                "agent_id",
                                                childId,
                                                "task",
                                                "analyse data",
                                                "timeout_seconds",
                                                60))))
                .thenReturn(Flux.just(stopChunk("c1", "analysis complete")))
                .thenReturn(Flux.just(stopChunk("p2", "result obtained")));

        parent =
                HarnessAgent.builder()
                        .name("parent")
                        .model(model)
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystem(workspace))
                        .build();

        Msg reply =
                parent.call(
                                List.of(Msg.builder().role(MsgRole.USER).textContent("go").build()),
                                RuntimeContext.builder().sessionId("sess-call").build())
                        .block();

        assertNotNull(reply, "reply must not be null");
        assertTrue(
                reply.getTextContent().contains("result obtained"),
                "final reply text mismatch; got: " + reply.getTextContent());
    }

    /** 父构建器配置的自定义 {@link java.net.http.HttpClient} 应由派生子智能体继承：{@code buildDeclaredFactory} 捕获 {@code b.webHttpClient} 并传给子构建器。 */
    @Test
    void call_localSubagent_inheritsCustomWebHttpClient() throws Exception {
        String childId = "webclient-child";
        Files.createDirectories(workspace.resolve("subagents"));
        Files.writeString(
                workspace.resolve("subagents/" + childId + ".md"),
                """
                ---
                description: Web client child
                ---
                You are a helper.
                """);

        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("stub");
        when(model.stream(anyList(), any(), any()))
                .thenReturn(
                        Flux.just(
                                toolCallChunk(
                                        "p1",
                                        "agent_spawn",
                                        Map.of(
                                                "agent_id",
                                                childId,
                                                "task",
                                                "check",
                                                "timeout_seconds",
                                                60))))
                .thenReturn(Flux.just(stopChunk("c1", "child done")))
                .thenReturn(Flux.just(stopChunk("p2", "parent done")));

        parent =
                HarnessAgent.builder()
                        .name("parent")
                        .model(model)
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystem(workspace))
                        .webHttpClient(
                                HttpClient.newBuilder()
                                        .version(HttpClient.Version.HTTP_1_1)
                                        .build())
                        .build();

        Msg reply =
                parent.call(
                                List.of(Msg.builder().role(MsgRole.USER).textContent("go").build()),
                                RuntimeContext.builder().sessionId("sess-webclient").build())
                        .block();

        assertNotNull(reply, "reply must not be null");
        assertTrue(
                reply.getTextContent().contains("parent done"),
                "final reply text mismatch; got: " + reply.getTextContent());
    }

    // -----------------------------------------------------------------
    // 4. EventSource 单元测试（无需 HarnessAgent）
    // -----------------------------------------------------------------

    @Test
    void eventSource_withAppendedPath_incrementsDepthAndPath() {
        EventSource root =
                EventSource.builder().agentId("planner").path("main/planner").depth(1).build();

        EventSource child = root.withAppendedPath("executor");

        assertEquals("main/planner/executor", child.getPath());
        assertEquals(2, child.getDepth());
        assertEquals("planner", child.getAgentId()); // 复制 agentId，保持其值不变
    }

    @Test
    void eventSource_withAppendedPath_whenPathNull_usesSegmentOnly() {
        EventSource root = EventSource.builder().agentId("root").depth(0).build();
        EventSource child = root.withAppendedPath("child");

        assertEquals("child", child.getPath());
        assertEquals(1, child.getDepth());
    }

    @Test
    void event_withSource_preservesOriginalAndIsImmutable() {
        Msg msg = Msg.builder().role(MsgRole.ASSISTANT).textContent("hi").build();
        Event original = new Event(EventType.REASONING, msg, true);
        assertNull(original.getSource(), "new Event should have source == null");

        EventSource src = EventSource.builder().agentId("sub").path("main/sub").depth(1).build();
        Event tagged = original.withSource(src);

        // 原对象不被修改
        assertNull(original.getSource(), "original should remain untouched");
        // 标记后的对象携带来源
        assertNotNull(tagged.getSource());
        assertEquals("sub", tagged.getSource().getAgentId());
        assertEquals("main/sub", tagged.getSource().getPath());
        // 保留 type、message 与 isLast
        assertEquals(EventType.REASONING, tagged.getType());
        assertEquals(msg, tagged.getMessage());
        assertTrue(tagged.isLast());
    }

    @Test
    void event_legacyThreeArgConstructor_sourceSetsNull() {
        Msg msg = Msg.builder().role(MsgRole.ASSISTANT).textContent("x").build();
        Event e = new Event(EventType.TOOL_RESULT, msg, false);
        assertNull(e.getSource(), "3-arg constructor should leave source null");
    }

    // -----------------------------------------------------------------
    // 5. 诊断：验证父工具集中存在 agent_spawn
    // -----------------------------------------------------------------

    @Test
    void toolRegistration_agentSpawnToolIsRegistered() throws Exception {
        String childId = "diag-child";
        Files.createDirectories(workspace.resolve("subagents"));
        Files.writeString(
                workspace.resolve("subagents/" + childId + ".md"),
                """
                ---
                description: Diagnostic child
                ---
                Diagnostic subagent.
                """);

        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("stub");

        agent =
                HarnessAgent.builder()
                        .name("diag-parent")
                        .model(model)
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystem(workspace))
                        .build();

        // HarnessAgent → ReActAgent 统一后，通过 getDelegate() 暴露内部智能体；
        // 可通过公开的 getToolkit() 访问器取得工具集。
        io.agentscope.core.tool.Toolkit toolkit = agent.getDelegate().getToolkit();

        List<String> toolNames =
                toolkit.getToolSchemas().stream()
                        .map(io.agentscope.core.model.ToolSchema::getName)
                        .collect(Collectors.toList());
        System.err.println("[DIAG] Registered tool names: " + toolNames);
        assertTrue(
                toolNames.contains("agent_spawn"),
                "agent_spawn must be in toolkit; got: " + toolNames);
    }
}
