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
package io.agentscope.core.e2e;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.TestUtils;
import io.agentscope.core.e2e.providers.ModelProvider;
import io.agentscope.core.message.Msg;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** 核心智能体功能 E2E 测试合集。<p>对可用模型提供方测试基本 ReAct 流程、对话、流式、记忆管理及工具调用。<p>要求配置 OPENAI_API_KEY、DASHSCOPE_API_KEY 等相应密钥，按可用密钥动态启用。 */
@Tag("e2e")
@ExtendWith(E2ETestCondition.class)
@Execution(ExecutionMode.CONCURRENT)
@DisplayName("Core Agent E2E Tests")
class CoreAgentE2ETest {

    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(300);

    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getBasicProviders")
    @DisplayName("Should handle basic conversation across all providers")
    void testBasicConversation(ModelProvider provider) {
        System.out.println(
                "\n=== Test: Basic Conversation with " + provider.getProviderName() + " ===");

        Toolkit toolkit = new Toolkit();
        ReActAgent agent = provider.createAgent("BasicTestAgent", toolkit);

        Msg input = TestUtils.createUserMessage("User", "What is the capital of France?");
        System.out.println("Question: " + TestUtils.extractTextContent(input));

        Msg response = agent.call(input).block(TEST_TIMEOUT);

        assertNotNull(response, "Response should not be null");
        assertTrue(
                ContentValidator.hasMeaningfulContent(response),
                "Response should have meaningful content");

        String responseText = TestUtils.extractTextContent(response);
        System.out.println("Answer: " + responseText);
        assertTrue(
                ContentValidator.containsKeywords(response, "paris"),
                "Response should mention Paris for " + provider.getModelName());

        System.out.println("✓ Basic conversation verified for " + provider.getProviderName());
    }

    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getBasicProviders")
    @DisplayName("Should handle multi-round conversation with context preservation")
    void testMultiRoundConversation(ModelProvider provider) {
        System.out.println(
                "\n=== Test: Multi-Round Conversation with " + provider.getProviderName() + " ===");

        Toolkit toolkit = new Toolkit();
        ReActAgent agent = provider.createAgent("ConversationTestAgent", toolkit);

        // 第 1 轮：设置上下文
        Msg contextMsg = TestUtils.createUserMessage("User", "My favorite color is blue.");
        System.out.println("Round 1: " + TestUtils.extractTextContent(contextMsg));

        Msg response1 = agent.call(contextMsg).block(TEST_TIMEOUT);
        assertNotNull(response1, "First response should not be null");
        System.out.println("Response 1: " + TestUtils.extractTextContent(response1));

        // 第 2 轮：测试上下文保留
        Msg followUpMsg = TestUtils.createUserMessage("User", "What is my favorite color?");
        System.out.println("Round 2: " + TestUtils.extractTextContent(followUpMsg));

        Msg response2 = agent.call(followUpMsg).block(TEST_TIMEOUT);
        assertNotNull(response2, "Second response should not be null");

        String response2Text = TestUtils.extractTextContent(response2);
        System.out.println("Response 2: " + response2Text);

        assertTrue(
                ContentValidator.containsKeywords(response2, "blue"),
                "Should remember favorite color from previous turn for " + provider.getModelName());

        // 验证记忆条目增长
        int memorySize = agent.getAgentState().getContext().size();
        assertTrue(
                memorySize >= 4,
                "Memory should contain conversation history for " + provider.getModelName());
        System.out.println("Memory size: " + memorySize + " messages");

        System.out.println("✓ Multi-round conversation verified for " + provider.getProviderName());
    }

    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getToolProviders")
    @DisplayName("Should handle mixed conversation (with and without tools)")
    void testMixedConversation(ModelProvider provider) {
        System.out.println(
                "\n=== Test: Mixed Conversation with " + provider.getProviderName() + " ===");

        Toolkit toolkit = E2ETestUtils.createTestToolkit();
        ReActAgent agent = provider.createAgent("MixedTestAgent", toolkit);

        // 第 1 轮：问候，无需工具
        Msg greeting = TestUtils.createUserMessage("User", "Hello! How are you?");
        System.out.println("Round 1 (no tools): " + TestUtils.extractTextContent(greeting));

        Msg response1 = agent.call(greeting).block(TEST_TIMEOUT);
        assertNotNull(response1, "Should respond to greeting");
        assertTrue(
                ContentValidator.hasMeaningfulContent(response1),
                "Greeting response should have content for " + provider.getModelName());

        // 第 2 轮：计算，需要工具
        Msg calculation = TestUtils.createUserMessage("User", "Can you calculate 25 plus 17?");
        System.out.println("Round 2 (with tools): " + TestUtils.extractTextContent(calculation));

        Msg response2 = agent.call(calculation).block(TEST_TIMEOUT);
        assertNotNull(response2, "Should respond to calculation");
        assertTrue(
                ContentValidator.hasMeaningfulContent(response2),
                "Calculation response should have content for " + provider.getModelName());

        // 第 3 轮：致谢，无需工具
        Msg thanks = TestUtils.createUserMessage("User", "Thanks for your help!");
        System.out.println("Round 3 (no tools): " + TestUtils.extractTextContent(thanks));

        Msg response3 = agent.call(thanks).block(TEST_TIMEOUT);
        assertNotNull(response3, "Should respond to thanks");

        // 验证所有交互均保存在记忆中
        List<Msg> allMessages = agent.getAgentState().getContext();
        assertTrue(allMessages.size() >= 3, "Should have at least 3 user messages in memory");

        System.out.println("Final memory size: " + allMessages.size() + " messages");
        System.out.println("✓ Mixed conversation verified for " + provider.getProviderName());
    }

    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getToolProviders")
    @DisplayName("Should handle simple tool calling")
    void testSimpleToolCalling(ModelProvider provider) {
        assumeTrue(
                provider.supportsToolCalling(),
                "Skipping test: " + provider.getProviderName() + " does not support tool calling");

        System.out.println(
                "\n=== Test: Simple Tool Calling with " + provider.getProviderName() + " ===");

        Toolkit toolkit = E2ETestUtils.createTestToolkit();
        ReActAgent agent = provider.createAgent("ToolTestAgent", toolkit);

        Msg input =
                TestUtils.createUserMessage(
                        "User",
                        "What is 15 multiplied by 8? You should use the calculator tool. Do not"
                                + " calculate by yourself.");
        System.out.println("Question: " + TestUtils.extractTextContent(input));

        Msg response = agent.call(input).block(TEST_TIMEOUT);

        String responseText = TestUtils.extractTextContent(response);
        System.out.println("Answer: " + responseText);

        // 验证计算结果
        assertTrue(
                ContentValidator.containsCalculationResult(response, "120"),
                "Response should contain correct calculation result 120 for "
                        + provider.getModelName());

        System.out.println("✓ Simple tool calling verified for " + provider.getProviderName());
    }

    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getToolProviders")
    @DisplayName("Should handle tool errors gracefully")
    void testToolErrorHandling(ModelProvider provider) {
        assumeTrue(
                provider.supportsToolCalling(),
                "Skipping test: " + provider.getProviderName() + " does not support tool calling");

        System.out.println(
                "\n=== Test: Tool Error Handling with " + provider.getProviderName() + " ===");

        Toolkit toolkit = E2ETestUtils.createTestToolkit();
        // 添加可能失败的业务工具
        toolkit.registerTool(new BusinessTools());
        ReActAgent agent = provider.createAgent("ErrorTestAgent", toolkit);

        // 请求访问受保护资源，预期失败
        Msg input =
                TestUtils.createUserMessage(
                        "User", "Try to access the user profile with ID 'invalid_user'");
        System.out.println("Sending: " + TestUtils.extractTextContent(input));

        Msg response = agent.call(input).block(TEST_TIMEOUT);

        assertNotNull(response, "Should receive responses even with tool error");
        assertTrue(
                ContentValidator.hasMeaningfulContent(response),
                "Should provide error explanation");

        System.out.println("Response: " + TestUtils.extractTextContent(response));

        // 验证错误处理，回复应说明访问失败
        assertTrue(
                ContentValidator.indicatesErrorHandling(response),
                "Response should indicate error handling for " + provider.getModelName());

        System.out.println("✓ Tool error handling verified for " + provider.getProviderName());
    }

