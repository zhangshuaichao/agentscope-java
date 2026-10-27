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
package io.agentscope.harness.agent.gateway.channel;

import io.agentscope.harness.agent.gateway.MsgContext;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 按确定的优先级顺序评估 {@link ChannelBinding} 规则，为 {@link InboundMessage}
 * 解析目标 {@code agentId} 和稳定的 {@link MsgContext}。
 *
 * <h2>绑定评估层次（优先级从高到低）</h2>
 *
 * <ol>
 *   <li><b>明确指定</b> — 调用方已指定智能体时，{@link InboundMessage#preferredAgentId()}
 *       直接决定目标；仍查询绑定以确定 {@code sessionScope} 和出站地址。
 *   <li><b>对端</b> — 精确匹配 {@link Peer#key()}（例如 {@code "direct:u_42"}）
 *   <li><b>父对端</b> — 精确匹配线程父对端键
 *   <li><b>社区与角色</b> — 社区 ID 匹配，且发送者持有绑定角色中的至少一个
 *   <li><b>社区</b> — 社区 ID 匹配（无角色约束）
 *   <li><b>团队</b> — 团队 ID 匹配
 *   <li><b>账户</b> — 账户 ID 匹配
 *   <li><b>渠道</b> — 渠道 ID 匹配
 *   <li><b>默认</b> — {@link ChannelConfig#defaultAgentId()} 或 {@code globalDefaultAgentId}
 * </ol>
 *
 * <p>每层内部，按 {@link ChannelConfig#bindings()} 列表顺序第一个匹配绑定胜出。
 *
 * <h2>会话键构造（{@link MsgContext}）</h2>
 *
 * 解析 {@code agentId} 后，路由器构建 {@link MsgContext}，
 * 其 {@link MsgContext#canonicalKey()} 生成稳定会话键：
 *
 * <ul>
 *   <li>私聊 + {@link DmScope#MAIN} — 仅 {@code channel} 字段；全部私聊共享一个会话
 *   <li>私聊 + 其他作用域 — room = peerId（可选 group = accountId）
 *   <li>线程 — room = parentPeer id，threadId = peer id
 *   <li>非私聊频道／群组 — room = peer id，group = guild
 * </ul>
 *
 * 解析出的 {@code agentId} 始终以键 {@code
 * "agentId"} 放入 {@link MsgContext#extra()}，使网关从注册表选出正确智能体。
 */
public final class ChannelRouter {

    private final String globalDefaultAgentId;

    /**
     * @param globalDefaultAgentId 既无匹配绑定也无渠道默认值时的回退智能体 ID；
     *     通常为通过 {@link io.agentscope.harness.agent.gateway.Gateway#bindMainAgent} 注册的智能体 ID
     */
    public ChannelRouter(String globalDefaultAgentId) {
        this.globalDefaultAgentId = globalDefaultAgentId != null ? globalDefaultAgentId : "main";
    }

    /**
     * 按优先级顺序评估绑定，返回可供网关执行的 {@link RouteResult}。
     *
     * @param config 渠道级路由配置（绑定、dmScope、渠道默认智能体）
     * @param msg 规范化的入站消息
     */
    public RouteResult resolveRoute(ChannelConfig config, InboundMessage msg) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(msg, "msg");

        String agentId;
        String matchedBy;
        String explicit = msg.preferredAgentId();
        ChannelBinding matchedBinding = findMatchedBinding(config.bindings(), msg);

        if (explicit != null && !explicit.isBlank()) {
            agentId = explicit;
            matchedBy = "explicit";
        } else if (matchedBinding != null) {
            agentId = matchedBinding.agentId();
            matchedBy = detectMatchedTier(config.bindings(), msg);
        } else if (config.defaultAgentId() != null) {
            agentId = config.defaultAgentId();
            matchedBy = "channel-default";
        } else {
            agentId = globalDefaultAgentId;
            matchedBy = "global-default";
        }

        DmScope effectiveScope =
                matchedBinding != null && matchedBinding.sessionScope() != null
                        ? matchedBinding.sessionScope()
                        : config.dmScope();

        String userId =
                msg.senderId() != null
                        ? msg.senderId()
                        : (msg.peer().kind().isDirect() ? msg.peer().id() : null);

        MsgContext context = buildContext(msg, effectiveScope, agentId, userId);
        OutboundAddress outbound = buildOutboundAddress(msg);
        return new RouteResult(agentId, context, matchedBy, outbound);
    }

    // -----------------------------------------------------------------
    // 绑定评估：7 个层次
    // -----------------------------------------------------------------

    private static final List<String> TIERS =
            List.of("peer", "parentPeer", "guildRoles", "guild", "team", "account", "channel");

    private ChannelBinding findMatchedBinding(List<ChannelBinding> bindings, InboundMessage msg) {
        for (String tier : TIERS) {
            ChannelBinding b = findFirstBinding(bindings, msg, tier);
            if (b != null) return b;
        }
        return null;
    }

    private String detectMatchedTier(List<ChannelBinding> bindings, InboundMessage msg) {
        for (String tier : TIERS) {
            if (findFirstBinding(bindings, msg, tier) != null) {
                return tier.equals("guildRoles") ? "guild+roles" : tier;
            }
        }
        return "none";
    }

    private ChannelBinding findFirstBinding(
            List<ChannelBinding> bindings, InboundMessage msg, String tier) {
        for (ChannelBinding b : bindings) {
            if (matches(b, msg, tier)) {
                return b;
            }
        }
        return null;
    }

    private boolean matches(ChannelBinding b, InboundMessage msg, String tier) {
        return switch (tier) {
            case "peer" -> b.peer() != null && b.peer().equals(msg.peer().key());
            case "parentPeer" ->
                    b.parentPeer() != null
                            && msg.parentPeer() != null
                            && b.parentPeer().equals(msg.parentPeer().key());
            case "guildRoles" ->
                    b.guild() != null
                            && !b.roles().isEmpty()
                            && b.guild().equals(msg.guild())
                            && msg.roles().stream().anyMatch(b.roles()::contains);
            case "guild" ->
                    b.guild() != null && b.roles().isEmpty() && b.guild().equals(msg.guild());
            case "team" -> b.team() != null && b.team().equals(msg.team());
            case "account" -> b.account() != null && b.account().equals(msg.accountId());
            case "channel" -> b.channel() != null && b.channel().equals(msg.channelId());
            default -> false;
        };
    }

    // -----------------------------------------------------------------
    // 根据路由结果与 dmScope 构造 MsgContext
    // -----------------------------------------------------------------

    private MsgContext buildContext(
            InboundMessage msg, DmScope dmScope, String agentId, String userId) {
        Map<String, String> extra = Map.of("agentId", agentId);
        String channel = msg.channelId();
        Peer peer = msg.peer();

        MsgContext ctx;
        if (peer.kind().isThread()) {
            String parentRoom = msg.parentPeer() != null ? msg.parentPeer().id() : null;
            ctx = new MsgContext(channel, msg.guild(), parentRoom, peer.id(), null, extra);
        } else if (peer.kind().isDirect()) {
            ctx = buildDmContext(channel, peer.id(), msg.accountId(), dmScope, extra);
        } else {
            ctx = new MsgContext(channel, msg.guild(), peer.id(), null, null, extra);
        }

        return userId != null ? ctx.withUserId(userId) : ctx;
    }

    private MsgContext buildDmContext(
            String channel,
            String peerId,
            String accountId,
            DmScope dmScope,
            Map<String, String> extra) {
        return switch (dmScope) {
            case MAIN -> new MsgContext(channel, null, null, null, null, extra);
            case PER_PEER -> new MsgContext(channel, null, peerId, null, null, extra);
            case PER_CHANNEL_PEER -> new MsgContext(channel, null, peerId, null, null, extra);
            case PER_ACCOUNT_CHANNEL_PEER ->
                    new MsgContext(channel, accountId, peerId, null, null, extra);
        };
    }

    // -----------------------------------------------------------------
    // 构造 OutboundAddress
    // -----------------------------------------------------------------

    private OutboundAddress buildOutboundAddress(InboundMessage msg) {
        String to = msg.channelId() + ":" + msg.peer().kind().name() + ":" + msg.peer().id();
        String threadId = msg.peer().kind().isThread() ? msg.peer().id() : null;
        return new OutboundAddress(msg.channelId(), msg.accountId(), to, threadId);
    }
}
