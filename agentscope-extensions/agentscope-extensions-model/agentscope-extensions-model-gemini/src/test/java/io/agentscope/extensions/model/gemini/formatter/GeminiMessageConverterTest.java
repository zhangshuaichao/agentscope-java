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
package io.agentscope.extensions.model.gemini.formatter;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.genai.types.CodeExecutionResult;
import com.google.genai.types.Content;
import com.google.genai.types.Outcome;
import com.google.genai.types.Part;
import com.google.genai.types.ToolResponse;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.message.VideoBlock;
import io.agentscope.core.util.JsonUtils;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * GeminiMessageConverter 单元测试。
 *
 * <p>验证消息转换逻辑，包括：
 * <ul>
 *   <li>文本消息转换</li>
 *   <li>工具使用与工具结果转换</li>
 *   <li>图像、音频、视频多模态内容转换</li>
 *   <li>USER/ASSISTANT/SYSTEM 到 Gemini 角色的映射</li>
 *   <li>单个与多个输出的工具结果格式化</li>
 *   <li>媒体块转文本引用</li>
 * </ul>
 */
@Tag("unit")
@DisplayName("GeminiMessageConverter Unit Tests")
class GeminiMessageConverterTest {

    private GeminiMessageConverter converter;

    @BeforeEach
    void setUp() {
        converter = new GeminiMessageConverter();
    }

