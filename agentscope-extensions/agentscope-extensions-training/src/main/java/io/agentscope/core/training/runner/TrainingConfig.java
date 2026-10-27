/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.agentscope.core.training.runner;

import io.agentscope.core.training.reward.RewardCalculator;
import io.agentscope.core.training.strategy.SamplingRateStrategy;
import io.agentscope.core.training.strategy.TrainingSelectionStrategy;
import java.time.Duration;

/**
 * 训练运行器配置，包含 Trinity 地址、训练模型、筛选策略和奖励计算器等参数。
 * <p>系统自动管理任务与运行 ID，支持灵活的训练筛选；用户也可在训练标记元数据中指定任务关联。
 */
public class TrainingConfig {
    private final String trinityEndpoint; // Trinity 服务地址，例如 http://47.252.36.19:8010。
    private final String trinityApiKey; // Trinity API key，可选；部分部署需要。
    private final String
            modelName; // 训练模型路径，例如 /home/ecs-user/models/Qwen2.5-0.5B-Instruct。
    private final TrainingSelectionStrategy
            selectionStrategy; // 训练请求筛选策略。
    private final RewardCalculator rewardCalculator;
    private final long commitIntervalSeconds;
    private final Duration httpTimeout;
    private final boolean enableAutoCommit;
    private final int shadowPoolSize;
    private final int shadowPoolCapacity;
    private final int repeatTime; // 每个任务的重复执行次数，默认 1。

    private TrainingConfig(Builder builder) {
        this.trinityEndpoint = builder.trinityEndpoint;
        this.trinityApiKey = builder.trinityApiKey;
        this.modelName = builder.modelName;
        this.selectionStrategy = builder.selectionStrategy;
        this.rewardCalculator = builder.rewardCalculator;
        this.commitIntervalSeconds = builder.commitIntervalSeconds;
        this.httpTimeout = builder.httpTimeout;
        this.enableAutoCommit = builder.enableAutoCommit;
        this.shadowPoolSize = builder.shadowPoolSize;
        this.shadowPoolCapacity = builder.shadowPoolCapacity;
        this.repeatTime = builder.repeatTime;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getTrinityEndpoint() {
        return trinityEndpoint;
    }

    public String getTrinityApiKey() {
        return trinityApiKey;
    }

    public String getModelName() {
        return modelName;
    }

    public TrainingSelectionStrategy getSelectionStrategy() {
        return selectionStrategy;
    }

    public RewardCalculator getRewardCalculator() {
        return rewardCalculator;
    }

    public long getCommitIntervalSeconds() {
        return commitIntervalSeconds;
    }

    public Duration getHttpTimeout() {
        return httpTimeout;
    }

    public boolean isEnableAutoCommit() {
        return enableAutoCommit;
    }

    public int getShadowPoolSize() {
        return shadowPoolSize;
    }

    public int getShadowPoolCapacity() {
        return shadowPoolCapacity;
    }

    public int getRepeatTime() {
        return repeatTime;
    }

    /**
     * 获取当前采样率。
     * @return 0.0～1.0 的采样率；非采样策略返回 -1
     */
    public double getSampleRate() {
        if (selectionStrategy instanceof SamplingRateStrategy) {
            return ((SamplingRateStrategy) selectionStrategy).getSampleRate();
        }
        return -1;
    }

    public static class Builder {
        private String trinityEndpoint;
        private String trinityApiKey =
                "dummy"; // 默认占位值，部分 Trinity 部署不需要真实密钥。
        private String modelName = "training-model";
        private TrainingSelectionStrategy selectionStrategy; // 可选，默认采样 10%。
        private RewardCalculator rewardCalculator;
        private long commitIntervalSeconds = 300; // 默认 5 分钟。
        private Duration httpTimeout = Duration.ofSeconds(300); // 默认超时 5 分钟。
        private boolean enableAutoCommit = true;
        private int shadowPoolSize = 10; // 影子智能体线程池默认大小。
        private int shadowPoolCapacity = 1000; // 影子智能体队列默认容量。
        private int repeatTime = 1; // 每个任务默认运行一次。

        public Builder trinityEndpoint(String endpoint) {
            this.trinityEndpoint = endpoint;
            return this;
        }

        public Builder trinityApiKey(String apiKey) {
            this.trinityApiKey = apiKey;
            return this;
        }

        public Builder modelName(String modelName) {
            this.modelName = modelName;
            return this;
        }

        /**
         * 设置训练请求筛选策略。
         * <p>支持 {@link SamplingRateStrategy} 按采样率选择、
         * {@link io.agentscope.core.training.strategy.ExplicitMarkingStrategy} 按用户显式标记选择，
         * 或自行实现 {@link TrainingSelectionStrategy}。
         * @param strategy 筛选策略
         * @return 当前构建器
         */
        public Builder selectionStrategy(TrainingSelectionStrategy strategy) {
            this.selectionStrategy = strategy;
            return this;
        }

        /**
         * 设置简单采样率策略的快捷方法。
         * @param sampleRate 采样率，范围 0.0～1.0
         * @return 当前构建器
         * @deprecated 建议使用 {@link #selectionStrategy(TrainingSelectionStrategy)}
         */
        @Deprecated
        public Builder sampleRate(double sampleRate) {
            if (sampleRate < 0 || sampleRate > 1) {
                throw new IllegalArgumentException("sampleRate must be between 0 and 1");
            }
            this.selectionStrategy = SamplingRateStrategy.of(sampleRate);
            return this;
        }

        public Builder rewardCalculator(RewardCalculator calculator) {
            this.rewardCalculator = calculator;
            return this;
        }

        public Builder commitIntervalSeconds(long seconds) {
            this.commitIntervalSeconds = seconds;
            return this;
        }

        public Builder httpTimeout(Duration timeout) {
            this.httpTimeout = timeout;
            return this;
        }

        public Builder enableAutoCommit(boolean enable) {
            this.enableAutoCommit = enable;
            return this;
        }

        public Builder shadowPoolSize(int size) {
            this.shadowPoolSize = size;
            return this;
        }

        public Builder shadowPoolCapacity(int capacity) {
            this.shadowPoolCapacity = capacity;
            return this;
        }

        /**
         * 设置各任务重复执行次数。
         * <p>请求被选中后，以相同 taskId 运行 repeatTime 次，每次分配递增 runId（0、1、2……）。
         * 可用于对比实验、同任务多样本生成及稳定性评估；不同模型／参数的配置仍由调用方负责。
         * @param repeatTime 重复次数，至少为 1，默认 1
         * @return 当前构建器
         * @throws IllegalArgumentException repeatTime &lt; 1 时抛出
         */
        public Builder repeatTime(int repeatTime) {
            if (repeatTime < 1) {
                throw new IllegalArgumentException("repeatTime must be >= 1");
            }
            this.repeatTime = repeatTime;
            return this;
        }

        public TrainingConfig build() {
            if (trinityEndpoint == null || trinityEndpoint.isEmpty()) {
                throw new IllegalArgumentException("Trinity endpoint must be specified");
            }
            if (rewardCalculator == null) {
                throw new IllegalArgumentException("RewardCalculator must be specified");
            }

            // 未配置策略时使用默认 10% 采样。
            if (selectionStrategy == null) {
                selectionStrategy = SamplingRateStrategy.of(0.1);
            }

            return new TrainingConfig(this);
        }
    }
}
