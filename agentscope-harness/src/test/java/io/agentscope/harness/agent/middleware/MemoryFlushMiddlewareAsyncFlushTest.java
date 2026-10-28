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
package io.agentscope.harness.agent.middleware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.memory.MemoryBackgroundTasks;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/** 验证 {@link MemoryFlushMiddleware#onAgent} 在记忆刷新完成前结束智能体流：刷新为发出后不等待，慢速提取不得阻塞调用方；同时检查相同隔离键的并发刷新串行执行。 */
class MemoryFlushMiddlewareAsyncFlushTest {

    @Test
    void onCompleteFiresBeforeAsyncFlushCompletes() throws Exception {
        // 用闩锁阻挡刷新流，使其在智能体流完成时仍在途；
        // 测试末尾释放闩锁，避免后台任务及其
        // 在途追踪器泄漏到其他测试。
        CountDownLatch flushStarted = new CountDownLatch(1);
        CountDownLatch flushFinishGate = new CountDownLatch(1);
        AtomicReference<List<Msg>> flushedMessages = new AtomicReference<>();
        Model gatedModel =
                new Model() {
                    @Override
                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        flushedMessages.set(messages);
                        flushStarted.countDown();
                        return Flux.defer(
                                () -> {
                                    try {
                                        flushFinishGate.await(5, TimeUnit.SECONDS);
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                    }
                                    return Flux.just(stubChunk());
                                });
                    }

                    @Override
                    public String getModelName() {
                        return "gated-model";
                    }
                };

        Msg userMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .textContent("remember: deploys happen on Fridays")
                        .build();
        AgentState state = AgentState.builder().addMessage(userMsg).build();
        RuntimeContext rc = RuntimeContext.builder().agentState(state).build();
        MemoryFlushMiddleware middleware = new MemoryFlushMiddleware(null, gatedModel);

