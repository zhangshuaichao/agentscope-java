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
package io.agentscope.core.rag.integration.haystack;

import io.agentscope.core.rag.integration.haystack.model.HayStackDocument;
import io.agentscope.core.rag.integration.haystack.model.SparseEmbedding;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * HayStack 集成配置。
 *
 * <p>保存连接 HayStack 服务的端点及检索参数。
 *
 * <p><b>API 约定：</b>此配置服务于本集成所约定的 HayStack RAG 检索接口。
 */
public class HayStackConfig {

    private static final int DEFAULT_TOP_K = 3;

    public static final int DEFAULT_SUCCESS_CODE = 0;

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private static final int DEFAULT_MAX_RETRIES = 3;

    // 端点。
    private final String baseUrl;

    private final Integer successCode;

    // 检索参数。
    private final Integer topK;

    private final Boolean scaleScore;

    private final Boolean returnEmbedding;

    private final Double scoreThreshold;

    private final String groupBy;

    private final Integer groupSize;

    private final List<Float> queryEmbedding;

    private final SparseEmbedding querySparseEmbedding;

    private final List<HayStackDocument> documents;

    private final List<HayStackDocument> retrievedDocuments;

    private final Integer windowSize;

    private final Map<String, Object> filters;

    private final FilterPolicy filterPolicy;

    // HTTP 客户端设置。
    private final Duration timeout;

    private final Integer maxRetries;

    private final Map<String, String> customHeaders;