    @Test
    @DisplayName("Should convert empty message list")
    void testConvertEmptyMessages() {
        List<Content> result = converter.convertMessages(new ArrayList<>());

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("Should convert single text message")
    void testConvertSingleTextMessage() {
        Msg msg =
                Msg.builder()
                        .name("user")
                        .content(List.of(TextBlock.builder().text("Hello, world!").build()))
                        .role(MsgRole.USER)
                        .build();

        List<Content> result = converter.convertMessages(List.of(msg));

        assertEquals(1, result.size());
        Content content = result.get(0);
        assertEquals("user", content.role().get());
        assertEquals(1, content.parts().get().size());
        assertEquals("Hello, world!", content.parts().get().get(0).text().get());
    }

    @Test
    @DisplayName("Should convert multiple text messages")
    void testConvertMultipleTextMessages() {
        Msg msg1 =
                Msg.builder()
                        .name("user")
                        .content(List.of(TextBlock.builder().text("First message").build()))
                        .role(MsgRole.USER)
                        .build();

        Msg msg2 =
                Msg.builder()
                        .name("assistant")
                        .content(List.of(TextBlock.builder().text("Second message").build()))
                        .role(MsgRole.ASSISTANT)
                        .build();

        List<Content> result = converter.convertMessages(List.of(msg1, msg2));

        assertEquals(2, result.size());
        assertEquals("user", result.get(0).role().get());
        assertEquals("model", result.get(1).role().get());
    }

    @Test
    @DisplayName("Should convert ASSISTANT role to 'model'")
    void testConvertAssistantRole() {
        Msg msg =
                Msg.builder()
                        .name("assistant")
                        .content(List.of(TextBlock.builder().text("Response").build()))
                        .role(MsgRole.ASSISTANT)
                        .build();

        List<Content> result = converter.convertMessages(List.of(msg));

        assertEquals("model", result.get(0).role().get());
    }

    @Test
    @DisplayName("Should convert USER role to 'user'")
    void testConvertUserRole() {
        Msg msg =
                Msg.builder()
                        .name("user")
                        .content(List.of(TextBlock.builder().text("Question").build()))
                        .role(MsgRole.USER)
                        .build();

        List<Content> result = converter.convertMessages(List.of(msg));

        assertEquals("user", result.get(0).role().get());
    }

    @Test
    @DisplayName("Should convert SYSTEM role to 'user'")
    void testConvertSystemRole() {
        Msg msg =
                Msg.builder()
                        .name("system")
                        .content(List.of(TextBlock.builder().text("System message").build()))
                        .role(MsgRole.SYSTEM)
                        .build();

        List<Content> result = converter.convertMessages(List.of(msg));

        assertEquals("user", result.get(0).role().get());
    }

    @Test
    @DisplayName("Should convert ToolUseBlock to FunctionCall")
    void testConvertToolUseBlock() {
        Map<String, Object> input = new HashMap<>();
        input.put("query", "test");

        ToolUseBlock toolUseBlock =
                ToolUseBlock.builder().id("call_123").name("search").input(input).build();

        Msg msg =
                Msg.builder()
                        .name("assistant")
                        .content(List.of(toolUseBlock))
                        .role(MsgRole.ASSISTANT)
                        .build();

        List<Content> result = converter.convertMessages(List.of(msg));

        assertEquals(1, result.size());
        Content content = result.get(0);
        assertEquals("model", content.role().get());

        Part part = content.parts().get().get(0);
        assertNotNull(part.functionCall().get());
        assertEquals("call_123", part.functionCall().get().id().get());
        assertEquals("search", part.functionCall().get().name().get());
    }

    @Test
    @DisplayName("Should convert ToolResultBlock to independent Content with user role")
    void testConvertToolResultBlock() {
        ToolResultBlock toolResultBlock =
                ToolResultBlock.builder()
                        .id("call_123")
                        .name("search")
                        .output(List.of(TextBlock.builder().text("Result text").build()))
                        .build();

        Msg msg =
                Msg.builder()
                        .name("system")
                        .content(List.of(toolResultBlock))
                        .role(MsgRole.TOOL)
                        .build();

        List<Content> result = converter.convertMessages(List.of(msg));

        assertEquals(1, result.size());
        Content content = result.get(0);
        assertEquals("user", content.role().get());

        Part part = content.parts().get().get(0);
        assertNotNull(part.functionResponse().get());
        assertEquals("call_123", part.functionResponse().get().id().get());
        assertEquals("search", part.functionResponse().get().name().get());
        assertEquals("Result text", part.functionResponse().get().response().get().get("output"));
    }

    @Test
    @DisplayName("Should format tool result with single output")
    void testToolResultSingleOutput() {
        ToolResultBlock toolResultBlock =
                ToolResultBlock.builder()
                        .id("call_123")
                        .name("tool")
                        .output(List.of(TextBlock.builder().text("Single output").build()))
                        .build();

        Msg msg =
                Msg.builder()
                        .name("system")
                        .content(List.of(toolResultBlock))
                        .role(MsgRole.TOOL)
                        .build();

        List<Content> result = converter.convertMessages(List.of(msg));

        Part part = result.get(0).parts().get().get(0);
        String output = (String) part.functionResponse().get().response().get().get("output");
        assertEquals("Single output", output);
    }

    @Test
    @DisplayName("Should format tool result with multiple outputs using dash prefix")
    void testToolResultMultipleOutputs() {
        ToolResultBlock toolResultBlock =
                ToolResultBlock.builder()
                        .id("call_123")
                        .name("tool")
                        .output(
                                List.of(
                                        TextBlock.builder().text("First output").build(),
                                        TextBlock.builder().text("Second output").build(),
                                        TextBlock.builder().text("Third output").build()))
                        .build();

        Msg msg =
                Msg.builder()
                        .name("system")
                        .content(List.of(toolResultBlock))
                        .role(MsgRole.TOOL)
                        .build();

        List<Content> result = converter.convertMessages(List.of(msg));

        Part part = result.get(0).parts().get().get(0);
        String output = (String) part.functionResponse().get().response().get().get("output");
        assertEquals("- First output\n- Second output\n- Third output", output);
    }

    @Test
    @DisplayName("Should handle tool result with URL image")
    void testToolResultWithURLImage() {
        ImageBlock imageBlock =
                ImageBlock.builder()
                        .source(URLSource.builder().url("https://example.com/image.png").build())
                        .build();

        ToolResultBlock toolResultBlock =
                ToolResultBlock.builder()
                        .id("call_123")
                        .name("tool")
                        .output(
                                List.of(
                                        TextBlock.builder().text("Here is the image:").build(),
                                        imageBlock))
                        .build();

        Msg msg =
                Msg.builder()
                        .name("system")
                        .content(List.of(toolResultBlock))
                        .role(MsgRole.TOOL)
                        .build();

        List<Content> result = converter.convertMessages(List.of(msg));

        Part part = result.get(0).parts().get().get(0);
        String output = (String) part.functionResponse().get().response().get().get("output");
        assertTrue(output.contains("Here is the image:"));
        assertTrue(
                output.contains(
                        "The returned image can be found at: https://example.com/image.png"));
    }

    @Test
    @DisplayName("Should handle tool result with Base64 image")
    void testToolResultWithBase64Image() {
        String base64Data = Base64.getEncoder().encodeToString("fake image data".getBytes());

        ImageBlock imageBlock =
                ImageBlock.builder()
                        .source(
                                Base64Source.builder()
                                        .mediaType("image/png")
                                        .data(base64Data)
                                        .build())
                        .build();

        ToolResultBlock toolResultBlock =
                ToolResultBlock.builder()
                        .id("call_123")
                        .name("tool")
                        .output(List.of(imageBlock))
                        .build();

        Msg msg =
                Msg.builder()
                        .name("system")
                        .content(List.of(toolResultBlock))
                        .role(MsgRole.TOOL)
                        .build();

        List<Content> result = converter.convertMessages(List.of(msg));

        Part part = result.get(0).parts().get().get(0);
        String output = (String) part.functionResponse().get().response().get().get("output");
        assertTrue(output.contains("The returned image can be found at:"));
        assertTrue(output.contains("agentscope_"));
        assertTrue(output.contains(".png"));
    }

    @Test
    @DisplayName("Should handle tool result with URL audio")
    void testToolResultWithURLAudio() {
        AudioBlock audioBlock =
                AudioBlock.builder()
                        .source(URLSource.builder().url("https://example.com/audio.mp3").build())
                        .build();

        ToolResultBlock toolResultBlock =
                ToolResultBlock.builder()
                        .id("call_123")
                        .name("tool")
                        .output(List.of(audioBlock))
                        .build();

        Msg msg =
                Msg.builder()
                        .name("system")
                        .content(List.of(toolResultBlock))
                        .role(MsgRole.TOOL)
                        .build();

        List<Content> result = converter.convertMessages(List.of(msg));

        Part part = result.get(0).parts().get().get(0);
        String output = (String) part.functionResponse().get().response().get().get("output");
        assertTrue(
                output.contains(
                        "The returned audio can be found at: https://example.com/audio.mp3"));
    }

    @Test
    @DisplayName("Should handle tool result with URL video")
    void testToolResultWithURLVideo() {
        VideoBlock videoBlock =
                VideoBlock.builder()
                        .source(URLSource.builder().url("https://example.com/video.mp4").build())
                        .build();

        ToolResultBlock toolResultBlock =
                ToolResultBlock.builder()
                        .id("call_123")
                        .name("tool")
                        .output(List.of(videoBlock))
                        .build();

        Msg msg =
                Msg.builder()
                        .name("system")
                        .content(List.of(toolResultBlock))
                        .role(MsgRole.TOOL)
                        .build();

        List<Content> result = converter.convertMessages(List.of(msg));

        Part part = result.get(0).parts().get().get(0);
        String output = (String) part.functionResponse().get().response().get().get("output");
        assertTrue(
                output.contains(
                        "The returned video can be found at: https://example.com/video.mp4"));
    }

    @Test
    @DisplayName("Should handle empty tool result output")
    void testToolResultEmptyOutput() {
        ToolResultBlock toolResultBlock =
                ToolResultBlock.builder()
                        .id("call_123")
                        .name("tool")
                        .output(new ArrayList<>())
                        .build();

        Msg msg =
                Msg.builder()
                        .name("system")
                        .content(List.of(toolResultBlock))
                        .role(MsgRole.TOOL)
                        .build();

        List<Content> result = converter.convertMessages(List.of(msg));

        Part part = result.get(0).parts().get().get(0);
        String output = (String) part.functionResponse().get().response().get().get("output");
        assertEquals("", output);
    }

    @Test
    @DisplayName("Should convert ImageBlock to inline data part")
    void testConvertImageBlock() {
        String base64Data = Base64.getEncoder().encodeToString("fake image".getBytes());

        ImageBlock imageBlock =
                ImageBlock.builder()
                        .source(
                                Base64Source.builder()
                                        .mediaType("image/jpeg")
                                        .data(base64Data)
                                        .build())
                        .build();

        Msg msg =
                Msg.builder().name("user").content(List.of(imageBlock)).role(MsgRole.USER).build();

        List<Content> result = converter.convertMessages(List.of(msg));

        assertEquals(1, result.size());
        Content content = result.get(0);
        assertEquals(1, content.parts().get().size());
        // 实际转换由媒体转换器处理
        assertNotNull(content.parts().get().get(0));
    }

    @Test
    @DisplayName("Should convert AudioBlock to inline data part")
    void testConvertAudioBlock() {
        String base64Data = Base64.getEncoder().encodeToString("fake audio".getBytes());

        AudioBlock audioBlock =
                AudioBlock.builder()
                        .source(
                                Base64Source.builder()
                                        .mediaType("audio/wav")
                                        .data(base64Data)
                                        .build())
                        .build();

        Msg msg =
                Msg.builder().name("user").content(List.of(audioBlock)).role(MsgRole.USER).build();

        List<Content> result = converter.convertMessages(List.of(msg));

        assertEquals(1, result.size());
        assertNotNull(result.get(0).parts().get().get(0));
    }

    @Test
    @DisplayName("Should convert VideoBlock to inline data part")
    void testConvertVideoBlock() {
        String base64Data = Base64.getEncoder().encodeToString("fake video".getBytes());

        VideoBlock videoBlock =
                VideoBlock.builder()
                        .source(
                                Base64Source.builder()
                                        .mediaType("video/mp4")
                                        .data(base64Data)
                                        .build())
                        .build();

        Msg msg =
                Msg.builder().name("user").content(List.of(videoBlock)).role(MsgRole.USER).build();

        List<Content> result = converter.convertMessages(List.of(msg));

        assertEquals(1, result.size());
        assertNotNull(result.get(0).parts().get().get(0));
    }

    @Test
    @DisplayName("Should skip ThinkingBlock")
    void testSkipThinkingBlock() {
        ThinkingBlock thinkingBlock =
                ThinkingBlock.builder().thinking("Internal reasoning").build();

        Msg msg =
                Msg.builder()
                        .name("assistant")
                        .content(
                                List.of(
                                        thinkingBlock,
                                        TextBlock.builder().text("Visible response").build()))
                        .role(MsgRole.ASSISTANT)
                        .build();

        List<Content> result = converter.convertMessages(List.of(msg));

        assertEquals(1, result.size());
        Content content = result.get(0);
        assertEquals(1, content.parts().get().size());
        assertEquals("Visible response", content.parts().get().get(0).text().get());
    }

    @Test
    @DisplayName("Should skip message with only ThinkingBlock")
    void testSkipMessageWithOnlyThinkingBlock() {
        ThinkingBlock thinkingBlock =
                ThinkingBlock.builder().thinking("Internal reasoning").build();

        Msg msg =
                Msg.builder()
                        .name("assistant")
                        .content(List.of(thinkingBlock))
                        .role(MsgRole.ASSISTANT)
                        .build();

        List<Content> result = converter.convertMessages(List.of(msg));

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("Should handle mixed content types")
    void testMixedContentTypes() {
        String base64Data = Base64.getEncoder().encodeToString("fake image".getBytes());

        Msg msg =
                Msg.builder()
                        .name("user")
                        .content(
                                List.of(
                                        TextBlock.builder().text("Here is an image:").build(),
                                        ImageBlock.builder()
                                                .source(
                                                        Base64Source.builder()
                                                                .mediaType("image/png")
                                                                .data(base64Data)
                                                                .build())
                                                .build(),
                                        TextBlock.builder().text("What do you see?").build()))
                        .role(MsgRole.USER)
                        .build();

        List<Content> result = converter.convertMessages(List.of(msg));

        assertEquals(1, result.size());
        Content content = result.get(0);
        assertEquals(3, content.parts().get().size());
    }

    @Test
    @DisplayName("Should handle message with text and tool use")
    void testMessageWithTextAndToolUse() {
        Map<String, Object> input = new HashMap<>();
        input.put("query", "test");

        Msg msg =
                Msg.builder()
                        .name("assistant")
                        .content(
                                List.of(
                                        TextBlock.builder().text("Let me search for that.").build(),
                                        ToolUseBlock.builder()
                                                .id("call_123")
                                                .name("search")
                                                .input(input)
                                                .build()))
                        .role(MsgRole.ASSISTANT)
                        .build();

        List<Content> result = converter.convertMessages(List.of(msg));

        assertEquals(1, result.size());
        Content content = result.get(0);
        assertEquals(2, content.parts().get().size());
    }

    @Test
    @DisplayName("Should create separate Content for tool result")
    void testSeparateContentForToolResult() {
        Msg msg =
                Msg.builder()
                        .name("system")
                        .content(
                                List.of(
                                        TextBlock.builder().text("Before tool result").build(),
                                        ToolResultBlock.builder()
                                                .id("call_123")
                                                .name("tool")
                                                .output(
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text("Result")
                                                                        .build()))
                                                .build(),
                                        TextBlock.builder().text("After tool result").build()))
                        .role(MsgRole.TOOL)
                        .build();

        List<Content> result = converter.convertMessages(List.of(msg));

        // 应有 2 个 Content 对象：先是消息自身的文本部分，再追加工具
        // 结果内容，确保响应始终在调用之后。
        assertEquals(2, result.size());

        // 第一个内容对象应含前后两段文本
        Content textContent = result.get(0);
        assertEquals("user", textContent.role().get());
        assertEquals(2, textContent.parts().get().size());

        // 第二个内容对象应为工具结果，追加在消息内容后
        Content toolResultContent = result.get(1);
        assertEquals("user", toolResultContent.role().get());
        assertNotNull(toolResultContent.parts().get().get(0).functionResponse().get());
    }

    @Test
    @DisplayName("Should handle consecutive messages with different roles")
    void testConsecutiveMessagesWithDifferentRoles() {
        Msg userMsg =
                Msg.builder()
                        .name("user")
                        .content(List.of(TextBlock.builder().text("Question").build()))
                        .role(MsgRole.USER)
                        .build();

        Msg assistantMsg =
                Msg.builder()
                        .name("assistant")
                        .content(List.of(TextBlock.builder().text("Answer").build()))
                        .role(MsgRole.ASSISTANT)
                        .build();

        Msg systemMsg =
                Msg.builder()
                        .name("system")
                        .content(List.of(TextBlock.builder().text("System info").build()))
                        .role(MsgRole.SYSTEM)
                        .build();

        List<Content> result = converter.convertMessages(List.of(userMsg, assistantMsg, systemMsg));

        assertEquals(3, result.size());
        assertEquals("user", result.get(0).role().get());
        assertEquals("model", result.get(1).role().get());
        assertEquals("user", result.get(2).role().get());
    }

    @Test
    @DisplayName("Should handle complex conversation flow")
    void testComplexConversationFlow() {
        // 用户问题
        Msg userMsg =
                Msg.builder()
                        .name("user")
                        .content(List.of(TextBlock.builder().text("What's the weather?").build()))
                        .role(MsgRole.USER)
                        .build();

        // 助手工具调用
        Map<String, Object> input = new HashMap<>();
        input.put("location", "Tokyo");

        Msg toolCallMsg =
                Msg.builder()
                        .name("assistant")
                        .content(
                                List.of(
                                        ToolUseBlock.builder()
                                                .id("call_123")
                                                .name("get_weather")
                                                .input(input)
                                                .build()))
                        .role(MsgRole.ASSISTANT)
                        .build();

        // 工具结果
        Msg toolResultMsg =
                Msg.builder()
                        .name("system")
                        .content(
                                List.of(
                                        ToolResultBlock.builder()
                                                .id("call_123")
                                                .name("get_weather")
                                                .output(
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text("Sunny, 25°C")
                                                                        .build()))
                                                .build()))
                        .role(MsgRole.TOOL)
                        .build();

        // 助手响应
        Msg responseMsg =
                Msg.builder()
                        .name("assistant")
                        .content(
                                List.of(
                                        TextBlock.builder()
                                                .text("The weather in Tokyo is sunny with 25°C.")
                                                .build()))
                        .role(MsgRole.ASSISTANT)
                        .build();

        List<Content> result =
                converter.convertMessages(
                        List.of(userMsg, toolCallMsg, toolResultMsg, responseMsg));

        assertEquals(4, result.size());

        // 验证角色
        assertEquals("user", result.get(0).role().get());
        assertEquals("model", result.get(1).role().get());
        assertEquals("user", result.get(2).role().get()); // 工具结果
        assertEquals("model", result.get(3).role().get());

        // 验证工具调用
        assertNotNull(result.get(1).parts().get().get(0).functionCall().get());
        assertEquals(
                "get_weather",
                result.get(1).parts().get().get(0).functionCall().get().name().get());

        // 验证工具结果
        assertNotNull(result.get(2).parts().get().get(0).functionResponse().get());
        assertEquals(
                "Sunny, 25°C",
                result.get(2)
                        .parts()
                        .get()
                        .get(0)
                        .functionResponse()
                        .get()
                        .response()
                        .get()
                        .get("output"));
    }

