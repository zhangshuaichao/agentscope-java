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

import java.util.Objects;
import java.util.Set;

/**
 * 将一组入站消息条件映射到目标 {@code agentId} 的单条路由规则。
 *
 * <p>{@link ChannelRouter} 根据绑定携带的最具体非 null 条件字段，在相应优先级层评估：
 *
 * <ol>
 *   <li>{@link #peer} — 精确对端键匹配（{@code "direct:u_123"} 或 {@code "channel:c_help"}）
 *   <li>{@link #parentPeer} — 父对端键匹配（线程父级继承）
 *   <li>{@link #guild} + {@link #roles} — 属于该社区且至少匹配一个角色
 *   <li>仅 {@link #guild} — 属于该社区（无角色约束）
 *   <li>{@link #team} — 团队 ID 匹配
 *   <li>{@link #account} — 账户 ID 匹配
 *   <li>{@link #channel} — 渠道 ID 匹配
 * </ol>
 *
 * <p>同一层中，按 {@link ChannelConfig#bindings()} 列表顺序第一个匹配绑定胜出。
 * 每条绑定只激活一个条件层：最具体的非 null 字段决定所在层。
 *
 * <p>可选 {@link #sessionScope} 提供绑定级会话覆盖；非 null 时，
 * 构建私聊会话键会优先使用它，而不是渠道级 {@link ChannelConfig#dmScope()}。
 *
 * @param agentId 绑定匹配时的目标智能体 ID（必填）
 * @param peer 精确匹配的对端键（例如 {@code "direct:u_42"}）；优先级最高
 * @param parentPeer 线程父级继承所用的父对端键（例如 {@code "channel:c_ops"}）
 * @param guild 要匹配的社区／服务器／工作区 ID
 * @param roles 要匹配的社区内角色 ID，与 {@code guild} 一起评估；非空时为社区与角色层，
 *     为空时为仅社区层
 * @param team 要匹配的团队 ID
 * @param account 要匹配的账户 ID
 * @param channel 要匹配的渠道 ID（优先级较低；渠道专属配置中通常不需要）
 * @param sessionScope 可选的绑定级私聊会话作用域覆盖；null 表示继承 {@link ChannelConfig#dmScope()}
 */
public record ChannelBinding(
        String agentId,
        String peer,
        String parentPeer,
        String guild,
        Set<String> roles,
        String team,
        String account,
        String channel,
        DmScope sessionScope) {

    public ChannelBinding {
        Objects.requireNonNull(agentId, "agentId");
        roles = roles != null ? Set.copyOf(roles) : Set.of();
    }

    /**
     * 匹配精确对端，例如特定私聊用户或频道 ID。
     */
    public static ChannelBinding forPeer(String peer, String agentId) {
        return new ChannelBinding(agentId, peer, null, null, Set.of(), null, null, null, null);
    }

    /**
     * 匹配指定父对端下的线程消息。
     */
    public static ChannelBinding forParentPeer(String parentPeer, String agentId) {
        return new ChannelBinding(
                agentId, null, parentPeer, null, Set.of(), null, null, null, null);
    }

    /**
     * 匹配来自指定社区（服务器／工作区）的任意消息。
     */
    public static ChannelBinding forGuild(String guild, String agentId) {
        return new ChannelBinding(agentId, null, null, guild, Set.of(), null, null, null, null);
    }

    /**
     * 匹配指定社区中、发送者至少持有给定角色之一的消息。
     */
    public static ChannelBinding forGuildRoles(String guild, Set<String> roles, String agentId) {
        return new ChannelBinding(agentId, null, null, guild, roles, null, null, null, null);
    }

    /**
     * 匹配指定团队 ID。
     */
    public static ChannelBinding forTeam(String team, String agentId) {
        return new ChannelBinding(agentId, null, null, null, Set.of(), team, null, null, null);
    }

    /**
     * 匹配指定账户 ID。
     */
    public static ChannelBinding forAccount(String account, String agentId) {
        return new ChannelBinding(agentId, null, null, null, Set.of(), null, account, null, null);
    }

    /**
     * 匹配指定渠道 ID。
     */
    public static ChannelBinding forChannel(String channel, String agentId) {
        return new ChannelBinding(agentId, null, null, null, Set.of(), null, null, channel, null);
    }

    /**
     * 返回附带绑定级会话作用域覆盖的绑定副本。
     */
    public ChannelBinding withSessionScope(DmScope scope) {
        return new ChannelBinding(
                agentId, peer, parentPeer, guild, roles, team, account, channel, scope);
    }
}
