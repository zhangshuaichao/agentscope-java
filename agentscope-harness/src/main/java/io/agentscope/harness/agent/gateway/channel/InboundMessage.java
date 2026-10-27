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

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 由 {@link Channel} 适配器在路由前生成的规范化入站消息。
 * 携带 {@link ChannelRouter} 解析智能体及构建稳定会话键所需的全部身份与上下文元数据。
 *
 * <p>两个身份字段刻意分开：
 * <ul>
 *   <li>{@link #peer} — 用于构建会话键的<em>对话</em>标识（私聊用户 ID、群聊 ID、频道 ID）
 *   <li>{@link #senderId} — <em>消息发送者</em>身份，作为 HarnessAgent 命名空间隔离的
 *       {@code userId}。私聊中二者相等；群组／频道中 {@code peer.id} 是群组，
 *       {@code senderId} 是具体作者。
 * </ul>
 *
 * <p>{@link #runtimeContext()} <em>不</em>用于路由，也不参与会话键计算。
 * 它转发给网关，作为智能体轮次的调用方上下文合并基础。
 *
 * @param channelId 来源渠道标识（例如 {@code "chatui"}、{@code "slack"}）
 * @param accountId 可选多账户标识（例如 Slack 应用安装 ID）；单账户渠道为 null
 * @param peer 对话对端（私聊伙伴、频道、群组或线程），用于会话键
 * @param senderId 用户级隔离的可选发送者身份；null 时，私聊上下文回退使用 {@link #peer} 的 ID
 * @param parentPeer 线程内消息的可选父对端，用于 {@code binding.peer.parent} 继承
 * @param guild 可选社区／服务器／工作区 ID（Discord 社区、Slack 工作区）
 * @param team 可选团队 ID（Slack 团队、MS Teams 团队）
 * @param roles 发送者在社区中持有的可选角色 ID 集合，用于 {@code binding.guild+roles} 层评估
 * @param messages 要转发给智能体的实际消息内容
 * @param preferredAgentId 可选的明确智能体覆盖，{@link ChannelRouter} 在绑定层之前作为第 0 层短路目标选择。
 *     调用方已知道处理请求的智能体时设置，例如路径映射的 Web UI 中用户点击了智能体卡片；
 *     路由器仍正常构建会话与出站上下文，绑定继续控制 {@code sessionScope} 和出站地址。
 *     {@code null} 表示常规绑定路由。
 * @param runtimeContext 合并到智能体轮次的可选调用方 {@link RuntimeContext}；没有时为 {@code null}。
 *     不属于路由／会话身份。
 */
public record InboundMessage(
        String channelId,
        String accountId,
        Peer peer,
        String senderId,
        Peer parentPeer,
        String guild,
        String team,
        Set<String> roles,
        List<Msg> messages,
        String preferredAgentId,
        RuntimeContext runtimeContext) {

    public InboundMessage {
        Objects.requireNonNull(channelId, "channelId");
        Objects.requireNonNull(peer, "peer");
        Objects.requireNonNull(messages, "messages");
        roles = roles != null ? Set.copyOf(roles) : Set.of();
    }

    /**
     * 不带社区／团队／角色元数据的单轮私聊，常用于直接聊天 UI。
     *
     * <p>私聊上下文中 {@code senderId} 等于 {@code peerId}，对话伙伴也是消息作者。
     */
    public static InboundMessage dm(String channelId, String peerId, List<Msg> messages) {
        return new InboundMessage(
                channelId,
                null,
                Peer.direct(peerId),
                peerId,
                null,
                null,
                null,
                Set.of(),
                List.copyOf(messages),
                null,
                null);
    }

    /**
     * 明确覆盖 {@link #preferredAgentId()} 的单轮私聊：路由器将给定 {@code agentId}
     * 作为第 0 层短路目标选择，不再由绑定决定目标智能体。
     */
    public static InboundMessage dmFor(
            String channelId, String peerId, String agentId, List<Msg> messages) {
        Objects.requireNonNull(agentId, "agentId");
        return new InboundMessage(
                channelId,
                null,
                Peer.direct(peerId),
                peerId,
                null,
                null,
                null,
                Set.of(),
                List.copyOf(messages),
                agentId,
                null);
    }

    /**
     * 带可选社区上下文的频道／群组消息。{@code senderId} 标识具体作者，
     * 与 {@code roomId} 对话对端分开。
     */
    public static InboundMessage channel(
            String channelId, String roomId, String senderId, String guild, List<Msg> messages) {
        return new InboundMessage(
                channelId,
                null,
                Peer.channel(roomId),
                senderId,
                null,
                guild,
                null,
                Set.of(),
                List.copyOf(messages),
                null,
                null);
    }

    /**
     * 带可选社区上下文的频道消息；未明确提供 senderId，发送者身份为 null。
     *
     * <p>【学习提示】上游旧描述称回退到房间对端，但 ChannelRouter 只对私聊做 peer ID 回退。
     * 此频道消息不会自动把 roomId 作为 userId；需要用户隔离时应显式传入可信发送者身份。
     */
    public static InboundMessage channel(
            String channelId, String roomId, String guild, List<Msg> messages) {
        return new InboundMessage(
                channelId,
                null,
                Peer.channel(roomId),
                null,
                null,
                guild,
                null,
                Set.of(),
                List.copyOf(messages),
                null,
                null);
    }

    /**
     * 此消息是否来自直接／私聊对端。
     */
    public boolean isDm() {
        return peer.kind().isDirect();
    }

    /**
     * 此消息是否是线程内回复。
     */
    public boolean isThread() {
        return peer.kind().isThread();
    }

    /**
     * 返回携带指定 {@link RuntimeContext} 的副本。
     */
    public InboundMessage withRuntimeContext(RuntimeContext runtimeContext) {
        return new InboundMessage(
                channelId,
                accountId,
                peer,
                senderId,
                parentPeer,
                guild,
                team,
                roles,
                messages,
                preferredAgentId,
                runtimeContext);
    }

    /**
     * 返回可设置全部可选字段的消息构建器。
     */
    public static Builder builder(String channelId, Peer peer, List<Msg> messages) {
        return new Builder(channelId, peer, messages);
    }

    public static final class Builder {
        private final String channelId;
        private final Peer peer;
        private final List<Msg> messages;
        private String accountId;
        private String senderId;
        private Peer parentPeer;
        private String guild;
        private String team;
        private Set<String> roles = Set.of();
        private String preferredAgentId;
        private RuntimeContext runtimeContext;

        private Builder(String channelId, Peer peer, List<Msg> messages) {
            this.channelId = channelId;
            this.peer = peer;
            this.messages = messages;
        }

        public Builder accountId(String accountId) {
            this.accountId = accountId;
            return this;
        }

        public Builder senderId(String senderId) {
            this.senderId = senderId;
            return this;
        }

        public Builder parentPeer(Peer parentPeer) {
            this.parentPeer = parentPeer;
            return this;
        }

        public Builder guild(String guild) {
            this.guild = guild;
            return this;
        }

        public Builder team(String team) {
            this.team = team;
            return this;
        }

        public Builder roles(Set<String> roles) {
            this.roles = roles != null ? roles : Set.of();
            return this;
        }

        public Builder preferredAgentId(String preferredAgentId) {
            this.preferredAgentId = preferredAgentId;
            return this;
        }

        public Builder runtimeContext(RuntimeContext runtimeContext) {
            this.runtimeContext = runtimeContext;
            return this;
        }

        public InboundMessage build() {
            return new InboundMessage(
                    channelId,
                    accountId,
                    peer,
                    senderId,
                    parentPeer,
                    guild,
                    team,
                    roles,
                    messages,
                    preferredAgentId,
                    runtimeContext);
        }
    }
}
