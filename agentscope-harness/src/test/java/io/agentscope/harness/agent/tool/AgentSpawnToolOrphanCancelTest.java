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

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.middleware.SubagentEntry;
import io.agentscope.harness.agent.subagent.DefaultAgentManager;
import io.agentscope.harness.agent.subagent.SubagentFactory;
import io.agentscope.harness.agent.subagent.task.BackgroundTask;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.subagent.task.TaskRunSpec;
import io.agentscope.harness.agent.subagent.task.TaskStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * {@code AgentSpawnTool} 孤儿子智能体泄漏的复现／回归测试。
 *
 * <p><b>原问题场景：</b>父级调用 {@code agent_spawn}，在返回 {@link Mono} 完成前因外层 {@code Mono.timeout()}、用户停止或上游取消而取消订阅。原 {@code execWithTimeoutPromotion} 内对 {@code execLocalSync} 的执行 Mono 使用裸 {@code .subscribe(...)}，未保存 {@code Disposable}，父级取消时也未释放，导致子智能体成为继续运行的孤儿。
 *
 * <p>测试对应核心 {@code SubAgentTool} 中的 {@code SubAgentToolTimeoutRetryIntegrationTest}（相关问题在 {@code 029cc55e} 修复），但改用 Harness 侧 {@code AgentSpawnTool} API：
 *
 * <ul>
 *   <li>{@link DefaultAgentManager} + {@link SubagentEntry}，替代 {@code SubAgentProvider}
 *   <li>直接调用 {@code tool.agentSpawn(...)}，替代 {@code tool.callAsync(param)}
 *   <li>外层 {@code Mono.timeout(2s)}，替代 {@code .timeout(3s).retryWhen(1)}
 * </ul>
 *
 * <p>断言描述修复后的期望状态。未修复实现会失败，并报告超时及等待窗口后的文件行数、模型调用数，作为问题诊断证据；此说明不宣称当前测试已执行。
 */
@DisplayName("AgentSpawnTool parent-cancel: orphan sub-agent must be interrupted")
class AgentSpawnToolOrphanCancelTest {

    private Path tmpFile;

    @AfterEach
    void cleanup() throws Exception {
        if (tmpFile != null) {
            try {
                Files.deleteIfExists(tmpFile);
            } catch (Exception ignored) {
                // 尽力清理
            }
        }
    }

