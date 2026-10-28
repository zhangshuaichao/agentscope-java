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
package io.agentscope.extensions.model.dashscope.formatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.extensions.model.dashscope.dto.DashScopeContentPart;
import io.agentscope.extensions.model.dashscope.dto.DashScopeFunction;
import io.agentscope.extensions.model.dashscope.dto.DashScopeMessage;
import io.agentscope.extensions.model.dashscope.dto.DashScopeToolCall;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * DashScopeChatFormatter 基准结果测试。
 * 验证格式化器输出与预期 DashScope API 格式一致。
 */
class DashScopeChatFormatterGroundTruthTest {

    private static DashScopeChatFormatter formatter;
    private static String imagePath;
    private static String mockAudioPath;

    // 测试消息
    private static List<Msg> msgsSystem;
    private static List<Msg> msgsConversation;
    private static List<Msg> msgsTools;

    // 基准结果
    private static List<DashScopeMessage> groundTruthChat;

    @BeforeAll
    static void setUp() throws IOException {
        formatter = new DashScopeChatFormatter();

        // 创建临时图像文件。
        // 使用唯一文件名，避免与其他测试类冲突
        imagePath = "./image_chat_formatter.png";
        File imageFile = new File(imagePath);
        Files.write(imageFile.toPath(), "fake image content".getBytes());

        // 模拟音频路径
        mockAudioPath = "/var/folders/gf/krg8x_ws409cpw_46b2s6rjc0000gn/T/tmpfymnv2w9.wav";

        // 构建测试消息
        buildTestMessages();

        // 构建基准结果
        buildGroundTruth();
    }

    @AfterAll
    static void tearDown() {
        // 清理临时图像文件
        File imageFile = new File(imagePath);
        if (imageFile.exists()) {
            try {
                Files.delete(imageFile.toPath());
            } catch (IOException e) {
                // 忽略删除错误
            }
        }
    }

    private static void buildTestMessages() {
        // 系统消息
        msgsSystem =
                List.of(
                        Msg.builder()
                                .name("system")
                                .content(
                                        List.of(
                                                TextBlock.builder()
                                                        .text("You're a helpful assistant.")
                                                        .build()))
                                .role(MsgRole.SYSTEM)
                                .build());

        // 包含多模态内容的对话消息
        msgsConversation =
                List.of(
                        // 用户消息包含文本和图像
                        Msg.builder()
                                .name("user")
                                .content(
                                        List.of(
                                                TextBlock.builder()
                                                        .text("What is the capital of France?")
                                                        .build(),
                                                ImageBlock.builder()
                                                        .source(
                                                                URLSource.builder()
                                                                        .url(imagePath)
                                                                        .build())
                                                        .build()))
                                .role(MsgRole.USER)
                                .build(),
                        // 助手响应
                        Msg.builder()
                                .name("assistant")
                                .content(
                                        List.of(
                                                TextBlock.builder()
                                                        .text("The capital of France is Paris.")
                                                        .build()))
                                .role(MsgRole.ASSISTANT)
                                .build(),
                        // 用户消息包含文本和音频
                        Msg.builder()
                                .name("user")
                                .content(
                                        List.of(
                                                TextBlock.builder()
                                                        .text("What is the capital of Germany?")
                                                        .build(),
                                                AudioBlock.builder()
                                                        .source(
                                                                URLSource.builder()
                                                                        .url(
                                                                                "https://example.com/audio1.mp3")
                                                                        .build())
                                                        .build()))
                                .role(MsgRole.USER)
                                .build(),
                        // 助手响应
                        Msg.builder()
                                .name("assistant")
                                .content(
                                        List.of(
                                                TextBlock.builder()
                                                        .text("The capital of Germany is Berlin.")
                                                        .build()))
                                .role(MsgRole.ASSISTANT)
                                .build(),
                        // 用户纯文本消息
                        Msg.builder()
                                .name("user")
                                .content(
                                        List.of(
                                                TextBlock.builder()
                                                        .text("What is the capital of Japan?")
                                                        .build()))
                                .role(MsgRole.USER)
                                .build());

        // 工具消息
        Map<String, Object> toolInput = new HashMap<>();
        toolInput.put("country", "Japan");

        msgsTools =
                List.of(
                        // 含工具调用的助手消息
                        Msg.builder()
                                .name("assistant")
                                .content(
                                        List.of(
                                                ToolUseBlock.builder()
                                                        .id("1")
                                                        .name("get_capital")
                                                        .input(toolInput)
                                                        .build()))
                                .role(MsgRole.ASSISTANT)
                                .build(),
                        // 工具结果
                        Msg.builder()
                                .name("system")
                                .content(
                                        List.of(
                                                ToolResultBlock.builder()
                                                        .id("1")
                                                        .name("get_capital")
                                                        .output(
                                                                List.of(
                                                                        TextBlock.builder()
                                                                                .text(
                                                                                        "The capital"
                                                                                            + " of Japan"
                                                                                            + " is Tokyo.")
                                                                                .build(),
                                                                        ImageBlock.builder()
                                                                                .source(
                                                                                        URLSource
                                                                                                .builder()
                                                                                                .url(
                                                                                                        imagePath)
                                                                                                .build())
                                                                                .build(),
                                                                        AudioBlock.builder()
                                                                                .source(
                                                                                        Base64Source
                                                                                                .builder()
                                                                                                .mediaType(
                                                                                                        "audio/wav")
                                                                                                .data(
                                                                                                        "ZmFrZSBhdWRpbyBjb250ZW50")
                                                                                                .build())
                                                                                .build()))
                                                        .build()))
                                .role(MsgRole.TOOL)
                                .build(),
                        // 助手最终响应
                        Msg.builder()
                                .name("assistant")
                                .content(
                                        List.of(
                                                TextBlock.builder()
                                                        .text("The capital of Japan is Tokyo.")
                                                        .build()))
                                .role(MsgRole.ASSISTANT)
                                .build());
    }

