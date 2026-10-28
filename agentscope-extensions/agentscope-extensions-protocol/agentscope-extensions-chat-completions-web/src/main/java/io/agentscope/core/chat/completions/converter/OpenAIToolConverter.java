/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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
package io.agentscope.core.chat.completions.converter;

import io.agentscope.core.chat.completions.model.OpenAITool;
import io.agentscope.core.chat.completions.model.OpenAIToolFunction;
import io.agentscope.core.model.ToolSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 将 OpenAI 工具格式转换为 AgentScope ToolSchema 的转换器。
 *
 * <p>将 Chat Completions API 请求中的 OpenAI 工具格式转换为 AgentScope 内部 ToolSchema 格式。转换后的工具用于注册为仅含模式的工具，被调用时会触发工具执行挂起。
 */
public class OpenAIToolConverter {

    private static final Logger log = LoggerFactory.getLogger(OpenAIToolConverter.class);

    /**
     * 将 OpenAI 工具列表转换为 AgentScope ToolSchema 列表。
     *
     * <p>仅转换类型为 "function" 的工具；其他类型会记录警告并跳过。
     *
     * @param tools 待转换的 OpenAI 工具列表（可为 null 或空）
     * @return 转换后的 ToolSchema 对象列表；输入为 null 或空时返回空列表
     */
    public List<ToolSchema> convertToToolSchemas(List<OpenAITool> tools) {
        if (tools == null || tools.isEmpty()) {
            return List.of();
        }

        List<ToolSchema> schemas = new ArrayList<>();

        for (OpenAITool tool : tools) {
            if (tool == null) {
                log.warn("Skipping null tool in conversion");
                continue;
            }

            // 目前仅支持 function 类型的工具
            if (!"function".equals(tool.getType())) {
                log.warn(
                        "Skipping tool with unsupported type: {}. Only 'function' type is"
                                + " supported",
                        tool.getType());
                continue;
            }

            OpenAIToolFunction function = tool.getFunction();
            if (function == null) {
                log.warn("Skipping tool with null function definition");
                continue;
            }

            String name = function.getName();
            String description = function.getDescription();
            Map<String, Object> parameters = function.getParameters();

            // 校验必填字段
            if (name == null || name.isBlank()) {
                log.warn("Skipping tool with null or empty name");
                continue;
            }

            if (description == null || description.isBlank()) {
                log.warn("Skipping tool '{}' with null or empty description", name);
                // 描述缺失时回退为空字符串
                description = "";
            }

            try {
                ToolSchema.Builder schemaBuilder =
                        ToolSchema.builder().name(name).description(description);

                if (parameters != null) {
                    schemaBuilder.parameters(parameters);
                }

                if (function.getStrict() != null) {
                    schemaBuilder.strict(function.getStrict());
                }

                ToolSchema schema = schemaBuilder.build();
                schemas.add(schema);
                log.debug("Converted OpenAI tool to ToolSchema: {}", name);

            } catch (Exception e) {
                log.error("Failed to convert tool '{}' to ToolSchema: {}", name, e.getMessage(), e);
            }
        }

        return schemas;
    }

    /**
     * 将单个 OpenAI 工具转换为 ToolSchema。
     *
     * @param tool 待转换的 OpenAI 工具
     * @return 转换后的 ToolSchema；转换失败时返回 null
     */
    public ToolSchema convertToToolSchema(OpenAITool tool) {
        if (tool == null) {
            return null;
        }

        List<ToolSchema> schemas = convertToToolSchemas(List.of(tool));
        return schemas.isEmpty() ? null : schemas.get(0);
    }
}
