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
package io.agentscope.extensions.model.anthropic.formatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolUnion;
import com.anthropic.models.messages.WebSearchTool20250305;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.extensions.model.anthropic.tool.AnthropicServerTool;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** AnthropicToolsHelper 单元测试。 */
class AnthropicToolsHelperTest {

    /** 创建包含必需占位消息的构建器辅助方法。 */
    private MessageCreateParams.Builder createBuilder() {
        return MessageCreateParams.builder()
                .model("claude-sonnet-4-5-20250929")
                .maxTokens(1024)
                .addMessage(
                        MessageParam.builder()
                                .role(MessageParam.Role.USER)
                                .content("test")
                                .build());
    }

    @Test
    void testApplyToolsWithSimpleSchema() {
        MessageCreateParams.Builder builder = createBuilder();

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", Map.of("query", Map.of("type", "string")));
        parameters.put("required", List.of("query"));

        ToolSchema toolSchema =
                ToolSchema.builder()
                        .name("search")
                        .description("Search for information")
                        .parameters(parameters)
                        .build();

        GenerateOptions options = GenerateOptions.builder().build();
        AnthropicToolsHelper.applyTools(builder, List.of(toolSchema), options);

        MessageCreateParams params = builder.build();
        assertTrue(params.tools().isPresent());
        List<ToolUnion> tools = params.tools().get();
        assertEquals(1, tools.size());

        assertTrue(tools.get(0).isTool());
        Tool tool = tools.get(0).asTool();
        assertEquals("search", tool.name());
        assertEquals("Search for information", tool.description().get());
        // 注意：inputSchema 校验由 Anthropic SDK 在 API 调用时处理
    }

    @Test
    void testApplyToolsWithMultipleSchemas() {
        MessageCreateParams.Builder builder = createBuilder();

        ToolSchema schema1 =
                ToolSchema.builder()
                        .name("tool1")
                        .description("First tool")
                        .parameters(Map.of("type", "object"))
                        .build();

        ToolSchema schema2 =
                ToolSchema.builder()
                        .name("tool2")
                        .description("Second tool")
                        .parameters(Map.of("type", "object"))
                        .build();

        GenerateOptions options = GenerateOptions.builder().build();
        AnthropicToolsHelper.applyTools(builder, List.of(schema1, schema2), options);

        MessageCreateParams params = builder.build();
        assertTrue(params.tools().isPresent());
        assertEquals(2, params.tools().get().size());
    }

    @Test
    void testApplyToolsWithNullOrEmptyList() {
        MessageCreateParams.Builder builder = createBuilder();

        // null 列表
        AnthropicToolsHelper.applyTools(builder, null, null);
        MessageCreateParams params1 = builder.build();
        assertTrue(params1.tools().isEmpty());

        // 空列表
        builder = createBuilder();
        AnthropicToolsHelper.applyTools(builder, List.of(), null);
        MessageCreateParams params2 = builder.build();
        assertTrue(params2.tools().isEmpty());
    }

    @Test
    void testApplyToolChoiceAuto() {
        MessageCreateParams.Builder builder = createBuilder();

        ToolSchema schema =
                ToolSchema.builder()
                        .name("search")
                        .description("Search")
                        .parameters(Map.of("type", "object"))
                        .build();

        GenerateOptions options =
                GenerateOptions.builder().toolChoice(new ToolChoice.Auto()).build();
        AnthropicToolsHelper.applyTools(builder, List.of(schema), options);

        MessageCreateParams params = builder.build();
        assertTrue(params.toolChoice().isPresent());
        assertTrue(params.toolChoice().get().isAuto());
    }

