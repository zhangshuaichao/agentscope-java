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
package io.agentscope.extensions.agentprotocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.subagent.protocol.RemoteConfirmDecision;
import io.agentscope.harness.agent.subagent.task.TaskRecord;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/**
 * 确定性复现 CI 中恢复与退出清理的竞争：暂停运行在 CompletableFuture 完成前持久化 {@code awaiting_confirm} 记录，客户端观察记录或 REQUIRE_CONFIRM 事件后，可能在该 future 尚未结束清理时调用 {@code resume}。在持久化后立即停驻执行器线程，模拟 CI 执行器最坏抢占时机；此窗口内仍必须接受恢复。
 */
class AgentProtocolTaskStorePauseRaceReproTest {

    @TempDir Path tempDir;

    @Test
    void resumeWhilePausedRunStillUnwinding() throws Exception {
        CountDownLatch releaseExecutor = new CountDownLatch(1);
        AtomicInteger streamCalls = new AtomicInteger();

        WorkspaceProtocolTaskRepository delegate = new WorkspaceProtocolTaskRepository(tempDir);
        ProtocolTaskRepository repo =
                new ProtocolTaskRepository() {
                    @Override
                    public void save(TaskRecord record) {
                        delegate.save(record);
                        if (record.isAwaitingConfirm()) {
                            try {
                                // 记录已持久化且可见，在持久化与 future 完成之间
                                // 暂时阻塞执行器。
                                releaseExecutor.await(10, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }
                    }

                    @Override
                    public Optional<TaskRecord> find(String taskId) {
                        return delegate.find(taskId);
                    }
                };

        HarnessAgent agent = mock(HarnessAgent.class);
        when(agent.streamEvents(any(Msg.class), any(RuntimeContext.class)))
                .thenAnswer(
                        inv -> {
                            if (streamCalls.incrementAndGet() == 1) {
                                return Flux.just(
                                        new AgentStartEvent("sess", null, "worker"),
                                        new AgentResultEvent(askingMsg()),
                                        new AgentEndEvent(null));
                            }
                            return Flux.just(
                                    new AgentStartEvent("sess", null, "worker"),
                                    new AgentResultEvent(completedMsg()),
                                    new AgentEndEvent(null));
                        });

        AgentProtocolProperties props = new AgentProtocolProperties();
        props.setHitlEnabled(true);
        props.setStreamingEnabled(true);
        AgentProtocolTaskStore store =
                new AgentProtocolTaskStore(
                        AgentFactory.fixed(agent), repo, new AgentProtocolTaskEventBus(), props);

        store.submit("race-1", "worker", "please run bash", Map.of());

        long deadline = System.currentTimeMillis() + 5_000;
        while (!"awaiting_confirm".equals(store.snapshot("race-1").get("status"))) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("task never reached awaiting_confirm");
            }
            Thread.sleep(20);
        }

        try {
            // 记录为 awaiting_confirm，因此必须接受恢复，
            // 即使暂停运行的 future 尚未完成退出清理。
            store.resume("race-1", List.of(new RemoteConfirmDecision("tc-ask", false, "no")));
        } finally {
            releaseExecutor.countDown();
        }

        deadline = System.currentTimeMillis() + 5_000;
        while (!"success".equals(store.snapshot("race-1").get("status"))) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("task never completed after resume");
            }
            Thread.sleep(20);
        }
        assertEquals("denied and done", store.snapshot("race-1").get("result"));
        assertEquals(2, streamCalls.get());
    }

    private static Msg askingMsg() {
        return Msg.builder()
                .role(MsgRole.ASSISTANT)
                .content(
                        ToolUseBlock.builder()
                                .id("tc-ask")
                                .name("bash")
                                .input(Map.of("cmd", "rm -rf /"))
                                .state(ToolCallState.ASKING)
                                .build())
                .generateReason(GenerateReason.PERMISSION_ASKING)
                .build();
    }

    private static Msg completedMsg() {
        return Msg.builder().role(MsgRole.ASSISTANT).textContent("denied and done").build();
    }
}
