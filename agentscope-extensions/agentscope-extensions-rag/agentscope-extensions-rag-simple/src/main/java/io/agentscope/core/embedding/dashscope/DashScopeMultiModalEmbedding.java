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

import com.alibaba.dashscope.embeddings.MultiModalEmbedding;
import com.alibaba.dashscope.embeddings.MultiModalEmbeddingItemImage;
import com.alibaba.dashscope.embeddings.MultiModalEmbeddingItemText;
import com.alibaba.dashscope.embeddings.MultiModalEmbeddingParam;
import com.alibaba.dashscope.embeddings.MultiModalEmbeddingResult;
import com.alibaba.dashscope.embeddings.MultiModalEmbeddingResultItem;
import io.agentscope.core.embedding.EmbeddingException;
import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.embedding.EmbeddingResponse;
import io.agentscope.core.embedding.EmbeddingUsage;
import io.agentscope.core.embedding.EmbeddingUtils;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.extensions.model.dashscope.formatter.DashScopeMediaConverter;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * DashScope 多模态嵌入模型实现。
 *
 * <p>通过 ContentBlock 输入支持文本与图像嵌入，全部内容统一使用 MultiModalEmbedding API。
 *
 * <p>支持：
 * <ul>
 * <li>{@link TextBlock}：通过 MultiModalEmbeddingItemText 调用 DashScope MultiModalEmbedding API。</li>
 * <li>{@link ImageBlock}：通过 MultiModalEmbeddingItemImage 调用 DashScope MultiModalEmbedding API。</li>
 * </ul>
 *
 * <p>其他 ContentBlock 类型不受支持，会触发 {@link EmbeddingException}。
 *
 * <p>通过 ExecutionConfig 支持超时与重试配置。
 */
public class DashScopeMultiModalEmbedding implements EmbeddingModel {

    private static final Logger log = LoggerFactory.getLogger(DashScopeMultiModalEmbedding.class);

    private final String apiKey;
    private final String modelName;
    private final int dimensions;
    private final ExecutionConfig defaultExecutionConfig;
    private final DashScopeMediaConverter mediaConverter;

    private final String baseUrl;

    /**
     * 创建 DashScopeMultiModalEmbedding 实例。
     *
     * @param apiKey DashScope 认证 API 密钥
     * @param modelName 模型名称，如 multimodal-embedding-v1
     * @param dimensions 向量维度
     * @param defaultExecutionConfig 超时与重试默认配置
     * @param baseUrl DashScope API 自定义基础 URL（null 使用默认值）
     */
    public DashScopeMultiModalEmbedding(
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
        this.mediaConverter = new DashScopeMediaConverter();
    }

    /**
     * 创建 DashScopeMultiModalEmbedding 构建器。
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

        Mono<EmbeddingResponse> embeddingMono =
                Mono.fromCallable(
                                () -> {
                                    try {
                                        return embed0(block);
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
                                            "DashScope multi-modal embedding API call failed: "
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
     * 使用 MultiModalEmbedding API 嵌入单个 ContentBlock（TextBlock 或 ImageBlock）。
     *
     * @param block 待嵌入内容块
     * @return 包含向量和使用统计的嵌入响应
     * @throws EmbeddingException 当嵌入失败或块类型不受支持时
     */
    private EmbeddingResponse embed0(ContentBlock block) throws EmbeddingException {
        try {
            Instant startTime = Instant.now();
            MultiModalEmbeddingParam param;

            if (block instanceof TextBlock textBlock) {
                String text = textBlock.getText();
                if (text == null || text.trim().isEmpty()) {
                    throw new EmbeddingException(
                            "TextBlock text cannot be null or empty", modelName, "dashscope");
                }
                param = buildTextParam(text);
            } else if (block instanceof ImageBlock imageBlock) {
                String imageUrl = mediaConverter.convertImageBlockToUrl(imageBlock);
                param = buildImageParam(imageUrl);
            } else {
                throw new EmbeddingException(
                        "DashScopeMultiModalEmbedding only supports"
                                + " TextBlock and ImageBlock, but got: "
                                + block.getClass().getSimpleName(),
                        modelName,
                        "dashscope");
            }

            MultiModalEmbeddingResult result = callEmbeddingAPI(param);
            double[] embedding = parseEmbeddingResult(result);
            validateDimensions(embedding);
            EmbeddingUsage usage = extractUsage(result, startTime);

            return new EmbeddingResponse(embedding, usage);
        } catch (EmbeddingException e) {
            throw e;
        } catch (Exception e) {
            throw new EmbeddingException(
                    "Failed to embed content block: " + e.getMessage(), e, modelName, "dashscope");
        }
    }

