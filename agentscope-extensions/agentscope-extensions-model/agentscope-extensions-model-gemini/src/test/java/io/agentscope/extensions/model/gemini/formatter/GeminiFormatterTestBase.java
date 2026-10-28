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

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.agentscope.core.util.JsonCodec;
import io.agentscope.core.util.JsonUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/**
 * Gemini 格式化器测试基类。
 * 提供公共测试工具与临时文件管理。
 */
public abstract class GeminiFormatterTestBase {

    protected static final JsonCodec jsonCodec = JsonUtils.getJsonCodec();

    protected Path tempImageFile;
    protected Path tempAudioFile;

    @BeforeEach
    void setUpBase() throws IOException {
        // 创建临时测试文件
        tempImageFile = Files.createTempFile("test_image", ".png");
        Files.write(tempImageFile, "fake image content".getBytes());

        tempAudioFile = Files.createTempFile("test_audio", ".mp3");
        Files.write(tempAudioFile, "fake audio content".getBytes());
    }

    @AfterEach
    void tearDownBase() throws IOException {
        // 清理临时文件
        if (tempImageFile != null && Files.exists(tempImageFile)) {
            Files.deleteIfExists(tempImageFile);
        }
        if (tempAudioFile != null && Files.exists(tempAudioFile)) {
            Files.deleteIfExists(tempAudioFile);
        }
    }

    /**
     * 将对象转为格式化 JSON 字符串。
     *
     * @param obj 待转换对象
     * @return JSON 字符串
     */
    protected String toJson(Object obj) {
        try {
            return jsonCodec.toPrettyJson(obj);
        } catch (Exception e) {
            throw new RuntimeException("Failed to convert object to JSON", e);
        }
    }

    /**
     * 将 JSON 字符串解析为对象。
     *
     * @param json JSON 字符串
     * @param clazz 目标类
     * @param <T> 类型参数
     * @return 解析后的对象
     */
    protected <T> T fromJson(String json, Class<T> clazz) {
        try {
            return jsonCodec.fromJson(json, clazz);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse JSON", e);
        }
    }

    /**
     * 规范化并比较结构，深度比较两个 JSON 字符串。
     * 本方法先解析两段 JSON，再比较结果对象。
     *
     * @param expectedJson 预期 JSON 字符串
     * @param actualJson 实际 JSON 字符串
     */
    protected void assertJsonEquals(String expectedJson, String actualJson) {
        try {
            Object expected = jsonCodec.fromJson(expectedJson, Object.class);
            Object actual = jsonCodec.fromJson(actualJson, Object.class);

            // 重新序列化为规范化 JSON 进行比较
            String normalizedExpected = jsonCodec.toJson(expected);
            String normalizedActual = jsonCodec.toJson(actual);

            assertEquals(
                    normalizedExpected,
                    normalizedActual,
                    "JSON structures do not match.\nExpected:\n"
                            + jsonCodec.toPrettyJson(expected)
                            + "\n\nActual:\n"
                            + jsonCodec.toPrettyJson(actual));
        } catch (Exception e) {
            throw new AssertionError("Failed to compare JSON: " + e.getMessage(), e);
        }
    }

    /**
     * 将两个对象转为 JSON 后比较结构，进行深度比较。
     *
     * @param expected 预期对象
     * @param actual 实际对象
     */
    protected void assertObjectEquals(Object expected, Object actual) {
        String expectedJson = toJson(expected);
        String actualJson = toJson(actual);
        assertJsonEquals(expectedJson, actualJson);
    }
}
