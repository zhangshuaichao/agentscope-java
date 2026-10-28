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
package io.agentscope.core.embedding.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonField;
import com.openai.models.embeddings.CreateEmbeddingResponse;
import com.openai.models.embeddings.CreateEmbeddingResponse.Usage;
import com.openai.models.embeddings.Embedding;
import com.openai.models.embeddings.EmbeddingCreateParams;
import com.openai.services.blocking.EmbeddingService;
import io.agentscope.core.embedding.EmbeddingException;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ExecutionConfig;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import reactor.test.StepVerifier;

/**
 * 专门测试 OpenAITextEmbedding 的 embed 方法，以提高代码覆盖率。
 */
@Tag("unit")
@DisplayName("OpenAITextEmbedding Embed Method Tests")
class OpenAITextEmbeddingEmbedTest {

    private static final String TEST_MODEL_NAME = "text-embedding-3-small";
    private static final int TEST_DIMENSIONS = 1536;

    private OpenAITextEmbedding model;

    @BeforeEach
    void setUp() {
        // 使用真实的 OpenAITextEmbedding 实例，避免 mock 掉被测方法
        model =
                OpenAITextEmbedding.builder()
                        .apiKey("mock_api_key")
                        .modelName(TEST_MODEL_NAME)
                        .dimensions(TEST_DIMENSIONS)
                        .executionConfig(ExecutionConfig.builder().maxAttempts(1).build())
                        .build();
    }

    @AfterEach
    void tearDown() {
        model.close();
    }

    @Test
    @DisplayName("Should successfully generate embedding for valid text")
    void testSuccessfulEmbeddingGeneration() {
        // 准备模拟响应
        Embedding mockEmbedding = mock(Embedding.class);
        when(mockEmbedding.embedding()).thenReturn(Arrays.asList(0.1f, 0.2f, 0.3f));

        CreateEmbeddingResponse mockResponse = mock(CreateEmbeddingResponse.class);
        when(mockResponse.data()).thenReturn(Arrays.asList(mockEmbedding));

        // 模拟 OpenAI 客户端的静态 builder -> builder -> client -> embeddings 调用链
        try (MockedStatic<OpenAIOkHttpClient> mockedClient =
                Mockito.mockStatic(OpenAIOkHttpClient.class)) {

            OpenAIOkHttpClient.Builder mockBuilder = mock(OpenAIOkHttpClient.Builder.class);
            OpenAIClient mockOpenAIClient = mock(OpenAIClient.class);
            EmbeddingService mockEmbeddings = mock(EmbeddingService.class);

            when(mockBuilder.build()).thenReturn(mockOpenAIClient);
            when(mockBuilder.apiKey(any(String.class))).thenReturn(mockBuilder);
            when(mockBuilder.baseUrl(any(String.class))).thenReturn(mockBuilder);
            when(mockBuilder.putHeader(any(), any())).thenReturn(mockBuilder);

            mockedClient.when(OpenAIOkHttpClient::builder).thenReturn(mockBuilder);

            when(mockOpenAIClient.embeddings()).thenReturn(mockEmbeddings);
            when(mockEmbeddings.create(any(EmbeddingCreateParams.class))).thenReturn(mockResponse);

            TextBlock textBlock = TextBlock.builder().text("Hello, world!").build();

            StepVerifier.create(model.embed(textBlock))
                    .assertNext(
                            response -> {
                                assertNotNull(response);
                                assertNotNull(response.getEmbedding());
                                // 返回的向量长度等于返回数据长度（3），覆盖维度不匹配分支
                                assertEquals(3, response.getEmbedding().length);
                                assertEquals(0.1, response.getEmbedding()[0], 0.001);
                                assertEquals(0.2, response.getEmbedding()[1], 0.001);
                                assertEquals(0.3, response.getEmbedding()[2], 0.001);
                            })
                    .verifyComplete();
        }
    }

