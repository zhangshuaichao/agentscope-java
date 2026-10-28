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
package io.agentscope.harness.agent.bus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.tracing.Tracer;
import io.agentscope.core.tracing.TracerRegistry;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.Disposable;

class WorkspaceMessageBusTest {

    @TempDir Path tempDir;
    private WorkspaceMessageBus bus;

    @BeforeEach
    void setUp() {
        LocalFilesystem fs = new LocalFilesystem(tempDir, true, 10);
        // 将心跳设为 50 毫秒，避免 subscribe() 时钟测试等待默认 3 秒。
        bus = new WorkspaceMessageBus(fs, "/bus", Duration.ofMillis(50));
    }

    // ---- 模式 A：排空队列 ----

    @Test
    void pushAndDrainInOrder() {
        bus.queuePush("q1", Map.of("seq", 1)).block();
        bus.queuePush("q1", Map.of("seq", 2)).block();
        bus.queuePush("q1", Map.of("seq", 3)).block();

        List<BusEntry> drained = bus.queueDrain("q1", 10).block();
        assertNotNull(drained);
        assertEquals(3, drained.size());
        assertEquals(1, drained.get(0).payload().get("seq"));
        assertEquals(2, drained.get(1).payload().get("seq"));
        assertEquals(3, drained.get(2).payload().get("seq"));
    }

    @Test
    void drainRemovesEntries() {
        bus.queuePush("q1", Map.of("v", "a")).block();
        bus.queuePush("q1", Map.of("v", "b")).block();

        bus.queueDrain("q1", 10).block();
        List<BusEntry> second = bus.queueDrain("q1", 10).block();
        assertTrue(second.isEmpty());
    }

    @Test
    void drainRespectsMaxCount() {
        bus.queuePush("q1", Map.of("v", 1)).block();
        bus.queuePush("q1", Map.of("v", 2)).block();
        bus.queuePush("q1", Map.of("v", 3)).block();

        List<BusEntry> first = bus.queueDrain("q1", 2).block();
        assertEquals(2, first.size());

        List<BusEntry> remaining = bus.queueDrain("q1", 10).block();
        assertEquals(1, remaining.size());
        assertEquals(3, remaining.get(0).payload().get("v"));
    }

    @Test
    void drainEmptyQueueReturnsEmpty() {
        List<BusEntry> drained = bus.queueDrain("nonexistent", 10).block();
        assertNotNull(drained);
        assertTrue(drained.isEmpty());
    }

    @Test
    void queueDeleteRemovesAll() {
        bus.queuePush("q1", Map.of("v", 1)).block();
        bus.queueDelete("q1").block();

        List<BusEntry> drained = bus.queueDrain("q1", 10).block();
        assertTrue(drained.isEmpty());
    }

    @Test
    void pushReturnsUniqueIds() {
        String id1 = bus.queuePush("q1", Map.of("v", 1)).block();
        String id2 = bus.queuePush("q1", Map.of("v", 2)).block();
        assertNotNull(id1);
        assertNotNull(id2);
        assertNotEquals(id1, id2);
    }

    @Test
    void queuesIsolatedByKey() {
        bus.queuePush("q1", Map.of("from", "q1")).block();
        bus.queuePush("q2", Map.of("from", "q2")).block();

        List<BusEntry> fromQ1 = bus.queueDrain("q1", 10).block();
        assertEquals(1, fromQ1.size());
        assertEquals("q1", fromQ1.get(0).payload().get("from"));
    }

    // ---- 模式 C：重放日志 ----

    @Test
    void logAppendAndReadInOrder() {
        bus.logAppend("log1", Map.of("seq", 1), 0).block();
        bus.logAppend("log1", Map.of("seq", 2), 0).block();

        List<BusEntry> all = bus.logRead("log1", null, 100).block();
        assertEquals(2, all.size());
        assertEquals(1, all.get(0).payload().get("seq"));
        assertEquals(2, all.get(1).payload().get("seq"));
    }

