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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.extensions.model.ollama.dto.OllamaMessage;
import io.agentscope.extensions.model.ollama.dto.OllamaRequest;
import io.agentscope.extensions.model.ollama.dto.OllamaResponse;
import io.agentscope.extensions.model.ollama.options.OllamaOptions;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * OllamaChatFormatter 单元测试。
 */
@DisplayName("OllamaChatFormatter Unit Tests")
class OllamaChatFormatterTest {

    private OllamaChatFormatter formatter;
    private String imagePath;

    @BeforeEach
    void setUp() {
        formatter = new OllamaChatFormatter();
        imagePath = "src/test/resources/dog.png";
    }

    @Test
    @DisplayName("Should create formatter with default components")
    void testConstructor() {
        assertNotNull(formatter);
    }

    @Test
    @DisplayName("Should format messages correctly")
    void testFormatMessages() {
        // 准备：系统消息
        List<Msg> msgsSystem =
                Arrays.asList(
                        Msg.builder()
                                .role(MsgRole.SYSTEM)
                                .name("system")
                                .content(
                                        TextBlock.builder()
                                                .text("You're a helpful assistant.")
                                                .build())
                                .build());

        // 准备：对话消息
        List<Msg> msgsConversation =
                Arrays.asList(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .name("user")
                                .content(
                                        Arrays.asList(
                                                TextBlock.builder()
                                                        .text("What is the capital of France?")
                                                        .build(),
                                                ImageBlock.builder()
                                                        .source(
                                                                URLSource.builder()
                                                                        .url(imagePath)
                                                                        .build())
                                                        .build()))
                                .build(),
                        Msg.builder()
                                .role(MsgRole.ASSISTANT)
                                .name("assistant")
                                .content(
                                        TextBlock.builder()
                                                .text("The capital of France is Paris.")
                                                .build())
                                .build(),
                        Msg.builder()
                                .role(MsgRole.USER)
                                .name("user")
                                .content(
                                        TextBlock.builder()
                                                .text("What is the capital of Japan?")
                                                .build())
                                .build());

        // 准备：工具消息
        List<Msg> msgsTools =
                Arrays.asList(
                        Msg.builder()
                                .role(MsgRole.ASSISTANT)
                                .name("assistant")
                                .content(
                                        Arrays.asList(
                                                ToolUseBlock.builder()
                                                        .id("1")
                                                        .name("get_capital")
                                                        .input(
                                                                Collections.singletonMap(
                                                                        "country", "Japan"))
                                                        .build()))
                                .build(),
                        Msg.builder()
                                .role(MsgRole.TOOL)
                                .name("system")
                                .content(
                                        Arrays.asList(
                                                ToolResultBlock.of(
                                                        "1",
                                                        "get_capital",
                                                        Arrays.asList(
                                                                TextBlock.builder()
                                                                        .text(
                                                                                "The capital of"
                                                                                    + " Japan is"
                                                                                    + " Tokyo.")
                                                                        .build(),
                                                                ImageBlock.builder()
                                                                        .source(
                                                                                URLSource.builder()
                                                                                        .url(
                                                                                                imagePath)
                                                                                        .build())
                                                                        .build()))))
                                .build(),
                        Msg.builder()
                                .role(MsgRole.ASSISTANT)
                                .name("assistant")
                                .content(
                                        TextBlock.builder()
                                                .text("The capital of Japan is Tokyo.")
                                                .build())
                                .build());

        // 执行
        List<OllamaMessage> formatted =
                formatter.format(concatLists(msgsSystem, msgsConversation, msgsTools));

        // 断言：检查预期结果与基准结果匹配
        assertEquals(7, formatted.size());

        assertEquals("system", formatted.get(0).getRole());
        assertEquals("You're a helpful assistant.", formatted.get(0).getContent());

        assertEquals("user", formatted.get(1).getRole());
        assertEquals("What is the capital of France?", formatted.get(1).getContent());
        // 实际图像的 Base64 内容不同，不能检查精确内容，
        // 但可检查 images 列表非 null 且含一项
        if (formatted.get(1).getImages() != null) {
            assertEquals(1, formatted.get(1).getImages().size());
        }

        assertEquals("assistant", formatted.get(2).getRole());
        assertEquals("The capital of France is Paris.", formatted.get(2).getContent());

        assertEquals("user", formatted.get(3).getRole());
        assertEquals("What is the capital of Japan?", formatted.get(3).getContent());

        assertEquals("assistant", formatted.get(4).getRole());
        assertTrue(
                formatted.get(4).getToolCalls() != null
                        && formatted.get(4).getToolCalls().size() > 0,
                "Tool calls should exist");
        if (formatted.get(4).getToolCalls() != null && !formatted.get(4).getToolCalls().isEmpty()) {
            assertEquals(
                    "get_capital", formatted.get(4).getToolCalls().get(0).getFunction().getName());
            assertEquals(
                    "Japan",
                    formatted
                            .get(4)
                            .getToolCalls()
                            .get(0)
                            .getFunction()
                            .getArguments()
                            .get("country"));
        }

        assertEquals("tool", formatted.get(5).getRole());
        assertEquals("1", formatted.get(5).getToolCallId());
        assertEquals("get_capital", formatted.get(5).getName());
        assertNotNull(formatted.get(5).getContent());
        assertTrue(formatted.get(5).getContent().contains("The capital of Japan is Tokyo."));

        assertEquals("assistant", formatted.get(6).getRole());
        assertEquals("The capital of Japan is Tokyo.", formatted.get(6).getContent());
    }

