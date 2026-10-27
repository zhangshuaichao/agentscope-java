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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 表示嵌入响应的 token 使用统计。
 *
 * <p>此不可变数据类记录嵌入请求使用的 token 数。不同于聊天补全，嵌入请求仅消费输入 token，没有生成文本输出。
 */
public class EmbeddingUsage {

    private final int inputTokens;
    private final int totalTokens;
    private final double time;

    /**
     * 创建 EmbeddingUsage 实例。
     *
     * @param inputTokens 输入 token 数
     * @param totalTokens 提供者报告的总 token 数
     * @param time 客户端测量的端到端耗时（秒）
     */
    @JsonCreator
    public EmbeddingUsage(
            @JsonProperty("inputTokens") int inputTokens,
            @JsonProperty("totalTokens") int totalTokens,
            @JsonProperty("time") double time) {
        this.inputTokens = inputTokens;
        this.totalTokens = totalTokens;
        this.time = time;
    }

    /**
     * 获取输入 token 数。
     *
     * @return 输入 token 数
     */
    public int getInputTokens() {
        return inputTokens;
    }

    /**
     * 获取提供者报告的总 token 数。
     *
     * <p>嵌入请求通常与 {@link #getInputTokens()} 相等，因为只消费输入 token。不区分两者的提供者会在两个字段中报告相同值。
     *
     * @return 总 token 数
     */
    public int getTotalTokens() {
        return totalTokens;
    }

    /**
     * 获取客户端测量的端到端执行时间。
     *
     * <p>从发起嵌入调用到完成响应处理（包括向量解析）的耗时，不是提供者报告的服务端处理时间。
     *
     * @return 执行时间（秒）
     */
    public double getTime() {
        return time;
    }

    /**
     * 创建 EmbeddingUsage 构建器。
     *
     * @return 新的 Builder 实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 创建 EmbeddingUsage 的构建器。
     */
    public static class Builder {
        private int inputTokens;
        private int totalTokens;
        private double time;

        /**
         * 设置输入 token 数。
         *
         * @param inputTokens 输入 token 数
         * @return 当前构建器实例
         */
        public Builder inputTokens(int inputTokens) {
            this.inputTokens = inputTokens;
            return this;
        }

        /**
         * 设置总 token 数。
         *
         * @param totalTokens 总 token 数
         * @return 当前构建器实例
         */
        public Builder totalTokens(int totalTokens) {
            this.totalTokens = totalTokens;
            return this;
        }

        /**
         * 设置客户端测量的端到端执行时间。
         *
         * @param time 客户端测量的端到端耗时（秒）
         * @return 当前构建器实例
         */
        public Builder time(double time) {
            this.time = time;
            return this;
        }

        /**
         * 使用已设置值构建 EmbeddingUsage。
         *
         * @return 新的 EmbeddingUsage 实例
         */
        public EmbeddingUsage build() {
            return new EmbeddingUsage(inputTokens, totalTokens, time);
        }
    }
}
