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

import io.agentscope.core.message.ContentBlock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.util.retry.Retry;

/**
 * 常用模型操作的工具类。
 *
 * <p>提供不同 Model 实现共享的能力，包括模型 API 调用的超时和重试逻辑。
 */
public final class ModelUtils {

    private static final Logger LOG = LoggerFactory.getLogger(ModelUtils.class);

    private ModelUtils() {
        // 工具类，禁止实例化
    }

    /**
     * 为模型响应 Flux 应用超时与重试配置。
     *
     * <p>根据 GenerateOptions 中的配置，用超时和重试操作符包装原始 Flux。
     * 两者均为可选项，仅在配置后应用。
     *
     * <p><b>超时行为：</b>
     * <ul>
     *   <li>配置 requestTimeout 后，响应流等待信号超过指定时长则失败
     *   <li>超时触发 ModelException，并包含超时时长信息
     *   <li>未配置超时时，请求可无限期运行
     * </ul>
     *
     * <p><b>重试行为：</b>
     * <ul>
     *   <li>提供 RetryConfig 后，失败请求采用指数退避重试，但仅限当前订阅
     *       尚未发出包含可见内容块的响应时
     *   <li>重试遵循 maxAttempts、initialBackoff 和 maxBackoff 设置
     *   <li>仅重试满足 retryOn 谓词的错误
     *   <li>每次重试记录尝试次数及失败原因
     * </ul>
     *
     * <p>【学习提示】重试意味着重新订阅，可能重新发出模型请求。
     * 已有部分可见输出后继续重试会造成重复内容，因此“错误可重试”不等于“任何时刻都可重试”。
     * 此处使用 Flux.timeout：检查等待首个或后续信号的间隔，不是整个流的累计耗时上限。
     *
     * @param responseFlux 要增强的原始响应 Flux
     * @param options 包含超时和重试配置的生成选项（可为 null）
     * @param defaultOptions 当 options 为 null 时使用的默认选项
     * @param modelName 用于错误消息和日志的模型名称
     * @param provider 用于错误消息的服务商名称（例如 "dashscope"、"openai"）
     * @return 应用超时和重试后的 Flux
     */
    public static Flux<ChatResponse> applyTimeoutAndRetry(
            Flux<ChatResponse> responseFlux,
            GenerateOptions options,
            GenerateOptions defaultOptions,
            String modelName,
            String provider) {

        // 合并选项：请求级选项（主配置）覆盖默认选项（回退配置）
        GenerateOptions effectiveOptions = GenerateOptions.mergeOptions(options, defaultOptions);

        // 提取执行配置
        ExecutionConfig execConfig = effectiveOptions.getExecutionConfig();
        if (execConfig != null) {
            // 已配置时应用超时
            Duration timeout = execConfig.getTimeout();
            if (timeout != null) {
                responseFlux =
                        responseFlux.timeout(
                                timeout,
                                Flux.error(
                                        new ModelException(
                                                "Model request timeout after " + timeout,
                                                new TimeoutException(
                                                        "Model request timeout after " + timeout),
                                                modelName,
                                                provider)));
                LOG.debug("Applied timeout: {} for model: {}", timeout, modelName);
            }

            // 已配置时应用重试（maxAttempts > 1 表示启用重试）
            Integer maxAttempts = execConfig.getMaxAttempts();
            if (maxAttempts != null && maxAttempts > 1) {
                Duration initialBackoff = execConfig.getInitialBackoff();
                Duration maxBackoff = execConfig.getMaxBackoff();
                Predicate<Throwable> retryOn = execConfig.getRetryOn();

                // 未指定时使用默认值
                if (initialBackoff == null) {
                    initialBackoff = Duration.ofSeconds(1);
                }
                if (maxBackoff == null) {
                    maxBackoff = Duration.ofSeconds(10);
                }
                if (retryOn == null) {
                    retryOn = error -> true; // 默认重试所有错误
                }
                // 下方 Lambda 捕获的变量必须保持有效 final
                final int retryCount = maxAttempts - 1;
                final Duration effectiveInitialBackoff = initialBackoff;
                final Duration effectiveMaxBackoff = maxBackoff;
                final Predicate<Throwable> effectiveRetryOn = retryOn;

                // 只有尚未发出用户可见内容时才可安全重试；在部分输出之后重新订阅
                // 会再次发出请求，下游会先看到第一次的部分响应，随后又看到重试响应，
                // 从而产生重复内容。
                // 可见性标记在 defer 内创建，保证每个订阅（模型调用）
                // 都有独立的新标记；同一次调用的多次重试
                // 则共享该标记。
                final Flux<ChatResponse> source = responseFlux;
                responseFlux =
                        Flux.defer(
                                () -> {
                                    AtomicBoolean emittedVisibleContent = new AtomicBoolean(false);
                                    return source.doOnNext(
                                                    response -> {
                                                        if (hasVisibleContent(response)) {
                                                            emittedVisibleContent.set(true);
                                                        }
                                                    })
                                            .retryWhen(
                                                    Retry.backoff(
                                                                    retryCount,
                                                                    effectiveInitialBackoff)
                                                            .maxBackoff(effectiveMaxBackoff)
                                                            .jitter(0.5)
                                                            .filter(
                                                                    error -> {
                                                                        if (emittedVisibleContent
                                                                                .get()) {
                                                                            if (effectiveRetryOn
                                                                                    .test(error)) {
                                                                                LOG.warn(
                                                                                        "Skipping"
                                                                                            + " retry"
                                                                                            + " of retryable"
                                                                                            + " error"
                                                                                            + " because"
                                                                                            + " visible"
                                                                                            + " content"
                                                                                            + " was already"
                                                                                            + " emitted"
                                                                                            + " (retrying"
                                                                                            + " would"
                                                                                            + " duplicate"
                                                                                            + " it):"
                                                                                            + " model={},"
                                                                                            + " error={}",
                                                                                        modelName,
                                                                                        error
                                                                                                .getMessage());
                                                                            }
                                                                            return false;
                                                                        }
                                                                        return effectiveRetryOn
                                                                                .test(error);
                                                                    })
                                                            .doBeforeRetry(
                                                                    signal ->
                                                                            LOG.warn(
                                                                                    "Retrying model"
                                                                                        + " request"
                                                                                        + " (attempt"
                                                                                        + " {}/{})"
                                                                                        + " due to:"
                                                                                        + " {}",
                                                                                    signal
                                                                                                    .totalRetriesInARow()
                                                                                            + 1,
                                                                                    retryCount,
                                                                                    signal.failure()
                                                                                            .getMessage(),
                                                                                    signal
                                                                                            .failure())));
                                });
                LOG.debug(
                        "Applied retry config: maxAttempts={}, initialBackoff={},"
                                + " retryBeforeFirstResponse=true for model: {}",
                        maxAttempts,
                        initialBackoff,
                        modelName);
            }
        }

        return responseFlux;
    }

