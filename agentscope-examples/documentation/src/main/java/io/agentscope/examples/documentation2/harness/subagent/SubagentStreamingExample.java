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

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;

/**
 * 演示通过 {@code streamEvents()} 实时转发子智能体事件。
 * <p>父智能体通过 {@code agent_spawn} 同步生成子智能体时，其模型调用、文本增量、工具调用等事件实时进入父 {@code streamEvents()}。子事件带 {@link AgentEvent#getSource() source} 路径，例如 {@code "main/researcher"}，可与父事件 {@code source == null} 区分。
 * <p>事件流程及运行命令（原图示保留）：
 * <pre>
 *   AGENT_START                                      ← parent
 *     TEXT_BLOCK_DELTA …                             ← parent reasoning
 *     TOOL_CALL_START "agent_spawn"                  ← parent calls agent_spawn
 *     AGENT_START       (source="main/researcher")   ← child starts
 *       TEXT_BLOCK_DELTA  (source="main/researcher") ← child reasoning tokens
 *     AGENT_END         (source="main/researcher")   ← child done
 *     TOOL_RESULT_END                                ← parent gets tool result
 *     TEXT_BLOCK_DELTA …                             ← parent final response
 *   AGENT_END                                        ← parent done
 * </pre>
 * <p>事件图中文对照：父智能体开始并输出推理，调用 agent_spawn；子智能体开始、流式输出思考内容并结束，事件都带 source 路径；父智能体收到工具结果，输出最终回复并结束。parent 指父智能体，child 指子智能体。
 * <pre>
 *   export DASHSCOPE_API_KEY=your_key
 *   mvn exec:java -pl agentscope-examples/documentation \
 *       -Dexec.mainClass=io.agentscope.examples.documentation2.harness.subagent.SubagentStreamingExample
 * </pre>
 */
public class SubagentStreamingExample {

    public static void main(String[] args) throws InterruptedException {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("Subagent Streaming — streamEvents() event forwarding");
        System.out.println("=".repeat(60) + "\n");

        InMemoryAgentStateStore stateStore = new InMemoryAgentStateStore();
        // ── 以程序化子智能体声明构建父智能体 ──
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("orchestrator")
                        .sysPrompt(
                                "You are an orchestrator. When the user asks a question, "
                                        + "spawn the researcher subagent to investigate, then "
                                        + "summarize the findings.")
                        .model("dashscope:qwen-plus")
                        .subagent(
                                SubagentDeclaration.builder()
                                        .name("researcher")
                                        .description(
                                                "Research specialist. Use when the user needs"
                                                        + " in-depth investigation on a topic.")
                                        .inlineAgentsBody(
                                                "You are a research assistant. Investigate the"
                                                    + " given topic and provide a concise summary"
                                                    + " with key findings.")
                                        .persistSession(true)
                                        .build())
                        .stateStore(stateStore)
                        .build();

        RuntimeContext ctx = RuntimeContext.builder().sessionId("demo-subagent-stream").build();

        // ── 流式获取并区分父／子事件 ──
        System.out.println("User: What are the latest trends in LLM agents?\n");

        agent.streamEvents(new UserMessage("What are the latest trends in LLM agents?"), ctx)
                .doOnNext(SubagentStreamingExample::handleEvent)
                .blockLast();

        System.out.println("\n" + "=".repeat(60));

        Thread.sleep(
                10000); // 程序退出前稍等，确保事件打印完成。
    }

    private static String lastTextSource = null;

    private static void handleEvent(AgentEvent event) {
        String source = event.getSource();
        String prefix = (source != null) ? "[" + source + "] " : "";

        if (event instanceof AgentStartEvent e) {
            lastTextSource = null;
            if (source != null) {
                System.out.printf("%n── child agent started: %s ──%n", source);
            } else {
                System.out.printf("[AGENT_START] agent=%s%n", e.getName());
            }

        } else if (event instanceof TextBlockDeltaEvent e) {
            String currentSource = source != null ? source : "__parent__";
            if (!currentSource.equals(lastTextSource)) {
                if (lastTextSource != null) {
                    System.out.println();
                }
                System.out.print(prefix);
                lastTextSource = currentSource;
            }
            System.out.print(e.getDelta());

        } else if (event instanceof ToolCallStartEvent e) {
            lastTextSource = null;
            System.out.printf("%n%s[TOOL_CALL] %s%n", prefix, e.getToolCallName());

        } else if (event instanceof ToolResultEndEvent e) {
            lastTextSource = null;
            System.out.printf("%s[TOOL_RESULT_END] state=%s%n", prefix, e.getState());

        } else if (event instanceof AgentEndEvent) {
            lastTextSource = null;
            if (source != null) {
                System.out.printf("%n── child agent finished: %s ──%n", source);
            } else {
                System.out.println("\n[AGENT_END]");
            }
        }
    }
}
