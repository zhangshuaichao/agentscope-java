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

import static io.agentscope.extensions.model.gemini.formatter.GeminiFormatterTestData.buildConversationMessages;
import static io.agentscope.extensions.model.gemini.formatter.GeminiFormatterTestData.buildConversationMessages2;
import static io.agentscope.extensions.model.gemini.formatter.GeminiFormatterTestData.buildSystemMessage;
import static io.agentscope.extensions.model.gemini.formatter.GeminiFormatterTestData.buildToolMessages;
import static io.agentscope.extensions.model.gemini.formatter.GeminiFormatterTestData.buildToolMessages2;
import static io.agentscope.extensions.model.gemini.formatter.GeminiFormatterTestData.getGroundTruthMultiAgent2Json;
import static io.agentscope.extensions.model.gemini.formatter.GeminiFormatterTestData.getGroundTruthMultiAgentJson;
import static io.agentscope.extensions.model.gemini.formatter.GeminiFormatterTestData.parseGroundTruth;

import com.google.genai.types.Content;
import io.agentscope.core.message.Msg;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * GeminiMultiAgentFormatter 基准结果测试。
 * 验证多智能体格式化器输出与预期 Gemini API 格式一致。
 */
class GeminiMultiAgentFormatterGroundTruthTest extends GeminiFormatterTestBase {

    private static GeminiMultiAgentFormatter formatter;
    private static String imagePath;
    private static String audioPath;
    private static Path imageTempPath;
    private static Path audioTempPath;

    // 测试消息
    private static List<Msg> msgsSystem;
    private static List<Msg> msgsConversation;
    private static List<Msg> msgsTools;
    private static List<Msg> msgsConversation2;
    private static List<Msg> msgsTools2;

    // 基准结果
    private static List<Map<String, Object>> groundTruthMultiAgent;
    private static List<Map<String, Object>> groundTruthMultiAgent2;
    private static List<Map<String, Object>> groundTruthMultiAgentWithoutFirstConversation;

    @BeforeAll
    static void setUp() throws IOException {
        formatter = new GeminiMultiAgentFormatter();

        // 创建临时文件
        imageTempPath = Files.createTempFile("gemini_test_image", ".png");
        imagePath = imageTempPath.toAbsolutePath().toString();
        Files.write(imageTempPath, "fake image content".getBytes());

        audioTempPath = Files.createTempFile("gemini_test_audio", ".mp3");
        audioPath = audioTempPath.toAbsolutePath().toString();
        Files.write(audioTempPath, "fake audio content".getBytes());

        // 构建测试消息
        msgsSystem = buildSystemMessage();
        msgsConversation = buildConversationMessages(imagePath, audioPath);
        msgsTools = buildToolMessages(imagePath);
        msgsConversation2 = buildConversationMessages2();
        msgsTools2 = buildToolMessages2(imagePath);

        // 解析基准结果
        groundTruthMultiAgent = parseGroundTruth(getGroundTruthMultiAgentJson());
        groundTruthMultiAgent2 = parseGroundTruth(getGroundTruthMultiAgent2Json());

        // 构建无首轮对话场景的基准结果。
        // 格式：system + tools，不包装对话历史
        groundTruthMultiAgentWithoutFirstConversation = buildWithoutFirstConversationGroundTruth();
    }

