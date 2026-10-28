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
import io.agentscope.harness.coding.CodingBootstrap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * {@code ${cwd}/.agentscope/agentscope.json} 的根文档结构。
 *
 * <p>形态有意接近 OpenClaw 顶层配置：{@code main} 入口 ID、以智能体 ID 为键的 {@code agents} 映射，以及可选的以通道 ID 为键的 {@code channels} 映射。
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentscopeConfig {

    /**
     * 供编辑器使用的可选 JSON Schema 引用。
     */
    @JsonProperty("$schema")
    private String schema;

    /**
     * 默认入口智能体 ID。设置编程式 {@link CodingBootstrap.Builder#mainAgent(String)} 时覆盖此值。
     */
    @JsonProperty("main")
    private String main;

    @JsonProperty("agents")
    private Map<String, AgentConfigEntry> agents = new LinkedHashMap<>();

    /**
     * 以通道 ID（例如 {@code "chatui"}、{@code "slack"}）为键的可选通道配置。无相同 ID 编程注册时，根据条目自动创建内置 {@code chatui} 通道。其他通道类型的条目提供启动时应用的路由配置（{@link ChannelConfigEntry#toChannelConfig}）。
     */
    @JsonProperty("channels")
    private Map<String, ChannelConfigEntry> channels = new LinkedHashMap<>();

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
}