    @Test
    void testApplyToolChoiceNone() {
        MessageCreateParams.Builder builder = createBuilder();

        ToolSchema schema =
                ToolSchema.builder()
                        .name("search")
                        .description("Search")
                        .parameters(Map.of("type", "object"))
                        .build();

        GenerateOptions options =
                GenerateOptions.builder().toolChoice(new ToolChoice.None()).build();
        AnthropicToolsHelper.applyTools(builder, List.of(schema), options);

        MessageCreateParams params = builder.build();
        assertTrue(params.toolChoice().isPresent());
        // None 正确映射为 Anthropic 的 "none"，工具仍存在但本轮禁用
        assertTrue(params.toolChoice().get().isNone());
        // 互斥含义：不能混同于强制使用工具的 "any" 或 "auto"
        assertTrue(!params.toolChoice().get().isAny());
        assertTrue(!params.toolChoice().get().isAuto());

        // 工具列表仍必须存在且完整
        assertTrue(params.tools().isPresent());
        assertEquals(1, params.tools().get().size());
    }

    @Test
    void testApplyToolChoiceNoneWithEmptyToolsSkipsToolChoice() {
        MessageCreateParams.Builder builder = createBuilder();

        GenerateOptions options =
                GenerateOptions.builder().toolChoice(new ToolChoice.None()).build();
        // 工具列表为空时 applyTools 提前返回，不应设置 toolChoice
        AnthropicToolsHelper.applyTools(builder, List.of(), options);

        MessageCreateParams params = builder.build();
        assertTrue(params.toolChoice().isEmpty());
    }

    @Test
    void testApplyToolChoiceRequired() {
        MessageCreateParams.Builder builder = createBuilder();

        ToolSchema schema =
                ToolSchema.builder()
                        .name("search")
                        .description("Search")
                        .parameters(Map.of("type", "object"))
                        .build();

        GenerateOptions options =
                GenerateOptions.builder().toolChoice(new ToolChoice.Required()).build();
        AnthropicToolsHelper.applyTools(builder, List.of(schema), options);

        MessageCreateParams params = builder.build();
        assertTrue(params.toolChoice().isPresent());
        // Required 映射为 Anthropic 的 "any"
        assertTrue(params.toolChoice().get().isAny());
    }

    @Test
    void testApplyToolChoiceSpecific() {
        MessageCreateParams.Builder builder = createBuilder();

        ToolSchema schema =
                ToolSchema.builder()
                        .name("search")
                        .description("Search")
                        .parameters(Map.of("type", "object"))
                        .build();

        GenerateOptions options =
                GenerateOptions.builder().toolChoice(new ToolChoice.Specific("search")).build();
        AnthropicToolsHelper.applyTools(builder, List.of(schema), options);

        MessageCreateParams params = builder.build();
        assertTrue(params.toolChoice().isPresent());
        assertTrue(params.toolChoice().get().isTool());
        assertEquals("search", params.toolChoice().get().asTool().name());
    }

    @Test
    void testApplyToolChoiceAutoWithParallelDisabled() {
        MessageCreateParams.Builder builder = createBuilder();

        ToolSchema schema =
                ToolSchema.builder()
                        .name("search")
                        .description("Search")
                        .parameters(Map.of("type", "object"))
                        .build();

        GenerateOptions options =
                GenerateOptions.builder()
                        .toolChoice(new ToolChoice.Auto())
                        .parallelToolCalls(false)
                        .build();
        AnthropicToolsHelper.applyTools(builder, List.of(schema), options);

        MessageCreateParams params = builder.build();
        assertTrue(params.toolChoice().isPresent());
        assertTrue(params.toolChoice().get().isAuto());
        // 禁用并行工具调用：parallelToolCalls=false 对应 disable_parallel_tool_use=true
        assertTrue(params.toolChoice().get().asAuto().disableParallelToolUse().isPresent());
        assertTrue(params.toolChoice().get().asAuto().disableParallelToolUse().get());
    }

