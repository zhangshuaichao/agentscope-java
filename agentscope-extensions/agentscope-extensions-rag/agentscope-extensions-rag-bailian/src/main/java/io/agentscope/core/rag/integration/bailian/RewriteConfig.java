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
 * 百炼知识库检索的多轮对话改写配置。
 *
 * <p>查询改写根据对话上下文自动调整用户查询，以提高检索效果。
 */
public class RewriteConfig {

    private final String modelName;

    private RewriteConfig(Builder builder) {
        this.modelName = builder.modelName;
    }

    /**
     * 获取改写模型名称。
     *
     * @return 模型名称
     */
    public String getModelName() {
        return modelName;
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
     * RewriteConfig 的构建器。
     */
    public static class Builder {
        private String modelName = "conv-rewrite-qwen-1.8b";

        private Builder() {}

        /**
         * 设置改写模型名称。
         *
         * <p>目前仅支持：
         * <ul>
         *   <li>conv-rewrite-qwen-1.8b（默认）
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
         * 构建新的 RewriteConfig。
         *
         * @return 新的 RewriteConfig 实例
         */
        public RewriteConfig build() {
            return new RewriteConfig(this);
        }
    }
}
