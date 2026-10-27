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
package io.agentscope.core.rag.integration.bailian;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 阿里云百炼知识库配置。
 *
 * <p>包含连接和使用阿里云百炼知识库服务所需的全部配置参数，
 * 包括连接设置与检索参数。
 *
 * <p>使用示例：
 * <pre>{@code
 * BailianConfig config = BailianConfig.builder()
 *     .accessKeyId(System.getenv("ALIBABA_CLOUD_ACCESS_KEY_ID"))
 *     .accessKeySecret(System.getenv("ALIBABA_CLOUD_ACCESS_KEY_SECRET"))
 *     .workspaceId("llm-xxx")
 *     .indexId("mymxbdxxxx")
 *     // 可选：配置检索参数
 *     .denseSimilarityTopK(50)
 *     .sparseSimilarityTopK(50)
 *     .enableReranking(true)
 *     .rerankConfig(RerankConfig.builder()
 *         .modelName("gte-rerank-hybrid")
 *         .build())
 *     .enableRewrite(true)
 *     .rewriteConfig(RewriteConfig.builder()
 *         .modelName("conv-rewrite-qwen-1.8b")
 *         .build())
 *     .build();
 * }</pre>
 */
public class BailianConfig {

    private static final String DEFAULT_ENDPOINT = "bailian.cn-beijing.aliyuncs.com";

    // 连接配置
    private final String accessKeyId;
    private final String accessKeySecret;
    private final String workspaceId;
    private final String indexId;
    private final String endpoint;

    // 检索配置（知识库默认值）
    private final Integer denseSimilarityTopK;
    private final Integer sparseSimilarityTopK;
    private final Boolean enableReranking;
    private final RerankConfig rerankConfig;
    private final Boolean enableRewrite;
    private final RewriteConfig rewriteConfig;
    private final List<Map<String, String>> searchFilters;
    private final Boolean saveRetrieverHistory;

    private BailianConfig(Builder builder) {
        if (builder.accessKeyId == null || builder.accessKeyId.trim().isEmpty()) {
            throw new IllegalArgumentException("AccessKeyId cannot be null or empty");
        }
        if (builder.accessKeySecret == null || builder.accessKeySecret.trim().isEmpty()) {
            throw new IllegalArgumentException("AccessKeySecret cannot be null or empty");
        }
        if (builder.workspaceId == null || builder.workspaceId.trim().isEmpty()) {
            throw new IllegalArgumentException("WorkspaceId cannot be null or empty");
        }

        // 连接配置
        this.accessKeyId = builder.accessKeyId;
        this.accessKeySecret = builder.accessKeySecret;
        this.workspaceId = builder.workspaceId;
        this.indexId = builder.indexId;
        this.endpoint = builder.endpoint != null ? builder.endpoint : DEFAULT_ENDPOINT;

        // 检索配置
        this.denseSimilarityTopK = builder.denseSimilarityTopK;
        this.sparseSimilarityTopK = builder.sparseSimilarityTopK;
        this.enableReranking = builder.enableReranking;
        this.rerankConfig = builder.rerankConfig;
        this.enableRewrite = builder.enableRewrite;
        this.rewriteConfig = builder.rewriteConfig;
        this.searchFilters =
                builder.searchFilters != null ? new ArrayList<>(builder.searchFilters) : null;
        this.saveRetrieverHistory = builder.saveRetrieverHistory;

        // 验证 denseSimilarityTopK + sparseSimilarityTopK <= 200
        if (denseSimilarityTopK != null && sparseSimilarityTopK != null) {
            if (denseSimilarityTopK + sparseSimilarityTopK > 200) {
                throw new IllegalArgumentException(
                        "denseSimilarityTopK + sparseSimilarityTopK must be <= 200");
            }
        }
    }

    /**
     * 获取阿里云 Access Key ID。
     *
     * @return Access Key ID
     */
    public String getAccessKeyId() {
        return accessKeyId;
    }

    /**
     * 获取阿里云 Access Key Secret。
     *
     * @return Access Key Secret
     */
    public String getAccessKeySecret() {
        return accessKeySecret;
    }