    @Test
    @DisplayName("Should handle null response from API")
    void testNullApiResponse() {
        // 模拟 OpenAI 客户端的静态 builder -> builder -> client -> embeddings 调用链
        try (MockedStatic<OpenAIOkHttpClient> mockedClient =
                Mockito.mockStatic(OpenAIOkHttpClient.class)) {

            OpenAIOkHttpClient.Builder mockBuilder = mock(OpenAIOkHttpClient.Builder.class);
            OpenAIClient mockOpenAIClient = mock(OpenAIClient.class);
            EmbeddingService mockEmbeddings = mock(EmbeddingService.class);

            when(mockBuilder.build()).thenReturn(mockOpenAIClient);
            when(mockBuilder.apiKey(any(String.class))).thenReturn(mockBuilder);
            when(mockBuilder.baseUrl(any(String.class))).thenReturn(mockBuilder);
            when(mockBuilder.putHeader(any(), any())).thenReturn(mockBuilder);

            mockedClient.when(OpenAIOkHttpClient::builder).thenReturn(mockBuilder);

            when(mockOpenAIClient.embeddings()).thenReturn(mockEmbeddings);
            when(mockEmbeddings.create(any(EmbeddingCreateParams.class))).thenReturn(null);

            TextBlock textBlock = TextBlock.builder().text("Hello, world!").build();

            StepVerifier.create(model.embed(textBlock))
                    .expectErrorSatisfies(
                            throwable -> {
                                assertTrue(throwable instanceof EmbeddingException);
                                assertTrue(
                                        throwable
                                                .getMessage()
                                                .contains(
                                                        "Empty response from OpenAI embedding"
                                                                + " API"));
                            })
                    .verify();
        }
    }

    @Test
    @DisplayName("Should handle null data in response")
    void testNullDataInResponse() {
        // 准备 data 为 null 的模拟响应
        CreateEmbeddingResponse mockResponse = mock(CreateEmbeddingResponse.class);
        when(mockResponse.data()).thenReturn(null);

        // 模拟 OpenAI 客户端的静态 builder -> builder -> client -> embeddings 调用链
        try (MockedStatic<OpenAIOkHttpClient> mockedClient =
                Mockito.mockStatic(OpenAIOkHttpClient.class)) {

            OpenAIOkHttpClient.Builder mockBuilder = mock(OpenAIOkHttpClient.Builder.class);
            OpenAIClient mockOpenAIClient = mock(OpenAIClient.class);
            EmbeddingService mockEmbeddings = mock(EmbeddingService.class);

            when(mockBuilder.build()).thenReturn(mockOpenAIClient);
            when(mockBuilder.apiKey(any(String.class))).thenReturn(mockBuilder);
            when(mockBuilder.baseUrl(any(String.class))).thenReturn(mockBuilder);
            when(mockBuilder.putHeader(any(), any())).thenReturn(mockBuilder);

            mockedClient.when(OpenAIOkHttpClient::builder).thenReturn(mockBuilder);

            when(mockOpenAIClient.embeddings()).thenReturn(mockEmbeddings);
            when(mockEmbeddings.create(any(EmbeddingCreateParams.class))).thenReturn(mockResponse);

            TextBlock textBlock = TextBlock.builder().text("Hello, world!").build();

            StepVerifier.create(model.embed(textBlock))
                    .expectErrorSatisfies(
                            throwable -> {
                                assertTrue(throwable instanceof EmbeddingException);
                                assertTrue(
                                        throwable
                                                .getMessage()
                                                .contains(
                                                        "Empty response from OpenAI embedding"
                                                                + " API"));
                            })
                    .verify();
        }
    }

    @Test
    @DisplayName("Should handle empty data list in response")
    void testEmptyDataListInResponse() {
        // 准备 data 为空的模拟响应
        CreateEmbeddingResponse mockResponse = mock(CreateEmbeddingResponse.class);
        when(mockResponse.data()).thenReturn(List.of());

        // 模拟 OpenAI 客户端的静态 builder -> builder -> client -> embeddings 调用链
        try (MockedStatic<OpenAIOkHttpClient> mockedClient =
                Mockito.mockStatic(OpenAIOkHttpClient.class)) {

            OpenAIOkHttpClient.Builder mockBuilder = mock(OpenAIOkHttpClient.Builder.class);
            OpenAIClient mockOpenAIClient = mock(OpenAIClient.class);
            EmbeddingService mockEmbeddings = mock(EmbeddingService.class);

            when(mockBuilder.build()).thenReturn(mockOpenAIClient);
            when(mockBuilder.apiKey(any(String.class))).thenReturn(mockBuilder);
            when(mockBuilder.baseUrl(any(String.class))).thenReturn(mockBuilder);
            when(mockBuilder.putHeader(any(), any())).thenReturn(mockBuilder);

            mockedClient.when(OpenAIOkHttpClient::builder).thenReturn(mockBuilder);

            when(mockOpenAIClient.embeddings()).thenReturn(mockEmbeddings);
            when(mockEmbeddings.create(any(EmbeddingCreateParams.class))).thenReturn(mockResponse);

            TextBlock textBlock = TextBlock.builder().text("Hello, world!").build();

            StepVerifier.create(model.embed(textBlock))
                    .expectErrorSatisfies(
                            throwable -> {
                                assertTrue(throwable instanceof EmbeddingException);
                                assertTrue(
                                        throwable
                                                .getMessage()
                                                .contains("No embedding data in response"));
                            })
                    .verify();
        }
    }

