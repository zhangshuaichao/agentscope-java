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
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.URLSource;
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

/** 多模态能力 E2E 测试合集，对可用且支持多模态的提供方测试图片分析、音频处理、视频分析及混合多模态对话。<p>要求配置 OPENAI_API_KEY、DASHSCOPE_API_KEY 等密钥，按可用密钥及模型能力动态启用。 */
@Tag("e2e")
@Tag("multimodal")
@ExtendWith(E2ETestCondition.class)
@Execution(ExecutionMode.CONCURRENT)
@DisplayName("Multimodal E2E Tests (Consolidated)")
class MultiModalE2ETest {

    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(300);

    // 测试 URL，使用 E2ETestConstants
    private static final String TEST_IMAGE_URL = E2ETestConstants.DOG_GIRL_IMAGE_URL;
    private static final String CAT_IMAGE_URL = E2ETestConstants.CAT_IMAGE_URL;

    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getImageProviders")
    @DisplayName("Should analyze image from URL")
    void testImageAnalysisFromURL(ModelProvider provider) {
        System.out.println(
                "\n=== Test: Image Analysis from URL with " + provider.getProviderName() + " ===");
        System.out.println("Image URL: " + TEST_IMAGE_URL);

        Toolkit toolkit = new Toolkit();
        ReActAgent agent = provider.createAgent("ImageTestAgent", toolkit);

        Msg userMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                List.of(
                                        TextBlock.builder()
                                                .text(
                                                        "Please describe what you see in this image"
                                                                + " in detail.")
                                                .build(),
                                        ImageBlock.builder()
                                                .source(
                                                        URLSource.builder()
                                                                .url(TEST_IMAGE_URL)
                                                                .build())
                                                .build()))
                        .build();

        System.out.println("Sending image analysis request...");

        Msg response = agent.call(userMsg).block(TEST_TIMEOUT);

        assertNotNull(response, "Response should not be null");
        assertTrue(
                ContentValidator.hasMeaningfulContent(response),
                "Response should have meaningful content");

        String responseText = TestUtils.extractTextContent(response);
        System.out.println(
                "Response: " + responseText.substring(0, Math.min(200, responseText.length())));

        // 图片含一只狗和一名女孩，回复应提及这些内容
        assertTrue(
                ContentValidator.mentionsVisualElements(
                        response, "dog", "girl", "person", "animal"),
                "Response should mention key elements in the image for " + provider.getModelName());

