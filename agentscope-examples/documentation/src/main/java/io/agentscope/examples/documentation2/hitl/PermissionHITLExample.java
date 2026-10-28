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
package io.agentscope.examples.documentation2.hitl;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.dashscope.formatter.DashScopeChatFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

/**
 * PermissionHITLExample：通过权限引擎进行人工确认。
 * <p>匹配 {@code ASK} 的工具调用使智能体停止，响应满足 {@code getGenerateReason() == GenerateReason.PERMISSION_ASKING}。调用方检查待处理工具，收集决定，再次调用恢复。
 * <ol><li>用户消息触发需确认工具。
 * <li>提前返回 {@code GenerateReason.PERMISSION_ASKING}。
 * <li>应用展示工具调用并收集用户决定。
 * <li>应用发送跟进消息恢复或取消。</ol>
 * <p>无人值守模式 {@link PermissionMode#DONT_ASK}：CI 或自动测试中设置 {@code DONT_ASK}，原本要询问的调用自动拒绝。运行：
 * <pre>
 *   export DASHSCOPE_API_KEY=your_key
 *   mvn exec:java -pl agentscope-examples/documentation \
 *       -Dexec.mainClass=io.agentscope.examples.documentation2.hitl.PermissionHITLExample
 * </pre>
 */
public class PermissionHITLExample {

    /**
     * 运行权限 HITL 示例。
     * @param args 命令行参数，此处忽略
     */
    public static void main(String[] args) {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("Permission HITL Example");
        System.out.println("=".repeat(60));
        System.out.println(
                "Demonstrates human-in-the-loop confirmation via the permission engine.\n"
                        + "safe_read is auto-allowed; dangerous_delete requires confirmation.\n"
                        + "Pass --headless to run in DONT_ASK mode (auto-deny confirmations).");
        System.out.println("=".repeat(60) + "\n");

        boolean headless = args.length > 0 && "--headless".equalsIgnoreCase(args[0]);

        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new DangerousTools());

        // ── 构建权限上下文 ──
        //
        // safe_read：自动允许；ALLOW 的 ruleContent 为 null，匹配所有参数。
        // dangerous_delete：执行前询问用户，使用 ASK 规则。
        // 无人值守 DONT_ASK 模式下，ASK 自动转为 DENY。
        PermissionMode mode = headless ? PermissionMode.DONT_ASK : PermissionMode.DEFAULT;
        PermissionContextState permCtx =
                PermissionContextState.builder()
                        .mode(mode)
                        .addAllowRule(
                                "safe_read",
                                new PermissionRule(
                                        "safe_read", null, PermissionBehavior.ALLOW, "policy"))
                        .addAskRule(
                                "dangerous_delete",
                                new PermissionRule(
                                        "dangerous_delete", null, PermissionBehavior.ASK, "policy"))
                        .build();

        ReActAgent agent =
                ReActAgent.builder()
                        .name("GuardedAgent")
                        .sysPrompt(
                                "You are a file assistant. You have safe_read and dangerous_delete"
                                        + " tools.")
                        .model(
                                DashScopeChatModel.builder()
                                        .apiKey(apiKey)
                                        .modelName("qwen-max")
                                        .stream(true)
                                        .formatter(new DashScopeChatFormatter())
                                        .build())
                        .toolkit(toolkit)
                        .permissionContext(permCtx)
                        .build();

        if (headless) {
            System.out.println(
                    "Running in headless mode (DONT_ASK): confirmations are auto-denied.\n");
            runHeadless(agent);
        } else {
            System.out.println(
                    "Running in interactive mode (DEFAULT): you will be asked to confirm"
                            + " deletes.\n");
            runInteractive(agent);
        }
    }

    private static void runHeadless(ReActAgent agent) {
        Msg result =
                agent.call(new UserMessage("user", "Delete the file /tmp/important.txt")).block();
        System.out.println("Reason: " + (result != null ? result.getGenerateReason() : "null"));
        System.out.println("Agent:  " + (result != null ? result.getTextContent() : "(null)"));
    }

    private static void runInteractive(ReActAgent agent) {
        Scanner scanner = new Scanner(System.in);
        System.out.print("You: ");

        while (scanner.hasNextLine()) {
            String userInput = scanner.nextLine().trim();
            if (userInput.isBlank() || "exit".equalsIgnoreCase(userInput)) {
                break;
            }

            Msg userMsg = new UserMessage("user", userInput);

            Msg result = agent.call(java.util.List.of(userMsg)).block();

            if (result != null && result.getGenerateReason() == GenerateReason.PERMISSION_ASKING) {
                // ── 智能体停止并请求确认 ──
                System.out.println(
                        "\n[HITL] The agent wants to execute a tool that requires your approval.");
                System.out.println("[HITL] Pending operations:");

                // 从返回 Msg 提取 ASKING ToolUseBlock
                List<ToolUseBlock> askingTools =
                        result.getContent().stream()
                                .filter(b -> b instanceof ToolUseBlock)
                                .map(ToolUseBlock.class::cast)
                                .filter(t -> t.getState() == ToolCallState.ASKING)
                                .toList();
                askingTools.forEach(
                        t -> System.out.println("  - " + t.getName() + " " + t.getInput()));

                System.out.print("[HITL] Allow? (yes/no): ");
                String decision = scanner.hasNextLine() ? scanner.nextLine().trim() : "no";
                boolean approved =
                        "yes".equalsIgnoreCase(decision) || "y".equalsIgnoreCase(decision);

                // 根据提取的调用构建 ConfirmResult 并恢复
                List<ConfirmResult> confirmResults =
                        askingTools.stream().map(t -> new ConfirmResult(approved, t)).toList();
                Map<String, Object> meta = new HashMap<>();
                meta.put(Msg.METADATA_CONFIRM_RESULTS, confirmResults);
                Msg resumeMsg =
                        Msg.builder()
                                .name("user")
                                .role(MsgRole.USER)
                                .textContent(approved ? "approved" : "denied")
                                .metadata(meta)
                                .build();

                Msg finalResult = agent.call(List.of(resumeMsg)).block();
                System.out.println(
                        "\nAgent: "
                                + (finalResult != null ? finalResult.getTextContent() : "(null)"));
            } else {
                System.out.println(
                        "\nAgent: " + (result != null ? result.getTextContent() : "(null)"));
            }

            System.out.print("\nYou: ");
        }
    }

    /**
     * 一项安全、一项危险的演示工具。
     */
    public static class DangerousTools {

        /**
         * 安全读取文件，本示例规则自动允许。
         * @param path 文件路径
         * @return 模拟文件内容
         */
        @Tool(name = "safe_read", description = "Read a file (safe, always allowed)")
        public String safeRead(
                @ToolParam(name = "path", description = "File path to read") String path) {
            return "Content of " + path + ": [simulated data]";
        }

        /**
         * 模拟永久删除文件，需要用户确认；示例方法仅返回说明文本。
         * @param path 待删除路径
         * @return 确认字符串
         */
        @Tool(
                name = "dangerous_delete",
                description = "Permanently delete a file (requires confirmation)")
        public String dangerousDelete(
                @ToolParam(name = "path", description = "File path to delete permanently")
                        String path) {
            return "Deleted: " + path;
        }
    }
}
