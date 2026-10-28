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
package io.agentscope.harness.coding;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiChannel;
import io.agentscope.harness.coding.tools.FetchUrlTool;
import io.agentscope.harness.coding.tools.GitHubApiTool;
import io.agentscope.harness.coding.tools.HttpRequestTool;
import io.agentscope.harness.coding.tools.WebSearchTool;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Collectors;

/**
 * 编码智能体的本地 CLI 入口。
 *
 * <p>启动 {@link CodingBootstrap}，获取 {@link ChatUiChannel}，在标准输入/输出上运行交互式 REPL。无需 webhook，适合本地开发和冒烟测试。
 *
 * <h2>运行</h2>
 * <pre>
 * export DASHSCOPE_API_KEY=your_key_here
 * mvn -pl agentscope-examples/agents/agentscope-codingagent -am compile \
 *     org.codehaus.mojo:exec-maven-plugin:3.6.3:java \
 *     -Dexec.mainClass=io.agentscope.harness.coding.CodingChatCli
 * </pre>
 *
 * <h2>子命令</h2>
 * <ul>
 * <li>{@code review <pr_url>}：对给定 PR URL 启动审查智能体</li>
 * <li>其他文本：发送给编码智能体</li>
 * </ul>
 *
 * <p>输入 {@code /exit} 或 {@code /quit} 退出。
 */
public class CodingChatCli {

    private static final String ANSI_RESET = "\u001B[0m";
    private static final String ANSI_BOLD = "\u001B[1m";
    private static final String ANSI_CYAN = "\u001B[36m";
    private static final String ANSI_GREEN = "\u001B[32m";
    private static final String ANSI_YELLOW = "\u001B[33m";

    public static void main(String[] args) throws Exception {
        printBanner();

        Path cwd = Paths.get(System.getProperty("user.dir"));
        Model model = buildModel();

        Toolkit codingToolkit = new Toolkit();
        codingToolkit.registerTool(new HttpRequestTool());
        codingToolkit.registerTool(new FetchUrlTool());
        codingToolkit.registerTool(new WebSearchTool());
        codingToolkit.registerTool(new GitHubApiTool());

        // skipConfigFile：本地 CLI 完全通过代码配置编码与审查智能体（下方
        // withDualCodingAgents 调用），因此启动器不得再尝试加载
        // ~/.agentscope/codingagent/agentscope.json；用户若手动编辑过该文件，
        // 其条目会与此处装配冲突并覆盖它。审查工具集
        // 为 null，因为 CLI 不接入 dispatcher / findings store；`review <pr_url>`
        // 会降级为普通提示词。
        CodingBootstrap bootstrap =
                CodingBootstrap.builder()
                        .cwd(cwd)
                        .model(model)
                        .skipConfigFile(true)
                        .withDualCodingAgents(codingToolkit, null)
                        .build();

        ChatUiChannel chat = bootstrap.chatUiChannel();

        System.out.println(ANSI_GREEN + "✓ Coding Agent ready" + ANSI_RESET);
        System.out.println("  Workspace: " + CodingBootstrap.DEFAULT_WORKSPACE_ROOT);
        System.out.println(
                "  Model: "
                        + io.agentscope.harness.coding.agent.CodingAgentFactory.resolveModelId());
        System.out.println();
        System.out.println("Type your message, or:");
        System.out.println(
                "  "
                        + ANSI_CYAN
                        + "review <pr_url>"
                        + ANSI_RESET
                        + "  — trigger reviewer agent on a PR");
        System.out.println("  " + ANSI_CYAN + "/exit" + ANSI_RESET + "             — quit");
        System.out.println();
        System.out.println("Try one of these (the agent works inside its own workspace):");
        System.out.println(
                "  " + ANSI_CYAN + "\"write hello.txt with a haiku about Java\"" + ANSI_RESET);
        System.out.println(
                "  "
                        + ANSI_CYAN
                        + "\"fetch"
                        + " https://github.com/anthropics/anthropic-sdk-python/blob/main/README.md"
                        + " and summarize it\""
                        + ANSI_RESET);
        System.out.println(
                "  "
                        + ANSI_CYAN
                        + "\"clone https://github.com/<owner>/<repo> into the workspace and tell me"
                        + " what it does\""
                        + ANSI_RESET);
        System.out.println();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            String line;
            while (true) {
                System.out.print(ANSI_BOLD + "You> " + ANSI_RESET);
                System.out.flush();
                line = reader.readLine();
                if (line == null
                        || line.equalsIgnoreCase("/exit")
                        || line.equalsIgnoreCase("/quit")) {
                    System.out.println("Goodbye.");
                    break;
                }
                if (line.isBlank()) {
                    continue;
                }

                if (line.startsWith("review ")) {
                    String prUrl = line.substring("review ".length()).trim();
                    dispatchReviewer(chat, prUrl);
                    continue;
                }

                System.out.print(ANSI_YELLOW + "Agent> " + ANSI_RESET);
                System.out.flush();
                try {
                    Msg reply = chat.send(line).block();
                    if (reply != null) {
                        String text =
                                reply.getContent().stream()
                                        .filter(b -> b instanceof TextBlock)
                                        .map(b -> ((TextBlock) b).getText())
                                        .collect(Collectors.joining("\n"));
                        System.out.println(text.isEmpty() ? "[no text response]" : text);
                    }
                } catch (Exception e) {
                    System.err.println("[Error] " + e.getMessage());
                }
                System.out.println();
            }
        }

        bootstrap.stop();
    }

    private static void dispatchReviewer(ChatUiChannel chat, String prUrl) {
        if (prUrl.isBlank()) {
            System.err.println("[Error] Usage: review <pr_url>");
            return;
        }
        System.out.println(
                ANSI_YELLOW + "Reviewer> " + ANSI_RESET + "Triggering review of: " + prUrl);
        String reviewPrompt =
                "Please review the following pull request and provide detailed feedback: " + prUrl;
        try {
            Msg reply = chat.send(reviewPrompt).block();
            if (reply != null) {
                String text =
                        reply.getContent().stream()
                                .filter(b -> b instanceof TextBlock)
                                .map(b -> ((TextBlock) b).getText())
                                .collect(Collectors.joining("\n"));
                System.out.println(text.isEmpty() ? "[no reviewer response]" : text);
            }
        } catch (Exception e) {
            System.err.println("[Error] Reviewer dispatch failed: " + e.getMessage());
        }
        System.out.println();
    }

    private static Model buildModel() {
        return io.agentscope.harness.coding.agent.CodingAgentFactory.buildModel();
    }

    private static void printBanner() {
        System.out.println(ANSI_BOLD + ANSI_CYAN);
        System.out.println("  ╔═══════════════════════════════════════════╗");
        System.out.println("  ║        AgentScope Coding Agent CLI        ║");
        System.out.println("  ╚═══════════════════════════════════════════╝");
        System.out.println(ANSI_RESET);
    }
}
