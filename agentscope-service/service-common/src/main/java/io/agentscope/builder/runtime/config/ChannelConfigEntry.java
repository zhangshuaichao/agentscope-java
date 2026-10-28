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
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import io.agentscope.harness.agent.gateway.channel.DmScope;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单个通道配置位于 {@code agentscope.json} 的 {@code channels.<channelId>}.
 *
 * <p>定义通道适配器的路由配置。若未以编程方式注册此通道，则内置 {@code chatui} 通道会依据此项自动创建。
 * 对其他通道类型，此项提供 {@link ChannelConfig} 路由规则，在引导时应用。
 *
 * <h2>示例</h2>
 *
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
     * 通道类型判别字段；{@link ChannelTypeRegistry} 据此选择构建通道实例的
     * {@link ChannelFactory}。内置类型：{@code chatui}、
     * {@code dingtalk}、{@code wecom}、{@code feishu}、{@code github}、{@code gitlab}。
     * 宿主应用通过编程方式注册通道时可省略。
     */
    @JsonProperty("type")
    private String type;

    /** 此通道在控制平面的所有者（Builder 用户 ID）。调度器桥接层据此将托管会话归属于配置 IM 身份的租户，而不是 IM 对端。 */
    @JsonProperty("ownerId")
    private String ownerId;

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

    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
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
