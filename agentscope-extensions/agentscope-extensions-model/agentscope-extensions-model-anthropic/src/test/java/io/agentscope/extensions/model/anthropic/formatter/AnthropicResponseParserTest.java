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
package io.agentscope.extensions.model.anthropic.formatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.anthropic.core.JsonValue;
import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.CodeExecutionResultBlock;
import com.anthropic.models.messages.CodeExecutionToolResultBlock;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.DirectCaller;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageDeltaUsage;
import com.anthropic.models.messages.OutputTokensDetails;
import com.anthropic.models.messages.RawContentBlockDeltaEvent;
import com.anthropic.models.messages.RawContentBlockStartEvent;
import com.anthropic.models.messages.RawMessageDeltaEvent;
import com.anthropic.models.messages.RawMessageStartEvent;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.ServerToolUseBlock;
import com.anthropic.models.messages.Usage;
import com.anthropic.models.messages.WebSearchResultBlock;
import com.anthropic.models.messages.WebSearchToolResultBlock;
import com.anthropic.models.messages.WebSearchToolResultError;
import com.anthropic.models.messages.WebSearchToolResultErrorCode;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/** AnthropicResponseParser 单元测试。 */
class AnthropicResponseParserTest extends AnthropicFormatterTestBase {

    private static com.anthropic.models.messages.TextBlock mockTextBlock() {
        return mock(com.anthropic.models.messages.TextBlock.class);
    }

    private static com.anthropic.models.messages.ThinkingBlock mockThinkingBlock() {
        return mock(com.anthropic.models.messages.ThinkingBlock.class);
    }

    private static com.anthropic.models.messages.ToolUseBlock mockToolUseBlock() {
        return mock(com.anthropic.models.messages.ToolUseBlock.class);
    }

    /** 通过反射调用私有 parseStreamEvent，单元测试各事件类型。 */
    private ChatResponse invokeParseStreamEvent(RawMessageStreamEvent event, Instant startTime)
            throws Exception {
        Class<?> stateClass =
                Class.forName(AnthropicResponseParser.class.getName() + "$StreamUsageState");
        var constructor = stateClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object state = constructor.newInstance();

        Method method =
                AnthropicResponseParser.class.getDeclaredMethod(
                        "parseStreamEvent", RawMessageStreamEvent.class, Instant.class, stateClass);
        method.setAccessible(true);
        return (ChatResponse) method.invoke(null, event, startTime, state);
    }

    @Test
    void testParseMessageWithTextBlock() {
        // 创建含文本内容的模拟 Message
        Message message = mock(Message.class);
        Usage usage = mock(Usage.class);
        ContentBlock contentBlock = mock(ContentBlock.class);
        var textBlock = mockTextBlock();

        when(message.id()).thenReturn("msg_123");
        when(message.content()).thenReturn(List.of(contentBlock));
        when(message.usage()).thenReturn(usage);
        when(usage.inputTokens()).thenReturn(100L);
        when(usage.outputTokens()).thenReturn(50L);
        when(usage.outputTokensDetails()).thenReturn(Optional.empty());
        when(usage.cacheReadInputTokens()).thenReturn(Optional.empty());
        when(usage.cacheCreationInputTokens()).thenReturn(Optional.empty());

        when(contentBlock.text()).thenReturn(Optional.of(textBlock));
        when(contentBlock.toolUse()).thenReturn(Optional.empty());
        when(contentBlock.thinking()).thenReturn(Optional.empty());
        when(textBlock.text()).thenReturn("Hello, world!");

        Instant startTime = Instant.now();
        ChatResponse response = AnthropicResponseParser.parseMessage(message, startTime);

        assertNotNull(response);
        assertEquals("msg_123", response.getId());
        assertEquals(1, response.getContent().size());
        TextBlock parsedText = assertInstanceOf(TextBlock.class, response.getContent().get(0));
        assertEquals("Hello, world!", parsedText.getText());

        ChatUsage responseUsage = response.getUsage();
        assertNotNull(responseUsage);
        assertEquals(100, responseUsage.getInputTokens());
        assertEquals(50, responseUsage.getOutputTokens());
    }

