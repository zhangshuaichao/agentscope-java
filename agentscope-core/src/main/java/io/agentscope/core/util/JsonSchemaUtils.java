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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.victools.jsonschema.generator.Option;
import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfig;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaVersion;
import com.github.victools.jsonschema.module.jackson.JacksonModule;
import com.github.victools.jsonschema.module.jackson.JacksonOption;
import io.agentscope.core.tool.ToolSchemaModule;
import java.lang.reflect.Type;
import java.util.Map;

/**
 * JSON Schema 操作工具类。
 *
 * <p>提供以下辅助方法：
 * <ul>
 * <li>从 Java 类生成 JSON schema（用于结构化输出）</li>
 * <li>Map 与类型化对象之间转换</li>
 * <li>将 Java 类型映射到 JSON Schema 类型</li>
 * </ul>
 *
 * <p>支持 AgentScope 注解：
 * <ul>
 * <li>{@code @ToolParam(description = ...)}：添加属性描述</li>
 * <li>{@code @ToolParam(required = ...)}：标记必填属性</li>
 * </ul>
 *
 * <p>支持 Jackson 注解：
 * <ul>
 * <li>{@code @JsonProperty(required = ...)}：标记必填属性</li>
 * <li>{@code @JsonPropertyDescription(...)}：添加属性描述</li>
 * <li>{@code @JsonClassDescription(...)}：添加类描述</li>
 * </ul>
 *
 * <p>所有公共方法均线程安全。共享 victools {@code SchemaGenerator} 本身不支持并发使用，
 * 因此通过内部锁将 schema 生成串行化。</p>
 *
 * @hidden
 */
public class JsonSchemaUtils {

    private static final boolean PROPERTY_REQUIRED_BY_DEFAULT = false;

    private static final SchemaGenerator schemaGenerator;

    /**
     * 保护非线程安全的共享 victools {@link SchemaGenerator}：
     * 其 JacksonModule 持有未同步的内省缓存，因此必须串行化并发 schema 生成。
     */
    private static final Object SCHEMA_LOCK = new Object();

    static {
        // 使用 JacksonModule 支持 @JsonProperty、@JsonPropertyDescription 注解
        JacksonModule jacksonModule =
                new JacksonModule(JacksonOption.RESPECT_JSONPROPERTY_REQUIRED);

        ToolSchemaModule toolSchemaModule =
                PROPERTY_REQUIRED_BY_DEFAULT
                        ? new ToolSchemaModule()
                        : new ToolSchemaModule(
                                ToolSchemaModule.Option.PROPERTY_REQUIRED_FALSE_BY_DEFAULT);

        SchemaGeneratorConfigBuilder configBuilder =
                new SchemaGeneratorConfigBuilder(
                                SchemaVersion.DRAFT_2020_12, OptionPreset.PLAIN_JSON)
                        .with(jacksonModule)
                        .with(toolSchemaModule)
                        .with(Option.PLAIN_DEFINITION_KEYS)
                        .without(Option.SCHEMA_VERSION_INDICATOR);
        SchemaGeneratorConfig config = configBuilder.build();
        schemaGenerator = new SchemaGenerator(config);
    }

    /**
     * 从 Java 类生成 JSON Schema。
     * 适用于需要将复杂嵌套对象转换为 JSON Schema 的结构化输出场景。
     *
     * @param clazz 要生成 schema 的类
     * @return Map 形式的 JSON Schema
     * @throws RuntimeException 反射错误、配置问题或其他处理错误导致 schema 生成失败时抛出
     */
    public static Map<String, Object> generateSchemaFromClass(Class<?> clazz) {
        try {
            JsonNode schemaNode;
            synchronized (SCHEMA_LOCK) {
                schemaNode = schemaGenerator.generateSchema(clazz);
            }
            return JsonUtils.getJsonCodec()
                    .convertValue(schemaNode, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate JSON schema for " + clazz.getName(), e);
        }
    }

    /**
     * 从 com.fasterxml.jackson.databind.JsonNode 实例生成 JSON Schema。
     * 适用于需要将复杂嵌套对象转换为 JSON Schema 的结构化输出场景。
     *
     * @param schema 要生成 schema 的 com.fasterxml.jackson.databind.JsonNode 实例
     * @return Map 形式的 JSON Schema
     * @throws RuntimeException 反射错误、配置问题或其他处理错误导致 schema 生成失败时抛出
     */
    public static Map<String, Object> generateSchemaFromJsonNode(JsonNode schema) {
        try {
            return JsonUtils.getJsonCodec()
                    .convertValue(schema, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate JSON schema for schema", e);
        }
    }

    /**
     * 从 Java Type 生成 JSON Schema（支持泛型）。
     *
     * @param type 要生成 schema 的类型
     * @return Map 形式的 JSON Schema
     */
    public static Map<String, Object> generateSchemaFromType(Type type) {
        try {
            JsonNode schemaNode;
            synchronized (SCHEMA_LOCK) {
                schemaNode = schemaGenerator.generateSchema(type);
            }
            return JsonUtils.getJsonCodec()
                    .convertValue(schemaNode, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to generate JSON schema for " + type.getTypeName(), e);
        }
    }

    /**
     * 将 Map 转换为类型化对象。
     *
     * @param data 数据映射
     * @param targetClass 目标类
     * @param <T> 类型
     * @return 转换后的对象
     * @throws IllegalStateException 输入数据为 null 时抛出
     * @throws RuntimeException 类型不匹配、JSON 解析错误或不兼容数据结构导致转换失败时抛出
     */
    public static <T> T convertToObject(Object data, Class<T> targetClass) {
        if (data == null) {
            throw new IllegalStateException("No structured data available in response");
        }

        try {
            return JsonUtils.getJsonCodec().convertValue(data, targetClass);
        } catch (Exception e) {
            throw new RuntimeException("Failed to convert metadata to " + targetClass.getName(), e);
        }
    }
}
