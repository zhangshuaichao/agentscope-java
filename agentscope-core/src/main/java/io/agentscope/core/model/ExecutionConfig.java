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

package io.agentscope.core.model;

import io.agentscope.core.model.transport.HttpTransportException;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

/**
 * 统一的超时与重试执行配置。
 *
 * <p>此类替代旧的 TimeoutConfig 和 RetryConfig，以单一配置统一控制
 * 模型 API 调用和工具执行的行为。
 *
 * <p>使用建造者模式创建实例。所有字段均为可选项，允许为 null。
 *
 * <h2>标准默认值</h2>
 * <ul>
 *   <li>{@link #MODEL_DEFAULTS}：5 分钟超时，最多尝试 3 次，使用指数退避
 *   <li>{@link #TOOL_DEFAULTS}：5 分钟超时，不重试（仅尝试 1 次）
 * </ul>
 *
 * <h2>配置合并</h2>
 *
 * <p>通过 {@link #mergeConfigs(ExecutionConfig, ExecutionConfig)} 逐参数按优先级合并配置，
 * 从而叠加来自不同来源的配置：
 *
 * <pre>{@code
 * // 优先级：请求级 > 智能体级 > 组件默认值 > 系统默认值
 * ExecutionConfig effective = ExecutionConfig.mergeConfigs(
 *     perRequestConfig,
 *     ExecutionConfig.mergeConfigs(agentConfig, ExecutionConfig.MODEL_DEFAULTS)
 * );
 * }</pre>
 *
 * <p>【学习提示】尝试次数包含首次执行：最多尝试 3 次就是首次加 2 次重试。
 * 工具默认不重试，有助于避免重复执行带副作用的操作；自定义重试前仍需评估幂等性。
 */
public class ExecutionConfig {
    /** 单次执行（模型请求或工具调用）的超时时长。 */
    private final Duration timeout;

    /** 最大尝试次数（包含首次尝试）。 */
    private final Integer maxAttempts;

    /** 第一次重试前的初始退避时长。 */
    private final Duration initialBackoff;

    /** 两次重试之间的最大退避时长。 */
    private final Duration maxBackoff;

    /** 每次重试后应用于退避时长的倍数。 */
    private final Double backoffMultiplier;

    /** 判断错误是否应触发重试的谓词。 */
    private final Predicate<Throwable> retryOn;

    /**
     * 判断错误是否应重试的谓词。
     *
     * <p>可重试错误包括：
     * <ul>
     *   <li>HTTP 429（限流）</li>
     *   <li>HTTP 5xx（服务端错误）</li>
     *   <li>超时错误</li>
     *   <li>网络／IO 错误</li>
     * </ul>
     *
     * <p>不可重试错误包括：
     * <ul>
     *   <li>HTTP 400（错误请求）—— 参数验证失败</li>
     *   <li>HTTP 401/403（认证／授权错误）</li>
     *   <li>其他 4xx 客户端错误</li>
     * </ul>
     */
    public static final Predicate<Throwable> RETRYABLE_ERRORS = ExecutionConfig::isRetryableError;

    /**
     * 检查指定错误是否可以重试。
     *
     * @param error 要检查的错误
     * @return 应重试该错误时返回 true
     */
    private static boolean isRetryableError(Throwable error) {
        // 对于 HttpTransportException，使用内置的 isRetryable() 方法，
        // 该方法在 429（限流）和 5xx（服务端错误）时返回 true
        if (error instanceof HttpTransportException hte) {
            return hte.isRetryable();
        }

        // 没有状态码的实现（例如包装流式传输失败的 OpenAIException）
        // 必须继续执行下方的传输／IO 和异常原因链检查，
        // 而不是直接归类为永久性客户端错误（issue #3057）。
        if (error instanceof ModelHttpException mhe && mhe.getStatusCode() != null) {
            return mhe.isRetryableHttpStatus();
        }

        // 超时错误可以重试
        if (error instanceof TimeoutException) {
            return true;
        }

        // 网络／IO 错误可以重试
        if (error instanceof IOException) {
            return true;
        }

        // 检查底层原因是否可重试（用于包装后的异常）
        Throwable cause = error.getCause();
        if (cause != null && cause != error) {
            return isRetryableError(cause);
        }

        // 未知错误默认不重试，避免掩盖问题
        return false;
    }

