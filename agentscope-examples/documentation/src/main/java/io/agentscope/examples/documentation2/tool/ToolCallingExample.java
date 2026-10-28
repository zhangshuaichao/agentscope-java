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
package io.agentscope.examples.documentation2.tool;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.dashscope.formatter.DashScopeChatFormatter;
import io.agentscope.harness.agent.middleware.AgentTraceMiddleware;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * ToolCallingExample：为智能体配置自定义工具。迁移时移除 {@code .memory(new InMemoryMemory())}，2.0 不再需要。
 */
public class ToolCallingExample {

    /**
     * 运行工具调用示例。
     * @param args 命令行参数，此处忽略
     * @throws Exception I/O 错误时
     */
    public static void main(String[] args) throws Exception {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("Tool Calling Example");
        System.out.println("=".repeat(60));
        System.out.println(
                "This example demonstrates how to equip an Agent with tools.\n"
                        + "The agent has access to: time checking, calculator, and search.");
        System.out.println("=".repeat(60) + "\n");

        String apiKey = System.getenv("DASHSCOPE_API_KEY");

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new SimpleTools());

        System.out.println("Registered tools:");
        System.out.println("  - get_current_time: Get current time in a timezone");
        System.out.println("  - calculate: Evaluate simple math expressions");
        System.out.println("  - search: Simulate search functionality\n");

        ReActAgent agent =
                ReActAgent.builder()
                        .name("ToolAgent")
                        .sysPrompt(
                                "You are a helpful assistant with access to tools. "
                                        + "Use tools when needed to answer questions accurately. "
                                        + "Always explain what you're doing when using tools.")
                        .model(
                                DashScopeChatModel.builder()
                                        .apiKey(apiKey)
                                        .modelName("qwen-max")
                                        .stream(true)
                                        .enableThinking(false)
                                        .formatter(new DashScopeChatFormatter())
                                        .build())
                        .toolkit(toolkit)
                        .middleware(new AgentTraceMiddleware())
                        .build();

        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        System.out.println("Chat started. Type 'exit' to quit.\n");

        while (true) {
            System.out.print("You: ");
            String input = reader.readLine();
            if (input == null || input.trim().equalsIgnoreCase("exit")) {
                System.out.println("\nGoodbye!");
                break;
            }
            if (input.isBlank()) {
                continue;
            }
            Msg userMsg = new UserMessage(input.trim());
            System.out.print("\nAgent: ");
            //            agent.streamEvents(userMsg)
            //                    .doOnNext(
            //                            event -> {
            //                                if (event instanceof TextBlockDeltaEvent e) {
            //                                    System.out.print(e.getDelta());
            //                                }
            //                            })
            //                    .blockLast();
            agent.call(userMsg).block();
            System.out.println("\n");
        }
    }

    /**
     * 演示用简单工具，每个 {@code @Tool} 方法成为智能体可调用工具。
     */
    public static class SimpleTools {

        /**
         * 返回指定时区的当前时间。
         * @param timezone IANA 时区名，如 {@code "Asia/Tokyo"}
         * @return 当前时间字符串
         */
        @Tool(
                name = "get_current_time",
                description = "Get the current time in a specific timezone")
        public String getCurrentTime(
                @ToolParam(
                                name = "timezone",
                                description =
                                        "Timezone name, e.g., 'Asia/Tokyo', 'America/New_York',"
                                                + " 'Europe/London'")
                        String timezone) {
            try {
                ZoneId zoneId = ZoneId.of(timezone);
                LocalDateTime now = LocalDateTime.now(zoneId);
                DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
                return String.format("Current time in %s: %s", timezone, now.format(fmt));
            } catch (Exception e) {
                return "Error: Invalid timezone. Try 'Asia/Tokyo' or 'America/New_York'";
            }
        }

        /**
         * 计算简单算术表达式。
         * @param expression 表达式，如 {@code "123 + 456"}
         * @return 结果字符串
         */
        @Tool(name = "calculate", description = "Calculate simple math expressions")
        public String calculate(
                @ToolParam(
                                name = "expression",
                                description = "Math expression, e.g., '123 + 456', '10 * 20'")
                        String expression) {
            try {
                String expr = expression.replaceAll("\\s+", "");
                double result;
                if (expr.contains("+")) {
                    String[] p = expr.split("\\+");
                    result = Double.parseDouble(p[0]) + Double.parseDouble(p[1]);
                } else if (expr.contains("-")) {
                    String[] p = expr.split("-");
                    result = Double.parseDouble(p[0]) - Double.parseDouble(p[1]);
                } else if (expr.contains("*")) {
                    String[] p = expr.split("\\*");
                    result = Double.parseDouble(p[0]) * Double.parseDouble(p[1]);
                } else if (expr.contains("/")) {
                    String[] p = expr.split("/");
                    result = Double.parseDouble(p[0]) / Double.parseDouble(p[1]);
                } else {
                    return "Error: Unsupported operation. Use +, -, *, or /";
                }
                return String.format("%s = %.2f", expression, result);
            } catch (Exception e) {
                return "Error: Invalid expression. Example: '123 + 456'";
            }
        }

        /**
         * 模拟网络搜索，返回占位结果。
         * @param query 搜索查询
         * @return 模拟结果
         */
        @Tool(name = "search", description = "Search for information (simulated)")
        public String searchWeb(
                @ToolParam(name = "query", description = "Search query") String query) {
            return String.format(
                    "Search results for '%s':\n"
                            + "1. Example result about %s - Wikipedia\n"
                            + "2. Latest news about %s - News Site\n"
                            + "3. Documentation for %s - Official Docs\n"
                            + "\nNote: These are simulated results for demonstration purposes.",
                    query, query, query, query);
        }
    }
}
