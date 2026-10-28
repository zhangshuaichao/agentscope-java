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
package io.agentscope.core.rag.e2e;

import io.agentscope.core.message.Msg;
import java.util.List;

/**
 * 使用内容检查验证端到端测试响应的工具类。
 *
 * <p>通过关键词匹配、语义相关线索和结构验证，检查可能具有非确定性的大语言模型输出。
 */
public class ContentValidator {

    private ContentValidator() {
        // 工具类
    }

    /**
     * 验证响应包含有意义的内容。
     *
     * @param response 待验证的响应消息
     * @return 内容长度大于 5 个字符时为 true
     */
    public static boolean hasMeaningfulContent(Msg response) {
        String text = TestUtils.extractTextContent(response);
        return text != null && text.trim().length() > 5;
    }

    /**
     * 验证列表中任一响应包含有意义的内容。
     *
     * @param responses 响应消息列表
     * @return 至少一个响应有有效内容时为 true
     */
    public static boolean hasMeaningfulContent(List<Msg> responses) {
        if (responses == null || responses.isEmpty()) {
            return false;
        }

        return responses.stream().anyMatch(ContentValidator::hasMeaningfulContent);
    }

    /**
     * 检查响应是否包含预期关键词，忽略大小写。
     *
     * @param response 响应消息
     * @param expectedKeywords 待查找关键词
     * @return 找到任意关键词时为 true
     */
    public static boolean containsKeywords(Msg response, String... expectedKeywords) {
        String text = TestUtils.extractTextContent(response);
        if (text == null) return false;

        String lowerText = text.toLowerCase();
        for (String keyword : expectedKeywords) {
            if (lowerText.contains(keyword.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 检查列表中任一响应是否包含预期关键词。
     *
     * @param responses 响应消息列表
     * @param expectedKeywords 待查找关键词
     * @return 任一响应包含任意关键词时为 true
     */
    public static boolean containsKeywords(List<Msg> responses, String... expectedKeywords) {
        if (responses == null || responses.isEmpty()) {
            return false;
        }

        return responses.stream()
                .anyMatch(response -> containsKeywords(response, expectedKeywords));
    }

    /**
     * 验证数学计算结果。
     *
     * @param response 包含计算结果的响应
     * @param expectedResult 预期数值结果
     * @return 响应包含预期结果时为 true
     */
    public static boolean containsCalculationResult(Msg response, String expectedResult) {
        return containsKeywords(response, expectedResult);
    }

    /**
     * 验证任一响应中的数学计算结果。
     *
     * @param responses 包含计算结果的响应列表
     * @param expectedResult 预期数值结果
     * @return 任一响应包含预期结果时为 true
     */
    public static boolean containsCalculationResult(List<Msg> responses, String expectedResult) {
        return containsKeywords(responses, expectedResult);
    }

    /**
     * 验证图像分析响应提及预期视觉元素。
     *
     * @param response 图像分析响应
     * @param expectedElements 预期视觉元素，如 "dog"、"cat"、"car"
     * @return 响应提及任意预期元素时为 true
     */
    public static boolean mentionsVisualElements(Msg response, String... expectedElements) {
        return containsKeywords(response, expectedElements);
    }

    /**
     * 验证音频分析响应提及预期音频元素。
     *
     * @param response 音频分析响应
     * @param expectedElements 预期音频元素，如 "music"、"speech"、"sound"
     * @return 响应提及任意预期元素时为 true
     */
    public static boolean mentionsAudioElements(Msg response, String... expectedElements) {
        return containsKeywords(response, expectedElements);
    }

    /**
     * 验证错误处理响应包含错误指示。
     *
     * @param response 错误处理响应
     * @return 响应表明进行了错误处理时为 true
     */
    public static boolean indicatesErrorHandling(Msg response) {
        return containsKeywords(response, "error", "invalid", "cannot", "unable", "sorry");
    }

    /**
     * 验证对话上下文保留。
     *
     * @param currentResponse 当前响应
     * @param previousContext 先前对话的元素
     * @return 响应引用先前上下文时为 true
     */
    public static boolean maintainsContext(Msg currentResponse, String... previousContext) {
        return containsKeywords(currentResponse, previousContext);
    }

    /**
     * 验证多模态工具响应提及文件信息。
     *
     * @param response 工具响应
     * @return 响应提及文件路径、URL 或媒体信息时为 true
     */
    public static boolean mentionsFileInfo(Msg response) {
        return containsKeywords(
                response,
                "file",
                "url",
                "http",
                "image",
                "audio",
                "video",
                "path",
                ".jpg",
                ".png",
                ".wav",
                ".mp3",
                "found",
                "generated");
    }

    /**
     * 验证思考模式响应包含思考指示。
     *
     * @param response 思考模式响应
     * @return 响应显示思考过程时为 true
     */
    public static boolean showsThinkingProcess(Msg response) {
        return containsKeywords(
                response,
                "think",
                "consider",
                "analyze",
                "reason",
                "let me",
                "first",
                "then",
                "step");
    }

    /**
     * 验证响应长度达到最低要求。
     *
     * @param response 待检查响应
     * @param minLength 预期最小长度
     * @return 满足长度要求时为 true
     */
    public static boolean meetsMinimumLength(Msg response, int minLength) {
        String text = TestUtils.extractTextContent(response);
        return text != null && text.length() >= minLength;
    }

    /**
     * 验证响应含列表、步骤等结构化信息。
     *
     * @param response 待检查响应
     * @return 响应呈现结构化内容时为 true
     */
    public static boolean hasStructuredContent(Msg response) {
        String text = TestUtils.extractTextContent(response);
        if (text == null) return false;

        // 查找结构化内容的标记
        return text.matches(".*[0-9]+.*") // 编号列表
                || text.matches(".*[-*•].*") // 项目符号
                || text.matches(".*[Ff]irst.*") // 顺序表达
                || text.matches(".*[Nn]ext.*")
                || text.matches(".*[Ff]inally.*");
    }
}
