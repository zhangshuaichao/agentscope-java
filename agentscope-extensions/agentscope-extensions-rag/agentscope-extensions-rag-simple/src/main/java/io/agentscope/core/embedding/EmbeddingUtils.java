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

import io.agentscope.core.model.ExecutionConfig;
import java.time.Duration;
import java.util.List;
import java.util.function.Predicate;
import org.slf4j.Logger;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * EmbeddingModel 常用操作工具类。
 *
 * <p>提供不同嵌入模型实现共享的功能，包括 API 调用超时、重试及转换工具。
 */
public final class EmbeddingUtils {

    private static final Duration DEFAULT_INITIAL_BACKOFF = Duration.ofSeconds(1);
    private static final Duration DEFAULT_MAX_BACKOFF = Duration.ofSeconds(10);

    private EmbeddingUtils() {
        // 工具类，禁止实例化。
    }

    /**
     * 为嵌入 Mono 应用超时与重试配置。
     *
     * <p>根据 ExecutionConfig 为原 Mono 包装超时和重试操作符。两者均可选，仅配置时生效。
     *
     * <p>【学习提示】操作符顺序是先 timeout 再 retryWhen，重试会重新订阅带超时的上游，因此该时长限制单次尝试，而不是包含退避的总任务期限。maxAttempts 包含初次尝试；允许重试的错误由谓词决定。
     *
     * <p><b>超时行为：</b>
     * <ul>
     * <li>配置超时时，请求超过指定时长会失败。</li>
     * <li>超时触发带时长详情的 EmbeddingException。</li>
     * <li>未配置超时时，此包装层不限制请求时长。</li>
     * </ul>
     *
     * <p><b>重试行为：</b>
     * <ul>
     * <li>提供 ExecutionConfig 且 maxAttempts > 1 时，失败请求采用指数退避重试。</li>
     * <li>遵循 maxAttempts、initialBackoff、maxBackoff 设置。</li>
     * <li>仅重试符合 retryOn 谓词的错误。</li>
     * <li>每次重试记录尝试次数和失败原因。</li>
     * </ul>
     *
     * @param embeddingMono 待增强的原嵌入 Mono
     * @param config 超时与重试执行配置（可为 null）
     * @param modelName 错误消息和日志使用的模型名称
     * @param provider 错误消息使用的提供者名称，如 dashscope、openai
     * @param logger 输出调试和警告消息的日志实例
     * @param operationType 操作类型描述，如 embedding、batch embedding
     * @param <T> 嵌入结果类型
     * @return 应用超时与重试后的 Mono
     */
    public static <T> Mono<T> applyTimeoutAndRetry(
            Mono<T> embeddingMono,
            ExecutionConfig config,
            String modelName,
            String provider,
            Logger logger,
            String operationType) {

        if (config == null) {
            return embeddingMono;
        }

        // 应用已配置的超时。
        Duration timeout = config.getTimeout();
        if (timeout != null) {
            embeddingMono =
                    embeddingMono.timeout(
                            timeout,
                            Mono.error(
                                    new EmbeddingException(
                                            operationType + " request timeout after " + timeout,
                                            modelName,
                                            provider)));
            logger.debug("Applied timeout: {} for {} model: {}", timeout, operationType, modelName);
        }

        // 应用已配置的重试（maxAttempts > 1 表示启用）。
        Integer maxAttempts = config.getMaxAttempts();
        if (maxAttempts != null && maxAttempts > 1) {
            Duration initialBackoff = config.getInitialBackoff();
            Duration maxBackoff = config.getMaxBackoff();
            Predicate<Throwable> retryOn = config.getRetryOn();

            // 未指定时使用默认值。
            if (initialBackoff == null) {
                initialBackoff = DEFAULT_INITIAL_BACKOFF;
            }
            if (maxBackoff == null) {
                maxBackoff = DEFAULT_MAX_BACKOFF;
            }
            if (retryOn == null) {
                retryOn = error -> true; // 默认重试全部错误。
            }

            Retry retrySpec =
                    Retry.backoff(maxAttempts - 1, initialBackoff)
                            .maxBackoff(maxBackoff)
                            .jitter(0.5)
                            .filter(retryOn)
                            .doBeforeRetry(
                                    signal ->
                                            logger.warn(
                                                    "Retrying {} request (attempt {}/{}) due to:"
                                                            + " {}",
                                                    operationType,
                                                    signal.totalRetriesInARow() + 1,
                                                    maxAttempts - 1,
                                                    signal.failure().getMessage()));

            embeddingMono = embeddingMono.retryWhen(retrySpec);
            logger.debug(
                    "Applied retry config: maxAttempts={}, initialBackoff={} for {} model: {}",
                    maxAttempts,
                    initialBackoff,
                    operationType,
                    modelName);
        }

        return embeddingMono;
    }

