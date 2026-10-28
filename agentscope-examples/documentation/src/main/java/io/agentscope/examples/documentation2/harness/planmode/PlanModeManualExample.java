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
import io.agentscope.harness.agent.HarnessAgent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicReference;

/**
 * PlanModeManualExample：应用驱动的计划模式，采用<b>交互终端</b>体验计划 → 批准 → 构建。
 * <p>在任务输入前调用 {@code agent.enterPlanMode(ctx)} 进入只读计划阶段，观察调查与写计划；{@code plan_exit} 发出 {@link RequireUserConfirmEvent} 后，在终端由用户批准切换 BUILD 或拒绝继续规划，通过 {@link ConfirmResult} 恢复。
 * <p>计划阶段只允许只读及计划工具（{@code plan_write} / {@code plan_exit}）。也可调用 {@code agent.exitPlanMode(ctx)} 程序化退出而不走 HITL，但本例刻意使用人工审批。
 * <p>运行（读取 stdin，请使用真实终端）：
 * <pre>
 *   export DASHSCOPE_API_KEY=your_key
 *   mvn exec:java -pl agentscope-examples/documentation \
 *       -Dexec.mainClass=io.agentscope.examples.documentation2.harness.planmode.PlanModeManualExample
 * </pre>
 * <p>【学习提示】{@code agent.enterPlanMode(ctx)} 改变会话计划状态；{@code plan_exit} 是带人工确认的退出工具。应按工具调用 ID 回传确认，不要把普通文字“同意”当作 {@code plan_exit} 的完整确认协议。
 */
public class PlanModeManualExample {

    private static final String DEFAULT_TASK =
            "Design the database schema for a blog platform with posts, comments, and tags. "
                    + "Write the plan with plan_write, then call plan_exit when it is ready.";

    public static void main(String[] args) throws Exception {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("Plan Mode — Programmatic Entry, Interactive HITL Exit");
        System.out.println("=".repeat(60) + "\n");

        Path workspace = Files.createTempDirectory("agentscope-planmode-manual");

        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("planner")
                        .sysPrompt(
                                "You are a software architect. You are currently in plan mode. "
                                        + "Investigate the task, write a plan with plan_write, "
                                        + "then call plan_exit when the plan is ready.")
                        .model("dashscope:qwen-plus")
                        .workspace(workspace)
                        .enablePlanMode()
                        .enableTaskList()
                        .build();

        RuntimeContext ctx = RuntimeContext.builder().sessionId("manual-plan").build();
        Scanner in = new Scanner(System.in);

        // ── 在任务输入前程序化进入计划模式 ──
        agent.enterPlanMode(ctx);
        System.out.println("enterPlanMode(ctx) → active: " + agent.isPlanModeActive(ctx));
        System.out.println("The agent is read-only until you approve plan_exit.\n");

        System.out.println("Enter a task for the planner (or press Enter for the default):");
        System.out.print("> ");
        String typed = in.hasNextLine() ? in.nextLine().trim() : "";
        String task = typed.isEmpty() ? DEFAULT_TASK : typed;
        System.out.println();

        Msg next = new UserMessage(task);

        // 交互循环：流式运行一轮；plan_exit 确认暂停时询问用户；
        // 仍在规划但只返回文字时让用户回复；构建模式轮次完成后
        // 结束。轮次上限防止失控。
        int maxTurns = 12;
        for (int turn = 0; turn < maxTurns && next != null; turn++) {
            AtomicReference<RequireUserConfirmEvent> pending = new AtomicReference<>();

            agent.streamEvents(next, ctx)
                    .doOnNext(PlanModeManualExample::handleEvent)
                    .doOnNext(
                            event -> {
                                if (event instanceof RequireUserConfirmEvent confirm) {
                                    pending.set(confirm);
                                }
                            })
                    .blockLast();

            RequireUserConfirmEvent confirm = pending.get();
            if (confirm != null) {
                next = confirmInteractively(confirm, in, workspace);
            } else if (agent.isPlanModeActive(ctx)) {
                // 仍在规划但未要求批准，模型只给出文字，
                // 例如澄清问题。让用户回复或退出。
                System.out.println("\n\n(agent is still in PLAN mode and waiting for your input)");
                next = promptFollowUp(in);
            } else {
                // 构建阶段轮次结束，且无需更多确认。
                next = null;
            }
        }

        // ── 结果 ──
        System.out.println("\n\n── Result ──\n");
        showPlanFile(workspace);
        boolean planActive = agent.isPlanModeActive(ctx);
        if (planActive) {
            System.out.println(
                    "[OUTCOME] Still in PLAN mode — plan_exit was not approved, so the agent never"
                            + " switched to build mode.");
        } else {
            System.out.println(
                    "[OUTCOME] Switched to BUILD mode — you approved plan_exit and the agent left"
                            + " the read-only plan phase.");
        }

        System.out.println("\nWorkspace: " + workspace);
        System.out.println("\n" + "=".repeat(60));
    }

    /**
     * 展示已写计划，逐一询问用户批准或拒绝等待确认的工具调用（通常为 plan_exit）。将 {@link ConfirmResult} 放在 {@link Msg#METADATA_CONFIRM_RESULTS} 下构建恢复消息。
     */
    private static Msg confirmInteractively(
            RequireUserConfirmEvent confirm, Scanner in, Path workspace) throws Exception {
        System.out.println("\n\n" + "─".repeat(60));
        System.out.println("[HITL] The agent wants to leave PLAN mode and start executing.");
        System.out.println("Review the drafted plan below, then approve or reject.\n");
        showPlanFile(workspace);

        List<ConfirmResult> results = new ArrayList<>();
        for (ToolUseBlock call : confirm.getToolCalls()) {
            System.out.printf(
                    "%nApprove '%s'? [y = switch to BUILD mode / anything else = keep planning]: ",
                    call.getName());
            String ans = in.hasNextLine() ? in.nextLine().trim().toLowerCase() : "";
            boolean approved = ans.equals("y") || ans.equals("yes");
            results.add(new ConfirmResult(approved, call));
            System.out.println(
                    approved
                            ? "  → approved; switching to BUILD mode"
                            : "  → rejected; staying in PLAN mode");
        }
        return UserMessage.builder()
                .metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS, results))
                .build();
    }

    /**
     * 从终端读取自由格式跟进消息，空白或 q 表示退出，返回 {@code null}。
     */
    private static Msg promptFollowUp(Scanner in) {
        System.out.print("\nYour reply (or 'q' to quit): ");
        String line = in.hasNextLine() ? in.nextLine().trim() : "q";
        if (line.isEmpty() || line.equalsIgnoreCase("q")) {
            return null;
        }
        return new UserMessage(line);
    }

    private static void showPlanFile(Path workspace) throws Exception {
        Path planFile = workspace.resolve("plans/PLAN.md");
        if (Files.exists(planFile)) {
            String content = Files.readString(planFile);
            System.out.println("── plans/PLAN.md ──");
            if (content.length() > 600) {
                System.out.println(content.substring(0, 600) + "\n... (truncated)");
            } else {
                System.out.println(content);
            }
        } else {
            System.out.println("(no plans/PLAN.md written yet)");
        }
    }

    private static void handleEvent(AgentEvent event) {
        if (event instanceof TextBlockDeltaEvent e) {
            System.out.print(e.getDelta());
        } else if (event instanceof ToolCallStartEvent e) {
            System.out.printf("%n[TOOL] %s%n", e.getToolCallName());
        }
    }
}
