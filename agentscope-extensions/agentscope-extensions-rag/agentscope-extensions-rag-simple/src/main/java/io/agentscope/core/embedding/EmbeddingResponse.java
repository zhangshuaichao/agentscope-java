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
package io.agentscope.core.embedding;

/**
 * 表示嵌入模型的响应。
 *
 * <p>此不可变数据类包含模型处理请求后返回的嵌入向量及可选使用统计。
 */
public class EmbeddingResponse {

    private final double[] embedding;
    private final EmbeddingUsage usage;

    /**
     * 创建 EmbeddingResponse 实例。
     *
     * @param embedding 嵌入向量
     * @param usage token 使用统计；提供者未报告时可为 null
     */
    public EmbeddingResponse(double[] embedding, EmbeddingUsage usage) {
        this.embedding = embedding;
        this.usage = usage;
    }

    /**
     * 获取嵌入向量。
     *
     * @return 嵌入向量
     */
    public double[] getEmbedding() {
        return embedding;
    }

    /**
     * 获取此响应的 token 使用统计。
     *
     * @return 使用统计；提供者未报告时为 null
     */
    public EmbeddingUsage getUsage() {
        return usage;
    }

    /**
     * 创建 EmbeddingResponse 构建器。
     *
     * @return 新的 Builder 实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 创建 EmbeddingResponse 的构建器。
     */
    public static class Builder {
        private double[] embedding;
        private EmbeddingUsage usage;

        /**
         * 设置嵌入向量。
         *
         * @param embedding 嵌入向量
         * @return 当前构建器实例
         */
        public Builder embedding(double[] embedding) {
            this.embedding = embedding;
            return this;
        }

        /**
         * 设置使用统计。
         *
         * @param usage token 使用统计
         * @return 当前构建器实例
         */
        public Builder usage(EmbeddingUsage usage) {
            this.usage = usage;
            return this;
        }

        /**
         * 使用已设置值构建 EmbeddingResponse。
         *
         * @return 新的 EmbeddingResponse 实例
         */
        public EmbeddingResponse build() {
            return new EmbeddingResponse(embedding, usage);
        }
    }
}