    @Test
    @DisplayName("Should convert ToolUseBlock with thoughtSignature")
    void testConvertToolUseBlockWithThoughtSignature() {
        Map<String, Object> input = new HashMap<>();
        input.put("query", "test");

        byte[] thoughtSignature = "test-signature".getBytes();
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(ToolUseBlock.METADATA_THOUGHT_SIGNATURE, thoughtSignature);

        ToolUseBlock toolUseBlock =
                ToolUseBlock.builder()
                        .id("call_with_sig")
                        .name("search")
                        .input(input)
                        .metadata(metadata)
                        .build();

        Msg msg =
                Msg.builder()
                        .name("assistant")
                        .content(List.of(toolUseBlock))
                        .role(MsgRole.ASSISTANT)
                        .build();

        List<Content> result = converter.convertMessages(List.of(msg));

        assertEquals(1, result.size());
        Content content = result.get(0);
        assertEquals("model", content.role().get());

        Part part = content.parts().get().get(0);
        assertNotNull(part.functionCall().get());
        assertEquals("call_with_sig", part.functionCall().get().id().get());
        assertEquals("search", part.functionCall().get().name().get());

        // 验证 Part 附带思考签名
        assertTrue(part.thoughtSignature().isPresent());
        assertArrayEquals(thoughtSignature, part.thoughtSignature().get());
    }