    /**
     * 从多模态嵌入结果提取 token 使用统计。
     *
     * <p>任一字段缺失时返回 {@code null}，与 {@code OpenAITextEmbedding.extractUsage} 一致。
     *
     * @param result API 嵌入结果
     * @param startTime 请求开始时间，用于计算耗时
     * @return 使用统计；提供者未报告时为 null
     */
    private EmbeddingUsage extractUsage(MultiModalEmbeddingResult result, Instant startTime) {
        if (result == null || result.getUsage() == null) {
            return null;
        }
        Integer inputTokens = result.getUsage().getInputTokens();
        Integer totalTokens = result.getUsage().getTotalUsage();
        if (inputTokens == null || totalTokens == null) {
            return null;
        }
        double elapsedSeconds = Duration.between(startTime, Instant.now()).toMillis() / 1000.0;
        return EmbeddingUsage.builder()
                .inputTokens(inputTokens)
                .totalTokens(totalTokens)
                .time(elapsedSeconds)
                .build();
    }

    /**
     * 创建 MultiModalEmbedding 客户端。
     *
     * <p>每个请求创建新实例；此实现按 DashScope SDK 多模态嵌入客户端单次使用的约定处理，不复用客户端。
     *
     * @return 新的 MultiModalEmbedding 实例
     */
    private MultiModalEmbedding createEmbeddingClient() {
        return baseUrl != null ? new MultiModalEmbedding(baseUrl) : new MultiModalEmbedding();
    }

    /**
     * 构建文本嵌入 MultiModalEmbeddingParam。
     *
     * @param text 待嵌入文本
     * @return 已配置的 MultiModalEmbeddingParam
     */
    private MultiModalEmbeddingParam buildTextParam(String text) {
        return MultiModalEmbeddingParam.builder()
                .apiKey(apiKey)
                .model(modelName)
                .parameters(Map.of("dimension", dimensions))
                .contents(List.of(MultiModalEmbeddingItemText.builder().text(text).build()))
                .build();
    }

    /**
     * 构建图像嵌入 MultiModalEmbeddingParam。
     *
     * @param imageUrl 待嵌入图像 URL
     * @return 已配置的 MultiModalEmbeddingParam
     */
    private MultiModalEmbeddingParam buildImageParam(String imageUrl) {
        return MultiModalEmbeddingParam.builder()
                .apiKey(apiKey)
                .model(modelName)
                .parameters(Map.of("dimension", dimensions))
                .contents(List.of(MultiModalEmbeddingItemImage.builder().image(imageUrl).build()))
                .build();
    }

    /**
     * 使用给定参数调用 DashScope MultiModalEmbedding API。
     *
     * @param param 嵌入参数
     * @return 嵌入结果
     * @throws EmbeddingException 当 API 调用失败或响应为空时
     */
    private MultiModalEmbeddingResult callEmbeddingAPI(MultiModalEmbeddingParam param)
            throws EmbeddingException {
        try {
            MultiModalEmbedding embedding = createEmbeddingClient();
            MultiModalEmbeddingResult result = embedding.call(param);

            if (result == null || result.getOutput() == null) {
                throw new EmbeddingException(
                        "Empty response from DashScope multi-modal embedding API",
                        modelName,
                        "dashscope");
            }

            return result;
        } catch (EmbeddingException e) {
            throw e;
        } catch (Exception e) {
            throw new EmbeddingException(
                    "DashScope multi-modal embedding API call failed: " + e.getMessage(),
                    e,
                    modelName,
                    "dashscope");
        }
    }

    /**
     * 解析嵌入结果并转换为 double 数组。
     *
     * @param result API 嵌入结果
     * @return double 数组形式的嵌入向量
     * @throws EmbeddingException 当结果无效或为空时
     */
    private double[] parseEmbeddingResult(MultiModalEmbeddingResult result)
            throws EmbeddingException {
        List<MultiModalEmbeddingResultItem> embeddings = result.getOutput().getEmbeddings();
        if (embeddings == null || embeddings.isEmpty() || embeddings.get(0) == null) {
            throw new EmbeddingException("No embedding data in response", modelName, "dashscope");
        }

        List<Double> embeddingValues = embeddings.get(0).getEmbedding();
        if (embeddingValues == null || embeddingValues.isEmpty()) {
            throw new EmbeddingException(
                    "Empty embedding vector in response", modelName, "dashscope");
        }

        return EmbeddingUtils.convertDoubleListToArray(embeddingValues);
    }

    /**
     * 校验嵌入维度，不匹配时记录警告。
     *
     * @param embedding 待校验向量
     */
    private void validateDimensions(double[] embedding) {
        if (embedding.length != dimensions) {
            log.warn(
                    "Embedding dimension mismatch: expected={}, actual={}",
                    dimensions,
                    embedding.length);
        }
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
     * DashScopeMultiModalEmbedding 的构建器。
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
         * @param modelName 模型名称，如 multimodal-embedding-v1
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
         * 构建 DashScopeMultiModalEmbedding 实例。
         *
         * <p>校验必填参数，并通过 EmbeddingUtils.ensureDefaultExecutionConfig() 确保 defaultExecutionConfig 应用默认值。
         *
         * @return 配置完成的 DashScopeMultiModalEmbedding 实例
         * @throws IllegalStateException 当必填参数缺失或无效时
         */
        public DashScopeMultiModalEmbedding build() {
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

            return new DashScopeMultiModalEmbedding(
                    apiKey, modelName, dimensions, effectiveConfig, baseUrl);
        }
    }
}