    @Test
    void testParseMessageReadsCacheReadInputTokens() {
        Message message = mock(Message.class);
        Usage usage = mock(Usage.class);
        ContentBlock contentBlock = mock(ContentBlock.class);

        when(message.id()).thenReturn("msg_cache");
        when(message.content()).thenReturn(List.of(contentBlock));
        when(message.usage()).thenReturn(usage);
        when(usage.inputTokens()).thenReturn(1000L);
        when(usage.outputTokens()).thenReturn(50L);
        when(usage.outputTokensDetails()).thenReturn(Optional.empty());
        when(usage.cacheReadInputTokens()).thenReturn(Optional.of(500L));
        when(usage.cacheCreationInputTokens()).thenReturn(Optional.of(200L));

        when(contentBlock.text()).thenReturn(Optional.empty());
        when(contentBlock.toolUse()).thenReturn(Optional.empty());
        when(contentBlock.thinking()).thenReturn(Optional.empty());

        Instant startTime = Instant.now();
        ChatResponse response = AnthropicResponseParser.parseMessage(message, startTime);

        assertNotNull(response);
        assertNotNull(response.getUsage());
        assertEquals(500, response.getUsage().getCachedTokens());
        assertEquals(1700, response.getUsage().getInputTokens());
    }

    @Test
    void testParseMessageWithToolUseBlock() {
        // 创建含工具使用内容的模拟 Message
        // 注意：使用 null input 避免模拟 JsonValue 时的 Kotlin 反射问题
        Message message = mock(Message.class);
        Usage usage = mock(Usage.class);
        ContentBlock contentBlock = mock(ContentBlock.class);
        var toolUseBlock = mockToolUseBlock();

        when(message.id()).thenReturn("msg_456");
        when(message.content()).thenReturn(List.of(contentBlock));
        when(message.usage()).thenReturn(usage);
        when(usage.inputTokens()).thenReturn(200L);
        when(usage.outputTokens()).thenReturn(100L);
        when(usage.outputTokensDetails()).thenReturn(Optional.empty());
        when(usage.cacheReadInputTokens()).thenReturn(Optional.empty());
        when(usage.cacheCreationInputTokens()).thenReturn(Optional.empty());

        when(contentBlock.text()).thenReturn(Optional.empty());
        when(contentBlock.toolUse()).thenReturn(Optional.of(toolUseBlock));
        when(contentBlock.thinking()).thenReturn(Optional.empty());

        when(toolUseBlock.id()).thenReturn("tool_call_123");
        when(toolUseBlock.name()).thenReturn("search");
        when(toolUseBlock._input()).thenReturn(null); // 避免 Kotlin 反射问题

        Instant startTime = Instant.now();
        ChatResponse response = AnthropicResponseParser.parseMessage(message, startTime);

        assertNotNull(response);
        assertEquals("msg_456", response.getId());
        assertEquals(1, response.getContent().size());
        ToolUseBlock parsedToolUse =
                assertInstanceOf(ToolUseBlock.class, response.getContent().get(0));
        assertEquals("tool_call_123", parsedToolUse.getId());
        assertEquals("search", parsedToolUse.getName());
        assertNotNull(parsedToolUse.getInput());
        // null 输入应得到空映射
        assertTrue(parsedToolUse.getInput().isEmpty());
    }

    @Test
    void testParseMessageWithThinkingBlock() {
        // 创建含思考内容的模拟 Message
        Message message = mock(Message.class);
        Usage usage = mock(Usage.class);
        ContentBlock contentBlock = mock(ContentBlock.class);
        var thinkingBlock = mockThinkingBlock();

        when(message.id()).thenReturn("msg_789");
        when(message.content()).thenReturn(List.of(contentBlock));
        when(message.usage()).thenReturn(usage);
        when(usage.inputTokens()).thenReturn(150L);
        when(usage.outputTokens()).thenReturn(75L);
        when(usage.outputTokensDetails()).thenReturn(Optional.empty());
        when(usage.cacheReadInputTokens()).thenReturn(Optional.empty());
        when(usage.cacheCreationInputTokens()).thenReturn(Optional.empty());

        when(contentBlock.text()).thenReturn(Optional.empty());
        when(contentBlock.toolUse()).thenReturn(Optional.empty());
        when(contentBlock.thinking()).thenReturn(Optional.of(thinkingBlock));
        when(thinkingBlock.thinking()).thenReturn("Let me think about this...");

        Instant startTime = Instant.now();
        ChatResponse response = AnthropicResponseParser.parseMessage(message, startTime);

        assertNotNull(response);
        assertEquals("msg_789", response.getId());
        assertEquals(1, response.getContent().size());
        ThinkingBlock parsedThinking =
                assertInstanceOf(ThinkingBlock.class, response.getContent().get(0));
        assertEquals("Let me think about this...", parsedThinking.getThinking());
    }