    @Test
    @DisplayName("Should convert ToolUseBlock without thoughtSignature")
    void testConvertToolUseBlockWithoutThoughtSignature() {
        Map<String, Object> input = new HashMap<>();
        input.put("query", "test");

        ToolUseBlock toolUseBlock =
                ToolUseBlock.builder().id("call_no_sig").name("search").input(input).build();

        Msg msg =
                Msg.builder()
                        .name("assistant")
                        .content(List.of(toolUseBlock))
                        .role(MsgRole.ASSISTANT)
                        .build();

        List<Content> result = converter.convertMessages(List.of(msg));

        assertEquals(1, result.size());
        Part part = result.get(0).parts().get().get(0);

        assertNotNull(part.functionCall().get());
        // 验证不存在思考签名
        assertFalse(part.thoughtSignature().isPresent());
    }

    @Test
    @DisplayName("Should handle round-trip of thoughtSignature in function calling flow")
    void testThoughtSignatureRoundTrip() {
        // 此测试模拟：
        // 1. 模型返回带 thoughtSignature 的函数调用，由 ResponseParser 解析
        // 2. 将签名存入 ToolUseBlock 元数据
        // 3. 随后经 MessageConverter 将函数调用与签名一起回传

        Map<String, Object> input = new HashMap<>();
        input.put("location", "Tokyo");

        byte[] signature = "gemini3-thought-sig-abc123".getBytes();
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(ToolUseBlock.METADATA_THOUGHT_SIGNATURE, signature);

        // 模拟含工具调用的助手消息，如同从解析后的响应构建
        ToolUseBlock toolUseBlock =
                ToolUseBlock.builder()
                        .id("call_roundtrip")
                        .name("get_weather")
                        .input(input)
                        .metadata(metadata)
                        .build();

        Msg assistantMsg =
                Msg.builder()
                        .name("assistant")
                        .content(List.of(toolUseBlock))
                        .role(MsgRole.ASSISTANT)
                        .build();

        // 转为 Gemini 格式，供下次请求发送
        List<Content> result = converter.convertMessages(List.of(assistantMsg));

        // 验证输出保留签名
        assertEquals(1, result.size());
        Part part = result.get(0).parts().get().get(0);

        assertNotNull(part.functionCall().get());
        assertEquals("get_weather", part.functionCall().get().name().get());

        // 签名应附在 Part 上
        assertTrue(part.thoughtSignature().isPresent());
        assertArrayEquals(signature, part.thoughtSignature().get());
    }

