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

/**
 * Agent 模块测试常量，为各智能体测试提供公共常量，保持一致性与可维护性。
 */
public class TestConstants {

    // 智能体名称
    public static final String TEST_AGENT_NAME = "TestAgent";
    public static final String TEST_USER_AGENT_NAME = "TestUser";
    public static final String TEST_REACT_AGENT_NAME = "TestReActAgent";

    // 系统提示词
    public static final String DEFAULT_SYS_PROMPT = "You are a helpful AI assistant.";
    public static final String TEST_SYS_PROMPT = "You are a test agent for unit testing.";

    // 测试消息
    public static final String TEST_USER_INPUT = "Hello, how are you?";
    public static final String TEST_ASSISTANT_RESPONSE = "I'm doing well, thank you!";
    public static final String TEST_THINKING = "Let me think about this...";
    public static final String TEST_TOOL_CALL_RESULT = "Tool executed successfully";

    // 工具名称
    public static final String TEST_TOOL_NAME = "test_tool";
    public static final String CALCULATOR_TOOL_NAME = "calculator";
    public static final String FINISH_TOOL_NAME = "generate_response";

    // 超时设置
    public static final long DEFAULT_TEST_TIMEOUT_MS = 5000L;
    public static final long SHORT_TEST_TIMEOUT_MS = 1000L;
    public static final long LONG_TEST_TIMEOUT_MS = 10000L;

    // 限制值
    public static final int DEFAULT_MAX_ITERS = 10;
    public static final int TEST_MAX_ITERS = 5;
    public static final int TEST_MEMORY_SIZE_LIMIT = 100;

    // 模型响应
    public static final String MOCK_MODEL_SIMPLE_RESPONSE = "This is a mock response";
    public static final String MOCK_MODEL_THINKING_RESPONSE = "Analyzing the question...";
    public static final String MOCK_MODEL_FINAL_RESPONSE = "Here is my final answer";

    private TestConstants() {
        // 工具类，禁止实例化
    }
}
