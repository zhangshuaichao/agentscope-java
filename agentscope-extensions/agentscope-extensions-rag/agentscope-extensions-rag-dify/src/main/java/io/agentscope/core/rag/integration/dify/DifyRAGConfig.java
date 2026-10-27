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
package io.agentscope.core.rag.integration.dify;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Dify 知识库集成配置。
 *
 * <p>包含连接 Dify 服务所需的连接设置、检索参数和 HTTP 客户端设置。
 *
 * <p>用法示例：
 * <pre>{@code
 * DifyRAGConfig config = DifyRAGConfig.builder()
 *     .apiKey(System.getenv("DIFY_RAG_API_KEY"))
 *     .datasetId("your-dataset-id")  // 在代码中配置
 *     .apiBaseUrl("https://api.dify.ai/v1")  // 可选，用于自行托管
 *     .retrievalMode(RetrievalMode.HYBRID_SEARCH)
 *     .topK(10)
 *     .scoreThreshold(0.5)
 *     .enableRerank(true)
 *     .rerankConfig(RerankConfig.builder()
 *         .topN(5)
 *         .build())
 *     .build();
 * }</pre>
 */
public class DifyRAGConfig {

    private static final String DEFAULT_API_BASE_URL = "https://api.dify.ai/v1";
    private static final RetrievalMode DEFAULT_RETRIEVAL_MODE = RetrievalMode.HYBRID_SEARCH;
    private static final Integer DEFAULT_TOP_K = 10;
    private static final Double DEFAULT_SCORE_THRESHOLD = 0.0;
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(60);
    private static final Integer DEFAULT_MAX_RETRIES = 3;

    // 连接配置。
    private final String apiKey;
    private final String apiBaseUrl;
    private final String datasetId;

    // 检索配置。
    private final RetrievalMode retrievalMode;
    private final Integer topK;
    private final Double scoreThreshold;

    // 重排序配置。
    private final Boolean enableRerank;
    private final RerankConfig rerankConfig;

    // 混合检索权重，范围 0～1，用于 hybrid_search 模式。
    private final Double weights;

    // 元数据过滤。
    private final MetadataFilter metadataFilter;

    // HTTP 配置。
    private final Duration connectTimeout;
    private final Duration readTimeout;
    private final Integer maxRetries;

    // 高级配置。
    private final Map<String, String> customHeaders;

    private DifyRAGConfig(Builder builder) {
        // 验证必填字段。
        if (builder.apiKey == null || builder.apiKey.trim().isEmpty()) {
            throw new IllegalArgumentException("API key cannot be null or empty");
        }
        if (builder.datasetId == null || builder.datasetId.trim().isEmpty()) {
            throw new IllegalArgumentException("Dataset ID cannot be null or empty");
        }

        // 连接配置。
        this.apiKey = builder.apiKey.trim();
        this.apiBaseUrl =
                (builder.apiBaseUrl != null && !builder.apiBaseUrl.trim().isEmpty())
                        ? builder.apiBaseUrl.trim()
                        : DEFAULT_API_BASE_URL;
        this.datasetId = builder.datasetId.trim();

        // 检索配置。
        this.retrievalMode =
                builder.retrievalMode != null ? builder.retrievalMode : DEFAULT_RETRIEVAL_MODE;
        this.topK = builder.topK != null ? builder.topK : DEFAULT_TOP_K;
        this.scoreThreshold =
                builder.scoreThreshold != null ? builder.scoreThreshold : DEFAULT_SCORE_THRESHOLD;

        // 重排序配置。
        this.enableRerank = builder.enableRerank;
        this.rerankConfig = builder.rerankConfig;

        // 混合检索权重。
        this.weights = builder.weights;

        // 元数据过滤。
        this.metadataFilter = builder.metadataFilter;

        // HTTP 配置。
        this.connectTimeout =
                builder.connectTimeout != null ? builder.connectTimeout : DEFAULT_CONNECT_TIMEOUT;
        this.readTimeout = builder.readTimeout != null ? builder.readTimeout : DEFAULT_READ_TIMEOUT;
        this.maxRetries = builder.maxRetries != null ? builder.maxRetries : DEFAULT_MAX_RETRIES;

        // 高级配置。
        this.customHeaders =
                builder.customHeaders != null
                        ? new HashMap<>(builder.customHeaders)
                        : new HashMap<>();
    }

    /**
     * 获取 Dify API 密钥。
     *
     * @return API 密钥
     */
    public String getApiKey() {
        return apiKey;
    }

    /**
     * 获取 Dify API 基础 URL。
     *
     * @return 基础 URL
     */
    public String getApiBaseUrl() {
        return apiBaseUrl;
    }

    /**
     * 获取数据集（知识库）ID。
     *
     * @return 数据集 ID
     */
    public String getDatasetId() {
        return datasetId;
    }

    /**
     * 获取检索模式。
     *
     * @return 检索模式
     */
    public RetrievalMode getRetrievalMode() {
        return retrievalMode;
    }

    /**
     * 获取检索 top K 值。
     *
     * @return top K，范围 1～100
     */
    public Integer getTopK() {
        return topK;
    }

