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
package io.agentscope.harness.coding.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import io.agentscope.harness.agent.gateway.channel.DmScope;
import io.agentscope.harness.coding.CodingBootstrap;
import java.util.Map;

/**
 * {@code agentscope.json} 中 {@code channels.<channelId>} 下的单通道配置。
 *
 * <p>定义通道适配器路由配置。若没有对应的编程式 {@link CodingBootstrap.Builder#channel(io.agentscope.harness.agent.gateway.channel.Channel...)} 注册，则按条目自动创建内置 {@code chatui} 通道。其他类型则在启动时应用此条目提供的 {@link ChannelConfig} 路由规则。
 *
 * <h2>示例</h2>
 * <pre>{@code
 * "channels": {
 *   "chatui": {
 *     "defaultAgentId": "main",
 *     "dmScope": "PER_PEER"
 *   },
 *   "slack": {
 *     "defaultAgentId": "support",
 *     "dmScope": "PER_PEER"
 *   }
 * }
 * }</pre>
 *
 * @see AgentscopeConfig
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChannelConfigEntry {

    /**
     * 没有绑定匹配时的回退智能体 ID；省略时回退到全局绑定主智能体。
     */
    @JsonProperty("defaultAgentId")
    private String defaultAgentId;

    /**
     * 控制私聊会话键作用域，可选 {@code MAIN}、{@code PER_PEER}、{@code PER_CHANNEL_PEER}、{@code PER_ACCOUNT_CHANNEL_PEER}。省略时默认 {@code MAIN}。
     *
     * @see DmScope
     */
    @JsonProperty("dmScope")
    private String dmScope;

    /**
     * 为 {@code true} 时启动器忽略此通道条目，不创建实例；同 ID 编程注册通道也不会启动。
     */
    @JsonProperty("disabled")
    private Boolean disabled;

    /**
     * 由通道适配器工厂消费的提供方专属属性，例如钉钉的 {@code appKey}、{@code appSecret}、{@code robotCode}，飞书的 {@code verificationToken}、{@code encryptKey}。未知键透传给工厂。
     */
    @JsonProperty("properties")
    private Map<String, Object> properties;

    public String getDefaultAgentId() {
        return defaultAgentId;
    }

    public void setDefaultAgentId(String defaultAgentId) {
        this.defaultAgentId = defaultAgentId;
    }

    public String getDmScope() {
        return dmScope;
    }

    public void setDmScope(String dmScope) {
        this.dmScope = dmScope;
    }

    public Boolean getDisabled() {
        return disabled;
    }

    public void setDisabled(Boolean disabled) {
        this.disabled = disabled;
    }

    public Map<String, Object> getProperties() {
        return properties;
    }

    public void setProperties(Map<String, Object> properties) {
        this.properties = properties;
    }

    /**
     * 将此条目转换为给定通道 ID 的 {@link ChannelConfig}。
     */
    public ChannelConfig toChannelConfig(String channelId) {
        DmScope scope = DmScope.MAIN;
        if (dmScope != null && !dmScope.isBlank()) {
            try {
                scope = DmScope.valueOf(dmScope.toUpperCase());
            } catch (IllegalArgumentException ignored) {
                // 未知值回退为 MAIN
            }
        }
        return ChannelConfig.builder(channelId)
                .defaultAgentId(defaultAgentId)
                .dmScope(scope)
                .build();
    }
}