    @Test
    void testParseMessageWithMixedContent() {
        // 创建含多个内容块的模拟 Message
        Message message = mock(Message.class);
        Usage usage = mock(Usage.class);

        ContentBlock textContentBlock = mock(ContentBlock.class);
        var textBlock = mockTextBlock();

        ContentBlock toolContentBlock = mock(ContentBlock.class);
        var toolUseBlock = mockToolUseBlock();

        when(message.id()).thenReturn("msg_mixed");
        when(message.content()).thenReturn(List.of(textContentBlock, toolContentBlock));
        when(message.usage()).thenReturn(usage);
        when(usage.inputTokens()).thenReturn(300L);
        when(usage.outputTokens()).thenReturn(150L);
        when(usage.outputTokensDetails()).thenReturn(Optional.empty());
        when(usage.cacheReadInputTokens()).thenReturn(Optional.empty());
        when(usage.cacheCreationInputTokens()).thenReturn(Optional.empty());

        // 文本块
        when(textContentBlock.text()).thenReturn(Optional.of(textBlock));
        when(textContentBlock.toolUse()).thenReturn(Optional.empty());
        when(textContentBlock.thinking()).thenReturn(Optional.empty());
        when(textBlock.text()).thenReturn("Let me search for that.");

        // 工具使用块，使用 null input 避免 Kotlin 反射问题
        when(toolContentBlock.text()).thenReturn(Optional.empty());
        when(toolContentBlock.toolUse()).thenReturn(Optional.of(toolUseBlock));
        when(toolContentBlock.thinking()).thenReturn(Optional.empty());
        when(toolUseBlock.id()).thenReturn("tool_xyz");
        when(toolUseBlock.name()).thenReturn("web_search");
        when(toolUseBlock._input()).thenReturn(null); // 避免 Kotlin 反射问题

        Instant startTime = Instant.now();
        ChatResponse response = AnthropicResponseParser.parseMessage(message, startTime);

        assertNotNull(response);
        assertEquals("msg_mixed", response.getId());
        assertEquals(2, response.getContent().size());

        assertInstanceOf(TextBlock.class, response.getContent().get(0));
        assertInstanceOf(ToolUseBlock.class, response.getContent().get(1));
    }

    @Test
    void testParseMessageWithEmptyContent() {
        // 创建无内容的模拟 Message
        Message message = mock(Message.class);
        Usage usage = mock(Usage.class);

        when(message.id()).thenReturn("msg_empty");
        when(message.content()).thenReturn(List.of());
        when(message.usage()).thenReturn(usage);
        when(usage.inputTokens()).thenReturn(50L);
        when(usage.outputTokens()).thenReturn(0L);
        when(usage.outputTokensDetails()).thenReturn(Optional.empty());
        when(usage.cacheReadInputTokens()).thenReturn(Optional.empty());
        when(usage.cacheCreationInputTokens()).thenReturn(Optional.empty());

        Instant startTime = Instant.now();
        ChatResponse response = AnthropicResponseParser.parseMessage(message, startTime);

        assertNotNull(response);
        assertEquals("msg_empty", response.getId());
        assertTrue(response.getContent().isEmpty());
    }

    @Test
    void testParseMessageWithNullToolInput() {
        // 创建工具输入为 null 的模拟 Message
        Message message = mock(Message.class);
        Usage usage = mock(Usage.class);
        ContentBlock contentBlock = mock(ContentBlock.class);
        var toolUseBlock = mockToolUseBlock();

        when(message.id()).thenReturn("msg_null_input");
        when(message.content()).thenReturn(List.of(contentBlock));
        when(message.usage()).thenReturn(usage);
        when(usage.inputTokens()).thenReturn(100L);
        when(usage.outputTokens()).thenReturn(50L);
        when(usage.outputTokensDetails()).thenReturn(Optional.empty());
        when(usage.cacheReadInputTokens()).thenReturn(Optional.empty());
        when(usage.cacheCreationInputTokens()).thenReturn(Optional.empty());

        when(contentBlock.text()).thenReturn(Optional.empty());
        when(contentBlock.toolUse()).thenReturn(Optional.of(toolUseBlock));
        when(contentBlock.thinking()).thenReturn(Optional.empty());

        when(toolUseBlock.id()).thenReturn("tool_null");
        when(toolUseBlock.name()).thenReturn("test_tool");
        when(toolUseBlock._input()).thenReturn(null);

        Instant startTime = Instant.now();
        ChatResponse response = AnthropicResponseParser.parseMessage(message, startTime);

        assertNotNull(response);
        assertEquals(1, response.getContent().size());

        ToolUseBlock parsedToolUse =
                assertInstanceOf(ToolUseBlock.class, response.getContent().get(0));
        assertEquals("tool_null", parsedToolUse.getId());
        assertEquals("test_tool", parsedToolUse.getName());
        // null 输入应得到空映射
        assertNotNull(parsedToolUse.getInput());
        assertTrue(parsedToolUse.getInput().isEmpty());
    }