    /**
     * 获取结果过滤分数阈值。
     *
     * @return 分数阈值，范围 0.0～1.0
     */
    public Double getScoreThreshold() {
        return scoreThreshold;
    }

    /**
     * 检查是否启用重排序。
     *
     * @return 启用时为 true，未配置时为 null
     */
    public Boolean getEnableRerank() {
        return enableRerank;
    }

    /**
     * 获取重排序配置。
     *
     * @return 重排序配置，未设置时为 null
     */
    public RerankConfig getRerankConfig() {
        return rerankConfig;
    }

    /**
     * 获取混合检索权重。
     *
     * <p>用于 HYBRID_SEARCH，控制关键词与语义检索的平衡。
     *
     * @return 权重，范围 0～1；未设置时为 null
     */
    public Double getWeights() {
        return weights;
    }

    /**
     * 获取元数据过滤配置。
     *
     * @return 元数据过滤器，未设置时为 null
     */
    public MetadataFilter getMetadataFilter() {
        return metadataFilter;
    }

    /**
     * 获取 HTTP 连接超时。
     *
     * @return 连接超时
     */
    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    /**
     * 获取 HTTP 读取超时。
     *
     * @return 读取超时
     */
    public Duration getReadTimeout() {
        return readTimeout;
    }

    /**
     * 获取失败请求的最大重试次数。
     *
     * @return 最大重试次数
     */
    public Integer getMaxRetries() {
        return maxRetries;
    }

    /**
     * 获取自定义 HTTP 请求头。
     *
     * @return 自定义请求头映射的副本
     */
    public Map<String, String> getCustomHeaders() {
        return new HashMap<>(customHeaders);
    }

    /**
     * 创建 DifyRAGConfig 构建器。
     *
     * @return 新的 Builder 实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /** DifyRAGConfig 的构建器。 */
    public static class Builder {
        // 连接配置。
        private String apiKey;
        private String apiBaseUrl;
        private String datasetId;

        // 检索配置。
        private RetrievalMode retrievalMode;
        private Integer topK;
        private Double scoreThreshold;

        // 重排序配置。
        private Boolean enableRerank;
        private RerankConfig rerankConfig;

        // 混合检索权重。
        private Double weights;

        // 元数据过滤。
        private MetadataFilter metadataFilter;

        // HTTP 配置。
        private Duration connectTimeout;
        private Duration readTimeout;
        private Integer maxRetries;

        // 高级配置。
        private Map<String, String> customHeaders;

        private Builder() {}

        /**
         * 设置 Dify API 密钥。
         *
         * <p>从 Dify 控制台获取数据集 API 密钥。
         *
         * @param apiKey API 密钥，格式如 dataset-xxxxxxxxxxxxxxxx
         * @return 当前构建器，支持链式调用
         */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /**
         * 设置 Dify API 基础 URL。
         *
         * <p>Dify Cloud 默认使用 https://api.dify.ai/v1。
         * 自托管部署应设置实例 URL，例如 https://your-dify.com/v1。
         *
         * <p>未设置时默认为 https://api.dify.ai/v1。
         *
         * @param apiBaseUrl API 基础 URL，必须包含 /v1 后缀
         * @return 当前构建器，支持链式调用
         */
        public Builder apiBaseUrl(String apiBaseUrl) {
            this.apiBaseUrl = apiBaseUrl;
            return this;
        }

        /**
         * 设置数据集（知识库）ID。
         *
         * <p>可从 Dify 控制台的数据集 URL 获取，格式为 UUID。
         *
         * @param datasetId 数据集 ID
         * @return 当前构建器，支持链式调用
         */
        public Builder datasetId(String datasetId) {
            this.datasetId = datasetId;
            return this;
        }

        /**
         * 设置检索模式。
         *
         * <p>可用模式：
         * <ul><li>KEYWORD：传统关键词检索。</li><li>SEMANTIC：向量相似性检索。</li>
         * <li>HYBRID：关键词与语义组合。</li><li>FULLTEXT：全文检索。</li></ul>
         *
         * <p>默认为 HYBRID。
         *
         * @param retrievalMode 检索模式
         * @return 当前构建器，支持链式调用
         */
        public Builder retrievalMode(RetrievalMode retrievalMode) {
            this.retrievalMode = retrievalMode;
            return this;
        }

        /**
         * 设置检索 top K，即从知识库召回的文档数。
         *
         * <p>范围 [1, 100]，默认 10。
         *
         * @param topK top K 值
         * @return 当前构建器，支持链式调用
         * @throws IllegalArgumentException topK 不在有效范围内时抛出
         */
        public Builder topK(Integer topK) {
            if (topK != null && (topK < 1)) {
                throw new IllegalArgumentException("topK must be larger than 1");
            }
            this.topK = topK;
            return this;
        }

