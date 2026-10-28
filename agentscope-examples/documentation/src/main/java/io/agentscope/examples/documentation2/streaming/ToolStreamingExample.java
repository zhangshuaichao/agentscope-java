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
package io.agentscope.examples.documentation2.streaming;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolProgressEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolEmitter;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;

/**
 * ToolStreamingExample：工具通过 {@link ToolEmitter} 发出进度，调用方经 {@code streamEvents()} 接收 {@link ToolProgressEvent}。
 * <ol><li>{@code @Tool} 方法声明 {@link ToolEmitter} 参数，无需注解，框架自动注入。
 * <li>执行中调用 {@code emitter.emit(ToolResultBlock.text(...))} 推送中间进度。
 * <li>框架将块路由为 {@link ToolProgressEvent}。
 * <li>最终 {@code return} 值才是交给 LLM 的工具结果，通过 {@link ToolResultTextDeltaEvent} 单独发出；进度块仅供 UI，不发送给 LLM。</ol>
 * <p>原事件序列及运行命令：
 * <pre>
 *   TOOL_RESULT_START        (tool execution begins)
 *     TOOL_PROGRESS           ("Step 1: Fetching data...")   — UI only, emitted by the tool
 *     TOOL_PROGRESS           ("Step 2: Processing...")
 *     TOOL_RESULT_TEXT_DELTA  ("Quantum computing...")       — the return value, sent to the LLM
 *   TOOL_RESULT_END          (state=SUCCESS)
 * </pre>
 * <p>事件图中文对照：工具执行开始后，TOOL_PROGRESS 报告“步骤 1：获取数据”“步骤 2：处理”，仅供界面使用；TOOL_RESULT_TEXT_DELTA 才是工具返回值并交给模型，最后以 SUCCESS 结束。
 * <pre>
 *   export DASHSCOPE_API_KEY=your_key
 *   mvn exec:java -pl agentscope-examples/documentation \
 *       -Dexec.mainClass=io.agentscope.examples.documentation2.streaming.ToolStreamingExample
 * </pre>
 */
public class ToolStreamingExample {

    public static void main(String[] args) {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("Tool Streaming Example");
        System.out.println("=".repeat(60));
        System.out.println("Shows how tools emit streaming progress via ToolEmitter.");
        System.out.println("=".repeat(60) + "\n");

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new ResearchTools());

        ReActAgent agent =
                ReActAgent.builder()
                        .name("ResearchAgent")
                        .sysPrompt(
                                "You are a research assistant. Use the research_topic tool to"
                                        + " investigate topics.")
                        .model("dashscope:qwen-plus")
                        .toolkit(toolkit)
                        .build();

        Msg userMsg = new UserMessage("user", "Research the current state of quantum computing");

        System.out.println("User: Research the current state of quantum computing\n");
        System.out.println("─".repeat(60));

        agent.streamEvents(userMsg).doOnNext(ToolStreamingExample::handleEvent).blockLast();

        System.out.println("\n" + "─".repeat(60));
        System.out.println("Done.");
    }

    private static void handleEvent(AgentEvent event) {
        if (event instanceof ToolResultStartEvent e) {
            System.out.printf(
                    "%n[Tool Started] %s (id=%s)%n", e.getToolCallName(), e.getToolCallId());

        } else if (event instanceof ToolProgressEvent e) {
            // 来自 emitter.emit() 的进度块，仅用于 UI，绝非最终结果。
            if (e.getContent() instanceof TextBlock text) {
                System.out.println("  ▸ " + text.getText());
            } else {
                System.out.printf(
                        "  ▸ [data block: %s]%n", e.getContent().getClass().getSimpleName());
            }

        } else if (event instanceof ToolResultTextDeltaEvent e) {
            // 工具返回值，也是模型接收到的内容。
            System.out.println("  = " + e.getDelta());

        } else if (event instanceof ToolResultEndEvent e) {
            System.out.printf("[Tool Finished] id=%s  state=%s%n", e.getToolCallId(), e.getState());
        }
    }

    /**
     * 通过 {@link ToolEmitter} 流式更新进度的工具类。
     * <p>在 @Tool 方法声明 {@code ToolEmitter} 参数，框架自动注入，无需 @ToolParam。执行时调用 {@code emitter.emit(...)} 向事件流推送中间结果。
     */
    public static class ResearchTools {

        @Tool(description = "Research a topic and return a summary with streaming progress updates")
        public String research_topic(
                @ToolParam(name = "topic", description = "The topic to research") String topic,
                ToolEmitter emitter) {

            // 步骤 1：发出进度
            emitter.emit(ToolResultBlock.text("Searching for information on: " + topic));
            sleep(500);

            // 步骤 2：发出进度
            emitter.emit(ToolResultBlock.text("Found 12 relevant sources. Analyzing..."));
            sleep(500);

            // 步骤 3：发出进度
            emitter.emit(ToolResultBlock.text("Cross-referencing findings..."));
            sleep(500);

            // 步骤 4：发出进度
            emitter.emit(ToolResultBlock.text("Generating summary..."));
            sleep(300);

            // 最终返回值才是 LLM 看到的工具结果。
            // 上方全部 emitter.emit() 调用仅供 UI 使用。
            return "Research summary for '"
                    + topic
                    + "': Quantum computing has made significant progress"
                    + " in 2025-2026, with major advances in error correction (Google's"
                    + " Willow chip achieving below-threshold error rates), increased qubit"
                    + " counts (IBM's 1000+ qubit processors), and early commercial"
                    + " applications in drug discovery and materials science.";
        }

        private static void sleep(long ms) {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
