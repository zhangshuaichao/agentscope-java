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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.examples.jev.JevSupervisionExample;
import io.agentscope.extensions.judge.jev.supervision.JevSupervisionMiddleware;
import io.agentscope.extensions.judge.jev.supervision.JevTaskSupervisor;
import io.agentscope.extensions.judge.jev.supervision.SupervisionEvidence;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

class JevSupervisionMiddlewareTest {
    static RuntimeContext context(String session) {
        return RuntimeContext.builder().userId("user").sessionId(session).build();
    }

    static JevSupervisionMiddleware middleware(
            Function<SystemOneRequest, Mono<SystemOneResult>> caller,
            List<JevSupervisionMiddleware.Observation> observations,
            int max,
            int failures) {
        return new JevSupervisionMiddleware(
                JevSupervisionTest.supervisor(caller),
                new JevSupervisionMiddleware.Schedule(
                        Duration.ofMillis(10), Duration.ofMillis(30), max, failures),
                (ctx, o) -> observations.add(o));
    }

    static Flux<AgentEvent> invoke(
            JevSupervisionMiddleware m, RuntimeContext ctx, Flux<AgentEvent> events) {
        return m.onAgent(
                null,
                ctx,
                new AgentInput(List.of(new UserMessage("Task " + ctx.getSessionId()))),
                ignored -> events);
    }

    static boolean limitReached(List<JevSupervisionMiddleware.Observation> observations) {
        return observations.stream()
                .anyMatch(o -> o.decision().reason().equals("ASSESSMENT_LIMIT"));
    }

    @Test
    void synchronousWorkerIsSubscribedOnceAndAllEventsAreIdentical() {
        var observations = new CopyOnWriteArrayList<JevSupervisionMiddleware.Observation>();
        // 工作者同步完成，因此应仅触发一次终结评估。
        // 共享辅助器的 10 毫秒周期信号仍可能在同步发出前插入：
        // 负载较高的 -T1C CI 在订阅中途抢占线程，
        // 产生随后被取代的执行中观察。终结信号由工作者完成触发，
        // 不是计时器触发；将周期设得长于测试生命周期，
        // 可从结构上排除周期信号，又不拖慢测试。
        var m =
                new JevSupervisionMiddleware(
                        JevSupervisionTest.supervisor(JevSupervisionExample::syntheticAnswers),
                        new JevSupervisionMiddleware.Schedule(
                                Duration.ofDays(1), Duration.ofDays(1), 5, 3),
                        (ctx, o) -> observations.add(o));
        AtomicInteger starts = new AtomicInteger();
        var first = new TextBlockDeltaEvent("r", "b", "Done");
        var last = new AgentResultEvent(new AssistantMessage("Done"));
        var result =
                invoke(
                                m,
                                context("s"),
                                Flux.defer(
                                        () -> {
                                            starts.incrementAndGet();
                                            return Flux.just(first, last);
                                        }))
                        .collectList()
                        .block(Duration.ofSeconds(8));
        assertEquals(1, starts.get());
        assertSame(first, result.get(0));
        assertSame(last, result.get(1));
        assertEquals(1, observations.size());
        assertEquals(JevTaskSupervisor.Advice.REQUEST_VERIFICATION, observations.get(0).advice());
    }

    @Test
    void disabledAndThrowingObserverNeverAlterWorker() {
        var off =
                new JevTaskSupervisor(
                        r -> {
                            throw new AssertionError();
                        },
                        new JevTaskSupervisor.Thresholds(.2, .8),
                        JevTaskSupervisor.Limits.defaults(),
                        JevSupervisionTest.options(JevExecution.Mode.OFF));
        var m =
                new JevSupervisionMiddleware(
                        off,
                        JevSupervisionMiddleware.Schedule.defaults(),
                        (c, r) -> {
                            throw new IllegalStateException("sink");
                        });
        var event = new AgentResultEvent(new AssistantMessage("done"));
        assertEquals(
                List.of(event), invoke(m, context("s"), Flux.just(event)).collectList().block());
        var enabled =
                new JevSupervisionMiddleware(
                        JevSupervisionTest.supervisor(JevSupervisionExample::syntheticAnswers),
                        JevSupervisionMiddleware.Schedule.defaults(),
                        (c, r) -> {
                            throw new IllegalStateException("sink");
                        });
        assertEquals(
                List.of(event),
                invoke(enabled, context("s"), Flux.just(event)).collectList().block());
    }