    /**
     * 模型 API 调用的标准默认配置。
     *
     * <ul>
     *   <li>超时：5 分钟
     *   <li>最大尝试次数：3（首次 + 2 次重试）
     *   <li>初始退避：2 秒
     *   <li>最大退避：30 秒
     *   <li>退避倍数：2.0（指数增长）
     *   <li>重试条件：仅可重试错误（429、5xx、超时、网络错误）
     * </ul>
     *
     * <p>注意：退避时间设置得较长（初始 2 秒、最长 30 秒），
     * 以更好地应对高并发场景下模型服务商的 HTTP 429 限流。
     */
    public static final ExecutionConfig MODEL_DEFAULTS =
            builder()
                    .timeout(Duration.ofMinutes(5))
                    .maxAttempts(3)
                    .initialBackoff(Duration.ofSeconds(2))
                    .maxBackoff(Duration.ofSeconds(30))
                    .backoffMultiplier(2.0)
                    .retryOn(RETRYABLE_ERRORS)
                    .build();

    /**
     * 工具执行的标准默认配置。
     *
     * <ul>
     *   <li>超时：5 分钟
     *   <li>最大尝试次数：1（不重试）
     * </ul>
     */
    public static final ExecutionConfig TOOL_DEFAULTS =
            builder().timeout(Duration.ofMinutes(5)).maxAttempts(1).build();

    private ExecutionConfig(Builder builder) {
        this.timeout = builder.timeout;
        this.maxAttempts = builder.maxAttempts;
        this.initialBackoff = builder.initialBackoff;
        this.maxBackoff = builder.maxBackoff;
        this.backoffMultiplier = builder.backoffMultiplier;
        this.retryOn = builder.retryOn;
    }

    /**
     * 获取超时时长。
     *
     * @return 超时时长；未设置时为 null
     */
    public Duration getTimeout() {
        return timeout;
    }

    /**
     * 获取最大尝试次数。
     *
     * @return 最大尝试次数（含首次尝试）；未设置时为 null
     */
    public Integer getMaxAttempts() {
        return maxAttempts;
    }

    /**
     * 获取初始退避时长。
     *
     * @return 初始退避时长；未设置时为 null
     */
    public Duration getInitialBackoff() {
        return initialBackoff;
    }

    /**
     * 获取最大退避时长。
     *
     * @return 最大退避时长；未设置时为 null
     */
    public Duration getMaxBackoff() {
        return maxBackoff;
    }

    /**
     * 获取退避倍数。
     *
     * @return 退避倍数；未设置时为 null
     */
    public Double getBackoffMultiplier() {
        return backoffMultiplier;
    }

    /**
     * 获取重试谓词。
     *
     * @return 判断错误是否应重试的谓词；未设置时为 null
     */
    public Predicate<Throwable> getRetryOn() {
        return retryOn;
    }