    /**
     * 为单条嵌入 Mono 应用超时与重试配置。
     *
     * <p>便捷方法，委托给 {@link #applyTimeoutAndRetry(Mono, ExecutionConfig, String, String, Logger, String)}，操作类型为 embedding。
     *
     * @param embeddingMono 待增强的原嵌入 Mono
     * @param config 超时与重试执行配置（可为 null）
     * @param modelName 错误消息和日志使用的模型名称
     * @param provider 错误消息使用的提供者名称，如 dashscope、openai
     * @param logger 输出调试和警告消息的日志实例
     * @return 应用超时与重试后的 Mono
     */
    public static Mono<EmbeddingResponse> applyTimeoutAndRetry(
            Mono<EmbeddingResponse> embeddingMono,
            ExecutionConfig config,
            String modelName,
            String provider,
            Logger logger) {
        return applyTimeoutAndRetry(
                embeddingMono, config, modelName, provider, logger, "Embedding");
    }

    /**
     * 为批量嵌入 Mono 应用超时与重试配置。
     *
     * <p>便捷方法，委托给 {@link #applyTimeoutAndRetry(Mono, ExecutionConfig, String, String, Logger, String)}，操作类型为 batch embedding。
     *
     * @param batchEmbeddingMono 待增强的原批量嵌入 Mono
     * @param config 超时与重试执行配置（可为 null）
     * @param modelName 错误消息和日志使用的模型名称
     * @param provider 错误消息使用的提供者名称，如 dashscope、openai
     * @param logger 输出调试和警告消息的日志实例
     * @return 应用超时与重试后的 Mono
     */
    public static Mono<List<double[]>> applyTimeoutAndRetryBatch(
            Mono<List<double[]>> batchEmbeddingMono,
            ExecutionConfig config,
            String modelName,
            String provider,
            Logger logger) {
        return applyTimeoutAndRetry(
                batchEmbeddingMono, config, modelName, provider, logger, "Batch embedding");
    }

    /**
     * 确保 ExecutionConfig 在需要时采用 MODEL_DEFAULTS。
     *
     * <p>标准默认配置规则：
     * <ul>
     * <li>config 为 null 时返回 MODEL_DEFAULTS。</li>
     * <li>否则原样返回（需要合并时由调用方处理）。</li>
     * </ul>
     *
     * <p>嵌入模型构建器据此补充默认超时与重试配置。
     *
     * @param config 原始配置（可为 null）
     * @return 按需应用默认值的配置；config 为 null 时返回 MODEL_DEFAULTS
     */
    public static ExecutionConfig ensureDefaultExecutionConfig(ExecutionConfig config) {
        if (config == null) {
            return ExecutionConfig.MODEL_DEFAULTS;
        }
        // 配置存在时原样返回，需要合并时由调用方处理。
        return config;
    }

    /**
     * 将 Double 列表转换为 double 数组。
     *
     * <p>用于将 DashScope SDK 返回的 List&lt;Double&gt; 向量转换为 EmbeddingModel 的标准 double[]。
     *
     * @param values Double 列表
     * @return double 数组
     */
    public static double[] convertDoubleListToArray(List<Double> values) {
        double[] array = new double[values.size()];
        for (int i = 0; i < values.size(); i++) {
            array[i] = values.get(i);
        }
        return array;
    }

    /**
     * 将 Float 列表转换为 double 数组。
     *
     * <p>用于将 OpenAI SDK 返回的 List&lt;Float&gt; 向量转换为 EmbeddingModel 的标准 double[]。
     *
     * @param values Float 列表
     * @return double 数组
     */
    public static double[] convertFloatListToDoubleArray(List<Float> values) {
        double[] array = new double[values.size()];
        for (int i = 0; i < values.size(); i++) {
            array[i] = values.get(i);
        }
        return array;
    }

    /**
     * 将 float 数组转换为 double 数组。
     *
     * <p>用于将 Ollama SDK 返回的 float[] 向量转换为 EmbeddingModel 的标准 double[]。
     *
     * @param values float 数组
     * @return double 数组
     */
    public static double[] convertFloatArrayToDoubleArray(float[] values) {
        double[] array = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            array[i] = values[i];
        }
        return array;
    }
}