    private static void buildGroundTruth() {
        groundTruthChat = new ArrayList<>();
        File imageFile = new File(imagePath);
        String absoluteImagePath = "data:image/png;base64,ZmFrZSBpbWFnZSBjb250ZW50";

        // 消息 1：系统消息
        groundTruthChat.add(
                DashScopeMessage.builder()
                        .role("system")
                        .content(
                                List.of(
                                        DashScopeContentPart.builder()
                                                .text("You're a helpful assistant.")
                                                .build()))
                        .build());

        // 消息 2：用户文本和图像
        groundTruthChat.add(
                DashScopeMessage.builder()
                        .role("user")
                        .content(
                                List.of(
                                        DashScopeContentPart.builder()
                                                .text("What is the capital of France?")
                                                .build(),
                                        DashScopeContentPart.builder()
                                                .image(absoluteImagePath)
                                                .build()))
                        .build());

        // 消息 3：助手响应
        groundTruthChat.add(
                DashScopeMessage.builder()
                        .role("assistant")
                        .content(
                                List.of(
                                        DashScopeContentPart.builder()
                                                .text("The capital of France is Paris.")
                                                .build()))
                        .build());

        // 消息 4：用户文本和音频
        groundTruthChat.add(
                DashScopeMessage.builder()
                        .role("user")
                        .content(
                                List.of(
                                        DashScopeContentPart.builder()
                                                .text("What is the capital of Germany?")
                                                .build(),
                                        DashScopeContentPart.builder()
                                                .audio("https://example.com/audio1.mp3")
                                                .build()))
                        .build());

        // 消息 5：助手响应
        groundTruthChat.add(
                DashScopeMessage.builder()
                        .role("assistant")
                        .content(
                                List.of(
                                        DashScopeContentPart.builder()
                                                .text("The capital of Germany is Berlin.")
                                                .build()))
                        .build());

        // 消息 6：用户纯文本
        groundTruthChat.add(
                DashScopeMessage.builder()
                        .role("user")
                        .content(
                                List.of(
                                        DashScopeContentPart.builder()
                                                .text("What is the capital of Japan?")
                                                .build()))
                        .build());

        // 消息 7：助手工具调用
        DashScopeFunction function = new DashScopeFunction();
        function.setName("get_capital");
        function.setArguments("{\"country\": \"Japan\"}");

        DashScopeToolCall toolCall = new DashScopeToolCall();
        toolCall.setId("1");
        toolCall.setType("function");
        toolCall.setFunction(function);

        groundTruthChat.add(
                DashScopeMessage.builder()
                        .role("assistant")
                        .content(List.of(DashScopeContentPart.builder().text(null).build()))
                        .toolCalls(List.of(toolCall))
                        .build());

        // 消息 8：工具结果
        File imageFile2 = new File(imagePath);
        String absoluteImagePath2 = "data:image/png;base64,ZmFrZSBpbWFnZSBjb250ZW50";
        groundTruthChat.add(
                DashScopeMessage.builder()
                        .role("tool")
                        .toolCallId("1")
                        .name("get_capital")
                        .content(
                                List.of(
                                        DashScopeContentPart.builder()
                                                .text("The capital of Japan is Tokyo.")
                                                .build(),
                                        DashScopeContentPart.builder()
                                                .image(absoluteImagePath2)
                                                .build(),
                                        DashScopeContentPart.builder()
                                                .audio(
                                                        "data:audio/wav;base64,"
                                                                + "ZmFrZSBhdWRpbyBjb250ZW50")
                                                .build()))
                        .build());

        // 消息 9：助手最终响应
        groundTruthChat.add(
                DashScopeMessage.builder()
                        .role("assistant")
                        .content(
                                List.of(
                                        DashScopeContentPart.builder()
                                                .text("The capital of Japan is Tokyo.")
                                                .build()))
                        .build());
    }