    @Test
    void testParseStreamEventsMessageStart() {
        // 创建模拟 MessageStart 事件
        RawMessageStreamEvent event = mock(RawMessageStreamEvent.class);
        RawMessageStartEvent messageStartEvent = mock(RawMessageStartEvent.class);
        Message message = mock(Message.class);

        when(event.isMessageStart()).thenReturn(true);
        when(event.asMessageStart()).thenReturn(messageStartEvent);
        when(messageStartEvent.message()).thenReturn(message);
        when(message.id()).thenReturn("msg_stream_123");

        Instant startTime = Instant.now();
        Flux<ChatResponse> responseFlux =
                AnthropicResponseParser.parseStreamEvents(Flux.just(event), startTime);

        // MessageStart 内容为空，应被过滤
        StepVerifier.create(responseFlux).verifyComplete();
    }

    @Test
    void testParseStreamEventMessageStart() throws Exception {
        // 测试 MessageStart：应设置消息 ID，但内容为空
        RawMessageStreamEvent event = mock(RawMessageStreamEvent.class);
        RawMessageStartEvent messageStart = mock(RawMessageStartEvent.class);
        Message message = mock(Message.class);
        Usage usage = mock(Usage.class);

        when(event.isMessageStart()).thenReturn(true);
        when(event.asMessageStart()).thenReturn(messageStart);
        when(messageStart.message()).thenReturn(message);
        when(message.id()).thenReturn("msg_stream_123");
        when(message.usage()).thenReturn(usage);
        when(usage.cacheReadInputTokens()).thenReturn(Optional.empty());
        when(usage.cacheCreationInputTokens()).thenReturn(Optional.empty());

        when(event.isContentBlockDelta()).thenReturn(false);
        when(event.isContentBlockStart()).thenReturn(false);
        when(event.isMessageDelta()).thenReturn(false);

        Instant startTime = Instant.now();
        ChatResponse response = invokeParseStreamEvent(event, startTime);

        assertNotNull(response);
        assertEquals("msg_stream_123", response.getId());
        assertTrue(response.getContent().isEmpty()); // MessageStart 没有内容
    }

    @Test
    void testParseStreamEventThinkingDelta() throws Exception {
        RawContentBlockDeltaEvent deltaEvent =
                RawContentBlockDeltaEvent.builder()
                        .index(0)
                        .thinkingDelta("Let me reason through this.")
                        .build();
        RawMessageStreamEvent event = RawMessageStreamEvent.ofContentBlockDelta(deltaEvent);

        Instant startTime = Instant.now();
        ChatResponse response = invokeParseStreamEvent(event, startTime);

        assertNotNull(response);
        assertEquals(1, response.getContent().size());
        ThinkingBlock parsedThinking =
                assertInstanceOf(ThinkingBlock.class, response.getContent().get(0));
        assertEquals("Let me reason through this.", parsedThinking.getThinking());
        assertNull(response.getUsage());
    }

    @Test
    void testParseStreamEventUnknownType() throws Exception {
        // 测试未知事件类型，应返回空响应
        RawMessageStreamEvent event = mock(RawMessageStreamEvent.class);

        when(event.isMessageStart()).thenReturn(false);
        when(event.isContentBlockDelta()).thenReturn(false);
        when(event.isContentBlockStart()).thenReturn(false);
        when(event.isMessageDelta()).thenReturn(false);

        Instant startTime = Instant.now();
        ChatResponse response = invokeParseStreamEvent(event, startTime);

        assertNotNull(response);
        assertNotNull(response.getId()); // ID 为 null 时构建器自动生成 UUID
        assertFalse(response.getId().isEmpty());
        assertTrue(response.getContent().isEmpty());
        assertNull(response.getUsage());
    }

    @Test
    void testParseStreamEventsFiltersEmptyContent() {
        // 测试 parseStreamEvents 过滤空内容响应
        RawMessageStreamEvent event = mock(RawMessageStreamEvent.class);

        when(event.isMessageStart()).thenReturn(false);
        when(event.isContentBlockDelta()).thenReturn(false);
        when(event.isContentBlockStart()).thenReturn(false);
        when(event.isMessageDelta()).thenReturn(false);

        Instant startTime = Instant.now();
        Flux<ChatResponse> responseFlux =
                AnthropicResponseParser.parseStreamEvents(Flux.just(event), startTime);

        // 应过滤空内容响应
        StepVerifier.create(responseFlux).verifyComplete();
    }