    @Test
    @DisplayName("Should verify provider configuration works correctly")
    void testProviderConfiguration() {
        System.out.println("\n=== Test: Provider Configuration Verification ===");

        long enabledBasicProviders = ProviderFactory.getBasicProviders().count();
        long enabledToolProviders = ProviderFactory.getToolProviders().count();

        System.out.println("Enabled basic providers: " + enabledBasicProviders);
        System.out.println("Enabled tool providers: " + enabledToolProviders);

        // 至少应启用一个提供方
        assertTrue(
                enabledBasicProviders > 0,
                "At least one basic provider should be enabled (check OPENAI_API_KEY or"
                        + " DASHSCOPE_API_KEY)");

        System.out.println("✓ Provider configuration verified");
    }

    /** 用于测试错误处理的业务工具。 */
    public static class BusinessTools {
        @Tool(description = "Access user profile information by user ID")
        public String getUserProfile(
                @ToolParam(name = "userId", description = "User ID to access", required = true)
                        String userId) {
            // 模拟对无效用户认证失败
            if (userId == null || userId.isEmpty() || userId.equals("invalid_user")) {
                throw new IllegalArgumentException(
                        "Access denied: User ID '" + userId + "' not found or unauthorized");
            }
            return "Profile for user " + userId + ": Name: John Doe, Email: john@example.com";
        }
    }
}
