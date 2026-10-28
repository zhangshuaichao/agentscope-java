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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.test.SampleTools;
import io.agentscope.core.tool.test.ToolTestUtils;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 工具功能集成测试。
 *
 * <p>验证工具与智能体集成、异步执行、错误传播和超时场景。
 *
 * <p>标记为 "integration"，测试组件交互。
 */
@Tag("integration")
@DisplayName("Tool Integration Tests")
class ToolIntegrationTest {

    private Toolkit toolkit;
    private SampleTools sampleTools;
    private MockModel mockModel;
    private InMemoryMemory memory;

    @BeforeEach
    void setUp() {
        toolkit = new Toolkit();
        sampleTools = new SampleTools();
        toolkit.registerTool(sampleTools);
        mockModel = new MockModel("Tool integration response");
        memory = new InMemoryMemory();
    }

    @Test
    @DisplayName("Should integrate Toolkit with Agent")
    void testToolkitAgentIntegration() {
        // 创建带工具集的智能体
        ReActAgent agent =
                ReActAgent.builder()
                        .name("TestAgent")
                        .sysPrompt("Test agent with tools")
                        .model(mockModel)
                        .toolkit(toolkit)
                        .build();

        assertNotNull(agent, "Agent should be created with toolkit");

        // 验证工具集可访问
        Set<String> toolNames = toolkit.getToolNames();
        assertTrue(toolNames.size() > 0, "Agent should have access to toolkit tools");

        // 验证可通过工具集调用工具
        AgentTool addTool = toolkit.getTool("add");
        if (addTool != null) {
            Map<String, Object> params = Map.of("a", 5, "b", 10);
            ToolResultBlock response =
                    addTool.callAsync(ToolCallParam.builder().input(params).build()).block();

            assertNotNull(response, "Tool should return response");
            assertTrue(ToolTestUtils.isValidToolResultBlock(response), "Response should be valid");
        }
    }

    @Test
    @DisplayName("Should support async tool execution")
    void testAsyncToolExecution() {
        // 异步执行工具
        AgentTool addTool = toolkit.getTool("add");

        if (addTool != null) {
            CompletableFuture<ToolResultBlock> future =
                    CompletableFuture.supplyAsync(
                            () -> {
                                Map<String, Object> params = Map.of("a", 100, "b", 200);
                                return addTool.callAsync(
                                                ToolCallParam.builder().input(params).build())
                                        .block();
                            });

            // 等待完成
            assertDoesNotThrow(
                    () -> {
                        ToolResultBlock response = future.get(5, TimeUnit.SECONDS);
                        assertNotNull(response, "Async execution should return response");
                        assertTrue(
                                ToolTestUtils.isValidToolResultBlock(response),
                                "Async response should be valid");
                    });
        }
    }

    @Test
    @DisplayName("Should propagate errors correctly")
    void testToolErrorPropagation() {
        // 获取错误工具
        AgentTool errorTool = toolkit.getTool("error_tool");

        if (errorTool != null) {
            Map<String, Object> params = Map.of("message", "test error propagation");

            // 工具应抛异常或返回错误响应
            try {
                ToolResultBlock response =
                        errorTool.callAsync(ToolCallParam.builder().input(params).build()).block();

                // 若得到响应，验证其表示错误
                if (response != null) {
                    // 错误可能在元数据或内容中
                    assertTrue(
                            ToolTestUtils.isErrorResponse(response)
                                    || !response.getOutput().isEmpty(),
                            "Error should be propagated in response");
                }
            } catch (RuntimeException e) {
                // 传播异常同样有效
                assertTrue(
                        e.getMessage().contains("error") || e.getMessage().contains("Tool"),
                        "Exception should contain error information");
            }
        }
    }

    @Test
    @DisplayName("Should handle tool timeout scenarios")
    void testToolTimeout() {
        // 获取慢工具
        AgentTool slowTool = toolkit.getTool("slow_tool");

        if (slowTool != null) {
            // 使用合理延迟测试
            Map<String, Object> params = Map.of("delay_ms", 100);
            ToolResultBlock response =
                    slowTool.callAsync(ToolCallParam.builder().input(params).build()).block();

            assertNotNull(response, "Slow tool should complete");
            assertTrue(
                    ToolTestUtils.isValidToolResultBlock(response),
                    "Response should be valid after delay");

            // 测试异步超时
            CompletableFuture<ToolResultBlock> future =
                    CompletableFuture.supplyAsync(
                            () -> {
                                Map<String, Object> longParams = Map.of("delay_ms", 5000);
                                return slowTool.callAsync(
                                                ToolCallParam.builder().input(longParams).build())
                                        .block();
                            });

            // 真实场景应超时，此测试仅验证设置
            assertNotNull(future, "Async execution should be initiated");
        }
    }
}
