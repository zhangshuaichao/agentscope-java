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
package io.agentscope.claw2.runtime.outbound;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 智能体主动发起的出站消息请求。
 *
 * <p>请求描述消息的<em>目的地</em>（通道、对端类型、对端 ID，以及可选账户／线程上下文）与<em>内容</em>（文本或 Markdown）。{@link OutboundService} 将其转换为 {@link io.agentscope.harness.agent.gateway.channel.OutboundAddress}，委托给匹配通道。
 *
 * @param channelId 目标通道 ID，必须匹配已注册 {@link io.agentscope.harness.agent.gateway.channel.Channel}；必填
 * @param peerKind {@code DIRECT}、{@code CHANNEL}、{@code GROUP}、{@code THREAD} 之一；必填
 * @param peerId 提供方专属对端 ID（用户、群／会话等）；必填
 * @param accountId 可选多账户维度（企业 ID、应用实例）；可为 null
 * @param threadId 可选线程回复锚点；可为 null
 * @param text 纯文本正文；{@code text} 与 {@code markdown} 至少设置一个
 * @param markdown Markdown 正文；{@code text} 与 {@code markdown} 至少设置一个
 * @param agentId 可选调用方提供的智能体 ID；设置后，服务验证通道对 {@code peerId} 的路由是否解析到相同智能体，否则拒绝投递，防止向绑定其他智能体的通道／对端发消息。为 {@code null} 或空白时跳过检查。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OutboundRequest(
        String channelId,
        String peerKind,
        String peerId,
        String accountId,
        String threadId,
        String text,
        String markdown,
        String agentId) {

    /**
     * 不含 {@code agentId} 的便捷构造方法，不执行调用方路由检查。
     */
    public OutboundRequest(
            String channelId,
            String peerKind,
            String peerId,
            String accountId,
            String threadId,
            String text,
            String markdown) {
        this(channelId, peerKind, peerId, accountId, threadId, text, markdown, null);
    }
}
