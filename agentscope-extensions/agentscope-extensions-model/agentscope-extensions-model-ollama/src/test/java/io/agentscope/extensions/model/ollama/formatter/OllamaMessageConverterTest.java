/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.extensions.model.ollama.formatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.util.JacksonJsonCodec;
import io.agentscope.extensions.model.ollama.dto.OllamaMessage;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * OllamaMessageConverter 单元测试。
 */
@DisplayName("OllamaMessageConverter Unit Tests")
class OllamaMessageConverterTest {

    private OllamaMessageConverter converter;

    @BeforeEach
    void setUp() {
        converter = new OllamaMessageConverter();
    }

    @Test
    @DisplayName("Should create converter successfully")
    void testConstructor() {
        assertNotNull(converter);
    }

    @Test
    @DisplayName("Should convert user message with text content")
    void testConvertUserMessageWithText() {
        // 准备
        Msg msg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .name("Alice")
                        .content(TextBlock.builder().text("Hello, how are you?").build())
                        .build();

        // 执行
        OllamaMessage ollamaMsg = converter.convertMessage(msg);

        // 断言
        assertNotNull(ollamaMsg);
        assertEquals("user", ollamaMsg.getRole());
        assertEquals("Hello, how are you?", ollamaMsg.getContent());
    }

    @Test
    @DisplayName("Should convert assistant message with text content")
    void testConvertAssistantMessageWithText() {
        // 准备
        Msg msg =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("I'm doing well, thank you!").build())
                        .build();

        // 执行
        OllamaMessage ollamaMsg = converter.convertMessage(msg);

