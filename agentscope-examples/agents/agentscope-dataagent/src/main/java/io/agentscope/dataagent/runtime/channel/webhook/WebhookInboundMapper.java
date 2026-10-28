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

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.gateway.channel.InboundMessage;
import io.agentscope.harness.agent.gateway.channel.Peer;
import io.agentscope.harness.agent.gateway.channel.PeerKind;
import java.util.List;
import java.util.Optional;

/**
 * 将已认证的 {@link WebhookInboundRequest} 转换为框架的 {@link InboundMessage}。
 * <p>对端映射：
 * <ul>
 *   <li>有 {@code externalSessionId}：使用 {@link PeerKind#DIRECT}，ID 为
 *       {@code "<externalUserId>::<externalSessionId>"}，避免同一用户并发子对话共用会话。
 *   <li>无 {@code externalSessionId}：使用 {@link PeerKind#DIRECT}，ID 为 {@code externalUserId}。
 * </ul>
 * <p>{@code senderId} 始终为原始 {@code externalUserId}，使每用户工作区命名空间不随消息所属子会话改变。
 */
public final class WebhookInboundMapper {

    private final String channelId;

    public WebhookInboundMapper(String channelId) {
        this.channelId = channelId;
    }

    public Optional<InboundMessage> map(WebhookInboundRequest req) {
        if (req == null) return Optional.empty();
        if (req.externalUserId() == null || req.externalUserId().isBlank()) {
            return Optional.empty();
        }
        if (req.message() == null || req.message().isBlank()) {
            return Optional.empty();
        }
        String userId = req.externalUserId().strip();
        String peerId =
                (req.externalSessionId() == null || req.externalSessionId().isBlank())
                        ? userId
                        : userId + "::" + req.externalSessionId().strip();
        Msg msg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .name(userId)
                        .textContent(req.message().strip())
                        .build();
        InboundMessage.Builder b =
                InboundMessage.builder(channelId, new Peer(PeerKind.DIRECT, peerId), List.of(msg))
                        .senderId(userId);
        if (req.preferredAgentId() != null && !req.preferredAgentId().isBlank()) {
            b.preferredAgentId(req.preferredAgentId().strip());
        }
        return Optional.of(b.build());
    }
}
