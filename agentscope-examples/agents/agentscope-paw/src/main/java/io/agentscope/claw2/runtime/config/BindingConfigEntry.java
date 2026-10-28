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
package io.agentscope.claw2.runtime.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.agentscope.harness.agent.gateway.channel.ChannelBinding;
import io.agentscope.harness.agent.gateway.channel.DmScope;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * {@link ChannelBinding} 的 JSON 可序列化对应配置。
 *
 * <p>字段顺序对应 {@link io.agentscope.harness.agent.gateway.channel.ChannelRouter} 求值的路由优先级层次：
 * <ol>
 *   <li>{@link #peer}
 *   <li>{@link #parentPeer}
 *   <li>{@link #guild} + {@link #roles}
 *   <li>仅 {@link #guild}
 *   <li>{@link #team}
 *   <li>{@link #account}
 *   <li>{@link #channel}
 * </ol>
 * <p>最具体的非 null 字段决定此绑定的求值层次。{@link #agentId} 必填；{@link #sessionScope} 可覆盖通道级私聊作用域，应用于此绑定创建的会话。
 * <h2>JSON 示例</h2>
 * <pre>{@code
 * {
 *   "agentId": "support",
 *   "guild":   "ws-alpha",
 *   "roles":   ["staff"],
 *   "sessionScope": "PER_PEER"
 * }
 * }</pre>
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
public class BindingConfigEntry {

    @JsonProperty("agentId")
    private String agentId;

    @JsonProperty("peer")
    private String peer;

    @JsonProperty("parentPeer")
    private String parentPeer;

    @JsonProperty("guild")
    private String guild;

    @JsonProperty("roles")
    private List<String> roles;

    @JsonProperty("team")
    private String team;

    @JsonProperty("account")
    private String account;

    @JsonProperty("channel")
    private String channel;

    /**
     * 可选的逐绑定私聊作用域覆盖：{@code MAIN}、{@code PER_PEER}、{@code PER_CHANNEL_PEER} 或 {@code PER_ACCOUNT_CHANNEL_PEER}；为 null 时应用通道级作用域。
     */
    @JsonProperty("sessionScope")
    private String sessionScope;

    // -----------------------------------------------------------------
    // 读写访问器
    // -----------------------------------------------------------------

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public String getPeer() {
        return peer;
    }

    public void setPeer(String peer) {
        this.peer = peer;
    }

    public String getParentPeer() {
        return parentPeer;
    }

    public void setParentPeer(String parentPeer) {
        this.parentPeer = parentPeer;
    }

    public String getGuild() {
        return guild;
    }

    public void setGuild(String guild) {
        this.guild = guild;
    }

    public List<String> getRoles() {
        return roles;
    }

    public void setRoles(List<String> roles) {
        this.roles = roles;
    }

    public String getTeam() {
        return team;
    }

    public void setTeam(String team) {
        this.team = team;
    }

    public String getAccount() {
        return account;
    }

    public void setAccount(String account) {
        this.account = account;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public String getSessionScope() {
        return sessionScope;
    }

    public void setSessionScope(String sessionScope) {
        this.sessionScope = sessionScope;
    }

    // -----------------------------------------------------------------
    // 转换
    // -----------------------------------------------------------------

    /**
     * 将此条目转换为运行时 {@link ChannelBinding}。
     *
     * @throws IllegalArgumentException 若 {@code agentId} 缺失或为空白
     */
    public ChannelBinding toBinding() {
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException("BindingConfigEntry.agentId is required");
        }
        Set<String> rolesSet =
                roles == null || roles.isEmpty() ? Set.of() : new LinkedHashSet<>(roles);
        DmScope scope = null;
        if (sessionScope != null && !sessionScope.isBlank()) {
            try {
                scope = DmScope.valueOf(sessionScope.toUpperCase());
            } catch (IllegalArgumentException ignored) {
                // 未知值：保持 null，继续应用通道级作用域。
            }
        }
        return new ChannelBinding(
                agentId.trim(),
                blankToNull(peer),
                blankToNull(parentPeer),
                blankToNull(guild),
                rolesSet,
                blankToNull(team),
                blankToNull(account),
                blankToNull(channel),
                scope);
    }

    /**
     * 逆向辅助方法：从运行时 {@link ChannelBinding} 构建 {@link BindingConfigEntry}。
     */
    public static BindingConfigEntry fromBinding(ChannelBinding b) {
        BindingConfigEntry e = new BindingConfigEntry();
        e.setAgentId(b.agentId());
        e.setPeer(b.peer());
        e.setParentPeer(b.parentPeer());
        e.setGuild(b.guild());
        if (b.roles() != null && !b.roles().isEmpty()) {
            e.setRoles(List.copyOf(b.roles()));
        }
        e.setTeam(b.team());
        e.setAccount(b.account());
        e.setChannel(b.channel());
        if (b.sessionScope() != null) {
            e.setSessionScope(b.sessionScope().name());
        }
        return e;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