    @Test
    @DisplayName("Should format messages without system message")
    void testFormatMessagesWithoutSystem() {
        // 准备：对话与工具消息，不含系统消息
        List<Msg> msgsConversation =
                Arrays.asList(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .name("user")
                                .content(
                                        TextBlock.builder()
                                                .text("What is the capital of France?")
                                                .build())
                                .build(),
                        Msg.builder()
                                .role(MsgRole.ASSISTANT)
                                .name("assistant")
                                .content(
                                        TextBlock.builder()
                                                .text("The capital of France is Paris.")
                                                .build())
                                .build(),
                        Msg.builder()
                                .role(MsgRole.USER)
                                .name("user")
                                .content(
                                        TextBlock.builder()
                                                .text("What is the capital of Japan?")
                                                .build())
                                .build());

        List<Msg> msgsTools =
                Arrays.asList(
                        Msg.builder()
                                .role(MsgRole.ASSISTANT)
                                .name("assistant")
                                .content(
                                        Arrays.asList(
                                                ToolUseBlock.builder()
                                                        .id("1")
                                                        .name("get_capital")
                                                        .input(
                                                                Collections.singletonMap(
                                                                        "country", "Japan"))
                                                        .build()))
                                .build(),
                        Msg.builder()
                                .role(MsgRole.TOOL)
                                .name("system")
                                .content(
                                        Arrays.asList(
                                                ToolResultBlock.of(
                                                        "1",
                                                        "get_capital",
                                                        Arrays.asList(
                                                                TextBlock.builder()
                                                                        .text(
                                                                                "The capital of"
                                                                                    + " Japan is"
                                                                                    + " Tokyo.")
                                                                        .build(),
                                                                ImageBlock.builder()
                                                                        .source(
                                                                                URLSource.builder()
                                                                                        .url(
                                                                                                imagePath)
                                                                                        .build())
                                                                        .build()))))
                                .build(),
                        Msg.builder()
                                .role(MsgRole.ASSISTANT)
                                .name("assistant")
                                .content(
                                        TextBlock.builder()
                                                .text("The capital of Japan is Tokyo.")
                                                .build())
                                .build());

        // 执行
        List<OllamaMessage> formatted = formatter.format(concatLists(msgsConversation, msgsTools));

        // 断言：应匹配 ground_truth_chat[1:]，不含系统消息
        assertEquals(6, formatted.size());
        assertEquals("user", formatted.get(0).getRole());
        assertEquals("assistant", formatted.get(1).getRole());
        assertEquals("user", formatted.get(2).getRole());
        assertEquals("assistant", formatted.get(3).getRole());
        assertEquals("tool", formatted.get(4).getRole());
        assertEquals("assistant", formatted.get(5).getRole());
    }

