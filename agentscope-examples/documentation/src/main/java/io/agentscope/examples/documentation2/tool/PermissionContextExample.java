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
package io.agentscope.examples.documentation2.tool;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
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
import io.agentscope.harness.agent.middleware.AgentTraceMiddleware;

/**
 * PermissionContextExample：演示 {@link PermissionContextState} 权限引擎。
 * <p>每个工具执行前，由 {@link PermissionContextState} 的显式规则、全局 {@link PermissionMode} 以及 {@link io.agentscope.core.tool.ToolBase} 的 {@code checkPermissions()} 共同决定。
 * <p>DEFAULT 对未决定调用默认询问；ACCEPT_EDITS 可允许合适写操作；EXPLORE 限制为只读，非只读可能直接拒绝而非询问；BYPASS 仅提供最终允许回退，不能跳过显式拒绝、询问与工具安全自检；DONT_ASK 将询问转为拒绝。以 PermissionEngine 实际顺序为准，不能把 BYPASS 当成“跳过所有检查”。
 * <p>运行：
 * <pre>
 *   export DASHSCOPE_API_KEY=your_key
 *   mvn exec:java -pl agentscope-examples/documentation \
 *       -Dexec.mainClass=io.agentscope.examples.documentation2.tool.PermissionContextExample
 * </pre>
 */
public class PermissionContextExample {

    /**
     * 运行权限上下文示例。
     * @param args 命令行参数，此处忽略
     */
    public static void main(String[] args) {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("Permission Context Example");
        System.out.println("=".repeat(60));
        System.out.println(
                "Demonstrates how PermissionContextState controls tool access.\n"
                    + "Mode: DEFAULT (ask for everything unless a rule matches).\n"
                    + "read_file → allow rule; write_file → ask rule; delete_file → deny rule.");
        System.out.println("=".repeat(60) + "\n");

        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new FileTools());

        // ── 构建权限上下文 ──
        //
        // 规则求值顺序，首个决定生效，参见 PermissionEngine：
        // 1. 显式拒绝规则
        // 2. 显式询问规则
        // 3. 工具检查：EXPLORE/ACCEPT_EDITS 模式及 ToolBase.checkPermissions
        // 4. 显式允许规则
        // 5. BYPASS 回退
        // 6. 默认 ASK，DONT_ASK 下转为 DENY
        PermissionContextState permCtx =
                PermissionContextState.builder()
                        // DEFAULT 模式对未被规则或前置检查决定的调用默认询问。
                        // 以下三条规则分别展示：
                        // read_file → 显式 ALLOW → 自动允许
                        // write_file → 显式 ASK → 暂停确认
                        // delete_file → 显式 DENY → 拒绝
                        //
                        // 注意：EXPLORE 在允许规则前自动拒绝非只读工具，
                        // 因此只适合设置 readOnly(true) 的
                        // ToolBase 工具。
                        .mode(PermissionMode.DEFAULT)
                        .addAllowRule(
                                "read_file",
                                new PermissionRule(
                                        "read_file",
                                        null,
                                        PermissionBehavior.ALLOW,
                                        "userSettings"))
                        .addAskRule(
                                "write_file",
                                new PermissionRule(
                                        "write_file", null, PermissionBehavior.ASK, "userSettings"))
                        .addDenyRule(
                                "delete_file",
                                new PermissionRule(
                                        "delete_file",
                                        null,
                                        PermissionBehavior.DENY,
                                        "userSettings"))
                        .build();

        // ── 将权限上下文接入智能体 ──
        ReActAgent agent =
                ReActAgent.builder()
                        .name("FileAgent")
                        .sysPrompt(
                                "You are a file assistant. You can read, write, and delete files.")
                        .model(
                                DashScopeChatModel.builder()
                                        .apiKey(apiKey)
                                        .modelName("qwen-max")
                                        .stream(true)
                                        .formatter(new DashScopeChatFormatter())
                                        .build())
                        .toolkit(toolkit)
                        .permissionContext(permCtx)
                        .middleware(new AgentTraceMiddleware())
                        .build();

        // ── 测试 read_file，应自动允许 ──
        System.out.println("--- Scenario 1: read_file (expect: auto-allowed) ---");
        Msg readResult =
                agent.call(new UserMessage("user", "Read the file /tmp/notes.txt")).block();
        printResult(readResult);

        // ── 测试 delete_file，应拒绝 ──
        System.out.println("\n--- Scenario 2: delete_file (expect: denied) ---");
        Msg deleteResult =
                agent.call(new UserMessage("user", "Delete the file /tmp/notes.txt")).block();
        printResult(deleteResult);

        // ── 测试 write_file，以 PERMISSION_ASKING 停止 ──
        System.out.println("\n--- Scenario 3: write_file (expect: stops for confirmation) ---");
        Msg writeResult =
                agent.call(new UserMessage("user", "Write 'hello world' to /tmp/out.txt")).block();
        if (writeResult != null
                && writeResult.getGenerateReason() == GenerateReason.PERMISSION_ASKING) {
            System.out.println("Agent paused for permission confirmation (PERMISSION_ASKING).");
            System.out.println(
                    "In a real UI, display the pending tool calls and let the user confirm.");
        } else {
            printResult(writeResult);
        }
    }

    private static void printResult(Msg msg) {
        if (msg == null) {
            System.out.println("(null response)");
        } else {
            System.out.println("Reason: " + msg.getGenerateReason());
            System.out.println("Agent: " + msg.getTextContent());
        }
    }

    /**
     * 简单文件操作桩。
     */
    public static class FileTools {

        /**
         * 模拟读取文件。
         * @param path 文件路径
         * @return 模拟文件内容
         */
        @Tool(name = "read_file", readOnly = true, description = "Read a file at the given path")
        public String readFile(
                @ToolParam(name = "path", description = "Absolute file path") String path) {
            return "Simulated content of: " + path;
        }

        /**
         * 模拟写文件。
         * @param path 目标路径
         * @param content 待写内容
         * @return 确认消息
         */
        @Tool(name = "write_file", description = "Write content to a file")
        public String writeFile(
                @ToolParam(name = "path", description = "Absolute file path") String path,
                @ToolParam(name = "content", description = "Text content to write")
                        String content) {
            return "Wrote " + content.length() + " chars to: " + path;
        }

        /**
         * 模拟删除文件。
         * @param path 待删除路径
         * @return 确认消息
         */
        @Tool(name = "delete_file", description = "Permanently delete a file")
        public String deleteFile(
                @ToolParam(name = "path", description = "Absolute file path to delete")
                        String path) {
            return "Deleted: " + path;
        }
    }
}
