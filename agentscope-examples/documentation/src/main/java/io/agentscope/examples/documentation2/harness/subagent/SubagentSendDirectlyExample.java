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
package io.agentscope.examples.documentation2.harness.subagent;

import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.SubagentExposedEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiChannel;
import io.agentscope.harness.agent.gateway.channel.chatui.SendOptions;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 通过 {@code sendStream()} 流式获取事件，并通过 {@link SubagentExposedEvent} 与公开子智能体对话。
 * <p>{@code sendStream()} 返回 {@code Flux<}{@link AgentEvent}{@code >}，与 {@code agent.streamEvents()} 同样细粒度，但经过网关会话管理，适合 SSE 聊天界面。
 * <p>生成子智能体时设置 {@code expose_to_user=true}，流中会发出携带 {@code subagentId} 的 {@link SubagentExposedEvent}。客户端随后可用 {@code sendToSubagentStream(subagentId, text)} 直接发送，绕过父智能体。
 * <p>运行：
 * <pre>
 *   export DASHSCOPE_API_KEY=your_key
 *   mvn exec:java -pl agentscope-examples/documentation \
 *       -Dexec.mainClass=io.agentscope.examples.documentation2.harness.subagent.SubagentSendDirectlyExample
 * </pre>
 */
public class SubagentSendDirectlyExample {

    /**
     * 运行流式与子智能体示例。
     * @param args 命令行参数，此处忽略
     */
    public static void main(String[] args) {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("Channel — Streaming + SubagentExposedEvent");
        System.out.println("=".repeat(60) + "\n");

        InMemoryAgentStateStore stateStore = new InMemoryAgentStateStore();
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("orchestrator")
                        .sysPrompt(
                                "You are an orchestrator. When asked to research, spawn a "
                                        + "researcher subagent with expose_to_user=true.")
                        .model("dashscope:qwen-plus")
                        .stateStore(stateStore)
                        .build();

        ChatUiChannel chat = agent.channel(ChatUiChannel.create());

        // 保留发现的 subagentId，流结束后继续使用。
        AtomicReference<String> discoveredSubagentId = new AtomicReference<>();

        // ── 流式获取主智能体事件 ──
        //
        // sendStream() 返回 Flux<AgentEvent>，适合 SSE 转发。
        // 智能体调用 agent_spawn(expose_to_user=true) 时收到 SubagentExposedEvent。
        chat.sendStream(SendOptions.userId("user-1"), "Please research recent AI trends")
                .doOnNext(
                        event -> {
                            if (event instanceof AgentStartEvent e) {
                                System.out.printf("[AGENT_START] agent=%s%n", e.getName());

                            } else if (event instanceof TextBlockDeltaEvent e) {
                                System.out.print(e.getDelta());

                            } else if (event instanceof ToolCallStartEvent e) {
                                System.out.printf("%n[TOOL_CALL] %s%n", e.getToolCallName());

                            } else if (event instanceof SubagentExposedEvent e) {
                                discoveredSubagentId.set(e.getSubagentId());
                                System.out.printf(
                                        "%n[SUBAGENT_EXPOSED] subagentId=%s  agentId=%s"
                                                + "  label=%s%n",
                                        e.getSubagentId(), e.getAgentId(), e.getLabel());

                            } else if (event instanceof AgentEndEvent) {
                                System.out.println("\n[AGENT_END]");
                            }
                        })
                .blockLast();

        // ── 直接与公开子智能体对话 ──
        //
        // 若智能体公开了子智能体，可通过 subagentId
        // 直接发消息，完全绕过父智能体。
        String subagentId = discoveredSubagentId.get();
        if (subagentId != null) {
            System.out.println("\nTalking directly to subagent: " + subagentId);

            chat.sendToSubagentStream(
                            subagentId,
                            "Focus on LLM agents specifically. Compare LLM agent frameworks (e.g.,"
                                    + " LangChain vs. AgentScope vs. LlamaIndex)")
                    .doOnNext(
                            event -> {
                                if (event instanceof TextBlockDeltaEvent e) {
                                    System.out.print(e.getDelta());
                                }
                            })
                    .blockLast();

            System.out.println();
        }
    }
}