    @Test
    @DisplayName("Should format messages with promote tool result images")
    void testFormatMessagesWithPromoteToolResultImages() {
        // 准备：创建 promoteToolResultImages = true 的格式化器
        OllamaChatFormatter formatterWithPromote = new OllamaChatFormatter(true);

        // 准备：系统消息
        List<Msg> msgsSystem =
                Arrays.asList(
                        Msg.builder()
                                .role(MsgRole.SYSTEM)
                                .name("system")
                                .content(
                                        TextBlock.builder()
                                                .text("You're a helpful assistant.")
                                                .build())
                                .build());

        // 准备：对话消息
        List<Msg> msgsConversation =
                Arrays.asList(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .name("user")
                                .content(
                                        Arrays.asList(
                                                TextBlock.builder()
                                                        .text("What is the capital of France?")
                                                        .build(),
                                                ImageBlock.builder()
                                                        .source(
                                                                URLSource.builder()
                                                                        .url(imagePath)
                                                                        .build())
                                                        .build()))
                                .build(),
                        Msg.builder()
                                .role(MsgRole.ASSISTANT)
                                .name("assistant")
                                .content(
                                        TextBlock.builder()
                                                .text("The capital of France is Paris.")
                                                .build())
                                .build(),
                        Msg.builder()
                                .role(MsgRole.USER)
                                .name("user")
                                .content(
                                        TextBlock.builder()
                                                .text("What is the capital of Japan?")
                                                .build())
                                .build());

        // 准备：工具消息
        List<Msg> msgsTools =
                Arrays.asList(
                        Msg.builder()
                                .role(MsgRole.ASSISTANT)
                                .name("assistant")
                                .content(
                                        Arrays.asList(
                                                ToolUseBlock.builder()
                                                        .id("1")
                                                        .name("get_capital")
                                                        .input(
                                                                Collections.singletonMap(
                                                                        "country", "Japan"))
                                                        .build()))
                                .build(),
                        Msg.builder()
                                .role(MsgRole.TOOL)
                                .name("system")
                                .content(
                                        Arrays.asList(
                                                ToolResultBlock.of(
                                                        "1",
                                                        "get_capital",
                                                        Arrays.asList(
                                                                TextBlock.builder()
                                                                        .text(
                                                                                "The capital of"
                                                                                    + " Japan is"
                                                                                    + " Tokyo.")
                                                                        .build(),
                                                                ImageBlock.builder()
                                                                        .source(
                                                                                URLSource.builder()
                                                                                        .url(
                                                                                                imagePath)
                                                                                        .build())
                                                                        .build()))))
                                .build(),
                        Msg.builder()
                                .role(MsgRole.ASSISTANT)
                                .name("assistant")
                                .content(
                                        TextBlock.builder()
                                                .text("The capital of Japan is Tokyo.")
                                                .build())
                                .build());

        // 执行
        List<OllamaMessage> formatted =
                formatterWithPromote.format(concatLists(msgsSystem, msgsConversation, msgsTools));

        // 断言：为提升为独立消息的图像多出一条消息，共 8 条而非 7 条
        assertEquals(8, formatted.size());

        assertEquals("system", formatted.get(0).getRole());
        assertEquals("user", formatted.get(1).getRole());
        assertEquals("assistant", formatted.get(2).getRole());
        assertEquals("user", formatted.get(3).getRole());
        assertEquals("assistant", formatted.get(4).getRole());
        assertEquals("tool", formatted.get(5).getRole());

        // 检查提升后的图像消息，应位于索引 6
        assertEquals("user", formatted.get(6).getRole());
        if (formatted.get(6).getContent() != null) {
            assertTrue(
                    formatted.get(6).getContent().contains("image contents from the tool result"));
            assertTrue(formatted.get(6).getContent().contains("get_capital"));
        }
        if (formatted.get(6).getImages() != null) {
            assertEquals(1, formatted.get(6).getImages().size());
        }

        assertEquals("assistant", formatted.get(7).getRole());
    }

    @Test
    @DisplayName("Should parse response correctly")
    void testParseResponse() {
        // 准备
        OllamaResponse response = new OllamaResponse();
        response.setModel("test-model");
        response.setCreatedAt("2024-01-01T00:00:00.000Z");
        response.setMessage(new OllamaMessage("assistant", "Hello"));

        // 执行
        ChatResponse chatResponse = formatter.parseResponse(response, Instant.now());

        // 断言
        assertNotNull(chatResponse);
        assertEquals("test-model", chatResponse.getMetadata().get("model"));
    }

    @Test
    @DisplayName("Should apply generate options correctly")
    void testApplyGenerateOptions() {
        // 准备
        OllamaRequest request = new OllamaRequest();
        GenerateOptions options = GenerateOptions.builder().temperature(0.7).build();
        GenerateOptions defaultOptions = GenerateOptions.builder().temperature(0.5).build();

        // 执行并断言：不应抛异常
        assertDoesNotThrow(() -> formatter.applyOptions(request, options, defaultOptions));
    }

    @Test
    @DisplayName("Should apply Ollama options correctly")
    void testApplyOllamaOptions() {
        // 准备
        OllamaRequest request = new OllamaRequest();
        OllamaOptions options = OllamaOptions.builder().temperature(0.7).build();
        OllamaOptions defaultOptions = OllamaOptions.builder().temperature(0.5).build();

        // 执行并断言：不应抛异常
        assertDoesNotThrow(() -> formatter.applyOptions(request, options, defaultOptions));
    }