    @Test
    void testParseStreamEventsHandlesExceptions() {
        // 测试解析异常会被捕获并记录
        RawMessageStreamEvent event = mock(RawMessageStreamEvent.class);

        // 使事件抛出异常
        when(event.isMessageStart()).thenThrow(new RuntimeException("Test exception"));

        Instant startTime = Instant.now();
        Flux<ChatResponse> responseFlux =
                AnthropicResponseParser.parseStreamEvents(Flux.just(event), startTime);

        // 异常应被捕获并产生空 Flux
        StepVerifier.create(responseFlux).verifyComplete();
    }

    @Test
    void testParseStreamEventsErrorHandling() {
        // 创建发出错误的 Flux
        Flux<RawMessageStreamEvent> errorFlux = Flux.error(new RuntimeException("Stream error"));

        Instant startTime = Instant.now();

        // parseStreamEvents 应传播错误
        StepVerifier.create(AnthropicResponseParser.parseStreamEvents(errorFlux, startTime))
                .expectError(RuntimeException.class)
                .verify();
    }

    // ==================== 服务端工具（网页搜索） ====================

    private static ServerToolUseBlock buildServerToolUseBlock(String id) {
        return ServerToolUseBlock.builder()
                .id(id)
                .name(ServerToolUseBlock.Name.WEB_SEARCH)
                .input(JsonValue.from(Map.of("query", "AgentScope")))
                .caller(DirectCaller.builder().build())
                .build();
    }

    private static WebSearchToolResultBlock buildWebSearchResultBlock(String toolUseId) {
        return WebSearchToolResultBlock.builder()
                .toolUseId(toolUseId)
                .caller(DirectCaller.builder().build())
                .contentOfResultBlocks(
                        List.of(
                                WebSearchResultBlock.builder()
                                        .url("https://example.com")
                                        .title("Example")
                                        .encryptedContent("enc_abc123")
                                        .pageAge("2 days")
                                        .build()))
                .build();
    }

    @Test
    void testParseMessageWithServerToolUseAndWebSearchResult() {
        Message message = mock(Message.class);
        Usage usage = mock(Usage.class);

        ContentBlock serverToolUseUnion =
                ContentBlock.ofServerToolUse(buildServerToolUseBlock("srvtoolu_01"));
        ContentBlock webSearchResultUnion =
                ContentBlock.ofWebSearchToolResult(buildWebSearchResultBlock("srvtoolu_01"));

        when(message.id()).thenReturn("msg_server");
        when(message.content()).thenReturn(List.of(serverToolUseUnion, webSearchResultUnion));
        when(message.usage()).thenReturn(usage);
        when(usage.inputTokens()).thenReturn(100L);
        when(usage.outputTokens()).thenReturn(50L);

        ChatResponse response = AnthropicResponseParser.parseMessage(message, Instant.now());

        assertNotNull(response);
        assertEquals(2, response.getContent().size());

        ToolUseBlock toolUse = assertInstanceOf(ToolUseBlock.class, response.getContent().get(0));
        assertEquals("srvtoolu_01", toolUse.getId());
        assertEquals("web_search", toolUse.getName());
        assertTrue(toolUse.isServerTool());
        assertEquals(ToolCallState.FINISHED, toolUse.getState());
        assertEquals("AgentScope", toolUse.getInput().get("query"));

        io.agentscope.core.message.ToolResultBlock toolResult =
                assertInstanceOf(
                        io.agentscope.core.message.ToolResultBlock.class,
                        response.getContent().get(1));
        assertEquals("srvtoolu_01", toolResult.getId());
        assertTrue(toolResult.isServerTool());

        // 原始块（含 encrypted_content）以 JSON 保留在元数据中
        String rawJson =
                assertInstanceOf(
                        String.class,
                        toolResult.getMetadata().get(AnthropicServerToolHelper.RESULT_METADATA));
        assertTrue(rawJson.contains("\"web_search_tool_result\""));
        assertTrue(rawJson.contains("\"srvtoolu_01\""));
        assertTrue(rawJson.contains("\"enc_abc123\""));
        assertTrue(rawJson.contains("\"2 days\""));

        // 可供人阅读的输出
        TextBlock outputText = assertInstanceOf(TextBlock.class, toolResult.getOutput().get(0));
        assertEquals("Example (https://example.com)", outputText.getText());
    }

