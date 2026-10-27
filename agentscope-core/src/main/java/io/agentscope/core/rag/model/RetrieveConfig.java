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
package io.agentscope.core.rag.model;

import io.agentscope.core.message.Msg;
import java.util.List;

/**
 * 文档检索操作的配置。
 *
 * <p>此类使用构建器模式配置检索参数，例如最大结果数量、最低相似度分数阈值，
 * 以及用于结合上下文检索的可选对话历史。
 *
 * @deprecated 自 2.0.0 起弃用。rag 包已移除；请在应用层集成检索。
 */
@Deprecated(forRemoval = true, since = "2.0.0")
public class RetrieveConfig {

    private final int limit;
    private final double scoreThreshold;
    private final String vectorName;
    private final List<Msg> conversationHistory;

    private RetrieveConfig(Builder builder) {
        this.limit = builder.limit;
        this.scoreThreshold = builder.scoreThreshold;
        this.vectorName = builder.vectorName;
        this.conversationHistory = builder.conversationHistory;
    }

    /**
     * 获取要检索的最大文档数量。
     *
     * @return 数量上限
     */
    public int getLimit() {
        return limit;
    }

    /**
     * 获取最低相似度分数阈值。
     *
     * @return 分数阈值
     */
    public double getScoreThreshold() {
        return scoreThreshold;
    }

    /**
     * 获取用于检索的向量名称。
     *
     * @return 向量名称；未设置时为 null
     */
    public String getVectorName() {
        return vectorName;
    }

    /**
     * 获取用于结合上下文检索的对话历史。
     *
     * <p>这是可选字段，供支持多轮对话上下文的知识库使用
     * （例如支持查询改写的百炼知识库）。
     *
     * @return 对话历史；未设置时为 null
     */
    public List<Msg> getConversationHistory() {
        return conversationHistory;
    }

    /**
     * 基于当前实例创建可修改的新构建器。
     *
     * @return 与当前实例具有相同值的新构建器
     */
    public Builder mutate() {
        return new Builder()
                .limit(this.limit)
                .scoreThreshold(this.scoreThreshold)
                .vectorName(this.vectorName)
                .conversationHistory(this.conversationHistory);
    }

    /**
     * 创建新的构建器实例。
     *
     * @return 新构建器
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * RetrieveConfig 的构建器。
     */
    public static class Builder {

        private int limit = 5;
        private double scoreThreshold = 0.5;
        private String vectorName;
        private List<Msg> conversationHistory;

        /**
         * 设置要检索的最大文档数量。
         *
         * @param limit 数量上限（必须为正数）
         * @return 当前构建器，以支持链式调用
         */
        public Builder limit(int limit) {
            if (limit <= 0) {
                throw new IllegalArgumentException("Limit must be positive");
            }
            this.limit = limit;
            return this;
        }

        /**
         * 设置最低相似度分数阈值。
         *
         * @param scoreThreshold 分数阈值（必须介于 0.0 与 1.0 之间）
         * @return 当前构建器，以支持链式调用
         */
        public Builder scoreThreshold(double scoreThreshold) {
            if (scoreThreshold < 0.0 || scoreThreshold > 1.0) {
                throw new IllegalArgumentException("Score threshold must be between 0.0 and 1.0");
            }
            this.scoreThreshold = scoreThreshold;
            return this;
        }

        /**
         * 设置用于检索的向量名称。
         *
         * @param vectorName 向量名称（可为 null）
         * @return 当前构建器，以支持链式调用
         */
        public Builder vectorName(String vectorName) {
            this.vectorName = vectorName;
            return this;
        }

        /**
         * 设置用于结合上下文检索的对话历史。
         *
         * <p>这是可选参数，供支持多轮对话上下文的知识库使用。提供此参数后，
         * 知识库可以利用历史改写查询或补充查询上下文，以获得更好的检索结果。
         *
         * @param conversationHistory 对话历史（可为 null）
         * @return 当前构建器，以支持链式调用
         */
        public Builder conversationHistory(List<Msg> conversationHistory) {
            this.conversationHistory = conversationHistory;
            return this;
        }

        /**
         * 构建 RetrieveConfig 实例。
         *
         * @return 配置完成的 RetrieveConfig
         */
        public RetrieveConfig build() {
            return new RetrieveConfig(this);
        }
    }
}
