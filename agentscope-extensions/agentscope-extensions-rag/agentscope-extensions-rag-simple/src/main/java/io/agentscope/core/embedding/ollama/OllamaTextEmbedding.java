/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.embedding.ollama;

import io.agentscope.core.embedding.EmbeddingException;
import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.embedding.EmbeddingResponse;
import io.agentscope.core.embedding.EmbeddingUsage;
import io.agentscope.core.embedding.EmbeddingUtils;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.transport.HttpTransportFactory;
import io.agentscope.extensions.model.ollama.OllamaHttpClient;
import io.agentscope.extensions.model.ollama.dto.OllamaEmbeddingRequest;
import io.agentscope.extensions.model.ollama.dto.OllamaEmbeddingResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Ollama 文本嵌入模型实现。
 *
 * <p>访问文本嵌入 API，支持单文本及批量嵌入操作。
 *
 * <p>仅支持 {@link TextBlock}，其他内容块触发 {@link EmbeddingException}。
 *
 * <p>通过 ExecutionConfig 支持超时与重试。
 */
public class OllamaTextEmbedding implements EmbeddingModel {

    private static final Logger log = LoggerFactory.getLogger(OllamaTextEmbedding.class);

    private final String baseUrl;
    private final String modelName;
    private final int dimensions;
    private final ExecutionConfig defaultExecutionConfig;

    /**
     * 创建 Ollama 文本嵌入模型。
     *
     * @param baseUrl Ollama API 基础 URL，如 http://localhost:11434
     * @param modelName 模型名称，如 nomic-embed-text、all-minilm
     * @param dimensions 向量维度
     * @param defaultExecutionConfig 超时与重试默认配置
     */
    public OllamaTextEmbedding(
            String baseUrl,
            String modelName,
            int dimensions,
            ExecutionConfig defaultExecutionConfig) {
        this.baseUrl = baseUrl;
        this.modelName = modelName;
        this.dimensions = dimensions;
        this.defaultExecutionConfig =
                EmbeddingUtils.ensureDefaultExecutionConfig(defaultExecutionConfig);
    }