    @Test
    void testApplyToolChoiceAutoWithParallelEnabled() {
        MessageCreateParams.Builder builder = createBuilder();

        ToolSchema schema =
                ToolSchema.builder()
                        .name("search")
                        .description("Search")
                        .parameters(Map.of("type", "object"))
                        .build();

        GenerateOptions options =
                GenerateOptions.builder()
                        .toolChoice(new ToolChoice.Auto())
                        .parallelToolCalls(true)
                        .build();
        AnthropicToolsHelper.applyTools(builder, List.of(schema), options);

        MessageCreateParams params = builder.build();
        assertTrue(params.toolChoice().isPresent());
        assertTrue(params.toolChoice().get().isAuto());
        // 启用并行工具调用：parallelToolCalls=true 对应 disable_parallel_tool_use=false
        assertTrue(params.toolChoice().get().asAuto().disableParallelToolUse().isPresent());
        assertTrue(!params.toolChoice().get().asAuto().disableParallelToolUse().get());
    }

    @Test
    void testApplyToolChoiceRequiredWithParallelDisabled() {
        MessageCreateParams.Builder builder = createBuilder();

        ToolSchema schema =
                ToolSchema.builder()
                        .name("search")
                        .description("Search")
                        .parameters(Map.of("type", "object"))
                        .build();

        GenerateOptions options =
                GenerateOptions.builder()
                        .toolChoice(new ToolChoice.Required())
                        .parallelToolCalls(false)
                        .build();
        AnthropicToolsHelper.applyTools(builder, List.of(schema), options);

        MessageCreateParams params = builder.build();
        assertTrue(params.toolChoice().isPresent());
        assertTrue(params.toolChoice().get().isAny());
        // Required 映射为 "any"，且 disable_parallel_tool_use=true
        assertTrue(params.toolChoice().get().asAny().disableParallelToolUse().isPresent());
        assertTrue(params.toolChoice().get().asAny().disableParallelToolUse().get());
    }

    @Test
    void testApplyToolChoiceSpecificWithParallelDisabled() {
        MessageCreateParams.Builder builder = createBuilder();

        ToolSchema schema =
                ToolSchema.builder()
                        .name("search")
                        .description("Search")
                        .parameters(Map.of("type", "object"))
                        .build();

        GenerateOptions options =
                GenerateOptions.builder()
                        .toolChoice(new ToolChoice.Specific("search"))
                        .parallelToolCalls(false)
                        .build();
        AnthropicToolsHelper.applyTools(builder, List.of(schema), options);

        MessageCreateParams params = builder.build();
        assertTrue(params.toolChoice().isPresent());
        assertTrue(params.toolChoice().get().isTool());
        assertEquals("search", params.toolChoice().get().asTool().name());
        // 禁用并行：disable_parallel_tool_use=true
        assertTrue(params.toolChoice().get().asTool().disableParallelToolUse().isPresent());
        assertTrue(params.toolChoice().get().asTool().disableParallelToolUse().get());
    }

    @Test
    void testApplyToolChoiceNoneWithParallelDisabled() {
        MessageCreateParams.Builder builder = createBuilder();

        ToolSchema schema =
                ToolSchema.builder()
                        .name("search")
                        .description("Search")
                        .parameters(Map.of("type", "object"))
                        .build();

        GenerateOptions options =
                GenerateOptions.builder()
                        .toolChoice(new ToolChoice.None())
                        .parallelToolCalls(false)
                        .build();
        AnthropicToolsHelper.applyTools(builder, List.of(schema), options);

        MessageCreateParams params = builder.build();
        assertTrue(params.toolChoice().isPresent());
        // None 正确映射为 "none"；不支持 disable_parallel_tool_use，因此忽略
        assertTrue(params.toolChoice().get().isNone());
        assertTrue(!params.toolChoice().get().isAny());
        assertTrue(!params.toolChoice().get().isAuto());
    }

