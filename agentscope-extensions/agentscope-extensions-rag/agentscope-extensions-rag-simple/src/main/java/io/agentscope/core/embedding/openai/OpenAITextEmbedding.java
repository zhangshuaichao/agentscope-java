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

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.embeddings.CreateEmbeddingResponse;
import com.openai.models.embeddings.Embedding;
import com.openai.models.embeddings.EmbeddingCreateParams;
import io.agentscope.core.Version;
import io.agentscope.core.embedding.EmbeddingException;
import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.embedding.EmbeddingResponse;
import io.agentscope.core.embedding.EmbeddingUsage;
import io.agentscope.core.embedding.EmbeddingUtils;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ExecutionConfig;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * OpenAI 文本嵌入模型实现。
 *
 * <p>访问文本嵌入 API，支持单文本和批量嵌入操作。
 *
 * <p>仅支持 {@link TextBlock}，其他类型触发 {@link EmbeddingException}。
 *
 * <p>通过 ExecutionConfig 支持超时与重试。
 *
 * <p>每个模型复用一个延迟初始化的 SDK 客户端，包括并发调用和重试。不再需要模型时，应在全部嵌入操作结束后关闭。
 */
public class OpenAITextEmbedding implements EmbeddingModel, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OpenAITextEmbedding.class);

    private final String apiKey;
    private final String modelName;
    private final int dimensions;
    private final ExecutionConfig defaultExecutionConfig;

    private final String baseUrl;

    // 由 this 锁保护，SDK 初始化与关闭不能竞态执行。
    private OpenAIClient client;
    private boolean closed;

    /**
     * 创建 OpenAITextEmbedding 实例。
     *
     * @param apiKey OpenAI 认证 API 密钥
     * @param modelName 模型名称，如 text-embedding-3-small
     * @param dimensions 向量维度
     * @param defaultExecutionConfig 超时与重试默认配置
     * @param baseUrl OpenAI API 自定义基础 URL（null 使用默认值）
     */
    public OpenAITextEmbedding(
            String apiKey,
            String modelName,
            int dimensions,
            ExecutionConfig defaultExecutionConfig,
            String baseUrl) {
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.dimensions = dimensions;
        this.defaultExecutionConfig =
                EmbeddingUtils.ensureDefaultExecutionConfig(defaultExecutionConfig);
        this.baseUrl = baseUrl;
    }

    /**
     * 创建 OpenAITextEmbedding 构建器。
     *
     * @return 新的 Builder 实例
     */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public Mono<EmbeddingResponse> embed(ContentBlock block) {
        if (block == null) {
            return Mono.error(
                    new EmbeddingException("ContentBlock cannot be null", modelName, "openai"));
        }

        if (!(block instanceof TextBlock textBlock)) {
            return Mono.error(
                    new EmbeddingException(
                            "OpenAITextEmbedding only supports TextBlock, but got: "
                                    + block.getClass().getSimpleName(),
                            modelName,
                            "openai"));
        }

        String text = textBlock.getText();
        if (text == null || text.trim().isEmpty()) {
            return Mono.error(
                    new EmbeddingException(
                            "TextBlock text cannot be null or empty", modelName, "openai"));
        }

        Mono<EmbeddingResponse> embeddingMono =
                Mono.fromCallable(
                                () -> {
                                    try {
                                        Instant startTime = Instant.now();
                                        OpenAIClient client = getClient();

                                        EmbeddingCreateParams createParams =
                                                EmbeddingCreateParams.builder()
                                                        .model(modelName)
                                                        .dimensions(dimensions)
                                                        .encodingFormat(
                                                                EmbeddingCreateParams.EncodingFormat
                                                                        .FLOAT)
                                                        .inputOfArrayOfStrings(List.of(text))
                                                        .build();

                                        log.debug(
                                                "OpenAI embedding call: model={},"
                                                        + " text_length={}",
                                                modelName,
                                                text.length());

                                        CreateEmbeddingResponse result =
                                                client.embeddings().create(createParams);

                                        if (result == null || result.data() == null) {
                                            throw new EmbeddingException(
                                                    "Empty response from OpenAI embedding API",
                                                    modelName,
                                                    "openai");
                                        }

                                        List<Embedding> embeddings = result.data();
                                        if (embeddings == null
                                                || embeddings.isEmpty()
                                                || embeddings.get(0) == null) {
                                            throw new EmbeddingException(
                                                    "No embedding data in response",
                                                    modelName,
                                                    "openai");
                                        }

                                        List<Float> embeddingValues = embeddings.get(0).embedding();
                                        if (embeddingValues == null || embeddingValues.isEmpty()) {
                                            throw new EmbeddingException(
                                                    "Empty embedding vector in response",
                                                    modelName,
                                                    "openai");
                                        }

                                        // 将 List<Float> 转换为 double[]。
                                        double[] embeddingArray =
                                                EmbeddingUtils.convertFloatListToDoubleArray(
                                                        embeddingValues);

                                        // 校验维度。
                                        if (embeddingArray.length != dimensions) {
                                            log.warn(
                                                    "Embedding dimension mismatch: expected={},"
                                                            + " actual={}",
                                                    dimensions,
                                                    embeddingArray.length);
                                        }

                                        EmbeddingUsage usage = extractUsage(result, startTime);
                                        return new EmbeddingResponse(embeddingArray, usage);
                                    } catch (EmbeddingException e) {
                                        throw e;
                                    } catch (Exception e) {
                                        throw new EmbeddingException(
                                                "Failed to generate embedding: " + e.getMessage(),
                                                e,
                                                modelName,
                                                "openai");
                                    }
                                })
                        .onErrorMap(
                                e -> {
                                    if (e instanceof EmbeddingException) {
                                        return e;
                                    }
                                    return new EmbeddingException(
                                            "OpenAI embedding API call failed: " + e.getMessage(),
                                            e,
                                            modelName,
                                            "openai");
                                });

        // 在订阅时、重试之外检查，包括 close() 前创建的 Mono。
        return Mono.defer(
                () -> {
                    synchronized (this) {
                        if (closed) {
                            return Mono.error(
                                    new EmbeddingException(
                                            "OpenAITextEmbedding is closed", modelName, "openai"));
                        }
                    }
                    return EmbeddingUtils.applyTimeoutAndRetry(
                            embeddingMono, defaultExecutionConfig, modelName, "openai", log);
                });
    }

    private synchronized OpenAIClient getClient() {
        if (closed) {
            throw new EmbeddingException("OpenAITextEmbedding is closed", modelName, "openai");
        }
        if (client == null) {
            OpenAIOkHttpClient.Builder clientBuilder = OpenAIOkHttpClient.builder();
            if (apiKey != null) {
                clientBuilder.apiKey(apiKey);
            }
            if (baseUrl != null) {
                clientBuilder.baseUrl(baseUrl);
            }
            clientBuilder.putHeader("User-Agent", Version.getUserAgent());
            client = clientBuilder.build();
        }
        return client;
    }

    /**
     * 关闭模型及其 SDK 客户端；重复调用无影响。
     *
     * <p>关闭前应等待进行中的嵌入操作结束。关闭尚未使用的模型不会初始化客户端。之后的嵌入订阅以 {@link EmbeddingException} 失败，且不进入重试链，包括关闭前创建、关闭后订阅的 Mono。
     */
    @Override
    public synchronized void close() {
        if (!closed) {
            closed = true;
            if (client != null) {
                client.close();
            }
        }
    }

    /**
     * 从 OpenAI 嵌入响应提取 token 使用统计。
     *
     * @param result SDK 响应
     * @param startTime 请求开始时间，用于计算耗时
     * @return 使用统计；提供者未报告时为 null
     */
    private static EmbeddingUsage extractUsage(CreateEmbeddingResponse result, Instant startTime) {
        if (result == null || result.usage() == null) {
            return null;
        }
        CreateEmbeddingResponse.Usage usage = result.usage();
        Optional<Long> promptTokens = usage._promptTokens().asKnown();
        Optional<Long> totalTokens = usage._totalTokens().asKnown();
        if (promptTokens.isEmpty() || totalTokens.isEmpty()) {
            return null;
        }
        double elapsedSeconds = Duration.between(startTime, Instant.now()).toMillis() / 1000.0;
        return EmbeddingUsage.builder()
                .inputTokens(promptTokens.get().intValue())
                .totalTokens(totalTokens.get().intValue())
                .time(elapsedSeconds)
                .build();
    }

    @Override
    public String getModelName() {
        return modelName;
    }

    @Override
    public int getDimensions() {
        return dimensions;
    }

    /**
     * OpenAITextEmbedding 的构建器。
     */
    public static class Builder {
        private String apiKey;
        private String modelName;
        private int dimensions = 1536;
        private ExecutionConfig defaultExecutionConfig;
        private String baseUrl;

        /**
         * 设置 OpenAI 认证 API 密钥。
         *
         * @param apiKey API 密钥
         * @return 当前构建器实例
         */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /**
         * 设置模型名称。
         *
         * @param modelName 模型名称，如 text-embedding-3-small
         * @return 当前构建器实例
         */
        public Builder modelName(String modelName) {
            this.modelName = modelName;
            return this;
        }

        /**
         * 设置嵌入向量维度。
         *
         * @param dimensions 维度
         * @return 当前构建器实例
         */
        public Builder dimensions(int dimensions) {
            this.dimensions = dimensions;
            return this;
        }

        /**
         * 设置默认执行配置。
         *
         * @param config 执行配置（null 使用默认值）
         * @return 当前构建器实例
         */
        public Builder executionConfig(ExecutionConfig config) {
            this.defaultExecutionConfig = config;
            return this;
        }

        /**
         * 设置 OpenAI API 自定义基础 URL。
         *
         * @param baseUrl 基础 URL（null 使用默认值）
         * @return 当前构建器实例
         */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /**
         * 构建 OpenAITextEmbedding 实例。
         *
         * <p>校验必填参数，并通过 EmbeddingUtils.ensureDefaultExecutionConfig() 确保 defaultExecutionConfig 应用默认值。
         *
         * @return 配置完成的 OpenAITextEmbedding 实例
         * @throws IllegalStateException 当必填参数缺失或无效时
         */
        public OpenAITextEmbedding build() {
            // 校验必填参数。
            if (apiKey == null || apiKey.isEmpty()) {
                throw new IllegalStateException("apiKey is required and cannot be null or empty");
            }
            if (modelName == null || modelName.isEmpty()) {
                throw new IllegalStateException(
                        "modelName is required and cannot be null or empty");
            }
            if (dimensions <= 0) {
                throw new IllegalStateException("dimensions must be positive, got: " + dimensions);
            }

            ExecutionConfig effectiveConfig =
                    EmbeddingUtils.ensureDefaultExecutionConfig(defaultExecutionConfig);

            return new OpenAITextEmbedding(apiKey, modelName, dimensions, effectiveConfig, baseUrl);
        }
    }
}