    @Test
    void concurrentCallsKeepScopesEvidenceAndCountersSeparate() {
        var observations = new CopyOnWriteArrayList<JevSupervisionMiddleware.Observation>();
        Map<String, String> seen = new ConcurrentHashMap<>();
        // 本测试工作者同步完成，理论上只应触发终结评估。
        // 但共享辅助器的 10 毫秒信号可能在同步输出前插入，
        // 当 CI 抢占订阅线程时，过时执行中观察
        // 会破坏 REVIEW_COMPLETION 断言；将周期设得长于测试生命周期，
        // 可在 -T1C 竞争下保证只评估终态，
        // 不改变所验证行为，也不拖慢测试。
        var m =
                new JevSupervisionMiddleware(
                        JevSupervisionTest.supervisor(
                                r -> {
                                    Map<?, ?> state = (Map<?, ?>) r.state();
                                    seen.put(
                                            state.get("original_job").toString().trim(),
                                            state.get("git_diff").toString());
                                    return JevSupervisionExample.syntheticAnswers(r)
                                            .delayElement(Duration.ofMillis(20));
                                }),
                        new JevSupervisionMiddleware.Schedule(
                                Duration.ofDays(1), Duration.ofDays(1), 5, 3),
                        (ctx, o) -> observations.add(o));
        Function<String, Flux<AgentEvent>> run =
                session -> {
                    var ctx =
                            RuntimeContext.builder()
                                    .userId("user")
                                    .sessionId(session)
                                    .put(
                                            JevSupervisionMiddleware.EvidenceSource.class,
                                            new JevSupervisionMiddleware.EvidenceSource(
                                                    request ->
                                                            Mono.just(
                                                                    new SupervisionEvidence(
                                                                            session,
                                                                            "",
                                                                            "diff-" + session,
                                                                            List.of(),
                                                                            "",
                                                                            false,
                                                                            new SupervisionEvidence
                                                                                    .Verification(
                                                                                    request.scope()
                                                                                            .userId(),
                                                                                    request.scope()
                                                                                            .sessionId(),
                                                                                    request.scope()
                                                                                            .runId(),
                                                                                    session,
                                                                                    "host",
                                                                                    true,
                                                                                    "passed")))))
                                    .build();
                    return invoke(
                            m, ctx, Flux.just(new AgentResultEvent(new AssistantMessage(session))));
                };
        assertEquals(2, Flux.merge(run.apply("a"), run.apply("b")).collectList().block().size());
        assertEquals(Map.of("Task a", "diff-a", "Task b", "diff-b"), seen);
        assertEquals(2, observations.stream().map(o -> o.scope().runId()).distinct().count());
        assertTrue(
                observations.stream()
                        .allMatch(o -> o.advice() == JevTaskSupervisor.Advice.REVIEW_COMPLETION));
    }

