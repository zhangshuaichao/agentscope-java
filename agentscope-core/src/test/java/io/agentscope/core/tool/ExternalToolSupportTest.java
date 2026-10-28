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

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ToolSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * Toolkit 外部工具支持单元测试。
 *
 * <p>验证仅含 schema 的工具注册与 isExternalTool 检测。
 */
@Tag("unit")
@DisplayName("Toolkit External Tool Support Tests")
class ExternalToolSupportTest {

    private Toolkit toolkit;

    @BeforeEach
    void setUp() {
        toolkit = new Toolkit();
    }

    @Test
    @DisplayName("Should register external tool via registerSchema")
    void testRegisterSchema() {
        ToolSchema schema =
                ToolSchema.builder()
                        .name("external_api")
                        .description("Call external API")
                        .parameters(
                                Map.of(
                                        "type",
                                        "object",
                                        "properties",
                                        Map.of("endpoint", Map.of("type", "string"))))
                        .build();

        toolkit.registerSchema(schema);

        // 验证工具已注册
        AgentTool tool = toolkit.getTool("external_api");
        assertNotNull(tool);
        assertTrue(tool instanceof SchemaOnlyTool);
        assertEquals("external_api", tool.getName());
        assertEquals("Call external API", tool.getDescription());
    }

    @Test
    @DisplayName("Should register multiple external tools via registerSchemas")
    void testRegisterSchemas() {
        List<ToolSchema> schemas =
                List.of(
                        ToolSchema.builder()
                                .name("tool_a")
                                .description("Tool A")
                                .parameters(Map.of("type", "object"))
                                .build(),
                        ToolSchema.builder()
                                .name("tool_b")
                                .description("Tool B")
                                .parameters(Map.of("type", "object"))
                                .build());

        toolkit.registerSchemas(schemas);

        assertNotNull(toolkit.getTool("tool_a"));
        assertNotNull(toolkit.getTool("tool_b"));
        assertTrue(toolkit.isExternalTool("tool_a"));
        assertTrue(toolkit.isExternalTool("tool_b"));
    }

    @Test
    @DisplayName("Should identify external tool via isExternalTool")
    void testIsExternalTool() {
        // 注册外部工具
        ToolSchema externalSchema =
                ToolSchema.builder()
                        .name("external_tool")
                        .description("External")
                        .parameters(Map.of("type", "object"))
                        .build();
        toolkit.registerSchema(externalSchema);

        // 注册内部工具
        toolkit.registerAgentTool(
                new AgentTool() {
                    @Override
                    public String getName() {
                        return "internal_tool";
                    }

                    @Override
                    public String getDescription() {
                        return "Internal";
                    }

                    @Override
                    public Map<String, Object> getParameters() {
                        return Map.of("type", "object");
                    }

                    @Override
                    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                        return Mono.just(ToolResultBlock.text("result"));
                    }
                });