    @Test
    @DisplayName("Should use content field when present for tool call arguments")
    void testToolCallUsesContentFieldWhenPresent() {
        // 创建同时含 content 原始字符串和 input 映射的 ToolUseBlock
        // 应优先使用 content 字段
        String rawContent = "{\"city\":\"Beijing\",\"unit\":\"celsius\"}";
        Map<String, Object> inputMap = new HashMap<>();
        inputMap.put("city", "Shanghai");
        inputMap.put("unit", "fahrenheit");

        ToolUseBlock toolBlock =
                ToolUseBlock.builder()
                        .id("call_content_test")
                        .name("get_weather")
                        .input(inputMap)
                        .content(rawContent)
                        .build();

        Msg msg =
                Msg.builder()
                        .name("assistant")
                        .content(List.of(toolBlock))
                        .role(MsgRole.ASSISTANT)
                        .build();

        List<Content> result = converter.convertMessages(List.of(msg));

        assertEquals(1, result.size());
        Part part = result.get(0).parts().get().get(0);
        assertNotNull(part.functionCall().get());

        // 应使用从原始字符串解析的 content 字段，而非 input 映射
        Map<String, Object> args = part.functionCall().get().args().get();
        assertEquals("Beijing", args.get("city"));
        assertEquals("celsius", args.get("unit"));
    }

    @Test
    @DisplayName("Should fallback to input map when content is null")
    void testToolCallFallbackToInputMapWhenContentNull() {
        // 创建仅含 input 映射的 ToolUseBlock，content 为 null
        Map<String, Object> inputMap = new HashMap<>();
        inputMap.put("city", "Beijing");
        inputMap.put("unit", "celsius");

        ToolUseBlock toolBlock =
                ToolUseBlock.builder()
                        .id("call_fallback_test")
                        .name("get_weather")
                        .input(inputMap)
                        .content(null)
                        .build();

        Msg msg =
                Msg.builder()
                        .name("assistant")
                        .content(List.of(toolBlock))
                        .role(MsgRole.ASSISTANT)
                        .build();

        List<Content> result = converter.convertMessages(List.of(msg));

        assertEquals(1, result.size());
        Part part = result.get(0).parts().get().get(0);
        assertNotNull(part.functionCall().get());

        // content 为 null，应使用 input 映射
        Map<String, Object> args = part.functionCall().get().args().get();
        assertEquals("Beijing", args.get("city"));
        assertEquals("celsius", args.get("unit"));
    }