    @Test
    void logReadWithCursor() {
        bus.logAppend("log1", Map.of("v", "a"), 0).block();
        String id2 = bus.logAppend("log1", Map.of("v", "b"), 0).block();
        bus.logAppend("log1", Map.of("v", "c"), 0).block();

        List<BusEntry> afterId2 = bus.logRead("log1", id2, 100).block();
        assertEquals(1, afterId2.size());
        assertEquals("c", afterId2.get(0).payload().get("v"));
    }

    @Test
    void logReadIsNonDestructive() {
        bus.logAppend("log1", Map.of("v", 1), 0).block();
        List<BusEntry> first = bus.logRead("log1", null, 100).block();
        List<BusEntry> second = bus.logRead("log1", null, 100).block();
        assertEquals(first.size(), second.size());
    }

    @Test
    void logAppendRespectsMaxLen() {
        for (int i = 0; i < 10; i++) {
            bus.logAppend("log1", Map.of("i", i), 5).block();
        }
        List<BusEntry> all = bus.logRead("log1", null, 100).block();
        assertEquals(5, all.size());
        assertEquals(5, all.get(0).payload().get("i"));
        assertEquals(9, all.get(4).payload().get("i"));
    }

    @Test
    void logTrimDeletesAll() {
        bus.logAppend("log1", Map.of("v", 1), 0).block();
        bus.logTrim("log1").block();

        List<BusEntry> all = bus.logRead("log1", null, 100).block();
        assertTrue(all.isEmpty());
    }

    // ---- 收件箱领域辅助方法 ----

    @Test
    void inboxPushAndDrain() {
        bus.inboxPush("s1", Map.of("hint", "hello")).block();
        bus.inboxPush("s1", Map.of("hint", "world")).block();

        List<BusEntry> entries = bus.inboxDrain("s1", 100).block();
        assertEquals(2, entries.size());
        assertEquals("hello", entries.get(0).payload().get("hint"));

        assertTrue(bus.inboxDrain("s1", 100).block().isEmpty());
    }

    // ---- 跨实例共享 ----

    @Test
    void twoInstancesShareSameDirectory() {
        LocalFilesystem fs2 = new LocalFilesystem(tempDir, true, 10);
        WorkspaceMessageBus bus2 = new WorkspaceMessageBus(fs2, "/bus");

        bus.queuePush("shared", Map.of("from", "bus1")).block();
        bus2.queuePush("shared", Map.of("from", "bus2")).block();

        List<BusEntry> all = bus.queueDrain("shared", 10).block();
        assertEquals(2, all.size());

        assertTrue(bus2.queueDrain("shared", 10).block().isEmpty());
    }

    // ---- 会话事件 ----

    @Test
    void sessionPublishAndReadEvents() {
        bus.sessionPublishEvent("s1", Map.of("type", "TEXT_DELTA")).block();
        bus.sessionPublishEvent("s1", Map.of("type", "TEXT_END")).block();

        List<BusEntry> events = bus.sessionReadEvents("s1", null, 100).block();
        assertEquals(2, events.size());
        assertEquals("TEXT_DELTA", events.get(0).payload().get("type"));
        assertEquals("TEXT_END", events.get(1).payload().get("type"));
    }

    @Test
    void sessionTrimClearsEvents() {
        bus.sessionPublishEvent("s1", Map.of("v", 1)).block();
        bus.sessionTrimEvents("s1").block();

        assertTrue(bus.sessionReadEvents("s1", null, 100).block().isEmpty());
    }

    // ---- 文件结构验证 ----

    @Test
    void filesCreatedUnderBusRoot() throws IOException {
        bus.queuePush("myqueue", Map.of("v", 1)).block();

        Path busDir = tempDir.resolve("bus");
        assertTrue(Files.exists(busDir), "Bus root directory should exist");
        assertTrue(Files.exists(busDir.resolve("queues")), "queues/ should exist");

        long jsonFiles =
                Files.walk(busDir.resolve("queues"))
                        .filter(p -> p.toString().endsWith(".json"))
                        .count();
        assertTrue(jsonFiles >= 1, "Should have at least one .json queue entry");
    }

