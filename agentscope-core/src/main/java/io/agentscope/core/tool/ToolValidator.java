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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.util.JsonUtils;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 工具相关操作的统一验证器。
 *
 * <p>提供以下验证能力：
 * <ul>
 * <li>模式验证：使用 networknt-schema 按 JSON Schema 验证工具输入参数</li>
 * <li>HITL 验证：验证 ToolResult 消息与待处理 ToolUse 块匹配</li>
 * </ul>
 */
public final class ToolValidator {

    private static final Logger log = LoggerFactory.getLogger(ToolValidator.class);

    private static final SchemaRegistry SCHEMA_REGISTRY =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private ToolValidator() {
        // 工具类
    }

    // ==================== 模式验证 ====================

    /**
     * 验证提供商响应包含可用工具名称。
     *
     * <p>提供商解析器在创建 {@link ToolUseBlock} 前使用此检查。空白名称既不能分派给本地工具集，也不能回传给提供商，因此解析器应跳过该工具调用，而不是传播无效内容块。
     *
     * @param provider 用于诊断的模型提供商名称
     * @param name 提供商响应中的工具名称
     * @param id 用于诊断的工具调用 ID，可为 null 或空白
     * @return 名称非 null 且非空白时为 true，否则为 false
     */
    public static boolean requireNonBlank(String provider, String name, String id) {
        if (name != null && !name.isBlank()) {
            return true;
        }

        String diagnosticId = id == null || id.isBlank() ? "<missing>" : id;
        log.warn(
                "Ignoring {} tool call with blank name; id={}",
                provider != null ? provider : "<unknown>",
                diagnosticId);
        return false;
    }

    /**
     * 使用 networknt-schema 按 JSON Schema 验证工具输入参数。
     *
     * <p>执行完整验证，包括：
     * <ul>
     * <li>必填字段检查</li>
     * <li>类型验证（string、number、integer、boolean、array、object）</li>
     * <li>枚举值验证</li>
     * <li>数值最小值和最大值约束</li>
     * <li>字符串 MinLength/maxLength 约束</li>
     * <li>字符串模式匹配</li>
     * <li>嵌套对象和数组验证</li>
     * </ul>
     *
     * @param input 待验证输入参数
     * @param schema 用于验证的 JSON Schema（来自 tool.getParameters()）
     * @return 通过时为 null，否则返回描述验证失败的错误消息
     */
    public static String validateInput(String input, Map<String, Object> schema) {
        if (schema == null || schema.isEmpty()) {
            return null; // 没有模式，验证通过
        }

        try {
            // 将模式转换为 JSON 字符串
            String schemaJson = JsonUtils.getJsonCodec().toJson(schema);

            // 根据模式字符串创建 Schema
            Schema jsonSchema = SCHEMA_REGISTRY.getSchema(schemaJson);

            String normalizedInput = normalizeOptionalNullFields(input, schemaJson);

            // 执行验证
            List<Error> errors = jsonSchema.validate(normalizedInput, InputFormat.JSON);

            if (errors == null || errors.isEmpty()) {
                return null; // 验证通过
            }

            // 格式化错误消息，为每条错误添加字段路径前缀，便于定位修复
            return errors.stream()
                    .map(e -> e.getInstanceLocation() + ": " + e.getMessage())
                    .collect(Collectors.joining("\n"));

        } catch (Exception e) {
            return "Schema validation error: " + e.getMessage();
        }
    }

    /**
     * 按 JSON Schema 验证已解析的结构化输出载荷。
     *
     * <p>与 {@link #validateInput(String, Map)} 不同，不会规范化可选 null 字段：直接遵循 JSON Schema 语义，只有模式允许时，显式 {@code null} 才有效。
     *
     * @param output 已解析的模型输出
     * @param schema 用于验证的 JSON Schema
     * @return 符合模式时为 {@code null}，否则为以换行分隔的错误列表
     */
    public static String validateJsonOutput(JsonNode output, Map<String, Object> schema) {
        if (schema == null || schema.isEmpty()) {
            return null;
        }
        if (output == null) {
            return "$: output is null";
        }

        try {
            JsonNode schemaNode = JsonUtils.getJsonCodec().convertValue(schema, JsonNode.class);
            Schema compiledSchema = SCHEMA_REGISTRY.getSchema(schemaNode);
            List<Error> errors = compiledSchema.validate(output);
            if (errors == null || errors.isEmpty()) {
                return null;
            }
            return errors.stream()
                    .map(e -> e.getInstanceLocation() + ": " + e.getMessage())
                    .collect(Collectors.joining("\n"));
        } catch (Exception e) {
            return "Schema validation error: " + e.getMessage();
        }
    }

    /**
     * 在模式验证前移除值为 null 的对象属性。
     *
     * <p>将显式 null 与省略可选字段等同处理，同时让移除 null 属性后的必填检查自然失败。
     */
    private static String normalizeOptionalNullFields(String input, String schemaJson)
            throws Exception {
        if (input == null || input.isBlank()) {
            return input;
        }

        JsonNode root = OBJECT_MAPPER.readTree(input);
        JsonNode schemaRoot = OBJECT_MAPPER.readTree(schemaJson);
        pruneOptionalNullObjectFields(root, schemaRoot, schemaRoot);
        return OBJECT_MAPPER.writeValueAsString(root);
    }

