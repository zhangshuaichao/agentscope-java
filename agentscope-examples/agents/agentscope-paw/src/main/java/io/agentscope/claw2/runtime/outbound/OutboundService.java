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

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.gateway.ChannelManager;
import io.agentscope.harness.agent.gateway.channel.Channel;
import io.agentscope.harness.agent.gateway.channel.ChannelRouter;
import io.agentscope.harness.agent.gateway.channel.InboundMessage;
import io.agentscope.harness.agent.gateway.channel.OutboundAddress;
import io.agentscope.harness.agent.gateway.channel.Peer;
import io.agentscope.harness.agent.gateway.channel.PeerKind;
import io.agentscope.harness.agent.gateway.channel.RouteResult;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 将 {@link OutboundRequest} 转换为 {@link OutboundAddress} + {@link Msg}，通过 {@link ChannelManager} 推送。
 *
 * <p>由 {@link OutboundController}（HTTP）与 {@link OutboundTool}（智能体工具）共用。错误表现为 {@link IllegalArgumentException}（请求错误）或 {@link IllegalStateException}（通道不存在／不健康）。
 */
public final class OutboundService {

    private static final Logger log = LoggerFactory.getLogger(OutboundService.class);

    private final ChannelManager channelManager;
    private final ChannelRouter router = new ChannelRouter(null);

    public OutboundService(ChannelManager channelManager) {
        this.channelManager = Objects.requireNonNull(channelManager, "channelManager");
    }

    /**
     * 投递 {@code request} 描述的消息。
     */
    public void send(OutboundRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.channelId() == null || request.channelId().isBlank()) {
            throw new IllegalArgumentException("channelId is required");
        }
        if (request.peerId() == null || request.peerId().isBlank()) {
            throw new IllegalArgumentException("peerId is required");
        }
        boolean hasText = request.text() != null && !request.text().isBlank();
        boolean hasMd = request.markdown() != null && !request.markdown().isBlank();
        if (!hasText && !hasMd) {
            throw new IllegalArgumentException("either text or markdown must be provided");
        }

        PeerKind kind = parsePeerKind(request.peerKind());
        Peer peer = new Peer(kind, request.peerId().trim());

        Optional<Channel> ch = channelManager.getChannel(request.channelId());
        if (ch.isEmpty()) {
            throw new IllegalStateException(
                    "No channel registered for channelId='" + request.channelId() + "'");
        }

        // 【学习提示】这是可选的路由一致性检查，不是身份认证；agentId 为空会跳过，入口仍需独立鉴权。
        if (request.agentId() != null && !request.agentId().isBlank()) {
            verifyAgentRouting(ch.get(), peer, request);
        }

        OutboundAddress address =
                new OutboundAddress(
                        request.channelId(),
                        emptyToNull(request.accountId()),
                        request.channelId() + ":" + peer.key(),
                        emptyToNull(request.threadId()));

        Msg msg =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .name("outbound")
                        .textContent(hasMd ? request.markdown() : request.text())
                        .build();

        log.debug(
                "Outbound send: channel={}, peer={}, account={}, thread={}, mode={}",
                request.channelId(),
                peer.key(),
                request.accountId(),
                request.threadId(),
                hasMd ? "markdown" : "text");

        channelManager.deliver(address, List.of(msg));
    }

    /**
     * 使用 {@code peer} 的合成入站消息探测通道路由器；若绑定或 {@code defaultAgentId} 路由到不同于 {@code request.agentId()} 的智能体则拒绝投递。探测携带请求智能体提示，使没有绑定且无默认智能体的通道接受调用方；显式绑定仍优先。
     */
    private void verifyAgentRouting(Channel channel, Peer peer, OutboundRequest request) {
        String wanted = request.agentId().trim();
        InboundMessage probe =
                InboundMessage.builder(
                                request.channelId(),
                                peer,
                                List.of(
                                        Msg.builder()
                                                .role(MsgRole.USER)
                                                .textContent("__outbound_probe__")
                                                .build()))
                        .senderId(peer.id())
                        .accountId(emptyToNull(request.accountId()))
                        .preferredAgentId(wanted)
                        .build();
        RouteResult route = router.resolveRoute(channel.config(), probe);
        String resolved = route.agentId();
        if (!Objects.equals(wanted, resolved)) {
            throw new IllegalStateException(
                    "Routing mismatch: channel '"
                            + request.channelId()
                            + "' / peer '"
                            + peer.key()
                            + "' resolves to agent '"
                            + resolved
                            + "' (matchedBy="
                            + route.matchedBy()
                            + ") but caller declared agent '"
                            + wanted
                            + "'. Update the channel binding or omit agent_id to override.");
        }
    }

    private static PeerKind parsePeerKind(String raw) {
        if (raw == null || raw.isBlank()) {
            return PeerKind.DIRECT;
        }
        try {
            return PeerKind.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unknown peerKind '" + raw + "'. Allowed: DIRECT | CHANNEL | GROUP | THREAD");
        }
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
