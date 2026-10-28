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
import io.agentscope.claw2.runtime.ClawBootstrap;
import io.agentscope.harness.agent.gateway.channel.ChannelBinding;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import io.agentscope.harness.agent.gateway.channel.DmScope;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code agentscope.json} 中 {@code channels.<channelId>} 下的逐通道配置。
 *
 * <p>定义通道适配器的路由配置。不存在对应程序化 {@link ClawBootstrap.Builder#channel(io.agentscope.harness.agent.gateway.channel.Channel...)} 注册时，从条目自动创建内置 {@code chatui} 通道。其他通道类型通过此条目提供引导阶段应用的 {@link ChannelConfig} 路由规则。
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
     * 通道类型 ID，用于在 {@link ChannelTypeRegistry} 查找 {@link ChannelFactory}。省略时默认使用 JSON 键（通道 ID），兼容类型 ID 与通道 ID 相同的内置 {@code chatui} 通道。
     */
    @JsonProperty("type")
    private String type;

    /**
     * 传给 {@link ChannelFactory} 的提供方专属属性（凭据、端点、机器人代码等）。这是自由格式映射，工厂负责验证必填键。
     */
    @JsonProperty("properties")
    private Map<String, Object> properties;

    /**
     * 未匹配任何绑定时的后备智能体 ID；省略时回退到全局绑定的主智能体。
     */
    @JsonProperty("defaultAgentId")
    private String defaultAgentId;

    /**
     * 控制私聊会话键的作用域：{@code MAIN}、{@code PER_PEER}、{@code PER_CHANNEL_PEER} 或 {@code PER_ACCOUNT_CHANNEL_PEER}；省略时默认 {@code MAIN}。
     *
     * @see DmScope
     */
    @JsonProperty("dmScope")
    private String dmScope;

    /**
     * 为 {@code true} 时引导阶段忽略此通道条目，不创建实例，也不启动相同 ID 的程序化注册通道。
     */
    @JsonProperty("disabled")
    private Boolean disabled;

    /**
     * 有序 {@link io.agentscope.harness.agent.gateway.channel.ChannelBinding} 路由规则列表，由 {@link io.agentscope.harness.agent.gateway.channel.ChannelRouter} 按优先级分层求值；最高优先级层中第一个匹配的绑定获胜。
     */
    @JsonProperty("bindings")
    private List<BindingConfigEntry> bindings;

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Map<String, Object> getProperties() {
        return properties;
    }

    public void setProperties(Map<String, Object> properties) {
        this.properties = properties;
    }

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

    public List<BindingConfigEntry> getBindings() {
        return bindings;
    }

    public void setBindings(List<BindingConfigEntry> bindings) {
        this.bindings = bindings;
    }

    /**
     * 将此条目转换为指定通道 ID 的 {@link ChannelConfig}。
     */
    public ChannelConfig toChannelConfig(String channelId) {
        DmScope scope = DmScope.MAIN;
        if (dmScope != null && !dmScope.isBlank()) {
            try {
                scope = DmScope.valueOf(dmScope.toUpperCase());
            } catch (IllegalArgumentException ignored) {
                // 未知值：回退到 MAIN。
            }
        }
        List<ChannelBinding> resolved = new ArrayList<>();
        if (bindings != null) {
            for (BindingConfigEntry e : bindings) {
                if (e == null) continue;
                try {
                    resolved.add(e.toBinding());
                } catch (IllegalArgumentException ignored) {
                    // 跳过格式错误的绑定（缺少 agentId）。
                }
            }
        }
        return ChannelConfig.builder(channelId)
                .defaultAgentId(defaultAgentId)
                .dmScope(scope)
                .bindings(resolved)
                .build();
    }
}
