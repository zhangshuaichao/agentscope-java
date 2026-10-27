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

import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.training.backend.TrinityClient;
import io.agentscope.core.training.backend.dto.CommitRequest;
import io.agentscope.core.training.reward.RewardCalculator;
import io.agentscope.core.training.strategy.TrainingSelectionStrategy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * 训练过程控制器，负责安装请求筛选 Hook、将影子执行的模型路由到 Trinity、
 * 收集轨迹与奖励反馈，并周期提交训练请求。
 * <p>【学习提示】start/stop 管理的是全局 Hook 与提交调度器，不会把原生产调用变成沙箱。
 * 影子执行是否可安全重放取决于工具副作用与外部服务配置，开始采样前应明确数据与执行权限。
 * <p>用法：
 * <pre>{@code
 * // 方法 1：使用采样率策略
 * TrainingRunner runner = TrainingRunner.builder()
 *     .trinityEndpoint("http://localhost:8080")
 *     .modelName("/path/to/model")
 *     .selectionStrategy(SamplingRateStrategy.of(0.1))  // 10% 采样率
 *     .rewardCalculator(agent -> 0.0)  // 实现自定义奖励逻辑
 *     .commitIntervalSeconds(300)  // 每 5 分钟提交
 *     .build();
 *
 * // 方法 2：使用显式标记策略
 * TrainingRunner runner = TrainingRunner.builder()
 *     .trinityEndpoint("http://localhost:8080")
 *     .modelName("/path/to/model")
 *     .selectionStrategy(ExplicitMarkingStrategy.create())  // 用户手动标记
 *     .rewardCalculator(agent -> {
 *         // 自定义奖励计算逻辑
 *         return 1.0;
 *     })
 *     .build();
 *
 * // 方法 3：使用自定义 RewardCalculator 类
 * TrainingRunner runner = TrainingRunner.builder()
 *     .trinityEndpoint("http://localhost:8080")
 *     .modelName("/path/to/model")
 *     .selectionStrategy(SamplingRateStrategy.of(0.1))
 *     .rewardCalculator(new CustomRewardCalculator())  // 实现 RewardCalculator 接口
 *     .build();
 *
 * // 启动训练运行器
 * runner.start();
 *
 * // 用户继续正常调用智能体
 * agent.call(msg).block();
 *
 * // 停止训练
 * runner.stop();
 * }</pre>
 */
public class TrainingRunner {
    private static final Logger logger = LoggerFactory.getLogger(TrainingRunner.class);

    private final TrainingConfig config;
    private final TrinityClient trinityClient;
    private final TrainingRouter router;

    private ScheduledExecutorService commitScheduler;
    private volatile boolean running = false;

    private TrainingRunner(Builder builder) {
        this.config = builder.config;

        // 初始化 Trinity 客户端。
        this.trinityClient =
                TrinityClient.builder()
                        .endpoint(config.getTrinityEndpoint())
                        .timeout(config.getHttpTimeout())
                        .build();

        // 初始化路由器。
        this.router =
                new TrainingRouter(
                        config,
                        trinityClient,
                        config.getRewardCalculator(),
                        config.getSelectionStrategy());
    }

    /**
     * 启动训练运行器。
     */
    public synchronized void start() {
        if (running) {
            logger.warn("TrainingRunner already running");
            return;
        }

        logger.info(
                "Starting TrainingRunner: strategy={}, model={}",
                config.getSelectionStrategy().getClass().getSimpleName(),
                config.getModelName());

        // 将 TrainingRouter 注册为系统 Hook。
        AgentBase.addSystemHook(router);

        // 启动周期提交调度器。
        startCommitScheduler();

        running = true;
        logger.info("TrainingRunner started successfully");
    }

    /**
     * 停止训练运行器。
     */
    public synchronized void stop() {
        if (!running) {
            logger.warn("TrainingRunner not running");
            return;
        }

        logger.info("Stopping TrainingRunner...");

        // 停止提交调度器。
        if (commitScheduler != null) {
            commitScheduler.shutdown();
            try {
                if (!commitScheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                    commitScheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                commitScheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        // 执行最终提交。
        commit().block();

        // 移除 Hook。
        AgentBase.removeSystemHook(router);
        logger.debug("Removed training router hook from AgentBase");

        running = false;
        logger.info("TrainingRunner stopped");
    }

    /**
     * 手工触发提交。
     */
    public Mono<Void> commit() {
        return trinityClient
                .commit(CommitRequest.builder().build())
                .doOnSuccess(v -> logger.info("Training commit successful"))
                .doOnError(e -> logger.error("Failed to commit training", e))
                .then();
    }

    /**
     * 启动周期提交调度器。
     */
    private void startCommitScheduler() {
        if (config.getCommitIntervalSeconds() <= 0) {
            logger.info("Commit scheduler disabled (interval <= 0)");
            return;
        }

        commitScheduler =
                Executors.newSingleThreadScheduledExecutor(
                        r -> {
                            Thread thread = new Thread(r, "training-commit-scheduler");
                            thread.setDaemon(true);
                            return thread;
                        });

        commitScheduler.scheduleAtFixedRate(
                () -> {
                    try {
                        commit().block();
                    } catch (Exception e) {
                        logger.error("Error in scheduled commit", e);
                    }
                },
                config.getCommitIntervalSeconds(),
                config.getCommitIntervalSeconds(),
                TimeUnit.SECONDS);

        logger.info(
                "Commit scheduler started with interval: {} seconds",
                config.getCommitIntervalSeconds());
    }

    public boolean isRunning() {
        return running;
    }

    public TrainingConfig getConfig() {
        return config;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * TrainingRunner 构建器。
     */
    public static class Builder {
        private TrainingConfig config;
        private TrainingConfig.Builder configBuilder;

        public Builder config(TrainingConfig config) {
            this.config = config;
            return this;
        }

        public Builder trinityEndpoint(String endpoint) {
            getConfigBuilder().trinityEndpoint(endpoint);
            return this;
        }

        public Builder modelName(String modelName) {
            getConfigBuilder().modelName(modelName);
            return this;
        }

        public Builder selectionStrategy(TrainingSelectionStrategy strategy) {
            getConfigBuilder().selectionStrategy(strategy);
            return this;
        }

        public Builder rewardCalculator(RewardCalculator calculator) {
            getConfigBuilder().rewardCalculator(calculator);
            return this;
        }

        public Builder commitIntervalSeconds(long seconds) {
            getConfigBuilder().commitIntervalSeconds(seconds);
            return this;
        }

        public Builder repeatTime(int repeatTime) {
            getConfigBuilder().repeatTime(repeatTime);
            return this;
        }

        private TrainingConfig.Builder getConfigBuilder() {
            if (config != null) {
                throw new IllegalStateException(
                        "Cannot use builder methods when config is already set");
            }
            if (configBuilder == null) {
                configBuilder = TrainingConfig.builder();
            }
            return configBuilder;
        }

        public TrainingRunner build() {
            if (config == null) {
                if (configBuilder == null) {
                    throw new IllegalStateException(
                            "TrainingConfig must be set. Use config() or builder methods.");
                }
                config = configBuilder.build();
            }
            return new TrainingRunner(this);
        }
    }
}
