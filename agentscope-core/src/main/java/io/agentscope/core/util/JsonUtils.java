/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.agentscope.core.util;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import io.agentscope.core.message.ToolUseBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 访问全局 {@link JsonCodec} 实例的工具类。
 *
 * <p>为框架提供集中的 JSON 序列化／反序列化访问入口。默认使用 {@link JacksonJsonCodec}，用户可替换为自定义实现。
 *
 * <p>用法：
 * <pre>{@code
 * // Basic usage
 * JsonUtils.getJsonCodec().toJson(obj);
 * JsonUtils.getJsonCodec().fromJson(json, MyClass.class);
 *
 * // Cache reference for frequent calls
 * JsonCodec codec = JsonUtils.getJsonCodec();
 * codec.toJson(obj1);
 * codec.toJson(obj2);
 *
 * // Replace with custom implementation
 * JsonUtils.setJsonCodec(new MyCustomJsonCodec());
 * }</pre>
 *
 * @see JsonCodec
 * @see JacksonJsonCodec
 */
public final class JsonUtils {

    private static final Logger log = LoggerFactory.getLogger(JsonUtils.class);
    private static final JsonFactory JSON_FACTORY = new JsonFactory();

    private static volatile JsonCodec codec = new JacksonJsonCodec();

    private JsonUtils() {
        // 工具类，不允许实例化
    }

    /**
     * 获取全局 JsonCodec 实例。
     *
     * @return JsonCodec 实例
     */
    public static JsonCodec getJsonCodec() {
        return codec;
    }

    /**
     * 设置自定义 JsonCodec 实现。
     *
     * <p>允许用自有实现（例如 Gson、Fastjson）替换默认的 Jackson 实现。
     *
     * @param jsonCodec 自定义 JsonCodec 实现
     * @throws IllegalArgumentException jsonCodec 为 null 时抛出
     */
    public static void setJsonCodec(JsonCodec jsonCodec) {
        if (jsonCodec == null) {
            throw new IllegalArgumentException("JsonCodec cannot be null");
        }
        codec = jsonCodec;
    }

    /**
     * 重置为默认 JacksonJsonCodec 实现。
     *
     * <p>适用于测试或恢复默认行为。
     */
    public static void resetToDefault() {
        codec = new JacksonJsonCodec();
    }

    /**
     * 检查整个字符串是否恰好包含一个完整 JSON 对象，允许尾随空白，但不允许额外值或格式错误的后缀。
     *
     * <p>工具调用 {@code arguments} 必须是 JSON 对象，因此拒绝 {@code null}、数组、字符串等其他 JSON 值。
     *
     * @param str 要校验的字符串
     * @return {@code str} 是非 null、可解析的 JSON 对象时为 {@code true}
     */
    public static boolean isValidJsonObject(String str) {
        if (str == null || str.isEmpty()) {
            return false;
        }
        try (JsonParser parser = JSON_FACTORY.createParser(str)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                return false;
            }
            // 【学习提示】检查首 token 为对象还不够：先完整消费对象，再确认没有下一个 token。
            // 这样既拒绝截断 JSON，也拒绝两个对象拼接或合法对象后附带其他数据。
            parser.skipChildren();
            return parser.nextToken() == null;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 从 {@link ToolUseBlock} 解析参数 JSON 字符串，确保结果始终为合法 JSON 对象。
     *
     * <p>解析顺序：
     * <ol>
     * <li>{@link ToolUseBlock#getContent()} 是合法 JSON 对象时使用它</li>
     * <li>通过 {@link JsonCodec#toJson} 序列化 {@link ToolUseBlock#getInput()}</li>
     * <li>回退到 {@code "{}"}</li>
     * </ol>
     *
     * <p>避免将格式错误的 JSON（例如流式中断留下的片段）作为工具调用参数发送，导致模型 API 拒绝请求。
     *
     * @param toolUse 工具调用块
     * @return 表示工具调用参数的合法 JSON 对象字符串
     */
    public static String resolveToolCallArgsJson(ToolUseBlock toolUse) {
        String content = toolUse.getContent();
        if (content != null && !content.isEmpty()) {
            if (isValidJsonObject(content)) {
                return content;
            }
            log.warn(
                    "Invalid JSON in tool call content for '{}', falling back to input"
                            + " serialization",
                    toolUse.getName());
        }

        try {
            return codec.toJson(toolUse.getInput());
        } catch (Exception e) {
            log.warn("Failed to serialize tool call arguments: {}", e.getMessage());
            return "{}";
        }
    }
}