    @Test
    void testChatFormatter_FullHistory() {
        // 合并所有消息：system + conversation + tools
        List<Msg> allMessages = new ArrayList<>();
        allMessages.addAll(msgsSystem);
        allMessages.addAll(msgsConversation);
        allMessages.addAll(msgsTools);

        List<DashScopeMessage> result = formatter.formatMultiModal(allMessages);

        assertMultiModalMessagesEqual(groundTruthChat, result);
    }

    @Test
    void testChatFormatter_WithoutSystemMessage() {
        // conversation + tools
        List<Msg> messages = new ArrayList<>();
        messages.addAll(msgsConversation);
        messages.addAll(msgsTools);

        List<DashScopeMessage> result = formatter.formatMultiModal(messages);

        // 基准结果去掉首条系统消息
        List<DashScopeMessage> expected = groundTruthChat.subList(1, groundTruthChat.size());

        assertMultiModalMessagesEqual(expected, result);
    }

    @Test
    void testChatFormatter_WithoutConversation() {
        // system + tools
        List<Msg> messages = new ArrayList<>();
        messages.addAll(msgsSystem);
        messages.addAll(msgsTools);

        List<DashScopeMessage> result = formatter.formatMultiModal(messages);

        // 基准结果：首条消息 + 最后 3 条工具消息
        List<DashScopeMessage> expected = new ArrayList<>();
        expected.add(groundTruthChat.get(0));
        expected.addAll(
                groundTruthChat.subList(
                        groundTruthChat.size() - msgsTools.size(), groundTruthChat.size()));

        assertMultiModalMessagesEqual(expected, result);
    }

    @Test
    void testChatFormatter_WithoutTools() {
        // system + conversation
        List<Msg> messages = new ArrayList<>();
        messages.addAll(msgsSystem);
        messages.addAll(msgsConversation);

        List<DashScopeMessage> result = formatter.formatMultiModal(messages);

        // 基准结果去掉最后 3 条工具消息
        List<DashScopeMessage> expected =
                groundTruthChat.subList(0, groundTruthChat.size() - msgsTools.size());

        assertMultiModalMessagesEqual(expected, result);
    }

    @Test
    void testChatFormatter_EmptyMessages() {
        List<DashScopeMessage> result = formatter.formatMultiModal(List.of());

        assertEquals(0, result.size());
    }

    /**
     * 深度比较两个 MultiModalMessage 列表。
     * 确保格式化器输出与基准结果完全一致。
     */
    private void assertMultiModalMessagesEqual(
            List<DashScopeMessage> expected, List<DashScopeMessage> actual) {
        assertEquals(
                expected.size(),
                actual.size(),
                "Number of messages should match. Expected: "
                        + expected.size()
                        + ", Actual: "
                        + actual.size());

        for (int i = 0; i < expected.size(); i++) {
            DashScopeMessage expectedMsg = expected.get(i);
            DashScopeMessage actualMsg = actual.get(i);

            // 比较角色
            assertEquals(
                    expectedMsg.getRole(), actualMsg.getRole(), "Role should match at index " + i);

            // 比较内容
            assertContentEqual(
                    expectedMsg.getContentAsList(),
                    actualMsg.getContentAsList(),
                    "at message index " + i);

            // 比较工具调用
            assertToolCallsEqual(
                    expectedMsg.getToolCalls(), actualMsg.getToolCalls(), "at message index " + i);

            // 比较工具调用 ID
            assertEquals(
                    expectedMsg.getToolCallId(),
                    actualMsg.getToolCallId(),
                    "Tool call id should match at index " + i);

            // 比较名称
            assertEquals(
                    expectedMsg.getName(), actualMsg.getName(), "Name should match at index " + i);
        }
    }

