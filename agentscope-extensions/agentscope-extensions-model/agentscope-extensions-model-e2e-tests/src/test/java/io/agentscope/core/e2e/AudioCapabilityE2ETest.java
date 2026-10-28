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
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.tool.Toolkit;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** 不同模型提供方的音频能力 E2E 测试。<p>覆盖 AudioBlock 的 URL/Base64 输入、音频转录与理解、音频加文本多模态对话，以及 OpenAI gpt-4o-audio-preview、DashScope qwen3-omni-flash 和百炼 qwen-omni-turbo 等测试配置。 */
@Tag("e2e")
@Tag("audio")
@ExtendWith(E2ETestCondition.class)
@Execution(ExecutionMode.CONCURRENT)
@DisplayName("Audio Capability E2E Tests")
class AudioCapabilityE2ETest {

    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(300);

    // 来自 DashScope 文档的测试音频 URL
    private static final String TEST_AUDIO_URL =
            "https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250211/tixcef/cherry.wav";

    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getAudioProviders")
    @DisplayName("Should process audio input from URL")
    void testAudioInputFromURL(ModelProvider provider) {
        System.out.println(
                "\n=== Test: Audio Input from URL with " + provider.getProviderName() + " ===");
        System.out.println("Audio URL: " + TEST_AUDIO_URL);

        if (provider.getModelName().contains("gpt")) {
            System.out.println("OpenAI not support URL Audio input");
            return;
        }

        Toolkit toolkit = new Toolkit();
        ReActAgent agent = provider.createAgent("AudioURLTestAgent", toolkit);

        Msg userMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                List.of(
                                        TextBlock.builder()
                                                .text(
                                                        "Please describe what you hear in this"
                                                                + " audio.")
                                                .build(),
                                        AudioBlock.builder()
                                                .source(
                                                        URLSource.builder()
                                                                .url(TEST_AUDIO_URL)
                                                                .build())
                                                .build()))
                        .build();

        System.out.println("Sending audio analysis request...");

        Msg response = agent.call(userMsg).block(TEST_TIMEOUT);

        assertNotNull(response, "Response should not be null");
        assertTrue(
                ContentValidator.hasMeaningfulContent(response),
                "Response should have meaningful content");

        String responseText = TestUtils.extractTextContent(response);
        System.out.println(
                "Response: " + responseText.substring(0, Math.min(200, responseText.length())));

        // 验证模型已处理音频
        assertTrue(
                ContentValidator.meetsMinimumLength(response, 10),
                "Response should have reasonable length for " + provider.getModelName());

