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

package io.agentscope.core.tracing;

import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Operators;
import reactor.util.context.Context;

/**
 * 全局 {@link Tracer} 实例注册表。
 *
 * <p><b>Reactor 全局钩子：</b>通过 {@link #enableTracingHook()} 和 {@link #disableTracingHook()}
 * 启用或禁用跨 Reactor 操作符传播追踪上下文的全局钩子。
 * 启用后影响 JVM 内所有 Reactor 操作，即使与追踪无关的代码也可能承担性能开销。
 * 请谨慎使用，仅在全部 Reactor 管道都需要追踪上下文传播时启用。
 *
 * <p><b>迁移：</b>新应用应通过 {@code OpenTelemetrySdk.builder().setTracerProvider(...).buildAndRegisterGlobal()}
 * 配置 OpenTelemetry SDK，再为每个智能体附加 {@link OtelTracingMiddleware}。
 * 该中间件直接读取全局 OpenTelemetry 实例，新代码不应调用 {@link #register(Tracer)}。
 *
 * @deprecated 自 2.0.0 起弃用，请改用 {@link OtelTracingMiddleware}。
 */
@Deprecated(forRemoval = true, since = "2.0.0")
@SuppressWarnings("deprecation")
public class TracerRegistry {
    private static final String HOOK_KEY = "agentscope-trace-context";
    private static volatile boolean hookEnabled = false;

    /**
     * 启用用于追踪上下文传播的全局 Reactor 钩子。
     *
     * <p><b>机制：</b>通过 {@link Hooks#onEachOperator(String, Function)} 注册全局钩子，
     * 拦截 {@code Flux} 与 {@code Mono} 链中每个 Reactor 操作符的创建。
     * 包装 {@code Subscriber}，确保下游信号（{@code onNext}、{@code onError}、{@code onComplete}）
     * 在组装时捕获或来自上游的追踪上下文中处理。
     *
     * <p><b>激活：</b>调用后立即生效，影响 JVM 中随后组装的所有 Reactor 流。
     *
     * <p><b>性能：</b>为应用中的每个 Reactor 操作符增加开销，包括与 AgentScope 追踪无关的操作符。
     * 涉及包装订阅者的对象分配及上下文捕获／恢复，仅在需要跨异步边界自动传播上下文时启用。
     *
     * <p>可以安全多次调用，钩子只注册一次。
     */
    public static synchronized void enableTracingHook() {
        if (!hookEnabled) {
            Hooks.onEachOperator(
                    HOOK_KEY,
                    Operators.lift(
                            (scannable, subscriber) ->
                                    new CoreSubscriber<Object>() {
                                        @Override
                                        public void onSubscribe(Subscription s) {
                                            subscriber.onSubscribe(s);
                                        }

                                        @Override
                                        public void onNext(Object o) {
                                            TracerRegistry.get()
                                                    .runWithContext(
                                                            subscriber.currentContext(),
                                                            () -> {
                                                                subscriber.onNext(o);
                                                                return null;
                                                            });
                                        }

                                        @Override
                                        public void onError(Throwable t) {
                                            TracerRegistry.get()
                                                    .runWithContext(
                                                            subscriber.currentContext(),
                                                            () -> {
                                                                subscriber.onError(t);
                                                                return null;
                                                            });
                                        }

                                        @Override
                                        public void onComplete() {
                                            TracerRegistry.get()
                                                    .runWithContext(
                                                            subscriber.currentContext(),
                                                            () -> {
                                                                subscriber.onComplete();
                                                                return null;
                                                            });
                                        }

                                        @Override
                                        public Context currentContext() {
                                            return subscriber.currentContext();
                                        }
                                    }));
            hookEnabled = true;
        }
    }

    /**
     * 禁用用于追踪上下文传播的全局 Reactor 钩子。
     * 从 JVM 中所有 Reactor 操作符移除此钩子。
     */
    public static synchronized void disableTracingHook() {
        if (hookEnabled) {
            Hooks.resetOnEachOperator(HOOK_KEY);
            hookEnabled = false;
        }
    }

    private static volatile Tracer tracer = new NoopTracer();

    /**
     * 为旧版全局追踪路径注册 tracer。
     *
     * <p>新代码应全局注册 OpenTelemetry SDK，并为智能体附加 {@link OtelTracingMiddleware}。
     */
    public static void register(Tracer tracer) {
        TracerRegistry.tracer = tracer;
        if (tracer instanceof NoopTracer) {
            disableTracingHook();
        } else {
            enableTracingHook();
        }
    }

    public static void resetToNoop() {
        Tracer previousTracer = TracerRegistry.tracer;
        TracerRegistry.tracer = new NoopTracer();
        disableTracingHook();
        previousTracer.shutdown();
    }

    public static Tracer get() {
        return tracer;
    }
}
