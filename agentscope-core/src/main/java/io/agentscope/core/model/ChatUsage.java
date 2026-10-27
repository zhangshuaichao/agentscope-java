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
package io.agentscope.core.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 表示聊天补全响应的词元用量信息。
 *
 * <p>此不可变数据类记录聊天补全使用的词元数量，包括输入词元（提示）、
 * 输出词元（生成响应）、输入／输出词元细分以及执行耗时。
 *
 * <p>【学习提示】细分指标是总量的子集，不应再次加到总词元数上；
 * 服务商未报告某细分时的零值，也不能证明实际没有发生该类用量。
 */
public class ChatUsage {

    private final int inputTokens;
    private final int outputTokens;
    private final int cachedTokens;
    private final int cacheCreationTokens;
    private final int reasoningTokens;
    private final int toolUsePromptTokens;
    private final double time;

    /**
     * 创建不带词元细分信息的 ChatUsage 实例。
     *
     * <p>保留此构造方法以保持向后兼容。所有词元细分指标默认为 {@code 0}。
     *
     * @param inputTokens 输入／提示使用的词元数量
     * @param outputTokens 输出／生成响应使用的词元数量
     * @param time 执行时间，单位为秒
     */
    public ChatUsage(int inputTokens, int outputTokens, double time) {
        this(inputTokens, outputTokens, 0, time);
    }

    /**
     * 创建新的 ChatUsage 实例。
     *
     * @param inputTokens 输入／提示使用的词元数量
     * @param outputTokens 输出／生成响应使用的词元数量
     * @param cachedTokens 从提示缓存读取的输入词元数量（属于 {@code inputTokens} 的子集）；
     *     服务商不报告缓存信息时为 {@code 0}
     * @param time 执行时间，单位为秒
     */
    public ChatUsage(
            @JsonProperty("inputTokens") int inputTokens,
            @JsonProperty("outputTokens") int outputTokens,
            @JsonProperty("cachedTokens") int cachedTokens,
            @JsonProperty("time") double time) {
        this(inputTokens, outputTokens, cachedTokens, 0, 0, 0, time);
    }

    /**
     * 创建包含全部词元细分信息的 ChatUsage 实例。
     *
     * <p>Jackson 也使用此构造方法反序列化 {@code ChatUsage} 对象。
     * JSON 省略某细分属性时，该基本类型值默认为 {@code 0}。
     *
     * @param inputTokens 输入／提示使用的词元数量
     * @param outputTokens 输出／生成响应使用的词元数量
     * @param cachedTokens 从提示缓存读取的输入词元数量（属于 {@code inputTokens} 的子集）；
     *     服务商不报告缓存信息时为 {@code 0}
     * @param cacheCreationTokens 用于创建提示缓存条目的输入词元数量（属于 {@code inputTokens} 的子集）；
     *     服务商不报告此信息时为 {@code 0}
     * @param reasoningTokens 用于模型推理的输出词元数量（属于 {@code outputTokens} 的子集）；
     *     服务商不报告此信息时为 {@code 0}
     * @param toolUsePromptTokens 表示返回给模型的工具结果的输入词元数量（属于 {@code inputTokens} 的子集）；
     *     服务商不报告此信息时为 {@code 0}
     * @param time 执行时间，单位为秒
     */
    @JsonCreator
    public ChatUsage(
            @JsonProperty("inputTokens") int inputTokens,
            @JsonProperty("outputTokens") int outputTokens,
            @JsonProperty("cachedTokens") int cachedTokens,
            @JsonProperty("cacheCreationTokens") int cacheCreationTokens,
            @JsonProperty("reasoningTokens") int reasoningTokens,
            @JsonProperty("toolUsePromptTokens") int toolUsePromptTokens,
            @JsonProperty("time") double time) {
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.cachedTokens = cachedTokens;
        this.cacheCreationTokens = cacheCreationTokens;
        this.reasoningTokens = reasoningTokens;
        this.toolUsePromptTokens = toolUsePromptTokens;
        this.time = time;
    }

    /**
     * 获取已使用的输入词元数量。
     *
     * @return 输入／提示使用的词元数量
     */
    public int getInputTokens() {
        return inputTokens;
    }

    /**
     * 获取已使用的输出词元数量。
     *
     * @return 输出／生成响应使用的词元数量
     */
    public int getOutputTokens() {
        return outputTokens;
    }

    /**
     * 获取从提示缓存读取的输入词元数量。
     *
     * <p>缓存词元是 {@link #getInputTokens()} 的子集（即 {@code inputTokens =
     * non-cached prompt tokens + cachedTokens}），不是额外数量，通常按较低费率计费。
     * 服务商不报告缓存信息时返回 {@code 0}。
     *
     * @return 缓存输入词元数量
     */
    public int getCachedTokens() {
        return cachedTokens;
    }

