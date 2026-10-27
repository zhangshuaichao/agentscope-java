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
package io.agentscope.core.rag.integration.ragflow;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RAGFlow 集成配置。
 *
 * <p>保存连接 RAGFlow 服务所需的认证、端点及检索参数。
 *
 * <p><b>API 参考：</b>此配置面向 RAGFlow 检索 API：{@code POST /api/v1/retrieval}
 */
public class RAGFlowConfig {

    private static final int DEFAULT_TOP_K = 1024;

    private static final double DEFAULT_SIMILARITY_THRESHOLD = 0.2;

    private static final double DEFAULT_VECTOR_SIMILARITY_WEIGHT = 0.3;

    private static final int DEFAULT_PAGE = 1;

    private static final int DEFAULT_PAGE_SIZE = 30;

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private static final int DEFAULT_MAX_RETRIES = 3;

    // 认证
    private final String apiKey;

    // 端点
    private final String baseUrl;

    // 数据集（知识库）：数据集 ID 数组
    private final List<String> datasetIds;

    // 可选：限定指定文档
    private final List<String> documentIds;

    // 检索参数
    private final Integer topK;

    private final Double similarityThreshold;

    private final Double vectorSimilarityWeight;

    private final Integer page;

    private final Integer pageSize;

    private final Boolean useKg;

    private final Boolean tocEnhance;

    private final String rerankId;

    private final Boolean keyword;

    private final Boolean highlight;

    private final List<String> crossLanguages;

    private final Map<String, Object> metadataCondition;

    // HTTP 客户端设置
    private final Duration timeout;

    private final Integer maxRetries;

    private final Map<String, String> customHeaders;