        /**
         * 设置结果过滤分数阈值。
         *
         * <p>仅返回分数不低于阈值的文档。范围 [0.0, 1.0]，默认 0.0，不主动启用服务端阈值过滤。
         *
         * @param scoreThreshold 分数阈值
         * @return 当前构建器，支持链式调用
         * @throws IllegalArgumentException scoreThreshold 不在有效范围内时抛出
         */
        public Builder scoreThreshold(Double scoreThreshold) {
            if (scoreThreshold != null && (scoreThreshold < 0.0 || scoreThreshold > 1.0)) {
                throw new IllegalArgumentException("scoreThreshold must be between 0.0 and 1.0");
            }
            this.scoreThreshold = scoreThreshold;
            return this;
        }

        /**
         * 设置是否启用重排序。
         *
         * <p>使用专门模型重新评分与排序已召回文档，以额外耗时换取相关性改善。
         *
         * <p>默认未配置，客户端仅在显式为 true 时启用。
         *
         * @param enableRerank true 表示启用重排序
         * @return 当前构建器，支持链式调用
         */
        public Builder enableRerank(Boolean enableRerank) {
            this.enableRerank = enableRerank;
            return this;
        }

        /**
         * 设置重排序配置。
         *
         * <p>仅在 enableRerank 为 true 时生效。
         *
         * @param rerankConfig 重排序配置
         * @return 当前构建器，支持链式调用
         */
        public Builder rerankConfig(RerankConfig rerankConfig) {
            this.rerankConfig = rerankConfig;
            return this;
        }

        /**
         * 设置混合检索权重。
         *
         * <p>用于 HYBRID_SEARCH，控制关键词与语义检索的平衡。
         * 接近 1 偏重语义，接近 0 偏重关键词。
         *
         * <p>范围 [0.0, 1.0]。
         *
         * @param weights 权重值
         * @return 当前构建器，支持链式调用
         * @throws IllegalArgumentException weights 不在有效范围内时抛出
         */
        public Builder weights(Double weights) {
            if (weights != null && (weights < 0.0 || weights > 1.0)) {
                throw new IllegalArgumentException("weights must be between 0.0 and 1.0");
            }
            this.weights = weights;
            return this;
        }

        /**
         * 设置元数据过滤配置，以文档元数据字段进行过滤。
         *
         * @param metadataFilter 元数据过滤配置
         * @return 当前构建器，支持链式调用
         */
        public Builder metadataFilter(MetadataFilter metadataFilter) {
            this.metadataFilter = metadataFilter;
            return this;
        }

        /**
         * 设置 HTTP 连接超时。
         *
         * <p>默认 30 秒。
         *
         * @param connectTimeout 连接超时
         * @return 当前构建器，支持链式调用
         */
        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
            return this;
        }

        /**
         * 设置 HTTP 读取超时。
         *
         * <p>默认 60 秒。
         *
         * @param readTimeout 读取超时
         * @return 当前构建器，支持链式调用
         */
        public Builder readTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
            return this;
        }

        /**
         * 设置失败请求的最大重试次数。
         *
         * <p>默认为 3。
         *
         * @param maxRetries 最大重试次数，0 表示不重试
         * @return 当前构建器，支持链式调用
         * @throws IllegalArgumentException maxRetries 为负数时抛出
         */
        public Builder maxRetries(Integer maxRetries) {
            if (maxRetries != null && maxRetries < 0) {
                throw new IllegalArgumentException("maxRetries cannot be negative");
            }
            this.maxRetries = maxRetries;
            return this;
        }

        /**
         * 设置所有请求携带的自定义 HTTP 请求头。
         *
         * <p>可用于自定义认证、追踪请求头等。
         *
         * @param customHeaders 请求头名称到值的映射
         * @return 当前构建器，支持链式调用
         */
        public Builder customHeaders(Map<String, String> customHeaders) {
            this.customHeaders = customHeaders != null ? new HashMap<>(customHeaders) : null;
            return this;
        }

        /**
         * 添加单个自定义 HTTP 请求头。
         *
         * @param name 请求头名称
         * @param value 请求头值
         * @return 当前构建器，支持链式调用
         */
        public Builder addCustomHeader(String name, String value) {
            if (this.customHeaders == null) {
                this.customHeaders = new HashMap<>();
            }
            this.customHeaders.put(name, value);
            return this;
        }

        /**
         * 构建 DifyRAGConfig 实例。
         *
         * @return 新的 DifyRAGConfig 实例
         * @throws IllegalArgumentException 必要参数缺失或无效时抛出
         */
        public DifyRAGConfig build() {
            return new DifyRAGConfig(this);
        }
    }

    @Override
    public String toString() {
        return "DifyRAGConfig{"
                + "apiBaseUrl='"
                + apiBaseUrl
                + '\''
                + ", datasetId='"
                + datasetId
                + '\''
                + ", retrievalMode="
                + retrievalMode
                + ", topK="
                + topK
                + ", scoreThreshold="
                + scoreThreshold
                + ", enableRerank="
                + enableRerank
                + ", rerankConfig="
                + rerankConfig
                + ", weights="
                + weights
                + ", metadataFilter="
                + metadataFilter
                + ", connectTimeout="
                + connectTimeout
                + ", readTimeout="
                + readTimeout
                + ", maxRetries="
                + maxRetries
                + '}';
    }
}
