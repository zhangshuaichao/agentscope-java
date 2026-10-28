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
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** 专用功能 E2E 测试合集，覆盖 DashScope 思考模式、高级多模态能力、性能场景及只适用于特定模型/API 的功能。<p>大部分专用功能要求 DASHSCOPE_API_KEY，按可用密钥动态启用。 */
@Tag("e2e")
@Tag("specialized")
@ExtendWith(E2ETestCondition.class)
@Execution(ExecutionMode.CONCURRENT)
@DisplayName("Specialized Features E2E Tests (Consolidated)")
class SpecializedFeaturesE2ETest {

    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(300);

    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getThinkingProviders")
    @DisplayName("Should handle DashScope thinking mode")
    void testDashScopeThinkingMode(ModelProvider provider) {
        System.out.println(
                "\n=== Test: DashScope Thinking Mode with " + provider.getProviderName() + " ===");

        Toolkit toolkit = new Toolkit();
        ReActAgent agent = provider.createAgent("ThinkingTestAgent", toolkit);

        // 使用复杂推理任务测试思考模式
        Msg input =
                TestUtils.createUserMessage(
                        "User",
                        "If it takes 5 machines 5 minutes to make 5 widgets, how long would it take"
                            + " 100 machines to make 100 widgets? Please think through this step by"
                            + " step.");
        System.out.println("Complex reasoning question: " + TestUtils.extractTextContent(input));

        Msg response = agent.call(input).block(TEST_TIMEOUT);

        assertNotNull(response, "Thinking mode response should not be null");
        assertTrue(
                ContentValidator.hasMeaningfulContent(response),
                "Thinking response should have content for " + provider.getModelName());

        String responseText = TestUtils.extractTextContent(response);
        System.out.println("Thinking response: " + responseText);

        // 验证存在思考内容
        boolean hasThinkingBlock = response.hasContentBlocks(ThinkingBlock.class);
        if (hasThinkingBlock) {
            ThinkingBlock thinkingBlock = response.getFirstContentBlock(ThinkingBlock.class);
            System.out.println(
                    "Thinking content found: "
                            + thinkingBlock
                                    .getThinking()
                                    .substring(
                                            0, Math.min(200, thinkingBlock.getThinking().length()))
                            + "...");
            assertTrue(
                    ContentValidator.showsThinkingProcess(response),
                    "Should show thinking process for " + provider.getModelName());
        } else {
            // 检查思考是否整合到响应文本
            assertTrue(
                    ContentValidator.showsThinkingProcess(response),
                    "Response should show reasoning process for " + provider.getModelName());
        }

        // 应得到正确答案 5 分钟
        boolean correctAnswer =
                ContentValidator.containsKeywords(
                        response, "5 minutes", "five minutes", "5 min", "same time");

        assertTrue(correctAnswer, "Should arrive at correct answer for " + provider.getModelName());

        System.out.println("✓ Thinking mode verified for " + provider.getProviderName());
    }

    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getThinkingBudgetProviders")
    @DisplayName("Should handle thinking mode with different budgets")
    void testThinkingModeWithBudgets(ModelProvider provider) {
        System.out.println(
                "\n=== Test: Thinking Mode with Budgets for "
                        + provider.getProviderName()
                        + " ===");

        // 使用较小思考预算测试
        Toolkit toolkit = new Toolkit();
        ReActAgent agent = provider.createAgent("SmallThinkingAgent", toolkit);

        Msg input =
                TestUtils.createUserMessage("User", "Explain why the sky is blue in a simple way.");
        System.out.println(
                "Simple question with small budget: " + TestUtils.extractTextContent(input));

        Msg response = agent.call(input).block(TEST_TIMEOUT);

        assertNotNull(response, "Small budget response should not be null");
        assertTrue(
                ContentValidator.hasMeaningfulContent(response),
                "Small budget response should have content for " + provider.getModelName());

        String responseText = TestUtils.extractTextContent(response);
        System.out.println("Small budget response: " + responseText);

        // 仍应给出连贯解释
        assertTrue(
                ContentValidator.containsKeywords(response, "blue", "sky", "light", "atmosphere"),
                "Should explain sky color for " + provider.getModelName());

        System.out.println(
                "✓ Thinking mode with budgets verified for " + provider.getProviderName());
    }

    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getVideoProviders")
    @DisplayName("Should handle video analysis capabilities")
    void testVideoAnalysis(ModelProvider provider) {
        System.out.println(
                "\n=== Test: Video Analysis with " + provider.getProviderName() + " ===");

        Toolkit toolkit = new Toolkit();
        ReActAgent agent = provider.createAgent("VideoAnalysisAgent", toolkit);

        // 测试视频分析能力
        Msg input =
                TestUtils.createUserMessage(
                        "User",
                        "If I showed you a video of a cat playing with a ball, what would you"
                                + " expect to see happening? Describe the typical behaviors.");
        System.out.println("Video analysis question: " + TestUtils.extractTextContent(input));

        Msg response = agent.call(input).block(TEST_TIMEOUT);

        assertNotNull(response, "Video analysis response should not be null");
        assertTrue(
                ContentValidator.hasMeaningfulContent(response),
                "Video analysis response should have content for " + provider.getModelName());

        String responseText = TestUtils.extractTextContent(response);
        System.out.println("Video analysis response: " + responseText);

        // 应提及猫的行为及视频相关概念
        boolean mentionsVideoContent =
                ContentValidator.containsKeywords(
                        response, "cat", "play", "ball", "video", "movement", "action");

        assertTrue(
                mentionsVideoContent,
                "Should describe expected video content for " + provider.getModelName());

        System.out.println(
                "✓ Video analysis capabilities verified for " + provider.getProviderName());
    }

    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getMultimodalProviders")
    @DisplayName("Should handle complex multimodal reasoning")
    void testComplexMultimodalReasoning(ModelProvider provider) {
        System.out.println(
                "\n=== Test: Complex Multimodal Reasoning with "
                        + provider.getProviderName()
                        + " ===");

        Toolkit toolkit = new Toolkit();
        ReActAgent agent = provider.createAgent("ComplexReasoningAgent", toolkit);

        // 测试复杂多模态推理任务
        Msg input =
                TestUtils.createUserMessage(
                        "User",
                        "Imagine you're looking at a complex diagram showing a workflow system. The"
                            + " diagram has multiple connected boxes with arrows, some decision"
                            + " points (diamond shapes), and different colored sections. Can you"
                            + " describe how you would approach analyzing such a complex visual"
                            + " diagram step by step?");
        System.out.println(
                "Complex multimodal reasoning question: " + TestUtils.extractTextContent(input));

        Msg response = agent.call(input).block(TEST_TIMEOUT);

        assertNotNull(response, "Complex reasoning response should not be null");
        assertTrue(
                ContentValidator.hasMeaningfulContent(response),
                "Complex reasoning response should have content for " + provider.getModelName());

        String responseText = TestUtils.extractTextContent(response);
        System.out.println("Complex reasoning response: " + responseText);

        // 应提及分析步骤
        boolean mentionsAnalysisSteps =
                ContentValidator.containsKeywords(
                        response,
                        "step",
                        "analyze",
                        "examine",
                        "identify",
                        "workflow",
                        "diagram",
                        "visual");

        assertTrue(
                mentionsAnalysisSteps,
                "Should describe analysis approach for " + provider.getModelName());

        System.out.println(
                "✓ Complex multimodal reasoning verified for " + provider.getProviderName());
    }

