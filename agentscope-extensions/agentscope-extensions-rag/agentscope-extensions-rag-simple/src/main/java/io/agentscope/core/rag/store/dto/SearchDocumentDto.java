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
package io.agentscope.core.rag.store.dto;

import org.jspecify.annotations.Nullable;

/**
 * 封装向量搜索参数的 DTO，包含向量名称、查询向量、结果数量上限与相似度阈值。
 */
public class SearchDocumentDto {

    /**
     * 向量名称，指定要搜索的向量空间。
     */
    @Nullable private String vectorName;

    /**
     * 查询嵌入向量，用于搜索空间中的相似向量。
     */
    private double[] queryEmbedding;

    /**
     * 返回结果数量上限。
     */
    private int limit;

    /**
     * 用于过滤搜索结果的相似度阈值，仅返回满足阈值的结果。
     */
    @Nullable private Double scoreThreshold;

    /**
     * 供构建器使用的私有构造方法。
     *
     * @param vectorName 向量名称
     * @param queryEmbedding 查询向量
     * @param limit 结果数量上限
     * @param scoreThreshold 相似度分数阈值
     */
    private SearchDocumentDto(
            String vectorName, double[] queryEmbedding, int limit, Double scoreThreshold) {
        this.vectorName = vectorName;
        this.queryEmbedding = queryEmbedding;
        this.limit = limit;
        this.scoreThreshold = scoreThreshold;
    }

    /**
     * 获取向量名称。
     *
     * @return 向量名称
     */
    public String getVectorName() {
        return vectorName;
    }

    /**
     * 设置向量名称。
     *
     * @param vectorName 向量名称
     */
    public void setVectorName(String vectorName) {
        this.vectorName = vectorName;
    }

    /**
     * 获取查询向量。
     *
     * @return 查询向量
     */
    public double[] getQueryEmbedding() {
        return queryEmbedding;
    }

    /**
     * 设置查询向量。
     *
     * @param queryEmbedding 查询向量
     */
    public void setQueryEmbedding(double[] queryEmbedding) {
        this.queryEmbedding = queryEmbedding;
    }

    /**
     * 获取结果数量上限。
     *
     * @return 结果数量上限
     */
    public int getLimit() {
        return limit;
    }

    /**
     * 设置结果数量上限。
     *
     * @param limit 结果数量上限
     */
    public void setLimit(int limit) {
        this.limit = limit;
    }

    /**
     * 获取相似度分数阈值。
     *
     * @return 相似度分数阈值
     */
    public Double getScoreThreshold() {
        return scoreThreshold;
    }

    /**
     * 设置相似度分数阈值。
     *
     * @param scoreThreshold 相似度分数阈值
     */
    public void setScoreThreshold(Double scoreThreshold) {
        this.scoreThreshold = scoreThreshold;
    }

    @Override
    public String toString() {
        return String.format(
                "SearchDocumentDto(vectorName=%s, limit=%s, scoreThreshold=%s)",
                this.vectorName != null ? this.vectorName : "null",
                this.limit,
                this.scoreThreshold != null ? String.format("%.3f", this.scoreThreshold) : "null");
    }

    /**
     * 创建 SearchDocumentDto 构建器。
     *
     * @return 构建器实例
     */
    public static SearchDocumentDtoBuilder builder() {
        return new SearchDocumentDtoBuilder();
    }

    /**
     * SearchDocument DTO 的构建器。
     */
    public static class SearchDocumentDtoBuilder {
        private String vectorName;
        private double[] queryEmbedding;
        private int limit;
        private Double scoreThreshold;

        private SearchDocumentDtoBuilder() {}

        /**
         * 设置向量名称。
         *
         * @param vectorName 向量名称
         * @return 构建器实例
         */
        public SearchDocumentDtoBuilder vectorName(String vectorName) {
            this.vectorName = vectorName;
            return this;
        }

        /**
         * 设置查询向量。
         *
         * @param queryEmbedding 查询向量
         * @return 构建器实例
         */
        public SearchDocumentDtoBuilder queryEmbedding(double[] queryEmbedding) {
            this.queryEmbedding = queryEmbedding;
            return this;
        }

        /**
         * 设置结果数量上限。
         *
         * @param limit 结果数量上限
         * @return 构建器实例
         */
        public SearchDocumentDtoBuilder limit(int limit) {
            this.limit = limit;
            return this;
        }

        /**
         * 设置相似度分数阈值。
         *
         * @param scoreThreshold 相似度分数阈值
         * @return 构建器实例
         */
        public SearchDocumentDtoBuilder scoreThreshold(Double scoreThreshold) {
            this.scoreThreshold = scoreThreshold;
            return this;
        }

        /**
         * 构建 SearchDocumentDto 实例。
         *
         * @return 搜索文档 DTO 实例
         */
        public SearchDocumentDto build() {
            return new SearchDocumentDto(
                    this.vectorName, this.queryEmbedding, this.limit, this.scoreThreshold);
        }
    }
}
