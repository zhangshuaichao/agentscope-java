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
package io.agentscope.examples.documentation2.state;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.dashscope.formatter.DashScopeChatFormatter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * SessionAutoSaveExample：演示 {@link ReActAgent} 结合 {@link AgentStateStore} 的自动保存／恢复。
 * <p>旧说明中的 {@code session}、{@code sessionKey} 及 {@code stateStore.get(userId, sessionId, "agent_state", AgentState.class)} / {@code stateStore.save(userId, sessionId, "agent_state", agentState)} 是历史概念表达，不是此版本应照抄的调用方式。以本示例 builder 和实际状态存储 API 为准；执行链按会话加载，{@code call()} / {@code stream()} 结束时保存。
 * <p>{@link io.agentscope.core.shutdown.GracefulShutdownManager} 提供关闭协调，但不能将正常关闭保存视为进程崩溃保证。{@link JsonFileAgentStateStore} 适合本地开发测试；历史示意文件名 {@code <sessionId>_agent_state.json} 不代替实现中的实际路径编码，生产共享状态应选择 Redis 或数据库后端。运行：
 * <pre>
 *   export DASHSCOPE_API_KEY=your_key
 *   mvn exec:java -pl agentscope-examples/documentation \
 *       -Dexec.mainClass=io.agentscope.examples.documentation2.state.StateAutoSaveExample
 * </pre>
 */
public class StateAutoSaveExample {

    private static final String SESSION_ID = "auto-save-demo";
    private static final String SESSION_DIR = "/tmp/agentscope-sessions";

    /**
     * 运行会话自动保存演示。
     * @param args 命令行参数，此处忽略
     * @throws Exception 会话目录初始化失败时
     */
    public static void main(String[] args) throws Exception {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("AgentStateStore Auto-Save Example");
        System.out.println("=".repeat(60));
        System.out.println(
                "Demonstrates automatic history persistence via AgentStateStore.\n"
                        + "AgentStateStore data is stored in: "
                        + SESSION_DIR);
        System.out.println("=".repeat(60) + "\n");

        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        Path sessionDir = Paths.get(SESSION_DIR);
        Files.createDirectories(sessionDir);

        // ── 阶段 1：首个智能体实例，从空状态开始发送两条消息 ──
        System.out.println("═══ Phase 1: First agent instance ═══");

        AgentStateStore session1 = new JsonFileAgentStateStore(sessionDir);
        ReActAgent agent1 = buildAgent("alice", apiKey, session1);

        Msg r1 = agent1.call(new UserMessage("user", "My favourite colour is blue.")).block();
        System.out.println("Agent: " + (r1 != null ? r1.getTextContent() : "(null)"));
        System.out.println(
                "History size after call 1: "
                        + agent1.getAgentState().getContext().size()
                        + " messages");

        Msg r2 = agent1.call(new UserMessage("user", "My lucky number is 7.")).block();
        System.out.println("Agent: " + (r2 != null ? r2.getTextContent() : "(null)"));
        System.out.println(
                "History size after call 2: "
                        + agent1.getAgentState().getContext().size()
                        + " messages");

        // 关闭首个智能体，执行生命周期清理；状态已由
        // 每次调用结束后的自动保存流程写入，此处展示完整资源管理。
        agent1.close();
        System.out.println("Agent 1 closed. State saved to: " + sessionDir);

        // ── 阶段 2：第二个实例从会话状态恢复 ──
        System.out.println("\n═══ Phase 2: Second agent instance (same session) ═══");

        AgentStateStore session2 = new JsonFileAgentStateStore(sessionDir);
        ReActAgent agent2 = buildAgent("alice", apiKey, session2);

        System.out.println(
                "History size after reload: "
                        + agent2.getAgentState().getContext().size()
                        + " messages");

        // 智能体记得阶段 1 的对话：
        Msg r3 =
                agent2.call(
                                new UserMessage(
                                        "user", "What is my favourite colour and lucky number?"))
                        .block();
        System.out.println("Agent: " + (r3 != null ? r3.getTextContent() : "(null)"));
        System.out.println("Expected: agent recalls blue and 7 from the previous session.");

        agent2.close();
    }

    /**
     * 构建结合 {@link JsonFileAgentStateStore} 的 {@link ReActAgent}，使用给定用户标识作为示例会话键。
     * <p>历史称呼 {@code sessionKey} 指历史隔离维度；实际 builder 使用的会话字段见代码，不能代替生产用户认证。
     * @param userId 用户标识，此示例用作会话键
     * @param apiKey DashScope API 密钥
     * @param stateStore 后端状态存储
     * @return 配置好的智能体
     */
    private static ReActAgent buildAgent(String userId, String apiKey, AgentStateStore stateStore) {
        return ReActAgent.builder()
                .name("SessionAgent")
                .sysPrompt("You are a helpful assistant. Remember what the user tells you.")
                .model(
                        DashScopeChatModel.builder().apiKey(apiKey).modelName("qwen-plus").stream(
                                        true)
                                .formatter(new DashScopeChatFormatter())
                                .build())
                // stateStore() 与 defaultSessionId() 组装按会话加载和
                // 调用结束后自动保存
                .stateStore(stateStore)
                .defaultSessionId(SESSION_ID + "-" + userId)
                .build();
    }
}