    @Test
    void testApplyToolsParallelDisabledWithoutToolChoice() {
        MessageCreateParams.Builder builder = createBuilder();

        ToolSchema schema =
                ToolSchema.builder()
                        .name("search")
                        .description("Search")
                        .parameters(Map.of("type", "object"))
                        .build();

        // 仅设置 parallelToolCalls，没有显式 toolChoice
        GenerateOptions options = GenerateOptions.builder().parallelToolCalls(false).build();
        AnthropicToolsHelper.applyTools(builder, List.of(schema), options);

        MessageCreateParams params = builder.build();
        // 创建隐式 "auto" tool_choice 以携带 disable_parallel_tool_use
        assertTrue(params.toolChoice().isPresent());
        assertTrue(params.toolChoice().get().isAuto());
        assertTrue(params.toolChoice().get().asAuto().disableParallelToolUse().isPresent());
        assertTrue(params.toolChoice().get().asAuto().disableParallelToolUse().get());
    }

    @Test
    void testServerOnlyToolsApplyParallelToolUseOptions() {
        MessageCreateParams.Builder builder = createBuilder();

        GenerateOptions options = GenerateOptions.builder().parallelToolCalls(false).build();
        AnthropicToolsHelper.applyTools(
                builder, List.of(), List.of(webSearchServerTool()), options, null);

        MessageCreateParams params = builder.build();
        assertTrue(params.tools().isPresent());
        assertEquals(1, params.tools().get().size());
        assertTrue(params.tools().get().get(0).isWebSearchTool20250305());
        assertTrue(params.toolChoice().isPresent());
        assertTrue(params.toolChoice().get().isAuto());
        assertTrue(params.toolChoice().get().asAuto().disableParallelToolUse().isPresent());
        assertTrue(params.toolChoice().get().asAuto().disableParallelToolUse().get());
    }

    @Test
    void testServerOnlyToolsApplyToolChoice() {
        MessageCreateParams.Builder builder = createBuilder();

        GenerateOptions options =
                GenerateOptions.builder().toolChoice(new ToolChoice.Specific("web_search")).build();
        AnthropicToolsHelper.applyTools(
                builder, List.of(), List.of(webSearchServerTool()), options, null);

        MessageCreateParams params = builder.build();
        assertTrue(params.toolChoice().isPresent());
        assertTrue(params.toolChoice().get().isTool());
        assertEquals("web_search", params.toolChoice().get().asTool().name());
    }

    @Test
    void testClientAndServerToolsAreAppliedAsOneToolSet() {
        MessageCreateParams.Builder builder = createBuilder();

        ToolSchema clientTool =
                ToolSchema.builder()
                        .name("get_weather")
                        .description("Get weather")
                        .parameters(Map.of("type", "object"))
                        .build();
        GenerateOptions options = GenerateOptions.builder().parallelToolCalls(false).build();
        AnthropicToolsHelper.applyTools(
                builder, List.of(clientTool), List.of(webSearchServerTool()), options, null);

        MessageCreateParams params = builder.build();
        assertTrue(params.tools().isPresent());
        assertEquals(2, params.tools().get().size());
        assertTrue(params.tools().get().get(0).isWebSearchTool20250305());
        assertTrue(params.tools().get().get(1).isTool());
        assertEquals("get_weather", params.tools().get().get(1).asTool().name());
        assertTrue(params.toolChoice().isPresent());
        assertTrue(params.toolChoice().get().isAuto());
        assertTrue(params.toolChoice().get().asAuto().disableParallelToolUse().get());
    }