    @Test
    void testParseMessageWithWebSearchError() {
        Message message = mock(Message.class);
        Usage usage = mock(Usage.class);

        WebSearchToolResultBlock errorBlock =
                WebSearchToolResultBlock.builder()
                        .toolUseId("srvtoolu_err")
                        .caller(DirectCaller.builder().build())
                        .content(
                                WebSearchToolResultError.builder()
                                        .errorCode(WebSearchToolResultErrorCode.MAX_USES_EXCEEDED)
                                        .build())
                        .build();

        when(message.id()).thenReturn("msg_err");
        when(message.content()).thenReturn(List.of(ContentBlock.ofWebSearchToolResult(errorBlock)));
        when(message.usage()).thenReturn(usage);
        when(usage.inputTokens()).thenReturn(10L);
        when(usage.outputTokens()).thenReturn(5L);

        ChatResponse response = AnthropicResponseParser.parseMessage(message, Instant.now());

        assertEquals(1, response.getContent().size());
        io.agentscope.core.message.ToolResultBlock toolResult =
                assertInstanceOf(
                        io.agentscope.core.message.ToolResultBlock.class,
                        response.getContent().get(0));
        assertTrue(toolResult.isServerTool());
        String rawJson =
                assertInstanceOf(
                        String.class,
                        toolResult.getMetadata().get(AnthropicServerToolHelper.RESULT_METADATA));
        assertTrue(rawJson.contains("\"max_uses_exceeded\""));
        TextBlock errorText = assertInstanceOf(TextBlock.class, toolResult.getOutput().get(0));
        assertTrue(errorText.getText().contains("max_uses_exceeded"));
    }

    @Test
    void testParseMessageWithCodeExecutionResult() {
        Message message = mock(Message.class);
        Usage usage = mock(Usage.class);

        CodeExecutionToolResultBlock codeResult =
                CodeExecutionToolResultBlock.builder()
                        .toolUseId("srvtoolu_code")
                        .content(
                                CodeExecutionResultBlock.builder()
                                        .stdout("42\n")
                                        .stderr("")
                                        .returnCode(0)
                                        .content(List.of())
                                        .build())
                        .build();

        when(message.id()).thenReturn("msg_code");
        when(message.content())
                .thenReturn(List.of(ContentBlock.ofCodeExecutionToolResult(codeResult)));
        when(message.usage()).thenReturn(usage);
        when(usage.inputTokens()).thenReturn(10L);
        when(usage.outputTokens()).thenReturn(5L);

        ChatResponse response = AnthropicResponseParser.parseMessage(message, Instant.now());

        assertEquals(1, response.getContent().size());
        io.agentscope.core.message.ToolResultBlock toolResult =
                assertInstanceOf(
                        io.agentscope.core.message.ToolResultBlock.class,
                        response.getContent().get(0));
        assertEquals("srvtoolu_code", toolResult.getId());
        assertEquals("code_execution", toolResult.getName());
        assertTrue(toolResult.isServerTool());
        String rawJson =
                assertInstanceOf(
                        String.class,
                        toolResult.getMetadata().get(AnthropicServerToolHelper.RESULT_METADATA));
        assertTrue(rawJson.contains("\"code_execution_tool_result\""));
        assertTrue(rawJson.contains("\"42\\n\""));
    }

    @Test
    void testParseStreamEventServerToolUseStart() throws Exception {
        RawContentBlockStartEvent startEvent =
                RawContentBlockStartEvent.builder()
                        .index(0)
                        .contentBlock(buildServerToolUseBlock("srvtoolu_stream"))
                        .build();
        RawMessageStreamEvent event = RawMessageStreamEvent.ofContentBlockStart(startEvent);

        ChatResponse response = invokeParseStreamEvent(event, Instant.now());

        assertNotNull(response);
        assertEquals(1, response.getContent().size());
        ToolUseBlock toolUse = assertInstanceOf(ToolUseBlock.class, response.getContent().get(0));
        assertEquals("srvtoolu_stream", toolUse.getId());
        assertEquals("web_search", toolUse.getName());
        assertTrue(toolUse.isServerTool());
        assertEquals(ToolCallState.FINISHED, toolUse.getState());
    }

    @Test
    void testParseStreamEventWebSearchToolResultStart() throws Exception {
        RawContentBlockStartEvent startEvent =
                RawContentBlockStartEvent.builder()
                        .index(1)
                        .contentBlock(buildWebSearchResultBlock("srvtoolu_stream"))
                        .build();
        RawMessageStreamEvent event = RawMessageStreamEvent.ofContentBlockStart(startEvent);

        ChatResponse response = invokeParseStreamEvent(event, Instant.now());

        assertNotNull(response);
        assertEquals(1, response.getContent().size());
        io.agentscope.core.message.ToolResultBlock toolResult =
                assertInstanceOf(
                        io.agentscope.core.message.ToolResultBlock.class,
                        response.getContent().get(0));
        assertEquals("srvtoolu_stream", toolResult.getId());
        assertTrue(toolResult.isServerTool());
    }

