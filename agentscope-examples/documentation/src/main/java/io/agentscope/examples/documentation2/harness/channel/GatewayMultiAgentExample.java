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
package io.agentscope.examples.documentation2.harness.channel;

import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.gateway.GatewayBootstrap;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiChannel;
import io.agentscope.harness.agent.gateway.channel.chatui.SendOptions;

/**
 * 通过 {@link GatewayBootstrap} 和 {@link SendOptions#withAgentId(String)} 将消息路由至不同智能体。
 * <p>{@link GatewayBootstrap} 管理多个 {@link HarnessAgent}；使用 {@link SendOptions#withAgentId(String)} 显式指定目标。
 * <p>运行：
 * <pre>
 *   export DASHSCOPE_API_KEY=your_key
 *   mvn exec:java -pl agentscope-examples/documentation \
 *       -Dexec.mainClass=io.agentscope.examples.documentation2.harness.channel.GatewayMultiAgentExample
 * </pre>
 */
public class GatewayMultiAgentExample {

    /**
     * 运行多智能体路由示例。
     * @param args 命令行参数，此处忽略
     */
    public static void main(String[] args) {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("Channel — Multi-Agent Routing");
        System.out.println("=".repeat(60) + "\n");

        HarnessAgent salesAgent =
                HarnessAgent.builder()
                        .name("sales")
                        .sysPrompt("You are a sales assistant.")
                        .model("dashscope:qwen-plus")
                        .build();

        HarnessAgent supportAgent =
                HarnessAgent.builder()
                        .name("support")
                        .sysPrompt("You are a support agent.")
                        .model("dashscope:qwen-plus")
                        .build();

        // 注册两个智能体，mainAgent("sales") 指定默认智能体。
        GatewayBootstrap gw =
                GatewayBootstrap.builder()
                        .agent("sales", salesAgent)
                        .agent("support", supportAgent)
                        .mainAgent("sales")
                        .build();

        ChatUiChannel chat = gw.chatUiChannel();

        // 路由到默认 sales 智能体，无需覆盖 agentId。
        Msg salesReply =
                chat.send(SendOptions.userId("user-1"), "What products do you have?").block();
        System.out.println(
                "Sales reply: " + (salesReply != null ? salesReply.getTextContent() : "(null)"));

        // 通过 withAgentId() 显式路由到 support 智能体。
        Msg supportReply =
                chat.send(
                                SendOptions.userId("user-1").withAgentId("support"),
                                "I have a billing issue")
                        .block();
        System.out.println(
                "Support reply: "
                        + (supportReply != null ? supportReply.getTextContent() : "(null)"));
    }
}
