/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.agui.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.agui.model.AguiTool;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.SchemaOnlyTool;
import io.agentscope.core.tool.ToolMergeMode;
import io.agentscope.core.tool.ToolRequestConfig;
import io.agentscope.core.tool.Toolkit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/**
 * AguiAgentAdapter 单元测试。
 */
@SuppressWarnings("unchecked")
class AguiAgentAdapterTest {

    private Agent mockAgent;
    private AguiAgentAdapter adapter;

    @BeforeEach
    void setUp() {
        mockAgent = mock(Agent.class);
        adapter = new AguiAgentAdapter(mockAgent, AguiAdapterConfig.defaultConfig());
    }

    @Test
    void testRunInjectsRunInputIntoRuntimeContext() {
        ArgumentCaptor<RuntimeContext> contextCaptor =
                ArgumentCaptor.forClass(RuntimeContext.class);
        when(mockAgent.stream(anyList(), any(StreamOptions.class), contextCaptor.capture()))
                .thenReturn(Flux.empty());

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-ctx")
                        .runId("run-ctx")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Hello")))
                        .tools(List.of(frontendTool("frontend_lookup")))
                        .context(List.of())
                        .state(Map.of("cursor", 12))
                        .forwardedProps(Map.of("tenant", "demo"))
                        .build();

        adapter.run(input).collectList().block();

        RuntimeContext context = contextCaptor.getValue();
        assertEquals("thread-ctx", context.getSessionId());
        assertSame(input, context.get(RunAgentInput.class));
        assertEquals("thread-ctx", context.get("agui.threadId"));
        assertEquals("run-ctx", context.get("agui.runId"));
        assertSame(input.getMessages(), context.get("agui.messages"));
        assertSame(input.getTools(), context.get("agui.tools"));
        assertSame(input.getContext(), context.get("agui.context"));
        assertSame(input.getState(), context.get("agui.state"));
        assertSame(input.getForwardedProps(), context.get("agui.forwardedProps"));
    }

    @Test
    void testRunRegistersFrontendToolsOnPerCallToolRequestConfig() {
        Toolkit toolkit = new Toolkit();
        when(mockAgent.getToolkit()).thenReturn(toolkit);
        when(mockAgent.stream(anyList(), any(StreamOptions.class), any(RuntimeContext.class)))
                .thenAnswer(
                        invocation -> {
                            RuntimeContext rc = invocation.getArgument(2);
                            ToolRequestConfig requestConfig = rc.getToolRequestConfig();
                            assertNotNull(requestConfig);
                            assertNotSame(ToolRequestConfig.NONE, requestConfig);
                            assertTrue(
                                    requestConfig.externalTools().containsKey("frontend_lookup"));
                            assertInstanceOf(
                                    SchemaOnlyTool.class,
                                    requestConfig.externalTools().get("frontend_lookup"));
                            // 绝不能修改智能体共享工具集。
                            assertNull(toolkit.getTool("frontend_lookup"));
                            return Flux.empty();
                        });

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-tools")
                        .runId("run-tools")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Hello")))
                        .tools(List.of(frontendTool("frontend_lookup")))
                        .build();

        adapter.run(input).collectList().block();

        verify(mockAgent).stream(anyList(), any(StreamOptions.class), any(RuntimeContext.class));

        // 运行后共享工具集保持不变，无需清理。
        assertNull(toolkit.getTool("frontend_lookup"));
    }

    @Test
    void testToolRequestConfigCarriesFrontendOverrideForSameNameTool() {
        Toolkit toolkit = new Toolkit();
        SchemaOnlyTool existingTool = schemaOnlyTool("shared_lookup");
        toolkit.registerAgentTool(existingTool);
        when(mockAgent.getToolkit()).thenReturn(toolkit);
        when(mockAgent.stream(anyList(), any(StreamOptions.class), any(RuntimeContext.class)))
                .thenAnswer(
                        invocation -> {
                            RuntimeContext rc = invocation.getArgument(2);
                            ToolRequestConfig requestConfig = rc.getToolRequestConfig();
                            // 请求配置以仅含模式定义的工具携带前端覆盖项，
                            // 该工具
                            // 与后端实例不同，
                            assertInstanceOf(
                                    SchemaOnlyTool.class,
                                    requestConfig.externalTools().get("shared_lookup"));
                            assertNotSame(
                                    existingTool,
                                    requestConfig.externalTools().get("shared_lookup"));
                            // 而共享工具集保留原始工具不变。
                            assertSame(existingTool, toolkit.getTool("shared_lookup"));
                            return Flux.empty();
                        });

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-tools")
                        .runId("run-tools")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Hello")))
                        .tools(List.of(frontendTool("shared_lookup")))
                        .build();

        adapter.run(input).collectList().block();

        verify(mockAgent).stream(anyList(), any(StreamOptions.class), any(RuntimeContext.class));

        // 从未修改共享工具集，原工具完整，无需清理。
        assertSame(existingTool, toolkit.getTool("shared_lookup"));
    }

    @Test
    void testRunDoesNotRegisterFrontendToolsWhenAgentOnly() {
        Toolkit toolkit = new Toolkit();
        when(mockAgent.getToolkit()).thenReturn(toolkit);
        when(mockAgent.stream(anyList(), any(StreamOptions.class), any(RuntimeContext.class)))
                .thenReturn(Flux.empty());

        AguiAgentAdapter agentOnlyAdapter =
                new AguiAgentAdapter(
                        mockAgent,
                        AguiAdapterConfig.builder()
                                .toolMergeMode(ToolMergeMode.AGENT_ONLY)
                                .build());
        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-agent-only")
                        .runId("run-agent-only")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Hello")))
                        .tools(List.of(frontendTool("frontend_lookup")))
                        .build();

        agentOnlyAdapter.run(input).collectList().block();

        assertNull(toolkit.getTool("frontend_lookup"));
    }

    @Test
    void testRunEmitsErrorEventsWhenStreamThrowsBeforeReturningFlux() {
        when(mockAgent.stream(anyList(), any(StreamOptions.class), any(RuntimeContext.class)))
                .thenThrow(new RuntimeException());

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-error")
                        .runId("run-error")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Hello")))
                        .build();

        List<AguiEvent> events = adapter.run(input).collectList().block();

        assertNotNull(events);
        assertEquals(2, events.size());
        assertInstanceOf(AguiEvent.RunStarted.class, events.get(0));
        AguiEvent.RunError runError = assertInstanceOf(AguiEvent.RunError.class, events.get(1));
        assertEquals("RuntimeException", runError.message());
        assertEquals("INTERNAL_ERROR", runError.code());
        assertNotNull(runError.timestamp());
    }

    @Test
    void testRunIgnoresFrontendToolsWhenAgentHasNoToolkit() {
        when(mockAgent.getToolkit()).thenReturn(null);
        when(mockAgent.stream(anyList(), any(StreamOptions.class), any(RuntimeContext.class)))
                .thenReturn(Flux.empty());

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-no-toolkit")
                        .runId("run-no-toolkit")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Hello")))
                        .tools(List.of(frontendTool("frontend_lookup")))
                        .build();

        List<AguiEvent> events = adapter.run(input).collectList().block();

        assertNotNull(events);
        assertEquals(2, events.size());
        assertInstanceOf(AguiEvent.RunStarted.class, events.get(0));
        assertInstanceOf(AguiEvent.RunFinished.class, events.get(1));
    }

    @Test
    void testRunUsesExternalPriorityWhenToolMergeModeIsNull() {
        Toolkit toolkit = new Toolkit();
        when(mockAgent.getToolkit()).thenReturn(toolkit);
        when(mockAgent.stream(anyList(), any(StreamOptions.class), any(RuntimeContext.class)))
                .thenAnswer(
                        invocation -> {
                            RuntimeContext rc = invocation.getArgument(2);
                            ToolRequestConfig requestConfig = rc.getToolRequestConfig();
                            assertNotNull(requestConfig);
                            assertTrue(
                                    requestConfig.externalTools().containsKey("frontend_lookup"));
                            // 合并模式为 null 时默认 MERGE_EXTERNAL_PRIORITY。
                            assertEquals(
                                    ToolMergeMode.MERGE_EXTERNAL_PRIORITY,
                                    requestConfig.mergeMode());
                            // 共享工具集从不携带前端工具。
                            assertNull(toolkit.getTool("frontend_lookup"));
                            return Flux.empty();
                        });

        AguiAgentAdapter nullMergeModeAdapter =
                new AguiAgentAdapter(
                        mockAgent, AguiAdapterConfig.builder().toolMergeMode(null).build());
        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-null-merge-mode")
                        .runId("run-null-merge-mode")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Hello")))
                        .tools(List.of(frontendTool("frontend_lookup")))
                        .build();

        nullMergeModeAdapter.run(input).collectList().block();

        verify(mockAgent).stream(anyList(), any(StreamOptions.class), any(RuntimeContext.class));

        assertNull(toolkit.getTool("frontend_lookup"));
    }