    @Test
    @DisplayName("Should apply tools correctly")
    void testApplyTools() {
        // 准备
        OllamaRequest request = new OllamaRequest();
        ToolSchema tool1 =
                ToolSchema.builder().name("test_tool").description("A test tool").build();
        List<ToolSchema> tools = Arrays.asList(tool1);

        // 执行并断言：不应抛异常
        assertDoesNotThrow(() -> formatter.applyTools(request, tools));
    }

    @Test
    @DisplayName("Should apply tool choice correctly")
    void testApplyToolChoice() {
        // 测试 Specific 工具选择
        OllamaRequest request = new OllamaRequest();
        ToolChoice toolChoice = new ToolChoice.Specific("test_tool");

        // 执行并断言：不应抛异常
        assertDoesNotThrow(() -> formatter.applyToolChoice(request, toolChoice));
    }

    @Test
    @DisplayName("Should build complete request with all parameters")
    void testBuildRequest() {
        // 准备
        String model = "test-model";
        OllamaMessage msg1 = new OllamaMessage("user", "Hello");
        List<OllamaMessage> messages = Arrays.asList(msg1);
        boolean stream = false;
        OllamaOptions options = OllamaOptions.builder().temperature(0.7).build();
        OllamaOptions defaultOptions = OllamaOptions.builder().temperature(0.5).build();
        ToolSchema tool = ToolSchema.builder().name("test_tool").description("A test tool").build();
        List<ToolSchema> tools = Arrays.asList(tool);
        ToolChoice toolChoice = new ToolChoice.Auto();

        // 执行
        OllamaRequest request =
                formatter.buildRequest(
                        model, messages, stream, options, defaultOptions, tools, toolChoice);

        // 断言
        assertNotNull(request);
        assertEquals(model, request.getModel());
        assertEquals(messages, request.getMessages());
        assertEquals(stream, request.getStream());
    }

    @Test
    @DisplayName("Should serialize request messages without thinking key when thinking is null")
    void testRequestSerializationOmitsThinkingWhenNull() {
        OllamaMessage userMsg = new OllamaMessage("user", "Hello");
        OllamaMessage assistantMsg = new OllamaMessage("assistant", "Hi there");

        List<OllamaMessage> messages = Arrays.asList(userMsg, assistantMsg);
        OllamaRequest request =
                formatter.buildRequest("test-model", messages, false, null, null, null, null);

        assertNotNull(request);
        String json = io.agentscope.core.util.JsonUtils.getJsonCodec().toJson(request);
        assertFalse(json.contains("\"thinking\""));
    }

    @Test
    @DisplayName("Should serialize request message with thinking key when thinking is present")
    void testRequestSerializationIncludesThinkingWhenPresent() {
        OllamaMessage assistantMsg = new OllamaMessage("assistant", "I will run a tool");
        assistantMsg.setThinking("Planning tool call");

        OllamaRequest request =
                formatter.buildRequest(
                        "test-model",
                        Collections.singletonList(assistantMsg),
                        false,
                        null,
                        null,
                        null,
                        null);

        assertNotNull(request);
        String json = io.agentscope.core.util.JsonUtils.getJsonCodec().toJson(request);
        assertTrue(json.contains("\"thinking\":\"Planning tool call\""));
    }

    @Test
    @DisplayName("Should preserve assistant ThinkingBlock in the formatted request")
    void testFormatPreservesAssistantThinkingBlock() {
        Msg assistantMsg =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .name("assistant")
                        .content(
                                Arrays.asList(
                                        ThinkingBlock.builder()
                                                .thinking("I should call the weather tool.")
                                                .build(),
                                        TextBlock.builder()
                                                .text("Let me check the weather.")
                                                .build(),
                                        ToolUseBlock.builder()
                                                .id("call-123")
                                                .name("get_weather")
                                                .input(Collections.singletonMap("city", "Tokyo"))
                                                .build()))
                        .build();

        List<OllamaMessage> formatted = formatter.format(Collections.singletonList(assistantMsg));
        OllamaRequest request =
                formatter.buildRequest("test-model", formatted, false, null, null, null, null);
        String json = io.agentscope.core.util.JsonUtils.getJsonCodec().toJson(request);

        assertEquals(1, formatted.size());
        assertEquals("assistant", formatted.get(0).getRole());
        assertEquals("I should call the weather tool.", formatted.get(0).getThinking());
        assertEquals("Let me check the weather.", formatted.get(0).getContent());
        assertNotNull(formatted.get(0).getToolCalls());
        assertTrue(json.contains("\"thinking\":\"I should call the weather tool.\""));
    }

    // 连接列表的辅助方法
    private <T> List<T> concatLists(List<T>... lists) {
        return Arrays.stream(lists).flatMap(List::stream).toList();
    }
}
