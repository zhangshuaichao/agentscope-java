/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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

package io.agentscope.extensions.model.ollama.options;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.io.IOException;
import java.util.List;

/**
 * 定义 AgentScope Ollama 集成中“思考”能力（思维链）的配置契约。
 * 此接口允许用户控制底层模型如何呈现内部推理过程。
 * <p>配置支持两种不同策略：
 * <ul>
 *   <li>{@link ThinkBoolean}：启用或禁用思考的简单开关（适合 DeepSeek R1 等模型）。</li>
 *   <li>{@link ThinkLevel}：更细粒度地控制推理深度
 *       （例如 GPT-OSS 等模型的 "low"、"medium"、"high"）。</li>
 * </ul>
 */
@JsonSerialize(using = ThinkOption.ThinkOptionSerializer.class)
@JsonDeserialize(using = ThinkOption.ThinkOptionDeserializer.class)
public sealed interface ThinkOption {

    /**
     * 将当前选项转为兼容 Ollama JSON API 的格式。
     *
     * @return 开关式选项返回 {@link Boolean}，级别式选项返回 {@link String}
     */
    Object toJsonValue();

    /**
     * {@link ThinkOption} 的自定义序列化器，负责将选项对象转换为适当的
     * JSON 原始值（布尔值或字符串）。
     */
    class ThinkOptionSerializer extends JsonSerializer<ThinkOption> {

        @Override
        public void serialize(ThinkOption value, JsonGenerator gen, SerializerProvider serializers)
                throws IOException {
            if (value == null) {
                gen.writeNull();
            } else {
                gen.writeObject(value.toJsonValue());
            }
        }
    }

    /**
     * {@link ThinkOption} 的自定义反序列化器，检测 JSON token 类型
     * （布尔值或字符串），并实例化对应的 {@link ThinkOption} 实现。
     */
    class ThinkOptionDeserializer extends JsonDeserializer<ThinkOption> {

        @Override
        public ThinkOption deserialize(JsonParser p, DeserializationContext ctxt)
                throws IOException {
            JsonToken token = p.currentToken();
            if (token == JsonToken.VALUE_TRUE) {
                return ThinkBoolean.ENABLED;
            } else if (token == JsonToken.VALUE_FALSE) {
                return ThinkBoolean.DISABLED;
            } else if (token == JsonToken.VALUE_STRING) {
                return new ThinkLevel(p.getValueAsString());
            } else if (token == JsonToken.VALUE_NULL) {
                return null;
            }
            throw new IOException(
                    "Unable to deserialize ThinkOption. Encountered unexpected token type: "
                            + token);
        }
    }

    /**
     * 表示二元状态（启用／禁用）的 {@link ThinkOption} 实现。
     * 适用于不支持细粒度思考级别的模型。
     *
     * @param enabled 思考能力的状态
     */
    record ThinkBoolean(boolean enabled) implements ThinkOption {

        /**
         * 表示启用思考状态的常量。
         */
        public static final ThinkBoolean ENABLED = new ThinkBoolean(true);

        /**
         * 表示禁用思考状态的常量。
         */
        public static final ThinkBoolean DISABLED = new ThinkBoolean(false);

        @Override
        public Object toJsonValue() {
            return this.enabled;
        }
    }

    /**
     * 表示指定推理投入级别的 {@link ThinkOption} 实现，
     * 面向允许调节思维链“投入”或“深度”的模型。
     *
     * @param level 期望的思考级别（必须为 "low"、"medium"、"high" 之一）
     */
    record ThinkLevel(String level) implements ThinkOption {

        private static final List<String> VALID_LEVELS = List.of("low", "medium", "high");

        /**
         * 低投入思考的预定义常量。
         */
        public static final ThinkLevel LOW = new ThinkLevel("low");

        /**
         * 中投入思考的预定义常量。
         */
        public static final ThinkLevel MEDIUM = new ThinkLevel("medium");

        /**
         * 高投入思考的预定义常量。
         */
        public static final ThinkLevel HIGH = new ThinkLevel("high");

        /**
         * 构造新的 ThinkLevel，并执行验证。
         *
         * @param level 思考级别字符串
         * @throws IllegalArgumentException 提供的级别不在有效集合内时抛出
         */
        public ThinkLevel {
            if (level != null && !VALID_LEVELS.contains(level)) {
                throw new IllegalArgumentException(
                        "Invalid thinking level provided: '"
                                + level
                                + "'. Supported levels are: "
                                + VALID_LEVELS);
            }
        }

        @Override
        public Object toJsonValue() {
            return this.level;
        }
    }
}
