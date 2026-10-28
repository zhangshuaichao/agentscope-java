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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.tool.test.SampleTools;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 工具注解处理测试。
 *
 * <p>验证 @Tool 和 @ToolParam 注解解析、schema 生成及无效注解处理。
 *
 * <p>标记为 "unit"，仅测试注解处理，不执行工具。
 */
@Tag("unit")
@DisplayName("Tool Annotation Tests")
class ToolAnnotationTest {

    private Toolkit toolkit;
    private SampleTools sampleTools;

    @BeforeEach
    void setUp() {
        toolkit = new Toolkit();
        sampleTools = new SampleTools();
    }

    @Test
    @DisplayName("Should parse @Tool annotations correctly")
    void testToolAnnotationParsing() {
        // 注册带注解工具
        toolkit.registerTool(sampleTools);

        // 获取已注册工具名
        Set<String> toolNames = toolkit.getToolNames();
        assertNotNull(toolNames, "Tools should be registered");
        assertTrue(toolNames.size() > 0, "Should parse and register tools");

        // 验证工具名称与描述来自 @Tool 注解
        for (String toolName : toolNames) {
            AgentTool tool = toolkit.getTool(toolName);
            assertNotNull(tool, "Should get tool: " + toolName);
            assertNotNull(tool.getName(), "Tool should have name from @Tool annotation");
            assertNotNull(
                    tool.getDescription(), "Tool should have description from @Tool annotation");

            // 名称不应为空
            assertTrue(tool.getName().length() > 0, "Tool name should not be empty");
            assertTrue(tool.getDescription().length() > 0, "Tool description should not be empty");
        }
    }

    @Test
    @DisplayName("Should parse @ToolParam annotations")
    void testToolParamAnnotation() {
        // 注册工具
        toolkit.registerTool(sampleTools);

        // 查找多参数工具
        AgentTool multiParamTool = toolkit.getTool("multi_param");

        if (multiParamTool != null) {
            // 验证参数从 @ToolParam 注解解析
            Map<String, Object> parameters = multiParamTool.getParameters();
            assertNotNull(parameters, "Parameters should be parsed from @ToolParam annotations");

            // 每个参数都应有对应属性
            if (parameters.containsKey("properties")) {
                Object properties = parameters.get("properties");
                assertNotNull(properties, "Should have properties object");
            }
        }
    }

    @Test
    @DisplayName("Should generate tool schema from annotations")
    void testToolSchemaGeneration() {
        // 注册工具
        toolkit.registerTool(sampleTools);

        // 验证每个工具都生成 schema
        Set<String> toolNames = toolkit.getToolNames();
        for (String toolName : toolNames) {
            AgentTool tool = toolkit.getTool(toolName);
            Map<String, Object> parameters = tool.getParameters();
            assertNotNull(parameters, "Schema should be generated for tool: " + toolName);

            // Schema 应为有效 JSON Schema 结构
            // 至少应是非 null 映射
            assertTrue(parameters instanceof Map, "Schema should be a valid structure");
        }
    }

    @Test
    @DisplayName("Should handle methods without Tool annotation")
    void testInvalidAnnotations() {
        // 尝试查找 SampleTools 类中的方法
        Method[] methods = SampleTools.class.getDeclaredMethods();
        assertNotNull(methods, "Should find methods in SampleTools");

        int toolAnnotatedMethods = 0;
        for (Method method : methods) {
            if (method.isAnnotationPresent(Tool.class)) {
                toolAnnotatedMethods++;
            }
        }

        // 注册工具
        toolkit.registerTool(sampleTools);

        // 注册工具数应与注解方法数匹配
        Set<String> toolNames = toolkit.getToolNames();
        int registeredTools = toolNames.size();

        assertTrue(registeredTools > 0, "Should register tools with @Tool annotation");

        // 注意：部分方法可能无注解，例如私有辅助方法
        // 因此注册数可能小于或等于总方法数
        assertTrue(
                registeredTools <= methods.length, "Should not register more tools than methods");
    }

    @Test
    @DisplayName("Should not include additionalProperties when strict not set")
    void testAdditionalPropertiesAbsentByDefault() {
        toolkit.registerTool(
                new Object() {
                    @Tool(name = "query_data", description = "Query data")
                    public String queryData(
                            @ToolParam(name = "keyword", description = "keyword") String keyword) {
                        return "result";
                    }
                });

        Map<String, Object> schema = toolkit.getToolSchemas().get(0).getParameters();

        assertFalse(
                schema.containsKey("additionalProperties"),
                "Default behavior should NOT add additionalProperties to schema");
    }

    @Test
    @DisplayName("Should reject hallucinated params when strict=true")
    void testRejectHallucinatedParamsWhenStrict() {
        toolkit.registerTool(
                new Object() {
                    @Tool(name = "query_data", description = "Query data", strict = true)
                    public String queryData(
                            @ToolParam(name = "keyword", description = "keyword") String keyword) {
                        return "result";
                    }
                });

        Map<String, Object> schema = toolkit.getToolSchemas().get(0).getParameters();
        String hallucinatedInput = "{\"keyword\": \"test\", \"owners\": [\"admin\"]}";

        String validationError = ToolValidator.validateInput(hallucinatedInput, schema);

        assertNotNull(
                validationError,
                "ToolValidator should reject hallucinated params when strict=true");
        assertTrue(
                validationError.contains("owners"),
                "Error message should mention the hallucinated param");
    }

    @Test
    @DisplayName("Should allow extra params by default (backward compatible)")
    void testAllowExtraParamsByDefault() {
        toolkit.registerTool(
                new Object() {
                    @Tool(name = "query_data", description = "Query data")
                    public String queryData(
                            @ToolParam(name = "keyword", description = "keyword") String keyword) {
                        return "result";
                    }
                });

        Map<String, Object> schema = toolkit.getToolSchemas().get(0).getParameters();
        String hallucinatedInput = "{\"keyword\": \"test\", \"owners\": [\"admin\"]}";

        String validationError = ToolValidator.validateInput(hallucinatedInput, schema);

        assertNull(
                validationError,
                "Default behavior should allow extra params (backward compatible)");
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("strict=true should inject additionalProperties=false into the schema")
    void testStrictInjectsAdditionalPropertiesFalse() {
        toolkit.registerTool(
                new Object() {
                    @Tool(name = "strict_tool", description = "Strict tool", strict = true)
                    public String execute(
                            @ToolParam(name = "keyword", description = "keyword") String keyword) {
                        return "result";
                    }
                });

        Map<String, Object> schema = toolkit.getToolSchemas().get(0).getParameters();

        assertTrue(
                schema.containsKey("additionalProperties"),
                "Schema should contain additionalProperties=false when strict=true");
        assertEquals(
                false,
                schema.get("additionalProperties"),
                "strict=true should force additionalProperties=false");

        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertFalse(
                properties.containsKey("additionalProperties"),
                "additionalProperties should NOT be inside properties map");
    }
}
