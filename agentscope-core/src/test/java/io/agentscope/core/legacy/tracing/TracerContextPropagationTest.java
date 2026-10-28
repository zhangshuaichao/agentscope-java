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
package io.agentscope.core.legacy.tracing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.agentscope.core.tracing.Tracer;
import io.agentscope.core.tracing.TracerRegistry;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

/**
 * 测试 Reactor {@link Context} 在响应式链中的传播及跟踪集成。自定义 {@link Tracer} 将上下文值传播至可访问的 {@link ThreadLocal}。
 * <p>{@code TestTracer} 演示将 Reactor 的 test-key 值在响应式操作期间转入 {@code ThreadLocal}，模拟跟踪传播。验证 {@code map} 和 {@code flatMap} 等操作之间正确管理及恢复跟踪上下文。
 */
public class TracerContextPropagationTest {

    static final ThreadLocal<String> THREAD_LOCAL = new ThreadLocal<>();

    /**
     * {@link Tracer} 测试实现，将 Reactor 上下文值传播到 {@link ThreadLocal}，使测试可通过操作后的 {@code THREAD_LOCAL} 验证传播行为。
     */
    static class TestTracer implements Tracer {
        @Override
        public <TResp> TResp runWithContext(ContextView reactorCtx, Supplier<TResp> inner) {
            String val = reactorCtx.getOrDefault("test-key", null);
            String old = THREAD_LOCAL.get();
            if (val != null) {
                THREAD_LOCAL.set(val);
            }
            try {
                return inner.get();
            } finally {
                if (old != null) {
                    THREAD_LOCAL.set(old);
                } else {
                    THREAD_LOCAL.remove();
                }
            }
        }
    }

    @BeforeAll
    public static void enableHook() {
        TracerRegistry.enableTracingHook();
    }

    @AfterAll
    public static void disableHook() {
        TracerRegistry.disableTracingHook();
    }

    @BeforeEach
    public void setup() {
        TracerRegistry.register(new TestTracer());
        THREAD_LOCAL.remove();
    }

    @Test
    public void testContextPropagationInMono() {
        AtomicReference<String> captured = new AtomicReference<>();

        Mono.just("hello")
                .map(
                        s -> {
                            captured.set(THREAD_LOCAL.get());
                            return s;
                        })
                .contextWrite(Context.of("test-key", "propagated-value"))
                .block();

        assertEquals("propagated-value", captured.get());
    }

    @Test
    public void testContextPropagationInFlatMap() {
        AtomicReference<String> captured = new AtomicReference<>();

        Mono.just("hello")
                .flatMap(
                        s ->
                                Mono.just(s)
                                        .map(
                                                v -> {
                                                    captured.set(THREAD_LOCAL.get());
                                                    return v;
                                                }))
                .contextWrite(Context.of("test-key", "propagated-value"))
                .block();

        assertEquals("propagated-value", captured.get());
    }
}