    private HayStackConfig(Builder builder) {
        this.baseUrl = builder.baseUrl;
        this.successCode = builder.successCode;
        this.topK = builder.topK;
        this.scaleScore = builder.scaleScore;
        this.returnEmbedding = builder.returnEmbedding;
        this.scoreThreshold = builder.scoreThreshold;
        this.groupBy = builder.groupBy;
        this.groupSize = builder.groupSize;
        this.queryEmbedding = builder.queryEmbedding;
        this.querySparseEmbedding = builder.querySparseEmbedding;
        this.documents = builder.documents;
        this.retrievedDocuments = builder.retrievedDocuments;
        this.windowSize = builder.windowSize;
        this.filters = builder.filters;
        this.filterPolicy = builder.filterPolicy;
        this.timeout = builder.timeout;
        this.maxRetries = builder.maxRetries;
        this.customHeaders = builder.customHeaders;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public Integer getSuccessCode() {
        return successCode;
    }

    public Integer getTopK() {
        return topK;
    }

    public Boolean getScaleScore() {
        return scaleScore;
    }

    public Boolean getReturnEmbedding() {
        return returnEmbedding;
    }

    public Double getScoreThreshold() {
        return scoreThreshold;
    }

    public String getGroupBy() {
        return groupBy;
    }

    public Integer getGroupSize() {
        return groupSize;
    }

    public List<Float> getQueryEmbedding() {
        return queryEmbedding;
    }

    public SparseEmbedding getQuerySparseEmbedding() {
        return querySparseEmbedding;
    }

    public List<HayStackDocument> getDocuments() {
        return documents;
    }

    public List<HayStackDocument> getRetrievedDocuments() {
        return retrievedDocuments;
    }

    public Integer getWindowSize() {
        return windowSize;
    }

    public Map<String, Object> getFilters() {
        return filters;
    }

    public FilterPolicy getFilterPolicy() {
        return filterPolicy;
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

        private String baseUrl;

        private Integer successCode = DEFAULT_SUCCESS_CODE;

        private Integer topK = DEFAULT_TOP_K;

        private Boolean scaleScore;

        private Boolean returnEmbedding;

        private Double scoreThreshold;

        private String groupBy;

        private Integer groupSize;

        private List<Float> queryEmbedding;

        private SparseEmbedding querySparseEmbedding;

        private List<HayStackDocument> documents;

        private List<HayStackDocument> retrievedDocuments;

        private Integer windowSize;

        private Map<String, Object> filters;

        private FilterPolicy filterPolicy;

        private Duration timeout = DEFAULT_TIMEOUT;

        private Integer maxRetries = DEFAULT_MAX_RETRIES;

        private Map<String, String> customHeaders = new HashMap<>();

        /**
         * 设置 HayStack 基础 URL。
         *
         * @param baseUrl 基础 URL
         * @return 当前构建器
         */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /**
         * 设置 HayStack API 成功码。
         *
         * <p>默认为 0。
         *
         * @param successCode 成功码
         * @return 当前构建器
         */
        public Builder successCode(Integer successCode) {
            this.successCode = successCode;
            return this;
        }

        /**
         * 返回文档的最大数量；使用 group_by 参数时表示最多返回的组数。
         *
         * <p>默认为 3。
         *
         * @param topK 要检索的分块数，必须大于 0
         * @return 当前构建器
         */
        public Builder topK(Integer topK) {
            if (topK != null && topK <= 0) {
                throw new IllegalArgumentException("topK must be greater than 0");
            }
            this.topK = topK;
            return this;
        }

        /**
         * 设置是否缩放检索文档的分数。
         *
         * <p>true 将分数缩放到 0～1，其中 1 表示极高相关性；false 使用原始相似度分数。
         *
         * @param scaleScore 是否缩放分数
         * @return 当前构建器
         */
        public Builder scaleScore(Boolean scaleScore) {
            this.scaleScore = scaleScore;
            return this;
        }

        /**
         * 设置是否返回检索文档的嵌入向量。
         *
         * @param returnEmbedding 是否返回嵌入
         * @return 当前构建器
         */
        public Builder returnEmbedding(Boolean returnEmbedding) {
            this.returnEmbedding = returnEmbedding;
            return this;
        }

        /**
         * 设置结果的分数阈值。
         *
         * <p>根据距离函数，服务端可能返回高于或低于阈值的结果，例如余弦相似度保留更高分数。
         *
         * @param scoreThreshold 分数阈值
         * @return 当前构建器
         */
        public Builder scoreThreshold(Double scoreThreshold) {
            this.scoreThreshold = scoreThreshold;
            return this;
        }

        /**
         * 指定用于分组的载荷字段，必须为字符串或数值字段。
         * 字段包含多个值时，每个值都参与分组，同一个点可属于多个组。
         *
         * @param groupBy 分组字段名称
         * @return 当前构建器
         */
        public Builder groupBy(String groupBy) {
            this.groupBy = groupBy;
            return this;
        }

        /**
         * 每组最多返回的点数。
         *
         * <p>默认为 3。
         *
         * @param groupSize 每组大小
         * @return 当前构建器
         */
        public Builder groupSize(Integer groupSize) {
            this.groupSize = groupSize;
            return this;
        }

        /**
         * 直接使用查询向量从向量存储检索。
         *
         * <p>通常应传普通查询文本，并结合 Haystack Embedders 生成向量，而不是直接指定。
         *
         * @param queryEmbedding 查询嵌入向量
         * @return 当前构建器
         */
        public Builder queryEmbedding(List<Float> queryEmbedding) {
            this.queryEmbedding = queryEmbedding;
            return this;
        }

        /**
         * 查询的稀疏嵌入。
         *
         * @param querySparseEmbedding SparseEmbedding 稀疏向量
         * @return 当前构建器
         */
        public Builder querySparseEmbedding(SparseEmbedding querySparseEmbedding) {
            this.querySparseEmbedding = querySparseEmbedding;
            return this;
        }

        public Builder documents(List<HayStackDocument> documents) {
            this.documents = documents;
            return this;
        }

        /**
         * 文档必须含表明来源与位置的 metadata：
         * <p>source_id 将来自同一原始文档的句子块分为一组；
         * split_id 表示该块在文档中的位置或顺序。
         *
         * @param retrievedDocuments 文档列表
         * @return 当前构建器
         */
        public Builder retrievedDocuments(List<HayStackDocument> retrievedDocuments) {
            this.retrievedDocuments = retrievedDocuments;
            return this;
        }

        /**
         * 通过 window_size 配置命中文档两侧各包含多少邻近文档。
         *
         * @param windowSize 窗口大小
         * @return 当前构建器
         */
        public Builder windowSize(Integer windowSize) {
            this.windowSize = windowSize;
            return this;
        }

        /**
         * 用于缩小检索器在文档存储中搜索范围的过滤字典。
         *
         * @param filters 过滤条件
         * @return 当前构建器
         */
        public Builder filters(Map<String, Object> filters) {
            this.filters = filters;
            return this;
        }

        /**
         * 确定检索器如何对文档存储应用过滤条件的策略。
         *
         * @param filterPolicy FilterPolicy.REPLACE 或 FilterPolicy.MERGE
         * @return 当前构建器
         */
        public Builder filterPolicy(FilterPolicy filterPolicy) {
            this.filterPolicy = filterPolicy;
            return this;
        }

        /**
         * 设置 HTTP 超时。
         *
         * <p>默认 30 秒。
         *
         * @param timeout 超时时长
         * @return 当前构建器
         */
        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        /**
         * 设置失败请求的最大重试次数。
         *
         * <p>默认为 3。
         *
         * @param maxRetries 最大重试次数，不小于 0
         * @return 当前构建器
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
         * @return 当前构建器
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
         * @return 当前构建器
         */
        public Builder addCustomHeader(String key, String value) {
            this.customHeaders.put(key, value);
            return this;
        }

        /**
         * 构建 HayStackConfig 实例。
         *
         * <p>{@code baseUrl} 必填，不能为 {@code null} 或空白。
         *
         * @return 新的 {@link HayStackConfig} 实例
         * @throws IllegalArgumentException 必填 {@code baseUrl} 缺失或空白时抛出
         */
        public HayStackConfig build() {
            if (baseUrl == null || baseUrl.trim().isEmpty()) {
                throw new IllegalArgumentException("BaseUrl key is required");
            }

            return new HayStackConfig(this);
        }
    }
}
