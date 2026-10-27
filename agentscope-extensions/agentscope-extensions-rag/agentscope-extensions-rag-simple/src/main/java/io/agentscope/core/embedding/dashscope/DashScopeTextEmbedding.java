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
package io.agentscope.core.embedding.dashscope;

import com.alibaba.dashscope.embeddings.TextEmbedding;
import com.alibaba.dashscope.embeddings.TextEmbeddingParam;
import com.alibaba.dashscope.embeddings.TextEmbeddingResult;
import com.alibaba.dashscope.embeddings.TextEmbeddingResultItem;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * DashScope 文本嵌入模型实现。
 *
 * <p>访问文本嵌入 API，支持单文本及批量嵌入操作。
 *
 * <p>仅支持 {@link TextBlock}，其他内容块类型会触发 {@link EmbeddingException}。
 *
 * <p>通过 ExecutionConfig 支持超时与重试配置。
 */
public class DashScopeTextEmbedding implements EmbeddingModel {

    private static final Logger log = LoggerFactory.getLogger(DashScopeTextEmbedding.class);

    private final String apiKey;
    private final String modelName;
    private final int dimensions;
    private final ExecutionConfig defaultExecutionConfig;

    private final String baseUrl;

    /**
     * 创建 DashScopeTextEmbedding 实例。
     *
     * @param apiKey DashScope 认证 API 密钥
     * @param modelName 模型名称，如 text-embedding-v3
     * @param dimensions 向量维度
     * @param defaultExecutionConfig 超时与重试默认配置
     * @param baseUrl DashScope API 自定义基础 URL（null 使用默认值）
     */
    public DashScopeTextEmbedding(
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
     * 创建 DashScopeTextEmbedding 构建器。
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
                    new EmbeddingException("ContentBlock cannot be null", modelName, "dashscope"));
        }

        if (!(block instanceof TextBlock textBlock)) {
            return Mono.error(
                    new EmbeddingException(
                            "DashScopeTextEmbedding only supports TextBlock, but got: "
                                    + block.getClass().getSimpleName(),
                            modelName,
                            "dashscope"));
        }

        String text = textBlock.getText();
        if (text == null || text.trim().isEmpty()) {
            return Mono.error(
                    new EmbeddingException(
                            "TextBlock text cannot be null or empty", modelName, "dashscope"));
        }

        Mono<EmbeddingResponse> embeddingMono =
                Mono.fromCallable(
                                () -> {
                                    try {
                                        Instant startTime = Instant.now();
                                        TextEmbedding embedding =
                                                baseUrl != null
                                                        ? new TextEmbedding(baseUrl)
                                                        : new TextEmbedding();
                                        TextEmbeddingParam param =
                                                TextEmbeddingParam.builder()
                                                        .apiKey(apiKey)
                                                        .model(modelName)
                                                        .dimension(dimensions)
                                                        .texts(List.of(text))
                                                        .build();

                                        log.debug(
                                                "DashScope embedding call: model={},"
                                                        + " text_length={}",
                                                modelName,
                                                text.length());

                                        TextEmbeddingResult result = embedding.call(param);

                                        if (result == null || result.getOutput() == null) {
                                            throw new EmbeddingException(
                                                    "Empty response from DashScope embedding API",
                                                    modelName,
                                                    "dashscope");
                                        }

                                        List<TextEmbeddingResultItem> embeddings =
                                                result.getOutput().getEmbeddings();
                                        if (embeddings == null
                                                || embeddings.isEmpty()
                                                || embeddings.get(0) == null) {
                                            throw new EmbeddingException(
                                                    "No embedding data in response",
                                                    modelName,
                                                    "dashscope");
                                        }

                                        List<Double> embeddingValues =
                                                embeddings.get(0).getEmbedding();
                                        if (embeddingValues == null || embeddingValues.isEmpty()) {
                                            throw new EmbeddingException(
                                                    "Empty embedding vector in response",
                                                    modelName,
                                                    "dashscope");
                                        }

                                        // 将 List<Double> 转换为 double[]。
                                        double[] embeddingArray =
                                                EmbeddingUtils.convertDoubleListToArray(
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
                                                "dashscope");
                                    }
                                })
                        .subscribeOn(Schedulers.boundedElastic())
                        .onErrorMap(
                                e -> {
                                    if (e instanceof EmbeddingException) {
                                        return e;
                                    }
                                    return new EmbeddingException(
                                            "DashScope embedding API call failed: "
                                                    + e.getMessage(),
                                            e,
                                            modelName,
                                            "dashscope");
                                });

        // 应用超时与重试。
        return EmbeddingUtils.applyTimeoutAndRetry(
                embeddingMono, defaultExecutionConfig, modelName, "dashscope", log);
    }

    /**
     * 从 DashScope 文本嵌入结果提取 token 使用统计。
     *
     * <p>DashScope 文本嵌入只报告 {@code total_tokens}；由于请求仅消费输入 token，两个字段采用相同值。
     *
     * @param result SDK 返回的嵌入结果
     * @return 使用统计；提供者未报告时为 null
     */
    private static EmbeddingUsage extractUsage(TextEmbeddingResult result, Instant startTime) {
        if (result == null || result.getUsage() == null) {
            return null;
        }
        Integer totalTokens = result.getUsage().getTotalTokens();
        if (totalTokens == null) {
            return null;
        }
        double elapsedSeconds = Duration.between(startTime, Instant.now()).toMillis() / 1000.0;
        return EmbeddingUsage.builder()
                .inputTokens(totalTokens)
                .totalTokens(totalTokens)
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
     * DashScopeTextEmbedding 的构建器。
     */
    public static class Builder {
        private String apiKey;
        private String modelName;
        private int dimensions = 1024;
        private ExecutionConfig defaultExecutionConfig;
        private String baseUrl;

        /**
         * 设置 DashScope 认证 API 密钥。
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
         * @param modelName 模型名称，如 text-embedding-v3
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
         * 设置 DashScope API 自定义基础 URL。
         *
         * @param baseUrl 基础 URL（null 使用默认值）
         * @return 当前构建器实例
         */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /**
         * 构建 DashScopeTextEmbedding 实例。
         *
         * <p>校验必填参数，并通过 EmbeddingUtils.ensureDefaultExecutionConfig() 确保 defaultExecutionConfig 应用默认值。
         *
         * @return 配置完成的 DashScopeTextEmbedding 实例
         * @throws IllegalStateException 当必填参数缺失或无效时
         */
        public DashScopeTextEmbedding build() {
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

            return new DashScopeTextEmbedding(
                    apiKey, modelName, dimensions, effectiveConfig, baseUrl);
        }
    }
}