    @Test
    @DisplayName("Should fallback to input map when content is empty")
    void testToolCallFallbackToInputMapWhenContentEmpty() {
        // 创建 content 为空字符串的 ToolUseBlock
        Map<String, Object> inputMap = new HashMap<>();
        inputMap.put("city", "Shanghai");
        inputMap.put("unit", "fahrenheit");

        ToolUseBlock toolBlock =
                ToolUseBlock.builder()
                        .id("call_empty_content_test")
                        .name("get_weather")
                        .input(inputMap)
                        .content("")
                        .build();

        Msg msg =
                Msg.builder()
                        .name("assistant")
                        .content(List.of(toolBlock))
                        .role(MsgRole.ASSISTANT)
                        .build();

        List<Content> result = converter.convertMessages(List.of(msg));

        assertEquals(1, result.size());
        Part part = result.get(0).parts().get().get(0);
        assertNotNull(part.functionCall().get());

        // content 为空，应使用 input 映射
        Map<String, Object> args = part.functionCall().get().args().get();
        assertEquals("Shanghai", args.get("city"));
        assertEquals("fahrenheit", args.get("unit"));
    }

    @Test
    @DisplayName("Should fallback to input map when content is invalid JSON")
    void testToolCallFallbackToInputMapWhenContentInvalidJson() {
        // 创建 content 为无效 JSON 的 ToolUseBlock
        Map<String, Object> inputMap = new HashMap<>();
        inputMap.put("city", "Tokyo");
        inputMap.put("unit", "celsius");

        ToolUseBlock toolBlock =
                ToolUseBlock.builder()
                        .id("call_invalid_json_test")
                        .name("get_weather")
                        .input(inputMap)
                        .content("{invalid json}")
                        .build();

        Msg msg =
                Msg.builder()
                        .name("assistant")
                        .content(List.of(toolBlock))
                        .role(MsgRole.ASSISTANT)
                        .build();

        List<Content> result = converter.convertMessages(List.of(msg));

        assertEquals(1, result.size());
        Part part = result.get(0).parts().get().get(0);
        assertNotNull(part.functionCall().get());

        // content 为无效 JSON，应回退到 input 映射
        Map<String, Object> args = part.functionCall().get().args().get();
        assertEquals("Tokyo", args.get("city"));
        assertEquals("celsius", args.get("unit"));
    }

    @Test
    @DisplayName(
            "Should keep inline server tool call and result in the model message (regression:"
                    + " previously split and reordered)")
    void testConvertAssistantMessageWithInlineServerToolResult() {
        // 内容块严格模拟 GeminiResponseParser 从以下响应生成的结果：
        // 服务端工具调用及其内联结果，加一个本地函数调用。
        ToolUseBlock serverToolUse =
                ToolUseBlock.builder()
                        .id("call_277215")
                        .name("GOOGLE_SEARCH_WEB")
                        .input(Map.of("queries", List.of("southernmost city in China")))
                        .metadata(Map.of(ToolUseBlock.METADATA_SERVER_TOOL, true))
                        .build();

        ToolResponse serverToolResponse =
                ToolResponse.builder()
                        .id("call_277215")
                        .toolType("GOOGLE_SEARCH_WEB")
                        .response(Map.of("search_suggestions", "southernmost city in China"))
                        .build();

        ToolResultBlock serverToolResult =
                ToolResultBlock.builder()
                        .id("call_277215")
                        .name("GOOGLE_SEARCH_WEB")
                        .metadata(
                                Map.of(
                                        ToolResultBlock.METADATA_SERVER_TOOL,
                                        true,
                                        GeminiResponseParser.METADATA_SERVER_TOOL_RESPONSE,
                                        serverToolResponse.toJson(),
                                        ToolUseBlock.METADATA_THOUGHT_SIGNATURE,
                                        "server-result-signature".getBytes()))
                        .output(
                                TextBlock.builder()
                                        .text(
                                                "{\"search_suggestions\":\"southernmost city in"
                                                        + " China\"}")
                                        .build())
                        .build();

        ToolUseBlock localToolUse =
                ToolUseBlock.builder()
                        .id("call_277275")
                        .name("getWeather")
                        .input(Map.of("location", "Sansha, China"))
                        .build();

        Msg msg =
                Msg.builder()
                        .name("assistant")
                        .content(List.of(serverToolUse, serverToolResult, localToolUse))
                        .role(MsgRole.ASSISTANT)
                        .build();

        // 为下一次请求格式化时不得抛异常；回归案例是 convertValue(String, Map)
        // 曾对序列化后的响应 JSON 抛出 IllegalArgumentException。
        List<Content> result = converter.convertMessages(List.of(msg));

        // Gemini 要求将原始候选内容作为一条模型消息回传：
        // toolCall、toolResponse、functionCall 保持在一起，并保留各自签名。
        assertEquals(1, result.size(), "One model content expected");

        Content modelContent = result.get(0);
        assertEquals("model", modelContent.role().get());
        List<Part> modelParts = modelContent.parts().get();
        assertEquals(3, modelParts.size(), "toolCall, toolResponse and functionCall expected");

        Part toolCallPart = modelParts.get(0);
        assertTrue(toolCallPart.toolCall().isPresent());
        assertEquals("call_277215", toolCallPart.toolCall().get().id().get());
        assertEquals(
                "GOOGLE_SEARCH_WEB", toolCallPart.toolCall().get().toolType().get().toString());

        Part toolResponsePart = modelParts.get(1);
        assertTrue(toolResponsePart.toolResponse().isPresent());
        ToolResponse toolResponse = toolResponsePart.toolResponse().get();
        assertEquals("call_277215", toolResponse.id().get());
        assertEquals("GOOGLE_SEARCH_WEB", toolResponse.toolType().get().toString());
        assertArrayEquals(
                "server-result-signature".getBytes(),
                toolResponsePart.thoughtSignature().orElseThrow());
        assertEquals(
                "southernmost city in China",
                toolResponse.response().get().get("search_suggestions"));

        Part functionCallPart = modelParts.get(2);
        assertTrue(functionCallPart.functionCall().isPresent());
        assertEquals("getWeather", functionCallPart.functionCall().get().name().get());
    }