    private void assertContentEqual(
            List<DashScopeContentPart> expected,
            List<DashScopeContentPart> actual,
            String context) {
        if (expected == null && actual == null) {
            return;
        }
        assertNotNull(expected, "Expected content should not be null " + context);
        assertNotNull(actual, "Actual content should not be null " + context);
        assertEquals(
                expected.size(),
                actual.size(),
                "Content size should match "
                        + context
                        + ". Expected: "
                        + expected.size()
                        + ", Actual: "
                        + actual.size());

        for (int i = 0; i < expected.size(); i++) {
            DashScopeContentPart expectedPart = expected.get(i);
            DashScopeContentPart actualPart = actual.get(i);

            // 比较文本，将 null 与空字符串视为等价
            if (expectedPart.getText() != null || actualPart.getText() != null) {
                String expectedText = expectedPart.getText();
                String actualText = actualPart.getText();
                // 规范化 null 和空字符串
                String normalizedExpected =
                        (expectedText == null || expectedText.isEmpty()) ? "" : expectedText;
                String normalizedActual =
                        (actualText == null || actualText.isEmpty()) ? "" : actualText;
                if (!normalizedExpected.isEmpty()
                        && !normalizedActual.isEmpty()
                        && normalizedExpected.contains("The returned")
                        && normalizedExpected.contains("can be found at:")) {
                    normalizedExpected = normalizeTempFilePaths(normalizedExpected);
                    normalizedActual = normalizeTempFilePaths(normalizedActual);
                }
                assertEquals(
                        normalizedExpected,
                        normalizedActual,
                        "Text should match " + context + " at content index " + i);
            }

            // 比较图像
            if (expectedPart.getImage() != null || actualPart.getImage() != null) {
                String expectedImage = expectedPart.getImage();
                String actualImage = actualPart.getImage();
                if (expectedImage != null
                        && actualImage != null
                        && expectedImage.startsWith("file://")
                        && actualImage.startsWith("file://")) {
                    assertTrue(
                            actualImage.contains(imagePath.replace("./", "")),
                            "Image URL should point to "
                                    + imagePath
                                    + " but was "
                                    + actualImage
                                    + " "
                                    + context);
                } else {
                    assertEquals(
                            expectedImage,
                            actualImage,
                            "Image should match " + context + " at content index " + i);
                }
            }

            // 比较音频
            assertEquals(
                    expectedPart.getAudio(),
                    actualPart.getAudio(),
                    "Audio should match " + context + " at content index " + i);

            // 比较视频
            assertEquals(
                    expectedPart.getVideo(),
                    actualPart.getVideo(),
                    "Video should match " + context + " at content index " + i);
        }
    }

    private void assertToolCallsEqual(
            List<DashScopeToolCall> expected, List<DashScopeToolCall> actual, String context) {
        if (expected == null && actual == null) {
            return;
        }
        if (expected == null || actual == null) {
            assertEquals(
                    expected, actual, "Tool calls should both be null or both non-null " + context);
            return;
        }

        assertEquals(expected.size(), actual.size(), "Tool calls size should match " + context);

        for (int i = 0; i < expected.size(); i++) {
            DashScopeToolCall expectedCall = expected.get(i);
            DashScopeToolCall actualCall = actual.get(i);

            assertEquals(
                    expectedCall.getId(),
                    actualCall.getId(),
                    "Tool call id should match " + context);
            assertEquals(
                    expectedCall.getType(),
                    actualCall.getType(),
                    "Tool call type should match " + context);

            if (expectedCall.getFunction() != null && actualCall.getFunction() != null) {
                assertEquals(
                        expectedCall.getFunction().getName(),
                        actualCall.getFunction().getName(),
                        "Function name should match " + context);
                // 注意：参数比较可能需要规范化空白和顺序
                assertJsonEqual(
                        expectedCall.getFunction().getArguments(),
                        actualCall.getFunction().getArguments(),
                        "Function arguments should match " + context);
            }
        }
    }

    private void assertJsonEqual(String expected, String actual, String context) {
        // 简单 JSON 比较，可进一步采用正式 JSON 解析
        // 目前仅检查非 null 且包含相同键值对
        if (expected == null && actual == null) {
            return;
        }
        assertNotNull(expected, "Expected JSON should not be null " + context);
        assertNotNull(actual, "Actual JSON should not be null " + context);

        // 移除空白后比较
        String normalizedExpected = expected.replaceAll("\\s+", "");
        String normalizedActual = actual.replaceAll("\\s+", "");
        assertEquals(normalizedExpected, normalizedActual, context);
    }

    /**
     * 规范化工具结果文本中的临时文件路径。
     * 将实际临时文件路径替换为占位符，以便比较。
     *
     * @param text 包含临时文件路径的文本
     * @return 替换临时路径后的规范化文本
     */
    private String normalizeTempFilePaths(String text) {
        // 匹配此类行的模式："- The returned audio can be found at:
        // /var/folders/.../tmpXXX.wav"
        // 将实际临时路径替换为占位符
        Pattern pattern =
                Pattern.compile(
                        "(The returned (audio|image|video) can be found at: )[^\\n]+",
                        Pattern.MULTILINE);
        Matcher matcher = pattern.matcher(text);
        String result = matcher.replaceAll("$1<TEMP_FILE>");
        return result.replaceAll("\\s+", " ").trim();
    }
}
