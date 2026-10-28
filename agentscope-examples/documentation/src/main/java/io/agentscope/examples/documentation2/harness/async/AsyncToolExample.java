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
package io.agentscope.examples.documentation2.harness.async;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.HintBlockEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.harness.agent.HarnessAgent;
import java.time.Duration;

/**
 * 演示异步工具执行：超过等待阈值的工具转到后台，通过收件箱交付结果。
 * <p>无需显式配置 MessageBus 或 AsyncToolRegistry，HarnessAgent 从解析后的文件系统创建工作区默认实现；只需 {@code .asyncToolTimeout()}。
 * <p>模式 A：下次推理由 InboxMiddleware 注入结果；模式 B：结果到达后 WakeupDispatcher 触发新 call()；模式 C：智能体调用 {@code wait_async_results} 等待结果。
 * <p>{@code slow_analysis} 睡眠 5 秒，阈值为 2 秒，因此转入后台。运行：
 * <pre>
 *   export DASHSCOPE_API_KEY=your_key
 *   mvn exec:java -pl agentscope-examples/documentation \
 *       -Dexec.mainClass=io.agentscope.examples.documentation2.harness.async.AsyncToolExample
 * </pre>
 */
public class AsyncToolExample {

    public static void main(String[] args) {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("Async Tool Example");
        System.out.println("=".repeat(60));
        System.out.println(
                "Tools exceeding the timeout are offloaded to background.\n"
                        + "MessageBus + AsyncToolRegistry are auto-created from workspace.\n");

        // ── 构建智能体，只需设置 asyncToolTimeout ──

        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("AsyncToolAgent")
                        .sysPrompt(
                                "You are an assistant with async tool capabilities.\n"
                                        + "When a tool runs in background, you have two options:\n"
                                        + "1. Call wait_async_results to wait for the result\n"
                                        + "2. Reply to the user and the result will arrive later\n"
                                        + "Choose based on the user's needs.")
                        .model("dashscope:qwen-plus")
                        .asyncToolTimeout(Duration.ofSeconds(2))
                        .build();

        agent.getToolkit().registerTool(new SlowTools());

        System.out.println("Tools: slow_analysis (5s), wait_async_results (auto-registered)");
        System.out.println("Async timeout: 2s (tool will be offloaded)\n");

        // ── 运行 ──

        System.out.println("─── Sending: 'Analyze the performance data' ───\n");

        Msg response =
                agent.streamEvents(new UserMessage("Analyze the performance data"))
                        .doOnNext(AsyncToolExample::printEvent)
                        .filter(e -> e instanceof AgentResultEvent)
                        .cast(AgentResultEvent.class)
                        .map(AgentResultEvent::getResult)
                        .blockLast();

        System.out.println("\n─── Agent response ───");
        System.out.println(response != null ? response.getTextContent() : "(no response)");

        // ── 等待后台结果 ──

        System.out.println("\n─── Waiting 6s for background tool to finish... ───");
        try {
            Thread.sleep(6000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        System.out.println("(Background result is now in inbox, persisted on workspace)");
        System.out.println("Done.");
    }

    static void printEvent(AgentEvent event) {
        if (event instanceof TextBlockDeltaEvent e) {
            System.out.print(e.getDelta());
        } else if (event instanceof ToolResultTextDeltaEvent e) {
            System.out.println("[Tool Result] " + truncate(e.getDelta(), 100));
        } else if (event instanceof HintBlockEvent e) {
            System.out.println("[HintBlock] " + truncate(e.getHint(), 100));
        }
    }

    static String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) + "..." : s;
    }

    /**
     * 故意较慢的工具操作，用于展示异步后台执行。
     */
    public static class SlowTools {

        @Tool(
                name = "slow_analysis",
                description =
                        "Perform a thorough data analysis. This operation takes several"
                                + " seconds to complete.")
        public String analyze(
                @ToolParam(name = "topic", description = "The topic to analyze") String topic) {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "Analysis interrupted.";
            }
            return "Analysis of '"
                    + topic
                    + "' complete.\n\n"
                    + "Key findings:\n"
                    + "- Throughput increased 23% over the last quarter\n"
                    + "- P99 latency reduced from 450ms to 280ms\n"
                    + "- Error rate dropped to 0.02%\n"
                    + "- Top bottleneck: database connection pooling";
        }
    }
}
