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
package io.agentscope.dataagent.runtime.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.agentscope.dataagent.runtime.DataAgentBootstrap;
import io.agentscope.harness.agent.gateway.channel.ChannelBinding;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import io.agentscope.harness.agent.gateway.channel.DmScope;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code agentscope.json} 中 {@code channels.<channelId>} 下的单通道配置。
 * <p>定义通道适配器的路由配置。如果没有通过编程式 {@link
 * DataAgentBootstrap.Builder#channel(io.agentscope.harness.agent.gateway.channel.Channel...)} 注册同通道，
 * 会依据此项自动创建内置 {@code chatui} 通道。对其他类型，此项提供引导时应用的 {@link ChannelConfig} 路由规则。
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
 * @see AgentscopeConfig
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChannelConfigEntry {

    /** 通道类型判别字段，由 {@link ChannelTypeRegistry} 据此选择构建实例的 {@link ChannelFactory}。此配置模型使用的类型名称包括 {@code chatui}、{@code dingtalk}、{@code wecom}、{@code feishu}、{@code github}、{@code gitlab}；当前应用实际注册的内置类型见注册表。通过 {@link DataAgentBootstrap.Builder#channel(io.agentscope.harness.agent.gateway.channel.Channel...)} 编程注册时可省略。 */
    @JsonProperty("type")
    private String type;

    /** 提供方特定属性（例如凭据、端点、签名密钥）。原样传递给 {@link ChannelFactory#create(String, ChannelConfig, Map)}。 */
    @JsonProperty("properties")
    private Map<String, Object> properties;

    /** 没有匹配绑定时使用的后备智能体 ID。省略时回退到全局绑定的主智能体。 */
    @JsonProperty("defaultAgentId")
    private String defaultAgentId;

    /**
     * 控制私信会话键的范围。可取 {@code MAIN}、{@code PER_PEER}、{@code
     * PER_CHANNEL_PEER}、{@code PER_ACCOUNT_CHANNEL_PEER}。省略时默认为 {@code MAIN}。
     * @see DmScope
     */
    @JsonProperty("dmScope")
    private String dmScope;

    /** 为 {@code true} 时，引导阶段忽略此通道项：既不创建通道实例，也不启动编程注册的同 ID 通道。 */
    @JsonProperty("disabled")
    private Boolean disabled;

    /** 有序的 {@link io.agentscope.harness.agent.gateway.channel.ChannelBinding} 路由规则列表，由 {@link io.agentscope.harness.agent.gateway.channel.ChannelRouter} 分优先级层次评估。最高优先级层次中首个匹配的绑定获选。 */
    @JsonProperty("bindings")
    private List<BindingConfigEntry> bindings;

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Map<String, Object> getProperties() {
        return properties != null ? properties : Map.of();
    }

    public void setProperties(Map<String, Object> properties) {
        this.properties = properties == null ? null : new LinkedHashMap<>(properties);
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

    /** 将此项转换为给定通道 ID 的 {@link ChannelConfig}。 */
    public ChannelConfig toChannelConfig(String channelId) {
        DmScope scope = DmScope.MAIN;
        if (dmScope != null && !dmScope.isBlank()) {
            try {
                scope = DmScope.valueOf(dmScope.toUpperCase());
            } catch (IllegalArgumentException ignored) {
                // 未知值 → 回退到 MAIN
            }
        }
        List<ChannelBinding> resolved = new ArrayList<>();
        if (bindings != null) {
            for (BindingConfigEntry e : bindings) {
                if (e == null) continue;
                try {
                    resolved.add(e.toBinding());
                } catch (IllegalArgumentException ignored) {
                    // 跳过格式不合法的绑定（缺少 agentId）
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