        // 断言
        assertNotNull(ollamaMsg);
        assertEquals("assistant", ollamaMsg.getRole());
        assertEquals("I'm doing well, thank you!", ollamaMsg.getContent());
    }

    @Test
    @DisplayName("Should convert message with multiple text blocks")
    void testConvertMessageWithMultipleTextBlocks() {
        // 准备
        Msg msg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                Arrays.asList(
                                        TextBlock.builder().text("First part").build(),
                                        TextBlock.builder().text("Second part").build()))
                        .build();

        // 执行
        OllamaMessage ollamaMsg = converter.convertMessage(msg);

        // 断言
        assertNotNull(ollamaMsg);
        assertEquals("user", ollamaMsg.getRole());
        assertEquals("First partSecond part", ollamaMsg.getContent());
    }

    @Test
    @DisplayName("Should convert tool result message")
    void testConvertToolResultMessage() {
        // 准备
        ToolResultBlock toolResult =
                new ToolResultBlock(
                        "call123",
                        "test_tool",
                        List.of(TextBlock.builder().text("Tool result output").build()),
                        null);
        Msg msg = Msg.builder().role(MsgRole.TOOL).content(toolResult).build();

        // 执行
        OllamaMessage ollamaMsg = converter.convertMessage(msg);

        // 断言
        assertNotNull(ollamaMsg);
        assertEquals("tool", ollamaMsg.getRole());
        assertEquals("Tool result output", ollamaMsg.getContent());
        assertEquals("call123", ollamaMsg.getToolCallId());
        assertEquals("test_tool", ollamaMsg.getName());
    }

    @Test
    @DisplayName("Should serialize tool result name as Ollama's tool_name field")
    void testSerializeToolResultWithOllamaToolName() {
        // 准备
        ToolResultBlock toolResult =
                new ToolResultBlock(
                        "call123",
                        "test_tool",
                        List.of(TextBlock.builder().text("Tool result output").build()),
                        null);
        Msg msg = Msg.builder().role(MsgRole.TOOL).content(toolResult).build();

        // 执行
        OllamaMessage ollamaMsg = converter.convertMessage(msg);
        String json = new JacksonJsonCodec().toJson(ollamaMsg);

        // 断言
        assertEquals("test_tool", ollamaMsg.getName());
        assertTrue(json.contains("\"tool_name\":\"test_tool\""));
        assertFalse(json.contains("\"name\":\"test_tool\""));
    }

    @Test
    @DisplayName("Should convert assistant message with tool use")
    void testConvertAssistantMessageWithToolUse() {
        // 准备
        ToolUseBlock toolUse =
                new ToolUseBlock(
                        "call123", "calculator", Map.of("operation", "add", "a", 5, "b", 3), null);
        Msg msg =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(
                                Arrays.asList(
                                        TextBlock.builder()
                                                .text("Let me calculate that for you.")
                                                .build(),
                                        toolUse))
                        .build();

        // 执行
        OllamaMessage ollamaMsg = converter.convertMessage(msg);

        // 断言
        assertNotNull(ollamaMsg);
        assertEquals("assistant", ollamaMsg.getRole());
        assertEquals("Let me calculate that for you.", ollamaMsg.getContent());
        assertNotNull(ollamaMsg.getToolCalls());
        assertEquals(1, ollamaMsg.getToolCalls().size());
        assertEquals("calculator", ollamaMsg.getToolCalls().get(0).getFunction().getName());
        assertEquals(5, ollamaMsg.getToolCalls().get(0).getFunction().getArguments().get("a"));
    }

    @Test
    @DisplayName("Should handle message with only tool use and no text")
    void testConvertMessageWithOnlyToolUse() {
        // 准备
        ToolUseBlock toolUse =
                new ToolUseBlock("call456", "search", Map.of("query", "weather"), null);
        Msg msg = Msg.builder().role(MsgRole.ASSISTANT).content(toolUse).build();

        // 执行
        OllamaMessage ollamaMsg = converter.convertMessage(msg);

        // 断言
        assertNotNull(ollamaMsg);
        assertEquals("assistant", ollamaMsg.getRole());
        assertNull(ollamaMsg.getContent()); // 无文本内容
        assertNotNull(ollamaMsg.getToolCalls());
        assertEquals(1, ollamaMsg.getToolCalls().size());
        assertEquals("search", ollamaMsg.getToolCalls().get(0).getFunction().getName());
    }

    @Test
    @DisplayName("Should preserve assistant ThinkingBlock with tool calls")
    void testConvertAssistantMessageWithThinkingAndToolUse() {
        Msg msg =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(
                                Arrays.asList(
                                        ThinkingBlock.builder()
                                                .thinking("I need current weather data.")
                                                .build(),
                                        new ToolUseBlock(
                                                "call-789",
                                                "get_weather",
                                                Map.of("city", "Tokyo"),
                                                null)))
                        .build();

        OllamaMessage ollamaMsg = converter.convertMessage(msg);

        assertNotNull(ollamaMsg);
        assertEquals("assistant", ollamaMsg.getRole());
        assertEquals("I need current weather data.", ollamaMsg.getThinking());
        assertNotNull(ollamaMsg.getToolCalls());
        assertEquals("get_weather", ollamaMsg.getToolCalls().get(0).getFunction().getName());
    }

    @Test
    @DisplayName("Should handle message with null content")
    void testConvertMessageWithNullContent() {
        // 准备
        Msg msg = Msg.builder().role(MsgRole.USER).build();

        // 执行
        OllamaMessage ollamaMsg = converter.convertMessage(msg);

        // 断言
        assertNotNull(ollamaMsg);
        assertEquals("user", ollamaMsg.getRole());
        assertNull(ollamaMsg.getContent());
    }

    @Test
    @DisplayName("Should handle message with empty content list")
    void testConvertMessageWithEmptyContentList() {
        // 准备
        Msg msg = Msg.builder().role(MsgRole.USER).content(Arrays.asList()).build();

        // 执行
        OllamaMessage ollamaMsg = converter.convertMessage(msg);

        // 断言
        assertNotNull(ollamaMsg);
        assertEquals("user", ollamaMsg.getRole());
        assertNull(ollamaMsg.getContent());
    }

    @Test
    @org.junit.jupiter.api.Disabled(
            "Stage 1 Msg.validateRoleContent rejects USER + ToolResultBlock at construction;"
                    + " this fallback extraction path is unreachable. See"
                    + " io.agentscope.core.message.Msg#validateRoleContent.")
    @DisplayName("Should extract text content from tool result when no direct content")
    void testExtractTextContentFromToolResult() {
        // 验证内部 extractTextContent 方法行为
        // 准备
        ToolResultBlock toolResult =
                new ToolResultBlock(
                        "call123",
                        "test_tool",
                        List.of(TextBlock.builder().text("Tool output").build()),
                        null);
        TextBlock textBlock = TextBlock.builder().text("Additional text").build();
        Msg msg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(Arrays.asList(textBlock, toolResult))
                        .build();

        // 执行
        OllamaMessage ollamaMsg = converter.convertMessage(msg);

        // 断言
        assertNotNull(ollamaMsg);
        assertEquals("user", ollamaMsg.getRole());
        assertEquals("Additional text", ollamaMsg.getContent());
    }
}
