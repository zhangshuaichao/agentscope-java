/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.agentscope.spring.boot.nacos.properties.a2a;

/**
 * 向 Nacos A2A Registry 注册的 A2A 属性。
 *
 * <p>包含注册智能体的子属性，便于开发者按需控制注册行为。
 *
 * <p>YAML 示例：
 * <pre>{@code
 * agentscope:
 *   a2a:
 *     nacos:
 *       registry:
 *         enabled: true
 *         overwrite-preferred-transport: JSONRPC
 * }</pre>
 *
 * <p>发现属性参见 {@link NacosA2aDiscoveryProperties}。
 */
public class NacosA2aRegistryProperties {

    /**
     * 是否启用 Nacos A2A 注册。
     *
     * <p>默认 {@code true}；顶层集成启用后，从 {@link io.agentscope.core.a2a.server.AgentScopeA2aServer} 读取此智能体的 AgentCard、A2A 传输信息等，并注册到 Nacos。
     */
    private boolean enabled = true;

    /**
     * 是否将当前新版本注册为智能体最新版本。
     *
     * <p>默认 {@code true}，将当前新版本设为最新。
     *
     * <p>只影响此前不存在的新版本。若当前版本已在 Nacos 注册，服务端忽略该注册行为，即使配置 {@code true}，也不会将现有版本改为最新。
     *
     * <p>此设计避免其他智能体覆盖最新版本。
     */
    private boolean registerAsLatest = true;

    /**
     * 是否向 Nacos 注册此智能体的传输端点。
     *
     * <p>默认 {@code true}，从 {@link io.agentscope.core.a2a.server.AgentScopeA2aServer} 读取需暴露的 A2A 传输信息，转换为 A2A Endpoint 注册到 Nacos。
     *
     * <p>{@link #enabled} 优先级更高；{@link #enabled} 为 false 时，即使本属性为 {@code true}，端点注册也禁用。
     */
    private boolean enabledRegisterEndpoint = true;

    /**
     * 设置此属性后覆盖 agentCard 中的 preferredTransport 和 url。
     *
     * <p>若从 AgentScope 及所有属性中均未找到对应传输，则忽略覆盖。
     */
    private String overwritePreferredTransport;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isRegisterAsLatest() {
        return registerAsLatest;
    }

    public void setRegisterAsLatest(boolean registerAsLatest) {
        this.registerAsLatest = registerAsLatest;
    }

    public boolean isEnabledRegisterEndpoint() {
        return enabledRegisterEndpoint;
    }

    public void setEnabledRegisterEndpoint(boolean enabledRegisterEndpoint) {
        this.enabledRegisterEndpoint = enabledRegisterEndpoint;
    }

    public String getOverwritePreferredTransport() {
        return overwritePreferredTransport;
    }

    public void setOverwritePreferredTransport(String overwritePreferredTransport) {
        this.overwritePreferredTransport = overwritePreferredTransport;
    }
}