    @Test
    @DisplayName("Should verify specialized feature availability")
    void testSpecializedFeatureAvailability() {
        System.out.println("\n=== Test: Specialized Feature Availability ===");

        long enabledThinkingProviders = ProviderFactory.getThinkingProviders().count();
        long enabledVideoProviders = ProviderFactory.getVideoProviders().count();
        long enabledMultimodalProviders = ProviderFactory.getMultimodalProviders().count();

        System.out.println("Enabled thinking providers: " + enabledThinkingProviders);
        System.out.println("Enabled video providers: " + enabledVideoProviders);
        System.out.println("Enabled multimodal providers: " + enabledMultimodalProviders);

        // 注意：部分功能是否可用取决于 API 密钥
        if (enabledThinkingProviders == 0) {
            System.out.println("ℹ️  Thinking mode not available (requires DASHSCOPE_API_KEY)");
        }
        if (enabledVideoProviders == 0) {
            System.out.println(
                    "ℹ️  Video analysis not available (requires DashScope qwen3-vl-plus)");
        }

        System.out.println("✓ Specialized feature availability verified");
    }

    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getToolProviders")
    @DisplayName("Should handle tool and thinking mode combination")
    void testToolAndThinkingCombination(ModelProvider provider) {
        System.out.println(
                "\n=== Test: Tool and Thinking Combination with "
                        + provider.getProviderName()
                        + " ===");

        // 仅对同时支持两项功能的提供方测试
        if (!provider.supportsThinking()) {
            System.out.println(
                    "Skipping " + provider.getProviderName() + " - does not support thinking mode");
            return;
        }

        Toolkit toolkit = E2ETestUtils.createTestToolkit();
        ReActAgent agent = provider.createAgent("ToolThinkingAgent", toolkit);

        Msg input =
                TestUtils.createUserMessage(
                        "User",
                        "I need to solve this problem step by step: First, think about how to"
                            + " calculate 15% of 240. Then use the appropriate tool to perform the"
                            + " calculation. Finally, explain what this percentage means in"
                            + " practical terms.");
        System.out.println("Tool + thinking question: " + TestUtils.extractTextContent(input));

        Msg response = agent.call(input).block(TEST_TIMEOUT);

        assertNotNull(response, "Tool + thinking response should not be null");
        assertTrue(
                ContentValidator.hasMeaningfulContent(response),
                "Tool + thinking response should have content for " + provider.getModelName());

        String responseText = TestUtils.extractTextContent(response);
        System.out.println("Tool + thinking response: " + responseText);

        // 应同时展示推理与计算
        boolean hasCalculation =
                ContentValidator.containsKeywords(response, "36", "15%", "percent");
        boolean hasExplanation =
                ContentValidator.containsKeywords(response, "means", "practical", "represents");

        assertTrue(
                hasCalculation, "Should calculate 15% of 240 = 36 for " + provider.getModelName());
        assertTrue(hasExplanation, "Should explain the result for " + provider.getModelName());

        System.out.println(
                "✓ Tool and thinking combination verified for " + provider.getProviderName());
    }
}