    private RAGFlowConfig(Builder builder) {
        this.apiKey = builder.apiKey;
        this.baseUrl = builder.baseUrl;
        this.datasetIds = builder.datasetIds;
        this.documentIds = builder.documentIds;
        this.topK = builder.topK;
        this.similarityThreshold = builder.similarityThreshold;
        this.vectorSimilarityWeight = builder.vectorSimilarityWeight;
        this.page = builder.page;
        this.pageSize = builder.pageSize;
        this.useKg = builder.useKg;
        this.tocEnhance = builder.tocEnhance;
        this.rerankId = builder.rerankId;
        this.keyword = builder.keyword;
        this.highlight = builder.highlight;
        this.crossLanguages = builder.crossLanguages;
        this.metadataCondition = builder.metadataCondition;
        this.timeout = builder.timeout;
        this.maxRetries = builder.maxRetries;
        this.customHeaders = builder.customHeaders;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getApiKey() {
        return apiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    /**
     * 获取数据集 ID。
     *
     * <p>这些 ID 用于 API 请求体：{@code "dataset_ids": ["id1", "id2"]}
     *
     * @return 数据集 ID 列表
     */
    public List<String> getDatasetIds() {
        return datasetIds;
    }

    /**
     * 获取用于过滤检索范围的文档 ID。
     *
     * <p>可选：指定后只在这些文档中搜索。
     *
     * @return 文档 ID 列表；未设置时为 null
     */
    public List<String> getDocumentIds() {
        return documentIds;
    }

    /**
     * 获取检索的 top K 值。
     *
     * <p>默认值：5（按 RAGFlow API 文档）
     *
     * @return top K 值
     */
    public Integer getTopK() {
        return topK;
    }

    /**
     * 获取用于过滤结果的相似度阈值。
     *
     * <p>默认值：0.2（按 RAGFlow API 文档）
     *
     * @return 相似度阈值（0.0–1.0）
     */
    public Double getSimilarityThreshold() {
        return similarityThreshold;
    }

    /**
     * 获取向量相似度权重。
     *
     * <p>向量余弦相似度的权重。若该权重为 x，则词项相似度权重为 (1 - x)。
     *
     * <p>默认值：0.3
     *
     * @return 向量相似度权重（0.0–1.0）
     */
    public Double getVectorSimilarityWeight() {
        return vectorSimilarityWeight;
    }

    /**
     * 获取分页页码。
     *
     * <p>默认值：1
     *
     * @return 页码
     */
    public Integer getPage() {
        return page;
    }

    /**
     * 获取分页大小。
     *
     * <p>默认值：30
     *
     * @return 分页大小
     */
    public Integer getPageSize() {
        return pageSize;
    }

    /**
     * 获取是否在多跳查询中使用知识图谱。
     *
     * <p>默认值：false
     *
     * @return 启用知识图谱时返回 true
     */
    public Boolean getUseKg() {
        return useKg;
    }

    /**
     * 获取是否启用 TOC（Table of Contents，目录）增强。
     *
     * <p>默认值：false
     *
     * @return 启用目录增强时返回 true
     */
    public Boolean getTocEnhance() {
        return tocEnhance;
    }

    /**
     * 获取重排序模型 ID。
     *
     * <p>RAGFlow 以名称（例如 {@code "BAAI/bge-reranker-v2-m3@BAAI"}）
     * 或十六进制 UUID 标识重排序模型，因此使用 {@link String} 而非数字 ID。
     *
     * @return 重排序模型 ID；未设置时为 null
     */
    public String getRerankId() {
        return rerankId;
    }

    /**
     * 获取是否启用基于关键词的匹配。
     *
     * <p>默认值：false
     *
     * @return 启用关键词匹配时返回 true
     */
    public Boolean getKeyword() {
        return keyword;
    }

    /**
     * 获取是否启用匹配词高亮。
     *
     * <p>默认值：false
     *
     * @return 启用高亮时返回 true
     */
    public Boolean getHighlight() {
        return highlight;
    }

    /**
     * 获取跨语言检索的语言列表。
     *
     * @return 目标语言列表；未设置时为 null
     */
    public List<String> getCrossLanguages() {
        return crossLanguages;
    }

    /**
     * 获取元数据过滤条件。
     *
     * <p>用于按元数据字段过滤分块，例如：
     *
     * <pre>{@code
     * Map<String, Object> condition = Map.of(
     *     "author", "John",
     *     "category", "tech"
     * );
     * }</pre>
     *
     * @return 元数据条件映射；未设置时为 null
     */
    public Map<String, Object> getMetadataCondition() {
        return metadataCondition;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public Integer getMaxRetries() {
        return maxRetries;
    }

    public Map<String, String> getCustomHeaders() {
        return customHeaders;
    }

    public static class Builder {

        private String apiKey;

        private String baseUrl;

        private List<String> datasetIds = new ArrayList<>();

        private List<String> documentIds;

        private Integer topK = DEFAULT_TOP_K;

        private Double similarityThreshold = DEFAULT_SIMILARITY_THRESHOLD;

        private Double vectorSimilarityWeight = DEFAULT_VECTOR_SIMILARITY_WEIGHT;

        private Integer page = DEFAULT_PAGE;

        private Integer pageSize = DEFAULT_PAGE_SIZE;

        private Boolean useKg;

        private Boolean tocEnhance;

        private String rerankId;

        private Boolean keyword;

        private Boolean highlight;

        private List<String> crossLanguages;

        private Map<String, Object> metadataCondition;

        private Duration timeout = DEFAULT_TIMEOUT;

        private Integer maxRetries = DEFAULT_MAX_RETRIES;

        private Map<String, String> customHeaders = new HashMap<>();

        /**
         * 设置 RAGFlow API 密钥。
         *
         * @param apiKey API 密钥（必填）
         * @return 此构建器
         */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /**
         * 设置 RAGFlow 基础 URL。
         *
         * <p>默认：http://localhost:9380
         *
         * @param baseUrl 基础 URL
         * @return 此构建器
         */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /**
         * 设置数据集 ID（替换现有列表）。
         *
         * <p>这些是待搜索的 RAGFlow 数据集 ID，可在 RAGFlow 控制台中找到。
         *
         * @param datasetIds 数据集 ID 列表（必填，至少一项）
         * @return 此构建器
         */
        public Builder datasetIds(List<String> datasetIds) {
            this.datasetIds = new ArrayList<>(datasetIds);
            return this;
        }

        /**
         * 向列表添加单个数据集 ID。
         *
         * @param datasetId 待添加的数据集 ID
         * @return 此构建器
         */
        public Builder addDatasetId(String datasetId) {
            this.datasetIds.add(datasetId);
            return this;
        }

        /**
         * 设置用于限定检索范围的文档 ID（可选）。
         *
         * <p>指定后只在这些文档中搜索。
         *
         * @param documentIds 文档 ID 列表
         * @return 此构建器
         */
        public Builder documentIds(List<String> documentIds) {
            this.documentIds = documentIds != null ? new ArrayList<>(documentIds) : null;
            return this;
        }

        /**
         * 向过滤列表添加单个文档 ID。
         *
         * @param documentId 待添加的文档 ID
         * @return 此构建器
         */
        public Builder addDocumentId(String documentId) {
            if (this.documentIds == null) {
                this.documentIds = new ArrayList<>();
            }
            this.documentIds.add(documentId);
            return this;
        }

        /**
         * 设置检索 top K 值。
         *
         * <p>默认：5
         *
         * @param topK 检索分块数（必须 > 0）
         * @return 此构建器
         */
        public Builder topK(Integer topK) {
            if (topK != null && topK <= 0) {
                throw new IllegalArgumentException("topK must be greater than 0");
            }
            this.topK = topK;
            return this;
        }

        /**
         * 设置相似度阈值。
         *
         * <p>仅返回相似度 >= 阈值的分块。
         *
         * <p>默认：0.2
         *
         * @param similarityThreshold 相似度阈值（0.0–1.0）
         * @return 此构建器
         */
        public Builder similarityThreshold(Double similarityThreshold) {
            if (similarityThreshold != null
                    && (similarityThreshold < 0.0 || similarityThreshold > 1.0)) {
                throw new IllegalArgumentException(
                        "similarityThreshold must be between 0.0 and 1.0");
            }
            this.similarityThreshold = similarityThreshold;
            return this;
        }

        /**
         * 设置向量相似度权重。
         *
         * <p>向量余弦相似度的权重。若该权重为 x，则词项相似度权重为 (1 - x)。
         *
         * <p>默认：0.3
         *
         * @param vectorSimilarityWeight 权重（0.0–1.0）
         * @return 此构建器
         */
        public Builder vectorSimilarityWeight(Double vectorSimilarityWeight) {
            if (vectorSimilarityWeight != null
                    && (vectorSimilarityWeight < 0.0 || vectorSimilarityWeight > 1.0)) {
                throw new IllegalArgumentException(
                        "vectorSimilarityWeight must be between 0.0 and 1.0");
            }
            this.vectorSimilarityWeight = vectorSimilarityWeight;
            return this;
        }

        /**
         * 设置分页页码。
         *
         * <p>默认：1
         *
         * @param page 页码（>= 1）
         * @return 此构建器
         */
        public Builder page(Integer page) {
            if (page != null && page < 1) {
                throw new IllegalArgumentException("page must be >= 1");
            }
            this.page = page;
            return this;
        }

        /**
         * 设置分页大小。
         *
         * <p>默认：30
         *
         * @param pageSize 分页大小（>= 1）
         * @return 此构建器
         */
        public Builder pageSize(Integer pageSize) {
            if (pageSize != null && pageSize < 1) {
                throw new IllegalArgumentException("pageSize must be >= 1");
            }
            this.pageSize = pageSize;
            return this;
        }

        /**
         * 设置是否在多跳查询中使用知识图谱。
         *
         * <p>启用前，确保已经为指定数据集成功构建知识图谱。
         *
         * <p>默认：false
         *
         * @param useKg true 表示启用知识图谱
         * @return 此构建器
         */
        public Builder useKg(Boolean useKg) {
            this.useKg = useKg;
            return this;
        }

        /**
         * 设置是否启用 TOC（Table of Contents，目录）增强。
         *
         * <p>启用前，确保已启用 TOC_Enhance 并成功提取指定数据集的目录。
         *
         * <p>默认：false
         *
         * @param tocEnhance true 表示启用目录增强
         * @return 此构建器
         */
        public Builder tocEnhance(Boolean tocEnhance) {
            this.tocEnhance = tocEnhance;
            return this;
        }

        /**
         * 设置重排序模型 ID。
         *
         * <p>RAGFlow 的 {@code /api/v1/retrieval} 端点要求 {@code rerank_id}
         * 为重排序模型名称（例如 {@code "BAAI/bge-reranker-v2-m3@BAAI"}），
         * 或十六进制 UUID（例如 {@code "b2a62730759d11ef987d0242ac120004"}）。
         *
         * @param rerankId 重排序模型 ID
         * @return 此构建器
         */
        public Builder rerankId(String rerankId) {
            this.rerankId = rerankId;
            return this;
        }

        /**
         * 使用数值设置重排序模型 ID。
         *
         * @param rerankId 重排序模型 ID
         * @return 此构建器
         * @deprecated RAGFlow 以名称而非数字 ID 标识重排序模型，因此没有
         *     {@code Integer} 能映射到真实模型，检索请求会被拒绝。请改用
         *     {@link #rerankId(String)}。此重载仅保留既有调用点的编译兼容性，并将值转为字符串。
         */
        @Deprecated(forRemoval = true)
        public Builder rerankId(Integer rerankId) {
            this.rerankId = rerankId != null ? String.valueOf(rerankId) : null;
            return this;
        }

        /**
         * 设置是否启用基于关键词的匹配。
         *
         * <p>默认：false
         *
         * @param keyword true 表示启用关键词匹配
         * @return 此构建器
         */
        public Builder keyword(Boolean keyword) {
            this.keyword = keyword;
            return this;
        }

        /**
         * 设置是否在结果中高亮匹配词。
         *
         * <p>默认：false
         *
         * @param highlight true 表示启用高亮
         * @return 此构建器
         */
        public Builder highlight(Boolean highlight) {
            this.highlight = highlight;
            return this;
        }

        /**
         * 设置跨语言检索的语言。
         *
         * <p>指定应翻译到哪些语言，以实现不同语言的关键词检索。
         *
         * @param crossLanguages 目标语言列表
         * @return 此构建器
         */
        public Builder crossLanguages(List<String> crossLanguages) {
            this.crossLanguages = crossLanguages != null ? new ArrayList<>(crossLanguages) : null;
            return this;
        }

        /**
         * 添加一种跨语言检索语言。
         *
         * @param language 待添加的目标语言
         * @return 此构建器
         */
        public Builder addCrossLanguage(String language) {
            if (this.crossLanguages == null) {
                this.crossLanguages = new ArrayList<>();
            }
            this.crossLanguages.add(language);
            return this;
        }

        /**
         * 设置元数据过滤条件。
         *
         * <p>用于按元数据过滤分块，例如：
         *
         * <pre>{@code
         * builder.metadataCondition(Map.of(
         *     "author", "John",
         *     "category", "tech"
         * ));
         * }</pre>
         *
         * @param metadataCondition 元数据条件映射
         * @return 此构建器
         */
        public Builder metadataCondition(Map<String, Object> metadataCondition) {
            this.metadataCondition =
                    metadataCondition != null ? new HashMap<>(metadataCondition) : null;
            return this;
        }

        /**
         * 设置 HTTP 超时。
         *
         * <p>默认：30 秒
         *
         * @param timeout 超时时长
         * @return 此构建器
         */
        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        /**
         * 设置失败请求的最大重试次数。
         *
         * <p>默认：3
         *
         * @param maxRetries 最大重试次数（>= 0）
         * @return 此构建器
         */
        public Builder maxRetries(Integer maxRetries) {
            if (maxRetries != null && maxRetries < 0) {
                throw new IllegalArgumentException("maxRetries cannot be negative");
            }
            this.maxRetries = maxRetries;
            return this;
        }

        /**
         * 设置自定义 HTTP 请求头。
         *
         * @param customHeaders 自定义请求头映射
         * @return 此构建器
         */
        public Builder customHeaders(Map<String, String> customHeaders) {
            this.customHeaders = new HashMap<>(customHeaders);
            return this;
        }

        /**
         * 添加自定义 HTTP 请求头。
         *
         * @param key 请求头名称
         * @param value 请求头值
         * @return 此构建器
         */
        public Builder addCustomHeader(String key, String value) {
            this.customHeaders.put(key, value);
            return this;
        }

        /**
         * 构建 RAGFlowConfig 实例。
         *
         * <p>必须设置 dataset_ids 或 document_ids（也可以两者都设置）。
         * 只设置 document_ids 而不设置 dataset_ids 时，确保所有文档使用同一嵌入模型。
         *
         * @return 新的 RAGFlowConfig 实例
         * @throws IllegalArgumentException 缺少必填参数时抛出
         */
        public RAGFlowConfig build() {
            if (baseUrl == null || baseUrl.trim().isEmpty()) {
                throw new IllegalArgumentException("BaseUrl key is required");
            }
            if (apiKey == null || apiKey.trim().isEmpty()) {
                throw new IllegalArgumentException("API key is required");
            }
            boolean hasDatasetIds = datasetIds != null && !datasetIds.isEmpty();
            boolean hasDocumentIds = documentIds != null && !documentIds.isEmpty();
            if (!hasDatasetIds && !hasDocumentIds) {
                throw new IllegalArgumentException(
                        "Either dataset_ids or document_ids must be set. Use"
                                + " addDatasetId()/datasetIds() or addDocumentId()/documentIds()");
            }
            return new RAGFlowConfig(this);
        }
    }
}