    // ---- 调度器亲和性回归保护 ----

    /**
     * 验证 {@link WorkspaceMessageBus#subscribe(String)} 在 {@code boundedElastic} 调度器上发出心跳。
     *
     * <p>原因：每次心跳的下游消费者（{@code WakeupDispatcher} 调用 {@code queueDrain(...).block()}）执行阻塞文件 I/O，并运行在发出心跳的线程上。不能放在 {@code parallel()} 上，因为该共享池大小与 CPU 核数相关，阻塞它会使全局定时器饥饿。{@code Flux.interval(d).subscribeOn(boundedElastic())} <b>不会</b>将时钟移出 {@code parallel}，因为 {@code subscribeOn} 只移动订阅调用；需使用 {@code Flux.interval(d, boundedElastic())}。此测试防止静默退回无效的 {@code subscribeOn} 写法。
     */
    @Test
    void subscribeEmitsTicksOnBoundedElastic() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> thread = new AtomicReference<>();
        Disposable d =
                bus.subscribe("regression")
                        .doOnNext(m -> thread.set(Thread.currentThread().getName()))
                        .take(1)
                        .subscribe(m -> latch.countDown());
        try {
            // 首个心跳在配置的 50 毫秒后到达；为调度预留余量。
            assertTrue(latch.await(5, TimeUnit.SECONDS), "no tick within 5s");
            assertNotNull(thread.get());
            assertTrue(
                    thread.get().startsWith("boundedElastic"),
                    "subscribe() tick emitted on " + thread.get() + ", expected boundedElastic");
        } finally {
            d.dispose();
        }
    }

    /**
     * 在 agentscope 层复现“打开 Studio 后崩溃”的场景。
     *
     * <p>注册非 Noop {@link Tracer} 会触发 {@link TracerRegistry#enableTracingHook()}，安装<b>全局</b> {@code Hooks.onEachOperator} lift，包装 JVM 中每个 Reactor 操作符（smartwe 的 Studio 集成通过 {@code TelemetryTracer} 启用的旧钩子）。心跳消费者调用 {@code .block()}，与 {@code WakeupDispatcher.drainAndDispatch()} 一致。在 {@code parallel} 非阻塞线程上，{@code Mono.block()} 会抛出 {@code IllegalStateException}；因此 subscribe() 应在 {@code boundedElastic} 的可阻塞线程发出心跳。
     *
     * <p>此测试用于区分旧 {@code Flux.interval(d).subscribeOn(boundedElastic)} 写法与将计时器放到 {@code boundedElastic} 的实现。
     */
    @Test
    void subscribeTickSafeUnderGlobalTracingHook() throws InterruptedException {
        // 非 Noop Tracer 的 register() 会启用全局 onEachOperator lift。
        TracerRegistry.register(new Tracer() {});
        try {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<Throwable> error = new AtomicReference<>();
            Disposable d =
                    bus.subscribe("hook-block-check")
                            .doOnNext(
                                    m -> {
                                        try {
                                            // 模拟 drainAndDispatch() 的阻塞调用。
                                            bus.queuePeek("anything").block();
                                        } catch (Throwable t) {
                                            error.set(t);
                                        }
                                    })
                            .take(1)
                            .subscribe(m -> latch.countDown());
            try {
                assertTrue(latch.await(5, TimeUnit.SECONDS), "no tick within 5s");
                assertNull(error.get(), () -> "blocking on subscribe() tick threw: " + error.get());
            } finally {
                d.dispose();
            }
        } finally {
            // 清理全局钩子，不能影响同一 JVM 的其他测试。
            TracerRegistry.resetToNoop();
        }
    }
}