    @Test
    void externalOnlyWithNoExternalToolsExposesNoBackendTools() {
        Toolkit toolkit =
                new Toolkit(
                        io.agentscope.core.tool.ToolkitConfig.builder()
                                .allowToolDeletion(false)
                                .build());
        toolkit.registerAgentTool(schemaOnlyTool("backend"));
        when(mockAgent.getToolkit()).thenReturn(toolkit);
        when(mockAgent.stream(anyList(), any(StreamOptions.class), any(RuntimeContext.class)))
                .thenAnswer(
                        invocation -> {
                            RuntimeContext rc = invocation.getArgument(2);
                            assertTrue(
                                    toolkit.getToolSchemas(List.of(), rc.getToolRequestConfig())
                                            .isEmpty());
                            assertNull(toolkit.getTool("backend", rc.getToolRequestConfig()));
                            assertNotNull(toolkit.getTool("backend"));
                            return Flux.empty();
                        });
        AguiAgentAdapter adapter =
                new AguiAgentAdapter(
                        mockAgent,
                        AguiAdapterConfig.builder()
                                .toolMergeMode(ToolMergeMode.EXTERNAL_ONLY)
                                .build());
        adapter.run(
                        RunAgentInput.builder()
                                .threadId("empty-external")
                                .runId("empty-run")
                                .messages(List.of(AguiMessage.userMessage("m", "Hello")))
                                .tools(List.of())
                                .build())
                .collectList()
                .block();
        verify(mockAgent).stream(anyList(), any(StreamOptions.class), any(RuntimeContext.class));
    }

    @Test
    void testExternalOnlyModeRecordsMergeModeAndKeepsSharedToolkitIntact() {
        Toolkit toolkit = new Toolkit();
        SchemaOnlyTool existingTool = schemaOnlyTool("agent_lookup");
        SchemaOnlyTool existingSharedTool = schemaOnlyTool("shared_lookup");
        toolkit.registerAgentTool(existingTool);
        toolkit.registerAgentTool(existingSharedTool);
        when(mockAgent.getToolkit()).thenReturn(toolkit);
        when(mockAgent.stream(anyList(), any(StreamOptions.class), any(RuntimeContext.class)))
                .thenAnswer(
                        invocation -> {
                            RuntimeContext rc = invocation.getArgument(2);
                            ToolRequestConfig requestConfig = rc.getToolRequestConfig();
                            // EXTERNAL_ONLY 记录在请求配置中；后端工具仅在共享 Toolkit 的
                            // 组合阶段隐藏，不会被修改。
                            assertEquals(ToolMergeMode.EXTERNAL_ONLY, requestConfig.mergeMode());
                            assertTrue(
                                    requestConfig.externalTools().containsKey("frontend_lookup"));
                            assertTrue(requestConfig.externalTools().containsKey("shared_lookup"));
                            // 运行期间共享工具集保持完整。
                            assertSame(existingTool, toolkit.getTool("agent_lookup"));
                            assertSame(existingSharedTool, toolkit.getTool("shared_lookup"));
                            return Flux.empty();
                        });

        AguiAgentAdapter externalOnlyAdapter =
                new AguiAgentAdapter(
                        mockAgent,
                        AguiAdapterConfig.builder()
                                .toolMergeMode(ToolMergeMode.EXTERNAL_ONLY)
                                .build());
        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-external-only")
                        .runId("run-external-only")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Hello")))
                        .tools(
                                List.of(
                                        frontendTool("frontend_lookup"),
                                        frontendTool("shared_lookup")))
                        .build();

        externalOnlyAdapter.run(input).collectList().block();

        verify(mockAgent).stream(anyList(), any(StreamOptions.class), any(RuntimeContext.class));

        // 从未修改共享工具集，无需恢复或清理。
        assertSame(existingTool, toolkit.getTool("agent_lookup"));
        assertSame(existingSharedTool, toolkit.getTool("shared_lookup"));
        assertNull(toolkit.getTool("frontend_lookup"));
    }

    @Test
    void testRunDoesNotRevertAgentToolRegisteredDuringStream() {
        // 适配器组装不可变请求配置，不修改工具集，因此智能体运行时
        // 自行注册到共享工具集的工具会保留。
        Toolkit toolkit = new Toolkit();
        SchemaOnlyTool existingTool = schemaOnlyTool("shared_lookup");
        SchemaOnlyTool replacementTool = schemaOnlyTool("shared_lookup");
        toolkit.registerAgentTool(existingTool);
        when(mockAgent.getToolkit()).thenReturn(toolkit);
        when(mockAgent.stream(anyList(), any(StreamOptions.class), any(RuntimeContext.class)))
                .thenAnswer(
                        invocation -> {
                            toolkit.registerAgentTool(replacementTool);
                            return Flux.empty();
                        });

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-replaced-tool")
                        .runId("run-replaced-tool")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Hello")))
                        .tools(List.of(frontendTool("shared_lookup")))
                        .build();

        adapter.run(input).collectList().block();

        assertSame(replacementTool, toolkit.getTool("shared_lookup"));
    }

    @Test
    void testRunReturnsRunStartedAndFinishedEvents() {
        // 智能体返回空流。
        when(mockAgent.stream(anyList(), any(StreamOptions.class))).thenReturn(Flux.empty());

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Hello")))
                        .build();

        List<AguiEvent> events = adapter.run(input).collectList().block();

        assertNotNull(events);
        assertEquals(2, events.size());
        assertInstanceOf(AguiEvent.RunStarted.class, events.get(0));
        assertInstanceOf(AguiEvent.RunFinished.class, events.get(1));

        AguiEvent.RunStarted started = (AguiEvent.RunStarted) events.get(0);
        assertEquals("thread-1", started.getThreadId());
        assertEquals("run-1", started.getRunId());
    }

    @Test
    void testRunWithTextReasoningEvent() {
        Msg reasoningMsg =
                Msg.builder()
                        .id("msg-r1")
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("Hello, I'm here to help!").build())
                        .build();

        Event reasoningEvent = new Event(EventType.REASONING, reasoningMsg, false);
        when(mockAgent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(Flux.just(reasoningEvent));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Hello")))
                        .build();

        List<AguiEvent> events = adapter.run(input).collectList().block();

        assertNotNull(events);
        // 事件顺序：RunStarted、TextMessageStart、TextMessageContent、TextMessageEnd、RunFinished。
        assertTrue(events.size() >= 4);

        // 验证事件顺序。
        assertInstanceOf(AguiEvent.RunStarted.class, events.get(0));

        // 查找 TextMessage 事件。
        boolean hasTextStart =
                events.stream().anyMatch(e -> e instanceof AguiEvent.TextMessageStart);
        boolean hasTextContent =
                events.stream().anyMatch(e -> e instanceof AguiEvent.TextMessageContent);
        boolean hasTextEnd = events.stream().anyMatch(e -> e instanceof AguiEvent.TextMessageEnd);

        assertTrue(hasTextStart, "Should have TextMessageStart");
        assertTrue(hasTextContent, "Should have TextMessageContent");
        assertTrue(hasTextEnd, "Should have TextMessageEnd");

        assertInstanceOf(AguiEvent.RunFinished.class, events.get(events.size() - 1));
    }

    @Test
    void testRunWithStreamingTextEvents() {
        // 模拟流式输出：多个事件具有相同消息 ID。
        Msg chunk1 =
                Msg.builder()
                        .id("msg-stream")
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("Hello").build())
                        .build();

