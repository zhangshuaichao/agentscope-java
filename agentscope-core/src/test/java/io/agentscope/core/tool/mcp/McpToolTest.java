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
package io.agentscope.core.tool.mcp;

import static org.junit.Assert.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolCallParam;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class McpToolTest {

    private McpClientWrapper mockClientWrapper;
    private Map<String, Object> parameters;

    @BeforeEach
    void setUp() {
        // McpTool.callAsync 动态读取连接级开关，此处默认启用。
        mockClientWrapper = McpClientWrapperTestSupport.mockWrapper("test-client", true);

        parameters = new HashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", new HashMap<>());
    }

    @Test
    void testMergeArguments_NullInputWithoutPresetReturnsEmptyMap() {
        McpTool tool = new McpTool("test-tool", "A test tool", parameters, mockClientWrapper);

        Map<String, Object> result = tool.mergeArguments(null);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void testMergeArguments_NullInputWithPresetReturnsPresetCopy() {
        Map<String, Object> preset = new HashMap<>();
        preset.put("presetKey", "presetValue");
        McpTool tool =
                new McpTool(
                        "test-tool",
                        "A test tool",
                        parameters,
                        null,
                        mockClientWrapper,
                        preset,
                        "test-client",
                        false);

        Map<String, Object> result = tool.mergeArguments(null);

        assertEquals(1, result.size());
        assertEquals("presetValue", result.get("presetKey"));
        // 结果必须为防御性副本，修改它不得影响预设
        result.put("mutated", "value");
        assertFalse(preset.containsKey("mutated"));
    }

    @Test
    void testConstructor_WithoutPresetArgs() {
        McpTool tool = new McpTool("test-tool", "A test tool", parameters, mockClientWrapper);

        assertEquals("test-tool", tool.getName());
        assertEquals("A test tool", tool.getDescription());
        assertEquals(parameters, tool.getParameters());
        assertNull(tool.getOutputSchema());
        assertEquals("test-client", tool.getClientName());
        assertNull(tool.getPresetArguments());
    }

    @Test
    void testConstructor_WithOutputSchema() {
        Map<String, Object> outputSchema = new HashMap<>();
        outputSchema.put("type", "object");
        outputSchema.put("properties", Map.of("answer", Map.of("type", "string")));

        McpTool tool =
                new McpTool(
                        "test-tool",
                        "A test tool",
                        parameters,
                        outputSchema,
                        mockClientWrapper,
                        null);

        assertNotNull(tool.getOutputSchema());
        assertEquals("object", tool.getOutputSchema().get("type"));
        assertTrue(tool.getOutputSchema().containsKey("properties"));
    }

    @Test
    void testConstructor_WithPresetArgs() {
        Map<String, Object> presetArgs = new HashMap<>();
        presetArgs.put("preset1", "value1");
        presetArgs.put("preset2", 42);

        McpTool tool =
                new McpTool("test-tool", "A test tool", parameters, mockClientWrapper, presetArgs);

        assertEquals("test-tool", tool.getName());
        assertNotNull(tool.getPresetArguments());
        assertEquals(2, tool.getPresetArguments().size());
        assertEquals("value1", tool.getPresetArguments().get("preset1"));
        assertEquals(42, tool.getPresetArguments().get("preset2"));
    }

    @Test
    void testConstructor_WithNullPresetArgs() {
        McpTool tool = new McpTool("test-tool", "A test tool", parameters, mockClientWrapper, null);

        assertNull(tool.getPresetArguments());
    }

    @Test
    void testGetName() {
        McpTool tool = new McpTool("my-tool", "Description", parameters, mockClientWrapper);
        assertEquals("my-tool", tool.getName());
    }

    @Test
    void testGetDescription() {
        McpTool tool =
                new McpTool("tool", "This is a test description", parameters, mockClientWrapper);
        assertEquals("This is a test description", tool.getDescription());
    }

    @Test
    void testGetParameters() {
        Map<String, Object> customParams = new HashMap<>();
        customParams.put("type", "object");
        customParams.put("required", List.of("param1"));

        McpTool tool = new McpTool("tool", "Description", customParams, mockClientWrapper);

        Map<String, Object> result = tool.getParameters();
        assertEquals("object", result.get("type"));
        assertTrue(result.containsKey("required"));
    }

    @Test
    void testGetClientName() {
        McpTool tool = new McpTool("tool", "Description", parameters, mockClientWrapper);
        assertEquals("test-client", tool.getClientName());
    }

    @Test
    void testCallAsync_Success() {
        McpTool tool = new McpTool("test-tool", "Description", parameters, mockClientWrapper);

        Map<String, Object> input = new HashMap<>();
        input.put("param1", "value1");

        McpSchema.TextContent resultContent =
                McpSchema.TextContent.builder("Success result").build();
        McpSchema.CallToolResult mcpResult =
                McpSchema.CallToolResult.builder()
                        .content(List.of(resultContent))
                        .isError(false)
                        .build();

        when(mockClientWrapper.callTool(eq("test-tool"), any(), any()))
                .thenReturn(Mono.just(mcpResult));

        ToolResultBlock result =
                tool.callAsync(ToolCallParam.builder().input(input).build()).block();
        assertNotNull(result);
        assertFalse(result.getOutput().isEmpty());
        String outputText = ((TextBlock) result.getOutput().get(0)).getText();
        assertFalse(outputText.startsWith("Error:"));

        verify(mockClientWrapper).callTool(eq("test-tool"), any(), any());
    }

    @Test
    void testCallAsync_WithEmptyInput() {
        McpTool tool = new McpTool("test-tool", "Description", parameters, mockClientWrapper);

        McpSchema.TextContent resultContent = McpSchema.TextContent.builder("Success").build();
        McpSchema.CallToolResult mcpResult =
                McpSchema.CallToolResult.builder()
                        .content(List.of(resultContent))
                        .isError(false)
                        .build();

        when(mockClientWrapper.callTool(eq("test-tool"), any(), any()))
                .thenReturn(Mono.just(mcpResult));

        ToolResultBlock result =
                tool.callAsync(ToolCallParam.builder().input(new HashMap<>()).build()).block();
        assertNotNull(result);
        assertFalse(result.getOutput().isEmpty());
        String outputText = ((TextBlock) result.getOutput().get(0)).getText();
        assertFalse(outputText.startsWith("Error:"));
    }

    @Test
    void testCallAsync_WithNullInput() {
        McpTool tool = new McpTool("test-tool", "Description", parameters, mockClientWrapper);

        McpSchema.TextContent resultContent = McpSchema.TextContent.builder("Success").build();
        McpSchema.CallToolResult mcpResult =
                McpSchema.CallToolResult.builder()
                        .content(List.of(resultContent))
                        .isError(false)
                        .build();

        when(mockClientWrapper.callTool(eq("test-tool"), any(), any()))
                .thenReturn(Mono.just(mcpResult));

        ToolResultBlock result = tool.callAsync(ToolCallParam.builder().build()).block();
        assertNotNull(result);
        assertFalse(result.getOutput().isEmpty());
        String outputText = ((TextBlock) result.getOutput().get(0)).getText();
        assertFalse(outputText.startsWith("Error:"));
    }

    @Test
    void testCallAsync_MergesPresetArguments() {
        Map<String, Object> presetArgs = new HashMap<>();
        presetArgs.put("preset_key", "preset_value");
        presetArgs.put("override_me", "old_value");

        McpTool tool =
                new McpTool("test-tool", "Description", parameters, mockClientWrapper, presetArgs);

        Map<String, Object> input = new HashMap<>();
        input.put("input_key", "input_value");
        input.put("override_me", "new_value"); // 应覆盖预设

        McpSchema.TextContent resultContent = McpSchema.TextContent.builder("Success").build();
        McpSchema.CallToolResult mcpResult =
                McpSchema.CallToolResult.builder()
                        .content(List.of(resultContent))
                        .isError(false)
                        .build();

        when(mockClientWrapper.callTool(eq("test-tool"), any(), any()))
                .thenReturn(Mono.just(mcpResult));

        ToolResultBlock result =
                tool.callAsync(ToolCallParam.builder().input(input).build()).block();
        assertNotNull(result);
        assertFalse(result.getOutput().isEmpty());
        String outputText = ((TextBlock) result.getOutput().get(0)).getText();
        assertFalse(outputText.startsWith("Error:"));

        // 验证传递了合并后的参数
        verify(mockClientWrapper).callTool(eq("test-tool"), any(Map.class), any(Map.class));
    }

    @Test
    void testCallAsync_PresetArgsOnly() {
        Map<String, Object> presetArgs = new HashMap<>();
        presetArgs.put("preset1", "value1");
        presetArgs.put("preset2", "value2");

        McpTool tool =
                new McpTool("test-tool", "Description", parameters, mockClientWrapper, presetArgs);

        McpSchema.TextContent resultContent = McpSchema.TextContent.builder("Success").build();
        McpSchema.CallToolResult mcpResult =
                McpSchema.CallToolResult.builder()
                        .content(List.of(resultContent))
                        .isError(false)
                        .build();

        when(mockClientWrapper.callTool(eq("test-tool"), any(), any()))
                .thenReturn(Mono.just(mcpResult));

        // null 输入调用，仅应使用预设参数
        ToolResultBlock result = tool.callAsync(ToolCallParam.builder().build()).block();
        assertNotNull(result);
        assertFalse(result.getOutput().isEmpty());
        String outputText = ((TextBlock) result.getOutput().get(0)).getText();
        assertFalse(outputText.startsWith("Error:"));
    }

    @Test
    void testCallAsync_ErrorHandling() {
        McpTool tool = new McpTool("test-tool", "Description", parameters, mockClientWrapper);

        when(mockClientWrapper.callTool(eq("test-tool"), any(), any()))
                .thenReturn(Mono.error(new RuntimeException("Network error")));

        ToolResultBlock result =
                tool.callAsync(ToolCallParam.builder().input(new HashMap<>()).build()).block();
        assertNotNull(result);
        assertFalse(result.getOutput().isEmpty());
        String outputText = ((TextBlock) result.getOutput().get(0)).getText();
        assertTrue(outputText.startsWith("Error:"));
        assertTrue(outputText.contains("MCP tool error"));
        assertTrue(outputText.contains("Network error"));
    }

    @Test
    void testCallAsync_ErrorWithNullMessage() {
        McpTool tool = new McpTool("test-tool", "Description", parameters, mockClientWrapper);

        when(mockClientWrapper.callTool(eq("test-tool"), any(), any()))
                .thenReturn(Mono.error(new NullPointerException()));

        ToolResultBlock result =
                tool.callAsync(ToolCallParam.builder().input(new HashMap<>()).build()).block();
        assertNotNull(result);
        assertFalse(result.getOutput().isEmpty());
        String outputText = ((TextBlock) result.getOutput().get(0)).getText();
        assertTrue(outputText.startsWith("Error:"));
        assertTrue(outputText.contains("MCP tool error"));
        assertTrue(outputText.contains("NullPointerException"));
    }

    @Test
    void testGetPresetArguments_ReturnsDefensiveCopy() {
        Map<String, Object> presetArgs = new HashMap<>();
        presetArgs.put("key", "value");

        McpTool tool =
                new McpTool("test-tool", "Description", parameters, mockClientWrapper, presetArgs);

        Map<String, Object> retrieved1 = tool.getPresetArguments();
        Map<String, Object> retrieved2 = tool.getPresetArguments();

        // 应返回不同实例，即防御性副本
        assertTrue(retrieved1 != retrieved2);
        assertEquals(retrieved1, retrieved2);

        // 修改返回映射不得影响工具内部状态
        retrieved1.put("new_key", "new_value");
        Map<String, Object> retrieved3 = tool.getPresetArguments();
        assertFalse(retrieved3.containsKey("new_key"));
    }

    @Test
    void testConvertMcpSchemaToParameters_NullSchema() {
        Map<String, Object> result = McpTool.convertMcpSchemaToParameters(null, null);

        assertNotNull(result);
        assertEquals("object", result.get("type"));
        assertTrue(result.containsKey("properties"));
        assertTrue(result.containsKey("required"));
        assertTrue(((Map<?, ?>) result.get("properties")).isEmpty());
        assertTrue(((List<?>) result.get("required")).isEmpty());
    }

    @Test
    void testConvertMcpSchemaToParameters_CompleteSchema() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("param1", Map.of("type", "string"));
        properties.put("param2", Map.of("type", "number"));

        List<String> required = List.of("param1");

        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", true);

        Map<String, Object> result = McpTool.convertMcpSchemaToParameters(schema, null);

        assertNotNull(result);
        assertEquals("object", result.get("type"));
        assertEquals(properties, result.get("properties"));
        assertEquals(required, result.get("required"));
        assertEquals(true, result.get("additionalProperties"));
    }

    @Test
    void testConvertMcpSchemaToParameters_PreservesAdvancedJsonSchemaKeywords() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("visible", Map.of("type", "string"));
        properties.put("hidden", Map.of("type", "string"));

        List<String> required = List.of("visible", "hidden");
        List<Map<String, Object>> allOf = List.of(Map.of("$ref", "#/$defs/base"));
        Map<String, Object> patternProperties = Map.of("^metadata_", Map.of("type", "string"));
        List<Map<String, Object>> prefixItems =
                List.of(Map.of("type", "string"), Map.of("type", "number"));

        Map<String, Object> schema = new HashMap<>();
        schema.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("allOf", allOf);
        schema.put("patternProperties", patternProperties);
        schema.put("prefixItems", prefixItems);
        schema.put("propertyNames", Map.of("pattern", "^[a-z][a-zA-Z0-9]*$"));
        schema.put("$defs", Map.of("base", Map.of("type", "object")));

        Map<String, Object> result = McpTool.convertMcpSchemaToParameters(schema, Set.of("hidden"));

        assertEquals("https://json-schema.org/draft/2020-12/schema", result.get("$schema"));
        assertEquals(allOf, result.get("allOf"));
        assertEquals(patternProperties, result.get("patternProperties"));
        assertEquals(prefixItems, result.get("prefixItems"));
        assertEquals(Map.of("pattern", "^[a-z][a-zA-Z0-9]*$"), result.get("propertyNames"));
        assertEquals(Map.of("base", Map.of("type", "object")), result.get("$defs"));

        Map<?, ?> resultProperties = (Map<?, ?>) result.get("properties");
        assertTrue(resultProperties.containsKey("visible"));
        assertFalse(resultProperties.containsKey("hidden"));

        List<?> resultRequired = (List<?>) result.get("required");
        assertTrue(resultRequired.contains("visible"));
        assertFalse(resultRequired.contains("hidden"));
    }

    @Test
    void testConvertMcpSchemaToParameters_NullType() {
        Map<String, Object> schema = new HashMap<>();

        Map<String, Object> result = McpTool.convertMcpSchemaToParameters(schema, null);

        assertNotNull(result);
        assertEquals("object", result.get("type")); // 默认为 "object"
        assertNotNull(result.get("properties"));
        assertNotNull(result.get("required"));
    }

    @Test
    void testConvertMcpSchemaToParameters_EmptyProperties() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", new HashMap<>());
        schema.put("required", new ArrayList<>());

        Map<String, Object> result = McpTool.convertMcpSchemaToParameters(schema, null);

        assertNotNull(result);
        assertTrue(((Map<?, ?>) result.get("properties")).isEmpty());
        assertTrue(((List<?>) result.get("required")).isEmpty());
        assertFalse(result.containsKey("additionalProperties")); // 为 null 时应省略
    }

    @Test
    void testConvertMcpSchemaToParameters_WithAdditionalProperties() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("additionalProperties", false);

        Map<String, Object> result = McpTool.convertMcpSchemaToParameters(schema, null);

        assertNotNull(result);
        assertTrue(result.containsKey("additionalProperties"));
        assertEquals(false, result.get("additionalProperties"));
    }

    @Test
    void testConvertMcpSchemaToParameters_ComplexProperties() {
        Map<String, Object> properties = new HashMap<>();
        properties.put(
                "user",
                Map.of("type", "object", "properties", Map.of("name", Map.of("type", "string"))));

        List<String> required = List.of("user");

        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);

        Map<String, Object> result = McpTool.convertMcpSchemaToParameters(schema, null);

        assertNotNull(result);
        Map<?, ?> resultProperties = (Map<?, ?>) result.get("properties");
        assertTrue(resultProperties.containsKey("user"));
        Map<?, ?> userProperty = (Map<?, ?>) resultProperties.get("user");
        assertEquals("object", userProperty.get("type"));
    }

    @Test
    void testMergeArguments_BothEmpty() {
        McpTool tool =
                new McpTool(
                        "test-tool", "Description", parameters, mockClientWrapper, new HashMap<>());

        McpSchema.TextContent resultContent = McpSchema.TextContent.builder("Success").build();
        McpSchema.CallToolResult mcpResult =
                McpSchema.CallToolResult.builder()
                        .content(List.of(resultContent))
                        .isError(false)
                        .build();

        when(mockClientWrapper.callTool(eq("test-tool"), any(), any()))
                .thenReturn(Mono.just(mcpResult));

        ToolResultBlock result =
                tool.callAsync(ToolCallParam.builder().input(new HashMap<>()).build()).block();
        assertNotNull(result);
        assertFalse(result.getOutput().isEmpty());
        String outputText = ((TextBlock) result.getOutput().get(0)).getText();
        assertFalse(outputText.startsWith("Error:"));
    }

    @Test
    void testMergeArguments_InputOverridesPreset() {
        Map<String, Object> presetArgs = new HashMap<>();
        presetArgs.put("key", "preset_value");

        McpTool tool =
                new McpTool("test-tool", "Description", parameters, mockClientWrapper, presetArgs);

        Map<String, Object> input = new HashMap<>();
        input.put("key", "input_value");

        McpSchema.TextContent resultContent = McpSchema.TextContent.builder("Success").build();
        McpSchema.CallToolResult mcpResult =
                McpSchema.CallToolResult.builder()
                        .content(List.of(resultContent))
                        .isError(false)
                        .build();

        when(mockClientWrapper.callTool(eq("test-tool"), any(), any()))
                .thenReturn(Mono.just(mcpResult));

        // 合并参数应含 input_value，而非 preset_value
        ToolResultBlock result =
                tool.callAsync(ToolCallParam.builder().input(input).build()).block();
        assertNotNull(result);
        assertFalse(result.getOutput().isEmpty());
        String outputText = ((TextBlock) result.getOutput().get(0)).getText();
        assertFalse(outputText.startsWith("Error:"));
    }

    @Test
    void testConvertMcpSchemaToParameters_WithExcludeParams() {
        // 创建带 properties 和 required 字段的 schema
        Map<String, Object> properties = new HashMap<>();
        properties.put("param1", Map.of("type", "string", "description", "First parameter"));
        properties.put("param2", Map.of("type", "number", "description", "Second parameter"));
        properties.put("param3", Map.of("type", "boolean", "description", "Third parameter"));

        List<String> required = List.of("param1", "param2", "param3");

        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);

        // 排除部分参数
        Set<String> excludeParams = Set.of("param2", "param3");

        Map<String, Object> result = McpTool.convertMcpSchemaToParameters(schema, excludeParams);

        assertNotNull(result);
        assertEquals("object", result.get("type"));

        // 检查已移除排除属性
        Map<String, Object> resultProperties = (Map<String, Object>) result.get("properties");
        assertTrue(resultProperties.containsKey("param1"));
        assertFalse(resultProperties.containsKey("param2"));
        assertFalse(resultProperties.containsKey("param3"));

        // 检查已移除排除的必填字段
        List<String> resultRequired = (List<String>) result.get("required");
        assertTrue(resultRequired.contains("param1"));
        assertFalse(resultRequired.contains("param2"));
        assertFalse(resultRequired.contains("param3"));
    }

    @Test
    void testConvertMcpSchemaToParameters_WithExcludeParams_EmptyExcludes() {
        // 创建带 properties 和 required 字段的 schema
        Map<String, Object> properties = new HashMap<>();
        properties.put("param1", Map.of("type", "string"));
        properties.put("param2", Map.of("type", "number"));

        List<String> required = List.of("param1", "param2");

        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);

        // 传空排除集合
        Set<String> excludeParams = Set.of();

        Map<String, Object> result = McpTool.convertMcpSchemaToParameters(schema, excludeParams);

        assertNotNull(result);
        assertEquals("object", result.get("type"));

        // 全部属性和必填字段应保留
        Map<String, Object> resultProperties = (Map<String, Object>) result.get("properties");
        assertTrue(resultProperties.containsKey("param1"));
        assertTrue(resultProperties.containsKey("param2"));

        List<String> resultRequired = (List<String>) result.get("required");
        assertTrue(resultRequired.contains("param1"));
        assertTrue(resultRequired.contains("param2"));
    }

    @Test
    void testConvertMcpSchemaToParameters_WithExcludeParams_NullExcludes() {
        // 创建带 properties 和 required 字段的 schema
        Map<String, Object> properties = new HashMap<>();
        properties.put("param1", Map.of("type", "string"));
        properties.put("param2", Map.of("type", "number"));

        List<String> required = List.of("param1", "param2");

        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);

        // 传 null 排除集合
        Map<String, Object> result = McpTool.convertMcpSchemaToParameters(schema, null);

        assertNotNull(result);
        assertEquals("object", result.get("type"));

        // 全部属性和必填字段应保留
        Map<String, Object> resultProperties = (Map<String, Object>) result.get("properties");
        assertTrue(resultProperties.containsKey("param1"));
        assertTrue(resultProperties.containsKey("param2"));

        List<String> resultRequired = (List<String>) result.get("required");
        assertTrue(resultRequired.contains("param1"));
        assertTrue(resultRequired.contains("param2"));
    }

    @Test
    void testConvertMcpSchemaToParameters_WithExcludeParams_NonExistentParams() {
        // 创建带 properties 和 required 字段的 schema
        Map<String, Object> properties = new HashMap<>();
        properties.put("param1", Map.of("type", "string"));
        properties.put("param2", Map.of("type", "number"));

        List<String> required = List.of("param1", "param2");

        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);

        // 排除 schema 中不存在的参数
        Set<String> excludeParams = Set.of("nonexistent1", "nonexistent2");

        Map<String, Object> result = McpTool.convertMcpSchemaToParameters(schema, excludeParams);

        assertNotNull(result);
        assertEquals("object", result.get("type"));

        // 属性和必填字段应保持不变
        Map<String, Object> resultProperties = (Map<String, Object>) result.get("properties");
        assertTrue(resultProperties.containsKey("param1"));
        assertTrue(resultProperties.containsKey("param2"));

        List<String> resultRequired = (List<String>) result.get("required");
        assertTrue(resultRequired.contains("param1"));
        assertTrue(resultRequired.contains("param2"));
    }

    // ==================== Issue #893：$defs 支持测试 ====================

    @Test
    void testConvertMcpSchemaToParameters_WithDefs() {
        // 在 $defs 中创建 Material 定义
        Map<String, Object> materialDef = new HashMap<>();
        materialDef.put("type", "object");
        materialDef.put(
                "properties",
                Map.of("key", Map.of("type", "string"), "value", Map.of("type", "string")));

        Map<String, Object> defs = new HashMap<>();
        defs.put("Material", materialDef);

        // 创建带 $ref 引用的属性
        Map<String, Object> materialProperty = new HashMap<>();
        materialProperty.put("type", "object");
        materialProperty.put(
                "properties",
                Map.of(
                        "searchMaterialList",
                        Map.of("type", "array", "items", Map.of("$ref", "#/$defs/Material"))));

        Map<String, Object> properties = new HashMap<>();
        properties.put("request", materialProperty);

        // 创建带 defs 的 MCP JsonSchema
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("request"));
        schema.put("$defs", defs);

        Map<String, Object> result = McpTool.convertMcpSchemaToParameters(schema, null);

        assertNotNull(result);
        assertEquals("object", result.get("type"));

        // 验证正确复制 $defs 字段
        assertTrue(result.containsKey("$defs"), "$defs should be present in converted schema");
        Map<?, ?> resultDefs = (Map<?, ?>) result.get("$defs");
        assertTrue(
                resultDefs.containsKey("Material"),
                "Material definition should be present in $defs");
    }

    @Test
    void testConvertMcpSchemaToParameters_WithDefinitions() {
        // 在旧版 JSON Schema 的 definitions 中创建 Material 定义
        Map<String, Object> materialDef = new HashMap<>();
        materialDef.put("type", "object");
        materialDef.put("properties", Map.of("key", Map.of("type", "string")));

        Map<String, Object> definitions = new HashMap<>();
        definitions.put("Material", materialDef);

        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", new HashMap<>());
        schema.put("required", new ArrayList<>());
        schema.put("definitions", definitions);

        Map<String, Object> result = McpTool.convertMcpSchemaToParameters(schema, null);

        assertNotNull(result);

        // 验证正确复制 definitions 字段
        assertTrue(
                result.containsKey("definitions"),
                "definitions should be present in converted schema");
        Map<?, ?> resultDefinitions = (Map<?, ?>) result.get("definitions");
        assertTrue(
                resultDefinitions.containsKey("Material"),
                "Material definition should be present in definitions");
    }

    @Test
    void testConvertMcpSchemaToParameters_WithBothDefsAndDefinitions() {
        Map<String, Object> defs = new HashMap<>();
        defs.put("NewType", Map.of("type", "string"));

        Map<String, Object> definitions = new HashMap<>();
        definitions.put("OldType", Map.of("type", "integer"));

        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", new HashMap<>());
        schema.put("required", new ArrayList<>());
        schema.put("$defs", defs);
        schema.put("definitions", definitions);

        Map<String, Object> result = McpTool.convertMcpSchemaToParameters(schema, null);

        assertNotNull(result);
        assertTrue(result.containsKey("$defs"), "$defs should be present");
        assertTrue(result.containsKey("definitions"), "definitions should be present");
    }

    @Test
    void testConvertMcpSchemaToParameters_WithEmptyDefsAndDefinitions() {
        // 空映射不应添加到结果
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", new HashMap<>());
        schema.put("required", new ArrayList<>());

        Map<String, Object> result = McpTool.convertMcpSchemaToParameters(schema, null);

        assertNotNull(result);
        assertFalse(result.containsKey("$defs"), "Empty $defs should not be present");
        assertFalse(result.containsKey("definitions"), "Empty definitions should not be present");
    }

    @Test
    void testConvertMcpSchemaToParameters_DefsWithMultipleDefinitions() {
        // 测试含多个类型定义的 $defs
        Map<String, Object> defs = new HashMap<>();
        defs.put(
                "Material",
                Map.of("type", "object", "properties", Map.of("id", Map.of("type", "string"))));
        defs.put("Category", Map.of("type", "string", "enum", List.of("A", "B", "C")));

        Map<String, Object> properties = new HashMap<>();
        properties.put(
                "items", Map.of("type", "array", "items", Map.of("$ref", "#/$defs/Material")));
        properties.put("category", Map.of("$ref", "#/$defs/Category"));

        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("items"));
        schema.put("$defs", defs);

        Map<String, Object> result = McpTool.convertMcpSchemaToParameters(schema, null);

        assertNotNull(result);
        assertTrue(result.containsKey("$defs"));
        Map<?, ?> resultDefs = (Map<?, ?>) result.get("$defs");
        assertEquals(2, resultDefs.size(), "Should have 2 definitions");
        assertTrue(resultDefs.containsKey("Material"));
        assertTrue(resultDefs.containsKey("Category"));
    }

    @Test
    void testConvertMcpSchemaToParameters_DefsWithExcludeParams() {
        // 测试使用 excludeParams 时仍保留 $defs
        Map<String, Object> defs = new HashMap<>();
        defs.put("ItemType", Map.of("type", "string"));

        Map<String, Object> properties = new HashMap<>();
        properties.put("name", Map.of("type", "string"));
        properties.put("hidden", Map.of("type", "string"));

        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("name", "hidden"));
        schema.put("$defs", defs);

        Map<String, Object> result = McpTool.convertMcpSchemaToParameters(schema, Set.of("hidden"));

        assertNotNull(result);
        assertTrue(result.containsKey("$defs"), "$defs should be preserved with excludeParams");
        assertFalse(
                ((Map<?, ?>) result.get("properties")).containsKey("hidden"),
                "hidden property should be excluded");
    }

    @Test
    void testConvertMcpSchemaToParameters_WithNullDefsAndDefinitions() {
        // defs 和 definitions 为 null 时，不应加入结果
        Map<String, Object> properties = new HashMap<>();
        properties.put("name", Map.of("type", "string"));

        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("name"));

        Map<String, Object> result = McpTool.convertMcpSchemaToParameters(schema, null);

        assertNotNull(result);
        assertFalse(result.containsKey("$defs"), "null $defs should not be present");
        assertFalse(result.containsKey("definitions"), "null definitions should not be present");
    }

    @Test
    void testConvertMcpSchemaToParameters_OnlyDefsNull() {
        // 测试仅有 definitions、defs 为 null
        Map<String, Object> definitions = new HashMap<>();
        definitions.put("OldType", Map.of("type", "integer"));

        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", new HashMap<>());
        schema.put("required", new ArrayList<>());
        schema.put("definitions", definitions);

        Map<String, Object> result = McpTool.convertMcpSchemaToParameters(schema, null);

        assertNotNull(result);
        assertFalse(result.containsKey("$defs"), "null $defs should not be present");
        assertTrue(result.containsKey("definitions"), "definitions should be present");
    }

    @Test
    void testConvertMcpSchemaToParameters_OnlyDefinitionsNull() {
        // 测试仅有 defs、definitions 为 null
        Map<String, Object> defs = new HashMap<>();
        defs.put("NewType", Map.of("type", "string"));

        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", new HashMap<>());
        schema.put("required", new ArrayList<>());
        schema.put("$defs", defs);

        Map<String, Object> result = McpTool.convertMcpSchemaToParameters(schema, null);

        assertNotNull(result);
        assertTrue(result.containsKey("$defs"), "$defs should be present");
        assertFalse(result.containsKey("definitions"), "null definitions should not be present");
    }

    @Test
    void testConstructor_FullConstructor_WithAllParams() {
        Map<String, Object> outputSchema = Map.of("type", "object");
        Map<String, Object> presetArgs = Map.of("key", "value");

        McpTool tool =
                new McpTool(
                        "test-tool",
                        "Description",
                        parameters,
                        outputSchema,
                        mockClientWrapper,
                        presetArgs,
                        "custom-mcp-server",
                        true);

        assertEquals("test-tool", tool.getName());
        assertEquals("Description", tool.getDescription());
        assertEquals("custom-mcp-server", tool.getMcpName());
        assertTrue(tool.isReadOnly());
        assertNotNull(tool.getOutputSchema());
        assertNotNull(tool.getPresetArguments());
        assertEquals(1, tool.getPresetArguments().size());
    }

    @Test
    void testConstructor_FullConstructor_WithNullDescription() {
        McpTool tool =
                new McpTool(
                        "test-tool",
                        null,
                        parameters,
                        null,
                        mockClientWrapper,
                        null,
                        "mcp-server",
                        false);

        assertEquals("", tool.getDescription());
    }

    @Test
    void testConstructor_FullConstructor_DefensiveCopyOutputSchema() {
        Map<String, Object> outputSchema = new HashMap<>();
        outputSchema.put("key", "value");

        McpTool tool =
                new McpTool(
                        "test-tool",
                        "Description",
                        parameters,
                        outputSchema,
                        mockClientWrapper,
                        null,
                        "mcp-server",
                        false);

        // 修改原对象不得影响工具
        outputSchema.put("newKey", "newValue");
        assertFalse(tool.getOutputSchema().containsKey("newKey"));
    }

    @Test
    void testConstructor_FullConstructor_DefensiveCopyPresetArgs() {
        Map<String, Object> presetArgs = new HashMap<>();
        presetArgs.put("key", "value");

        McpTool tool =
                new McpTool(
                        "test-tool",
                        "Description",
                        parameters,
                        null,
                        mockClientWrapper,
                        presetArgs,
                        "mcp-server",
                        false);

        // 修改原对象不得影响工具
        presetArgs.put("newKey", "newValue");
        assertFalse(tool.getPresetArguments().containsKey("newKey"));
    }

    @Test
    void testGetOutputSchema_ReturnsDefensiveCopy() {
        Map<String, Object> outputSchema = new HashMap<>();
        outputSchema.put("type", "object");
        outputSchema.put("properties", Map.of("result", Map.of("type", "string")));

        McpTool tool =
                new McpTool(
                        "test-tool",
                        "Description",
                        parameters,
                        outputSchema,
                        mockClientWrapper,
                        null,
                        "mcp-server",
                        false);

        Map<String, Object> schema1 = tool.getOutputSchema();
        Map<String, Object> schema2 = tool.getOutputSchema();

        // 应返回不同实例
        assertNotSame(schema1, schema2);
        assertEquals(schema1, schema2);

        // 修改返回映射不得影响内部状态
        schema1.put("modified", true);
        Map<String, Object> schema3 = tool.getOutputSchema();
        assertFalse(schema3.containsKey("modified"));
    }

    @Test
    void testGetOutputSchema_Null() {
        McpTool tool = new McpTool("test-tool", "Description", parameters, mockClientWrapper);
        assertNull(tool.getOutputSchema());
    }

    // ==================== extractMcpMeta 测试 ====================

    @Test
    void testCallAsync_WithMcpMetaInRuntimeContext() {
        McpTool tool = new McpTool("test-tool", "Description", parameters, mockClientWrapper);

        McpSchema.TextContent resultContent = McpSchema.TextContent.builder("Success").build();
        McpSchema.CallToolResult mcpResult =
                McpSchema.CallToolResult.builder()
                        .content(List.of(resultContent))
                        .isError(false)
                        .build();

        when(mockClientWrapper.callTool(eq("test-tool"), any(), any()))
                .thenReturn(Mono.just(mcpResult));

        // 创建含 McpMeta 的 RuntimeContext
        io.agentscope.core.agent.RuntimeContext rtCtx =
                io.agentscope.core.agent.RuntimeContext.builder()
                        .put(McpMeta.class, new McpMeta(Map.of("traceId", "abc-123")))
                        .build();

        ToolResultBlock result =
                tool.callAsync(
                                ToolCallParam.builder()
                                        .input(new HashMap<>())
                                        .runtimeContext(rtCtx)
                                        .build())
                        .block();

        assertNotNull(result);
        verify(mockClientWrapper)
                .callTool(eq("test-tool"), any(), eq(Map.of("traceId", "abc-123")));
    }

    @Test
    void testCallAsync_WithEmptyMcpMeta() {
        McpTool tool = new McpTool("test-tool", "Description", parameters, mockClientWrapper);

        McpSchema.TextContent resultContent = McpSchema.TextContent.builder("Success").build();
        McpSchema.CallToolResult mcpResult =
                McpSchema.CallToolResult.builder()
                        .content(List.of(resultContent))
                        .isError(false)
                        .build();

        when(mockClientWrapper.callTool(eq("test-tool"), any(), any()))
                .thenReturn(Mono.just(mcpResult));

        // 创建含空 McpMeta 的 RuntimeContext
        io.agentscope.core.agent.RuntimeContext rtCtx =
                io.agentscope.core.agent.RuntimeContext.builder()
                        .put(McpMeta.class, new McpMeta(null))
                        .build();

        ToolResultBlock result =
                tool.callAsync(
                                ToolCallParam.builder()
                                        .input(new HashMap<>())
                                        .runtimeContext(rtCtx)
                                        .build())
                        .block();

        assertNotNull(result);
        // 应传入空 meta 映射
        verify(mockClientWrapper).callTool(eq("test-tool"), any(), eq(Collections.emptyMap()));
    }

    @Test
    void testCallAsync_WithNullRuntimeContext() {
        McpTool tool = new McpTool("test-tool", "Description", parameters, mockClientWrapper);

        McpSchema.TextContent resultContent = McpSchema.TextContent.builder("Success").build();
        McpSchema.CallToolResult mcpResult =
                McpSchema.CallToolResult.builder()
                        .content(List.of(resultContent))
                        .isError(false)
                        .build();

        when(mockClientWrapper.callTool(eq("test-tool"), any(), any()))
                .thenReturn(Mono.just(mcpResult));

        ToolResultBlock result =
                tool.callAsync(
                                ToolCallParam.builder()
                                        .input(new HashMap<>())
                                        .runtimeContext(null)
                                        .build())
                        .block();

        assertNotNull(result);
        verify(mockClientWrapper).callTool(eq("test-tool"), any(), eq(Collections.emptyMap()));
    }

    @Test
    void testCallAsync_WithNullToolCallParam() {
        McpTool tool = new McpTool("test-tool", "Description", parameters, mockClientWrapper);

        McpSchema.TextContent resultContent = McpSchema.TextContent.builder("Success").build();
        McpSchema.CallToolResult mcpResult =
                McpSchema.CallToolResult.builder()
                        .content(List.of(resultContent))
                        .isError(false)
                        .build();

        when(mockClientWrapper.callTool(eq("test-tool"), any(), any()))
                .thenReturn(Mono.just(mcpResult));

        // 应抛 NullPointerException 或平稳处理
        assertThrows(
                NullPointerException.class,
                () -> {
                    tool.callAsync(null).block();
                });
    }

    @Test
    void testCallAsync_ErrorWithIllegalArgumentException() {
        McpTool tool = new McpTool("test-tool", "Description", parameters, mockClientWrapper);

        when(mockClientWrapper.callTool(eq("test-tool"), any(), any()))
                .thenReturn(Mono.error(new IllegalArgumentException("Invalid argument")));

        ToolResultBlock result =
                tool.callAsync(ToolCallParam.builder().input(new HashMap<>()).build()).block();
        assertNotNull(result);
        String outputText = ((TextBlock) result.getOutput().get(0)).getText();
        assertTrue(outputText.contains("Invalid argument"));
    }

    @Test
    void testMergeArguments_PresetOnly_NoInput() {
        Map<String, Object> presetArgs = new HashMap<>();
        presetArgs.put("preset1", "value1");
        presetArgs.put("preset2", "value2");

        McpTool tool =
                new McpTool("test-tool", "Description", parameters, mockClientWrapper, presetArgs);

        McpSchema.TextContent resultContent = McpSchema.TextContent.builder("Success").build();
        McpSchema.CallToolResult mcpResult =
                McpSchema.CallToolResult.builder()
                        .content(List.of(resultContent))
                        .isError(false)
                        .build();

        when(mockClientWrapper.callTool(eq("test-tool"), any(), any()))
                .thenReturn(Mono.just(mcpResult));

        ToolResultBlock result = tool.callAsync(ToolCallParam.builder().build()).block();
        assertNotNull(result);
        // 仅应使用预设参数
        verify(mockClientWrapper).callTool(eq("test-tool"), eq(presetArgs), any());
    }

    @Test
    void testMergeArguments_NullInput_NoPresets() {
        McpTool tool = new McpTool("test-tool", "Description", parameters, mockClientWrapper);

        McpSchema.TextContent resultContent = McpSchema.TextContent.builder("Success").build();
        McpSchema.CallToolResult mcpResult =
                McpSchema.CallToolResult.builder()
                        .content(List.of(resultContent))
                        .isError(false)
                        .build();

        when(mockClientWrapper.callTool(eq("test-tool"), any(), any()))
                .thenReturn(Mono.just(mcpResult));

        ToolResultBlock result = tool.callAsync(ToolCallParam.builder().build()).block();
        assertNotNull(result);
        // 应传入空映射
        verify(mockClientWrapper).callTool(eq("test-tool"), eq(new HashMap<>()), any());
    }
}