    @Test
    @DisplayName("Should skip server tool result without raw metadata")
    void testConvertServerToolResultWithNonJsonOutput() {
        ToolResultBlock serverToolResult =
                ToolResultBlock.builder()
                        .id("call_1")
                        .name("GOOGLE_SEARCH_WEB")
                        .metadata(Map.of(ToolResultBlock.METADATA_SERVER_TOOL, true))
                        .output(TextBlock.builder().text("plain text result").build())
                        .build();

        Msg msg =
                Msg.builder()
                        .name("assistant")
                        .content(List.of(serverToolResult))
                        .role(MsgRole.ASSISTANT)
                        .build();

        List<Content> result = converter.convertMessages(List.of(msg));

        assertEquals(0, result.size());
    }

    @Test
    @DisplayName("Should keep server tool response parseable with realistic Gemini payload")
    void testConvertServerToolResultWithSearchSuggestionsHtml() {
        // 模拟真实 GOOGLE_SEARCH_WEB toolResponse 载荷结构
        String htmlSuggestions =
                "<div class=\"container\">\n"
                        + "  <a class=\"chip\""
                        + " href=\"https://www.google.com/search?q=southernmost+city+in+China\">"
                        + "southernmost city in China</a>\n"
                        + "</div>";
        String json =
                JsonUtils.getJsonCodec().toJson(Map.of("search_suggestions", htmlSuggestions));
        ToolResponse original =
                ToolResponse.builder()
                        .id("call_2")
                        .toolType("GOOGLE_SEARCH_WEB")
                        .response(Map.of("search_suggestions", htmlSuggestions))
                        .build();

        ToolResultBlock serverToolResult =
                ToolResultBlock.builder()
                        .id("call_2")
                        .name("GOOGLE_SEARCH_WEB")
                        .metadata(
                                Map.of(
                                        ToolResultBlock.METADATA_SERVER_TOOL,
                                        true,
                                        GeminiResponseParser.METADATA_SERVER_TOOL_RESPONSE,
                                        original.toJson()))
                        .output(TextBlock.builder().text(json).build())
                        .build();

        Msg msg =
                Msg.builder()
                        .name("assistant")
                        .content(List.of(serverToolResult))
                        .role(MsgRole.ASSISTANT)
                        .build();

        List<Content> result = converter.convertMessages(List.of(msg));

        Part part = result.get(0).parts().get().get(0);
        ToolResponse toolResponse = part.toolResponse().get();
        assertEquals(htmlSuggestions, toolResponse.response().get().get("search_suggestions"));
    }

    @Test
    @DisplayName("Should convert code execution blocks back to Gemini parts")
    void testConvertCodeExecutionBlocks() {
        ToolUseBlock executableCode =
                ToolUseBlock.builder()
                        .id("code-execution-1")
                        .name("CODE_EXECUTION")
                        .input(Map.of("language", "PYTHON", "code", "print(1 + 1)"))
                        .metadata(
                                Map.of(
                                        ToolUseBlock.METADATA_SERVER_TOOL,
                                        true,
                                        GeminiResponseParser.METADATA_CODE_EXECUTION,
                                        true,
                                        ToolUseBlock.METADATA_THOUGHT_SIGNATURE,
                                        "code-signature".getBytes()))
                        .build();

        CodeExecutionResult original =
                CodeExecutionResult.builder()
                        .id("code-execution-1")
                        .outcome(Outcome.Known.OUTCOME_OK)
                        .output("2")
                        .build();

        ToolResultBlock codeExecutionResult =
                ToolResultBlock.builder()
                        .id("code-execution-1")
                        .name("CODE_EXECUTION")
                        .output(TextBlock.builder().text("2").build())
                        .metadata(
                                Map.of(
                                        ToolResultBlock.METADATA_SERVER_TOOL,
                                        true,
                                        GeminiResponseParser.METADATA_CODE_EXECUTION,
                                        true,
                                        GeminiResponseParser.METADATA_CODE_EXECUTION_RESULT,
                                        original.toJson(),
                                        ToolUseBlock.METADATA_THOUGHT_SIGNATURE,
                                        "result-signature".getBytes()))
                        .build();

        Msg msg =
                Msg.builder()
                        .name("assistant")
                        .content(List.of(executableCode, codeExecutionResult))
                        .role(MsgRole.ASSISTANT)
                        .build();

        List<Content> result = converter.convertMessages(List.of(msg));

        assertEquals(1, result.size());
        List<Part> parts = result.get(0).parts().get();
        assertEquals(2, parts.size());

        Part executableCodePart = parts.get(0);
        assertTrue(executableCodePart.executableCode().isPresent());
        assertEquals("code-execution-1", executableCodePart.executableCode().get().id().get());
        assertEquals(
                "PYTHON", executableCodePart.executableCode().get().language().get().toString());
        assertEquals(
                "print(1 + 1)", executableCodePart.executableCode().get().code().orElseThrow());
        assertArrayEquals(
                "code-signature".getBytes(), executableCodePart.thoughtSignature().orElseThrow());

        Part resultPart = parts.get(1);
        assertTrue(resultPart.codeExecutionResult().isPresent());
        assertEquals("code-execution-1", resultPart.codeExecutionResult().get().id().get());
        assertEquals(
                "OUTCOME_OK", resultPart.codeExecutionResult().get().outcome().get().toString());
        assertEquals("2", resultPart.codeExecutionResult().get().output().orElseThrow());
        assertArrayEquals(
                "result-signature".getBytes(), resultPart.thoughtSignature().orElseThrow());
    }

