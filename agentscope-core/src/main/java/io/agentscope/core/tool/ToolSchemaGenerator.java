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
package io.agentscope.core.tool;

import io.agentscope.core.util.JsonSchemaUtils;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 生成工具参数的 JSON Schema，将 Java 方法签名转换为兼容 OpenAI 函数调用 API 的 JSON Schema 格式。
 */
class ToolSchemaGenerator {

    /**
     * 生成支持 additionalProperties 控制的参数模式。
     *
     * <p>这是 {@link Tool#strict()} 的内部实现：不允许附加属性时，向所有对象类型节点递归注入 {@code additionalProperties: false}。
     *
     * @param method 要生成模式的方法
     * @param excludeParams 要排除的参数名称集合（可为 null 或空）
     * @param allowAdditionalProperties 是否允许模式中出现附加属性
     * @return JSON Schema 映射
     */
    @SuppressWarnings("unchecked")
    Map<String, Object> generateParameterSchema(
            Method method, Set<String> excludeParams, boolean allowAdditionalProperties) {
        Map<String, Object> schema = generateParameterSchema(method, excludeParams);

        if (!allowAdditionalProperties) {
            addAdditionalPropertiesFalseRecursively(schema);
        }

        return schema;
    }

    @SuppressWarnings("unchecked")
    private void addAdditionalPropertiesFalseRecursively(Map<String, Object> schema) {
        // 仅在对象类型节点注入 additionalProperties=false
        if ("object".equals(schema.get("type")) && !schema.containsKey("additionalProperties")) {
            schema.put("additionalProperties", false);
        }

        // 无论当前节点类型如何，都递归处理子节点
        Object propsObj = schema.get("properties");
        if (propsObj instanceof Map) {
            Map<String, Object> props = (Map<String, Object>) propsObj;
            for (Map.Entry<String, Object> entry : props.entrySet()) {
                if (entry.getValue() instanceof Map) {
                    addAdditionalPropertiesFalseRecursively((Map<String, Object>) entry.getValue());
                }
            }
        }

        Object itemsObj = schema.get("items");
        if (itemsObj instanceof Map) {
            addAdditionalPropertiesFalseRecursively((Map<String, Object>) itemsObj);
        }

        for (String key : new String[] {"oneOf", "anyOf", "allOf"}) {
            Object listObj = schema.get(key);
            if (listObj instanceof Iterable) {
                for (Object item : (Iterable<?>) listObj) {
                    if (item instanceof Map) {
                        addAdditionalPropertiesFalseRecursively((Map<String, Object>) item);
                    }
                }
            }
        }

        for (String defsKey : new String[] {"$defs", "definitions"}) {
            Object defsObj = schema.get(defsKey);
            if (defsObj instanceof Map) {
                Map<String, Object> defs = (Map<String, Object>) defsObj;
                for (Map.Entry<String, Object> entry : defs.entrySet()) {
                    if (entry.getValue() instanceof Map) {
                        addAdditionalPropertiesFalseRecursively(
                                (Map<String, Object>) entry.getValue());
                    }
                }
            }
        }
    }

    /**
     * 为方法生成排除指定参数后的参数模式。
     *
     * <p>此重载允许从生成模式中排除某些参数，适合不应暴露给智能体的预设参数。
     *
     * @param method 要生成模式的方法
     * @param excludeParams 要从模式中排除的参数名称集合（可为 null 或空）
     * @return OpenAI 格式的 JSON Schema 映射
     */
    @SuppressWarnings("unchecked")
    Map<String, Object> generateParameterSchema(Method method, Set<String> excludeParams) {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();
        List<String> required = new ArrayList<>();
        Map<String, Object> allDefs = new HashMap<>();

        Parameter[] parameters = method.getParameters();
        for (Parameter param : parameters) {
            // 仅包含带 @ToolParam 注解的参数
            ToolParam toolParam = param.getAnnotation(ToolParam.class);
            if (toolParam == null) {
                continue;
            }

            ParameterInfo info = extractParameterInfo(param, toolParam);

            // 跳过排除参数（例如预设参数）
            if (excludeParams != null && excludeParams.contains(info.name)) {
                continue;
            }

            // 将各参数模式的 $defs 提升到根级，确保
            // "#/$defs/TypeName" 等 $ref 指针相对于文档根解析。
            hoistDefs(info.schema, "$defs", allDefs);
            hoistDefs(info.schema, "definitions", allDefs);

            properties.put(info.name, info.schema);
            if (info.required) {
                required.add(info.name);
            }
        }

        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        if (!allDefs.isEmpty()) {
            schema.put("$defs", allDefs);
        }

        return schema;
    }

    @SuppressWarnings("unchecked")
    private void hoistDefs(
            Map<String, Object> paramSchema, String key, Map<String, Object> target) {
        Object raw = paramSchema.remove(key);
        if (raw instanceof Map<?, ?> defs && !defs.isEmpty()) {
            Map<String, Object> normalizedDefs = (Map<String, Object>) defs;
            for (Map.Entry<String, Object> entry : normalizedDefs.entrySet()) {
                String defKey = entry.getKey();
                Object incomingDef = entry.getValue();
                Object existingDef = target.get(defKey);
                if (existingDef == null) {
                    target.put(defKey, incomingDef);
                    continue;
                }
                if (!Objects.equals(existingDef, incomingDef)) {
                    throw new IllegalStateException(
                            "Conflicting schema definition found for key: " + defKey);
                }
            }
        }
    }

    /**
     * 从带 @ToolParam 注解的 Parameter 提取参数信息。
     *
     * @param param 要提取信息的参数
     * @param toolParam @ToolParam 注解
     * @return 包含名称、模式和必填标志的 ParameterInfo
     */
    private ParameterInfo extractParameterInfo(Parameter param, ToolParam toolParam) {
        String paramName = toolParam.name();

        // 使用 JsonSchemaUtils 生成模式，保留包括泛型在内的完整类型信息
        Map<String, Object> paramSchema =
                JsonSchemaUtils.generateSchemaFromType(param.getParameterizedType());

        if (!toolParam.description().isEmpty()) {
            paramSchema.put("description", toolParam.description());
        }

        return new ParameterInfo(paramName, paramSchema, toolParam.required());
    }

    /**
     * 保存参数信息的内部类。
     */
    private static class ParameterInfo {
        final String name;
        final Map<String, Object> schema;
        final boolean required;

        ParameterInfo(String name, Map<String, Object> schema, boolean required) {
            this.name = name;
            this.schema = schema;
            this.required = required;
        }
    }
}