    @Test
    void testParseMessageWithCachedTokens() {
        // Anthropic API 的 input_tokens 不含缓存 token，解析器会将它们加回
        Message message = mock(Message.class);
        Usage usage = mock(Usage.class);
        OutputTokensDetails outputTokensDetails = mock(OutputTokensDetails.class);

        when(message.id()).thenReturn("msg_cached");
        when(message.content()).thenReturn(List.of());
        when(message.usage()).thenReturn(usage);
        when(usage.inputTokens()).thenReturn(100L);
        when(usage.outputTokens()).thenReturn(50L);
        when(usage.outputTokensDetails()).thenReturn(Optional.of(outputTokensDetails));
        when(outputTokensDetails.thinkingTokens()).thenReturn(12L);
        when(usage.cacheReadInputTokens()).thenReturn(Optional.of(80L));
        when(usage.cacheCreationInputTokens()).thenReturn(Optional.of(20L));

        ChatResponse response = AnthropicResponseParser.parseMessage(message, Instant.now());

        ChatUsage responseUsage = response.getUsage();
        assertNotNull(responseUsage);
        assertEquals(200, responseUsage.getInputTokens()); // 100 + 80 + 20
        assertEquals(80, responseUsage.getCachedTokens());
        assertEquals(20, responseUsage.getCacheCreationTokens());
        assertEquals(50, responseUsage.getOutputTokens());
        assertEquals(12, responseUsage.getReasoningTokens());
    }

    @Test
    void testParseStreamEventsCombineStartAndDeltaUsage() {
        // message_start 携带输入及缓存 token 用量，message_delta 携带
        // 最终输出 token 数，解析器必须合并两者。
        RawMessageStreamEvent startEvent = mock(RawMessageStreamEvent.class);
        RawMessageStartEvent messageStart = mock(RawMessageStartEvent.class);
        Message message = mock(Message.class);
        Usage startUsage = mock(Usage.class);

        when(startEvent.isMessageStart()).thenReturn(true);
        when(startEvent.asMessageStart()).thenReturn(messageStart);
        when(messageStart.message()).thenReturn(message);
        when(message.id()).thenReturn("msg_stream_cached");
        when(message.usage()).thenReturn(startUsage);
        when(startUsage.inputTokens()).thenReturn(100L);
        when(startUsage.cacheReadInputTokens()).thenReturn(Optional.of(50L));
        when(startUsage.cacheCreationInputTokens()).thenReturn(Optional.of(30L));

        RawMessageStreamEvent deltaEvent = mock(RawMessageStreamEvent.class);
        RawMessageDeltaEvent messageDelta = mock(RawMessageDeltaEvent.class);
        MessageDeltaUsage deltaUsage = mock(MessageDeltaUsage.class);

        when(deltaEvent.isMessageDelta()).thenReturn(true);
        when(deltaEvent.asMessageDelta()).thenReturn(messageDelta);
        when(messageDelta.usage()).thenReturn(deltaUsage);
        when(deltaUsage.outputTokens()).thenReturn(42L);
        when(deltaUsage.outputTokensDetails()).thenReturn(Optional.empty());
        when(deltaUsage.inputTokens()).thenReturn(Optional.empty());
        when(deltaUsage.cacheReadInputTokens()).thenReturn(Optional.empty());
        when(deltaUsage.cacheCreationInputTokens()).thenReturn(Optional.empty());

        Instant startTime = Instant.now();
        Flux<ChatResponse> responseFlux =
                AnthropicResponseParser.parseStreamEvents(
                        Flux.just(startEvent, deltaEvent), startTime);

        StepVerifier.create(responseFlux)
                .assertNext(
                        response -> {
                            // message_delta 最终用量需合并之前记录的提示词用量，
                            // 该用量来自 message_start
                            ChatUsage usage = response.getUsage();
                            assertNotNull(usage);
                            assertEquals(180, usage.getInputTokens()); // 100 + 50 + 30
                            assertEquals(50, usage.getCachedTokens());
                            assertEquals(30, usage.getCacheCreationTokens());
                            assertEquals(42, usage.getOutputTokens());
                        })
                .verifyComplete();
    }

