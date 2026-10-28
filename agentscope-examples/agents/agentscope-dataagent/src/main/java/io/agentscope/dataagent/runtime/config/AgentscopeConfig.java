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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** {@code ${cwd}/.agentscope/agentscope.json} 的根文档。<p>结构有意与 OpenClaw 顶层配置相近：{@code main} 入口 ID、以智能体 ID 为键的 {@code agents} 映射，以及可选的以通道 ID 为键的 {@code channels} 映射。 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentscopeConfig {

    /** 供编辑器使用的可选 JSON Schema 引用。 */
    @JsonProperty("$schema")
    private String schema;

    /**
     * 默认入口的智能体 ID。设置了编程式 {@link
     * DataAgentBootstrap.Builder#mainAgent(String)} 时由它覆盖。
     */
    @JsonProperty("main")
    private String main;

    @JsonProperty("agents")
    private Map<String, AgentConfigEntry> agents = new LinkedHashMap<>();

    /**
     * 可选通道配置，以通道 ID 为键（例如 {@code "chatui"}、{@code
     * "slack"}）。若没有以编程方式注册同 ID 的通道，会依据此项自动创建内置 {@code chatui} 通道。
     * 对其他通道类型，此项提供引导时应用的路由配置（{@link ChannelConfigEntry#toChannelConfig}）。
     */
    @JsonProperty("channels")
    private Map<String, ChannelConfigEntry> channels = new LinkedHashMap<>();

    /**
     * 可选会话生命周期配置（自动重置、维护）。映射到运行时 {@link
     * io.agentscope.dataagent.runtime.session.SessionMaintenanceConfig}，并由内部调度器处理每日/空闲重置。
     */
    @JsonProperty("session")
    private SessionLifecycleConfig session;

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
}
