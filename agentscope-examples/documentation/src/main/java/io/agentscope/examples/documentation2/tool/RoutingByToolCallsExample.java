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
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.dashscope.formatter.DashScopeChatFormatter;
import io.agentscope.harness.agent.middleware.AgentTraceMiddleware;

/**
 * RoutingByToolCallsExample：通过工具调用将用户请求路由到子智能体。路由智能体接收查询，调用适合的工具委托专家；每个工具创建并调用新 {@link ReActAgent}。迁移移除 {@code .memory(new InMemoryMemory())}，2.0 不再需要。
 */
public class RoutingByToolCallsExample {

    /**
     * 运行路由示例。
     * @param args 命令行参数，此处忽略
     */
    public static void main(String[] args) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new SimpleTools());

        ReActAgent routerAgent =
                ReActAgent.builder()
                        .name("RouterImplicit")
                        .sysPrompt(
                                "You're a routing agent. Your target is to route the user query to"
                                        + " the right follow-up task.")
                        .model(
                                DashScopeChatModel.builder()
                                        .apiKey(System.getenv("DASHSCOPE_API_KEY"))
                                        .modelName("qwen-max")
                                        .stream(true)
                                        .enableThinking(true)
                                        .formatter(new DashScopeChatFormatter())
                                        .defaultOptions(
                                                GenerateOptions.builder()
                                                        .thinkingBudget(512)
                                                        .build())
                                        .build())
                        .toolkit(toolkit)
                        .middleware(new AgentTraceMiddleware())
                        .build();

        Msg userMsg = new UserMessage("Help me to generate a quick sort function in Python");

        try {
            Msg response = routerAgent.call(userMsg).block();
            if (response != null) {
                System.out.println("Agent> " + response.getTextContent() + "\n");
            } else {
                System.out.println("Agent> [No response]\n");
            }
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 供路由智能体委托专家子智能体的工具。
     */
    public static class SimpleTools {

        /**
         * 委托 Python 专家生成代码。
         * @param demand 待生成代码的需求描述
         * @return 专家响应
         */
        @Tool(
                name = "generate_Python_code",
                description = "Generate Python code based on the demand")
        public Msg generatePython(
                @ToolParam(name = "demand", description = "The demand for the Python code")
                        String demand) {
            System.out.println("PythonAgent: generating code for: " + demand);
            ReActAgent agent =
                    ReActAgent.builder()
                            .name("PythonAgent")
                            .sysPrompt(
                                    "You're a Python expert. Generate Python code based on the"
                                            + " demand.")
                            .model(
                                    DashScopeChatModel.builder()
                                            .apiKey(System.getenv("DASHSCOPE_API_KEY"))
                                            .modelName("qwen-max")
                                            .stream(true)
                                            .enableThinking(true)
                                            .formatter(new DashScopeChatFormatter())
                                            .defaultOptions(
                                                    GenerateOptions.builder()
                                                            .thinkingBudget(1024)
                                                            .build())
                                            .build())
                            .toolkit(new Toolkit())
                            .build();
            Msg userMsg = new UserMessage(demand);
            return agent.call(userMsg).block();
        }

        /**
         * 委托诗歌专家生成诗歌。
         * @param demand 诗歌需求描述
         * @return 专家响应
         */
        @Tool(name = "generate_poem", description = "Generate a poem based on the demand")
        public Msg generatePoem(
                @ToolParam(name = "demand", description = "The demand for the poem")
                        String demand) {
            System.out.println("PoemAgent: generating poem for: " + demand);
            ReActAgent agent =
                    ReActAgent.builder()
                            .name("PoemAgent")
                            .sysPrompt("You're a poet. Generate poems based on the demand.")
                            .model(
                                    DashScopeChatModel.builder()
                                            .apiKey(System.getenv("DASHSCOPE_API_KEY"))
                                            .modelName("qwen-max")
                                            .stream(true)
                                            .enableThinking(true)
                                            .formatter(new DashScopeChatFormatter())
                                            .defaultOptions(
                                                    GenerateOptions.builder()
                                                            .thinkingBudget(1024)
                                                            .build())
                                            .build())
                            .toolkit(new Toolkit())
                            .build();
            Msg userMsg = new UserMessage(demand);
            return agent.call(userMsg).block();
        }
    }
}