    @Test
    @DisplayName("Sub-agent loop stops after parent timeout cancels the tool subscription")
    @Timeout(30)
    void orphanAgentInterruptedOnParentCancel() throws Exception {
        tmpFile = Files.createTempFile("agentspawn_orphan_", ".log");
        // 从空文件开始，使行数确定。
        Files.writeString(tmpFile, "");

        // ---- slow_tool：每次调用写一行，并短暂休眠以减慢循环，
        // 使外层 2 秒超时在智能体仍运行时发生，而非已通过
        // 摘要／最大迭代次数退出；这样超时后的孤儿观察窗口
        // 能检测到可测量的执行进展。----
        AtomicInteger toolCallCount = new AtomicInteger(0);
        AgentTool slowTool =
                new AgentTool() {
                    @Override
                    public String getName() {
                        return "slow_tool";
                    }

                    @Override
                    public String getDescription() {
                        return "Appends one line to a temp file, then returns ok.";
                    }

                    @Override
                    public Map<String, Object> getParameters() {
                        return Map.of("type", "object", "properties", Map.of());
                    }

                    @Override
                    public Mono<ToolResultBlock> callAsync(ToolCallParam p) {
                        return Mono.fromRunnable(
                                        () -> {
                                            toolCallCount.incrementAndGet();
                                            try {
                                                Thread.sleep(150);
                                                Files.writeString(
                                                        tmpFile,
                                                        "line\n",
                                                        StandardOpenOption.APPEND);
                                            } catch (Exception ignored) {
                                                // 尽力写入；测试断言次数而非
                                                // 文件内容
                                            }
                                        })
                                .subscribeOn(Schedulers.boundedElastic())
                                .then(
                                        Mono.just(
                                                ToolResultBlock.of(
                                                        TextBlock.builder().text("ok").build())));
                    }
                };

        Toolkit slowTk = new Toolkit();
        slowTk.registerTool(slowTool);

        // ---- 每次调用都发出唯一 tool_call_id 的 MockModel ----
        // 现成 MockModel.withToolCall(...) 每次返回同一 ID，
        // ReActAgent 会去重，只有第一次执行，之后循环直接进入
        // 摘要。生成新 UUID 可强制每轮真正执行
        // slow_tool，使修复前的孤儿持续写入新行直到中断。
        java.util.function.Function<
                        List<io.agentscope.core.message.Msg>,
                        List<io.agentscope.core.model.ChatResponse>>
                gen =
                        messages -> {
                            java.util.Map<String, Object> args = new java.util.HashMap<>();
                            String callId = "call_" + java.util.UUID.randomUUID();
                            io.agentscope.core.model.ChatResponse resp =
                                    io.agentscope.core.model.ChatResponse.builder()
                                            .id("msg_" + java.util.UUID.randomUUID())
                                            .content(
                                                    java.util.List.of(
                                                            io.agentscope.core.message.ToolUseBlock
                                                                    .builder()
                                                                    .name("slow_tool")
                                                                    .id(callId)
                                                                    .input(args)
                                                                    .content(
                                                                            io.agentscope.core.util
                                                                                    .JsonUtils
                                                                                    .getJsonCodec()
                                                                                    .toJson(args))
                                                                    .build()))
                                            .usage(
                                                    new io.agentscope.core.model.ChatUsage(
                                                            8, 15, 23))
                                            .build();
                            return java.util.List.of(resp);
                        };
        MockModel slowModel = new MockModel(gen);

        ReActAgent slowSpy =
                Mockito.spy(
                        ReActAgent.builder()
                                .name("slow_sub")
                                .sysPrompt("slow sub")
                                .model(slowModel)
                                .toolkit(slowTk)
                                .maxIters(50)
                                .build());

        // ---- DefaultAgentManager + AgentSpawnTool ----
        SubagentFactory factory = rc -> slowSpy;
        DefaultAgentManager manager =
                new DefaultAgentManager(
                        List.of(new SubagentEntry("slow_agent", "Test slow agent", factory)), null);

        TaskRepository stubRepo = new NoopTaskRepository();
        AgentSpawnTool tool = new AgentSpawnTool(manager, stubRepo, 0);

        RuntimeContext ctx = RuntimeContext.builder().sessionId("s1").userId("u1").build();

        // ---- 通过外层 .timeout(2s) 取消父级 ----
        // 智能体内部超时为 30 秒，确保内部 race.orTimeout 不会先触发。
        // 外层 Mono.timeout(2s) 取消 agentSpawn 返回的 Mono<String>，
        // 这正是需要覆盖的父级取消场景。
        long t0 = System.currentTimeMillis();
        try {
            tool.agentSpawn(ctx, null, "slow_agent", "go", null, 30, null)
                    .timeout(Duration.ofSeconds(2))
                    .block();
        } catch (Exception expected) {
            // 预期外层超时触发 TimeoutException，可能由 .block() 包装。
            // 不检查精确异常类型；关键是在进入下方断言时，
            // 父订阅已被取消。
            System.out.println(
                    "[AgentSpawnToolOrphanCancelTest] outer timeout fired at t="
                            + (System.currentTimeMillis() - t0)
                            + "ms, exception class="
                            + expected.getClass().getSimpleName());
        }

        long linesAtTimeout = Files.readAllLines(tmpFile).size();
        long callsAtTimeout = slowModel.getCallCount();
        System.out.println(
                "[AgentSpawnToolOrphanCancelTest] at timeout: lines="
                        + linesAtTimeout
                        + ", modelCalls="
                        + callsAtTimeout
                        + ", toolCalls="
                        + toolCallCount.get());

        // ---- 等待足够时间，观察孤儿是否仍在运行 ----
        // 子循环每轮约 150 毫秒（slow_tool 休眠加 MockModel 调用），因此 1 秒
        // 涵盖约 6 轮；未修复孤儿会多写约 6 行，而下方
        // 断言只容忍一个在途调用。此前 4 秒窗口过长。
        Thread.sleep(1_000);

        long linesAfter = Files.readAllLines(tmpFile).size();
        long callsAfter = slowModel.getCallCount();
        long deltaLines = linesAfter - linesAtTimeout;
        long deltaCalls = callsAfter - callsAtTimeout;
        System.out.println(
                "[AgentSpawnToolOrphanCancelTest] after 1s wait: lines="
                        + linesAfter
                        + " (delta="
                        + deltaLines
                        + "), modelCalls="
                        + callsAfter
                        + " (delta="
                        + deltaCalls
                        + "), toolCalls="
                        + toolCallCount.get());

        // 取消由子订阅的 RunControl 管理；验证实际执行停止，
        // 而非检查已过时的 interrupt(RuntimeContext) 回调。
        assertTrue(callsAtTimeout > 0, "Child must have started before cancellation");
        assertTrue(toolCallCount.get() > 0, "The child must actually execute its tool");

        // ===== 证据 2：父级取消后文件不再持续增长 =====
        // 修复前此断言失败，孤儿循环在 1 秒窗口中继续写多行。
        // 允许差值为 1，以容忍中断标记被观察到之前
        // 完成的单个在途工具调用。
        assertTrue(
                deltaLines <= 1,
                "Orphan kept writing lines after parent cancel. linesAtTimeout="
                        + linesAtTimeout
                        + ", linesAfter="
                        + linesAfter
                        + ", delta="
                        + deltaLines);

        // ===== 证据 3：父级取消后模型未继续被大量调用 =====
        assertTrue(
                deltaCalls <= 1,
                "Orphan kept invoking the model after parent cancel. callsAtTimeout="
                        + callsAtTimeout
                        + ", callsAfter="
                        + callsAfter
                        + ", delta="
                        + deltaCalls);
    }

    /** 最小无操作 TaskRepository；问题场景不会进入提升路径，使用占位实现即可。 */
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
