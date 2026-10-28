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
package io.agentscope.dataagent.runtime.channel.webhook;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * {@code POST /api/webhook/{channelId}/inbound} 的入站请求体。
 * <p>支持两种 {@link #replyMode}：
 * <ul>
 *   <li>{@code "callback"}：通道将回复发送到 {@link #callbackUrl}，用通道共享密钥签名。
 *       原 POST 返回 202 及 {@code inboundId}。
 *   <li>{@code "poll"}（默认）：回复按 {@code inboundId} 暂存于内存环形缓冲，
 *       经 {@code GET /api/webhook/{channelId}/outbound/{inboundId}} 长轮询读取。
 * </ul>
 * @param externalUserId 调用系统提供的不透明发送者身份（映射为 {@code senderId}），必填。
 * @param externalSessionId 可选子对话键；存在时加入对端 ID，避免同一用户并发对话共用会话；
 *     不存在时对端 ID 等于 {@code externalUserId}。
 * @param message 转给智能体的用户可见文本载荷，必填。
 * @param replyMode {@code "callback"} 或 {@code "poll"}，省略时默认为 {@code "poll"}。
 * @param callbackUrl {@code replyMode="callback"} 的回调 URL，回调模式必填。
 * @param preferredAgentId 可选显式智能体覆盖值，对应 {@code InboundMessage
 *     .preferredAgentId}，在绑定评估前作为第 0 级短路选择。
 * @param inboundId 可选客户端幂等键；省略时服务器生成并在响应中返回。
 *     在通道幂等 TTL 内，相同 {@code inboundId} 的重复 POST 被丢弃。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WebhookInboundRequest(
        String externalUserId,
        String externalSessionId,
        String message,
        String replyMode,
        String callbackUrl,
        String preferredAgentId,
        String inboundId) {

    public static final String REPLY_MODE_CALLBACK = "callback";
    public static final String REPLY_MODE_POLL = "poll";

    /** 返回有效回复模式，默认为 {@link #REPLY_MODE_POLL}。 */
    public String effectiveReplyMode() {
        if (replyMode == null || replyMode.isBlank()) {
            return REPLY_MODE_POLL;
        }
        return replyMode.toLowerCase();
    }
}