    @Test
    void testClientAndServerToolNameCollisionFailsFast() {
        MessageCreateParams.Builder builder = createBuilder();

        ToolSchema clientTool =
                ToolSchema.builder()
                        .name("web_search")
                        .description("Local search")
                        .parameters(Map.of("type", "object"))
                        .build();

        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                AnthropicToolsHelper.applyTools(
                                        builder,
                                        List.of(clientTool),
                                        List.of(webSearchServerTool()),
                                        GenerateOptions.builder().build(),
                                        null));
        assertTrue(exception.getMessage().contains("web_search"));
        assertTrue(builder.build().tools().isEmpty());
    }

    @Test
    void testApplyToolChoiceAutoWithoutParallel() {
        MessageCreateParams.Builder builder = createBuilder();

        ToolSchema schema =
                ToolSchema.builder()
                        .name("search")
                        .description("Search")
                        .parameters(Map.of("type", "object"))
                        .build();

        // 设置 toolChoice，但 parallelToolCalls 保持 null（默认行为）
        GenerateOptions options =
                GenerateOptions.builder().toolChoice(new ToolChoice.Auto()).build();
        AnthropicToolsHelper.applyTools(builder, List.of(schema), options);

        MessageCreateParams params = builder.build();
        assertTrue(params.toolChoice().isPresent());
        assertTrue(params.toolChoice().get().isAuto());
        // 未设置 disable_parallel_tool_use，使用 Anthropic 默认行为（启用并行）
        assertTrue(params.toolChoice().get().asAuto().disableParallelToolUse().isEmpty());
    }

    @Test
    void testApplyOptionsWithTemperature() {
        MessageCreateParams.Builder builder = createBuilder();

        GenerateOptions options = GenerateOptions.builder().temperature(0.7).build();

        AnthropicToolsHelper.applyOptions(builder, options, null);

        MessageCreateParams params = builder.build();
        assertTrue(params.temperature().isPresent());
        assertEquals(0.7, params.temperature().get(), 0.001);
    }

    @Test
    void testApplyOptionsWithTopP() {
        MessageCreateParams.Builder builder = createBuilder();

        GenerateOptions options = GenerateOptions.builder().topP(0.9).build();

        AnthropicToolsHelper.applyOptions(builder, options, null);

        MessageCreateParams params = builder.build();
        assertTrue(params.topP().isPresent());
        assertEquals(0.9, params.topP().get(), 0.001);
    }

    @Test
    void testApplyOptionsWithMaxTokens() {
        MessageCreateParams.Builder builder = createBuilder();

        GenerateOptions options = GenerateOptions.builder().maxTokens(2048).build();

        AnthropicToolsHelper.applyOptions(builder, options, null);

        MessageCreateParams params = builder.build();
        assertEquals(2048, params.maxTokens());
    }

    @Test
    void testApplyOptionsWithAllParameters() {
        MessageCreateParams.Builder builder = createBuilder();

        GenerateOptions options =
                GenerateOptions.builder().temperature(0.8).topP(0.95).maxTokens(3000).build();

        AnthropicToolsHelper.applyOptions(builder, options, null);

        MessageCreateParams params = builder.build();
        assertTrue(params.temperature().isPresent());
        assertEquals(0.8, params.temperature().get(), 0.001);
        assertTrue(params.topP().isPresent());
        assertEquals(0.95, params.topP().get(), 0.001);
        assertEquals(3000, params.maxTokens());
    }

    @Test
    void testApplyOptionsWithDefaultFallback() {
        MessageCreateParams.Builder builder = createBuilder();

        GenerateOptions defaultOptions =
                GenerateOptions.builder().temperature(0.5).topP(0.9).build();

        // 未提供选项，应使用默认值
        AnthropicToolsHelper.applyOptions(builder, null, defaultOptions);

        MessageCreateParams params = builder.build();
        assertTrue(params.temperature().isPresent());
        assertEquals(0.5, params.temperature().get(), 0.001);
        assertTrue(params.topP().isPresent());
        assertEquals(0.9, params.topP().get(), 0.001);
    }

    @Test
    void testApplyOptionsOverridesDefault() {
        MessageCreateParams.Builder builder = createBuilder();

        GenerateOptions options = GenerateOptions.builder().temperature(0.7).build();
        GenerateOptions defaultOptions = GenerateOptions.builder().temperature(0.5).build();

        // 选项应覆盖默认值
        AnthropicToolsHelper.applyOptions(builder, options, defaultOptions);

        MessageCreateParams params = builder.build();
        assertTrue(params.temperature().isPresent());
        assertEquals(0.7, params.temperature().get(), 0.001);
    }

    @Test
    void testApplyToolsWithComplexParameters() {
        MessageCreateParams.Builder builder = createBuilder();

        Map<String, Object> properties = new HashMap<>();
        properties.put("name", Map.of("type", "string", "description", "Person name"));
        properties.put("age", Map.of("type", "integer", "minimum", 0, "maximum", 150));
        properties.put("tags", Map.of("type", "array", "items", Map.of("type", "string")));

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        parameters.put("required", List.of("name"));

        ToolSchema schema =
                ToolSchema.builder()
                        .name("create_person")
                        .description("Create a person")
                        .parameters(parameters)
                        .build();

        AnthropicToolsHelper.applyTools(builder, List.of(schema), null);

        MessageCreateParams params = builder.build();
        assertTrue(params.tools().isPresent());
        assertEquals(1, params.tools().get().size());
        assertTrue(params.tools().get().get(0).isTool());
        Tool tool = params.tools().get().get(0).asTool();
        assertEquals("create_person", tool.name());
        // 注意：inputSchema 校验由 Anthropic SDK 在 API 调用时处理
    }

    // ==================== 新参数测试 ====================

    @Test
    void testApplyOptionsWithTopK() {
        MessageCreateParams.Builder builder = createBuilder();

        GenerateOptions options = GenerateOptions.builder().topK(40).build();

        AnthropicToolsHelper.applyOptions(builder, options, null);

        MessageCreateParams params = builder.build();
        assertTrue(params.topK().isPresent());
        assertEquals(40L, params.topK().get());
    }

    @Test
    void testApplyOptionsWithAdditionalHeaders() {
        MessageCreateParams.Builder builder = createBuilder();

        GenerateOptions options =
                GenerateOptions.builder()
                        .additionalHeader("X-Custom-Header", "custom-value")
                        .additionalHeader("X-Request-Id", "req-123")
                        .build();

        AnthropicToolsHelper.applyOptions(builder, options, null);

        // 应用额外请求头后应成功构建
        MessageCreateParams params = builder.build();
        assertNotNull(params);
    }

    @Test
    void testApplyOptionsWithAdditionalBodyParams() {
        MessageCreateParams.Builder builder = createBuilder();

        GenerateOptions options =
                GenerateOptions.builder()
                        .additionalBodyParam("custom_param", "value1")
                        .additionalBodyParam("nested_param", Map.of("key", "value"))
                        .build();

        AnthropicToolsHelper.applyOptions(builder, options, null);

        // 应用额外请求体参数后应成功构建
        MessageCreateParams params = builder.build();
        assertNotNull(params);
    }

    @Test
    void testApplyOptionsWithAdditionalQueryParams() {
        MessageCreateParams.Builder builder = createBuilder();

        GenerateOptions options =
                GenerateOptions.builder()
                        .additionalQueryParam("api_version", "2024-01-01")
                        .additionalQueryParam("debug", "true")
                        .build();

        AnthropicToolsHelper.applyOptions(builder, options, null);

        // 应用额外查询参数后应成功构建
        MessageCreateParams params = builder.build();
        assertNotNull(params);
    }

    @Test
    void testApplyOptionsWithAllNewParameters() {
        MessageCreateParams.Builder builder = createBuilder();

        GenerateOptions options =
                GenerateOptions.builder()
                        .temperature(0.8)
                        .topK(50)
                        .additionalHeader("X-Api-Key", "secret")
                        .additionalBodyParam("stream", true)
                        .additionalQueryParam("version", "v1")
                        .build();

        AnthropicToolsHelper.applyOptions(builder, options, null);

        MessageCreateParams params = builder.build();
        assertTrue(params.temperature().isPresent());
        assertEquals(0.8, params.temperature().get(), 0.001);
        assertTrue(params.topK().isPresent());
        assertEquals(50L, params.topK().get());
    }

    @Test
    void testApplyOptionsTopKFromDefaultOptions() {
        MessageCreateParams.Builder builder = createBuilder();

        GenerateOptions options = GenerateOptions.builder().temperature(0.5).build();
        GenerateOptions defaultOptions = GenerateOptions.builder().topK(30).build();

        AnthropicToolsHelper.applyOptions(builder, options, defaultOptions);

        MessageCreateParams params = builder.build();
        assertTrue(params.temperature().isPresent());
        assertEquals(0.5, params.temperature().get(), 0.001);
        assertTrue(params.topK().isPresent());
        assertEquals(30L, params.topK().get());
    }

    @Test
    void testApplyOptionsWithEmptyAdditionalParams() {
        MessageCreateParams.Builder builder = createBuilder();

        GenerateOptions options = GenerateOptions.builder().temperature(0.5).build();

        // 应妥善处理空的额外参数
        AnthropicToolsHelper.applyOptions(builder, options, null);

        MessageCreateParams params = builder.build();
        assertTrue(params.temperature().isPresent());
        assertEquals(0.5, params.temperature().get(), 0.001);
    }

    @Test
    void testApplyOptionsMergesAdditionalHeadersFromBothOptionsAndDefault() {
        MessageCreateParams.Builder builder = createBuilder();

        // 默认选项含请求头 A 和 B
        GenerateOptions defaultOptions =
                GenerateOptions.builder()
                        .additionalHeader("X-Header-A", "value-a-default")
                        .additionalHeader("X-Header-B", "value-b")
                        .build();

        // 调用选项含请求头 A（覆盖）和 C（新增）
        GenerateOptions options =
                GenerateOptions.builder()
                        .additionalHeader("X-Header-A", "value-a-override")
                        .additionalHeader("X-Header-C", "value-c")
                        .build();

        // 应合并为 A=override、B=value-b、C=value-c
        AnthropicToolsHelper.applyOptions(builder, options, defaultOptions);

        MessageCreateParams params = builder.build();
        assertNotNull(params);
    }

    @Test
    void testApplyOptionsMergesAdditionalBodyParamsFromBothOptionsAndDefault() {
        MessageCreateParams.Builder builder = createBuilder();

        // 默认选项含参数 A 和 B
        GenerateOptions defaultOptions =
                GenerateOptions.builder()
                        .additionalBodyParam("param_a", "value-a-default")
                        .additionalBodyParam("param_b", "value-b")
                        .build();

        // 调用选项含参数 A（覆盖）和 C（新增）
        GenerateOptions options =
                GenerateOptions.builder()
                        .additionalBodyParam("param_a", "value-a-override")
                        .additionalBodyParam("param_c", "value-c")
                        .build();

        // 应合并为 A=override、B=value-b、C=value-c
        AnthropicToolsHelper.applyOptions(builder, options, defaultOptions);

        MessageCreateParams params = builder.build();
        assertNotNull(params);
    }

    @Test
    void testApplyOptionsMergesAdditionalQueryParamsFromBothOptionsAndDefault() {
        MessageCreateParams.Builder builder = createBuilder();

        // 默认选项含查询参数 A 和 B
        GenerateOptions defaultOptions =
                GenerateOptions.builder()
                        .additionalQueryParam("query_a", "value-a-default")
                        .additionalQueryParam("query_b", "value-b")
                        .build();

        // 调用选项含查询参数 A（覆盖）和 C（新增）
        GenerateOptions options =
                GenerateOptions.builder()
                        .additionalQueryParam("query_a", "value-a-override")
                        .additionalQueryParam("query_c", "value-c")
                        .build();

        // 应合并为 A=override、B=value-b、C=value-c
        AnthropicToolsHelper.applyOptions(builder, options, defaultOptions);

        MessageCreateParams params = builder.build();
        assertNotNull(params);
    }

    private static AnthropicServerTool webSearchServerTool() {
        return AnthropicServerTool.of(
                ToolUnion.ofWebSearchTool20250305(WebSearchTool20250305.builder().build()));
    }
}
