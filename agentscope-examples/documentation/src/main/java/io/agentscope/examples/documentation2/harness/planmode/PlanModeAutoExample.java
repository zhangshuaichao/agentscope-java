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
package io.agentscope.examples.documentation2.harness.planmode;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * PlanModeAutoExample：模型自主决定调用 {@code plan_enter}，写计划，经 {@code plan_exit} 审批后进入构建模式执行。
 * <ol><li>应用不调用 {@code enterPlanMode(ctx)}，由模型根据工具列表自行决定是否规划。
 * <li>完整流程：{@code plan_enter} 只读 → {@code plan_write} 写计划 → {@code plan_exit} 人工确认 → 构建执行。
 * <li>审批后恢复构建模式的写入能力。
 * <li>{@code plan_exit} 始终询问；示例监听 {@link RequireUserConfirmEvent}，自动批准，并携带 {@link ConfirmResult} 再次调用，否则会停在确认处。</ol>
 * <p>状态隔离应以实际 builder 配置为准：{@link InMemoryAgentStateStore} 可使新进程从空状态开始，但本示例创建行已被注释，且 builder 未配置该内存存储。默认磁盘存储按 {@code (agentId, sessionId)} 而非工作区定位；未确认的 ASKING 状态会被保留，后续运行若不附确认不能直接跳过。
 * <p>运行：
 * <pre>
 *   export DASHSCOPE_API_KEY=your_key
 *   mvn exec:java -pl agentscope-examples/documentation \
 *       -Dexec.mainClass=io.agentscope.examples.documentation2.harness.planmode.PlanModeAutoExample
 * </pre>
 * <p>【学习提示】{@code plan_enter} 是否真正执行，应以事件为准；{@code plan_exit} 暂停后需要匹配的确认结果。此演示自动批准 {@code plan_exit}，不是生产授权策略。
 */
public class PlanModeAutoExample {

