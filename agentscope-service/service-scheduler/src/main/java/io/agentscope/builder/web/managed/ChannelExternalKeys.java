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
package io.agentscope.builder.web.managed;

import io.agentscope.harness.agent.gateway.MsgContext;
import io.agentscope.harness.agent.gateway.channel.OutboundAddress;

/**
 * 为托管会话查找或创建构建稳定的 {@code externalKey}，与通道 {@code dmScope} 对齐：
 * <ul>
 * <li>MAIN 私聊 → {@code {channelId}|main}。
 * <li>PER_PEER 私聊／群组 → 出站地址中的 {@code {channelId}|{peerKind}|{peerId}}。
 * <li>群组即使在 MAIN 模式也使用群对端 ID，确保群聊和私聊不共享槽位。
 * </ul>
 */
public final class ChannelExternalKeys {

    private ChannelExternalKeys() {}

    /**
     * @param ctx 入站路由上下文
     * @param outbound 通道路由器构建的投递地址，可为 null
     * @param dmScope 通道级作用域字符串；空白／null 视为 {@code MAIN}
     */
    public static String forInbound(MsgContext ctx, OutboundAddress outbound, String dmScope) {
        String channelId =
                ctx != null && ctx.channel() != null && !ctx.channel().isBlank()
                        ? ctx.channel()
                        : "default";
        ParsedPeer peer = parseOutbound(outbound);
        boolean main = dmScope == null || dmScope.isBlank() || "MAIN".equalsIgnoreCase(dmScope);

        if (peer != null && "GROUP".equalsIgnoreCase(peer.kind)) {
            return channelId + "|group|" + peer.peerId;
        }
        if (peer != null && "THREAD".equalsIgnoreCase(peer.kind)) {
            return channelId + "|thread|" + peer.peerId;
        }
        if (main) {
            // MAIN 将此通道身份下的全部私聊合并。
            return channelId + "|main";
        }
        if (peer != null) {
            return channelId + "|" + peer.kind.toLowerCase() + "|" + peer.peerId;
        }
        // 回退：存在 MsgContext.room 时使用它（PER_PEER 将 peerId 放在这里）。
        if (ctx != null && ctx.room() != null && !ctx.room().isBlank()) {
            return channelId + "|direct|" + ctx.room();
        }
        if (ctx != null && ctx.userId() != null && !ctx.userId().isBlank()) {
            return channelId + "|direct|" + ctx.userId();
        }
        return channelId + "|main";
    }

    private static ParsedPeer parseOutbound(OutboundAddress outbound) {
        if (outbound == null || outbound.to() == null || outbound.to().isBlank()) {
            return null;
        }
        // 格式：channelId:peerKind:peerId（peerId 可包含 ':'）
        String to = outbound.to();
        int first = to.indexOf(':');
        if (first < 0) {
            return null;
        }
        int second = to.indexOf(':', first + 1);
        if (second < 0) {
            return null;
        }
        String kind = to.substring(first + 1, second);
        String peerId = to.substring(second + 1);
        if (kind.isBlank() || peerId.isBlank()) {
            return null;
        }
        return new ParsedPeer(kind, peerId);
    }

    private record ParsedPeer(String kind, String peerId) {}
}