    @Test
    void slowJudgeDoesNotBlockEventsAndStaleResultsCannotSuggestCompletion() throws Exception {
        var observations = new CopyOnWriteArrayList<JevSupervisionMiddleware.Observation>();
        var response = Sinks.<SystemOneResult>one();
        CountDownLatch requested = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        var m =
                middleware(
                        r -> {
                            if (calls.incrementAndGet() == 1) {
                                requested.countDown();
                                return response.asMono();
                            }
                            return JevSupervisionExample.syntheticAnswers(r);
                        },
                        observations,
                        4,
                        3);
        var worker = Sinks.many().unicast().<AgentEvent>onBackpressureBuffer();
        List<AgentEvent> emitted = new CopyOnWriteArrayList<>();
        var completion =
                invoke(m, context("s"), worker.asFlux()).doOnNext(emitted::add).then().toFuture();
        assertTrue(requested.await(2, TimeUnit.SECONDS));
        var event = new AgentResultEvent(new AssistantMessage("done"));
        worker.tryEmitNext(event);
        worker.tryEmitComplete();
        assertEquals(List.of(event), emitted);
        Map<String, Answer> answers = new java.util.LinkedHashMap<>();
        JevTaskSupervisor.checks(false).forEach(c -> answers.put(c.key(), new NoulAnswer(.99)));
        response.tryEmitValue(new SystemOneResult("synthetic", answers, new Usage(0, 0)));
        completion.get(8, TimeUnit.SECONDS);
        assertTrue(observations.get(0).superseded());
        assertEquals(JevTaskSupervisor.Advice.CONTINUE, observations.get(0).advice());
        assertEquals(2, calls.get()); // 过时的在途快照之后到来一个新的终结快照。
    }

