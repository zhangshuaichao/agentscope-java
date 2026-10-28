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

import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiChannel;
import io.agentscope.harness.agent.gateway.channel.chatui.SendOptions;
import java.util.List;

/**
 * 最简单的通道用法：构建 {@link HarnessAgent}，绑定 {@link ChatUiChannel}，通过 {@link SendOptions} 发消息。
 * <p>{@link SendOptions} 携带用户身份及可选会话 ID；不同 {@code userId} 默认有独立会话，指定 {@code sessionId} 可为同一用户管理多个对话。
 * <p>运行：
 * <pre>
 *   export DASHSCOPE_API_KEY=your_key
 *   mvn exec:java -pl agentscope-examples/documentation \
 *       -Dexec.mainClass=io.agentscope.examples.documentation2.harness.channel.ChannelSendExample
 * </pre>
 */
public class ChannelSendExample {

    /**
     * 运行基础通道示例。
     * @param args 命令行参数，此处忽略
     */
    public static void main(String[] args) throws InterruptedException {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("Channel — Basic Example");
        System.out.println("=".repeat(60) + "\n");

        InMemoryAgentStateStore stateStore = new InMemoryAgentStateStore();

        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("assistant")
                        .sysPrompt("You are a helpful assistant.")
                        .model("dashscope:qwen-plus")
                        .stateStore(stateStore)
                        .build();

        ChatUiChannel chat = agent.channel(ChatUiChannel.create());

        // 以 "user-1" 发送消息，网关为此用户分配稳定会话。
        Msg reply = chat.send(SendOptions.userId("user-1"), "What can you do?").block();
        System.out.println("Reply: " + (reply != null ? reply.getTextContent() : "(null)"));

        // 同一用户的第二条消息延续相同对话。
        Msg followUp = chat.send(SendOptions.userId("user-1"), "Tell me more.").block();
        System.out.println(
                "Follow-up: " + (followUp != null ? followUp.getTextContent() : "(null)"));

        // 不同用户获得完全独立的会话。
        Msg otherUser = chat.send(SendOptions.userId("user-2"), "Hi there").block();
        System.out.println(
                "Other user: " + (otherUser != null ? otherUser.getTextContent() : "(null)"));

        // 同一用户指定不同会话，实现一人多对话。
        Msg sessionA = chat.send(SendOptions.of("user-1", "session-a"), "Topic A").block();
        System.out.println(
                "Session A: " + (sessionA != null ? sessionA.getTextContent() : "(null)"));

        // 通过 SendOptions 附加应用上下文（属性／强制同步）。
        Msg withCtx =
                chat.send(
                                SendOptions.userId("user-1").withAttribute("tenant", "demo"),
                                "Reply with a short hello.")
                        .block();
        System.out.println(
                "With context: " + (withCtx != null ? withCtx.getTextContent() : "(null)"));

        Msg sessionB = chat.send(SendOptions.of("user-1", "session-b"), "Topic B").block();
        System.out.println(
                "Session B: " + (sessionB != null ? sessionB.getTextContent() : "(null)"));

        // 多模态／结构化 Msg 重载；图像 URL 仅作示意。
        Msg multimodal =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                TextBlock.builder().text("What is in this image?").build(),
                                ImageBlock.builder()
                                        .source(
                                                URLSource.builder()
                                                        .url("https://example.com/photo.png")
                                                        .build())
                                        .build())
                        .build();
        Msg vision = chat.send(SendOptions.userId("user-1"), multimodal).block();
        System.out.println("Multimodal: " + (vision != null ? vision.getTextContent() : "(null)"));
        // 等价的列表形式：
        chat.send(SendOptions.userId("user-1"), List.of(multimodal)).block();

        Thread.sleep(10000); // 退出前等待异步处理完成
    }
}
