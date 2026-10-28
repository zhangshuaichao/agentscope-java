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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.AgentRun;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.session.InMemorySessionLogStore;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

/** 验证压缩回退不会吞掉中断，也不会重复执行下游推理。 */
class CompactionMiddlewareTest {
    @Test
    void nativeOverflowRecoveryKeepsOneExecutionAndRecordsBothModelCalls() {
        AtomicInteger calls = new AtomicInteger();
        ChatModelBase model =
                new ChatModelBase() {
                    public String getModelName() {
                        return "overflow-once";
                    }

                    protected Flux<ChatResponse> doStream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        if (calls.getAndIncrement() == 0)
                            return Flux.error(new IllegalStateException("context_length_exceeded"));
                        return Flux.just(
                                ChatResponse.builder()
                                        .content(
                                                List.of(TextBlock.builder().text("answer").build()))
                                        .build());
                    }
                };
        var recovery = new CompactionMiddleware(null, new SuccessfulSummaryModel(), fixedConfig());
        var agent =
                ReActAgent.builder()
                        .name("overflow-test")
                        .model(model)
                        .sessionLogStore(new InMemorySessionLogStore())
                        .middleware(recovery.overflowRecovery())
                        .build();
        var ctx = context("user", "overflow-session");
        var run =
                agent.prepareCall(
                        List.of(userMessage("old context"), userMessage("latest request")), ctx);
        assertEquals("answer", run.stream().blockLast(Duration.ofSeconds(10)).getTextContent());
        assertEquals(AgentRun.Status.COMPLETED, run.status());
        assertEquals(2, calls.get());
        var facts = agent.sessionLog(ctx).readAfter(0, 1000);
        assertEquals(1, facts.stream().filter(e -> e.type().equals("run/start")).count());
        assertEquals(1, facts.stream().filter(e -> e.type().equals("run/end")).count());
        assertTrue(facts.stream().allMatch(e -> run.runId().equals(e.executionRunId())));
        assertTrue(facts.stream().filter(e -> e.type().equals("model/end")).count() >= 2);
    }

    /** 普通压缩失败时跳过压缩，仅调用一次原推理。 */
    @Test
    void ordinaryCompactionFailureFallsBackToOriginalInputOnce() {
        AtomicInteger nextCalls = new AtomicInteger();
        CompactionMiddleware middleware =
                new CompactionMiddleware(
                        null,
                        new SynchronousFailingSummaryModel(
                                new IllegalStateException("summary provider unavailable")),
                        fixedConfig());

        StepVerifier.create(
                        middleware.onReasoning(
                                agent(),
                                context("user", "session"),
                                input(),
                                next -> {
                                    nextCalls.incrementAndGet();
                                    return Flux.empty();
                                }))
                .verifyComplete();

        assertEquals(1, nextCalls.get());
    }

    /** 摘要失败不得用失败占位文本替换历史。 */
    @Test
    void ordinarySummaryFailurePreservesOriginalInput() {
        AtomicInteger nextCalls = new AtomicInteger();
        CompactionMiddleware middleware =
                new CompactionMiddleware(
                        null,
                        new FailingSummaryModel(
                                new IllegalStateException("summary provider unavailable")),
                        fixedConfig());

        StepVerifier.create(
                        middleware.onReasoning(
                                agent(),
                                context("user", "session"),
                                input(),
                                next -> {
                                    nextCalls.incrementAndGet();
                                    assertEquals(input().messages().size(), next.messages().size());
                                    assertEquals(
                                            input().messages().get(0).getTextContent(),
                                            next.messages().get(0).getTextContent());
                                    return Flux.empty();
                                }))
                .verifyComplete();

        assertEquals(1, nextCalls.get());
    }

    /** 实际压缩摘要期间的中断必须传播，不能进入推理。 */
    @Test
    void interruptedSummaryCompactionPropagatesWithoutCallingNext() {
        AtomicInteger nextCalls = new AtomicInteger();
        CompactionMiddleware middleware =
                new CompactionMiddleware(
                        null,
                        new FailingSummaryModel(
                                new InterruptedException("interrupted while compacting")),
                        fixedConfig());

        StepVerifier.create(
                        middleware.onReasoning(
                                agent(),
                                context("user", "session"),
                                input(),
                                next -> {
                                    nextCalls.incrementAndGet();
                                    return Flux.empty();
                                }))
                .expectError(InterruptedException.class)
                .verify();

        assertEquals(0, nextCalls.get());
    }

    /** 实际压缩摘要的异步中断不能降级为继续推理。 */
    @Test
    void asynchronousInterruptedSummaryCompactionPropagatesWithoutCallingNext() {
        AtomicInteger nextCalls = new AtomicInteger();
        CompactionMiddleware middleware =
                new CompactionMiddleware(
                        null,
                        new AsyncFailingSummaryModel(
                                new InterruptedException("interrupted while compacting")),
                        fixedConfig());

        StepVerifier.create(
                        middleware.onReasoning(
                                agent(),
                                context("user", "session"),
                                input(),
                                next -> {
                                    nextCalls.incrementAndGet();
                                    return Flux.empty();
                                }))
                .expectError(InterruptedException.class)
                .verify();

        assertEquals(0, nextCalls.get());
    }

    /** 被 Reactor 或应用异常包装的用户中断也必须传播。 */
    @Test
    void wrappedInterruptedCompactionPropagatesWithoutCallingNext() {
        AtomicInteger nextCalls = new AtomicInteger();
        CompactionMiddleware middleware =
                new CompactionMiddleware(
                        null,
                        new FailingSummaryModel(
                                new IllegalStateException(
                                        "compaction wrapper",
                                        new InterruptedException("interrupted while compacting"))),
                        fixedConfig());

        StepVerifier.create(
                        middleware.onReasoning(
                                agent(),
                                context("user", "session"),
                                input(),
                                next -> {
                                    nextCalls.incrementAndGet();
                                    return Flux.empty();
                                }))
                .expectErrorMatches(
                        error ->
                                error instanceof IllegalStateException
                                        && error.getCause() instanceof InterruptedException)
                .verify();

        assertEquals(0, nextCalls.get());
    }

    /** 异常原因链存在环时，检测必须终止并保留普通压缩回退行为。 */
    @Test
    void cyclicCompactionFailureCauseFallsBackWithoutLooping() {
        AtomicInteger nextCalls = new AtomicInteger();
        CompactionMiddleware middleware =
                new CompactionMiddleware(
                        null, new FailingSummaryModel(new CyclicCauseException()), fixedConfig());

        StepVerifier.create(
                        middleware.onReasoning(
                                agent(),
                                context("user", "session"),
                                input(),
                                next -> {
                                    nextCalls.incrementAndGet();
                                    return Flux.empty();
                                }))
                .verifyComplete();

        assertEquals(1, nextCalls.get());
    }

    /** 下游推理中断必须传播，不能通过回退重试。 */
    @Test
    void interruptedDownstreamPropagatesWithoutRetryingNext() {
        AtomicInteger nextCalls = new AtomicInteger();
        CompactionMiddleware middleware =
                new CompactionMiddleware(null, new SuccessfulSummaryModel(), fixedConfig());

        StepVerifier.create(
                        middleware.onReasoning(
                                agent(),
                                context("user", "session"),
                                input(),
                                next -> {
                                    nextCalls.incrementAndGet();
                                    return Flux.error(
                                            new InterruptedException(
                                                    "interrupted while reasoning"));
                                }))
                .expectError(InterruptedException.class)
                .verify();

        assertEquals(1, nextCalls.get());
    }

    /** 一个并发会话被中断，不应阻止另一个会话进入推理。 */
    @Test
    void concurrentSessionInterruptDoesNotAffectOtherSession() {
        AtomicInteger interruptedNextCalls = new AtomicInteger();
        AtomicInteger activeNextCalls = new AtomicInteger();
        Set<String> compactedSessions = ConcurrentHashMap.newKeySet();
        CompactionMiddleware middleware =
                new CompactionMiddleware(
                        null, new PerSessionSummaryModel(compactedSessions), fixedConfig());

        Flux<AgentEvent> interrupted =
                middleware.onReasoning(
                        agent(),
                        context("user-a", "session-a"),
                        input("session-a previous", "session-a latest"),
                        next -> {
                            interruptedNextCalls.incrementAndGet();
                            return Flux.empty();
                        });
        Flux<AgentEvent> active =
                middleware.onReasoning(
                        agent(),
                        context("user-b", "session-b"),
                        input("session-b previous", "session-b latest"),
                        next -> {
                            activeNextCalls.incrementAndGet();
                            return Flux.empty();
                        });

        Flux.mergeDelayError(
                        2,
                        interrupted.materialize().subscribeOn(Schedulers.parallel()),
                        active.materialize().subscribeOn(Schedulers.parallel()))
                .collectList()
                .block();

        assertEquals(Set.of("session-a", "session-b"), compactedSessions);
        assertEquals(0, interruptedNextCalls.get());
        assertEquals(1, activeNextCalls.get());
        assertTrue(compactedSessions.contains("session-b"));
    }

    /** 常规模型流的中断应只发起一次请求，并以 INTERRUPTED 结束。 */
    @Test
    void modelStreamInterruptProducesOneCallAndInterruptedTerminalReason() throws Exception {
        CountDownLatch subscribed = new CountDownLatch(1);
        CountingDelayedFirstChunkModel model = new CountingDelayedFirstChunkModel(subscribed);
        CompactionMiddleware middleware =
                new CompactionMiddleware(null, model, noCompactionConfig());
        ReActAgent agent =
                ReActAgent.builder()
                        .name("compaction-test-agent")
                        .sysPrompt("test")
                        .model(model)
                        .middleware(middleware)
                        .build();
        RuntimeContext context = context("user", "session");

        var reply =
                agent.call(
                                List.of(
                                        Msg.builder()
                                                .role(MsgRole.USER)
                                                .textContent("test")
                                                .build()),
                                context)
                        .subscribeOn(Schedulers.parallel())
                        .toFuture();
        assertTrue(subscribed.await(5, TimeUnit.SECONDS), "model stream should start");
        agent.interrupt(context);

        assertEquals(
                GenerateReason.INTERRUPTED, reply.get(5, TimeUnit.SECONDS).getGenerateReason());
        assertEquals(1, model.callCount.get());
    }

    /** 中断一个并发用户会话，不应取消另一会话的模型流。 */
    @Test
    void interruptingOneSessionDoesNotCancelConcurrentSession() throws Exception {
        CountDownLatch subscribed = new CountDownLatch(2);
        CountingDelayedFirstChunkModel model = new CountingDelayedFirstChunkModel(subscribed);
        CompactionMiddleware middleware =
                new CompactionMiddleware(null, model, noCompactionConfig());
        ReActAgent agent =
                ReActAgent.builder()
                        .name("compaction-test-agent")
                        .sysPrompt("test")
                        .model(model)
                        .middleware(middleware)
                        .build();
        RuntimeContext interruptedContext = context("user-a", "session-a");
        RuntimeContext activeContext = context("user-b", "session-b");

        var interruptedReply =
                agent.call(List.of(userMessage("interrupt me")), interruptedContext)
                        .subscribeOn(Schedulers.parallel())
                        .toFuture();
        var activeReply =
                agent.call(List.of(userMessage("finish normally")), activeContext)
                        .subscribeOn(Schedulers.parallel())
                        .toFuture();
        assertTrue(subscribed.await(5, TimeUnit.SECONDS), "both model streams should start");
        agent.interrupt(interruptedContext);

        assertEquals(
                GenerateReason.INTERRUPTED,
                interruptedReply.get(5, TimeUnit.SECONDS).getGenerateReason());
        assertFalse(
                activeReply.get(5, TimeUnit.SECONDS).getGenerateReason()
                        == GenerateReason.INTERRUPTED);
        assertEquals(2, model.callCount.get());
    }

    /** 创建稳定进入压缩分支的配置。 */
    private CompactionConfig fixedConfig() {
        return CompactionConfig.builder()
                .triggerTokens(1)
                .keepTokens(1)
                .flushBeforeCompact(false)
                .prune(null)
                .build();
    }

    /** 创建绕过压缩的稳定配置，用于模型流中断测试。 */
    private CompactionConfig noCompactionConfig() {
        return CompactionConfig.builder()
                .triggerMessages(0)
                .triggerTokens(Integer.MAX_VALUE)
                .keepTokens(0)
                .build();
    }

    /** 创建中间件所需的 ReActAgent 测试替身。 */
    private ReActAgent agent() {
        ReActAgent agent = mock(ReActAgent.class);
        when(agent.getName()).thenReturn("compaction-test-agent");
        return agent;
    }

    /** 创建按用户和会话隔离的运行时上下文。 */
    private RuntimeContext context(String userId, String sessionId) {
        return RuntimeContext.builder().userId(userId).sessionId(sessionId).build();
    }

    /** 创建只含一条用户消息的最小输入。 */
    private ReasoningInput input() {
        return input("previous context", "latest request");
    }

    /** 创建足够多的对话消息，使压缩能够截取前缀。 */
    private ReasoningInput input(String first, String second) {
        return new ReasoningInput(
                List.of(userMessage(first), userMessage(second)), List.of(), null);
    }

    /** 创建单会话及并发会话测试共用的最小用户消息。 */
    private Msg userMessage(String text) {
        return Msg.builder().role(MsgRole.USER).textContent(text).build();
    }

    /** 在创建摘要发布者时抛错，以覆盖外层压缩回退。 */
    private static final class SynchronousFailingSummaryModel extends ChatModelBase {
        private final RuntimeException error;

        private SynchronousFailingSummaryModel(RuntimeException error) {
            this.error = error;
        }

        @Override
        public String getModelName() {
            return "synchronous-failing-summary";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            throw error;
        }
    }

    /** 提供只发出一个成功摘要分块的模型。 */
    private static final class SuccessfulSummaryModel extends ChatModelBase {

        @Override
        public String getModelName() {
            return "successful-summary";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.just(
                    ChatResponse.builder()
                            .content(List.of(TextBlock.builder().text("summary").build()))
                            .build());
        }
    }

    /** 提供实际摘要模型错误，以测试压缩边界。 */
    private static class FailingSummaryModel extends ChatModelBase {
        private final Throwable error;

        private FailingSummaryModel(Throwable error) {
            this.error = error;
        }

        @Override
        public String getModelName() {
            return "failing-summary";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.error(error);
        }
    }

    /** 提供异步摘要模型错误，覆盖在途中断。 */
    private static final class AsyncFailingSummaryModel extends FailingSummaryModel {

        private AsyncFailingSummaryModel(Throwable error) {
            super(error);
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return super.doStream(messages, tools, options)
                    .delaySubscription(Duration.ofMillis(20));
        }
    }

    /** 让一个会话的摘要失败，同时允许另一会话完成。 */
    private static final class PerSessionSummaryModel extends ChatModelBase {
        private final Set<String> compactedSessions;

        private PerSessionSummaryModel(Set<String> compactedSessions) {
            this.compactedSessions = compactedSessions;
        }

        @Override
        public String getModelName() {
            return "per-session-summary";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            String rendered = messages.get(0).getTextContent();
            if (rendered.contains("session-a")) {
                compactedSessions.add("session-a");
                return Flux.error(new InterruptedException("interrupted session-a"));
            }
            compactedSessions.add("session-b");
            return Flux.just(
                    ChatResponse.builder()
                            .content(List.of(TextBlock.builder().text("summary").build()))
                            .build());
        }
    }

    /** 提供可在首分块前中断的模型，以验证真实 ReAct 流请求次数。 */
    private static final class CountingDelayedFirstChunkModel extends ChatModelBase {
        private final CountDownLatch subscribed;
        private final AtomicInteger callCount = new AtomicInteger();

        private CountingDelayedFirstChunkModel(CountDownLatch subscribed) {
            this.subscribed = subscribed;
        }

        @Override
        public String getModelName() {
            return "counting-delayed-first-chunk";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.defer(
                    () -> {
                        callCount.incrementAndGet();
                        subscribed.countDown();
                        return Flux.just(
                                        ChatResponse.builder()
                                                .content(
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text("reply")
                                                                        .build()))
                                                .build())
                                .delaySubscription(Duration.ofMillis(200));
                    });
        }
    }

    /** 提供异常原因环，验证中断检测不会无限循环。 */
    private static final class CyclicCauseException extends RuntimeException {

        @Override
        public synchronized Throwable getCause() {
            return this;
        }
    }
}