    @Test
    @DisplayName("Should handle null embedding in response")
    void testNullEmbeddingInResponse() {
        // 准备 embedding 为 null 的模拟响应
        Embedding mockEmbedding = mock(Embedding.class);
        when(mockEmbedding.embedding()).thenReturn(null);

        CreateEmbeddingResponse mockResponse = mock(CreateEmbeddingResponse.class);
        when(mockResponse.data()).thenReturn(Arrays.asList(mockEmbedding));

        // 模拟 OpenAI 客户端的静态 builder -> builder -> client -> embeddings 调用链
        try (MockedStatic<OpenAIOkHttpClient> mockedClient =
                Mockito.mockStatic(OpenAIOkHttpClient.class)) {

            OpenAIOkHttpClient.Builder mockBuilder = mock(OpenAIOkHttpClient.Builder.class);
            OpenAIClient mockOpenAIClient = mock(OpenAIClient.class);
            EmbeddingService mockEmbeddings = mock(EmbeddingService.class);

            when(mockBuilder.build()).thenReturn(mockOpenAIClient);
            when(mockBuilder.apiKey(any(String.class))).thenReturn(mockBuilder);
            when(mockBuilder.baseUrl(any(String.class))).thenReturn(mockBuilder);
            when(mockBuilder.putHeader(any(), any())).thenReturn(mockBuilder);

            mockedClient.when(OpenAIOkHttpClient::builder).thenReturn(mockBuilder);

            when(mockOpenAIClient.embeddings()).thenReturn(mockEmbeddings);
            when(mockEmbeddings.create(any(EmbeddingCreateParams.class))).thenReturn(mockResponse);

            TextBlock textBlock = TextBlock.builder().text("Hello, world!").build();

            StepVerifier.create(model.embed(textBlock))
                    .expectErrorSatisfies(
                            throwable -> {
                                assertTrue(throwable instanceof EmbeddingException);
                                assertTrue(
                                        throwable
                                                .getMessage()
                                                .contains("Empty embedding vector in response"));
                            })
                    .verify();
        }
    }

    @Test
    @DisplayName("Should handle empty embedding list in response")
    void testEmptyEmbeddingListInResponse() {
        // 准备 embedding 为空的模拟响应
        Embedding mockEmbedding = mock(Embedding.class);
        when(mockEmbedding.embedding()).thenReturn(List.of());

        CreateEmbeddingResponse mockResponse = mock(CreateEmbeddingResponse.class);
        when(mockResponse.data()).thenReturn(Arrays.asList(mockEmbedding));

        // 模拟 OpenAI 客户端的静态 builder -> builder -> client -> embeddings 调用链
        try (MockedStatic<OpenAIOkHttpClient> mockedClient =
                Mockito.mockStatic(OpenAIOkHttpClient.class)) {

            OpenAIOkHttpClient.Builder mockBuilder = mock(OpenAIOkHttpClient.Builder.class);
            OpenAIClient mockOpenAIClient = mock(OpenAIClient.class);
            EmbeddingService mockEmbeddings = mock(EmbeddingService.class);

            when(mockBuilder.build()).thenReturn(mockOpenAIClient);
            when(mockBuilder.apiKey(any(String.class))).thenReturn(mockBuilder);
            when(mockBuilder.baseUrl(any(String.class))).thenReturn(mockBuilder);
            when(mockBuilder.putHeader(any(), any())).thenReturn(mockBuilder);

            mockedClient.when(OpenAIOkHttpClient::builder).thenReturn(mockBuilder);

            when(mockOpenAIClient.embeddings()).thenReturn(mockEmbeddings);
            when(mockEmbeddings.create(any(EmbeddingCreateParams.class))).thenReturn(mockResponse);

            TextBlock textBlock = TextBlock.builder().text("Hello, world!").build();

            StepVerifier.create(model.embed(textBlock))
                    .expectErrorSatisfies(
                            throwable -> {
                                assertTrue(throwable instanceof EmbeddingException);
                                assertTrue(
                                        throwable
                                                .getMessage()
                                                .contains("Empty embedding vector in response"));
                            })
                    .verify();
        }
    }

