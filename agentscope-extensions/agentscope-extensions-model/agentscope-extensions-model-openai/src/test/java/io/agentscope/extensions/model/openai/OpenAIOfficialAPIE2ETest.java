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
package io.agentscope.extensions.model.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.extensions.model.openai.formatter.OpenAIChatFormatter;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import reactor.test.StepVerifier;

/** 使用官方 OpenAI API 的 OpenAIChatModel 端到端集成测试。<p>实际调用 https://api.openai.com，验证 HTTP 实现的非流式/流式补全、默认基础 URL、选项应用及多轮对话。<p>要求设置 OPENAI_API_KEY、可用网络及有效 API 额度。标记为 e2e，真实调用可能产生费用。<p>【学习提示】这里是外部系统集成测试，不是离线模拟；阅读或翻译测试不等于执行真实 API 调用。 */
@Tag("e2e")
@Tag("openai")
@Tag("official")
@DisplayName("OpenAI Official API E2E Tests (Real OpenAI API)")
@EnabledIfEnvironmentVariable(
        named = "OPENAI_API_KEY",
        matches = ".+",
        disabledReason = "Requires OPENAI_API_KEY environment variable (official OpenAI API key)")
class OpenAIOfficialAPIE2ETest {

    private static final String DEFAULT_OPENAI_BASE_URL = "https://api.openai.com";
    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(30);

    private OpenAIChatModel model;
    private OpenAIChatModel streamingModel;
    private OpenAIChatModel defaultBaseUrlModel; // 测试默认基础 URL
    private String openaiBaseUrl;

    @BeforeEach
    void setUp() {
        String apiKey = System.getenv("OPENAI_API_KEY");
        assumeTrue(apiKey != null && !apiKey.isEmpty(), "OPENAI_API_KEY must be set");

        // 从环境变量获取基础 URL，否则回退到默认值
        openaiBaseUrl = System.getenv("OPENAI_BASE_URL");
        if (openaiBaseUrl == null || openaiBaseUrl.isEmpty()) {
            openaiBaseUrl = DEFAULT_OPENAI_BASE_URL;
        }

        // 显式设置官方基础 URL 的模型
        model =
                OpenAIChatModel.builder().apiKey(apiKey).modelName("gpt-4o-mini").stream(false)
                        .baseUrl(openaiBaseUrl)
                        .formatter(new OpenAIChatFormatter())
                        .build();

        // 显式设置官方基础 URL 的流式模型
        streamingModel =
                OpenAIChatModel.builder().apiKey(apiKey).modelName("gpt-4o-mini").stream(true)
                        .baseUrl(openaiBaseUrl)
                        .formatter(new OpenAIChatFormatter())
                        .build();

        // 使用默认基础 URL 的模型（应为 https://api.openai.com）
        defaultBaseUrlModel =
                OpenAIChatModel.builder().apiKey(apiKey).modelName("gpt-4o-mini").stream(false)
                        // 未设置 baseUrl，应使用默认值
                        .formatter(new OpenAIChatFormatter())
                        .build();

        System.out.println("=== OpenAI Official API E2E Test Setup Complete ===");
        System.out.println("Model: gpt-4o-mini");
        System.out.println("Explicit Base URL: " + openaiBaseUrl);
        System.out.println("Using: Official OpenAI API");
    }

    @AfterEach
    void tearDown() throws IOException {
        // 无状态模型无需清理
    }

    @Test
    @DisplayName("Should use default base URL (https://api.openai.com)")
    void testDefaultBaseUrl() {
        // 无状态模型没有 getBaseUrl() 方法
        // baseUrl 通过 GenerateOptions 随每个请求传递
        // 此测试仅验证模型可创建和使用
        assertNotNull(defaultBaseUrlModel, "Model should not be null");
        System.out.println("✓ Model created successfully");
    }

    @Test
    @DisplayName("Should handle non-streaming call with official API")
    void testNonStreamingCall() {
        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(
                                        List.of(
                                                TextBlock.builder()
                                                        .text("Say 'Hello, World!'")
                                                        .build()))
                                .build());

        StepVerifier.create(model.stream(messages, null, null))
                .assertNext(
                        response -> {
                            assertNotNull(response);
                            assertNotNull(response.getContent());
                            assertTrue(response.getContent().size() > 0);
                            String text = ((TextBlock) response.getContent().get(0)).getText();
                            assertNotNull(text);
                            System.out.println("Official API Response: " + text);
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle streaming call with official API")
    void testStreamingCall() {
        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(
                                        List.of(
                                                TextBlock.builder()
                                                        .text("Count from 1 to 5")
                                                        .build()))
                                .build());

        StepVerifier.create(streamingModel.stream(messages, null, null))
                .assertNext(
                        response -> {
                            assertNotNull(response);
                            assertNotNull(response.getContent());
                        })
                .thenConsumeWhile(
                        response -> {
                            // 继续消费分块
                            return true;
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle non-streaming call with default base URL")
    void testDefaultBaseUrlCall() {
        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(List.of(TextBlock.builder().text("Say 'test'").build()))
                                .build());

        StepVerifier.create(defaultBaseUrlModel.stream(messages, null, null))
                .assertNext(
                        response -> {
                            assertNotNull(response);
                            assertNotNull(response.getContent());
                            assertTrue(response.getContent().size() > 0);
                            String text = ((TextBlock) response.getContent().get(0)).getText();
                            assertNotNull(text);
                            System.out.println("Default Base URL Response: " + text);
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle multi-turn conversation with official API")
    void testMultiTurnConversation() {
        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(
                                        List.of(
                                                TextBlock.builder()
                                                        .text("My name is Eve. Remember this.")
                                                        .build()))
                                .build(),
                        Msg.builder()
                                .role(MsgRole.ASSISTANT)
                                .content(
                                        List.of(
                                                TextBlock.builder()
                                                        .text("Hello Eve! I'll remember your name.")
                                                        .build()))
                                .build(),
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(
                                        List.of(
                                                TextBlock.builder()
                                                        .text("What is my name?")
                                                        .build()))
                                .build());

        StepVerifier.create(model.stream(messages, null, null))
                .assertNext(
                        response -> {
                            assertNotNull(response);
                            assertNotNull(response.getContent());
                            String text = ((TextBlock) response.getContent().get(0)).getText();
                            assertNotNull(text);
                            assertTrue(
                                    text.toLowerCase().contains("eve"),
                                    "Response should contain 'Eve'");
                            System.out.println("Multi-turn response: " + text);
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should return model name")
    void testGetModelName() {
        assertEquals("gpt-4o-mini", model.getModelName());
        assertEquals("gpt-4o-mini", streamingModel.getModelName());
        assertEquals("gpt-4o-mini", defaultBaseUrlModel.getModelName());
    }

    @Test
    @DisplayName("Should handle options with official API")
    void testApplyOptions() {
        GenerateOptions options = GenerateOptions.builder().temperature(0.1).maxTokens(50).build();

        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(List.of(TextBlock.builder().text("Say 'Four'").build()))
                                .build());

        StepVerifier.create(model.stream(messages, null, options))
                .assertNext(
                        response -> {
                            assertNotNull(response);
                            assertNotNull(response.getContent());
                            if (response.getContent().size() > 0) {
                                String text = ((TextBlock) response.getContent().get(0)).getText();
                                assertNotNull(text);
                                System.out.println("Official API Response with options: " + text);
                            }
                        })
                .verifyComplete();
    }
}