    /** 响应是否携带用户可见内容（文本、思考或工具调用增量）。 */
    private static boolean hasVisibleContent(ChatResponse response) {
        List<ContentBlock> content = response.getContent();
        return content != null && !content.isEmpty();
    }

    /**
     * 确保 GenerateOptions 的 executionConfig 已应用 MODEL_DEFAULTS。
     *
     * <p>为 GenerateOptions 应用标准默认执行配置：
     * <ul>
     *   <li>options 为 null 时，创建使用 MODEL_DEFAULTS 的新选项
     *   <li>options 的 executionConfig 为 null 时，与 MODEL_DEFAULTS 合并
     *   <li>否则原样返回 options
     * </ul>
     *
     * <p>模型建造者使用此方法，确保即使用户提供的自定义 GenerateOptions
     * 不含 executionConfig，所有模型仍具有恰当的默认超时和重试配置。
     *
     * @param options 原始选项（可为 null）
     * @return 按需应用 MODEL_DEFAULTS 后的选项
     */
    public static GenerateOptions ensureDefaultExecutionConfig(GenerateOptions options) {
        if (options == null) {
            // 未提供选项，使用 MODEL_DEFAULTS
            return GenerateOptions.builder()
                    .executionConfig(ExecutionConfig.MODEL_DEFAULTS)
                    .build();
        }

        if (options.getExecutionConfig() == null) {
            // 已提供选项但未提供 executionConfig，与 MODEL_DEFAULTS 合并
            GenerateOptions withDefaults =
                    GenerateOptions.builder()
                            .executionConfig(ExecutionConfig.MODEL_DEFAULTS)
                            .build();
            return GenerateOptions.mergeOptions(options, withDefaults);
        }

        // executionConfig 已存在，原样返回
        return options;
    }
}