        Msg chunk2 =
                Msg.builder()
                        .id("msg-stream")
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text(", world!").build())
                        .build();

        Event event1 = new Event(EventType.REASONING, chunk1, false);
        Event event2 = new Event(EventType.REASONING, chunk2, false);

        when(mockAgent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(Flux.just(event1, event2));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Hi")))
                        .build();

        List<AguiEvent> events = adapter.run(input).collectList().block();

        assertNotNull(events);

        // 统计 TextMessageContent 事件，应为 2 个，每个分块一个。
        long contentCount =
                events.stream().filter(e -> e instanceof AguiEvent.TextMessageContent).count();
        assertEquals(2, contentCount, "Should have 2 content events for streaming");

        // 相同消息 ID 应仅有 1 个 TextMessageStart。
        long startCount =
                events.stream().filter(e -> e instanceof AguiEvent.TextMessageStart).count();
        assertEquals(1, startCount, "Should have only 1 start event for same message ID");
    }

    @Test
    void testRunWithSummaryEventUsesTextMessages() {
        Msg summaryChunk =
                Msg.builder()
                        .id("msg-summary")
                        .role(MsgRole.ASSISTANT)
                        .content(
                                TextBlock.builder()
                                        .text("Here is the conversation summary.")
                                        .build())
                        .build();

        Msg summaryFinal =
                Msg.builder()
                        .id("msg-summary")
                        .role(MsgRole.ASSISTANT)
                        .content(
                                TextBlock.builder()
                                        .text("Here is the conversation summary.")
                                        .build())
                        .build();

        Event summaryChunkEvent = new Event(EventType.SUMMARY, summaryChunk, false);
        Event summaryFinalEvent = new Event(EventType.SUMMARY, summaryFinal, true);

        when(mockAgent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(Flux.just(summaryChunkEvent, summaryFinalEvent));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Hello")))
                        .build();

        List<AguiEvent> events = adapter.run(input).collectList().block();

        assertNotNull(events);

        AguiEvent.TextMessageContent summaryContent =
                events.stream()
                        .filter(e -> e instanceof AguiEvent.TextMessageContent)
                        .map(e -> (AguiEvent.TextMessageContent) e)
                        .findFirst()
                        .orElse(null);

        assertNotNull(summaryContent, "Should convert SUMMARY to TextMessageContent");
        assertEquals("msg-summary", summaryContent.messageId());
        assertEquals("Here is the conversation summary.", summaryContent.delta());

        long textEndCount =
                events.stream().filter(e -> e instanceof AguiEvent.TextMessageEnd).count();
        assertEquals(1, textEndCount, "Should close the summary text message exactly once");
    }

    @Test
    void testRunWithStreamingSummaryEvents() {
        Msg summaryChunk1 =
                Msg.builder()
                        .id("msg-summary")
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("First part. ").build())
                        .build();

        Msg summaryChunk2 =
                Msg.builder()
                        .id("msg-summary")
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("Second part.").build())
                        .build();

        Msg summaryFinal =
                Msg.builder()
                        .id("msg-summary")
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("First part. Second part.").build())
                        .build();

        Event event1 = new Event(EventType.SUMMARY, summaryChunk1, false);
        Event event2 = new Event(EventType.SUMMARY, summaryChunk2, false);
        Event event3 = new Event(EventType.SUMMARY, summaryFinal, true);

        when(mockAgent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(Flux.just(event1, event2, event3));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Hello")))
                        .build();

        List<AguiEvent> events = adapter.run(input).collectList().block();

        assertNotNull(events);

        long contentCount =
                events.stream().filter(e -> e instanceof AguiEvent.TextMessageContent).count();
        assertEquals(2, contentCount, "Should stream summary chunks as text deltas");

        long startCount =
                events.stream().filter(e -> e instanceof AguiEvent.TextMessageStart).count();
        assertEquals(1, startCount, "Should only start the summary message once");

        long endCount = events.stream().filter(e -> e instanceof AguiEvent.TextMessageEnd).count();
        assertEquals(1, endCount, "Should only end the summary message once");
    }

    @Test
    void testRunWithToolCallEvent() {
        Msg toolCallMsg =
                Msg.builder()
                        .id("msg-tc1")
                        .role(MsgRole.ASSISTANT)
                        .content(
                                ToolUseBlock.builder()
                                        .id("tc-1")
                                        .name("get_weather")
                                        .input(Map.of("city", "Beijing"))
                                        .content("{\"city\":\"Beijing\"}")
                                        .build())
                        .build();

        Event toolCallEvent = new Event(EventType.REASONING, toolCallMsg, false);
        when(mockAgent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(Flux.just(toolCallEvent));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Weather?")))
                        .build();

        List<AguiEvent> events = adapter.run(input).collectList().block();

        assertNotNull(events);

        // 查找 ToolCallStart。
        AguiEvent.ToolCallStart toolStart =
                events.stream()
                        .filter(e -> e instanceof AguiEvent.ToolCallStart)
                        .map(e -> (AguiEvent.ToolCallStart) e)
                        .findFirst()
                        .orElse(null);

        assertNotNull(toolStart, "Should have ToolCallStart");
        assertEquals("tc-1", toolStart.toolCallId());
        assertEquals("get_weather", toolStart.toolCallName());

        // 查找 ToolCallArgs。
        AguiEvent.ToolCallArgs toolArgs =
                events.stream()
                        .filter(e -> e instanceof AguiEvent.ToolCallArgs)
                        .map(e -> (AguiEvent.ToolCallArgs) e)
                        .findFirst()
                        .orElse(null);

        assertNotNull(toolArgs, "Should have ToolCallArgs");
        assertTrue(toolArgs.delta().contains("Beijing"));
    }

    @Test
    void testRunWithToolResultEvent() {
        // 首先：工具调用。
        Msg toolCallMsg =
                Msg.builder()
                        .id("msg-tc1")
                        .role(MsgRole.ASSISTANT)
                        .content(
                                ToolUseBlock.builder()
                                        .id("tc-1")
                                        .name("calculator")
                                        .input(Map.of("expr", "2+2"))
                                        .build())
                        .build();

        // 其次：工具结果。
        Msg toolResultMsg =
                Msg.builder()
                        .id("msg-tr1")
                        .role(MsgRole.TOOL)
                        .content(
                                ToolResultBlock.builder()
                                        .id("tc-1")
                                        .output(TextBlock.builder().text("4").build())
                                        .build())
                        .build();

        Event toolCallEvent = new Event(EventType.REASONING, toolCallMsg, true);
        Event toolResultEvent = new Event(EventType.TOOL_RESULT, toolResultMsg, true);

        when(mockAgent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(Flux.just(toolCallEvent, toolResultEvent));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Calculate")))
                        .build();

        List<AguiEvent> events = adapter.run(input).collectList().block();

        assertNotNull(events);

        // 查找 ToolCallEnd，它在结果前触发。
        AguiEvent.ToolCallEnd toolEnd =
                events.stream()
                        .filter(e -> e instanceof AguiEvent.ToolCallEnd)
                        .map(e -> (AguiEvent.ToolCallEnd) e)
                        .findFirst()
                        .orElse(null);

        assertNotNull(toolEnd, "Should have ToolCallEnd");
        assertEquals("tc-1", toolEnd.toolCallId());
        // ToolCallEnd 不再携带结果。

        // 查找由工具结果触发的 ToolCallResult。
        AguiEvent.ToolCallResult toolResult =
                events.stream()
                        .filter(e -> e instanceof AguiEvent.ToolCallResult)
                        .map(e -> (AguiEvent.ToolCallResult) e)
                        .findFirst()
                        .orElse(null);

        assertNotNull(toolResult, "Should have ToolCallResult");
        assertEquals("tc-1", toolResult.toolCallId());
        assertEquals("4", toolResult.content());
    }

    @Test
    void testRunWithAgentError() {
        when(mockAgent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(Flux.error(new RuntimeException("Agent error")));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Hello")))
                        .build();

        List<AguiEvent> events = adapter.run(input).collectList().block();

        assertNotNull(events);

        // 应包含 RunStarted、RunError。
        assertEquals(2, events.size());
        assertInstanceOf(AguiEvent.RunStarted.class, events.get(0));

        // 查找 RunError 事件。
        AguiEvent.RunError errorEvent =
                events.stream()
                        .filter(e -> e instanceof AguiEvent.RunError)
                        .map(e -> (AguiEvent.RunError) e)
                        .findFirst()
                        .orElse(null);

        assertNotNull(errorEvent, "Should have RunError event");
        assertTrue(errorEvent.message().contains("Agent error"));
        assertInstanceOf(AguiEvent.RunError.class, events.get(events.size() - 1));
    }

    @Test
    void testRunWithEmptyMessages() {
        when(mockAgent.stream(anyList(), any(StreamOptions.class))).thenReturn(Flux.empty());

        RunAgentInput input = RunAgentInput.builder().threadId("thread-1").runId("run-1").build();

        List<AguiEvent> events = adapter.run(input).collectList().block();

        assertNotNull(events);
        assertEquals(2, events.size());
        assertInstanceOf(AguiEvent.RunStarted.class, events.get(0));
        assertInstanceOf(AguiEvent.RunFinished.class, events.get(1));
    }

    @Test
    void testRunWithDisabledToolCallArgs() {
        AguiAdapterConfig config = AguiAdapterConfig.builder().emitToolCallArgs(false).build();
        AguiAgentAdapter adapterNoArgs = new AguiAgentAdapter(mockAgent, config);

        Msg toolCallMsg =
                Msg.builder()
                        .id("msg-tc1")
                        .role(MsgRole.ASSISTANT)
                        .content(
                                ToolUseBlock.builder()
                                        .id("tc-1")
                                        .name("test_tool")
                                        .input(Map.of("param", "value"))
                                        .build())
                        .build();

        Event toolCallEvent = new Event(EventType.REASONING, toolCallMsg, false);
        when(mockAgent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(Flux.just(toolCallEvent));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Test")))
                        .build();

        List<AguiEvent> events = adapterNoArgs.run(input).collectList().block();

        assertNotNull(events);

        // 不应有 ToolCallArgs。
        boolean hasToolArgs = events.stream().anyMatch(e -> e instanceof AguiEvent.ToolCallArgs);
        assertTrue(!hasToolArgs, "Should NOT have ToolCallArgs when disabled");

        // 仍应有 ToolCallStart。
        boolean hasToolStart = events.stream().anyMatch(e -> e instanceof AguiEvent.ToolCallStart);
        assertTrue(hasToolStart, "Should still have ToolCallStart");
    }

    @Test
    void testTextAndToolCallMixedContent() {
        // 同时包含文本与工具调用的消息。
        Msg mixedMsg =
                Msg.builder()
                        .id("msg-mixed")
                        .role(MsgRole.ASSISTANT)
                        .content(
                                List.of(
                                        TextBlock.builder()
                                                .text("Let me check the weather for you.")
                                                .build(),
                                        ToolUseBlock.builder()
                                                .id("tc-1")
                                                .name("get_weather")
                                                .input(Map.of("city", "Shanghai"))
                                                .build()))
                        .build();

        Event mixedEvent = new Event(EventType.REASONING, mixedMsg, false);
        when(mockAgent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(Flux.just(mixedEvent));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Weather?")))
                        .build();

        List<AguiEvent> events = adapter.run(input).collectList().block();

        assertNotNull(events);

        // 应同时有文本消息事件与工具调用事件。
        boolean hasTextStart =
                events.stream().anyMatch(e -> e instanceof AguiEvent.TextMessageStart);
        boolean hasTextContent =
                events.stream().anyMatch(e -> e instanceof AguiEvent.TextMessageContent);
        boolean hasTextEnd = events.stream().anyMatch(e -> e instanceof AguiEvent.TextMessageEnd);
        boolean hasToolStart = events.stream().anyMatch(e -> e instanceof AguiEvent.ToolCallStart);

        assertTrue(hasTextStart, "Should have TextMessageStart");
        assertTrue(hasTextContent, "Should have TextMessageContent");
        assertTrue(hasTextEnd, "Should have TextMessageEnd");
        assertTrue(hasToolStart, "Should have ToolCallStart");
    }

    @Test
    void testDuplicateToolCallStartNotEmitted() {
        // 相同工具调用出现在多个事件中，模拟流式场景。
        Msg toolCall1 =
                Msg.builder()
                        .id("msg-tc")
                        .role(MsgRole.ASSISTANT)
                        .content(
                                ToolUseBlock.builder()
                                        .id("tc-1")
                                        .name("tool")
                                        .input(Map.of())
                                        .build())
                        .build();

        Msg toolCall2 =
                Msg.builder()
                        .id("msg-tc")
                        .role(MsgRole.ASSISTANT)
                        .content(
                                ToolUseBlock.builder()
                                        .id("tc-1") // 相同 ID
                                        .name("tool")
                                        .input(Map.of())
                                        .build())
                        .build();

        Event event1 = new Event(EventType.REASONING, toolCall1, false);
        Event event2 = new Event(EventType.REASONING, toolCall2, true);

        when(mockAgent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(Flux.just(event1, event2));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Test")))
                        .build();

        List<AguiEvent> events = adapter.run(input).collectList().block();

        assertNotNull(events);

        // 去重后应仅有 1 个 ToolCallStart。
        long toolStartCount =
                events.stream().filter(e -> e instanceof AguiEvent.ToolCallStart).count();
        assertEquals(1, toolStartCount, "Should only emit 1 ToolCallStart per tool ID");
    }

    @Test
    void testReactiveStreamCompletion() {
        Msg reasoningMsg =
                Msg.builder()
                        .id("msg-1")
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("Done").build())
                        .build();

        Event event = new Event(EventType.REASONING, reasoningMsg, false);
        when(mockAgent.stream(anyList(), any(StreamOptions.class))).thenReturn(Flux.just(event));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("t1")
                        .runId("r1")
                        .messages(List.of(AguiMessage.userMessage("m1", "Hi")))
                        .build();

        StepVerifier.create(adapter.run(input))
                .expectNextMatches(e -> e instanceof AguiEvent.RunStarted)
                .expectNextMatches(e -> e instanceof AguiEvent.TextMessageStart)
                .expectNextMatches(e -> e instanceof AguiEvent.TextMessageContent)
                .expectNextMatches(e -> e instanceof AguiEvent.TextMessageEnd)
                .expectNextMatches(e -> e instanceof AguiEvent.RunFinished)
                .verifyComplete();
    }

    @Test
    void testToolUseBlockWithNullId() {
        Msg toolCallMsg =
                Msg.builder()
                        .id("msg-tc1")
                        .role(MsgRole.ASSISTANT)
                        .content(
                                ToolUseBlock.builder()
                                        .id(null)
                                        .name("test_tool")
                                        .input(Map.of("param", "value"))
                                        .content("{\"param\":\"value\"}")
                                        .build())
                        .build();

        Event toolCallEvent = new Event(EventType.REASONING, toolCallMsg, false);
        when(mockAgent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(Flux.just(toolCallEvent));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Test")))
                        .build();

        StepVerifier.create(adapter.run(input))
                .expectNextMatches(e -> e instanceof AguiEvent.RunStarted)
                .expectNextMatches(e -> e instanceof AguiEvent.ToolCallStart)
                .expectNextMatches(e -> e instanceof AguiEvent.ToolCallArgs)
                .expectNextMatches(e -> e instanceof AguiEvent.ToolCallEnd)
                .expectNextMatches(e -> e instanceof AguiEvent.RunFinished)
                .verifyComplete();
    }

    @Test
    void testTextMessageEndNotDuplicatedWhenLastEventAfterToolCall() {
        // 测试文本消息被工具调用打断后，若最后事件
        // 包含相同消息 ID 的文本块，仅发出一个 TextMessageEnd。
        String msgId = "msg-text";
        Msg firstMsg =
                Msg.builder()
                        .id(msgId)
                        .role(MsgRole.ASSISTANT)
                        .content(List.of(TextBlock.builder().text("first part").build()))
                        .build();

        Msg toolCall1 =
                Msg.builder()
                        .id("msg-tc")
                        .role(MsgRole.ASSISTANT)
                        .content(
                                ToolUseBlock.builder()
                                        .id("tc-1")
                                        .name("tool")
                                        .input(Map.of())
                                        .build())
                        .build();
        Msg lastMsg =
                Msg.builder()
                        .id(msgId)
                        .role(MsgRole.ASSISTANT)
                        .content(List.of(TextBlock.builder().text("last part").build()))
                        .build();

        Event firstEvent = new Event(EventType.REASONING, firstMsg, false);
        Event toolCallEvent = new Event(EventType.REASONING, toolCall1, false);
        Event lastEvent = new Event(EventType.REASONING, lastMsg, true);
        when(mockAgent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(Flux.just(firstEvent, toolCallEvent, lastEvent));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Test")))
                        .build();

        List<AguiEvent> events = adapter.run(input).collectList().block();

        assertNotNull(events);

        // 同一消息 ID 应恰好有一个 TextMessageEnd。
        long textEndCount =
                events.stream()
                        .filter(e -> e instanceof AguiEvent.TextMessageEnd)
                        .filter(
                                e -> {
                                    AguiEvent.TextMessageEnd end = (AguiEvent.TextMessageEnd) e;
                                    return msgId.equals(end.messageId());
                                })
                        .count();
        assertEquals(1, textEndCount, "Should have exactly 1 TextMessageEnd per message ID");
    }

    @Test
    void testToolUseBlockWithNullIdGeneratesUUID() {
        Msg toolCallMsg =
                Msg.builder()
                        .id("msg-tc1")
                        .role(MsgRole.ASSISTANT)
                        .content(
                                ToolUseBlock.builder()
                                        .id(null) // ID 为 null
                                        .name("test_tool")
                                        .input(Map.of("param", "value"))
                                        .build())
                        .build();

        Event toolCallEvent = new Event(EventType.REASONING, toolCallMsg, false);
        when(mockAgent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(Flux.just(toolCallEvent));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Test")))
                        .build();

        List<AguiEvent> events = adapter.run(input).collectList().block();

        assertNotNull(events);

        AguiEvent.ToolCallStart toolStart =
                events.stream()
                        .filter(e -> e instanceof AguiEvent.ToolCallStart)
                        .map(e -> (AguiEvent.ToolCallStart) e)
                        .findFirst()
                        .orElse(null);

        assertNotNull(toolStart, "Should have ToolCallStart");
        // 应已生成 UUID，为非 null、非空字符串。
        assertNotNull(toolStart.toolCallId(), "Tool call ID should not be null");
        assertTrue(!toolStart.toolCallId().isEmpty(), "Tool call ID should not be empty");
    }

    @Test
    void testTextMessageEndWithLastEventDirectly() {
        // 测试最后事件含文本块且消息尚未结束时，
        // 通过新增 hasEndedMessage 检查发出 TextMessageEnd。
        // 此测试专门覆盖新增的最后事件结束检查代码路径。
        String msgId = "msg-text";
        Msg textMsg =
                Msg.builder()
                        .id(msgId)
                        .role(MsgRole.ASSISTANT)
                        .content(List.of(TextBlock.builder().text("Hello world").build()))
                        .build();

        // 直接创建最后事件（isLast = true），不含此前事件。
        Event lastEvent = new Event(EventType.REASONING, textMsg, true);
        when(mockAgent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(Flux.just(lastEvent));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Test")))
                        .build();

        List<AguiEvent> events = adapter.run(input).collectList().block();

        assertNotNull(events);

        // 验证 TextMessageEnd 恰好发出一次。
        long textEndCount =
                events.stream()
                        .filter(e -> e instanceof AguiEvent.TextMessageEnd)
                        .filter(
                                e -> {
                                    AguiEvent.TextMessageEnd end = (AguiEvent.TextMessageEnd) e;
                                    return msgId.equals(end.messageId());
                                })
                        .count();
        assertEquals(1, textEndCount, "Should have exactly 1 TextMessageEnd");

        // 验证事件顺序。
        assertInstanceOf(AguiEvent.RunStarted.class, events.get(0));
        assertInstanceOf(AguiEvent.TextMessageStart.class, events.get(1));
        assertInstanceOf(AguiEvent.TextMessageEnd.class, events.get(2));
        assertInstanceOf(AguiEvent.RunFinished.class, events.get(3));
    }

    @Test
    void testExtractToolResultTextWithMultipleTextBlocks() {
        // 测试 extractToolResultText 处理多个 TextBlock，应添加换行。
        Msg toolResultMsg =
                Msg.builder()
                        .id("msg-tr1")
                        .role(MsgRole.TOOL)
                        .content(
                                ToolResultBlock.builder()
                                        .id("tc-1")
                                        .output(
                                                List.of(
                                                        TextBlock.builder().text("Line 1").build(),
                                                        TextBlock.builder().text("Line 2").build(),
                                                        TextBlock.builder().text("Line 3").build()))
                                        .build())
                        .build();

        Event toolResultEvent = new Event(EventType.TOOL_RESULT, toolResultMsg, true);

        when(mockAgent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(Flux.just(toolResultEvent));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Test")))
                        .build();

        List<AguiEvent> events = adapter.run(input).collectList().block();

        assertNotNull(events);

        long toolResultCount =
                events.stream().filter(e -> e instanceof AguiEvent.ToolCallResult).count();
        assertEquals(1, toolResultCount, "Should have ToolCallResult");

        // 验证工具结果内容。
        AguiEvent.ToolCallResult toolResult =
                events.stream()
                        .filter(e -> e instanceof AguiEvent.ToolCallResult)
                        .map(e -> (AguiEvent.ToolCallResult) e)
                        .findFirst()
                        .orElse(null);

        assertNotNull(toolResult, "Should have ToolCallResult");
        // 文本块之间应包含换行。
        assertTrue(toolResult.content().contains("\n"), "Should have newlines between text blocks");
        assertTrue(toolResult.content().contains("Line 1"), "Should contain Line 1");
        assertTrue(toolResult.content().contains("Line 2"), "Should contain Line 2");
        assertTrue(toolResult.content().contains("Line 3"), "Should contain Line 3");
    }

    @Test
    void testExtractToolResultTextWithEmptyOutput() {
        // 测试 extractToolResultText 处理空输出。
        Msg toolResultMsg =
                Msg.builder()
                        .id("msg-tr1")
                        .role(MsgRole.TOOL)
                        .content(ToolResultBlock.builder().id("tc-1").output(List.of()).build())
                        .build();

        Event toolResultEvent = new Event(EventType.TOOL_RESULT, toolResultMsg, true);

        when(mockAgent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(Flux.just(toolResultEvent));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Test")))
                        .build();

        List<AguiEvent> events = adapter.run(input).collectList().block();

        assertNotNull(events);

        AguiEvent.ToolCallResult toolResult =
                events.stream()
                        .filter(e -> e instanceof AguiEvent.ToolCallResult)
                        .map(e -> (AguiEvent.ToolCallResult) e)
                        .findFirst()
                        .orElse(null);

        assertNotNull(toolResult, "Should have ToolCallResult");
        assertTrue(
                toolResult.content() == null || toolResult.content().isEmpty(),
                "Empty output should result in null or empty content");
    }

    @Test
    void testToolUseBlockWithNullOrEmptyContent() {
        // 测试 ToolUseBlock 内容为 null 或空时不发出 ToolCallArgs。
        Msg toolCallMsg =
                Msg.builder()
                        .id("msg-tc1")
                        .role(MsgRole.ASSISTANT)
                        .content(
                                ToolUseBlock.builder()
                                        .id("tc-1")
                                        .name("test_tool")
                                        .input(Map.of("param", "value"))
                                        .content(null) // 内容为 null
                                        .build())
                        .build();

        Event toolCallEvent = new Event(EventType.REASONING, toolCallMsg, false);
        when(mockAgent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(Flux.just(toolCallEvent));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Test")))
                        .build();

        List<AguiEvent> events = adapter.run(input).collectList().block();

        assertNotNull(events);

        // 仍应有 ToolCallStart。
        boolean hasToolStart = events.stream().anyMatch(e -> e instanceof AguiEvent.ToolCallStart);
        assertTrue(hasToolStart, "Should have ToolCallStart");

        // 内容为 null 时不应有 ToolCallArgs。
        boolean hasToolArgs = events.stream().anyMatch(e -> e instanceof AguiEvent.ToolCallArgs);
        assertTrue(!hasToolArgs, "Should NOT have ToolCallArgs when content is null");
    }

    @Test
    void testRunWithThinkingBlockDefaultDisabled() {
        // 测试默认禁用推理事件。
        Msg reasoningMsg =
                Msg.builder()
                        .id("msg-r1")
                        .role(MsgRole.ASSISTANT)
                        .content(
                                ThinkingBlock.builder()
                                        .thinking("Let me think about this problem step by step...")
                                        .build())
                        .build();

        Event reasoningEvent = new Event(EventType.REASONING, reasoningMsg, false);
        when(mockAgent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(Flux.just(reasoningEvent));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Hi")))
                        .build();

        List<AguiEvent> events = adapter.run(input).collectList().block();

        assertNotNull(events);

        // 默认禁用时，不应有任何推理事件。
        boolean hasReasoningMessageStart =
                events.stream().anyMatch(e -> e instanceof AguiEvent.ReasoningMessageStart);
        boolean hasReasoningMessageContent =
                events.stream().anyMatch(e -> e instanceof AguiEvent.ReasoningMessageContent);

        assertTrue(
                !hasReasoningMessageStart, "Should NOT have ReasoningMessageStart when disabled");
        assertTrue(
                !hasReasoningMessageContent,
                "Should NOT have ReasoningMessageContent when disabled");
    }

    @Test
    void testRunWithThinkingBlockEvent() {
        // 测试启用后的推理事件。
        AguiAdapterConfig config = AguiAdapterConfig.builder().enableReasoning(true).build();
        AguiAgentAdapter adapterWithReasoning = new AguiAgentAdapter(mockAgent, config);

        Msg reasoningMsg =
                Msg.builder()
                        .id("msg-r1")
                        .role(MsgRole.ASSISTANT)
                        .content(
                                ThinkingBlock.builder()
                                        .thinking("Let me think about this problem step by step...")
                                        .build())
                        .build();

        Event reasoningEvent = new Event(EventType.REASONING, reasoningMsg, false);
        when(mockAgent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(Flux.just(reasoningEvent));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Hello")))
                        .build();

        List<AguiEvent> events = adapterWithReasoning.run(input).collectList().block();

        assertNotNull(events);

        // 查找推理事件。
        AguiEvent.ReasoningMessageStart reasoningMessageStart =
                events.stream()
                        .filter(e -> e instanceof AguiEvent.ReasoningMessageStart)
                        .map(e -> (AguiEvent.ReasoningMessageStart) e)
                        .findFirst()
                        .orElse(null);

        assertNotNull(reasoningMessageStart, "Should have ReasoningMessageStart");
        assertEquals("msg-r1", reasoningMessageStart.messageId());
        assertEquals("reasoning", reasoningMessageStart.role());

        AguiEvent.ReasoningMessageContent reasoningMessageContent =
                events.stream()
                        .filter(e -> e instanceof AguiEvent.ReasoningMessageContent)
                        .map(e -> (AguiEvent.ReasoningMessageContent) e)
                        .findFirst()
                        .orElse(null);

        assertNotNull(reasoningMessageContent, "Should have ReasoningMessageContent");
        assertTrue(
                reasoningMessageContent.delta().contains("think about this problem"),
                "Should contain thinking content");
    }

    @Test
    void testRunWithStreamingThinkingBlockEvents() {
        // 测试启用后的流式推理事件。
        AguiAdapterConfig config = AguiAdapterConfig.builder().enableReasoning(true).build();
        AguiAgentAdapter adapterWithReasoning = new AguiAgentAdapter(mockAgent, config);

        // 模拟流式思考：多个事件具有相同消息 ID。
        Msg thinkingChunk1 =
                Msg.builder()
                        .id("msg-thinking")
                        .role(MsgRole.ASSISTANT)
                        .content(ThinkingBlock.builder().thinking("First thought").build())
                        .build();

        Msg thinkingChunk2 =
                Msg.builder()
                        .id("msg-thinking")
                        .role(MsgRole.ASSISTANT)
                        .content(ThinkingBlock.builder().thinking("Second thought").build())
                        .build();

        Event event1 = new Event(EventType.REASONING, thinkingChunk1, false);
        Event event2 = new Event(EventType.REASONING, thinkingChunk2, false);

        when(mockAgent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(Flux.just(event1, event2));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Calculate")))
                        .build();

        List<AguiEvent> events = adapterWithReasoning.run(input).collectList().block();

        assertNotNull(events);

        // 统计 ReasoningMessageContent 事件。
        long reasoningMessageContentCount =
                events.stream().filter(e -> e instanceof AguiEvent.ReasoningMessageContent).count();
        assertEquals(
                2,
                reasoningMessageContentCount,
                "Should have 2 reasoning message content events for streaming");

        // 应仅有 1 个 ReasoningMessageStart。
        long reasoningMessageStartCount =
                events.stream().filter(e -> e instanceof AguiEvent.ReasoningMessageStart).count();
        assertEquals(
                1,
                reasoningMessageStartCount,
                "Should have only 1 start event for same reasoning message ID");
    }

    @Test
    void testStateLeakOnMultipleSubscriptions() {
        // 验证问题 #510 的修复。
        String bugMessageId = "chatcmpl-afaa1eae32eae120";
        String bugToolId = "chatcmpl-tool-ab42f73d312799c7";

        // 模拟首个流式文本分块。
        Msg textChunk =
                Msg.builder()
                        .id(bugMessageId)
                        .role(MsgRole.ASSISTANT)
                        .content(
                                List.of(
                                        TextBlock.builder()
                                                .text("Preparing to call the tool...")
                                                .build()))
                        .build();
        Event textEvent = new Event(EventType.REASONING, textChunk, false);

        Msg toolCallMsg =
                Msg.builder()
                        .id(bugMessageId)
                        .role(MsgRole.ASSISTANT)
                        .content(
                                List.of(
                                        ToolUseBlock.builder()
                                                .id(bugToolId)
                                                .name("getUniversityInfo")
                                                .input(Map.of())
                                                .build()))
                        .build();
        Event toolEvent = new Event(EventType.REASONING, toolCallMsg, false);

        // 模拟智能体流，使每次订阅返回相同事件。
        when(mockAgent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(Flux.just(textEvent, toolEvent));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("2010331348305129474")
                        .runId("17816087-d4aa-4743-baee-9989a4ab3c8d")
                        .messages(
                                List.of(
                                        AguiMessage.userMessage(
                                                "msg-1", "Check university score lines")))
                        .build();

        // 获取待测 Flux 管道。
        Flux<AguiEvent> resultFlux = adapter.run(input);

        // 首次订阅，模拟正常初始流式请求。
        List<AguiEvent> firstRunEvents = resultFlux.collectList().block();
        assertNotNull(firstRunEvents);

        long firstRunStartCount =
                firstRunEvents.stream()
                        .filter(e -> e instanceof AguiEvent.TextMessageStart)
                        .count();
        assertEquals(1, firstRunStartCount, "First execution should contain 1 TextMessageStart");

        // 第二次订阅，模拟适配器层自动重试或缓冲区刷新。
        List<AguiEvent> secondRunEvents = resultFlux.collectList().block();
        assertNotNull(secondRunEvents);

        long secondRunStartCount =
                secondRunEvents.stream()
                        .filter(e -> e instanceof AguiEvent.TextMessageStart)
                        .count();

        // 验证状态隔离有效，第二次订阅应正常发出 START。
        assertEquals(
                1,
                secondRunStartCount,
                "State should be isolated; second execution should contain 1 TextMessageStart");

        // 验证仍发出 CONTENT。
        long secondRunContentCount =
                secondRunEvents.stream()
                        .filter(e -> e instanceof AguiEvent.TextMessageContent)
                        .count();
        assertTrue(secondRunContentCount > 0, "Second execution should contain TextMessageContent");
    }

    @Test
    void testToolCallStartBackfillWithoutCache() {
        Msg toolResultMsg =
                Msg.builder()
                        .id("msg-tr1")
                        .role(MsgRole.TOOL)
                        .content(
                                ToolResultBlock.builder()
                                        .id("tc-unknown")
                                        .name("weather_lookup")
                                        .output(TextBlock.builder().text("result").build())
                                        .build())
                        .build();

        Event toolResultEvent = new Event(EventType.TOOL_RESULT, toolResultMsg, true);

        when(mockAgent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(Flux.just(toolResultEvent));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Hi")))
                        .build();

        List<AguiEvent> events = adapter.run(input).collectList().block();

        assertNotNull(events);

        // 未知工具结果应补发 ToolCallStart。
        long toolStartCount =
                events.stream().filter(e -> e instanceof AguiEvent.ToolCallStart).count();
        assertEquals(1, toolStartCount, "Should backfill ToolCallStart for unknown tool result");

        AguiEvent.ToolCallStart backfilledStart =
                events.stream()
                        .filter(e -> e instanceof AguiEvent.ToolCallStart)
                        .map(e -> (AguiEvent.ToolCallStart) e)
                        .findFirst()
                        .orElse(null);

        assertNotNull(backfilledStart, "Should backfill ToolCallStart");
        assertEquals("tc-unknown", backfilledStart.toolCallId());
        assertEquals("weather_lookup", backfilledStart.toolCallName());
    }

    @Test
    void testRunWithThinkingBlockEnabledShowsReasoningContent() {
        // 测试启用推理时的行为。
        AguiAdapterConfig config = AguiAdapterConfig.builder().enableReasoning(true).build();
        AguiAgentAdapter adapterWithReasoning = new AguiAgentAdapter(mockAgent, config);

        Msg reasoningMsg =
                Msg.builder()
                        .id("msg-r1")
                        .role(MsgRole.ASSISTANT)
                        .content(
                                ThinkingBlock.builder()
                                        .thinking("Let me think about this problem step by step...")
                                        .build())
                        .build();

        Event reasoningEvent = new Event(EventType.REASONING, reasoningMsg, false);
        when(mockAgent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(Flux.just(reasoningEvent));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Hi")))
                        .build();

        List<AguiEvent> events = adapterWithReasoning.run(input).collectList().block();

        assertNotNull(events);

        // 应有 ReasoningMessageContent 事件。
        long reasoningMessageContentCount =
                events.stream().filter(e -> e instanceof AguiEvent.ReasoningMessageContent).count();
        assertTrue(
                reasoningMessageContentCount > 0,
                "Should have reasoning message content events when enabled");

        // 应有 ReasoningMessageStart。
        long reasoningMessageStartCount =
                events.stream().filter(e -> e instanceof AguiEvent.ReasoningMessageStart).count();
        assertEquals(
                1,
                reasoningMessageStartCount,
                "Should have only 1 start event for reasoning message");
    }

    @Test
    void testRunWithThinkingAndTextMixedContent() {
        // 测试启用后推理与文本混合内容。
        AguiAdapterConfig config = AguiAdapterConfig.builder().enableReasoning(true).build();
        AguiAgentAdapter adapterWithReasoning = new AguiAgentAdapter(mockAgent, config);

        // 同时含思考与文本的消息。
        Msg mixedMsg =
                Msg.builder()
                        .id("msg-mixed")
                        .role(MsgRole.ASSISTANT)
                        .content(
                                List.of(
                                        ThinkingBlock.builder()
                                                .thinking("I need to analyze this carefully.")
                                                .build(),
                                        TextBlock.builder()
                                                .text("Based on my analysis, here's the answer.")
                                                .build()))
                        .build();

        Event mixedEvent = new Event(EventType.REASONING, mixedMsg, false);
        when(mockAgent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(Flux.just(mixedEvent));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Question?")))
                        .build();

        List<AguiEvent> events = adapterWithReasoning.run(input).collectList().block();

        assertNotNull(events);

        // 应有推理事件。
        boolean hasReasoningMessageStart =
                events.stream().anyMatch(e -> e instanceof AguiEvent.ReasoningMessageStart);
        boolean hasReasoningMessageContent =
                events.stream().anyMatch(e -> e instanceof AguiEvent.ReasoningMessageContent);

        // 应有普通文本消息事件。
        boolean hasTextStart =
                events.stream().anyMatch(e -> e instanceof AguiEvent.TextMessageStart);
        boolean hasTextContent =
                events.stream().anyMatch(e -> e instanceof AguiEvent.TextMessageContent);

        assertTrue(hasReasoningMessageStart, "Should have ReasoningMessageStart");
        assertTrue(hasReasoningMessageContent, "Should have ReasoningMessageContent");
        assertTrue(hasTextStart, "Should have TextMessageStart for text");
        assertTrue(hasTextContent, "Should have TextMessageContent for text");
    }

    @Test
    void testRunWithEmptyThinkingBlock() {
        // 即使启用，空思考块也应跳过。
        AguiAdapterConfig config = AguiAdapterConfig.builder().enableReasoning(true).build();
        AguiAgentAdapter adapterWithReasoning = new AguiAgentAdapter(mockAgent, config);

        Msg reasoningMsg =
                Msg.builder()
                        .id("msg-r1")
                        .role(MsgRole.ASSISTANT)
                        .content(ThinkingBlock.builder().thinking("").build())
                        .build();

        Event reasoningEvent = new Event(EventType.REASONING, reasoningMsg, false);
        when(mockAgent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(Flux.just(reasoningEvent));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Hello")))
                        .build();

        List<AguiEvent> events = adapterWithReasoning.run(input).collectList().block();

        assertNotNull(events);

        // 空思考不应产生推理事件。
        boolean hasReasoningMessageStart =
                events.stream().anyMatch(e -> e instanceof AguiEvent.ReasoningMessageStart);

        assertTrue(
                !hasReasoningMessageStart,
                "Should NOT have ReasoningMessageStart for empty thinking");
    }

    @Test
    void testRunWithThinkingBlockLastEvent() {
        // 测试启用时 ThinkingBlock 的 isLast() == true 分支。
        AguiAdapterConfig config = AguiAdapterConfig.builder().enableReasoning(true).build();
        AguiAgentAdapter adapterWithReasoning = new AguiAgentAdapter(mockAgent, config);

        Msg reasoningMsg =
                Msg.builder()
                        .id("msg-r1")
                        .role(MsgRole.ASSISTANT)
                        .content(ThinkingBlock.builder().thinking("Final thinking content").build())
                        .build();

        Event reasoningEvent = new Event(EventType.REASONING, reasoningMsg, true); // isLast 为 true
        when(mockAgent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(Flux.just(reasoningEvent));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Test")))
                        .build();

        List<AguiEvent> events = adapterWithReasoning.run(input).collectList().block();

        assertNotNull(events);

        // 应有 ReasoningMessageStart。
        AguiEvent.ReasoningMessageStart reasoningMessageStart =
                events.stream()
                        .filter(e -> e instanceof AguiEvent.ReasoningMessageStart)
                        .map(e -> (AguiEvent.ReasoningMessageStart) e)
                        .findFirst()
                        .orElse(null);

        assertNotNull(reasoningMessageStart, "Should have ReasoningMessageStart");

        // isLast=true 时应有 ReasoningMessageEnd。
        AguiEvent.ReasoningMessageEnd reasoningMessageEnd =
                events.stream()
                        .filter(e -> e instanceof AguiEvent.ReasoningMessageEnd)
                        .map(e -> (AguiEvent.ReasoningMessageEnd) e)
                        .findFirst()
                        .orElse(null);

        assertNotNull(reasoningMessageEnd, "Should have ReasoningMessageEnd when isLast=true");
    }

    @Test
    void testToolCallStartBackfillWithNullContent() {
        Msg toolCallMsg =
                Msg.builder()
                        .id("msg-tc1")
                        .role(MsgRole.ASSISTANT)
                        .content(
                                ToolUseBlock.builder()
                                        .id("tc-1")
                                        .name("test_tool")
                                        .input(Map.of("param", "value"))
                                        .content(null)
                                        .build())
                        .build();

        Event toolCallEvent = new Event(EventType.REASONING, toolCallMsg, false);
        when(mockAgent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(Flux.just(toolCallEvent));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Hello")))
                        .build();

        List<AguiEvent> events = adapter.run(input).collectList().block();

        assertNotNull(events);

        // 即使内容为 null，也应有 ToolCallStart。
        long toolStartCount =
                events.stream().filter(e -> e instanceof AguiEvent.ToolCallStart).count();
        assertEquals(1, toolStartCount, "Should have ToolCallStart even with null content");

        // 应有 ToolCallEnd。
        long toolEndCount = events.stream().filter(e -> e instanceof AguiEvent.ToolCallEnd).count();
        assertEquals(1, toolEndCount, "Should have ToolCallEnd");
    }

    @Test
    void testRunWithThinkingAndToolCallMixed() {
        // 测试启用后思考内容与工具调用混合。
        AguiAdapterConfig config = AguiAdapterConfig.builder().enableReasoning(true).build();
        AguiAgentAdapter adapterWithReasoning = new AguiAgentAdapter(mockAgent, config);

        Msg mixedMsg =
                Msg.builder()
                        .id("msg-mixed")
                        .role(MsgRole.ASSISTANT)
                        .content(
                                List.of(
                                        ThinkingBlock.builder()
                                                .thinking("I need to use a tool to get the answer.")
                                                .build(),
                                        ToolUseBlock.builder()
                                                .id("tc-1")
                                                .name("get_weather")
                                                .input(Map.of("city", "Beijing"))
                                                .build()))
                        .build();

        Event mixedEvent = new Event(EventType.REASONING, mixedMsg, false);
        when(mockAgent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(Flux.just(mixedEvent));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Test")))
                        .build();

        List<AguiEvent> events = adapterWithReasoning.run(input).collectList().block();

        assertNotNull(events);

        // 应有推理事件。
        boolean hasReasoningMessageStart =
                events.stream().anyMatch(e -> e instanceof AguiEvent.ReasoningMessageStart);
        boolean hasReasoningMessageContent =
                events.stream().anyMatch(e -> e instanceof AguiEvent.ReasoningMessageContent);

        // 应有工具调用事件。
        boolean hasToolStart = events.stream().anyMatch(e -> e instanceof AguiEvent.ToolCallStart);

        assertTrue(hasReasoningMessageStart, "Should have ReasoningMessageStart");
        assertTrue(hasReasoningMessageContent, "Should have ReasoningMessageContent");
        assertTrue(hasToolStart, "Should have ToolCallStart for tool call");

        int reasoningStartIdx = -1;
        int reasoningContentIdx = -1;
        int reasoningEndIdx = -1;
        int toolStartIdx = -1;

        for (int i = 0; i < events.size(); i++) {
            AguiEvent e = events.get(i);
            if (reasoningStartIdx < 0 && e instanceof AguiEvent.ReasoningMessageStart) {
                reasoningStartIdx = i;
            } else if (reasoningContentIdx < 0 && e instanceof AguiEvent.ReasoningMessageContent) {
                reasoningContentIdx = i;
            } else if (reasoningEndIdx < 0 && e instanceof AguiEvent.ReasoningMessageEnd) {
                reasoningEndIdx = i;
            } else if (toolStartIdx < 0 && e instanceof AguiEvent.ToolCallStart) {
                toolStartIdx = i;
            }
        }

        assertTrue(reasoningStartIdx >= 0, "Should have ReasoningMessageStart");
        assertTrue(reasoningContentIdx >= 0, "Should have ReasoningMessageContent");
        assertTrue(reasoningEndIdx >= 0, "Should have ReasoningMessageEnd before tool call");
        assertTrue(toolStartIdx >= 0, "Should have ToolCallStart");

        assertTrue(
                reasoningStartIdx < reasoningContentIdx,
                "Reasoning start should be before content");
        assertTrue(reasoningContentIdx < reasoningEndIdx, "Reasoning content should be before end");
        assertTrue(
                reasoningEndIdx < toolStartIdx, "Reasoning should be closed before ToolCallStart");

        // 必须在 ToolCallStart 前显式关闭 ReasoningMessage。
        long reasoningEndCount =
                events.stream().filter(e -> e instanceof AguiEvent.ReasoningMessageEnd).count();
        assertEquals(1, reasoningEndCount, "Should emit exactly one ReasoningMessageEnd");
    }

    @Test
    void testToolCallStartBackfillWithEmptyContent() {
        Msg toolCallMsg =
                Msg.builder()
                        .id("msg-tc1")
                        .role(MsgRole.ASSISTANT)
                        .content(
                                ToolUseBlock.builder()
                                        .id("tc-1")
                                        .name("test_tool")
                                        .input(Map.of("param", "value"))
                                        .content("")
                                        .build())
                        .build();

        Event toolCallEvent = new Event(EventType.REASONING, toolCallMsg, false);
        when(mockAgent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(Flux.just(toolCallEvent));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Weather?")))
                        .build();

        List<AguiEvent> events = adapter.run(input).collectList().block();

        assertNotNull(events);

        // 即使内容为空，也应有工具调用事件。
        long toolStartCount =
                events.stream().filter(e -> e instanceof AguiEvent.ToolCallStart).count();
        assertEquals(1, toolStartCount, "Should have ToolCallStart even with empty content");

        long toolEndCount = events.stream().filter(e -> e instanceof AguiEvent.ToolCallEnd).count();
        assertEquals(1, toolEndCount, "Should have ToolCallEnd");
    }

    @Test
    void testRunWithStreamingThinkingBlockLastEvent() {
        // 测试启用时最后事件（isLast=true）的流式处理。
        AguiAdapterConfig config = AguiAdapterConfig.builder().enableReasoning(true).build();
        AguiAgentAdapter adapterWithReasoning = new AguiAgentAdapter(mockAgent, config);

        Msg thinkingChunk1 =
                Msg.builder()
                        .id("msg-thinking")
                        .role(MsgRole.ASSISTANT)
                        .content(ThinkingBlock.builder().thinking("First thought").build())
                        .build();

        Msg thinkingChunk2 =
                Msg.builder()
                        .id("msg-thinking")
                        .role(MsgRole.ASSISTANT)
                        .content(ThinkingBlock.builder().thinking("Second thought").build())
                        .build();

        Event event1 = new Event(EventType.REASONING, thinkingChunk1, false);
        Event event2 = new Event(EventType.REASONING, thinkingChunk2, true); // 最后事件

        when(mockAgent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(Flux.just(event1, event2));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Test")))
                        .build();

        List<AguiEvent> events = adapterWithReasoning.run(input).collectList().block();

        assertNotNull(events);

        long messageContentCount =
                events.stream().filter(e -> e instanceof AguiEvent.ReasoningMessageContent).count();
        assertTrue(
                messageContentCount >= 1, "Should have at least 1 reasoning message content event");

        // isLast=true 时应有 ReasoningMessageEnd。
        long messageEndCount =
                events.stream().filter(e -> e instanceof AguiEvent.ReasoningMessageEnd).count();
        assertEquals(1, messageEndCount, "Should have ReasoningMessageEnd when isLast=true");
    }

    @Test
    void testRunWithStreamingThinkingBlockFirstChunk() {
        // 测试启用时首个分块（isLast=false）的流式处理。
        AguiAdapterConfig config = AguiAdapterConfig.builder().enableReasoning(true).build();
        AguiAgentAdapter adapterWithReasoning = new AguiAgentAdapter(mockAgent, config);

        Msg thinkingChunk1 =
                Msg.builder()
                        .id("msg-thinking")
                        .role(MsgRole.ASSISTANT)
                        .content(ThinkingBlock.builder().thinking("First thought").build())
                        .build();

        Event event1 = new Event(EventType.REASONING, thinkingChunk1, false); // 不是最后事件

        when(mockAgent.stream(anyList(), any(StreamOptions.class))).thenReturn(Flux.just(event1));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Hi")))
                        .build();

        List<AguiEvent> events = adapterWithReasoning.run(input).collectList().block();

        assertNotNull(events);

        // 首个分块应有 ReasoningMessageContent。
        long messageContentCount =
                events.stream().filter(e -> e instanceof AguiEvent.ReasoningMessageContent).count();
        assertEquals(
                1,
                messageContentCount,
                "Should have 1 reasoning message content event for first chunk");

        // 最后事件应有 ReasoningMessageEnd。
        boolean hasMessageEnd =
                events.stream().anyMatch(e -> e instanceof AguiEvent.ReasoningMessageEnd);
        assertTrue(hasMessageEnd, "Should have ReasoningMessageEnd for last event");
    }

    @Test
    void testRunWithNullThinkingBlock() {
        // ThinkingBlock 的 thinking 为 null 时应转换为空字符串并跳过。
        AguiAdapterConfig config = AguiAdapterConfig.builder().enableReasoning(true).build();
        AguiAgentAdapter adapterWithReasoning = new AguiAgentAdapter(mockAgent, config);

        Msg reasoningMsg =
                Msg.builder()
                        .id("msg-r1")
                        .role(MsgRole.ASSISTANT)
                        .content(ThinkingBlock.builder().thinking(null).build())
                        .build();

        Event reasoningEvent = new Event(EventType.REASONING, reasoningMsg, false);
        when(mockAgent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(Flux.just(reasoningEvent));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Hello")))
                        .build();

        List<AguiEvent> events = adapterWithReasoning.run(input).collectList().block();

        assertNotNull(events);

        // null／空思考不应产生推理事件。
        boolean hasReasoningMessageStart =
                events.stream().anyMatch(e -> e instanceof AguiEvent.ReasoningMessageStart);

        assertTrue(
                !hasReasoningMessageStart,
                "Should NOT have ReasoningMessageStart for null thinking");
    }

    private static AguiTool frontendTool(String name) {
        return new AguiTool(
                name,
                "Frontend tool",
                Map.of("type", "object", "properties", Map.of("query", Map.of("type", "string"))));
    }

    private static SchemaOnlyTool schemaOnlyTool(String name) {
        return new SchemaOnlyTool(
                ToolSchema.builder()
                        .name(name)
                        .description("Existing tool")
                        .parameters(
                                Map.of(
                                        "type",
                                        "object",
                                        "properties",
                                        Map.of("query", Map.of("type", "string"))))
                        .build());
    }
}