        AgentEndEvent event = new AgentEndEvent("reply-1");
        try {
            // 刷新仍被闩锁阻挡时，智能体流必须完成。
            List<AgentEvent> emitted;
            try {
                emitted =
                        middleware
                                .onAgent(
                                        null,
                                        rc,
                                        new AgentInput(List.of(userMsg)),
                                        input -> Flux.just(event))
                                .collectList()
                                .block(Duration.ofSeconds(2));
            } catch (Exception e) {
                throw new AssertionError(
                        "agent stream must complete before the (gated) flush does", e);
            }

            assertEquals(List.of(event), emitted, "agent events must be passed through unchanged");
            assertTrue(
                    flushStarted.await(5, TimeUnit.SECONDS),
                    "async flush must still be triggered after the stream completes");
            assertTrue(
                    flushedMessages.get() != null && !flushedMessages.get().isEmpty(),
                    "flush must receive the conversation messages");
        } finally {
            // 释放闩锁，使后台任务及其在途追踪器停止活动。
            flushFinishGate.countDown();
        }
        assertTrue(
                MemoryBackgroundTasks.awaitQuiescence(5, TimeUnit.SECONDS),
                "released async flush must finish");
    }

    @Test
    void flushesForSameIsolationKeyRunSerially() throws Exception {
        // 两次模型调用均由测试闩锁控制；第一次仍在途时，第二次
        // 不得启动，因为相同 userId 对应相同隔离键。
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        CountDownLatch[] started = {new CountDownLatch(1), new CountDownLatch(1)};
        CountDownLatch[] release = {new CountDownLatch(1), new CountDownLatch(1)};
        Model gatedModel =
                new Model() {
                    @Override
                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        int i = calls.getAndIncrement();
                        return Flux.defer(
                                () -> {
                                    int now = concurrent.incrementAndGet();
                                    maxConcurrent.accumulateAndGet(now, Math::max);
                                    started[i].countDown();
                                    try {
                                        release[i].await(10, TimeUnit.SECONDS);
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                    }
                                    concurrent.decrementAndGet();
                                    return Flux.just(stubChunk());
                                });
                    }

                    @Override
                    public String getModelName() {
                        return "gated-model";
                    }
                };

        Msg userMsg =
                Msg.builder().role(MsgRole.USER).textContent("remember: deploy Fridays").build();
        AgentState state = AgentState.builder().addMessage(userMsg).build();
        RuntimeContext rc = RuntimeContext.builder().userId("alice").agentState(state).build();
        MemoryFlushMiddleware middleware = new MemoryFlushMiddleware(null, gatedModel);
        AgentInput input = new AgentInput(List.of(userMsg));
        AgentEndEvent event = new AgentEndEvent("reply-1");

        try {
            // 第 1 轮：刷新开始并等待闩锁。
            middleware
                    .onAgent(null, rc, input, in -> Flux.just(event))
                    .collectList()
                    .block(Duration.ofSeconds(2));
            assertTrue(started[0].await(5, TimeUnit.SECONDS), "first flush must run");

            // 第 2 轮（同键）：排在仍在运行的首次刷新之后。
            middleware
                    .onAgent(null, rc, input, in -> Flux.just(event))
                    .collectList()
                    .block(Duration.ofSeconds(2));
            assertFalse(
                    started[1].await(250, TimeUnit.MILLISECONDS),
                    "second flush must not start while the first is in flight");

            // 释放第一次，随后执行排队的第二次。
            release[0].countDown();
            assertTrue(
                    started[1].await(5, TimeUnit.SECONDS),
                    "queued second flush must run once the first finishes");
            release[1].countDown();
            assertTrue(
                    MemoryBackgroundTasks.awaitQuiescence(5, TimeUnit.SECONDS),
                    "all flushes must quiesce after release");
        } finally {
            release[0].countDown();
            release[1].countDown();
            MemoryBackgroundTasks.awaitQuiescence(10, TimeUnit.SECONDS);
        }
        assertEquals(
                1, maxConcurrent.get(), "at most one flush per isolation key may run at a time");
    }

    @Test
    void backToBackSameSessionFlushesCoalesceIntoOnePendingTask() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch[] started = {
            new CountDownLatch(1), new CountDownLatch(1), new CountDownLatch(1)
        };
        CountDownLatch[] release = {
            new CountDownLatch(1), new CountDownLatch(1), new CountDownLatch(1)
        };
        Model gatedModel = gatedModel(calls, started, release);

        Msg userMsg =
                Msg.builder().role(MsgRole.USER).textContent("remember: deploy Fridays").build();
        AgentState state = AgentState.builder().addMessage(userMsg).build();
        RuntimeContext rc =
                RuntimeContext.builder()
                        .userId("alice")
                        .sessionId("session1")
                        .agentState(state)
                        .build();
        MemoryFlushMiddleware middleware = new MemoryFlushMiddleware(null, gatedModel);
        AgentInput input = new AgentInput(List.of(userMsg));
        AgentEndEvent event = new AgentEndEvent("reply-1");

        try {
            // 第 1 轮：刷新开始并等待闩锁。
            middleware
                    .onAgent(null, rc, input, in -> Flux.just(event))
                    .collectList()
                    .block(Duration.ofSeconds(2));
            assertTrue(started[0].await(5, TimeUnit.SECONDS), "first flush must run");

            // 同会话的第 2、3 轮在当前刷新之后排队，应合并为
            // 一个待执行任务；排队刷新在执行时快照对话，
            // 因此最新任务涵盖此前排队任务需要提取的全部内容。
            middleware
                    .onAgent(null, rc, input, in -> Flux.just(event))
                    .collectList()
                    .block(Duration.ofSeconds(2));
            middleware
                    .onAgent(null, rc, input, in -> Flux.just(event))
                    .collectList()
                    .block(Duration.ofSeconds(2));
            assertFalse(
                    started[1].await(250, TimeUnit.MILLISECONDS),
                    "queued flushes must not start while the first is in flight");

            // 释放第一次，随后只运行一次合并后的刷新。
            release[0].countDown();
            assertTrue(
                    started[1].await(5, TimeUnit.SECONDS),
                    "coalesced flush must run once the first finishes");
            release[1].countDown();
            assertTrue(
                    MemoryBackgroundTasks.awaitQuiescence(5, TimeUnit.SECONDS),
                    "flushes must quiesce after release");
            assertEquals(
                    2,
                    calls.get(),
                    "same-session back-to-back flushes must coalesce into one pending task");
        } finally {
            for (CountDownLatch latch : release) {
                latch.countDown();
            }
            MemoryBackgroundTasks.awaitQuiescence(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void sameUserDifferentSessionsEachKeepTheirPendingFlush() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch[] started = {
            new CountDownLatch(1), new CountDownLatch(1), new CountDownLatch(1)
        };
        CountDownLatch[] release = {
            new CountDownLatch(1), new CountDownLatch(1), new CountDownLatch(1)
        };
        Model gatedModel = gatedModel(calls, started, release);

        Msg userMsg =
                Msg.builder().role(MsgRole.USER).textContent("remember: deploy Fridays").build();
        RuntimeContext rcA =
                RuntimeContext.builder()
                        .userId("alice")
                        .sessionId("sessionA")
                        .agentState(AgentState.builder().addMessage(userMsg).build())
                        .build();
        RuntimeContext rcB =
                RuntimeContext.builder()
                        .userId("alice")
                        .sessionId("sessionB")
                        .agentState(AgentState.builder().addMessage(userMsg).build())
                        .build();
        MemoryFlushMiddleware middleware = new MemoryFlushMiddleware(null, gatedModel);
        AgentInput input = new AgentInput(List.of(userMsg));
        AgentEndEvent event = new AgentEndEvent("reply-1");

        try {
            // 会话 A：刷新开始并等待闩锁。
            middleware
                    .onAgent(null, rcA, input, in -> Flux.just(event))
                    .collectList()
                    .block(Duration.ofSeconds(2));
            assertTrue(started[0].await(5, TimeUnit.SECONDS), "session A's first flush must run");

            // 会话 B、A 在其后排队，B 的第二轮合并到 B 的待执行任务；
            // 但 A 的待执行任务必须保留，因为属于另一段对话。
            middleware
                    .onAgent(null, rcB, input, in -> Flux.just(event))
                    .collectList()
                    .block(Duration.ofSeconds(2));
            middleware
                    .onAgent(null, rcA, input, in -> Flux.just(event))
                    .collectList()
                    .block(Duration.ofSeconds(2));
            middleware
                    .onAgent(null, rcB, input, in -> Flux.just(event))
                    .collectList()
                    .block(Duration.ofSeconds(2));
            assertFalse(
                    started[1].await(250, TimeUnit.MILLISECONDS),
                    "queued flushes must not start while the first is in flight");

            release[0].countDown();
            assertTrue(
                    started[1].await(5, TimeUnit.SECONDS),
                    "first queued flush must run once the running one finishes");
            release[1].countDown();
            assertTrue(
                    started[2].await(5, TimeUnit.SECONDS),
                    "second queued flush must run afterwards");
            release[2].countDown();
            assertTrue(
                    MemoryBackgroundTasks.awaitQuiescence(5, TimeUnit.SECONDS),
                    "flushes must quiesce after release");
            assertEquals(
                    3,
                    calls.get(),
                    "one flush per distinct session must still run — coalescing is per session,"
                            + " not per isolation key");
        } finally {
            for (CountDownLatch latch : release) {
                latch.countDown();
            }
            MemoryBackgroundTasks.awaitQuiescence(10, TimeUnit.SECONDS);
        }
    }

    /** 本类各占位模型解除阻挡后发出的唯一流式分块。 */
    private static ChatResponse stubChunk() {
        return new ChatResponse(
                "stub-id",
                List.of(TextBlock.builder().text("extracted memory").build()),
                null,
                Map.of(),
                "stop");
    }

    /** 第 {@code i} 次调用会触发 {@code started[i]} 并等待 {@code release[i]} 的模型。 */
    private static Model gatedModel(
            AtomicInteger calls, CountDownLatch[] started, CountDownLatch[] release) {
        return new Model() {
            @Override
            public Flux<ChatResponse> stream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                int i = calls.getAndIncrement();
                return Flux.defer(
                        () -> {
                            started[i].countDown();
                            try {
                                release[i].await(10, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            return Flux.just(stubChunk());
                        });
            }

            @Override
            public String getModelName() {
                return "gated-model";
            }
        };
    }
}
