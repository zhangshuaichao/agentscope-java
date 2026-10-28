/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.extensions.model.ollama.formatter;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.extensions.model.ollama.dto.OllamaRequest;
import io.agentscope.extensions.model.ollama.dto.OllamaTool;
import io.agentscope.extensions.model.ollama.dto.OllamaToolCall;
import io.agentscope.extensions.model.ollama.options.OllamaOptions;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * OllamaToolsHelper 单元测试。
 */
@DisplayName("OllamaToolsHelper Unit Tests")
class OllamaToolsHelperTest {

    private OllamaToolsHelper helper;

    @BeforeEach
    void setUp() {
        helper = new OllamaToolsHelper();
    }

    @Test
    @DisplayName("Should create helper successfully")
    void testConstructor() {
        assertNotNull(helper);
    }

    @Test
    @DisplayName("Should apply GenerateOptions to request")
    void testApplyGenerateOptions() {
        // 准备
        OllamaRequest request = new OllamaRequest();
        GenerateOptions options = GenerateOptions.builder().temperature(0.7).build();
        GenerateOptions defaultOptions = GenerateOptions.builder().temperature(0.5).build();

        // 执行
        helper.applyOptions(request, options, defaultOptions);

        // 断言
        assertNotNull(request.getOptions());
    }

    @Test
    @DisplayName("Should apply OllamaOptions to request")
    void testApplyOllamaOptions() {
        // 准备
        OllamaRequest request = new OllamaRequest();
        OllamaOptions options = OllamaOptions.builder().temperature(0.7).build();
        OllamaOptions defaultOptions = OllamaOptions.builder().temperature(0.5).build();

        // 执行
        helper.applyOptions(request, options, defaultOptions);

        // 断言
        assertNotNull(request.getOptions());
    }

    @Test
    @DisplayName("Should handle null options when applying to request")
    void testApplyNullOptions() {
        // 准备
        OllamaRequest request = new OllamaRequest();

        // 执行并断言：不应抛异常
        assertDoesNotThrow(
                () -> helper.applyOptions(request, (GenerateOptions) null, (GenerateOptions) null));
        assertNotNull(request.getOptions()); // 仍应含 options 映射
    }

    @Test
    @DisplayName("Should convert tools from ToolSchema to OllamaTool")
    void testConvertTools() {
        // 准备
        ToolSchema tool1 =
                ToolSchema.builder()
                        .name("test_tool")
                        .description("A test tool")
                        .parameters(
                                Map.of(
                                        "type",
                                        "object",
                                        "properties",
                                        Map.of("param1", Map.of("type", "string"))))
                        .build();
        List<ToolSchema> tools = Arrays.asList(tool1);

        // 执行
        List<OllamaTool> ollamaTools = helper.convertTools(tools);

        // 断言
        assertNotNull(ollamaTools);
        assertEquals(1, ollamaTools.size());
        assertEquals("test_tool", ollamaTools.get(0).getFunction().getName());
        assertEquals("A test tool", ollamaTools.get(0).getFunction().getDescription());
    }

    @Test
    @DisplayName("Should return null when converting empty tools list")
    void testConvertEmptyTools() {
        // 执行
        List<OllamaTool> ollamaTools = helper.convertTools(Collections.emptyList());

        // 断言
        assertNull(ollamaTools);
    }

    @Test
    @DisplayName("Should return null when converting null tools list")
    void testConvertNullTools() {
        // 执行
        List<OllamaTool> ollamaTools = helper.convertTools(null);

        // 断言
        assertNull(ollamaTools);
    }

    @Test
    @DisplayName("Should apply tools to request")
    void testApplyTools() {
        // 准备
        OllamaRequest request = new OllamaRequest();
        ToolSchema tool1 =
                ToolSchema.builder().name("test_tool").description("A test tool").build();
        List<ToolSchema> tools = Arrays.asList(tool1);

        // 执行
        helper.applyTools(request, tools);

        // 断言
        assertNotNull(request.getTools());
        assertEquals(1, request.getTools().size());
    }

    @Test
    @DisplayName("Should not apply null tools to request")
    void testApplyNullTools() {
        // 准备
        OllamaRequest request = new OllamaRequest();

        // 执行
        helper.applyTools(request, null);

        // 断言
        assertNull(request.getTools());
    }

