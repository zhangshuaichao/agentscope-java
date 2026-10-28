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
package io.agentscope.core.agent.test;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import reactor.core.publisher.Mono;

/**
 * 测试用模拟 Toolkit，可配置行为以模拟工具调用，不执行真实工具代码。
 */
public class MockToolkit extends Toolkit {

    private final Map<String, Function<Map<String, Object>, String>> toolBehaviors;
    private final List<String> toolCallHistory;
    private int callCount = 0;

    public MockToolkit() {
        super();
        this.toolBehaviors = new HashMap<>();
        this.toolCallHistory = new ArrayList<>();
        registerDefaultTools();
    }

    /**
     * 注册返回固定结果的简单工具。
     */
    public MockToolkit withTool(String toolName, String result) {
        toolBehaviors.put(toolName, args -> result);
        registerMockTool(toolName, "Mock tool: " + toolName);
        return this;
    }

    /**
     * 注册自定义行为工具。
     */
    public MockToolkit withTool(String toolName, Function<Map<String, Object>, String> behavior) {
        toolBehaviors.put(toolName, behavior);
        registerMockTool(toolName, "Mock tool with custom behavior: " + toolName);
        return this;
    }

    /**
     * 注册抛错工具。
     */
    public MockToolkit withErrorTool(String toolName, String errorMessage) {
        toolBehaviors.put(
                toolName,
                args -> {
                    throw new RuntimeException(errorMessage);
                });
        registerMockTool(toolName, "Mock tool that throws error: " + toolName);
        return this;
    }

    /**
     * 注册默认测试工具。
     */
    private void registerDefaultTools() {
        // 计算器工具
        withTool(
                TestConstants.CALCULATOR_TOOL_NAME,
                args -> {
                    String operation = (String) args.getOrDefault("operation", "add");
                    double a = ((Number) args.getOrDefault("a", 0)).doubleValue();
                    double b = ((Number) args.getOrDefault("b", 0)).doubleValue();

                    double result =
                            switch (operation) {
                                case "add" -> a + b;
                                case "subtract" -> a - b;
                                case "multiply" -> a * b;
                                case "divide" -> b != 0 ? a / b : Double.NaN;
                                default -> 0.0;
                            };

                    return String.valueOf(result);
                });

        // 供 ReAct 智能体使用的完成工具
        withTool(
                TestConstants.FINISH_TOOL_NAME,
                args -> {
                    String response = (String) args.getOrDefault("response", "");
                    return response;
                });

        // 简单测试工具
        withTool(TestConstants.TEST_TOOL_NAME, args -> TestConstants.TEST_TOOL_CALL_RESULT);
    }

    /**
     * 向工具包注册模拟工具。
     */
    private void registerMockTool(String name, String description) {
        AgentTool tool =
                new AgentTool() {
                    @Override
                    public String getName() {
                        return name;
                    }

                    @Override
                    public String getDescription() {
                        return description;
                    }

                    @Override
                    public Map<String, Object> getParameters() {
                        Map<String, Object> schema = new HashMap<>();
                        schema.put("type", "object");
                        schema.put("properties", new HashMap<String, Object>());
                        return schema;
                    }

                    @Override
                    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                        return Mono.fromCallable(
                                () -> {
                                    callCount++;
                                    toolCallHistory.add(name);

                                    try {
                                        Function<Map<String, Object>, String> behavior =
                                                toolBehaviors.get(name);
                                        if (behavior != null) {
                                            String result = behavior.apply(param.getInput());
                                            return ToolResultBlock.of(
                                                    TextBlock.builder().text(result).build());
                                        }
                                        return ToolResultBlock.of(
                                                TextBlock.builder()
                                                        .text("Default mock result for " + name)
                                                        .build());
                                    } catch (Exception e) {
                                        return ToolResultBlock.error(e.getMessage());
                                    }
                                });
                    }
                };

        registerTool(tool);
    }

    /**
     * 获取工具调用总次数。
     */
    public int getCallCount() {
        return callCount;
    }

    /**
     * 获取按顺序记录工具名的调用历史。
     */
    public List<String> getToolCallHistory() {
        return new ArrayList<>(toolCallHistory);
    }

    /**
     * 检查指定工具是否被调用。
     */
    public boolean wasToolCalled(String toolName) {
        return toolCallHistory.contains(toolName);
    }

    /**
     * 获取指定工具调用次数。
     */
    public int getToolCallCount(String toolName) {
        return (int) toolCallHistory.stream().filter(name -> name.equals(toolName)).count();
    }

    /**
     * 重置模拟状态。
     */
    public void reset() {
        this.callCount = 0;
        this.toolCallHistory.clear();
    }
}
