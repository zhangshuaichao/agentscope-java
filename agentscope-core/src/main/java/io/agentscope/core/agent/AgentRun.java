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
package io.agentscope.core.agent;

import io.agentscope.core.interruption.InterruptSource;
import io.agentscope.core.message.Msg;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 一次性、惰性执行句柄。应在订阅 {@link #stream()} 之前注册它。
 * 每个句柄独立管理取消和状态，绝不会在智能体上注册 RuntimeContext。
 * 第二次订阅会被拒绝，因此同一句柄不会执行两次（采用调用方提供的 runId 时，其唯一性由调用方保证）。
 * 取消订阅也会取消此句柄。重试时应创建新句柄。
 *
 * @param <T> 执行的输出类型（通常是 Msg 或 AgentEvent）
 */
public final class AgentRun<T> {
    /**
     * 执行句柄状态。COMPLETED 表示发布者正常返回，包括语义上暂停或不成功的结果。
     * 请检查 AgentResult／持久化轮次状态以确定逻辑结果。
     * QUEUED 包括获准进入核心生命周期前的准备阶段。
     */
    public enum Status {
        CREATED,
        QUEUED,
        RUNNING,
        COMPLETED,
        FAILED,
        CANCELLED;

        /**
         * 是否无法再通过此句柄启动任何执行。
         */
        public boolean isTerminal() {
            return this == COMPLETED || this == FAILED || this == CANCELLED;
        }
    }

    private final RunControl control;
    private final AtomicBoolean subscribed = new AtomicBoolean();
    private final AtomicReference<Supplier<? extends Publisher<T>>> pendingSource;
    private final Flux<T> stream = Flux.defer(this::subscribeOnce);

    private AgentRun(String agentId, String runId, Supplier<? extends Publisher<T>> source) {
        control = new RunControl(Objects.requireNonNull(agentId, "agentId"), runId);
        pendingSource = new AtomicReference<>(Objects.requireNonNull(source, "source"));
        control.termination().subscribe(status -> pendingSource.set(null));
    }

    private Flux<T> subscribeOnce() {
        if (!subscribed.compareAndSet(false, true)) {
            return Flux.error(new IllegalStateException("AgentRun permits one subscription"));
        }
        if (!control.queue()) {
            return Flux.error(new CancellationException("Agent run cancelled before subscription"));
        }
        Flux<T> invocation =
                Flux.defer(this::invokeSource)
                        .contextWrite(ctx -> ctx.put(RunControl.CONTEXT_KEY, control));
        return control.guard(invocation)
                .doOnComplete(
                        () -> {
                            control.finish(Status.COMPLETED);
                            if (control.status() == Status.CANCELLED) {
                                throw new CancellationException("Agent run cancelled");
                            }
                        })
                .onErrorMap(
                        error -> {
                            control.finish(Status.FAILED);
                            return control.status() == Status.CANCELLED
                                    ? new CancellationException("Agent run cancelled")
                                    : error;
                        })
                .doOnCancel(control::cancel);
    }

    private Publisher<T> invokeSource() {
        Supplier<? extends Publisher<T>> invocation = pendingSource.getAndSet(null);
        if (invocation == null || control.status().isTerminal()) {
            return Flux.error(new CancellationException("Agent run cancelled"));
        }
        return invocation.get();
    }

    /**
     * 包装智能体调用但不启动它。优先使用智能体的 prepareRun/prepareCall API。
     */
    public static <T> AgentRun<T> create(String agentId, Supplier<? extends Publisher<T>> source) {
        return create(agentId, null, source);
    }

    /**
     * 包装智能体调用但不启动它，并采用 {@code runId}（通常为 {@code ctx.getRunId()}），
     * 使句柄与 {@link RuntimeContext} 标识同一次执行。为 null 或空白时生成新标识符。
     */
    public static <T> AgentRun<T> create(
            String agentId, String runId, Supplier<? extends Publisher<T>> source) {
        return new AgentRun<>(agentId, runId, source);
    }

    /**
     * 此次独立执行的稳定、不透明标识符。
     */
    public String runId() {
        return control.runId();
    }

    /**
     * 当前执行状态；协作式中断被观察到之前，状态仍可能为 RUNNING。
     */
    public Status status() {
        return control.status();
    }

    /**
     * 通过一次订阅启动执行。取消以 CancellationException 报告。
     */
    public Flux<T> stream() {
        return stream;
    }

    /**
     * 可重放的终态通知；观察它不会启动执行。
     */
    public Mono<Status> termination() {
        return control.termination();
    }

    /**
     * 立即取消此次运行的响应式订阅，包括仍在排队的运行。
     */
    public boolean cancel() {
        return control.cancel();
    }

    /**
     * 协作式中断正在执行的运行；尚未启动时则取消。
     */
    public boolean interrupt(Msg message) {
        return control.interrupt(InterruptSource.USER, message);
    }

    /**
     * 不附带用户消息地进行协作式中断。
     */
    public boolean interrupt() {
        return interrupt(null);
    }
}
