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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 智能体配置根文档。旧路径约定为 {@code ${cwd}/.agentscope/agentscope.json}；当前 ClawBootstrap.defaultConfigPath 实际读取 clawHome 下的 agentscope.json。
 *
 * <p>包含 {@code main} 入口 ID、以智能体 ID 为键的 {@code agents} 映射，以及可选的以通道 ID 为键的 {@code channels} 映射。
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentscopeConfig {

    /**
     * 可选的 JSON Schema 引用，供编辑器使用。
     */
    @JsonProperty("$schema")
    private String schema;

    /**
     * 默认入口的智能体 ID；设置程序化 {@link ClawBootstrap.Builder#mainAgent(String)} 时以后者为准。
     */
    @JsonProperty("main")
    private String main;

    @JsonProperty("agents")
    private Map<String, AgentConfigEntry> agents = new LinkedHashMap<>();

    /**
     * 可选通道配置，以通道 ID（如 {@code "chatui"}、{@code "slack"}）为键。无相同 ID 的程序化通道注册时，从条目自动创建内置 {@code chatui} 通道；其他类型的条目提供引导阶段应用的路由配置（{@link ChannelConfigEntry#toChannelConfig}）。
     */
    @JsonProperty("channels")
    private Map<String, ChannelConfigEntry> channels = new LinkedHashMap<>();

    /**
     * 可选的会话生命周期配置（自动重置、维护），映射到运行时 {@link io.agentscope.claw2.runtime.session.SessionMaintenanceConfig} 以及负责每日／空闲重置的内部调度器。
     */
    @JsonProperty("session")
    private SessionLifecycleConfig session;

    /**
     * 可选全局技能市场，以用户选定 ID（如 {@code "team-git"}、{@code "prod-nacos"}）为键。启动时具体化到 claw 侧 {@code ClawMarketplaceRegistry}；用户可浏览并将技能安装到任意智能体的 {@code workspace/skills/}。这与控制智能体自身运行时加载内容的 {@code agents.<id>.skillRepositories} 字段独立。
     */
    @JsonProperty("marketplaces")
    private Map<String, MarketplaceConfigEntry> marketplaces = new LinkedHashMap<>();

    public String getSchema() {
        return schema;
    }

    public void setSchema(String schema) {
        this.schema = schema;
    }

    public String getMain() {
        return main;
    }

    public void setMain(String main) {
        this.main = main;
    }

    public Map<String, AgentConfigEntry> getAgents() {
        return agents;
    }

    public void setAgents(Map<String, AgentConfigEntry> agents) {
        this.agents = agents != null ? agents : new LinkedHashMap<>();
    }

    public Map<String, ChannelConfigEntry> getChannels() {
        return channels;
    }

    public void setChannels(Map<String, ChannelConfigEntry> channels) {
        this.channels = Objects.requireNonNullElseGet(channels, LinkedHashMap::new);
    }

    public SessionLifecycleConfig getSession() {
        return session;
    }

    public void setSession(SessionLifecycleConfig session) {
        this.session = session;
    }

    public Map<String, MarketplaceConfigEntry> getMarketplaces() {
        return marketplaces;
    }

    public void setMarketplaces(Map<String, MarketplaceConfigEntry> marketplaces) {
        this.marketplaces = Objects.requireNonNullElseGet(marketplaces, LinkedHashMap::new);
    }
}
