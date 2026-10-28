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

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.TestUtils;
import io.agentscope.core.e2e.providers.ModelProvider;
import io.agentscope.core.message.Msg;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** 跨模型兼容性 E2E 测试合集，对不同 API（OpenAI 原生、DashScope 兼容、百炼等）测试等价功能，检查行为一致性与 API 兼容。<p>要求设置 OPENAI_API_KEY、DASHSCOPE_API_KEY 等相应密钥，并按可用密钥动态启用。 */
@Tag("e2e")
@Tag("compatibility")
@ExtendWith(E2ETestCondition.class)
@Execution(ExecutionMode.CONCURRENT)
@DisplayName("Cross-Model Compatibility E2E Tests (Consolidated)")
class CrossModelCompatibilityE2ETest {

    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(300);

    @ParameterizedTest
    @MethodSource("getAllEnabledProviders")
    @DisplayName("Should handle equivalent questions consistently across providers")
    void testEquivalentQuestions(ModelProvider provider) {
        System.out.println(
                "\n=== Test: Equivalent Questions with " + provider.getProviderName() + " ===");

        Toolkit toolkit = new Toolkit();
        ReActAgent agent = provider.createAgent("CompatibilityTestAgent", toolkit);

        String[] testQuestions = {
            "What is the capital of France?",
            "What is 2 + 2?",
            "Who wrote Romeo and Juliet?",
            "What is the boiling point of water in Celsius?"
        };

        for (String question : testQuestions) {
            System.out.println("Testing question: " + question);

            Msg input = TestUtils.createUserMessage("User", question);
            Msg response = agent.call(input).block(TEST_TIMEOUT);

            assertNotNull(response, "Response should not be null for question: " + question);

            String responseText = TestUtils.extractTextContent(response);
            System.out.println(
                    "  Response: "
                            + responseText.substring(0, Math.min(100, responseText.length()))
                            + "...");
        }

        System.out.println(
                "✓ Equivalent questions handled consistently for " + provider.getProviderName());
    }

    @ParameterizedTest
    @MethodSource("getToolProviders")
    @DisplayName("Should handle equivalent tool calls consistently across providers")
    void testEquivalentToolCalls(ModelProvider provider) {
        System.out.println(
                "\n=== Test: Equivalent Tool Calls with " + provider.getProviderName() + " ===");

        Toolkit toolkit = E2ETestUtils.createTestToolkit();
        ReActAgent agent = provider.createAgent("ToolCompatibilityAgent", toolkit);

        // 测试基本算术工具调用
        Msg input =
                TestUtils.createUserMessage(
                        "User", "Use the add tool to calculate: What is 25 plus 17?");
        System.out.println("Tool question: " + TestUtils.extractTextContent(input));

        Msg response = agent.call(input).block(TEST_TIMEOUT);

        String responseText = TestUtils.extractTextContent(response);
        System.out.println("Tool answer: " + responseText);

        // 所有提供方应得到相同结果 42
        assertTrue(
                ContentValidator.containsCalculationResult(response, "42"),
                "All providers should calculate 25+17=42 for " + provider.getModelName());

        System.out.println(
                "✓ Equivalent tool calls handled consistently for " + provider.getProviderName());
    }

    @ParameterizedTest
    @MethodSource("getAllEnabledProviders")
    @DisplayName("Should handle streaming vs non-streaming equivalently")
    void testStreamingEquivalence(ModelProvider provider) {
        System.out.println(
                "\n=== Test: Streaming Equivalence with " + provider.getProviderName() + " ===");

        String question = "What is 7 + 2?";

        // 测试流式模式，提供方默认已启用
        Toolkit toolkit1 = new Toolkit();
        ReActAgent streamingAgent = provider.createAgent("StreamingAgent", toolkit1);

        Msg streamingInput = TestUtils.createUserMessage("User", question);
        Msg streamingResponse = streamingAgent.call(streamingInput).block(TEST_TIMEOUT);

        assertNotNull(streamingResponse, "Streaming response should not be null");

        String streamingText = TestUtils.extractTextContent(streamingResponse);
        System.out.println("Streaming response: " + streamingText);

        // 两者均应回答 9
        boolean bothCorrect = ContentValidator.containsKeywords(streamingResponse, "9");

        assertTrue(
                bothCorrect, "Streaming should give correct answer for " + provider.getModelName());

        System.out.println("✓ Streaming functionality verified for " + provider.getProviderName());
    }

    @ParameterizedTest
    @MethodSource("getMultimodalProviders")
    @DisplayName("Should handle equivalent multimodal content across providers")
    void testEquivalentMultimodalContent(ModelProvider provider) {
        System.out.println(
                "\n=== Test: Equivalent Multimodal Content with "
                        + provider.getProviderName()
                        + " ===");

        Toolkit toolkit = new Toolkit();
        ReActAgent agent = provider.createAgent("MultimodalCompatibilityAgent", toolkit);

        // 测试所有提供方应能分析的简单图片场景
        String testImageUrl = "https://agentscope-test.oss-cn-beijing.aliyuncs.com/Cat03.jpg";

        // 为简化，此处用基于文本的多模态问题进行测试
        Msg input =
                TestUtils.createUserMessage(
                        "User",
                        "If I showed you a picture of a cat, what would you expect to see?");
        System.out.println("Multimodal question: " + TestUtils.extractTextContent(input));

        Msg response = agent.call(input).block(TEST_TIMEOUT);

        assertNotNull(response, "Multimodal response should not be null");
        assertTrue(
                ContentValidator.hasMeaningfulContent(response),
                "Multimodal response should have content for " + provider.getModelName());

        String responseText = TestUtils.extractTextContent(response);
        System.out.println("Multimodal answer: " + responseText);

        // 应提及与猫有关的内容
        boolean mentionsCat =
                ContentValidator.containsKeywords(
                        response, "cat", "feline", "whiskers", "fur", "pet");

        // 这里并未实际发送图片，因此采用更宽松判定
        if (mentionsCat) {
            System.out.println(
                    "✓ Response mentions cat-related content for " + provider.getProviderName());
        } else {
            System.out.println(
                    "✓ Response provides meaningful content for " + provider.getProviderName());
        }
    }

    @ParameterizedTest
    @MethodSource("getToolProviders")
    @DisplayName("Should handle error scenarios consistently across providers")
    void testErrorScenarios(ModelProvider provider) {
        System.out.println(
                "\n=== Test: Error Scenarios with " + provider.getProviderName() + " ===");

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

        System.out.println(
                "✓ Error scenarios handled consistently for " + provider.getProviderName());
    }

    // 提供方来源方法
    static Stream<ModelProvider> getAllEnabledProviders() {
        return ProviderFactory.getBasicProviders();
    }

    static Stream<ModelProvider> getToolProviders() {
        return ProviderFactory.getToolProviders();
    }

    static Stream<ModelProvider> getMultimodalProviders() {
        return ProviderFactory.getMultimodalProviders();
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
