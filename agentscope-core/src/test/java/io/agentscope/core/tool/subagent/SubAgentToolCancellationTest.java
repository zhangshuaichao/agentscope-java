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
package io.agentscope.core.tool.subagent;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolEmitter;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;

/**
 * 单元测试：验证释放 SubAgentTool 订阅会同时触发智能体 interrupt() 并取消其执行 Mono。
 */
@DisplayName("SubAgentTool cancellation interrupts and cancels orphan sub-agent")
class SubAgentToolCancellationTest {

    @Test
    @DisplayName("dispose() → interrupt() called + agent Mono cancelled")
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void shouldInterruptOnCancel() throws Exception {
        // 构建真实 ReActAgent，再创建 spy。
        ReActAgent realAgent =
                ReActAgent.builder()
                        .name("testBlockingAgent")
                        .sysPrompt("You are a test agent.")
                        .maxIters(1)
                        .build();
        ReActAgent spyAgent = Mockito.spy(realAgent);

        // 进入 call() 时发出信号。
        CountDownLatch enteredCall = new CountDownLatch(1);

        // 证明用观测：智能体 Mono 已订阅且已取消。
        AtomicBoolean monoSubscribed = new AtomicBoolean(false);
        AtomicBoolean monoCancelled = new AtomicBoolean(false);

        Mockito.doAnswer(
                        inv -> {
                            enteredCall.countDown();
                            return Mono.<Msg>never()
                                    .doOnSubscribe(s -> monoSubscribed.set(true))
                                    .doOnCancel(() -> monoCancelled.set(true));
                        })
                .when(spyAgent)
                .call(anyList(), any(RuntimeContext.class));

        SubAgentTool tool =
                new SubAgentTool(
                        () -> spyAgent, SubAgentConfig.builder().forwardEvents(false).build());

        ToolEmitter emitter = Mockito.mock(ToolEmitter.class);
        RuntimeContext rtCtx =
                RuntimeContext.builder().sessionId("test-session").userId("test-user").build();

        ToolCallParam param =
                ToolCallParam.builder()
                        .toolUseBlock(
                                io.agentscope.core.message.ToolUseBlock.builder()
                                        .id("call_test_1")
                                        .name("call_subtask_agent")
                                        .input(Map.of("message", "diagnose node 10.0.0.1"))
                                        .build())
                        .input(Map.of("message", "diagnose node 10.0.0.1"))
                        .runtimeContext(rtCtx)
                        .emitter(emitter)
                        .build();

        // ── 执行 ──────────────────────────────────────────────────────────────
        Mono<ToolResultBlock> mono = tool.callAsync(param);
        var disposable = mono.subscribe(result -> {}, error -> {});

        assertTrue(enteredCall.await(10, TimeUnit.SECONDS), "Agent should start executing");
        assertTrue(monoSubscribed.get(), "Agent Mono should be subscribed");

        // 取消，模拟 ToolExecutor 内 Mono.timeout 的取消信号。
        disposable.dispose();

        // ── 断言 ──────────────────────────────────────────────────────────────
        // 1. 智能体的 interrupt() 已被调用。
        boolean interrupted = false;
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                verify(spyAgent).interrupt(any(RuntimeContext.class));
                interrupted = true;
                break;
            } catch (org.mockito.exceptions.base.MockitoAssertionError e) {
                Thread.sleep(200);
            }
        }
        assertTrue(interrupted, "interrupt() should have been called");

        // 2. 智能体 Mono 已取消，执行停止而非泄漏。
        assertTrue(monoCancelled.get(), "Agent's execution Mono should be cancelled, not leaking");
    }
}