    @Test
    void cancellationDisposesInFlightRequestAndDoesNotRunFinalAssessment() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1), cancelled = new CountDownLatch(1);
        var m =
                middleware(
                        r -> {
                            calls.incrementAndGet();
                            entered.countDown();
                            return Mono.<SystemOneResult>never().doOnCancel(cancelled::countDown);
                        },
                        new CopyOnWriteArrayList<>(),
                        5,
                        3);
        var subscription = invoke(m, context("s"), Flux.never()).subscribe();
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        subscription.dispose();
        assertTrue(cancelled.await(2, TimeUnit.SECONDS));
        assertEquals(1, calls.get());
    }

    @Test
    void failureBudgetStopsOnlyObservationAndWorkerRunsToCompletion() {
        AtomicInteger calls = new AtomicInteger();
        var observations = new CopyOnWriteArrayList<JevSupervisionMiddleware.Observation>();
        var m =
                middleware(
                        r -> {
                            calls.incrementAndGet();
                            return Mono.error(new IllegalStateException("offline"));
                        },
                        observations,
                        10,
                        2);
        var event = new AgentResultEvent(new AssistantMessage("worker completed"));
        var events =
                invoke(
                                m,
                                context("s"),
                                Mono.delay(Duration.ofMillis(180)).thenMany(Flux.just(event)))
                        .collectList()
                        .block(Duration.ofSeconds(8));
        assertEquals(List.of(event), events);
        assertEquals(2, calls.get());
        assertEquals(JevTaskSupervisor.Advice.CONTINUE, observations.get(0).advice());
        assertEquals(JevTaskSupervisor.Advice.MANUAL_REVIEW, observations.get(1).advice());
    }

    @Test
    void eventBurstsAreCoalescedAndRequestsNeverOverlapOrExceedLimit() {
        AtomicInteger active = new AtomicInteger(),
                maxActive = new AtomicInteger(),
                calls = new AtomicInteger();
        var observations = new CopyOnWriteArrayList<JevSupervisionMiddleware.Observation>();
        var m =
                middleware(
                        r ->
                                Mono.defer(
                                        () -> {
                                            calls.incrementAndGet();
                                            maxActive.accumulateAndGet(
                                                    active.incrementAndGet(), Math::max);
                                            return JevSupervisionExample.syntheticAnswers(r)
                                                    .delayElement(Duration.ofMillis(25))
                                                    .doOnNext(ignored -> active.decrementAndGet());
                                        }),
                        observations,
                        2,
                        3);
        // 必须让工作者保持活动直到限制决定被发布：固定事件
        // 数量会与评估流水线竞争；并行调度器停顿时，工作者
        // 可能在第二次评估完成前结束，终结信号随后占用
        // 剩余评估槽，而不是触发限制，导致
        // ASSESSMENT_LIMIT 永远不发布。按已发布决定结束工作者，
        // 可排除此竞态；事件上限仅兜住真正损坏的流水线。
        var emitted = new AtomicInteger();
        var worker =
                Flux.interval(Duration.ofMillis(1))
                        .takeWhile(t -> !limitReached(observations))
                        .take(2_000)
                        .doOnNext(t -> emitted.incrementAndGet())
                        .map(i -> (AgentEvent) new TextBlockDeltaEvent("r", "b", "x"));
        long count = invoke(m, context("s"), worker).count().block(Duration.ofSeconds(8));
        assertEquals(emitted.get(), count);
        assertEquals(2, calls.get());
        assertEquals(1, maxActive.get());
        assertTrue(limitReached(observations));
    }

    @Test
    void workerFailureIsNotSwallowedOrRetried() {
        var m =
                middleware(
                        JevSupervisionExample::syntheticAnswers,
                        new CopyOnWriteArrayList<>(),
                        5,
                        3);
        RuntimeException failure = new IllegalStateException("worker failure");
        AtomicInteger calls = new AtomicInteger();
        StepVerifier.create(
                        invoke(
                                m,
                                context("s"),
                                Flux.defer(
                                        () -> {
                                            calls.incrementAndGet();
                                            return Flux.error(failure);
                                        })))
                .expectErrorMatches(e -> e == failure)
                .verify();
        assertEquals(1, calls.get());
    }

    @Test
    void runtimeIterationLimitCannotTurnIntoCompletionEvenWithPositiveJudgments() {
        var observations = new CopyOnWriteArrayList<JevSupervisionMiddleware.Observation>();
        // 与上面的 synchronousWorkerIsSubscribedOnce 同样，只允许终态评估；
        // 若周期信号早于同步 ExceedMaxItersEvent，
        // 就会先于终结 WORKER_FAILED 发出执行中的 CONTINUE。
        var m =
                new JevSupervisionMiddleware(
                        JevSupervisionTest.supervisor(JevSupervisionExample::syntheticAnswers),
                        new JevSupervisionMiddleware.Schedule(
                                Duration.ofDays(1), Duration.ofDays(1), 5, 3),
                        (ctx, o) -> observations.add(o));
        invoke(
                        m,
                        context("s"),
                        Flux.just(new io.agentscope.core.event.ExceedMaxItersEvent("r", 2, 2)))
                .blockLast(Duration.ofSeconds(8));
        assertEquals(JevTaskSupervisor.Advice.MANUAL_REVIEW, observations.get(0).advice());
        assertEquals("WORKER_FAILED", observations.get(0).decision().reason());
    }

    @Test
    void humanAdviceDoesNotInterruptOrReplayTheWorker() {
        AtomicInteger executions = new AtomicInteger();
        var observations = new CopyOnWriteArrayList<JevSupervisionMiddleware.Observation>();
        var m =
                middleware(
                        JevSupervisionTest.answers(
                                Map.of("core.human-escalation__needs_human", .99)),
                        observations,
                        5,
                        3);
        var result = new AgentResultEvent(new AssistantMessage("done"));
        assertEquals(
                List.of(result),
                invoke(
                                m,
                                context("s"),
                                Flux.defer(
                                        () -> {
                                            executions.incrementAndGet();
                                            return Flux.just(result);
                                        }))
                        .collectList()
                        .block());
        assertEquals(1, executions.get());
        assertEquals(JevTaskSupervisor.Advice.MANUAL_REVIEW, observations.get(0).advice());
    }

    @Test
    void periodicChecksContinueWithoutNewWorkerEvents() {
        AtomicInteger calls = new AtomicInteger();
        var m =
                middleware(
                        r -> {
                            calls.incrementAndGet();
                            return JevSupervisionExample.syntheticAnswers(r);
                        },
                        new CopyOnWriteArrayList<>(),
                        3,
                        3);
        invoke(m, context("s"), Mono.delay(Duration.ofMillis(170)).thenMany(Flux.empty()))
                .blockLast(Duration.ofSeconds(8));
        assertEquals(3, calls.get());
    }
}