    @Test
    @DisplayName("Should return vectors with null usage when both token fields are absent")
    void testUsagePresentButBothTokenFieldsMissing() {
        // 模拟 usage 对象存在，但 prompt_tokens 和
        // total_tokens 均缺失的响应。使用 SDK 可选字段访问器
        // _promptTokens / _totalTokens 时，asKnown() 返回 Optional.empty()。
        Embedding mockEmbedding = mock(Embedding.class);
        when(mockEmbedding.embedding()).thenReturn(Arrays.asList(0.1f, 0.2f, 0.3f));

        @SuppressWarnings("unchecked")
        JsonField<Long> missing = mock(JsonField.class);
        when(missing.asKnown()).thenReturn(Optional.empty());

        Usage mockUsage = mock(Usage.class);
        when(mockUsage._promptTokens()).thenReturn(missing);
        when(mockUsage._totalTokens()).thenReturn(missing);

        CreateEmbeddingResponse mockResponse = mock(CreateEmbeddingResponse.class);
        when(mockResponse.data()).thenReturn(Arrays.asList(mockEmbedding));
        when(mockResponse.usage()).thenReturn(mockUsage);

        try (MockedStatic<OpenAIOkHttpClient> mockedClient =
                Mockito.mockStatic(OpenAIOkHttpClient.class)) {

            OpenAIOkHttpClient.Builder mockBuilder = mock(OpenAIOkHttpClient.Builder.class);
            OpenAIClient mockOpenAIClient = mock(OpenAIClient.class);
            EmbeddingService mockEmbeddings = mock(EmbeddingService.class);

            when(mockBuilder.build()).thenReturn(mockOpenAIClient);
            when(mockBuilder.apiKey(any(String.class))).thenReturn(mockBuilder);
            when(mockBuilder.baseUrl(any(String.class))).thenReturn(mockBuilder);
            when(mockBuilder.putHeader(any(), any())).thenReturn(mockBuilder);

            mockedClient.when(OpenAIOkHttpClient::builder).thenReturn(mockBuilder);

            when(mockOpenAIClient.embeddings()).thenReturn(mockEmbeddings);
            when(mockEmbeddings.create(any(EmbeddingCreateParams.class))).thenReturn(mockResponse);

            TextBlock textBlock = TextBlock.builder().text("Hello, world!").build();

            StepVerifier.create(model.embed(textBlock))
                    .assertNext(
                            response -> {
                                assertNotNull(response);
                                assertNotNull(response.getEmbedding());
                                assertEquals(3, response.getEmbedding().length);
                                assertNull(response.getUsage());
                            })
                    .verifyComplete();
        }
    }

    @Test
    @DisplayName("Should return vectors with null usage when only one token field is present")
    void testUsagePresentButOneTokenFieldMissing() {
        // 仅 prompt_tokens 存在；total_tokens 缺失。
        Embedding mockEmbedding = mock(Embedding.class);
        when(mockEmbedding.embedding()).thenReturn(Arrays.asList(0.1f, 0.2f, 0.3f));

        @SuppressWarnings("unchecked")
        JsonField<Long> prompt = mock(JsonField.class);
        when(prompt.asKnown()).thenReturn(Optional.of(12L));
        @SuppressWarnings("unchecked")
        JsonField<Long> missing = mock(JsonField.class);
        when(missing.asKnown()).thenReturn(Optional.empty());

        Usage mockUsage = mock(Usage.class);
        when(mockUsage._promptTokens()).thenReturn(prompt);
        when(mockUsage._totalTokens()).thenReturn(missing);

        CreateEmbeddingResponse mockResponse = mock(CreateEmbeddingResponse.class);
        when(mockResponse.data()).thenReturn(Arrays.asList(mockEmbedding));
        when(mockResponse.usage()).thenReturn(mockUsage);

        try (MockedStatic<OpenAIOkHttpClient> mockedClient =
                Mockito.mockStatic(OpenAIOkHttpClient.class)) {

            OpenAIOkHttpClient.Builder mockBuilder = mock(OpenAIOkHttpClient.Builder.class);
            OpenAIClient mockOpenAIClient = mock(OpenAIClient.class);
            EmbeddingService mockEmbeddings = mock(EmbeddingService.class);

            when(mockBuilder.build()).thenReturn(mockOpenAIClient);
            when(mockBuilder.apiKey(any(String.class))).thenReturn(mockBuilder);
            when(mockBuilder.baseUrl(any(String.class))).thenReturn(mockBuilder);
            when(mockBuilder.putHeader(any(), any())).thenReturn(mockBuilder);

            mockedClient.when(OpenAIOkHttpClient::builder).thenReturn(mockBuilder);

            when(mockOpenAIClient.embeddings()).thenReturn(mockEmbeddings);
            when(mockEmbeddings.create(any(EmbeddingCreateParams.class))).thenReturn(mockResponse);

            TextBlock textBlock = TextBlock.builder().text("Hello, world!").build();

            StepVerifier.create(model.embed(textBlock))
                    .assertNext(
                            response -> {
                                assertNotNull(response);
                                assertNotNull(response.getEmbedding());
                                assertEquals(3, response.getEmbedding().length);
                                assertNull(response.getUsage());
                            })
                    .verifyComplete();
        }
    }
}
