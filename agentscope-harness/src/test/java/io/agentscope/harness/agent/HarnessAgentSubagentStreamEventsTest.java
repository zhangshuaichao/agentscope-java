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

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.testing.HarnessQuiescence;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/**
 * 验证同步本地子智能体调用会将子级 {@link AgentEvent} 转发到父级 {@link HarnessAgent#streamEvents} 流程，并标注非空 {@link AgentEvent#getSource() source} 路径，其中包含子智能体 ID。
 *
 * <p>与 {@link HarnessAgentSubagentStreamTest} 对应，但覆盖 {@code streamEvents()}（{@code Flux<AgentEvent>}）路径，而非已弃用的 {@code stream()}（{@code Flux<Event>}）。
 */
@HarnessQuiescence
class HarnessAgentSubagentStreamEventsTest {

    @TempDir Path workspace;
    @TempDir Path stateHome;

    private String previousStateHome;
    private HarnessAgent parent;

    @BeforeEach
    void overrideStateHome() {
        previousStateHome = System.getProperty("agentscope.state.home");
        System.setProperty("agentscope.state.home", stateHome.toString());
    }

    @AfterEach
    void tearDown() {
        try {
            if (parent != null) {
                parent.close();
            }
        } finally {
            if (previousStateHome != null) {
                System.setProperty("agentscope.state.home", previousStateHome);
            } else {
                System.clearProperty("agentscope.state.home");
            }
        }
    }

    // -----------------------------------------------------------------
    // 辅助方法
    // -----------------------------------------------------------------

    private static ChatResponse stopChunk(String id, String text) {
        return new ChatResponse(
                id, List.of(TextBlock.builder().text(text).build()), null, Map.of(), "stop");
    }

