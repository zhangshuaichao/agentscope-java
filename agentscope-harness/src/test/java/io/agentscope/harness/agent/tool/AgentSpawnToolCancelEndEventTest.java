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
package io.agentscope.harness.agent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventEmitter;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.middleware.SubagentEntry;
import io.agentscope.harness.agent.subagent.DefaultAgentManager;
import io.agentscope.harness.agent.subagent.task.BackgroundTask;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.subagent.task.TaskRunSpec;
import io.agentscope.harness.agent.subagent.task.TaskStatus;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.Mockito;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

/**
 * 同步派生子智能体的 {@code AgentStartEvent} / {@code AgentEndEvent} 配对回归测试。
 *
 * <p>原问题中，{@code AgentSpawnTool} 在订阅子级前无条件发出 {@link AgentStartEvent}，但只在终止回调发出配对的 {@link AgentEndEvent}。Reactor 的 {@code doOnTerminate} 仅响应 {@code onComplete} / {@code onError}，不响应取消；父级被用户停止、外层 {@code timeout()} 或上游取消时，消费者只有开始事件而没有结束事件，会一直显示子智能体运行中。
 *
 * <p>此问题不同于子级执行本身是否停止：#2408 / #2412 修复了对 {@code HarnessAgent} 的 {@code interruptAgent} 无操作问题；即使执行已正确中断，事件流仍必须配对结束。
 */
@DisplayName("AgentSpawnTool parent-cancel: subagent event stream must close")
class AgentSpawnToolCancelEndEventTest {

    /** 收集工具发到父级流的全部事件。 */
    private static final class RecordingEmitter implements AgentEventEmitter {
        private final List<AgentEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void emit(AgentEvent event) {
            events.add(event);
        }

        long count(Class<? extends AgentEvent> type) {
            return events.stream().filter(type::isInstance).count();
        }

        <T extends AgentEvent> T first(Class<T> type) {
            return events.stream()
                    .filter(type::isInstance)
                    .map(type::cast)
                    .findFirst()
                    .orElseThrow();
        }
    }

    @Test
    @DisplayName("parent cancel still emits AgentEndEvent for the spawned child")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void parentCancel_emitsAgentEndEvent() throws Exception {
        CountDownLatch childStarted = new CountDownLatch(1);

        ReActAgent delegate = Mockito.mock(ReActAgent.class);
        HarnessAgent harness = Mockito.mock(HarnessAgent.class);
        Mockito.when(harness.getDelegate()).thenReturn(delegate);
        // 子级不会自行完成，因此此 Mono 只能通过取消终止。
        Mockito.when(harness.call(any(Msg.class), any(RuntimeContext.class)))
                .thenReturn(Mono.<Msg>never().doOnSubscribe(ignored -> childStarted.countDown()));

        DefaultAgentManager manager =
                new DefaultAgentManager(
                        List.of(new SubagentEntry("harness_agent", "Harness child", rc -> harness)),
                        null);
        AgentSpawnTool tool = new AgentSpawnTool(manager, new NoopTaskRepository(), 0);
        RuntimeContext parentCtx =
                RuntimeContext.builder().sessionId("parent-session").userId("parent-user").build();

        RecordingEmitter emitter = new RecordingEmitter();

        Disposable subscription =
                tool.agentSpawn(parentCtx, null, "harness_agent", "work", null, 30, null)
                        .contextWrite(ctx -> ctx.put(AgentEventEmitter.CONTEXT_KEY, emitter))
                        .subscribe();

        assertTrue(childStarted.await(5, TimeUnit.SECONDS), "child should have started");
        assertEquals(
                1,
                emitter.count(AgentStartEvent.class),
                "a start event should have been emitted for the spawned child");

        subscription.dispose();

        // 使用有界等待而非固定休眠，结束事件一到就返回，
        // 也避免慢 CI 机器延迟取消回调导致测试不稳定。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (emitter.count(AgentEndEvent.class) == 0 && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }

        assertEquals(
                1,
                emitter.count(AgentEndEvent.class),
                "parent cancel must still close the child's event stream — an AgentStartEvent"
                        + " without a matching AgentEndEvent leaves consumers rendering the"
                        + " subagent as running forever (doOnTerminate does not fire on cancel)");
        assertReplyIdPair(emitter);
    }

    @Test
    @DisplayName("normal completion emits exactly one start and one end")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void normalCompletion_emitsPairedEvents() throws Exception {
        ReActAgent delegate = Mockito.mock(ReActAgent.class);
        HarnessAgent harness = Mockito.mock(HarnessAgent.class);
        Mockito.when(harness.getDelegate()).thenReturn(delegate);
        Mockito.when(harness.call(any(Msg.class), any(RuntimeContext.class)))
                .thenReturn(Mono.just(Msg.builder().name("child").textContent("done").build()));

        DefaultAgentManager manager =
                new DefaultAgentManager(
                        List.of(new SubagentEntry("harness_agent", "Harness child", rc -> harness)),
                        null);
        AgentSpawnTool tool = new AgentSpawnTool(manager, new NoopTaskRepository(), 0);
        RuntimeContext parentCtx =
                RuntimeContext.builder().sessionId("parent-session").userId("parent-user").build();

        RecordingEmitter emitter = new RecordingEmitter();

        tool.agentSpawn(parentCtx, null, "harness_agent", "work", null, 30, null)
                .contextWrite(ctx -> ctx.put(AgentEventEmitter.CONTEXT_KEY, emitter))
                .block();

        assertEquals(1, emitter.count(AgentStartEvent.class), "expected one start event");
        assertEquals(1, emitter.count(AgentEndEvent.class), "expected one end event");
        assertReplyIdPair(emitter);
    }

    private static void assertReplyIdPair(RecordingEmitter emitter) {
        AgentStartEvent start = emitter.first(AgentStartEvent.class);
        AgentEndEvent end = emitter.first(AgentEndEvent.class);
        assertNotNull(start.getReplyId(), "subagent start event should have a replyId");
        assertEquals(start.getReplyId(), end.getReplyId());
    }

    private static final class NoopTaskRepository implements TaskRepository {
        @Override
        public BackgroundTask getTask(RuntimeContext rc, String sessionId, String taskId) {
            return null;
        }

        @Override
        public BackgroundTask putTask(
                RuntimeContext rc,
                String taskId,
                String subAgentId,
                String sessionId,
                TaskRunSpec spec) {
            return null;
        }

        @Override
        public Collection<BackgroundTask> listTasks(
                RuntimeContext rc, String sessionId, TaskStatus filter) {
            return List.of();
        }

        @Override
        public boolean cancelTask(RuntimeContext rc, String sessionId, String taskId) {
            return false;
        }
    }
}