    /**
     * 获取百炼工作空间 ID。
     *
     * @return 工作空间 ID
     */
    public String getWorkspaceId() {
        return workspaceId;
    }

    /**
     * 获取知识库索引 ID。
     *
     * @return 索引 ID；未设置时为 null
     */
    public String getIndexId() {
        return indexId;
    }

    /**
     * 获取百炼 API 端点。
     *
     * @return API 端点
     */
    public String getEndpoint() {
        return endpoint;
    }

    /**
     * 获取稠密相似度 top K 值。
     *
     * @return 稠密相似度 top K（0–100）；未设置时为 null
     */
    public Integer getDenseSimilarityTopK() {
        return denseSimilarityTopK;
    }

    /**
     * 获取稀疏相似度 top K 值。
     *
     * @return 稀疏相似度 top K（0–100）；未设置时为 null
     */
    public Integer getSparseSimilarityTopK() {
        return sparseSimilarityTopK;
    }

    /**
     * 检查是否启用重排序。
     *
     * @return 启用时为 true，未配置时为 null
     */
    public Boolean getEnableReranking() {
        return enableReranking;
    }

    /**
     * 获取重排序配置。
     *
     * @return 重排序配置；未设置时为 null
     */
    public RerankConfig getRerankConfig() {
        return rerankConfig;
    }

    /**
     * 检查是否启用查询改写。
     *
     * @return 启用时为 true，未配置时为 null
     */
    public Boolean getEnableRewrite() {
        return enableRewrite;
    }

    /**
     * 获取改写配置。
     *
     * @return 改写配置；未设置时为 null
     */
    public RewriteConfig getRewriteConfig() {
        return rewriteConfig;
    }

    /**
     * 获取搜索过滤条件。
     *
     * @return 搜索过滤条件列表；未设置时为 null
     */
    public List<Map<String, String>> getSearchFilters() {
        return searchFilters;
    }

    /**
     * 检查是否应保存检索历史。
     *
     * @return 应保存时为 true，未配置时为 null
     */
    public Boolean getSaveRetrieverHistory() {
        return saveRetrieverHistory;
    }

    /**
     * 为 BailianConfig 创建新构建器。
     *
     * @return 新的 Builder 实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * BailianConfig 的构建器。
     */
    public static class Builder {
        // 连接配置
        private String accessKeyId;
        private String accessKeySecret;
        private String workspaceId;
        private String indexId;
        private String endpoint;

        // 检索配置
        private Integer denseSimilarityTopK;
        private Integer sparseSimilarityTopK;
        private Boolean enableReranking;
        private RerankConfig rerankConfig;
        private Boolean enableRewrite;
        private RewriteConfig rewriteConfig;
        private List<Map<String, String>> searchFilters;
        private Boolean saveRetrieverHistory;

        private Builder() {}

        /**
         * 设置阿里云 Access Key ID。
         *
         * @param accessKeyId Access Key ID
         * @return 此构建器，支持链式调用
         */
        public Builder accessKeyId(String accessKeyId) {
            this.accessKeyId = accessKeyId;
            return this;
        }

        /**
         * 设置阿里云 Access Key Secret。
         *
         * @param accessKeySecret Access Key Secret
         * @return 此构建器，支持链式调用
         */
        public Builder accessKeySecret(String accessKeySecret) {
            this.accessKeySecret = accessKeySecret;
            return this;
        }

        /**
         * 设置百炼工作空间 ID。
         *
         * @param workspaceId 工作空间 ID
         * @return 此构建器，支持链式调用
         */
        public Builder workspaceId(String workspaceId) {
            this.workspaceId = workspaceId;
            return this;
        }

        /**
         * 设置知识库索引 ID。
         *
         * <p>在配置中可选，但调用 retrieve() 时必需。
         *
         * @param indexId 索引 ID
         * @return 此构建器，支持链式调用
         */
        public Builder indexId(String indexId) {
            this.indexId = indexId;
            return this;
        }