    @Test
    @DisplayName("Should not apply empty tools list to request")
    void testApplyEmptyTools() {
        // 准备
        OllamaRequest request = new OllamaRequest();

        // 执行
        helper.applyTools(request, Collections.emptyList());

        // 断言
        assertNull(request.getTools());
    }

    @Test
    @DisplayName("Should apply auto tool choice to request")
    void testApplyAutoToolChoice() {
        // 准备
        OllamaRequest request = new OllamaRequest();
        ToolChoice toolChoice = new ToolChoice.Auto();

        // 执行
        helper.applyToolChoice(request, toolChoice);

        // 断言
        assertEquals("auto", request.getToolChoice());
    }

    @Test
    @DisplayName("Should apply none tool choice to request")
    void testApplyNoneToolChoice() {
        // 准备
        OllamaRequest request = new OllamaRequest();
        ToolChoice toolChoice = new ToolChoice.None();

        // 执行
        helper.applyToolChoice(request, toolChoice);

        // 断言
        assertEquals("none", request.getToolChoice());
    }

    @Test
    @DisplayName("Should apply specific tool choice to request")
    void testApplySpecificToolChoice() {
        // 准备
        OllamaRequest request = new OllamaRequest();
        ToolChoice toolChoice = new ToolChoice.Specific("test_tool");

        // 执行
        helper.applyToolChoice(request, toolChoice);

        // 断言
        assertNotNull(request.getToolChoice());
        assertTrue(request.getToolChoice() instanceof Map);
        Map<String, Object> toolChoiceMap = (Map<String, Object>) request.getToolChoice();
        assertEquals("function", toolChoiceMap.get("type"));
        Map<String, Object> function = (Map<String, Object>) toolChoiceMap.get("function");
        assertEquals("test_tool", function.get("name"));
    }

    @Test
    @DisplayName("Should handle null tool choice")
    void testApplyNullToolChoice() {
        // 准备
        OllamaRequest request = new OllamaRequest();

        // 执行
        helper.applyToolChoice(request, null);

        // 断言
        assertNull(request.getToolChoice());
    }

    @Test
    @DisplayName("Should convert tool use blocks to OllamaToolCall")
    void testConvertToolCalls() {
        // 准备
        ToolUseBlock block1 =
                new ToolUseBlock("call1", "test_tool", Map.of("param1", "value1"), null);
        List<ToolUseBlock> blocks = Arrays.asList(block1);

        // 执行
        List<OllamaToolCall> toolCalls = helper.convertToolCalls(blocks);

        // 断言
        assertNotNull(toolCalls);
        assertEquals(1, toolCalls.size());
        assertEquals("test_tool", toolCalls.get(0).getFunction().getName());
        assertEquals("value1", toolCalls.get(0).getFunction().getArguments().get("param1"));
    }

    @Test
    @DisplayName("Should return empty list when converting null tool use blocks")
    void testConvertNullToolCalls() {
        // 执行
        List<OllamaToolCall> toolCalls = helper.convertToolCalls(null);

        // 断言
        assertNotNull(toolCalls);
        assertTrue(toolCalls.isEmpty());
    }

    @Test
    @DisplayName("Should return empty list when converting empty tool use blocks")
    void testConvertEmptyToolCalls() {
        // 执行
        List<OllamaToolCall> toolCalls = helper.convertToolCalls(Collections.emptyList());

        // 断言
        assertNotNull(toolCalls);
        assertTrue(toolCalls.isEmpty());
    }

    @Test
    @DisplayName("Should skip null tool use blocks during conversion")
    void testConvertToolCallsWithNullElements() {
        // 准备
        ToolUseBlock block1 =
                new ToolUseBlock("call1", "test_tool", Map.of("param1", "value1"), null);
        List<ToolUseBlock> blocks = Arrays.asList(block1, null, block1); // 包含 null 元素

        // 执行
        List<OllamaToolCall> toolCalls = helper.convertToolCalls(blocks);

        // 断言
        assertNotNull(toolCalls);
        assertEquals(2, toolCalls.size()); // 应跳过 null 元素
    }
}
