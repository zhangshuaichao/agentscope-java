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
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import java.time.Duration;

/**
 * 演示模式 C：智能体在单次 {@code call()} 中调用 {@code wait_async_results} 等待后台结果，无需唤醒或用户交互。
 * <p>总线与注册表由工作区文件系统自动组装，只需 {@code .asyncToolTimeout()}。
 * <ol><li>{@code slow_analysis} 超过 2 秒等待阈值，转入后台。
 * <li>{@code wait_async_results(timeout_seconds=30)} 轮询收件箱。
 * <li>后台工具 5 秒后完成并推送结果。
 * <li>{@code wait_async_results} 检测消息后返回。
 * <li>InboxMiddleware 取出结果加入上下文，智能体回复。</ol>
 * <p>运行：
 * <pre>
 *   export DASHSCOPE_API_KEY=your_key
 *   mvn exec:java -pl agentscope-examples/documentation \
 *       -Dexec.mainClass=io.agentscope.examples.documentation2.harness.async.AsyncWaitModeExample
 * </pre>
 */
public class AsyncWaitModeExample {

    public static void main(String[] args) {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("Async Tool — Mode C (Wait)");
        System.out.println("=".repeat(60));
        System.out.println(
                "Agent uses wait_async_results to block until results arrive.\n"
                        + "All within one call(). Zero infrastructure configuration.\n");

        // ── 构建智能体，使用最小配置 ──

        InMemoryAgentStateStore stateStore = new InMemoryAgentStateStore();
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("WaitModeAgent")
                        .sysPrompt(
                                "You are an assistant that performs thorough analysis.\n"
                                        + "When a tool is offloaded to background, ALWAYS call "
                                        + "wait_async_results to wait for the result before "
                                        + "responding to the user.\n"
                                        + "After wait_async_results returns, the result will "
                                        + "appear in your context. Use it to give a complete "
                                        + "answer.")
                        .model("dashscope:qwen-plus")
                        .stateStore(stateStore)
                        .asyncToolTimeout(Duration.ofSeconds(2))
                        .build();

        agent.getToolkit().registerTool(new AsyncToolExample.SlowTools());

        System.out.println("Async timeout: 2s (tool takes 5s → offloaded)");
        System.out.println("System prompt instructs agent to wait_async_results\n");

        // ── 运行 ──

        System.out.println("─── Sending: 'Give me a detailed performance analysis' ───\n");

        long start = System.currentTimeMillis();

        Msg response =
                agent.streamEvents(
                                new UserMessage(
                                        "Call slow_analysis tool and give me a detailed performance"
                                                + " analysis"))
                        .doOnNext(AsyncWaitModeExample::printEvent)
                        .filter(e -> e instanceof AgentResultEvent)
                        .cast(AgentResultEvent.class)
                        .map(AgentResultEvent::getResult)
                        .blockLast();

        long elapsed = System.currentTimeMillis() - start;

        System.out.println("\n\n─── Agent response ───");
        System.out.println(response != null ? response.getTextContent() : "(no response)");
        System.out.printf(
                "\n(Total call() time: %.1fs — includes wait for background tool)\n",
                elapsed / 1000.0);
    }

    private static void printEvent(AgentEvent event) {
        if (event instanceof TextBlockDeltaEvent e) {
            System.out.print(e.getDelta());
        } else if (event instanceof ToolCallStartEvent e) {
            System.out.println("\n[Tool Call] " + e.getToolCallName());
        } else if (event instanceof ToolResultTextDeltaEvent e) {
            if (e.getDelta().contains("running in background")) {
                System.out.println("[Offloaded] " + AsyncToolExample.truncate(e.getDelta(), 80));
            } else if (e.getDelta().contains("results have arrived")) {
                System.out.println("[Wait Done] " + e.getDelta());
            } else {
                System.out.println("[Tool Result] " + AsyncToolExample.truncate(e.getDelta(), 100));
            }
        } else if (event instanceof HintBlockEvent e) {
            System.out.println("[HintBlock] " + AsyncToolExample.truncate(e.getHint(), 100));
        }
    }
}
