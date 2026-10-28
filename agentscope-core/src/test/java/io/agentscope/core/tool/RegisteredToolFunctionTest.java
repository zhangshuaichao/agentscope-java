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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.ToolResultBlock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class RegisteredToolFunctionTest {

    private AgentTool mockTool;

    @BeforeEach
    void setUp() {
        mockTool = createMockTool();
    }

    private AgentTool createMockTool() {
        return new AgentTool() {
            @Override
            public String getName() {
                return "testTool";
            }

            @Override
            public String getDescription() {
                return "Test description";
            }

            @Override
            public Map<String, Object> getParameters() {
                Map<String, Object> schema = new HashMap<>();
                schema.put("type", "object");

                Map<String, Object> properties = new HashMap<>();
                Map<String, Object> param1 = new HashMap<>();
                param1.put("type", "string");
                properties.put("param1", param1);

                schema.put("properties", properties);
                schema.put("required", List.of("param1"));
                return schema;
            }

            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                return Mono.just(ToolResultBlock.text("result"));
            }
        };
    }

    @Test
    void testConstructorWithAllParameters() {
        // 准备
        ExtendedModel extendedModel = new SimpleExtendedModel(Map.of(), List.of());

        // 执行
        RegisteredToolFunction registered =
                new RegisteredToolFunction(mockTool, extendedModel, "mcpClient1");

        // 断言
        assertEquals(mockTool, registered.getTool());
        assertEquals(extendedModel, registered.getExtendedModel());
        assertEquals("mcpClient1", registered.getMcpClientName());
    }

    @Test
    void testConstructorWithNullOptionalParameters() {
        // 执行
        RegisteredToolFunction registered = new RegisteredToolFunction(mockTool, null, null);

        // 断言
        assertEquals(mockTool, registered.getTool());
        assertNull(registered.getExtendedModel());
        assertNull(registered.getMcpClientName());
    }

    @Test
    void testGetExtendedParametersWithoutExtendedModel() {
        // 准备
        RegisteredToolFunction registered = new RegisteredToolFunction(mockTool, null, null);

        // 执行
        Map<String, Object> params = registered.getExtendedParameters();

        // 断言
        assertEquals(mockTool.getParameters(), params);
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) params.get("properties");
        assertTrue(properties.containsKey("param1"));
    }

    @Test
    void testGetExtendedParametersWithExtendedModel() {
        // 准备
        Map<String, Object> additionalProps = new HashMap<>();
        Map<String, Object> param2Schema = new HashMap<>();
        param2Schema.put("type", "integer");
        additionalProps.put("param2", param2Schema);

        ExtendedModel extendedModel = new SimpleExtendedModel(additionalProps, List.of("param2"));

        RegisteredToolFunction registered =
                new RegisteredToolFunction(mockTool, extendedModel, null);

        // 执行
        Map<String, Object> merged = registered.getExtendedParameters();

        // 断言
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) merged.get("properties");
        assertTrue(properties.containsKey("param1"), "Should contain base property");
        assertTrue(properties.containsKey("param2"), "Should contain extended property");

        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) merged.get("required");
        assertTrue(required.contains("param1"), "Should contain base required");
        assertTrue(required.contains("param2"), "Should contain extended required");
    }

    @Test
    void testExtendedModelConflictDetection() {
        // 准备：创建带冲突属性的扩展模型
        Map<String, Object> conflictingProps = new HashMap<>();
        Map<String, Object> param1Schema = new HashMap<>();
        param1Schema.put("type", "integer"); // 与基础 param1 冲突
        conflictingProps.put("param1", param1Schema);

        ExtendedModel extendedModel = new SimpleExtendedModel(conflictingProps, List.of());

        RegisteredToolFunction registered =
                new RegisteredToolFunction(mockTool, extendedModel, null);

        // 执行并断言
        IllegalStateException exception =
                assertThrows(IllegalStateException.class, () -> registered.getExtendedParameters());

        assertTrue(exception.getMessage().contains("conflicting properties"));
        assertTrue(exception.getMessage().contains("param1"));
    }

    @Test
    void testSimpleExtendedModelGetters() {
        // 准备
        Map<String, Object> props = Map.of("key", "value");
        List<String> required = List.of("field1", "field2");

        // 执行
        SimpleExtendedModel extendedModel = new SimpleExtendedModel(props, required);

        // 断言
        assertEquals(props, extendedModel.getAdditionalProperties());
        assertEquals(required, extendedModel.getAdditionalRequired());
    }

    @Test
    void testMergeWithBaseSchemaEmptyExtension() {
        // 准备
        ExtendedModel extendedModel = new SimpleExtendedModel(Map.of(), List.of());

        // 执行
        Map<String, Object> merged = extendedModel.mergeWithBaseSchema(mockTool.getParameters());

        // 断言
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) merged.get("properties");
        assertEquals(1, properties.size());
        assertTrue(properties.containsKey("param1"));
    }

    @Test
    void testMergeWithBaseSchemaEmptyBase() {
        // 准备
        Map<String, Object> additionalProps = new HashMap<>();
        Map<String, Object> param2Schema = new HashMap<>();
        param2Schema.put("type", "string");
        additionalProps.put("param2", param2Schema);

        ExtendedModel extendedModel = new SimpleExtendedModel(additionalProps, List.of("param2"));

        Map<String, Object> emptyBase = new HashMap<>();
        emptyBase.put("type", "object");

        // 执行
        Map<String, Object> merged = extendedModel.mergeWithBaseSchema(emptyBase);

        // 断言
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) merged.get("properties");
        assertEquals(1, properties.size());
        assertTrue(properties.containsKey("param2"));

        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) merged.get("required");
        assertTrue(required.contains("param2"));
    }

    @Test
    void testMergeWithBaseSchemaNoConflict() {
        // 准备
        Map<String, Object> additionalProps = new HashMap<>();
        Map<String, Object> param2Schema = new HashMap<>();
        param2Schema.put("type", "integer");
        additionalProps.put("param2", param2Schema);

        Map<String, Object> param3Schema = new HashMap<>();
        param3Schema.put("type", "boolean");
        additionalProps.put("param3", param3Schema);

        ExtendedModel extendedModel =
                new SimpleExtendedModel(additionalProps, List.of("param2", "param3"));

        // 执行
        Map<String, Object> merged = extendedModel.mergeWithBaseSchema(mockTool.getParameters());

        // 断言
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) merged.get("properties");
        assertEquals(3, properties.size());
        assertTrue(properties.containsKey("param1"));
        assertTrue(properties.containsKey("param2"));
        assertTrue(properties.containsKey("param3"));

        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) merged.get("required");
        assertEquals(3, required.size());
        assertTrue(required.contains("param1"));
        assertTrue(required.contains("param2"));
        assertTrue(required.contains("param3"));
    }

    @Test
    void testMergeWithMultipleConflicts() {
        // 准备
        Map<String, Object> conflictingProps = new HashMap<>();
        Map<String, Object> param1Schema = new HashMap<>();
        param1Schema.put("type", "integer");
        conflictingProps.put("param1", param1Schema);

        // 向基础 schema 添加 param2，产生额外冲突
        AgentTool toolWithMultipleParams =
                new AgentTool() {
                    @Override
                    public String getName() {
                        return "testTool";
                    }

                    @Override
                    public String getDescription() {
                        return "Test";
                    }

                    @Override
                    public Map<String, Object> getParameters() {
                        Map<String, Object> schema = new HashMap<>();
                        Map<String, Object> properties = new HashMap<>();
                        properties.put("param1", Map.of("type", "string"));
                        properties.put("param2", Map.of("type", "string"));
                        schema.put("properties", properties);
                        return schema;
                    }

                    @Override
                    public Mono<ToolResultBlock> callAsync(ToolCallParam input) {
                        return Mono.just(ToolResultBlock.text("result"));
                    }
                };

        conflictingProps.put("param2", Map.of("type", "integer"));

        ExtendedModel extendedModel = new SimpleExtendedModel(conflictingProps, List.of());

        // 执行并断言
        IllegalStateException exception =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                extendedModel.mergeWithBaseSchema(
                                        toolWithMultipleParams.getParameters()));

        assertTrue(exception.getMessage().contains("param1"));
        assertTrue(exception.getMessage().contains("param2"));
    }

    @Test
    void testMcpTool() {
        // 执行
        RegisteredToolFunction registered =
                new RegisteredToolFunction(mockTool, null, "filesystem");

        // 断言
        assertEquals("filesystem", registered.getMcpClientName());
    }

    @Test
    void testUngroupedNonMcpTool() {
        // 执行
        RegisteredToolFunction registered = new RegisteredToolFunction(mockTool, null, null);

        // 断言
        assertNull(registered.getMcpClientName());
    }

    @Test
    void testMergePreservesOtherBaseSchemaFields() {
        // 准备
        Map<String, Object> baseSchema = new HashMap<>(mockTool.getParameters());
        baseSchema.put("additionalProperties", false);
        baseSchema.put("title", "Base Schema");

        ExtendedModel extendedModel =
                new SimpleExtendedModel(Map.of("param2", Map.of("type", "string")), List.of());

        // 执行
        Map<String, Object> merged = extendedModel.mergeWithBaseSchema(baseSchema);

        // 断言
        assertEquals(false, merged.get("additionalProperties"));
        assertEquals("Base Schema", merged.get("title"));
    }
}