    @Test
    void testParseStreamEventsDeltaCarryingOwnPromptUsage() {
        // 某些响应直接在 message_delta 中包含提示词用量，应优先于
        // 此前从 message_start 记录的值
        RawMessageStreamEvent deltaEvent = mock(RawMessageStreamEvent.class);
        RawMessageDeltaEvent messageDelta = mock(RawMessageDeltaEvent.class);
        MessageDeltaUsage deltaUsage = mock(MessageDeltaUsage.class);

        when(deltaEvent.isMessageDelta()).thenReturn(true);
        when(deltaEvent.asMessageDelta()).thenReturn(messageDelta);
        when(messageDelta.usage()).thenReturn(deltaUsage);
        when(deltaUsage.outputTokens()).thenReturn(42L);
        when(deltaUsage.outputTokensDetails()).thenReturn(Optional.empty());
        when(deltaUsage.inputTokens()).thenReturn(Optional.of(100L));
        when(deltaUsage.cacheReadInputTokens()).thenReturn(Optional.of(50L));
        when(deltaUsage.cacheCreationInputTokens()).thenReturn(Optional.of(30L));

        Instant startTime = Instant.now();
        Flux<ChatResponse> responseFlux =
                AnthropicResponseParser.parseStreamEvents(Flux.just(deltaEvent), startTime);

        StepVerifier.create(responseFlux)
                .assertNext(
                        response -> {
                            ChatUsage usage = response.getUsage();
                            assertNotNull(usage);
                            assertEquals(180, usage.getInputTokens()); // 100 + 50 + 30
                            assertEquals(50, usage.getCachedTokens());
                            assertEquals(30, usage.getCacheCreationTokens());
                            assertEquals(42, usage.getOutputTokens());
                        })
                .verifyComplete();
    }

    @Test
    void testParseMessageWithoutIdFallsBackToGeneratedId() throws Exception {
        // 代理可能移除顶层 ID，导致 SDK 类型化访问器抛异常（问题
        // #592).
        String json =
                "{\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-sonnet-4-5\","
                        + "\"content\":[{\"type\":\"text\",\"text\":\"2 + 3 = 5\"}],"
                        + "\"stop_reason\":\"end_turn\","
                        + "\"usage\":{\"input_tokens\":10,\"output_tokens\":5}}";
        Message message = ObjectMappers.jsonMapper().readValue(json, Message.class);

        ChatResponse response = AnthropicResponseParser.parseMessage(message, Instant.now());

        assertNotNull(response);
        assertNotNull(response.getId());
        assertFalse(response.getId().isBlank());
        assertEquals(1, response.getContent().size());
        TextBlock parsedText = assertInstanceOf(TextBlock.class, response.getContent().get(0));
        assertEquals("2 + 3 = 5", parsedText.getText());
    }

    @Test
    void testParseMessageReusesRawId() throws Exception {
        // 覆盖原始 _id() 分支：手写模拟只为类型化访问器提供桩。
        String json =
                "{\"id\":\"msg_01ABC\",\"type\":\"message\",\"role\":\"assistant\","
                        + "\"model\":\"claude-sonnet-4-5\","
                        + "\"content\":[{\"type\":\"text\",\"text\":\"hi\"}],"
                        + "\"usage\":{\"input_tokens\":1,\"output_tokens\":2}}";
        Message message = ObjectMappers.jsonMapper().readValue(json, Message.class);

        ChatResponse response = AnthropicResponseParser.parseMessage(message, Instant.now());

        assertEquals("msg_01ABC", response.getId());
    }

    @Test
    void testParseStreamEventMessageStartWithoutIdDoesNotThrow() throws Exception {
        // 修复前这里抛异常，使整个 message_start 分支中断，并丢失
        // message_delta 之后要从共享用量状态读取的提示词 token 计数。
        String json =
                "{\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-sonnet-4-5\","
                        + "\"content\":[],\"usage\":{\"input_tokens\":10,\"output_tokens\":1}}";
        Message message = ObjectMappers.jsonMapper().readValue(json, Message.class);

        RawMessageStartEvent messageStart = mock(RawMessageStartEvent.class);
        when(messageStart.message()).thenReturn(message);

        RawMessageStreamEvent event = mock(RawMessageStreamEvent.class);
        when(event.isMessageStart()).thenReturn(true);
        when(event.asMessageStart()).thenReturn(messageStart);
        when(event.isContentBlockDelta()).thenReturn(false);
        when(event.isContentBlockStart()).thenReturn(false);
        when(event.isMessageDelta()).thenReturn(false);

        ChatResponse response = invokeParseStreamEvent(event, Instant.now());

        assertNotNull(response);
        assertNotNull(response.getId()); // 由于代理已移除 ID，因此自动生成
        assertTrue(response.getContent().isEmpty());
    }
}
