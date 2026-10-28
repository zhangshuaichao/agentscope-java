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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolEmitter;
import io.agentscope.core.tool.Toolkit;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 端到端：真实 SubAgentTool 配合真实 ReActAgent 子智能体。
 *
 * <p>慢子智能体使用 MockModel 返回 "slow_tool" 工具调用，该工具原计划睡眠 4 秒后向临时文件写一行。
 *
 * <p>SubAgentTool 应用 {@code .timeout(3s).retryWhen(1)}。超时后，{@code doFinally(CANCEL)} 调用 {@code interrupt(ctx)} 设置内部标记；执行取消还向内部工具传播，使睡眠中断。若循环继续到 {@code checkInterrupted()}，也会以 {@code InterruptedException} 停止，避免再调用 LLM 或工具。
 *
 * <p>测试实际验证：
 * <ol>
 * <li>慢智能体收到 interrupt()，快智能体未收到</li>
 * <li>slow_tool 在写入前被取消，因此文件为 0 行</li>
 * <li>继续等待仍无新行，循环已停止</li>
 * <li>MockModel.getCallCount() == 1，未发生第二次 LLM 调用</li>
 * </ol>
 */
@DisplayName("SubAgentTool timeout+retry: interrupt stops agent loop")
class SubAgentToolTimeoutRetryIntegrationTest {

    private Path tmpFile;

    @AfterEach
    void cleanup() throws Exception {
        if (tmpFile != null) Files.deleteIfExists(tmpFile);
    }

    @Test
    @DisplayName("Agent loop stops after interrupt — no further LLM or tool calls")
    @Timeout(30)
    void oldAgentIsStopped() throws Exception {
        tmpFile = Files.createTempFile("agent_bg_", ".log");

        // ---- slow_tool：睡眠 4 秒，再向文件写一行 ----
        AgentTool slowTool =
                new AgentTool() {
                    @Override
                    public String getName() {
                        return "slow_tool";
                    }

                    @Override
                    public String getDescription() {
                        return "Sleeps 4s then writes a line to file.";
                    }

                    @Override
                    public Map<String, Object> getParameters() {
                        return Map.of("type", "object", "properties", Map.of());
                    }

                    @Override
                    public Mono<ToolResultBlock> callAsync(ToolCallParam p) {
                        return Mono.fromRunnable(
                                        () -> {
                                            try {
                                                Thread.sleep(4_000);
                                                Files.writeString(
                                                        tmpFile, "slow_tool round done\n");
                                            } catch (Exception ignored) {
                                            }
                                        })
                                .subscribeOn(Schedulers.boundedElastic())
                                .then(
                                        Mono.just(
                                                ToolResultBlock.of(
                                                        TextBlock.builder().text("ok").build())));
                    }
                };

        // ---- 慢智能体：MockModel 返回工具调用，若循环继续则
        // 继续请求工具；有中断时只发生首次模型调用 ----
        Toolkit slowTk = new Toolkit();
        slowTk.registerTool(slowTool);

        // 模型每次被调用都返回工具调用。
        // 没有中断时，每次工具返回后智能体再次调用模型，
        // 导致无限工具调用，文件无限增长。
        // 有中断时，checkInterrupted() 触发并停止循环，模型仅调用一次。
        MockModel slowModel = MockModel.withToolCall("slow_tool", "call_1", Map.of());

        ReActAgent slowSpy =
                Mockito.spy(
                        ReActAgent.builder()
                                .name("slow_sub")
                                .sysPrompt("slow sub")
                                .model(slowModel)
                                .toolkit(slowTk)
                                .build());

        // ---- 快智能体：立即返回文本 ----
        ReActAgent fastSpy =
                Mockito.spy(
                        ReActAgent.builder()
                                .name("fast_sub")
                                .sysPrompt("fast sub")
                                .model(new MockModel("OK"))
                                .toolkit(new Toolkit())
                                .build());

        // ---- 提供者：0=构造器，1=慢实例超时，2=重试快实例 ----
        int[] idx = {0};
        SubAgentProvider<ReActAgent> provider =
                () -> {
                    int i = idx[0]++;
                    if (i == 0) return fastSpy;
                    if (i == 1) return slowSpy;
                    return fastSpy;
                };

        SubAgentTool tool =
                new SubAgentTool(provider, SubAgentConfig.builder().forwardEvents(false).build());

        RuntimeContext rtCtx = RuntimeContext.builder().sessionId("s1").userId("u1").build();
        ToolCallParam param =
                ToolCallParam.builder()
                        .toolUseBlock(
                                io.agentscope.core.message.ToolUseBlock.builder()
                                        .id("call_1")
                                        .name(tool.getName())
                                        .input(Map.of("message", "go"))
                                        .build())
                        .input(Map.of("message", "go"))
                        .runtimeContext(rtCtx)
                        .emitter(Mockito.mock(ToolEmitter.class))
                        .build();

        @SuppressWarnings({"unchecked", "rawtypes"})
        Mono<ToolResultBlock> mono =
                tool.callAsync(param)
                        .timeout(Duration.ofSeconds(3))
                        .retryWhen(
                                reactor.util.retry.Retry.backoff(1, Duration.ofMillis(100))
                                        .maxBackoff(Duration.ofSeconds(1)));

        ToolResultBlock result = mono.block(Duration.ofSeconds(15));
        assertNotNull(result, "Retry should have succeeded via the fast agent");

        // ---- 证明 1：仅慢智能体收到 interrupt() ----
        verify(slowSpy, atLeastOnce()).interrupt(any(RuntimeContext.class));
        verify(fastSpy, never()).interrupt(any(RuntimeContext.class));

        // ---- 证明 2：取消传播到内部工具执行 ----
        // sink.onCancel(lifecycleDisposable) 正确释放内部
        // 订阅，使 slow_tool 的 Thread.sleep 在写文件前
        // 被中断。短暂等待以确认没有写入。
        // slow_tool 约在 0 秒开始并睡眠 4 秒，若不中断将在
        // 约 4 秒写入，比 3 秒超时晚 1 秒；从现在再等 1.5 秒即可
        // 覆盖写入时点并留余量，原先 2 秒过长。
        Thread.sleep(1_500);

        // ---- 证明 3：slow_tool 正确取消，写入 0 行 ----
        long fileLines = Files.readAllLines(tmpFile).size();
        assertEquals(
                0,
                fileLines,
                "Expected 0 tool invocations (cancelled before write), got %d"
                        .formatted(fileLines));

        // ---- 证明 4：再等待仍无内容，循环已停止 ----
        // 复活的循环会在几毫秒内再次调用内存 MockModel，
        // 因此 750ms 足以观测。
        Thread.sleep(750);
        long fileLinesAfter = Files.readAllLines(tmpFile).size();
        assertEquals(0, fileLinesAfter, "File should remain empty. Agent loop did not restart.");

        // ---- 证明 5：MockModel 恰好调用一次 ----
        // 若中断未生效，slow_tool 返回后循环会再次调用 reasoning()，
        // 使 model.stream() 被第二次调用，
        // 进而 getCallCount() >= 2。
        assertEquals(
                1,
                slowModel.getCallCount(),
                "MockModel called %d times. Interrupt prevented the second LLM call."
                        .formatted(slowModel.getCallCount()));
    }
}