    @AfterAll
    static void tearDown() {
        // 清理临时文件
        try {
            if (imageTempPath != null) {
                Files.deleteIfExists(imageTempPath);
            }
            if (audioTempPath != null) {
                Files.deleteIfExists(audioTempPath);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Test
    void testMultiAgentFormatter_TwoRoundsFullHistory() {
        // system + conversation + tools + conversation2 + tools2
        List<Msg> messages = new ArrayList<>();
        messages.addAll(msgsSystem);
        messages.addAll(msgsConversation);
        messages.addAll(msgsTools);
        messages.addAll(msgsConversation2);
        messages.addAll(msgsTools2);

        List<Content> result = formatter.format(messages);

        assertContentsMatchGroundTruth(groundTruthMultiAgent2, result);
    }

    @Test
    void testMultiAgentFormatter_TwoRoundsWithoutSecondTools() {
        // system + conversation + tools + conversation2
        List<Msg> messages = new ArrayList<>();
        messages.addAll(msgsSystem);
        messages.addAll(msgsConversation);
        messages.addAll(msgsTools);
        messages.addAll(msgsConversation2);

        List<Content> result = formatter.format(messages);

        // 基准结果去掉最后的 tools2
        List<Map<String, Object>> expected =
                groundTruthMultiAgent2.subList(
                        0, groundTruthMultiAgent2.size() - msgsTools2.size());

        assertContentsMatchGroundTruth(expected, result);
    }

    @Test
    void testMultiAgentFormatter_SingleRoundFullHistory() {
        // system + conversation + tools
        List<Msg> messages = new ArrayList<>();
        messages.addAll(msgsSystem);
        messages.addAll(msgsConversation);
        messages.addAll(msgsTools);

        List<Content> result = formatter.format(messages);

        assertContentsMatchGroundTruth(groundTruthMultiAgent, result);
    }

    @Test
    void testMultiAgentFormatter_WithoutSystemMessage() {
        // conversation + tools
        List<Msg> messages = new ArrayList<>();
        messages.addAll(msgsConversation);
        messages.addAll(msgsTools);

        List<Content> result = formatter.format(messages);

        // 基准结果去掉首条系统消息
        List<Map<String, Object>> expected =
                groundTruthMultiAgent.subList(1, groundTruthMultiAgent.size());

        assertContentsMatchGroundTruth(expected, result);
    }

    @Test
    void testMultiAgentFormatter_WithoutFirstConversation() {
        // system + tools
        List<Msg> messages = new ArrayList<>();
        messages.addAll(msgsSystem);
        messages.addAll(msgsTools);

        List<Content> result = formatter.format(messages);

        assertContentsMatchGroundTruth(groundTruthMultiAgentWithoutFirstConversation, result);
    }

    @Test
    void testMultiAgentFormatter_OnlySystemMessage() {
        List<Content> result = formatter.format(msgsSystem);

        // 基准结果：仅首条消息
        List<Map<String, Object>> expected = groundTruthMultiAgent.subList(0, 1);

        assertContentsMatchGroundTruth(expected, result);
    }

    @Test
    void testMultiAgentFormatter_OnlyConversation() {
        List<Content> result = formatter.format(msgsConversation);

        // 基准结果：第二条消息，即合并后的对话历史
        List<Map<String, Object>> expected =
                groundTruthMultiAgent.subList(1, groundTruthMultiAgent.size() - msgsTools.size());

        assertContentsMatchGroundTruth(expected, result);
    }

    @Test
    void testMultiAgentFormatter_OnlyTools() {
        List<Content> result = formatter.format(msgsTools);

        // 基准结果：最后 3 条工具消息
        // 对应 ground_truth_multiagent_without_first_conversation[1:]
        List<Map<String, Object>> expected =
                groundTruthMultiAgentWithoutFirstConversation.subList(
                        1, groundTruthMultiAgentWithoutFirstConversation.size());

        assertContentsMatchGroundTruth(expected, result);
    }

    @Test
    void testMultiAgentFormatter_EmptyMessages() {
        List<Content> result = formatter.format(List.of());

        assertContentsMatchGroundTruth(List.of(), result);
    }

    /**
     * 构建无首轮对话场景的基准结果。
     *
     * @return 基准结果数据
     */
    private static List<Map<String, Object>> buildWithoutFirstConversationGroundTruth() {
        // 解析基础基准结果
        String groundTruthJson =
                """
                [
                    {
                        "role": "user",
                        "parts": [
                            {
                                "text": "You're a helpful assistant."
                            }
                        ]
                    },
                    {
                        "role": "model",
                        "parts": [
                            {
                                "function_call": {
                                    "id": "1",
                                    "name": "get_capital",
                                    "args": {
                                        "country": "Japan"
                                    }
                                }
                            }
                        ]
                    },
                    {
                        "role": "user",
                        "parts": [
                            {
                                "function_response": {
                                    "id": "1",
                                    "name": "get_capital",
                                    "response": {
                                        "output": "- The capital of Japan is Tokyo.\\n- The returned image can be found at: ./image.png\\n- The returned audio can be found at: /var/folders/gf/krg8x_ws409cpw_46b2s6rjc0000gn/T/tmpfymnv2w9.wav"
                                    }
                                }
                            }
                        ]
                    },
                    {
                        "role": "user",
                        "parts": [
                            {
                                "text": "# Conversation History\\nThe content between <history></history> tags contains your conversation history\\n<history>assistant: The capital of Japan is Tokyo.\\n</history>"
                            }
                        ]
                    }
                ]
                """;

        return parseGroundTruth(groundTruthJson);
    }

    /**
     * 将 Content 对象列表转为 JSON，并与基准结果比较。
     *
     * @param expectedGroundTruth 以映射列表表示的预期基准结果
     * @param actualContents 格式化器产生的实际 Content 对象
     */
    private void assertContentsMatchGroundTruth(
            List<Map<String, Object>> expectedGroundTruth, List<Content> actualContents) {
        String expectedJson = toJson(expectedGroundTruth);
        String actualJson = toJson(contentsToMaps(actualContents));

        // 比较前规范化临时文件路径
        String normalizedExpected = normalizeTempFilePaths(expectedJson);
        String normalizedActual = normalizeTempFilePaths(actualJson);

        assertJsonEquals(normalizedExpected, normalizedActual);
    }

    /**
     * 规范化 JSON 中的临时文件路径，以便比较。
     * 将实际临时文件路径替换为占位符。
     *
     * @param json JSON 字符串
     * @return 规范化后的 JSON
     */
    private String normalizeTempFilePaths(String json) {
        // 将任意临时文件路径，例如 /var/folders/.../tmpXXX.wav 或
        // .../agentscope_XXX.wav，
        // 替换为占位符，以便比较
        return json.replaceAll(
                "(The returned (audio|image|video) can be found at: )[^\"]+", "$1<TEMP_FILE>");
    }

    /**
     * 将 Content 对象列表转为 Map 列表，以便进行 JSON 比较。
     *
     * @param contents Content 对象
     * @return 表示内容的映射列表
     */
    private List<Map<String, Object>> contentsToMaps(List<Content> contents) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Content content : contents) {
            result.add(contentToMap(content));
        }
        return result;
    }

    /**
     * 将 Content 对象转为 Map，以便进行 JSON 比较。
     *
     * @param content Content 对象
     * @return 映射表示
     */
    private Map<String, Object> contentToMap(Content content) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();

        // 添加角色
        if (content.role().isPresent()) {
            map.put("role", content.role().get());
        }

        // 添加内容部分
        if (content.parts().isPresent()) {
            List<Map<String, Object>> partsList = new ArrayList<>();
            for (var part : content.parts().get()) {
                Map<String, Object> partMap = new java.util.LinkedHashMap<>();

                // 文本部分
                if (part.text().isPresent()) {
                    partMap.put("text", part.text().get());
                }

                // 内联数据，图像/音频
                if (part.inlineData().isPresent()) {
                    var inlineData = part.inlineData().get();
                    Map<String, Object> inlineDataMap = new java.util.LinkedHashMap<>();

                    if (inlineData.data().isPresent()) {
                        inlineDataMap.put("data", inlineData.data().get());
                    }
                    if (inlineData.mimeType().isPresent()) {
                        inlineDataMap.put("mime_type", inlineData.mimeType().get());
                    }

                    partMap.put("inline_data", inlineDataMap);
                }

                // 函数调用
                if (part.functionCall().isPresent()) {
                    var functionCall = part.functionCall().get();
                    Map<String, Object> functionCallMap = new java.util.LinkedHashMap<>();

                    if (functionCall.id().isPresent()) {
                        functionCallMap.put("id", functionCall.id().get());
                    }
                    if (functionCall.name().isPresent()) {
                        functionCallMap.put("name", functionCall.name().get());
                    }
                    if (functionCall.args().isPresent()) {
                        functionCallMap.put("args", functionCall.args().get());
                    }

                    partMap.put("function_call", functionCallMap);
                }

                // 函数响应
                if (part.functionResponse().isPresent()) {
                    var functionResponse = part.functionResponse().get();
                    Map<String, Object> functionResponseMap = new java.util.LinkedHashMap<>();

                    if (functionResponse.id().isPresent()) {
                        functionResponseMap.put("id", functionResponse.id().get());
                    }
                    if (functionResponse.name().isPresent()) {
                        functionResponseMap.put("name", functionResponse.name().get());
                    }
                    if (functionResponse.response().isPresent()) {
                        functionResponseMap.put("response", functionResponse.response().get());
                    }

                    partMap.put("function_response", functionResponseMap);
                }

                partsList.add(partMap);
            }
            map.put("parts", partsList);
        }

        return map;
    }
}