    /**
     * 创建 OllamaTextEmbedding 构建器。
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
                    new EmbeddingException("ContentBlock cannot be null", modelName, "ollama"));
        }

        if (!(block instanceof TextBlock textBlock)) {
            return Mono.error(
                    new EmbeddingException(
                            "OllamaTextEmbedding only supports TextBlock, but got: "
                                    + block.getClass().getSimpleName(),
                            modelName,
                            "ollama"));
        }

        String text = textBlock.getText();
        if (text == null || text.trim().isEmpty()) {
            return Mono.error(
                    new EmbeddingException(
                            "TextBlock text cannot be null or empty", modelName, "ollama"));
        }

        Mono<EmbeddingResponse> embeddingMono =
                Mono.fromCallable(
                                () -> {
                                    try {
                                        Instant startTime = Instant.now();
                                        log.debug(
                                                "Ollama embedding call: model={},"
                                                        + " text_length={}",
                                                modelName,
                                                text.length());

                                        // 创建 Ollama HTTP 客户端。
                                        OllamaHttpClient client =
                                                new OllamaHttpClient(
                                                        HttpTransportFactory.getDefault(), baseUrl);

                                        // 创建嵌入请求。
                                        OllamaEmbeddingRequest request =
                                                new OllamaEmbeddingRequest(
                                                        modelName, List.of(text));

                                        // 从选项设置附加参数。
                                        request.setKeepAlive(null); // 使用默认 keep alive。
                                        request.setTruncate(null); // 使用默认截断设置。

                                        // 调用 Ollama 嵌入 API。
                                        OllamaEmbeddingResponse response = client.embed(request);

                                        if (response == null || response.getEmbeddings() == null) {
                                            throw new EmbeddingException(
                                                    "Empty response from Ollama embedding API",
                                                    modelName,
                                                    "ollama");
                                        }

                                        List<float[]> embeddings = response.getEmbeddings();
                                        if (embeddings.isEmpty() || embeddings.get(0) == null) {
                                            throw new EmbeddingException(
                                                    "No embedding data in response",
                                                    modelName,
                                                    "ollama");
                                        }

                                        // 将 float[] 转换为 double[]。
                                        float[] embeddingValues = embeddings.get(0);
                                        double[] embeddingArray =
                                                EmbeddingUtils.convertFloatArrayToDoubleArray(
                                                        embeddingValues);

                                        // 已指定维度时进行校验。
                                        if (dimensions > 0 && embeddingArray.length != dimensions) {
                                            log.warn(
                                                    "Embedding dimension mismatch: expected={},"
                                                            + " actual={}",
                                                    dimensions,
                                                    embeddingArray.length);
                                        }

                                        EmbeddingUsage usage = extractUsage(response, startTime);
                                        return new EmbeddingResponse(embeddingArray, usage);
                                    } catch (EmbeddingException e) {
                                        throw e;
                                    } catch (Exception e) {
                                        // 处理 Ollama 专用 HTTP 异常。
                                        if (e instanceof OllamaHttpClient.OllamaHttpException) {
                                            OllamaHttpClient.OllamaHttpException ohe =
                                                    (OllamaHttpClient.OllamaHttpException) e;
                                            throw new EmbeddingException(
                                                    "Ollama API error: " + ohe.getMessage(),
                                                    e,
                                                    modelName,
                                                    "ollama");
                                        }
                                        throw new EmbeddingException(
                                                "Failed to generate embedding: " + e.getMessage(),
                                                e,
                                                modelName,
                                                "ollama");
                                    }
                                })
                        .onErrorMap(
                                e -> {
                                    if (e instanceof EmbeddingException) {
                                        return e;
                                    }
                                    return new EmbeddingException(
                                            "Ollama embedding API call failed: " + e.getMessage(),
                                            e,
                                            modelName,
                                            "ollama");
                                });

        // 应用超时与重试。
        return EmbeddingUtils.applyTimeoutAndRetry(
                embeddingMono, defaultExecutionConfig, modelName, "ollama", log);
    }

    /**
     * 从 Ollama 嵌入响应提取 token 使用统计。
     *
     * <p>Ollama 在 {@code prompt_eval_count} 中报告已处理提示 token 数；嵌入请求仅消费输入 token，因此两个字段采用相同值。
     *
     * @param response Ollama API 响应
     * @param startTime 请求开始时间，用于计算耗时
     * @return 使用统计；提供者未报告时为 null
     */
    private static EmbeddingUsage extractUsage(
            OllamaEmbeddingResponse response, Instant startTime) {
        if (response == null || response.getPromptEvalCount() == null) {
            return null;
        }
        int tokens = response.getPromptEvalCount();
        double elapsedSeconds = Duration.between(startTime, Instant.now()).toMillis() / 1000.0;
        return EmbeddingUsage.builder()
                .inputTokens(tokens)
                .totalTokens(tokens)
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
     * OllamaTextEmbedding 的构建器。
     */
    public static class Builder {
        private String baseUrl = "http://localhost:11434"; // 默认 Ollama 端点。
        private String modelName;
        private int dimensions = -1; // 默认 -1 表示未指定。
        private ExecutionConfig defaultExecutionConfig;

        /**
         * 设置 Ollama API 基础 URL。
         *
         * @param baseUrl 基础 URL，如 http://localhost:11434
         * @return 当前构建器实例
         */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /**
         * 设置模型名称。
         *
         * @param modelName 模型名称，如 nomic-embed-text、all-minilm
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
         * 构建 OllamaTextEmbedding 实例。
         *
         * <p>校验必填参数，并通过 EmbeddingUtils.ensureDefaultExecutionConfig() 确保 defaultExecutionConfig 应用默认值。
         *
         * @return 配置完成的 OllamaTextEmbedding 实例
         * @throws IllegalStateException 当必填参数缺失或无效时
         */
        public OllamaTextEmbedding build() {
            // 校验必填参数。
            if (modelName == null || modelName.isEmpty()) {
                throw new IllegalStateException(
                        "modelName is required and cannot be null or empty");
            }
            if (dimensions <= 0 && dimensions != -1) {
                throw new IllegalStateException("dimensions must be positive, got: " + dimensions);
            }

            ExecutionConfig effectiveConfig =
                    EmbeddingUtils.ensureDefaultExecutionConfig(defaultExecutionConfig);

            return new OllamaTextEmbedding(baseUrl, modelName, dimensions, effectiveConfig);
        }
    }
}
