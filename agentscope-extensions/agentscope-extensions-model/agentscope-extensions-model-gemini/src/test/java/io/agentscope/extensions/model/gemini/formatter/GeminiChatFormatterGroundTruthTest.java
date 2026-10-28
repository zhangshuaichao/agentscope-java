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
import static io.agentscope.extensions.model.gemini.formatter.GeminiFormatterTestData.buildSystemMessage;
import static io.agentscope.extensions.model.gemini.formatter.GeminiFormatterTestData.buildToolMessages;
import static io.agentscope.extensions.model.gemini.formatter.GeminiFormatterTestData.getGroundTruthChatJson;
import static io.agentscope.extensions.model.gemini.formatter.GeminiFormatterTestData.parseGroundTruth;

import com.google.genai.types.Content;
import io.agentscope.core.message.Msg;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * GeminiChatFormatter 基准结果测试。
 * 验证格式化器输出与预期 Gemini API 格式一致。
 */
class GeminiChatFormatterGroundTruthTest extends GeminiFormatterTestBase {

    private static GeminiChatFormatter formatter;
    private static String imagePath;
    private static String audioPath;

    // 测试消息
    private static List<Msg> msgsSystem;
    private static List<Msg> msgsConversation;
    private static List<Msg> msgsTools;

    // 基准结果
    private static List<Map<String, Object>> groundTruthChat;

    @BeforeAll
    static void setUp() throws IOException {
        formatter = new GeminiChatFormatter();

        // 创建临时文件
        imagePath = "./image.png";
        File imageFile = new File(imagePath);
        Files.write(imageFile.toPath(), "fake image content".getBytes());

        audioPath = "./audio.mp3";
        File audioFile = new File(audioPath);
        Files.write(audioFile.toPath(), "fake audio content".getBytes());

        // 构建测试消息
        msgsSystem = buildSystemMessage();
        msgsConversation = buildConversationMessages(imagePath, audioPath);
        msgsTools = buildToolMessages(imagePath);

        // 解析基准结果
        groundTruthChat = parseGroundTruth(getGroundTruthChatJson());
    }

    @AfterAll
    static void tearDown() {
        // 清理临时文件
        new File(imagePath).deleteOnExit();
        new File(audioPath).deleteOnExit();
    }

    @Test
    void testChatFormatter_FullHistory() {
        // 合并所有消息：system + conversation + tools
        List<Msg> allMessages = new ArrayList<>();
        allMessages.addAll(msgsSystem);
        allMessages.addAll(msgsConversation);
        allMessages.addAll(msgsTools);

        List<Content> result = formatter.format(allMessages);

        assertContentsMatchGroundTruth(groundTruthChat, result);
    }

    @Test
    void testChatFormatter_WithoutSystemMessage() {
        // conversation + tools
        List<Msg> messages = new ArrayList<>();
        messages.addAll(msgsConversation);
        messages.addAll(msgsTools);

        List<Content> result = formatter.format(messages);

        // 基准结果去掉首条系统消息
        List<Map<String, Object>> expected = groundTruthChat.subList(1, groundTruthChat.size());

        assertContentsMatchGroundTruth(expected, result);
    }

    @Test
    void testChatFormatter_WithoutConversation() {
        // system + tools
        List<Msg> messages = new ArrayList<>();
        messages.addAll(msgsSystem);
        messages.addAll(msgsTools);

        List<Content> result = formatter.format(messages);

        // 基准结果：首条消息 + 最后 3 条工具消息
        List<Map<String, Object>> expected = new ArrayList<>();
        expected.add(groundTruthChat.get(0));
        expected.addAll(
                groundTruthChat.subList(
                        groundTruthChat.size() - msgsTools.size(), groundTruthChat.size()));

        assertContentsMatchGroundTruth(expected, result);
    }

    @Test
    void testChatFormatter_WithoutTools() {
        // system + conversation
        List<Msg> messages = new ArrayList<>();
        messages.addAll(msgsSystem);
        messages.addAll(msgsConversation);

        List<Content> result = formatter.format(messages);

        // 基准结果去掉最后 3 条工具消息
        List<Map<String, Object>> expected =
                groundTruthChat.subList(0, groundTruthChat.size() - msgsTools.size());

        assertContentsMatchGroundTruth(expected, result);
    }

    @Test
    void testChatFormatter_EmptyMessages() {
        List<Content> result = formatter.format(List.of());

        assertContentsMatchGroundTruth(List.of(), result);
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
        Map<String, Object> map = new LinkedHashMap<>();

        // 添加角色
        if (content.role().isPresent()) {
            map.put("role", content.role().get());
        }

        // 添加内容部分
        if (content.parts().isPresent()) {
            List<Map<String, Object>> partsList = new ArrayList<>();
            for (var part : content.parts().get()) {
                Map<String, Object> partMap = new LinkedHashMap<>();

                // 文本部分
                if (part.text().isPresent()) {
                    partMap.put("text", part.text().get());
                }

                // 内联数据，图像/音频
                if (part.inlineData().isPresent()) {
                    var inlineData = part.inlineData().get();
                    Map<String, Object> inlineDataMap = new LinkedHashMap<>();

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
                    Map<String, Object> functionCallMap = new LinkedHashMap<>();

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
                    Map<String, Object> functionResponseMap = new LinkedHashMap<>();

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