    public static void main(String[] args) throws Exception {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("Plan Mode — Model-Autonomous Entry (plan → approve → build)");
        System.out.println("=".repeat(60) + "\n");

        Path workspace = Files.createTempDirectory("agentscope-planmode-auto");

        //        InMemoryAgentStateStore stateStore = new InMemoryAgentStateStore();
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("architect")
                        .sysPrompt(
                                "You are a software architect. For complex, multi-step tasks, use"
                                    + " plan_enter to enter plan mode first. In plan mode,"
                                    + " investigate and write a plan with plan_write, then call"
                                    + " plan_exit to get approval. After approval you are in build"
                                    + " mode — start executing the plan step by step. If the"
                                    + " workspace is empty (a greenfield project), do not get stuck"
                                    + " investigating — plan the architecture directly and commit"
                                    + " it with plan_write.")
                        .model("dashscope:qwen3.7-plus")
                        .workspace(workspace)
                        // 项目可写：生成代码位于项目目录，
                        // 记忆、会话及计划等元数据保留在工作区。
                        .filesystem(
                                new LocalFilesystemSpec().projectWritable(true).inheritEnv(true))
                        // 若使用内存状态存储，各次进程运行独立，
                        // 暂停的 plan_exit 不会泄漏到下次运行；以实际 builder 配置为准。
                        .enablePlanMode()
                        // 显式允许模型在计划模式使用 Shell 进行只读调查，
                        // 例如 cat / ls / grep / git log，以形成真实计划。
                        // 计划提示要求 Shell 只读；修改文件的编辑工具
                        // 在计划批准前仍被拒绝。
                        .allowShellInPlanMode()
                        .enableTaskList()
                        .build();

        RuntimeContext ctx = RuntimeContext.builder().sessionId("auto-plan").build();

        System.out.println("Plan mode active before call: " + agent.isPlanModeActive(ctx));
        System.out.println("No programmatic enterPlanMode(ctx) — the model decides on its own.\n");

        // ── 发送复杂任务，预期 plan_enter → plan_write → plan_exit → 构建 ──

        System.out.println("── Sending complex task ──\n");

        Msg next =
                new UserMessage(
                        "Design a new microservices-based e-commerce backend from scratch. The"
                            + " workspace is intentionally empty (greenfield) — there is NO"
                            + " existing code to investigate or migrate, so plan the architecture"
                            + " directly. This is a complex task that warrants planning: decide the"
                            + " service boundaries, data ownership, and communication style, write"
                            + " the plan with plan_write, get approval, then scaffold the project"
                            + " layout for the first service.");

        // HITL 循环：运行智能体，遇到 plan_exit 等确认暂停时批准，
        // 将 ConfirmResult 发送回去恢复。轮次完成且不再要求确认时
        // 结束循环；轮次上限防止失控。
        // 记录实际调用的计划控制工具，使最终摘要能够区分
        // “从未进入计划模式”和“进入后退出”；两者最终都可能
        // 表现为 planActive=false，
        // 但含义截然不同。
        java.util.Set<String> planToolsSeen = java.util.concurrent.ConcurrentHashMap.newKeySet();

        int maxTurns = 8;
        for (int turn = 0; turn < maxTurns && next != null; turn++) {
            AtomicReference<RequireUserConfirmEvent> pending = new AtomicReference<>();

            agent.streamEvents(next, ctx)
                    .doOnNext(PlanModeAutoExample::handleEvent)
                    .doOnNext(
                            event -> {
                                if (event instanceof ToolCallStartEvent tc
                                        && tc.getToolCallName() != null
                                        && tc.getToolCallName().startsWith("plan_")) {
                                    planToolsSeen.add(tc.getToolCallName());
                                }
                                if (event instanceof RequireUserConfirmEvent confirm) {
                                    pending.set(confirm);
                                }
                            })
                    .blockLast();

            RequireUserConfirmEvent confirm = pending.get();
            next = (confirm == null) ? null : approveAll(confirm);
        }

        // ── 展示结果 ──

        System.out.println("\n\n── Result ──\n");

        Path planFile = workspace.resolve("plans/PLAN.md");
        boolean planWritten = Files.exists(planFile);
        boolean planActive = agent.isPlanModeActive(ctx);

        if (planWritten) {
            String content = Files.readString(planFile);
            System.out.println("── plans/PLAN.md ──");
            if (content.length() > 600) {
                System.out.println(content.substring(0, 600) + "\n... (truncated)");
            } else {
                System.out.println(content);
            }
            System.out.println();
        }

        // 如实分类终态，避免两个陷阱：
        // 1. planActive=false 混淆“未进入”和“进入后退出”，
        // 只有
        // 后者属于成功路径；前者是模型自主选择
        // 不规划。
        // 2. 模型可能以自信文字结束轮次（如宣称将写完整计划），
        // 却未调用 plan_write/plan_exit；这种“只说不做”
        // 不能误报为成功。
        boolean planningHappened =
                planToolsSeen.contains("plan_enter")
                        || planToolsSeen.contains("plan_write")
                        || planWritten;

        if (!planActive && !planningHappened) {
            System.out.println(
                    "[OUTCOME] NO PLAN MODE — the agent worked directly in build mode and never"
                        + " called plan_enter. Entering plan mode is autonomous (the model"
                        + " decides), so this is a valid choice; it just means the plan → approve →"
                        + " build lifecycle was not exercised this run.");
            System.out.println(
                    "          Likely cause: the model judged the task simple enough to handle"
                        + " directly without a separate planning phase. Entering plan mode is the"
                        + " model's call, so this can be legitimate.");
            System.out.println(
                    "          What to do: strengthen the system prompt to require plan_enter for"
                        + " this kind of task, or make the task explicitly multi-phase so the model"
                        + " chooses to plan first.");
        } else if (!planActive) {
            System.out.println(
                    "[OUTCOME] OK — the agent entered plan mode and exited via plan_exit into build"
                            + " mode.");
        } else if (planWritten) {
            System.out.println(
                    "[OUTCOME] PARTIAL — a plan was drafted (plans/PLAN.md) but the agent is still"
                        + " in PLAN mode: it never called plan_exit. Review the plan, then send a"
                        + " follow-up message on the same session to approve and continue into"
                        + " build mode.");
        } else {
            System.out.println(
                    "[OUTCOME] NO PLAN PRODUCED — the agent ended its turn still in PLAN mode"
                        + " without writing plans/PLAN.md or calling plan_exit. Its final message"
                        + " may *read* like a plan, but no plan artifact was actually created.");
            System.out.println(
                    "          Likely cause: the model kept deliberating (or returned an empty"
                        + " completion) instead of committing to plan_write — sometimes it tries to"
                        + " investigate the empty greenfield workspace and stalls when it finds"
                        + " nothing.");
            System.out.println(
                    "          What to do: make the task more concrete, or add a system-prompt hint"
                        + " that the workspace is empty by design and it should plan the"
                        + " architecture directly — then send a follow-up message to continue the"
                        + " same session.");
        }

        System.out.println("\nWorkspace: " + workspace);
        System.out.println("\n" + "=".repeat(60));
    }

    private static void handleEvent(AgentEvent event) {
        if (event instanceof TextBlockDeltaEvent e) {
            System.out.print(e.getDelta());
        } else if (event instanceof ToolCallStartEvent e) {
            System.out.printf("%n[TOOL] %s%n", e.getToolCallName());
        }
    }

    /**
     * 自动批准智能体等待的每个工具调用，构建恢复消息。真实应用应让用户批准、拒绝或编辑；此演示为跑通全流程无条件批准。
     * <p>{@link ConfirmResult} 放在 {@link Msg#METADATA_CONFIRM_RESULTS} 下，下一次调用据此匹配待处理 ASKING 工具并继续。
     */
    private static Msg approveAll(RequireUserConfirmEvent confirm) {
        System.out.printf(
                "%n[HITL] Approving %d tool call(s) awaiting confirmation:%n",
                confirm.getToolCalls().size());
        List<ConfirmResult> results = new ArrayList<>();
        for (ToolUseBlock toolCall : confirm.getToolCalls()) {
            System.out.println("       - " + toolCall.getName());
            results.add(new ConfirmResult(/* confirmed= */ true, toolCall));
        }
        return UserMessage.builder()
                .metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS, results))
                .build();
    }
}