    /**
     * 获取用于创建提示缓存条目的输入词元数量。
     *
     * <p>缓存创建词元是 {@link #getInputTokens()} 的子集，不是额外数量，
     * 其计费方式可能与缓存命中不同。服务商不报告此指标时返回 {@code 0}。
     *
     * @return 缓存创建输入词元数量
     */
    public int getCacheCreationTokens() {
        return cacheCreationTokens;
    }

    /**
     * 获取用于模型推理的输出词元数量。
     *
     * <p>推理词元是 {@link #getOutputTokens()} 的子集，不是额外数量。
     * 服务商不报告此指标时返回 {@code 0}。
     *
     * @return 推理输出词元数量
     */
    public int getReasoningTokens() {
        return reasoningTokens;
    }

    /**
     * 获取表示返回给模型的工具结果的输入词元数量。
     *
     * <p>工具使用提示词元是 {@link #getInputTokens()} 的子集，不是额外数量。
     * 服务商不报告此指标时返回 {@code 0}。
     *
     * @return 工具结果输入词元数量
     */
    public int getToolUsePromptTokens() {
        return toolUsePromptTokens;
    }

    /**
     * 获取使用的总词元数。
     *
     * @return 输入词元与输出词元之和
     */
    public int getTotalTokens() {
        return inputTokens + outputTokens;
    }

    /**
     * 获取执行时间。
     *
     * @return 以秒为单位的执行时间
     */
    public double getTime() {
        return time;
    }

    /**
     * 创建新的 ChatUsage 建造者。
     *
     * @return 新的 Builder 实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /** 创建 ChatUsage 实例的建造者。 */
    public static class Builder {
        private int inputTokens;
        private int outputTokens;
        private int cachedTokens;
        private int cacheCreationTokens;
        private int reasoningTokens;
        private int toolUsePromptTokens;
        private double time;

        /**
         * 设置输入词元数量。
         *
         * @param inputTokens 输入／提示使用的词元数量
         * @return 当前建造者实例
         */
        public Builder inputTokens(int inputTokens) {
            this.inputTokens = inputTokens;
            return this;
        }

        /**
         * 设置输出词元数量。
         *
         * @param outputTokens 输出／生成响应使用的词元数量
         * @return 当前建造者实例
         */
        public Builder outputTokens(int outputTokens) {
            this.outputTokens = outputTokens;
            return this;
        }

        /**
         * 设置缓存输入词元数量。
         *
         * @param cachedTokens 从提示缓存读取的输入词元数量（属于 {@code inputTokens} 的子集）
         * @return 当前建造者实例
         */
        public Builder cachedTokens(int cachedTokens) {
            this.cachedTokens = cachedTokens;
            return this;
        }

        /**
         * 设置用于创建提示缓存条目的输入词元数量。
         *
         * @param cacheCreationTokens 缓存创建输入词元数量（属于 {@code inputTokens} 的子集）
         * @return 当前建造者实例
         */
        public Builder cacheCreationTokens(int cacheCreationTokens) {
            this.cacheCreationTokens = cacheCreationTokens;
            return this;
        }

        /**
         * 设置用于模型推理的输出词元数量。
         *
         * @param reasoningTokens 用于推理的输出词元数量（属于 {@code outputTokens} 的子集）
         * @return 当前建造者实例
         */
        public Builder reasoningTokens(int reasoningTokens) {
            this.reasoningTokens = reasoningTokens;
            return this;
        }

        /**
         * 设置表示返回给模型的工具结果的输入词元数量。
         *
         * @param toolUsePromptTokens 工具结果输入词元数量（属于 {@code inputTokens} 的子集）
         * @return 当前建造者实例
         */
        public Builder toolUsePromptTokens(int toolUsePromptTokens) {
            this.toolUsePromptTokens = toolUsePromptTokens;
            return this;
        }

        /**
         * 设置执行时间。
         *
         * @param time 执行时间，单位为秒
         * @return 当前建造者实例
         */
        public Builder time(double time) {
            this.time = time;
            return this;
        }

        /**
         * 使用已设置的值构建新的 ChatUsage 实例。
         *
         * @return 新的 ChatUsage 实例
         */
        public ChatUsage build() {
            return new ChatUsage(
                    inputTokens,
                    outputTokens,
                    cachedTokens,
                    cacheCreationTokens,
                    reasoningTokens,
                    toolUsePromptTokens,
                    time);
        }
    }
}