        /**
         * 设置百炼 API 端点。
         *
         * <p>未设置时默认为 "bailian.cn-beijing.aliyuncs.com"。
         *
         * <p>可用端点：
         * <ul>
         *   <li>公共云：bailian.cn-beijing.aliyuncs.com
         *   <li>金融云：bailian.cn-shanghai-finance-1.aliyuncs.com
         *   <li>VPC（北京）：bailian-vpc.cn-beijing.aliyuncs.com
         *   <li>VPC（上海金融）：bailian-vpc.cn-shanghai-finance-1.aliyuncs.com
         * </ul>
         *
         * @param endpoint API 端点
         * @return 此构建器，支持链式调用
         */
        public Builder endpoint(String endpoint) {
            this.endpoint = endpoint;
            return this;
        }

        /**
         * 设置稠密相似度 top K。
         *
         * <p>向量检索 top K，范围 [0–100]，默认 100。
         * 注意：denseSimilarityTopK + sparseSimilarityTopK 必须小于 200。
         *
         * @param denseSimilarityTopK top K 值
         * @return 此构建器
         */
        public Builder denseSimilarityTopK(Integer denseSimilarityTopK) {
            if (denseSimilarityTopK != null
                    && (denseSimilarityTopK < 0 || denseSimilarityTopK > 100)) {
                throw new IllegalArgumentException("denseSimilarityTopK must be between 0 and 100");
            }
            this.denseSimilarityTopK = denseSimilarityTopK;
            return this;
        }

        /**
         * 设置稀疏相似度 top K。
         *
         * <p>关键词检索 top K，范围 [0–100]，默认 100。
         * 注意：denseSimilarityTopK + sparseSimilarityTopK 必须小于 200。
         *
         * @param sparseSimilarityTopK top K 值
         * @return 此构建器
         */
        public Builder sparseSimilarityTopK(Integer sparseSimilarityTopK) {
            if (sparseSimilarityTopK != null
                    && (sparseSimilarityTopK < 0 || sparseSimilarityTopK > 100)) {
                throw new IllegalArgumentException(
                        "sparseSimilarityTopK must be between 0 and 100");
            }
            this.sparseSimilarityTopK = sparseSimilarityTopK;
            return this;
        }

        /**
         * 设置是否启用重排序。
         *
         * <p>默认 true（启用）
         *
         * @param enableReranking true 表示启用重排序
         * @return 此构建器
         */
        public Builder enableReranking(Boolean enableReranking) {
            this.enableReranking = enableReranking;
            return this;
        }

        /**
         * 设置重排序配置。
         *
         * @param rerankConfig 重排序配置
         * @return 此构建器
         */
        public Builder rerankConfig(RerankConfig rerankConfig) {
            this.rerankConfig = rerankConfig;
            return this;
        }

        /**
         * 设置是否启用多轮对话改写。
         *
         * <p>默认 false（禁用）
         *
         * @param enableRewrite true 表示启用改写
         * @return 此构建器
         */
        public Builder enableRewrite(Boolean enableRewrite) {
            this.enableRewrite = enableRewrite;
            return this;
        }

        /**
         * 设置改写配置。
         *
         * @param rewriteConfig 改写配置
         * @return 此构建器
         */
        public Builder rewriteConfig(RewriteConfig rewriteConfig) {
            this.rewriteConfig = rewriteConfig;
            return this;
        }

        /**
         * 设置用于个性化检索的搜索过滤条件。
         *
         * <p>可根据标签及其他元数据排除无关信息。
         *
         * @param searchFilters 搜索过滤条件
         * @return 此构建器
         */
        public Builder searchFilters(List<Map<String, String>> searchFilters) {
            this.searchFilters = searchFilters != null ? new ArrayList<>(searchFilters) : null;
            return this;
        }

        /**
         * 设置是否保存检索历史。
         *
         * <p>默认 false
         *
         * @param saveRetrieverHistory true 表示保存历史
         * @return 此构建器
         */
        public Builder saveRetrieverHistory(Boolean saveRetrieverHistory) {
            this.saveRetrieverHistory = saveRetrieverHistory;
            return this;
        }

        /**
         * 构建新的 BailianConfig 实例。
         *
         * @return 新的 BailianConfig 实例
         * @throws IllegalArgumentException 缺少必填参数时抛出
         */
        public BailianConfig build() {
            return new BailianConfig(this);
        }
    }
}
