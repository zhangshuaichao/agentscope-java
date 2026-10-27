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

/**
 * Dify 知识库检索的重排序配置。
 *
 * <p>重排序使用专门的排序模型重新给文档打分，通常在初次召回之后执行，以改善结果相关性。
 *
 * <p>用法示例：
 * <pre>{@code
 * RerankConfig rerankConfig = RerankConfig.builder()
 *     .providerName("cohere")           // Optional: reranking provider
 *     .modelName("rerank-english-v2.0") // Optional: model name
 *     .topN(5)                          // Optional: top N after reranking
 *     .build();
 *
 * DifyRAGConfig config = DifyRAGConfig.builder()
 *     .enableRerank(true)
 *     .rerankConfig(rerankConfig)
 *     .build();
 * }</pre>
 */
public class RerankConfig {

    private final String providerName;
    private final String modelName;

    private RerankConfig(Builder builder) {
        this.providerName = builder.providerName;
        this.modelName = builder.modelName;
    }

    /**
     * 获取重排序提供商名称。
     *
     * @return 提供商名称，如 "cohere"、"jina"；使用默认值时为 null
     */
    public String getProviderName() {
        return providerName;
    }

    /**
     * 获取重排序模型名称。
     *
     * @return 模型名称；使用默认值时为 null
     */
    public String getModelName() {
        return modelName;
    }

    /**
     * 创建 RerankConfig 构建器。
     *
     * @return 新的 Builder 实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /** RerankConfig 的构建器。 */
    public static class Builder {
        private String providerName;
        private String modelName;

        private Builder() {}

        /**
         * 设置重排序服务提供商名称。
         *
         * <p>用于选择重排序服务，例如：
         * <ul><li>cohere。</li><li>jina。</li><li>local：自托管模型。</li></ul>
         *
         * @param providerName 提供商名称
         * @return 当前构建器，支持链式调用
         */
        public Builder providerName(String providerName) {
            this.providerName = providerName;
            return this;
        }

        /**
         * 设置重排序模型名称。
         *
         * <p>名称应来自指定提供商，未设置时使用提供商默认重排序模型。
         *
         * <p>模型示例：
         * <ul><li>rerank-english-v2.0（Cohere）。</li><li>rerank-multilingual-v2.0（Cohere）。</li>
         * <li>jina-reranker-v1-base-en（Jina）。</li><li>bge-reranker-base（本地）。</li><li>bge-reranker-large（本地）。</li></ul>
         *
         * @param modelName 模型名称
         * @return 当前构建器，支持链式调用
         */
        public Builder modelName(String modelName) {
            this.modelName = modelName;
            return this;
        }

        /**
         * 构建 RerankConfig 实例。
         *
         * @return 新的 RerankConfig 实例
         */
        public RerankConfig build() {
            return new RerankConfig(this);
        }
    }

    @Override
    public String toString() {
        return "RerankConfig{"
                + "providerName='"
                + providerName
                + '\''
                + ", modelName='"
                + modelName
                + '}';
    }
}
