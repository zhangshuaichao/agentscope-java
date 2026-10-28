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
package io.agentscope.builder.runtime.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.agentscope.harness.agent.gateway.channel.ChannelBinding;
import io.agentscope.harness.agent.gateway.channel.DmScope;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 可进行 JSON 序列化的配置，对应 {@link ChannelBinding}.
 *
 * <p>字段顺序对应 OpenClaw 路由优先级层次，判定器为 {@link
 * io.agentscope.harness.agent.gateway.channel.ChannelRouter}:
 *
 * <ol>
 *   <li>{@link #peer}
 *   <li>{@link #parentPeer}
 *   <li>{@link #guild} + {@link #roles}
 *   <li>{@link #guild} 单独匹配
 *   <li>{@link #team}
 *   <li>{@link #account}
 *   <li>{@link #channel}
 * </ol>
 *
 * <p>最具体的非 null 字段决定评估此绑定的优先级层次。
 * {@link #agentId} 为必填项； {@link #sessionScope} 可选地覆盖此绑定创建的会话所使用的通道级私信范围。
 *
 * <h2>JSON 示例</h2>
 *
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
     * 可选的绑定级私信范围覆盖值。可取 {@code MAIN}、{@code PER_PEER}、{@code
     * PER_CHANNEL_PEER}、{@code PER_ACCOUNT_CHANNEL_PEER}。为 null 时使用通道级范围。
     */
    @JsonProperty("sessionScope")
    private String sessionScope;

    // -----------------------------------------------------------------
    // 访问器与设置器
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
     * 将此配置转换为运行时 {@link ChannelBinding}。
     * @throws IllegalArgumentException 当 {@code agentId} 缺失或为空白时
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
                // 未知值 → 保持 null，使用通道级范围
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

    /** 逆向辅助方法：从运行时 {@link ChannelBinding} 构建 {@link BindingConfigEntry}。 */
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
