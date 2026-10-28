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

package io.agentscope.extensions.model.dashscope.formatter;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.agentscope.core.model.ToolChoice;
import io.agentscope.extensions.model.dashscope.dto.DashScopeParameters;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * DashScopeToolsHelper 的 ToolChoice 支持测试。
 */
class DashScopeToolsHelperToolChoiceTest {

    private DashScopeToolsHelper helper;

    @BeforeEach
    void setUp() {
        helper = new DashScopeToolsHelper();
    }

    @Test
    void testConvertToolChoiceWithNull() {
        Object result = helper.convertToolChoice(null);
        assertNull(result);
    }

    @Test
    void testConvertToolChoiceWithAuto() {
        Object result = helper.convertToolChoice(new ToolChoice.Auto());
        assertEquals("auto", result);
    }

    @Test
    void testConvertToolChoiceWithNone() {
        Object result = helper.convertToolChoice(new ToolChoice.None());
        assertEquals("none", result);
    }

    @Test
    void testConvertToolChoiceWithRequired() {
        // 不支持 Required，回退为 "auto"
        Object result = helper.convertToolChoice(new ToolChoice.Required());
        assertEquals("auto", result);
    }

    @Test
    @SuppressWarnings("unchecked")
    void testConvertToolChoiceWithSpecific() {
        Object result = helper.convertToolChoice(new ToolChoice.Specific("my_tool"));
        assertNotNull(result);

        // 应为含 "type" 和 "function" 键的 Map
        Map<String, Object> choice = (Map<String, Object>) result;
        assertEquals("function", choice.get("type"));

        Map<String, String> function = (Map<String, String>) choice.get("function");
        assertEquals("my_tool", function.get("name"));
    }

    @Test
    void testApplyToolChoiceWithNull() {
        DashScopeParameters params = DashScopeParameters.builder().build();

        // 不应抛异常
        assertDoesNotThrow(() -> helper.applyToolChoice(params, null));

        // 不应设置工具选择
        assertNull(params.getToolChoice());
    }

    @Test
    void testApplyToolChoiceWithAuto() {
        DashScopeParameters params = DashScopeParameters.builder().build();

        helper.applyToolChoice(params, new ToolChoice.Auto());

        assertEquals("auto", params.getToolChoice());
    }

    @Test
    void testApplyToolChoiceWithNone() {
        DashScopeParameters params = DashScopeParameters.builder().build();

        helper.applyToolChoice(params, new ToolChoice.None());

        assertEquals("none", params.getToolChoice());
    }

    @Test
    void testApplyToolChoiceWithRequired() {
        DashScopeParameters params = DashScopeParameters.builder().build();

        // DashScope 中 Required 回退为 auto
        helper.applyToolChoice(params, new ToolChoice.Required());

        assertEquals("auto", params.getToolChoice());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testApplyToolChoiceWithSpecific() {
        DashScopeParameters params = DashScopeParameters.builder().build();

        helper.applyToolChoice(params, new ToolChoice.Specific("generate_response"));

        Object toolChoice = params.getToolChoice();
        assertNotNull(toolChoice);

        Map<String, Object> choice = (Map<String, Object>) toolChoice;
        assertEquals("function", choice.get("type"));

        Map<String, String> function = (Map<String, String>) choice.get("function");
        assertEquals("generate_response", function.get("name"));
    }

    @Test
    void testApplyToolChoiceMultipleTimes() {
        DashScopeParameters params = DashScopeParameters.builder().build();

        // 依次应用不同工具选择
        helper.applyToolChoice(params, new ToolChoice.Auto());
        assertEquals("auto", params.getToolChoice());

        helper.applyToolChoice(params, new ToolChoice.None());
        assertEquals("none", params.getToolChoice());

        helper.applyToolChoice(params, new ToolChoice.Required());
        assertEquals("auto", params.getToolChoice()); // Required 回退为 auto
    }

    @Test
    void testApplyToolChoiceHandlesAllTypes() {
        DashScopeParameters params = DashScopeParameters.builder().build();

        ToolChoice auto = new ToolChoice.Auto();
        ToolChoice none = new ToolChoice.None();
        ToolChoice required = new ToolChoice.Required();
        ToolChoice specific = new ToolChoice.Specific("tool");

        // 所有调用均应无异常完成
        assertDoesNotThrow(() -> helper.applyToolChoice(params, auto));
        assertDoesNotThrow(() -> helper.applyToolChoice(params, none));
        assertDoesNotThrow(() -> helper.applyToolChoice(params, required));
        assertDoesNotThrow(() -> helper.applyToolChoice(params, specific));
    }

    @Test
    void testToolChoiceSpecificToolName() {
        ToolChoice.Specific specific = new ToolChoice.Specific("generate_response");
        assertEquals("generate_response", specific.toolName());
    }
}