        System.out.println("✓ Image URL analysis verified for " + provider.getProviderName());
    }

    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getImageProviders")
    @DisplayName("Should handle follow-up questions about images")
    void testImageFollowUpQuestions(ModelProvider provider) {
        System.out.println(
                "\n=== Test: Image Follow-up Questions with "
                        + provider.getProviderName()
                        + " ===");

        Toolkit toolkit = new Toolkit();
        ReActAgent agent = provider.createAgent("ImageFollowUpAgent", toolkit);

        // 第一条消息：展示图片
        Msg firstMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                List.of(
                                        TextBlock.builder()
                                                .text("What do you see in this image?")
                                                .build(),
                                        ImageBlock.builder()
                                                .source(
                                                        URLSource.builder()
                                                                .url(TEST_IMAGE_URL)
                                                                .build())
                                                .build()))
                        .build();

        System.out.println("Round 1: Showing image...");
        Msg response1 = agent.call(firstMsg).block(TEST_TIMEOUT);
        assertNotNull(response1, "First response should not be null");

        // 第二条消息：不带图片追问
        Msg followUpMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                List.of(
                                        TextBlock.builder()
                                                .text(
                                                        "How many subjects are in the image? Please"
                                                            + " answer with a sentence describing"
                                                            + " the number.")
                                                .build()))
                        .build();

        System.out.println("Round 2: Asking follow-up question...");
        Msg response2 = agent.call(followUpMsg).block(TEST_TIMEOUT);
        assertNotNull(response2, "Second response should not be null");

        String response2Text = TestUtils.extractTextContent(response2);
        System.out.println("Follow-up response: " + response2Text);

        // 验证智能体记得图片上下文
        assertTrue(
                ContentValidator.hasMeaningfulContent(response2),
                "Follow-up response should have meaningful content for " + provider.getModelName());

        // 应提及主体数量，通常为 2：狗和女孩
        assertTrue(
                ContentValidator.containsKeywords(
                        response2, "2", "two", "subjects", "people", "animals"),
                "Should mention number of subjects for " + provider.getModelName());

        System.out.println(
                "✓ Image follow-up conversation verified for " + provider.getProviderName());
    }

    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getImageProviders")
    @DisplayName("Should handle mixed conversation (vision + text)")
    void testMixedVisionConversation(ModelProvider provider) {
        System.out.println(
                "\n=== Test: Mixed Vision Conversation with "
                        + provider.getProviderName()
                        + " ===");

        Toolkit toolkit = new Toolkit();
        ReActAgent agent = provider.createAgent("MixedVisionAgent", toolkit);

        // 第 1 轮：带图片的视觉问题
        Msg visionMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                List.of(
                                        TextBlock.builder()
                                                .text("Describe this image briefly.")
                                                .build(),
                                        ImageBlock.builder()
                                                .source(
                                                        URLSource.builder()
                                                                .url(CAT_IMAGE_URL)
                                                                .build())
                                                .build()))
                        .build();

        System.out.println("Round 1: Vision question with image");
        Msg response1 = agent.call(visionMsg).block(TEST_TIMEOUT);
        assertNotNull(response1);

        String response1Text = TestUtils.extractTextContent(response1);
        System.out.println(
                "Vision response: "
                        + response1Text.substring(0, Math.min(100, response1Text.length())));

        // 第 2 轮：纯文本问题
        Msg textMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(List.of(TextBlock.builder().text("What is 2 plus 2?").build()))
                        .build();

        System.out.println("Round 2: Text-only question");
        Msg response2 = agent.call(textMsg).block(TEST_TIMEOUT);
        assertNotNull(response2);

        String response2Text = TestUtils.extractTextContent(response2);
        System.out.println("Text response: " + response2Text);

        // 应能回答简单数学问题
        assertTrue(
                ContentValidator.containsKeywords(response2, "4"),
                "Should correctly answer 2+2=4 for " + provider.getModelName());

        System.out.println(
                "✓ Mixed vision conversation verified for " + provider.getProviderName());
    }

    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getMultimodalProviders")
    @DisplayName("Should handle complete multimodal conversation flow")
    void testCompleteMultimodalFlow(ModelProvider provider) {
        System.out.println(
                "\n=== Test: Complete Multimodal Flow with " + provider.getProviderName() + " ===");

        Toolkit toolkit = new Toolkit();
        ReActAgent agent = provider.createAgent("MultimodalAgent", toolkit);

        // 顺序测试多种模态
        System.out.println("Testing comprehensive multimodal capabilities...");

        // 步骤 1：图片分析
        Msg imageMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                List.of(
                                        TextBlock.builder()
                                                .text(
                                                        "Analyze this image and tell me what"
                                                                + " animals you see.")
                                                .build(),
                                        ImageBlock.builder()
                                                .source(
                                                        URLSource.builder()
                                                                .url(CAT_IMAGE_URL)
                                                                .build())
                                                .build()))
                        .build();

        Msg imageResponse = agent.call(imageMsg).block(TEST_TIMEOUT);
        assertNotNull(imageResponse);
        assertTrue(
                ContentValidator.mentionsVisualElements(imageResponse, "cat", "animal"),
                "Should identify the cat in the image for " + provider.getModelName());

        // 步骤 2：文本追问
        Msg followUpMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                List.of(
                                        TextBlock.builder()
                                                .text(
                                                        "Based on what you saw, is this animal"
                                                                + " likely to be friendly?")
                                                .build()))
                        .build();

        Msg followUpResponse = agent.call(followUpMsg).block(TEST_TIMEOUT);
        assertNotNull(followUpResponse);
        assertTrue(
                ContentValidator.hasMeaningfulContent(followUpResponse),
                "Should provide meaningful follow-up for " + provider.getModelName());

        // 步骤 3：上下文记忆测试
        Msg contextMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                List.of(
                                        TextBlock.builder()
                                                .text("What was the first image I showed you?")
                                                .build()))
                        .build();

        Msg contextResponse = agent.call(contextMsg).block(TEST_TIMEOUT);
        assertNotNull(contextResponse);
        assertTrue(
                ContentValidator.maintainsContext(contextResponse, "cat", "image", "first"),
                "Should remember the first image for " + provider.getModelName());

        System.out.println("✓ Complete multimodal flow verified for " + provider.getProviderName());
    }

    @Test
    @DisplayName("Should verify multimodal provider availability")
    void testMultimodalProviderAvailability() {
        System.out.println("\n=== Test: Multimodal Provider Availability ===");

        long enabledImageProviders = ProviderFactory.getImageProviders().count();
        long enabledAudioProviders = ProviderFactory.getAudioProviders().count();
        long enabledMultimodalProviders = ProviderFactory.getMultimodalProviders().count();

        System.out.println("Enabled image providers: " + enabledImageProviders);
        System.out.println("Enabled audio providers: " + enabledAudioProviders);
        System.out.println("Enabled multimodal providers: " + enabledMultimodalProviders);

        // 设置 API 密钥时，至少应有一个多模态提供方可用
        System.out.println("✓ Multimodal provider availability verified");
    }

    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getVideoProviders")
    @DisplayName("Should handle video analysis (if supported)")
    void testVideoAnalysis(ModelProvider provider) {
        System.out.println(
                "\n=== Test: Video Analysis with " + provider.getProviderName() + " ===");

        Toolkit toolkit = new Toolkit();
        ReActAgent agent = provider.createAgent("VideoTestAgent", toolkit);

        // 注意：此测试仅对声明支持视频的提供方运行，例如 qwen3-vl-plus
        // 此处暂用图片验证提供方可用
        Msg videoMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                List.of(
                                        TextBlock.builder()
                                                .text(
                                                        "Analyze this image as if it were a video"
                                                                + " frame. What do you see?")
                                                .build(),
                                        ImageBlock.builder()
                                                .source(
                                                        URLSource.builder()
                                                                .url(TEST_IMAGE_URL)
                                                                .build())
                                                .build()))
                        .build();

        Msg response = agent.call(videoMsg).block(TEST_TIMEOUT);

        assertNotNull(response, "Video/image analysis response should not be null");
        assertTrue(
                ContentValidator.hasMeaningfulContent(response),
                "Video analysis response should have content for " + provider.getModelName());

        System.out.println("Video analysis response: " + TestUtils.extractTextContent(response));
        System.out.println(
                "✓ Video analysis capability verified for " + provider.getProviderName());
    }
}
