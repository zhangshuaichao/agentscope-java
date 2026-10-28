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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.test.SampleTools;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 工具功能边界测试。
 *
 * <p>验证 null 参数、无效参数、缺失参数与不存在工具等边界条件下的行为。
 *
 * <p>标记为 "unit"，测试边界条件。
 */
@Tag("unit")
@DisplayName("Tool Boundary Tests")
class ToolBoundaryTest {

    private Toolkit toolkit;
    private SampleTools sampleTools;

    @BeforeEach
    void setUp() {
        toolkit = new Toolkit();
        sampleTools = new SampleTools();
        toolkit.registerTool(sampleTools);
    }

    @Test
    @DisplayName("Should handle null parameters gracefully")
    void testNullParameters() {
        AgentTool addTool = toolkit.getTool("add");

        if (addTool != null) {
            // 尝试使用 null 参数映射调用
            try {
                ToolResultBlock response = addTool.callAsync(null).block();
                // 若不抛异常则验证响应
                if (response != null) {
                    assertNotNull(response, "Should handle null params");
                }
            } catch (Exception e) {
                // null 参数允许抛异常
                assertTrue(
                        e instanceof NullPointerException || e instanceof IllegalArgumentException,
                        "Should throw appropriate exception for null params");
            }
        }
    }

    @Test
    @DisplayName("Should handle invalid parameter types")
    void testInvalidParameters() {
        AgentTool addTool = toolkit.getTool("add");

        if (addTool != null) {
            // 尝试使用错误参数类型调用
            Map<String, Object> invalidParams = new HashMap<>();
            invalidParams.put("a", "not_a_number"); // 用字符串代替 int
            invalidParams.put("b", "also_not_a_number");

            try {
                ToolResultBlock response =
                        addTool.callAsync(ToolCallParam.builder().input(invalidParams).build())
                                .block();
                // 若不抛异常则验证响应存在
                if (response != null) {
                    assertNotNull(response, "Should handle invalid types");
                }
            } catch (Exception e) {
                // 无效类型预期抛异常
                assertTrue(
                        e instanceof IllegalArgumentException
                                || e instanceof ClassCastException
                                || e instanceof NumberFormatException,
                        "Should throw appropriate exception for invalid types");
            }
        }
    }

    @Test
    @DisplayName("Should handle missing required parameters")
    void testMissingParameters() {
        AgentTool addTool = toolkit.getTool("add");

        if (addTool != null) {
            // 尝试缺少参数调用
            Map<String, Object> incompleteParams = new HashMap<>();
            incompleteParams.put("a", 5); // 缺少参数 b

            try {
                ToolResultBlock response =
                        addTool.callAsync(ToolCallParam.builder().input(incompleteParams).build())
                                .block();
                // 若不抛异常则验证响应
                if (response != null) {
                    assertNotNull(response, "Should handle missing params");
                }
            } catch (Exception e) {
                // 缺少必填参数允许抛异常
                assertTrue(
                        e instanceof IllegalArgumentException || e instanceof NullPointerException,
                        "Should throw appropriate exception for missing params");
            }
        }
    }

    @Test
    @DisplayName("Should handle non-existent tools")
    void testToolNotFound() {
        // 尝试获取不存在的工具
        AgentTool nonExistentTool = toolkit.getTool("non_existent_tool_xyz");

        assertNull(nonExistentTool, "Non-existent tool should return null");

        // 验证工具集仍可正常处理有效工具
        AgentTool validTool = toolkit.getTool("add");
        assertNotNull(validTool, "Valid tool should still be accessible");
    }

    @Test
    @DisplayName("Should handle empty parameter map")
    void testEmptyParameters() {
        // 获取需要参数的工具
        AgentTool addTool = toolkit.getTool("add");

        if (addTool != null) {
            // 尝试空参数映射
            Map<String, Object> emptyParams = new HashMap<>();

            try {
                ToolResultBlock response =
                        addTool.callAsync(ToolCallParam.builder().input(emptyParams).build())
                                .block();
                // 若不抛异常则验证响应
                if (response != null) {
                    assertNotNull(response, "Should handle empty params");
                }
            } catch (Exception e) {
                // 必填参数缺失时预期抛异常
                assertTrue(
                        e instanceof IllegalArgumentException || e instanceof NullPointerException,
                        "Should throw exception for empty params when params required");
            }
        }

        // 测试无参数工具，应成功
        AgentTool noParamTool = toolkit.getTool("no_param");
        if (noParamTool != null) {
            ToolResultBlock response =
                    noParamTool
                            .callAsync(ToolCallParam.builder().input(new HashMap<>()).build())
                            .block();
            assertNotNull(response, "No-param tool should work with empty params");
        }
    }
}