        System.out.println("✓ Audio URL input verified for " + provider.getProviderName());
    }

    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getAudioProviders")
    @DisplayName("Should process audio input from Base64")
    void testAudioInputFromBase64(ModelProvider provider) {
        if (provider.getClass().getName().contains("Gpt4oAudioPreviewMultiAgentOpenAI")) {
            // OpenAI 可能返回格式不符的结果
            return;
        }
        System.out.println(
                "\n=== Test: Audio Input from Base64 with " + provider.getProviderName() + " ===");

        try {
            // 下载音频并转换为 Base64
            String base64Audio = downloadAndEncodeAudio(TEST_AUDIO_URL);
            if (provider.getModelName().contains("qwen")) {
                base64Audio = "data:;base64," + base64Audio;
            }
            System.out.println("Downloaded audio, base64 length: " + base64Audio.length());

            Toolkit toolkit = new Toolkit();
            ReActAgent agent = provider.createAgent("AudioBase64TestAgent", toolkit);

            Msg userMsg =
                    Msg.builder()
                            .role(MsgRole.USER)
                            .content(
                                    List.of(
                                            TextBlock.builder()
                                                    .text(
                                                            "Please transcribe or describe this"
                                                                    + " audio.")
                                                    .build(),
                                            AudioBlock.builder()
                                                    .source(
                                                            Base64Source.builder()
                                                                    .data(base64Audio)
                                                                    .mediaType("audio/wav")
                                                                    .build())
                                                    .build()))
                            .build();

            System.out.println("Sending Base64 audio request...");

            Msg response = agent.call(userMsg).block(TEST_TIMEOUT);

            assertNotNull(response, "Response should not be null");
            assertTrue(
                    ContentValidator.hasMeaningfulContent(response),
                    "Response should have meaningful content");

            String responseText = TestUtils.extractTextContent(response);
            System.out.println("Response: " + responseText);

            // 验证模型已处理音频
            assertTrue(
                    ContentValidator.meetsMinimumLength(response, 10),
                    "Response should have reasonable length for " + provider.getModelName());

            System.out.println("✓ Audio Base64 input verified for " + provider.getProviderName());

        } catch (IOException e) {
            System.out.println(
                    "⚠ Skipping Base64 audio test for "
                            + provider.getProviderName()
                            + ": "
                            + e.getMessage());
        }
    }

    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getAudioProviders")
    @DisplayName("Should handle mixed audio and text conversation")
    void testAudioTextMixedConversation(ModelProvider provider) throws IOException {
        System.out.println(
                "\n=== Test: Audio + Text Mixed Conversation with "
                        + provider.getProviderName()
                        + " ===");

        if (provider.getModelName().contains("gpt")) {
            System.out.println("OpenAI might return wrong format result");
            return;
        }

        // 下载音频并转换为 Base64
        String base64Audio = downloadAndEncodeAudio(TEST_AUDIO_URL);
        if (provider.getModelName().contains("qwen")) {
            base64Audio = "data:;base64," + base64Audio;
        }
        System.out.println("Downloaded audio, base64 length: " + base64Audio.length());

        Toolkit toolkit = new Toolkit();
        ReActAgent agent = provider.createAgent("MixedAudioAgent", toolkit);

        // 第 1 轮：音频输入
        Msg audioMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                List.of(
                                        TextBlock.builder()
                                                .text("Listen to this audio and remember it.")
                                                .build(),
                                        AudioBlock.builder()
                                                .source(
                                                        Base64Source.builder()
                                                                .data(base64Audio)
                                                                .mediaType("audio/wav")
                                                                .build())
                                                .build()))
                        .build();

        System.out.println("Round 1: Sending audio");
        Msg response1 = agent.call(audioMsg).block(TEST_TIMEOUT);
        assertNotNull(response1);
        System.out.println(
                "Audio response: "
                        + TestUtils.extractTextContent(response1)
                                .substring(
                                        0,
                                        Math.min(
                                                100,
                                                TestUtils.extractTextContent(response1).length())));

        // 第 2 轮：纯文本追问
        Msg followUpMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                List.of(
                                        TextBlock.builder()
                                                .text("What did you hear in the previous audio?")
                                                .build()))
                        .build();

        System.out.println("Round 2: Follow-up question");
        Msg response2 = agent.call(followUpMsg).block(TEST_TIMEOUT);
        assertNotNull(response2);

        String response2Text = TestUtils.extractTextContent(response2);
        System.out.println("Follow-up response: " + response2Text);

        // 验证智能体记得音频上下文
        assertTrue(
                ContentValidator.hasMeaningfulContent(response2),
                "Follow-up response should have meaningful content for " + provider.getModelName());

        System.out.println(
                "✓ Audio + text conversation verified for " + provider.getProviderName());
    }

    @Test
    @DisplayName("Should verify audio provider availability")
    void testAudioProviderAvailability() {
        System.out.println("\n=== Test: Audio Provider Availability ===");

        long enabledAudioProviders = ProviderFactory.getAudioProviders().count();

        System.out.println("Enabled audio providers: " + enabledAudioProviders);

        // 设置了 API 密钥时，至少应有一个音频提供方可用
        assertTrue(
                enabledAudioProviders > 0,
                "At least one audio provider should be available when API keys are configured");

        System.out.println("✓ Audio provider availability verified");
    }

    /**
     * 从 URL 下载音频并编码为 Base64 的辅助方法。
     * @param audioUrl 音频 URL
     * @return Base64 编码音频字符串
     * @throws IOException 下载失败时
     */
    private String downloadAndEncodeAudio(String audioUrl) throws IOException {
        try (InputStream is = new URL(audioUrl).openStream()) {
            byte[] audioBytes = is.readAllBytes();
            return Base64.getEncoder().encodeToString(audioBytes);
        }
    }
}
