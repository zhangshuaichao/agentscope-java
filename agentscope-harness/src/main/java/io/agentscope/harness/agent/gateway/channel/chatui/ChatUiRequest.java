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
package io.agentscope.harness.agent.gateway.channel.chatui;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import java.util.List;
import java.util.Objects;

/**
 * {@link ChatUiChannel} 的请求对象，携带可选对端身份及要发送给智能体的消息。
 *
 * <p>渠道路由器使用 {@code peerId} 构建稳定会话键：
 *
 * <ul>
 *   <li>{@code peerId} 为 null 且渠道 DmScope 为 MAIN 时，全部请求共享一个会话，
 *       适合单用户或测试场景。
 *   <li>提供 {@code peerId} 时，每个不同对端拥有自己的会话（使用 PER_PEER 等作用域时）。
 * </ul>
 *
 * @param peerId 可选用户／对端标识；null 表示“无对端”（单会话模式）
 * @param agentId 可选的明确智能体覆盖；null 表示普通绑定路由
 * @param subagentId 可选已公开子智能体 ID，用于直接子智能体路由；null 表示普通路由
 * @param messages 要发送的一个或多个消息
 */
public record ChatUiRequest(String peerId, String agentId, String subagentId, List<Msg> messages) {

    public ChatUiRequest {
        Objects.requireNonNull(messages, "messages");
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("messages must not be empty");
        }
    }

    /**
     * 单会话模式下的单条用户文本消息（无对端）。
     */
    public static ChatUiRequest of(String text) {
        Objects.requireNonNull(text, "text");
        return new ChatUiRequest(null, null, null, List.of(userMsg(text)));
    }

    /**
     * 关联指定对端 ID 的单条用户文本消息。
     */
    public static ChatUiRequest withPeer(String peerId, String text) {
        Objects.requireNonNull(peerId, "peerId");
        Objects.requireNonNull(text, "text");
        return new ChatUiRequest(peerId, null, null, List.of(userMsg(text)));
    }

    /**
     * 发给指定智能体的单条用户文本消息。
     */
    public static ChatUiRequest forAgent(String peerId, String agentId, String text) {
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(text, "text");
        return new ChatUiRequest(peerId, agentId, null, List.of(userMsg(text)));
    }

    /**
     * 直接路由到已公开子智能体的单条用户文本消息。
     */
    public static ChatUiRequest toSubagent(String subagentId, String text) {
        Objects.requireNonNull(subagentId, "subagentId");
        Objects.requireNonNull(text, "text");
        return new ChatUiRequest(null, null, subagentId, List.of(userMsg(text)));
    }

    /**
     * 不带对端的多消息请求（单会话模式）。
     */
    public static ChatUiRequest of(List<Msg> messages) {
        return new ChatUiRequest(null, null, null, messages);
    }

    /**
     * 面向指定对端的多消息请求。
     */
    public static ChatUiRequest withPeer(String peerId, List<Msg> messages) {
        return new ChatUiRequest(peerId, null, null, messages);
    }

    private static Msg userMsg(String text) {
        return Msg.builder().role(MsgRole.USER).textContent(text).build();
    }
}
