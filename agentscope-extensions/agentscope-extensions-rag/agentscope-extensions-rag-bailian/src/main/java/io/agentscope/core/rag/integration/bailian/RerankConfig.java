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

/**
 * 百炼知识库检索的重排序配置。
 *
 * <p>重排序使用专用排序模型重新评分，提高检索文档的相关性。
 */
public class RerankConfig {

    private final String modelName;
    private final Float rerankMinScore;
    private final Integer rerankTopN;

    private RerankConfig(Builder builder) {
        this.modelName = builder.modelName;
        this.rerankMinScore = builder.rerankMinScore;
        this.rerankTopN = builder.rerankTopN;
    }

    /**
     * 获取重排序模型名称。
     *
     * @return 模型名称
     */
    public String getModelName() {
        return modelName;
    }

    /**
     * 获取重排序的最低分数阈值。
     *
     * @return 最低分数（0.01–1.00）
     */
    public Float getRerankMinScore() {
        return rerankMinScore;
    }

    /**
     * 获取重排序后的 top N 结果数。
     *
     * @return top N 值（1–20）
     */
    public Integer getRerankTopN() {
        return rerankTopN;
    }

    /**
     * 创建新构建器。
     *
     * @return 新的 Builder 实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * RerankConfig 的构建器。
     */
    public static class Builder {
        private String modelName = "gte-rerank-hybrid";
        private Float rerankMinScore;
        private Integer rerankTopN;

        private Builder() {}

        /**
         * 设置重排序模型名称。
         *
         * <p>支持的模型：
         * <ul>
         *   <li>gte-rerank-hybrid（默认）
         *   <li>gte-rerank
         * </ul>
         *
         * @param modelName 模型名称
         * @return 此构建器
         */
        public Builder modelName(String modelName) {
            this.modelName = modelName;
            return this;
        }

        /**
         * 设置最低分数阈值。
         *
         * <p>只返回分数高于此阈值的文档。范围 [0.01–1.00]。
         *
         * @param rerankMinScore 最低分数
         * @return 此构建器
         */
        public Builder rerankMinScore(Float rerankMinScore) {
            if (rerankMinScore != null && (rerankMinScore < 0.01f || rerankMinScore > 1.00f)) {
                throw new IllegalArgumentException("rerankMinScore must be between 0.01 and 1.00");
            }
            this.rerankMinScore = rerankMinScore;
            return this;
        }

        /**
         * 设置重排序后的 top N 结果数。
         *
         * <p>范围 [1–20]，默认 5。
         *
         * @param rerankTopN top N 值
         * @return 此构建器
         */
        public Builder rerankTopN(Integer rerankTopN) {
            if (rerankTopN != null && (rerankTopN < 1 || rerankTopN > 20)) {
                throw new IllegalArgumentException("rerankTopN must be between 1 and 20");
            }
            this.rerankTopN = rerankTopN;
            return this;
        }

        /**
         * 构建新的 RerankConfig。
         *
         * @return 新的 RerankConfig 实例
         */
        public RerankConfig build() {
            return new RerankConfig(this);
        }
    }
}