        assertTrue(toolkit.isExternalTool("external_tool"));
        assertFalse(toolkit.isExternalTool("internal_tool"));
        assertFalse(toolkit.isExternalTool("nonexistent_tool"));
    }

    @Test
    @DisplayName("Should include external tools in tool schemas")
    void testExternalToolInSchemas() {
        ToolSchema schema =
                ToolSchema.builder()
                        .name("db_query")
                        .description("Query database")
                        .parameters(
                                Map.of(
                                        "type",
                                        "object",
                                        "properties",
                                        Map.of("sql", Map.of("type", "string")),
                                        "required",
                                        List.of("sql")))
                        .build();

        toolkit.registerSchema(schema);

        List<ToolSchema> schemas = toolkit.getToolSchemas();
        assertEquals(1, schemas.size());
        assertEquals("db_query", schemas.get(0).getName());
    }

    @Test
    @DisplayName("Should preserve strict when registering external schema")
    void testExternalSchemaStrictPreserved() {
        ToolSchema strictSchema =
                ToolSchema.builder()
                        .name("strict_tool")
                        .description("Strict external tool")
                        .parameters(Map.of("type", "object"))
                        .strict(true)
                        .build();

        toolkit.registerSchema(strictSchema);

        List<ToolSchema> schemas = toolkit.getToolSchemas();
        assertEquals(1, schemas.size());
        assertEquals(Boolean.TRUE, schemas.get(0).getStrict());
    }

    @Test
    @DisplayName("Should keep strict as null when schema strict is not set")
    void testExternalSchemaStrictNullPreserved() {
        ToolSchema schema =
                ToolSchema.builder()
                        .name("null_strict_tool")
                        .description("Null strict external tool")
                        .parameters(Map.of("type", "object"))
                        .build();

        toolkit.registerSchema(schema);

        List<ToolSchema> schemas = toolkit.getToolSchemas();
        assertEquals(1, schemas.size());
        assertNull(schemas.get(0).getStrict());
    }

    @Test
    @DisplayName("Should preserve deferLoading when registering external schema")
    void testExternalSchemaDeferLoadingPreserved() {
        ToolSchema deferredSchema =
                ToolSchema.builder()
                        .name("deferred_tool")
                        .description("Deferred external tool")
                        .parameters(Map.of("type", "object"))
                        .deferLoading(true)
                        .build();

        toolkit.registerSchema(deferredSchema);

        List<ToolSchema> schemas = toolkit.getToolSchemas();
        assertEquals(1, schemas.size());
        assertEquals(Boolean.TRUE, schemas.get(0).getDeferLoading());
    }

    @Test
    @DisplayName("Should handle null schemas list gracefully")
    void testRegisterSchemasNull() {
        // 不应抛异常
        toolkit.registerSchemas(null);
        assertTrue(toolkit.getToolNames().isEmpty());
    }

    @Test
    @DisplayName("Should handle empty schemas list")
    void testRegisterSchemasEmpty() {
        toolkit.registerSchemas(List.of());
        assertTrue(toolkit.getToolNames().isEmpty());
    }

    @Test
    @DisplayName("Should mix external and internal tools")
    void testMixedTools() {
        // 注册外部工具
        toolkit.registerSchema(
                ToolSchema.builder()
                        .name("external")
                        .description("External tool")
                        .parameters(Map.of("type", "object"))
                        .build());

        // 通过注解注册内部工具
        toolkit.registerTool(new InternalToolExample());

        // 验证二者均已注册
        assertEquals(2, toolkit.getToolNames().size());
        assertTrue(toolkit.isExternalTool("external"));
        assertFalse(toolkit.isExternalTool("calculator"));

        // 验证 schema 包含二者
        List<ToolSchema> schemas = toolkit.getToolSchemas();
        assertEquals(2, schemas.size());
    }

    @Test
    @DisplayName("Should produce suspended result when executing SchemaOnlyTool via Toolkit")
    void testSchemaOnlyToolExecutionReturnsSuspended() {
        ToolSchema schema =
                ToolSchema.builder()
                        .name("db_query")
                        .description("Query database")
                        .parameters(
                                Map.of(
                                        "type",
                                        "object",
                                        "properties",
                                        Map.of("sql", Map.of("type", "string")),
                                        "required",
                                        List.of("sql")))
                        .build();
        toolkit.registerSchema(schema);

        Map<String, Object> input = Map.of("sql", "SELECT 1");
        ToolUseBlock toolCall =
                ToolUseBlock.builder()
                        .id("call-ext-1")
                        .name("db_query")
                        .input(input)
                        .content(io.agentscope.core.util.JsonUtils.getJsonCodec().toJson(input))
                        .build();

        List<ToolResultBlock> results =
                toolkit.callTools(List.of(toolCall), null, null, null)
                        .block(java.time.Duration.ofSeconds(3));

        assertNotNull(results);
        assertEquals(1, results.size());
        assertTrue(results.get(0).isSuspended(), "External tool result should be suspended");
        assertEquals("call-ext-1", results.get(0).getId());
        assertEquals("db_query", results.get(0).getName());
    }

    @Test
    @DisplayName(
            "Should produce suspended result when executing @Tool(externalTool=true) via Toolkit")
    void testAnnotationExternalToolExecutionReturnsSuspended() {
        toolkit.registerTool(new AnnotationExternalToolExample());

        Map<String, Object> input = Map.of("endpoint", "/api/test");
        ToolUseBlock toolCall =
                ToolUseBlock.builder()
                        .id("call-ext-2")
                        .name("call_api")
                        .input(input)
                        .content(io.agentscope.core.util.JsonUtils.getJsonCodec().toJson(input))
                        .build();

        List<ToolResultBlock> results =
                toolkit.callTools(List.of(toolCall), null, null, null)
                        .block(java.time.Duration.ofSeconds(3));

        assertNotNull(results);
        assertEquals(1, results.size());
        assertTrue(results.get(0).isSuspended(), "External tool result should be suspended");
        assertEquals("call-ext-2", results.get(0).getId());
    }

    static class AnnotationExternalToolExample {
        @Tool(name = "call_api", description = "Call external API", externalTool = true)
        public String callApi(@ToolParam(name = "endpoint") String endpoint) {
            throw new AssertionError("External tool body should never be invoked");
        }
    }

    /**
     * 用于测试的内部工具示例。
     */
    static class InternalToolExample {
        @Tool(name = "calculator", description = "Calculate expression")
        public String calculate(@ToolParam(name = "expression") String expression) {
            return "result";
        }
    }

    static class StrictInternalToolExample {
        @Tool(name = "strict_calculator", description = "Calculate expression", strict = true)
        public String calculate(@ToolParam(name = "expression") String expression) {
            return "result";
        }
    }

    static class DeferredInternalToolExample {
        @Tool(
                name = "deferred_calculator",
                description = "Calculate expression",
                deferLoading = true)
        public String calculate(@ToolParam(name = "expression") String expression) {
            return "result";
        }
    }

    @Test
    @DisplayName("Should propagate strict from @Tool annotation")
    void testAnnotationStrictPropagation() {
        toolkit.registerTool(new StrictInternalToolExample());

        List<ToolSchema> schemas = toolkit.getToolSchemas();
        assertEquals(1, schemas.size());
        assertEquals("strict_calculator", schemas.get(0).getName());
        assertEquals(Boolean.TRUE, schemas.get(0).getStrict());
    }

    @Test
    @DisplayName("Should propagate deferLoading from @Tool annotation")
    void testAnnotationDeferLoadingPropagation() {
        toolkit.registerTool(new DeferredInternalToolExample());

        List<ToolSchema> schemas = toolkit.getToolSchemas();
        assertEquals(1, schemas.size());
        assertEquals("deferred_calculator", schemas.get(0).getName());
        assertEquals(Boolean.TRUE, schemas.get(0).getDeferLoading());
    }
}