    private static void pruneOptionalNullObjectFields(
            JsonNode inputNode, JsonNode schemaNode, JsonNode schemaRoot) {
        if (inputNode == null || schemaNode == null) {
            return;
        }

        JsonNode resolvedSchema = resolveSchemaNode(schemaNode, schemaRoot);
        if (resolvedSchema == null) {
            return;
        }

        if (inputNode.isObject()) {
            ObjectNode objectNode = (ObjectNode) inputNode;
            JsonNode propertiesNode = resolvedSchema.get("properties");
            Set<String> requiredFields = getRequiredFields(resolvedSchema);
            JsonNode additionalPropertiesNode = resolvedSchema.get("additionalProperties");
            List<String> nullFieldNames = new ArrayList<>();
            objectNode
                    .fields()
                    .forEachRemaining(
                            entry -> {
                                JsonNode propertySchema =
                                        propertiesNode != null
                                                ? propertiesNode.get(entry.getKey())
                                                : null;
                                if (entry.getValue().isNull()
                                        && propertySchema != null
                                        && !requiredFields.contains(entry.getKey())) {
                                    nullFieldNames.add(entry.getKey());
                                } else {
                                    JsonNode childSchema = propertySchema;
                                    if (childSchema == null
                                            && additionalPropertiesNode != null
                                            && additionalPropertiesNode.isObject()) {
                                        childSchema = additionalPropertiesNode;
                                    }
                                    if (childSchema != null) {
                                        pruneOptionalNullObjectFields(
                                                entry.getValue(), childSchema, schemaRoot);
                                    }
                                }
                            });
            nullFieldNames.forEach(objectNode::remove);
            return;
        }

        if (inputNode.isArray()) {
            ArrayNode arrayNode = (ArrayNode) inputNode;
            JsonNode itemsSchema = resolvedSchema.get("items");
            for (JsonNode item : arrayNode) {
                if (itemsSchema != null) {
                    pruneOptionalNullObjectFields(item, itemsSchema, schemaRoot);
                }
            }
        }
    }

    private static JsonNode resolveSchemaNode(JsonNode schemaNode, JsonNode schemaRoot) {
        JsonNode current = schemaNode;
        while (current != null && current.has("$ref")) {
            String ref = current.get("$ref").asText();
            if (!ref.startsWith("#/")) {
                return current;
            }
            current = schemaRoot.at(ref.substring(1));
        }
        return current;
    }

    private static Set<String> getRequiredFields(JsonNode schemaNode) {
        JsonNode requiredNode = schemaNode.get("required");
        if (requiredNode == null || !requiredNode.isArray()) {
            return Set.of();
        }

        Set<String> requiredFields = new HashSet<>();
        requiredNode.forEach(item -> requiredFields.add(item.asText()));
        return requiredFields;
    }

    // ==================== HITL 验证 ====================

    /**
     * 验证 ToolResult 消息与待处理 ToolUse 块匹配。
     *
     * <p>用于两种场景：
     * <ul>
     * <li>通过 {@code agent.call()} 恢复 HITL（人在回路）执行</li>
     * <li>通过 {@code PostReasoningEvent.gotoReasoning()} 进行基于钩子的流程控制</li>
     * </ul>
     *
     * <p>验证规则：
     * <ul>
     * <li>assistantMsg 为 null 或不含 ToolUse 块时，通过</li>
     * <li>assistantMsg 含 ToolUse 块但 inputMsgs 为 null 或空时，抛出异常</li>
     * <li>提供 inputMsgs 时，检查每个待处理 ToolUse ID 都有匹配的 ToolResult ID</li>
     * </ul>
     *
     * @param assistantMsg 可能包含待处理 ToolUse 块的助手消息
     * @param inputMsgs 应包含 ToolResult 块的输入消息
     * @throws IllegalStateException 当验证失败时
     */
    public static void validateToolResultMatch(Msg assistantMsg, List<Msg> inputMsgs) {
        if (assistantMsg == null) {
            return;
        }

        List<ToolUseBlock> pendingToolUses = assistantMsg.getContentBlocks(ToolUseBlock.class);
        if (pendingToolUses.isEmpty()) {
            return; // 没有待处理 ToolUse，验证通过
        }

        // 存在待处理 ToolUse
        if (inputMsgs == null || inputMsgs.isEmpty()) {
            // 未提供输入消息，抛出错误
            List<String> toolNames = pendingToolUses.stream().map(ToolUseBlock::getName).toList();
            throw new IllegalStateException(
                    "Cannot proceed without ToolResult when there are pending ToolUse. "
                            + "Pending tools: "
                            + toolNames);
        }

        // 存在输入消息，验证 ToolResult 匹配
        Set<String> providedIds =
                inputMsgs.stream()
                        .flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
                        .map(ToolResultBlock::getId)
                        .collect(Collectors.toSet());

        Set<String> requiredIds =
                pendingToolUses.stream().map(ToolUseBlock::getId).collect(Collectors.toSet());

        if (!providedIds.containsAll(requiredIds)) {
            Set<String> missing = new HashSet<>(requiredIds);
            missing.removeAll(providedIds);
            throw new IllegalStateException(
                    "Missing ToolResult for pending ToolUse. Missing IDs: " + missing);
        }
    }
}
