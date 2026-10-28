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

package io.agentscope.extensions.judge.jev;

import io.agentscope.core.agent.RuntimeContext;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import reactor.core.publisher.Mono;

/**
 * JEV 集成共享的逐订阅执行预算，以及仅含元数据的观测。
 *
 * <p>【学习提示】预算在每次订阅时建立，OFF 跳过语义操作；SHADOW 与 ENFORCE 的具体副作用区别仍由各集成调用方落实，不能仅看枚举名推断。错误转为显式 ERROR 决策，取消则传播并记录 CANCELLED；观测器异常被隔离，不应改变业务执行。
 */
public final class JevExecution {
    public enum Mode {
        OFF,
        SHADOW,
        ENFORCE
    }

    public enum Status {
        DECIDED,
        INCONCLUSIVE,
        ERROR,
        CANCELLED,
        SKIPPED
    }

    public record Decision<T>(
            Status status, T value, String reason, Map<String, String> recommendation) {
        public Decision(Status status, T value, String reason) {
            this(status, value, reason, Map.of());
        }

        public Decision {
            recommendation = Map.copyOf(recommendation);
            Objects.requireNonNull(status);
            Objects.requireNonNull(reason);
        }

        public static <T> Decision<T> decided(T value) {
            return new Decision<>(Status.DECIDED, value, "DECIDED");
        }

        public static <T> Decision<T> uncertain(String reason) {
            return new Decision<>(Status.INCONCLUSIVE, null, reason);
        }
    }

    public record Record(
            String purpose,
            String version,
            Mode mode,
            Status status,
            String reason,
            Duration elapsed,
            Map<String, String> recommendation) {}

    public record Options(
            Mode mode,
            Duration budget,
            String version,
            BiConsumer<RuntimeContext, Record> observer) {
        public Options {
            Objects.requireNonNull(mode);
            Objects.requireNonNull(budget);
            Objects.requireNonNull(observer);
            if (budget.isZero() || budget.isNegative())
                throw new IllegalArgumentException("budget must be positive");
            if (version == null || version.isBlank())
                throw new IllegalArgumentException("version required");
        }

        public static Options disabled() {
            return new Options(Mode.OFF, Duration.ofSeconds(2), "v1", (ctx, record) -> {});
        }
    }

    private final String purpose;
    private final Options options;

    public JevExecution(String purpose, Options options) {
        this.purpose = Objects.requireNonNull(purpose);
        this.options = Objects.requireNonNull(options);
    }

    public Mode mode() {
        return options.mode();
    }

    public <T> Mono<Decision<T>> execute(
            RuntimeContext ctx, Supplier<Mono<Decision<T>>> operation) {
        return Mono.defer(
                () -> {
                    long started = System.nanoTime();
                    Mono<Decision<T>> source =
                            options.mode() == Mode.OFF
                                    ? Mono.just(new Decision<>(Status.SKIPPED, null, "DISABLED"))
                                    : Mono.defer(operation)
                                            .switchIfEmpty(
                                                    Mono.error(
                                                            new IllegalStateException(
                                                                    "empty decision")))
                                            .timeout(options.budget())
                                            .onErrorResume(
                                                    error ->
                                                            Mono.just(
                                                                    new Decision<>(
                                                                            Status.ERROR,
                                                                            null,
                                                                            error
                                                                                            instanceof
                                                                                            TimeoutException
                                                                                    ? "TIMEOUT"
                                                                                    : "CALL_OR_RESPONSE_ERROR")));
                    return source.doOnNext(
                                    d ->
                                            observe(
                                                    ctx,
                                                    d.status(),
                                                    d.reason(),
                                                    started,
                                                    d.recommendation()))
                            .doOnCancel(
                                    () ->
                                            observe(
                                                    ctx,
                                                    Status.CANCELLED,
                                                    "CANCELLED",
                                                    started,
                                                    Map.of()));
                });
    }

    private void observe(
            RuntimeContext ctx,
            Status status,
            String reason,
            long started,
            Map<String, String> recommendation) {
        try {
            options.observer()
                    .accept(
                            ctx,
                            new Record(
                                    purpose,
                                    options.version(),
                                    options.mode(),
                                    status,
                                    reason,
                                    Duration.ofNanos(System.nanoTime() - started),
                                    recommendation));
        } catch (RuntimeException ignored) {
            /*
             * 可观测性不得改变执行行为。
             */
        }
    }
}
