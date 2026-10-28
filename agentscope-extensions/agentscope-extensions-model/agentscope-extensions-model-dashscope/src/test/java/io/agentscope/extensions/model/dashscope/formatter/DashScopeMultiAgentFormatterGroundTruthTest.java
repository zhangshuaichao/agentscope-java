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
 * DashScopeMultiAgentFormatter 基准结果测试。
 * 验证格式化器输出与预期 DashScope API 格式一致。
 */
class DashScopeMultiAgentFormatterGroundTruthTest {

    private static DashScopeMultiAgentFormatter formatter;
    private static String imagePath;
    private static String mockAudioPath;

    // 测试消息
    private static List<Msg> msgsSystem;
    private static List<Msg> msgsConversation;
    private static List<Msg> msgsTools;
    private static List<Msg> msgsConversation2;
    private static List<Msg> msgsTools2;

    // 基准结果
    private static List<DashScopeMessage> groundTruthMultiagent;
    private static List<DashScopeMessage> groundTruthMultiagentWithoutFirstConversation;
    private static List<DashScopeMessage> groundTruthMultiagent2;

    @BeforeAll
    static void setUp() throws IOException {
        formatter = new DashScopeMultiAgentFormatter();

        // 创建临时图像文件。
        // 使用唯一文件名，避免与其他测试类冲突
        imagePath = "./image_multiagent_formatter.png";
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
        File imageFile = new File(imagePath);
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

        // 第二轮对话
        msgsConversation2 =
                List.of(
                        Msg.builder()
                                .name("user")
                                .content(
                                        List.of(
                                                TextBlock.builder()
                                                        .text(
                                                                "What is the capital of South"
                                                                        + " Korea?")
                                                        .build()))
                                .role(MsgRole.USER)
                                .build());

        // 第二轮工具消息
        Map<String, Object> toolInput2 = new HashMap<>();
        toolInput2.put("country", "South Korea");

        msgsTools2 =
                List.of(
                        // 含工具调用的助手消息
                        Msg.builder()
                                .name("assistant")
                                .content(
                                        List.of(
                                                ToolUseBlock.builder()
                                                        .id("1")
                                                        .name("get_capital")
                                                        .input(toolInput2)
                                                        .build()))
                                .role(MsgRole.ASSISTANT)
                                .build(),
                        // 工具结果，使用 tool_call_id "2"
                        Msg.builder()
                                .name("system")
                                .content(
                                        List.of(
                                                ToolResultBlock.builder()
                                                        .id("2")
                                                        .name("get_capital")
                                                        .output(
                                                                List.of(
                                                                        TextBlock.builder()
                                                                                .text(
                                                                                        "The capital"
                                                                                            + " of South"
                                                                                            + " Korea"
                                                                                            + " is Seoul.")
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
                                                        .text(
                                                                "The capital of South Korea is"
                                                                        + " Seoul.")
                                                        .build()))
                                .role(MsgRole.ASSISTANT)
                                .build());
    }

    private static void buildGroundTruth() {
        File imageFile = new File(imagePath);
        String absoluteImagePath = "data:image/png;base64,ZmFrZSBpbWFnZSBjb250ZW50";

        // 构建 groundTruthMultiagent：system + conversation + tools
        groundTruthMultiagent = new ArrayList<>();

        // 消息 1：系统消息
        DashScopeContentPart systemContent =
                DashScopeContentPart.text("You're a helpful assistant.");
        groundTruthMultiagent.add(
                DashScopeMessage.builder().role("system").content(List.of(systemContent)).build());

        // 消息 2：包含对话历史的用户消息
        String historyText =
                "# Conversation History\n"
                        + "The content between <history></history> tags contains your conversation"
                        + " history\n"
                        + "<history>\n"
                        + "user: What is the capital of France?";

        List<DashScopeContentPart> userContent = new ArrayList<>();
        userContent.add(DashScopeContentPart.text(historyText));
        userContent.add(DashScopeContentPart.image(absoluteImagePath));
        userContent.add(
                DashScopeContentPart.text(
                        "assistant: The capital of France is Paris.\n"
                                + "user: What is the capital of Germany?"));
        userContent.add(DashScopeContentPart.audio("https://example.com/audio1.mp3"));
        userContent.add(
                DashScopeContentPart.text(
                        "assistant: The capital of Germany is Berlin.\nuser: What is the capital of"
                                + " Japan?\n</history>"));

        groundTruthMultiagent.add(
                DashScopeMessage.builder().role("user").content(userContent).build());

        // 消息 3：助手工具调用
        DashScopeContentPart assistantEmptyContent = DashScopeContentPart.text(null);

        DashScopeFunction function = new DashScopeFunction();
        function.setName("get_capital");
        function.setArguments("{\"country\": \"Japan\"}");
        DashScopeToolCall toolCall = new DashScopeToolCall();
        toolCall.setId("1");
        toolCall.setType("function");
        toolCall.setFunction(function);

        groundTruthMultiagent.add(
                DashScopeMessage.builder()
                        .role("assistant")
                        .content(List.of(assistantEmptyContent))
                        .toolCalls(List.of(toolCall))
                        .build());

        // 消息 4：工具结果
        List<DashScopeContentPart> toolResultParts =
                List.of(
                        DashScopeContentPart.text("The capital of Japan is Tokyo."),
                        DashScopeContentPart.image(absoluteImagePath),
                        DashScopeContentPart.audio(
                                "data:audio/wav;base64," + "ZmFrZSBhdWRpbyBjb250ZW50"));
        groundTruthMultiagent.add(
                DashScopeMessage.builder()
                        .role("tool")
                        .toolCallId("1")
                        .name("get_capital")
                        .content(toolResultParts)
                        .build());

        // 消息 5：历史中含助手响应的用户消息
        DashScopeContentPart finalUserContent =
                DashScopeContentPart.text(
                        "<history>\nassistant: The capital of Japan is Tokyo.\n</history>");
        groundTruthMultiagent.add(
                DashScopeMessage.builder().role("user").content(List.of(finalUserContent)).build());

        // 构建 groundTruthMultiagentWithoutFirstConversation：system + tools
        groundTruthMultiagentWithoutFirstConversation = new ArrayList<>();

        // 消息 1：系统
        groundTruthMultiagentWithoutFirstConversation.add(
                DashScopeMessage.builder().role("system").content(List.of(systemContent)).build());

        // 消息 2：助手工具调用，同上
        groundTruthMultiagentWithoutFirstConversation.add(
                DashScopeMessage.builder()
                        .role("assistant")
                        .content(List.of(assistantEmptyContent))
                        .toolCalls(List.of(toolCall))
                        .build());

        // 消息 3：工具结果，同上
        groundTruthMultiagentWithoutFirstConversation.add(
                DashScopeMessage.builder()
                        .role("tool")
                        .toolCallId("1")
                        .name("get_capital")
                        .content(toolResultParts)
                        .build());

        // 消息 4：含历史的用户消息
        DashScopeContentPart historyContent =
                DashScopeContentPart.text(
                        "# Conversation History\n"
                                + "The content between <history></history> tags contains your"
                                + " conversation history\n"
                                + "<history>\n"
                                + "assistant: The capital of Japan is Tokyo.\n"
                                + "</history>");
        groundTruthMultiagentWithoutFirstConversation.add(
                DashScopeMessage.builder().role("user").content(List.of(historyContent)).build());

        // 构建 groundTruthMultiagent2：system + conversation + tools + conversation2 + tools2
        groundTruthMultiagent2 = new ArrayList<>();

        // 消息 1：系统
        groundTruthMultiagent2.add(
                DashScopeMessage.builder().role("system").content(List.of(systemContent)).build());

        // 消息 2：首轮对话历史，同 groundTruthMultiagent
        groundTruthMultiagent2.add(
                DashScopeMessage.builder().role("user").content(userContent).build());

        // 消息 3：助手工具调用，同上
        groundTruthMultiagent2.add(
                DashScopeMessage.builder()
                        .role("assistant")
                        .content(List.of(assistantEmptyContent))
                        .toolCalls(List.of(toolCall))
                        .build());

        // 消息 4：工具结果，同上
        groundTruthMultiagent2.add(
                DashScopeMessage.builder()
                        .role("tool")
                        .toolCallId("1")
                        .name("get_capital")
                        .content(toolResultParts)
                        .build());

        // 消息 5：更新后的用户历史，包含第二轮对话
        DashScopeContentPart updatedHistoryContent =
                DashScopeContentPart.text(
                        "<history>\n"
                                + "assistant: The capital of Japan is Tokyo.\n"
                                + "user: What is the capital of South Korea?\n"
                                + "</history>");
        groundTruthMultiagent2.add(
                DashScopeMessage.builder()
                        .role("user")
                        .content(List.of(updatedHistoryContent))
                        .build());

        // 消息 6：助手第二次工具调用
        DashScopeFunction function2 = new DashScopeFunction();
        function2.setName("get_capital");
        function2.setArguments("{\"country\": \"South Korea\"}");
        DashScopeToolCall toolCall2 = new DashScopeToolCall();
        toolCall2.setId("1");
        toolCall2.setType("function");
        toolCall2.setFunction(function2);

        groundTruthMultiagent2.add(
                DashScopeMessage.builder()
                        .role("assistant")
                        .content(List.of(assistantEmptyContent))
                        .toolCalls(List.of(toolCall2))
                        .build());

        // 消息 7：第二次工具结果，tool_call_id 为 "2"
        DashScopeContentPart toolContent2 =
                DashScopeContentPart.text("The capital of South Korea is Seoul.");
        DashScopeContentPart toolImageContent2 = DashScopeContentPart.image(absoluteImagePath);
        DashScopeContentPart toolAudioContent2 =
                DashScopeContentPart.audio("data:audio/wav;base64," + "ZmFrZSBhdWRpbyBjb250ZW50");
        groundTruthMultiagent2.add(
                DashScopeMessage.builder()
                        .role("tool")
                        .toolCallId("2")
                        .name("get_capital")
                        .content(List.of(toolContent2, toolImageContent2, toolAudioContent2))
                        .build());

        // 消息 8：最后的用户消息，历史中含上次响应
        DashScopeContentPart finalHistory =
                DashScopeContentPart.text(
                        "<history>\nassistant: The capital of South Korea is Seoul.\n</history>");
        groundTruthMultiagent2.add(
                DashScopeMessage.builder().role("user").content(List.of(finalHistory)).build());
    }

    @Test
    void testMultiAgentFormatter_FullHistory() {
        // system + conversation + tools + conversation2 + tools2
        List<Msg> allMessages = new ArrayList<>();
        allMessages.addAll(msgsSystem);
        allMessages.addAll(msgsConversation);
        allMessages.addAll(msgsTools);
        allMessages.addAll(msgsConversation2);
        allMessages.addAll(msgsTools2);

        List<DashScopeMessage> result = formatter.formatMultiModal(allMessages);

        assertMultiModalMessagesEqual(groundTruthMultiagent2, result);
    }

    @Test
    void testMultiAgentFormatter_WithoutSecondRound() {
        // system + conversation + tools + conversation2
        List<Msg> messages = new ArrayList<>();
        messages.addAll(msgsSystem);
        messages.addAll(msgsConversation);
        messages.addAll(msgsTools);
        messages.addAll(msgsConversation2);

        List<DashScopeMessage> result = formatter.formatMultiModal(messages);

        // 预期：groundTruthMultiagent2 去掉最后 3 条 tools2 消息
        List<DashScopeMessage> expected =
                groundTruthMultiagent2.subList(
                        0, groundTruthMultiagent2.size() - msgsTools2.size());

        assertMultiModalMessagesEqual(expected, result);
    }

    @Test
    void testMultiAgentFormatter_SystemConversationTools() {
        // system + conversation + tools
        List<Msg> messages = new ArrayList<>();
        messages.addAll(msgsSystem);
        messages.addAll(msgsConversation);
        messages.addAll(msgsTools);

        List<DashScopeMessage> result = formatter.formatMultiModal(messages);

        assertMultiModalMessagesEqual(groundTruthMultiagent, result);
    }

    @Test
    void testMultiAgentFormatter_WithoutSystemMessage() {
        // conversation + tools
        List<Msg> messages = new ArrayList<>();
        messages.addAll(msgsConversation);
        messages.addAll(msgsTools);

        List<DashScopeMessage> result = formatter.formatMultiModal(messages);

        // 预期：groundTruthMultiagent 去掉首条 system 消息
        List<DashScopeMessage> expected =
                groundTruthMultiagent.subList(1, groundTruthMultiagent.size());

        assertMultiModalMessagesEqual(expected, result);
    }

    @Test
    void testMultiAgentFormatter_WithoutConversation() {
        // system + tools
        List<Msg> messages = new ArrayList<>();
        messages.addAll(msgsSystem);
        messages.addAll(msgsTools);

        List<DashScopeMessage> result = formatter.formatMultiModal(messages);

        assertMultiModalMessagesEqual(groundTruthMultiagentWithoutFirstConversation, result);
    }

    @Test
    void testMultiAgentFormatter_SystemConversationOnly() {
        // system + conversation
        List<Msg> messages = new ArrayList<>();
        messages.addAll(msgsSystem);
        messages.addAll(msgsConversation);

        List<DashScopeMessage> result = formatter.formatMultiModal(messages);

        // 预期：groundTruthMultiagent 的前 2 条消息
        List<DashScopeMessage> expected = groundTruthMultiagent.subList(0, 2);

        assertMultiModalMessagesEqual(expected, result);
    }

    @Test
    void testMultiAgentFormatter_OnlySystemMessage() {
        List<DashScopeMessage> result = formatter.formatMultiModal(msgsSystem);

        // 预期：groundTruthMultiagent 的首条消息
        List<DashScopeMessage> expected = groundTruthMultiagent.subList(0, 1);

        assertMultiModalMessagesEqual(expected, result);
    }

    @Test
    void testMultiAgentFormatter_OnlyConversation() {
        List<DashScopeMessage> result = formatter.formatMultiModal(msgsConversation);

        // 预期：groundTruthMultiagent 的第二条消息，即无工具的对话历史
        List<DashScopeMessage> expected =
                groundTruthMultiagent.subList(1, 2); // 仅含历史的用户消息

        assertMultiModalMessagesEqual(expected, result);
    }

    @Test
    void testMultiAgentFormatter_OnlyTools() {
        List<DashScopeMessage> result = formatter.formatMultiModal(msgsTools);

        // 预期：groundTruthMultiagentWithoutFirstConversation 去掉首条 system 消息
        List<DashScopeMessage> expected =
                groundTruthMultiagentWithoutFirstConversation.subList(
                        1, groundTruthMultiagentWithoutFirstConversation.size());

        assertMultiModalMessagesEqual(expected, result);
    }

    /**
     * 深度比较两个 DashScopeMessage 列表。
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