    @Test
    @DisplayName("Should restore server tool result signature after JSON persistence")
    void testServerToolResultSignatureRestoredAfterJsonRoundTrip() {
        ToolResponse original =
                ToolResponse.builder()
                        .id("server-result-1")
                        .toolType("GOOGLE_SEARCH_WEB")
                        .response(Map.of("result", "ok"))
                        .build();

        ToolResultBlock serverToolResult =
                ToolResultBlock.builder()
                        .id("server-result-1")
                        .name("GOOGLE_SEARCH_WEB")
                        .output(TextBlock.builder().text("{\"result\":\"ok\"}").build())
                        .metadata(
                                Map.of(
                                        ToolResultBlock.METADATA_SERVER_TOOL,
                                        true,
                                        GeminiResponseParser.METADATA_SERVER_TOOL_RESPONSE,
                                        original.toJson(),
                                        ToolUseBlock.METADATA_THOUGHT_SIGNATURE,
                                        "persisted-signature".getBytes()))
                        .build();
        Msg msg =
                Msg.builder()
                        .name("assistant")
                        .content(List.of(serverToolResult))
                        .role(MsgRole.ASSISTANT)
                        .build();

        Msg roundTripped =
                JsonUtils.getJsonCodec().fromJson(JsonUtils.getJsonCodec().toJson(msg), Msg.class);

        List<Content> result = converter.convertMessages(List.of(roundTripped));

        Part part = result.get(0).parts().get().get(0);
        assertTrue(part.toolResponse().isPresent());
        assertArrayEquals("persisted-signature".getBytes(), part.thoughtSignature().orElseThrow());
    }

    @Test
    @DisplayName("Should restore server tool response from raw metadata")
    void testServerToolResponseRestoredFromRawMetadata() {
        ToolResponse original =
                ToolResponse.builder()
                        .id("raw-server-response")
                        .toolType("GOOGLE_SEARCH_WEB")
                        .response(Map.of("search_suggestions", "raw response"))
                        .build();

        ToolResultBlock serverToolResult =
                ToolResultBlock.builder()
                        .id("raw-server-response")
                        .name("GOOGLE_SEARCH_WEB")
                        .output(TextBlock.builder().text("corrupted display text").build())
                        .metadata(
                                Map.of(
                                        ToolResultBlock.METADATA_SERVER_TOOL,
                                        true,
                                        GeminiResponseParser.METADATA_SERVER_TOOL_RESPONSE,
                                        original.toJson()))
                        .build();
        Msg msg =
                Msg.builder()
                        .name("assistant")
                        .content(List.of(serverToolResult))
                        .role(MsgRole.ASSISTANT)
                        .build();

        List<Content> result = converter.convertMessages(List.of(msg));

        Part part = result.get(0).parts().get().get(0);
        assertTrue(part.toolResponse().isPresent());
        assertEquals("raw-server-response", part.toolResponse().get().id().orElseThrow());
        assertEquals(
                "GOOGLE_SEARCH_WEB", part.toolResponse().get().toolType().orElseThrow().toString());
        assertEquals(
                "raw response",
                part.toolResponse().get().response().orElseThrow().get("search_suggestions"));
    }

    @Test
    @DisplayName("Should restore code execution result from raw metadata")
    void testCodeExecutionResultRestoredFromRawMetadata() {
        CodeExecutionResult original =
                CodeExecutionResult.builder()
                        .id("raw-code-result")
                        .outcome(Outcome.Known.OUTCOME_OK)
                        .output("42")
                        .build();

        ToolResultBlock codeResult =
                ToolResultBlock.builder()
                        .id("raw-code-result")
                        .name("CODE_EXECUTION")
                        .output(TextBlock.builder().text("corrupted display text").build())
                        .metadata(
                                Map.of(
                                        ToolResultBlock.METADATA_SERVER_TOOL,
                                        true,
                                        GeminiResponseParser.METADATA_CODE_EXECUTION,
                                        true,
                                        GeminiResponseParser.METADATA_CODE_EXECUTION_RESULT,
                                        original.toJson()))
                        .build();
        Msg msg =
                Msg.builder()
                        .name("assistant")
                        .content(List.of(codeResult))
                        .role(MsgRole.ASSISTANT)
                        .build();

        List<Content> result = converter.convertMessages(List.of(msg));

        Part part = result.get(0).parts().get().get(0);
        assertTrue(part.codeExecutionResult().isPresent());
        assertEquals("raw-code-result", part.codeExecutionResult().get().id().orElseThrow());
        assertEquals(
                "OUTCOME_OK", part.codeExecutionResult().get().outcome().orElseThrow().toString());
        assertEquals("42", part.codeExecutionResult().get().output().orElseThrow());
    }
}
