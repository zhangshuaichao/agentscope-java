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
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.dashscope.formatter.DashScopeChatFormatter;
import java.util.List;
import java.util.Scanner;

/**
 * StreamingConsoleExample：通过 {@link ReActAgent#stream} 实现控制台实时输出。
 * <p>{@code agent.stream()} 返回 {@link reactor.core.publisher.Flux}&lt;{@link Event}&gt;，每项包含 {@link EventType} 和 {@link Msg}。订阅后可逐块处理，无需等完整响应。
 * <p>类型：{@code REASONING} 为助手文本／思考增量及最终推理消息；{@code TOOL_RESULT} 为工具结果；{@code HINT} 为注入的记忆、RAG、计划上下文。{@code isLast()} 区分增量与阶段最终消息。
 * <p>相比 {@code agent.call()}，{@code call()} 返回最终结果的 Mono，本身不等于阻塞，显式 block 才等待；{@code stream()} 提供增量，适合长响应。运行：
 * <pre>
 *   export DASHSCOPE_API_KEY=your_key
 *   mvn exec:java -pl agentscope-examples/documentation \
 *       -Dexec.mainClass=io.agentscope.examples.documentation2.streaming.StreamingConsoleExample
 * </pre>
 */
public class StreamingConsoleExample {

    /**
     * 运行流式控制台示例。
     * @param args 命令行参数，此处忽略
     */
    public static void main(String[] args) {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("Streaming Console Example");
        System.out.println("=".repeat(60));
        System.out.println(
                "Demonstrates agent.stream() with real-time token printing.\n"
                        + "Reasoning tokens are printed incrementally; tool results are\n"
                        + "printed when the tool completes.");
        System.out.println("=".repeat(60) + "\n");

        String apiKey = System.getenv("DASHSCOPE_API_KEY");

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new SimpleCalcTools());

        ReActAgent agent =
                ReActAgent.builder()
                        .name("StreamingAgent")
                        .sysPrompt("You are a helpful assistant with math tools.")
                        .model(
                                DashScopeChatModel.builder()
                                        .apiKey(apiKey)
                                        .modelName("qwen-max")
                                        .stream(true)
                                        .formatter(new DashScopeChatFormatter())
                                        .build())
                        .toolkit(toolkit)
                        .build();

        // ── 配置流选项 ──
        //
        // eventTypes：接收的类别，REASONING、TOOL_RESULT、HINT 或 ALL
        // incremental：true 时每项仅含新增量，false 为累计内容
        // includeReasoningChunk：接收部分推理块，true 启用实时输出
        StreamOptions streamOptions =
                StreamOptions.builder()
                        .eventTypes(EventType.REASONING, EventType.TOOL_RESULT)
                        .incremental(true)
                        .includeReasoningChunk(true)
                        .build();

        Scanner scanner = new Scanner(System.in);
        System.out.println("Type a question and press Enter. Type 'exit' to quit.\n");

        while (scanner.hasNextLine()) {
            String userInput = scanner.nextLine().trim();
            if ("exit".equalsIgnoreCase(userInput)) {
                break;
            }
            if (userInput.isBlank()) {
                continue;
            }

            Msg userMsg = new UserMessage("user", userInput);

            System.out.print("\nAgent: ");

            // ── 订阅事件流 ──
            //
            // stream() 返回 Flux<Event>，每项是一个流式事件。
            // blockLast() 在当前线程驱动并等待 Flux 完成。
            agent.stream(List.of(userMsg), streamOptions, (RuntimeContext) null)
                    .doOnNext(event -> printEvent(event))
                    .blockLast();

            System.out.println("\n");
        }
    }

    /**
     * 按类型打印事件。REASONING 增量不换行，形成实时输入效果；TOOL_RESULT 独立换行并带标签。
     * @param event 流发出的事件
     */
    private static void printEvent(Event event) {
        if (event.getType() == EventType.REASONING) {
            String text = event.getMessage() != null ? event.getMessage().getTextContent() : "";
            if (text != null && !text.isBlank()) {
                if (event.isLast()) {
                    // 最终推理消息已增量输出，跳过重复打印
                } else {
                    // 增量块，不换行打印
                    System.out.print(text);
                }
            }
        } else if (event.getType() == EventType.TOOL_RESULT) {
            if (event.isLast()) {
                String text = event.getMessage() != null ? event.getMessage().getTextContent() : "";
                System.out.println("\n[tool result] " + text);
            }
        }
    }

    /**
     * 演示用简单算术工具。
     */
    public static class SimpleCalcTools {

        /**
         * 两数相加。
         * @param a 第一个操作数
         * @param b 第二个操作数
         * @return 和的字符串
         */
        @Tool(name = "add", description = "Add two numbers")
        public String add(
                @ToolParam(name = "a", description = "First number") double a,
                @ToolParam(name = "b", description = "Second number") double b) {
            return String.valueOf(a + b);
        }

        /**
         * 两数相乘。
         * @param a 第一个操作数
         * @param b 第二个操作数
         * @return 积的字符串
         */
        @Tool(name = "multiply", description = "Multiply two numbers")
        public String multiply(
                @ToolParam(name = "a", description = "First number") double a,
                @ToolParam(name = "b", description = "Second number") double b) {
            return String.valueOf(a * b);
        }
    }
}