    /**
     * 创建新的 ExecutionConfig 建造者。
     *
     * @return 新的 Builder 实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 合并两个 ExecutionConfig 实例，优先使用主配置。
     *
     * <p>按参数逐一合并：主配置中该参数非 null 时使用主配置值，否则使用回退值。
     * 由此正确叠加不同来源的配置。
     *
     * <p><b>合并行为：</b>
     * <ul>
     *   <li>每个字段：primary != null ? primary : fallback
     *   <li>primary 为 null 时，直接返回 fallback
     *   <li>fallback 为 null 时，直接返回 primary
     * </ul>
     *
     * <p><b>示例：</b>
     *
     * <pre>{@code
     * ExecutionConfig defaults = ExecutionConfig.MODEL_DEFAULTS;
     * ExecutionConfig agentLevel = ExecutionConfig.builder()
     *     .timeout(Duration.ofMinutes(2))
     *     .build();
     *
     * // 结果：timeout=2min，maxAttempts=3（来自默认配置），……
     * ExecutionConfig merged = ExecutionConfig.mergeConfigs(agentLevel, defaults);
     * }</pre>
     *
     * @param primary 主配置（较高优先级）
     * @param fallback 回退配置（较低优先级）
     * @return 合并后的配置；两者都为 null 时返回 null
     */
    public static ExecutionConfig mergeConfigs(ExecutionConfig primary, ExecutionConfig fallback) {
        if (primary == null) {
            return fallback;
        }
        if (fallback == null) {
            return primary;
        }

        Builder builder = builder();
        builder.timeout(primary.timeout != null ? primary.timeout : fallback.timeout);
        builder.maxAttempts(
                primary.maxAttempts != null ? primary.maxAttempts : fallback.maxAttempts);
        builder.initialBackoff(
                primary.initialBackoff != null ? primary.initialBackoff : fallback.initialBackoff);
        builder.maxBackoff(primary.maxBackoff != null ? primary.maxBackoff : fallback.maxBackoff);
        builder.backoffMultiplier(
                primary.backoffMultiplier != null
                        ? primary.backoffMultiplier
                        : fallback.backoffMultiplier);
        builder.retryOn(primary.retryOn != null ? primary.retryOn : fallback.retryOn);

        return builder.build();
    }

    /** ExecutionConfig 的建造者。 */
    public static class Builder {
        private Duration timeout;
        private Integer maxAttempts;
        private Duration initialBackoff;
        private Duration maxBackoff;
        private Double backoffMultiplier;
        private Predicate<Throwable> retryOn;

        /**
         * 设置单次执行的超时时长。
         *
         * @param timeout 超时时长；null 表示不设置超时
         * @return 当前建造者实例
         */
        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        /**
         * 设置最大尝试次数（包含首次尝试）。
         *
         * <p>例如 maxAttempts=3 表示首次尝试 1 次，加上重试 2 次。
         *
         * @param maxAttempts 最大尝试次数（必须 >= 1），或 null
         * @return 当前建造者实例
         */
        public Builder maxAttempts(Integer maxAttempts) {
            if (maxAttempts != null && maxAttempts < 1) {
                throw new IllegalArgumentException("maxAttempts must be >= 1");
            }
            this.maxAttempts = maxAttempts;
            return this;
        }

        /**
         * 设置第一次重试前的初始退避时长。
         *
         * @param initialBackoff 初始退避时长，或 null
         * @return 当前建造者实例
         */
        public Builder initialBackoff(Duration initialBackoff) {
            this.initialBackoff = initialBackoff;
            return this;
        }

        /**
         * 设置两次重试之间的最大退避时长。
         *
         * @param maxBackoff 最大退避时长，或 null
         * @return 当前建造者实例
         */
        public Builder maxBackoff(Duration maxBackoff) {
            this.maxBackoff = maxBackoff;
            return this;
        }

        /**
         * 设置每次重试后应用的退避倍数。
         *
         * <p>例如 initialBackoff=1s、maxBackoff=10s、backoffMultiplier=2.0 时，
         * 重试延迟依次为 1s、2s、4s、8s、10s（达到上限）、10s……
         *
         * @param backoffMultiplier 退避倍数（必须 >= 1.0），或 null
         * @return 当前建造者实例
         */
        public Builder backoffMultiplier(Double backoffMultiplier) {
            if (backoffMultiplier != null && backoffMultiplier < 1.0) {
                throw new IllegalArgumentException("backoffMultiplier must be >= 1.0");
            }
            this.backoffMultiplier = backoffMultiplier;
            return this;
        }

        /**
         * 设置判断错误是否应触发重试的谓词。
         *
         * @param retryOn 重试谓词（返回 true 表示重试），或 null
         * @return 当前建造者实例
         */
        public Builder retryOn(Predicate<Throwable> retryOn) {
            this.retryOn = retryOn;
            return this;
        }

        /**
         * 构建新的 ExecutionConfig 实例。
         *
         * @return 新的 ExecutionConfig 实例
         */
        public ExecutionConfig build() {
            return new ExecutionConfig(this);
        }
    }
}