    private static ChatResponse toolCallChunk(String id, String toolName, Map<String, Object> in) {
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

    private void writeSubagentSpec(String childId, String description, String body)
            throws Exception {
        Files.createDirectories(workspace.resolve("subagents"));
        Files.writeString(
                workspace.resolve("subagents/" + childId + ".md"),
                "---\ndescription: " + description + "\n---\n" + body + "\n");
    }

    // -----------------------------------------------------------------
    // 1. streamEvents()：转发带来源标记的子级 AgentEvent
    // -----------------------------------------------------------------

    @Test
    void streamEvents_childEventsForwardedWithSource() throws Exception {
        String childId = "researcher";
        writeSubagentSpec(childId, "Research specialist", "You are a researcher.");

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
                                                "research X",
                                                "timeout_seconds",
                                                60))))
                .thenReturn(
                        Flux.just(
                                new ChatResponse(
                                        "c1",
                                        List.of(TextBlock.builder().text("researching…").build()),
                                        null,
                                        Map.of(),
                                        null),
                                stopChunk("c1", "research complete")))
                .thenReturn(Flux.just(stopChunk("p2", "summary done")));

        parent =
                HarnessAgent.builder()
                        .name("parent")
                        .model(model)
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystem(workspace))
                        .build();

        RuntimeContext ctx = RuntimeContext.builder().sessionId("sess-stream-events").build();

        List<AgentEvent> events =
                parent.streamEvents(
                                List.of(
                                        Msg.builder()
                                                .role(MsgRole.USER)
                                                .textContent("start")
                                                .build()),
                                ctx)
                        .collectList()
                        .block();

        assertNotNull(events);
        assertFalse(events.isEmpty());

        // 子事件：source 非空且包含 childId
        List<AgentEvent> childEvents =
                events.stream().filter(e -> e.getSource() != null).collect(Collectors.toList());
        assertFalse(
                childEvents.isEmpty(),
                "expected child events with source tag; got types: "
                        + events.stream()
                                .map(e -> e.getType() + "(src=" + e.getSource() + ")")
                                .collect(Collectors.joining(", ")));

        for (AgentEvent childEvent : childEvents) {
            assertTrue(
                    childEvent.getSource().contains(childId),
                    "source should contain childId; got: " + childEvent.getSource());
        }

        // 父事件：source 为空
        assertTrue(
                events.stream().anyMatch(e -> e.getSource() == null),
                "expected at least one parent event with source == null");
    }

    // -----------------------------------------------------------------
    // 1b. 在 AsyncToolMiddleware 下调用 streamEvents()，子事件仍应
    // 携带来源标记转发；覆盖裸 subscribe 导致
    // AsyncToolMiddleware.onActing 上下文断开的回归场景。
    //
    // AsyncToolMiddleware 通过 Flux.create 与
    // next.apply(input).subscribe(...) 包装行动阶段。裸 subscribe 会脱离
    // Reactor Context，使 runToolBatch 的 deferContextual 捕获空上下文，
    // 从而丢失 buildAgentStream 设置的 AgentEventEmitter.CONTEXT_KEY。
    // 若 executeToolCalls 的 contextWrite 未恢复发射器，
    // AgentSpawnTool#execLocalSync 就会回退到非流式
    // 路径（路径 3），子 AgentEvent 无法进入父级的
    // streamEvents() 事件流。
    // -----------------------------------------------------------------

    @Test
    void streamEvents_childEventsForwardedWithSource_underAsyncToolMiddleware() throws Exception {
        String childId = "researcher";
        writeSubagentSpec(childId, "Research specialist", "You are a researcher.");

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
                                                "research X",
                                                "timeout_seconds",
                                                60))))
                .thenReturn(
                        Flux.just(
                                new ChatResponse(
                                        "c1",
                                        List.of(TextBlock.builder().text("researching…").build()),
                                        null,
                                        Map.of(),
                                        null),
                                stopChunk("c1", "research complete")))
                .thenReturn(Flux.just(stopChunk("p2", "summary done")));

        // 注册 AsyncToolMiddleware（messageBus + asyncToolTimeout）
        // 触发该回归场景：裸 next.apply(input).subscribe(...)
        // 会在行动阶段脱离 Reactor Context。
        parent =
                HarnessAgent.builder()
                        .name("parent")
                        .model(model)
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystem(workspace))
                        .asyncToolTimeout(Duration.ofSeconds(30))
                        .build();

        RuntimeContext ctx = RuntimeContext.builder().sessionId("sess-async-stream-events").build();

        List<AgentEvent> events =
                parent.streamEvents(
                                List.of(
                                        Msg.builder()
                                                .role(MsgRole.USER)
                                                .textContent("start")
                                                .build()),
                                ctx)
                        .collectList()
                        .block();

        assertNotNull(events);
        assertFalse(events.isEmpty());

        // 子事件：source 非空且包含 childId。修复前这些事件
        // 会为空，因为行动上下文中的发射器丢失。
        List<AgentEvent> childEvents =
                events.stream().filter(e -> e.getSource() != null).collect(Collectors.toList());
        assertFalse(
                childEvents.isEmpty(),
                "expected child events with source tag under AsyncToolMiddleware; got types: "
                        + events.stream()
                                .map(e -> e.getType() + "(src=" + e.getSource() + ")")
                                .collect(Collectors.joining(", ")));

        for (AgentEvent childEvent : childEvents) {
            assertTrue(
                    childEvent.getSource().contains(childId),
                    "source should contain childId; got: " + childEvent.getSource());
        }

        // 子级 AGENT_START / AGENT_END 起止事件应包含来源
        assertTrue(
                childEvents.stream().anyMatch(e -> e.getType() == AgentEventType.AGENT_START),
                "expected child AGENT_START with source under AsyncToolMiddleware");
        assertTrue(
                childEvents.stream().anyMatch(e -> e.getType() == AgentEventType.AGENT_END),
                "expected child AGENT_END with source under AsyncToolMiddleware");

        // 父级起止事件：source 为空
        assertEquals(AgentEventType.AGENT_START, events.get(0).getType());
        assertNull(events.get(0).getSource(), "first event should be parent AGENT_START");
        assertEquals(AgentEventType.AGENT_END, events.get(events.size() - 1).getType());
        assertNull(
                events.get(events.size() - 1).getSource(), "last event should be parent AGENT_END");
    }

    // -----------------------------------------------------------------
    // 2. 子级 AGENT_START / AGENT_END 起止事件应包含来源
    // -----------------------------------------------------------------

    @Test
    void streamEvents_childAgentStartAndEndEmittedWithSource() throws Exception {
        String childId = "formatter";
        writeSubagentSpec(childId, "Text formatter", "Format everything.");

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
                                                "format data",
                                                "timeout_seconds",
                                                60))))
                .thenReturn(Flux.just(stopChunk("c1", "formatted")))
                .thenReturn(Flux.just(stopChunk("p2", "all done")));

        parent =
                HarnessAgent.builder()
                        .name("parent")
                        .model(model)
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystem(workspace))
                        .build();

        List<AgentEvent> events =
                parent.streamEvents(
                                List.of(Msg.builder().role(MsgRole.USER).textContent("go").build()),
                                RuntimeContext.builder().sessionId("sess-bookends").build())
                        .collectList()
                        .block();

        assertNotNull(events);

        // 子级 AGENT_START 带来源
        List<AgentEvent> childStarts =
                events.stream()
                        .filter(
                                e ->
                                        e.getType() == AgentEventType.AGENT_START
                                                && e.getSource() != null)
                        .collect(Collectors.toList());
        assertFalse(childStarts.isEmpty(), "expected child AGENT_START with source");
        assertTrue(childStarts.get(0).getSource().contains(childId));

        // 子级 AGENT_END 带来源
        List<AgentEvent> childEnds =
                events.stream()
                        .filter(
                                e ->
                                        e.getType() == AgentEventType.AGENT_END
                                                && e.getSource() != null)
                        .collect(Collectors.toList());
        assertFalse(childEnds.isEmpty(), "expected child AGENT_END with source");
        assertTrue(childEnds.get(0).getSource().contains(childId));
    }

    // -----------------------------------------------------------------
    // 3. 事件顺序：父级 AGENT_START → 子事件 → 父级 AGENT_END
    // -----------------------------------------------------------------

    @Test
    void streamEvents_eventOrdering_childBetweenParentStartAndEnd() throws Exception {
        String childId = "analyst";
        writeSubagentSpec(childId, "Data analyst", "You are an analyst.");

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

        List<AgentEvent> events =
                parent.streamEvents(
                                List.of(Msg.builder().role(MsgRole.USER).textContent("go").build()),
                                RuntimeContext.builder().sessionId("sess-order").build())
                        .collectList()
                        .block();

        assertNotNull(events);

        // 首事件应为父级 AGENT_START（source 为空）
        assertEquals(AgentEventType.AGENT_START, events.get(0).getType());
        assertNull(events.get(0).getSource(), "first event should be parent AGENT_START");

        // 末事件应为父级 AGENT_END（source 为空）
        AgentEvent lastEvent = events.get(events.size() - 1);
        assertEquals(AgentEventType.AGENT_END, lastEvent.getType());
        assertNull(lastEvent.getSource(), "last event should be parent AGENT_END");

        // 子事件应出现在首尾事件之间
        int firstChildIdx =
                events.stream()
                        .filter(e -> e.getSource() != null)
                        .mapToInt(events::indexOf)
                        .min()
                        .orElse(-1);
        assertTrue(firstChildIdx > 0, "child events should appear after parent AGENT_START");
        assertTrue(
                firstChildIdx < events.size() - 1,
                "child events should appear before parent AGENT_END");

        int childEndIdx =
                events.stream()
                        .filter(
                                e ->
                                        e.getType() == AgentEventType.AGENT_END
                                                && e.getSource() != null)
                        .mapToInt(events::indexOf)
                        .findFirst()
                        .orElse(-1);
        assertTrue(childEndIdx > firstChildIdx, "child AGENT_END should follow child events");
        assertTrue(
                childEndIdx < events.size() - 1,
                "child AGENT_END should appear before parent AGENT_END");
    }

    // -----------------------------------------------------------------
    // 4. 仅属于父级的事件 source 为空
    // -----------------------------------------------------------------

    @Test
    void streamEvents_parentEventsHaveNullSource() throws Exception {
        String childId = "helper";
        writeSubagentSpec(childId, "Helper", "You are a helper.");

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
                                                "help with X",
                                                "timeout_seconds",
                                                60))))
                .thenReturn(Flux.just(stopChunk("c1", "helped")))
                .thenReturn(Flux.just(stopChunk("p2", "done")));

        parent =
                HarnessAgent.builder()
                        .name("parent")
                        .model(model)
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystem(workspace))
                        .build();

        List<AgentEvent> events =
                parent.streamEvents(
                                List.of(Msg.builder().role(MsgRole.USER).textContent("go").build()),
                                RuntimeContext.builder().sessionId("sess-null-source").build())
                        .collectList()
                        .block();

        assertNotNull(events);

        List<AgentEvent> parentEvents =
                events.stream().filter(e -> e.getSource() == null).collect(Collectors.toList());
        assertFalse(parentEvents.isEmpty());

        // 父级 AGENT_START 和 AGENT_END 的 source 都应为空
        assertTrue(
                parentEvents.stream().anyMatch(e -> e.getType() == AgentEventType.AGENT_START),
                "parent AGENT_START should have null source");
        assertTrue(
                parentEvents.stream().anyMatch(e -> e.getType() == AgentEventType.AGENT_END),
                "parent AGENT_END should have null source");
    }
}
