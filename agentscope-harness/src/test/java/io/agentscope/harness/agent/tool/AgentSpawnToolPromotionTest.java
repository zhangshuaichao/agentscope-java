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

import static io.agentscope.harness.agent.tool.ToolResultAssertions.assertText;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
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
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * {@code AgentSpawnTool.execWithTimeoutPromotion} 的<b>超时提升</b>路径回归测试。
 *
 * <p>{@code AgentSpawnToolOrphanCancelTest} 覆盖孤儿取消修复；此测试检查另一项约定：智能体超过 {@code timeout_seconds}，内部 {@code race.orTimeout} 触发时，将在途执行包装为 {@link TaskRunSpec.AdoptedTaskRunSpec} 并提交到 {@link TaskRepository}，用户仍可通过 {@code task_output} 获取最终结果。
 *
 * <p>孤儿取消修复引入 {@code doFinally(CANCEL) → interrupt} 处理器和内层 Mono 的 {@code sink.onCancel(innerSub::dispose)} 钩子。提升路径在 CompletableFuture 超时后通过 {@code sink.success(...)} 结束，不是 Reactor 取消，因此不应触发这些取消处理。测试同时断言：
 *
 * <ol>
 *   <li>智能体不被中断，继续运行并最终生成回复。
 *   <li>{@link TaskRunSpec.AdoptedTaskRunSpec#future()} 以该回复完成。
 * </ol>
 *
 * <p>若以后重构在超时分支错误释放内层订阅，这两项断言将暴露回归。
 */
@DisplayName(
        "AgentSpawnTool timeout-promotion: AdoptedTaskRunSpec still works after orphan-cancel fix")
class AgentSpawnToolPromotionTest {

    @Test
    @DisplayName(
            "Inner timeout wraps in-flight execution as AdoptedTaskRunSpec; future completes with"
                    + " reply")
    @Timeout(30)
    void innerTimeoutPromotesToAdoptedTaskRunSpec() throws Exception {
        // ---- slow_tool：休眠 2 秒后返回，确保内部 1 秒超时先触发。
        // ----
        AgentTool slowTool =
                new AgentTool() {
                    @Override
                    public String getName() {
                        return "slow_tool";
                    }

                    @Override
                    public String getDescription() {
                        return "Sleeps 2s, then returns ok.";
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
                                                Thread.sleep(2_000);
                                            } catch (InterruptedException e) {
                                                Thread.currentThread().interrupt();
                                            }
                                        })
                                .subscribeOn(Schedulers.boundedElastic())
                                .then(
                                        Mono.just(
                                                ToolResultBlock.of(
                                                        TextBlock.builder().text("ok").build())));
                    }
                };

        Toolkit tk = new Toolkit();
        tk.registerTool(slowTool);

        // ---- 有状态 MockModel：首次调用返回 tool_call，后续返回最终文本 "task done"。
        // ----
        AtomicInteger modelCall = new AtomicInteger(0);
        Function<List<io.agentscope.core.message.Msg>, List<ChatResponse>> gen =
                messages -> {
                    if (modelCall.incrementAndGet() == 1) {
                        Map<String, Object> args = new HashMap<>();
                        return List.of(
                                ChatResponse.builder()
                                        .id("msg_" + java.util.UUID.randomUUID())
                                        .content(
                                                List.of(
                                                        ToolUseBlock.builder()
                                                                .name("slow_tool")
                                                                .id("call_1")
                                                                .input(args)
                                                                .content("{}")
                                                                .build()))
                                        .usage(new ChatUsage(8, 15, 23))
                                        .build());
                    }
                    return List.of(
                            ChatResponse.builder()
                                    .id("msg_" + java.util.UUID.randomUUID())
                                    .content(List.of(TextBlock.builder().text("task done").build()))
                                    .usage(new ChatUsage(10, 20, 30))
                                    .build());
                };
        MockModel model = new MockModel(gen);

        ReActAgent agentSpy =
                Mockito.spy(
                        ReActAgent.builder()
                                .name("slow_sub")
                                .sysPrompt("slow sub")
                                .model(model)
                                .toolkit(tk)
                                .build());

        // ---- DefaultAgentManager + AgentSpawnTool，配合可捕获提交的 TaskRepository ----
        SubagentFactory factory = rc -> agentSpy;
        DefaultAgentManager manager =
                new DefaultAgentManager(
                        List.of(new SubagentEntry("slow_agent", "Test slow agent", factory)), null);

        AtomicReference<TaskRunSpec> capturedSpec = new AtomicReference<>();
        AtomicReference<String> capturedTaskId = new AtomicReference<>();
        TaskRepository captureRepo = new CapturingTaskRepository(capturedSpec, capturedTaskId);

        AgentSpawnTool tool = new AgentSpawnTool(manager, captureRepo, 0);
        RuntimeContext ctx = RuntimeContext.builder().sessionId("s1").userId("u1").build();

        // ---- 以 1 秒超时调用 agentSpawn，智能体自然完成需要约 2 秒以上 ----
        String result =
                assertText(
                        tool.agentSpawn(ctx, null, "slow_agent", "go", null, 1, null)
                                .block(Duration.ofSeconds(10)),
                        ToolResultState.SUCCESS);

        // ===== 证据 1：工具返回提升消息，而非错误 =====
        assertNotNull(result, "agentSpawn returned null");
        assertTrue(
                result.contains("status: timeout_promoted"),
                "Expected 'status: timeout_promoted' in result, got: " + result);
        assertNotNull(capturedTaskId.get(), "TaskRepository.putTask was never called");
        assertTrue(
                result.contains("task_id: " + capturedTaskId.get()),
                "Result should include the captured task_id. result=" + result);

        // ===== 证据 2：捕获的定义为 AdoptedTaskRunSpec，而非 Local/Remote =====
        TaskRunSpec spec = capturedSpec.get();
        assertNotNull(spec, "Captured TaskRunSpec was null");
        assertTrue(
                spec instanceof TaskRunSpec.AdoptedTaskRunSpec,
                "Expected AdoptedTaskRunSpec but got " + spec.getClass().getSimpleName());

        // ===== 证据 3：接管的 future 最终以智能体回复完成 =====
        // 关键断言：孤儿取消修复不能在 race.orTimeout 通过
        // CompletableFuture 触发超时时释放内层订阅，否则
        // 智能体被终止，future 无法正常完成。
        CompletableFuture<String> adoptedFuture = ((TaskRunSpec.AdoptedTaskRunSpec) spec).future();
        String finalReply = adoptedFuture.get(15, TimeUnit.SECONDS);
        assertNotNull(finalReply, "Adopted future completed with null");
        assertTrue(
                finalReply.contains("task done"),
                "Adopted future should complete with agent's reply. Got: " + finalReply);

        // ===== 证据 4：从未对智能体调用 interrupt() =====
        // 提升应让智能体继续运行，而非终止；若孤儿取消修复
        // 误作用于提升路径，就会在此调用 interrupt()。
        verify(agentSpy, never()).interrupt(any(RuntimeContext.class));
    }

    /** 捕获提交给 {@code putTask} 的 {@link TaskRunSpec}，供测试检查。 */
    private static final class CapturingTaskRepository implements TaskRepository {
        private final AtomicReference<TaskRunSpec> capturedSpec;
        private final AtomicReference<String> capturedTaskId;

        CapturingTaskRepository(AtomicReference<TaskRunSpec> spec, AtomicReference<String> id) {
            this.capturedSpec = spec;
            this.capturedTaskId = id;
        }

        @Override
        public BackgroundTask putTask(
                RuntimeContext rc,
                String taskId,
                String subAgentId,
                String sessionId,
                TaskRunSpec spec) {
            capturedSpec.set(spec);
            capturedTaskId.set(taskId);
            return null;
        }

        @Override
        public BackgroundTask getTask(RuntimeContext rc, String sessionId, String taskId) {
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
