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
package io.agentscope.examples.documentation2.state;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.dashscope.formatter.DashScopeChatFormatter;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * SessionExample：演示持久化对话。
 * <p>迁移：{@code legacy.session.JsonFileAgentStateStore} 改为 {@code io.agentscope.core.state.JsonFileAgentStateStore}；移除 {@code legacy.memory.InMemoryMemory} 及 {@code .memory()}，历史由 {@code AgentState} 保存；旧 {@code agent.loadFrom(session, id)} / {@code agent.saveTo(session, id)} 改为配置状态存储及会话，历史表达 {@code .stateStore(session).sessionId(id)} 的具体方法名以本例 builder 为准；{@code agent.getMemory().getMessages()} 改为 {@code agent.getAgentState().getContext()}。
 */
public class StateExample {

    private static final String DEFAULT_SESSION_ID = "default_session";
    private static final BufferedReader READER =
            new BufferedReader(new InputStreamReader(System.in));

    /**
     * 运行会话示例。
     * @param args 命令行参数，此处忽略
     * @throws Exception I/O 错误时
     */
    public static void main(String[] args) throws Exception {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("AgentStateStore Example");
        System.out.println("=".repeat(60));
        System.out.println(
                "This example demonstrates persistent conversation sessions.\n"
                        + "Your conversations are saved and can be resumed later.");
        System.out.println("=".repeat(60) + "\n");

        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        String sessionId = getSessionId();

        Path sessionPath =
                Paths.get(System.getProperty("user.home"), ".agentscope", "examples", "sessions");
        AgentStateStore stateStore = new JsonFileAgentStateStore(sessionPath);

        // 配置会话后，首次调用加载已有历史，
        // 每次交互后自动持久化。
        ReActAgent agent =
                ReActAgent.builder()
                        .name("Assistant")
                        .sysPrompt(
                                "You are a helpful AI assistant with persistent memory. You can"
                                        + " remember information from previous conversations.")
                        .toolkit(new Toolkit())
                        .stateStore(stateStore)
                        .defaultSessionId(sessionId)
                        .model(
                                DashScopeChatModel.builder()
                                        .apiKey(apiKey)
                                        .modelName("qwen-max")
                                        .stream(true)
                                        .enableThinking(false)
                                        .formatter(new DashScopeChatFormatter())
                                        .build())
                        .build();

        runConversation(agent, sessionId);
    }

    private static String getSessionId() throws Exception {
        System.out.print("Enter session ID (default: " + DEFAULT_SESSION_ID + "): ");
        String sessionId = READER.readLine().trim();
        if (sessionId.isEmpty()) {
            sessionId = DEFAULT_SESSION_ID;
        }
        System.out.println("Using session: " + sessionId + "\n");
        return sessionId;
    }

    private static void runConversation(ReActAgent agent, String sessionId) throws Exception {
        System.out.println("=== Chat Started ===");
        System.out.println("Commands: 'exit' to quit, 'history' to view message history\n");

        // 展示从会话加载的上下文大小
        int ctxSize = agent.getAgentState().getContext().size();
        if (ctxSize > 0) {
            System.out.println("AgentStateStore resumed — " + ctxSize + " messages loaded.");
            System.out.println("Type 'history' to view them.\n");
        } else {
            System.out.println("New session: " + sessionId + "\n");
        }

        while (true) {
            System.out.print("You> ");
            String input = READER.readLine();

            if (input == null || "exit".equalsIgnoreCase(input.trim())) {
                break;
            }
            if (input.trim().isEmpty()) {
                continue;
            }
            if ("history".equalsIgnoreCase(input.trim())) {
                showHistory(agent);
                continue;
            }

            try {
                Msg userMsg = new UserMessage(input);

                Msg response = agent.call(userMsg).block();
                if (response != null) {
                    System.out.println("Agent> " + response.getTextContent() + "\n");
                } else {
                    System.out.println("Agent> [No response]\n");
                }
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
            }
        }

        System.out.println("\nSession '" + sessionId + "' is persisted automatically.");
        System.out.println("Resume this conversation later by entering the same session ID.");
    }

    private static void showHistory(ReActAgent agent) {
        List<Msg> messages = agent.getAgentState().getContext();

        if (messages.isEmpty()) {
            System.out.println("\n[No message history]\n");
            return;
        }

        System.out.println("\n=== Message History ===");
        for (int i = 0; i < messages.size(); i++) {
            Msg msg = messages.get(i);
            String role = msg.getRole() == MsgRole.USER ? "You" : "Agent";
            String content = msg.getTextContent();
            if (content.length() > 100) {
                content = content.substring(0, 97) + "...";
            }
            System.out.println((i + 1) + ". " + role + ": " + content);
        }
        System.out.println("Total messages: " + messages.size());
        System.out.println("=======================\n");
    }
}
