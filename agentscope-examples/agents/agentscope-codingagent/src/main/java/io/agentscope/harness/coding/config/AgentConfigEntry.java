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
import io.agentscope.harness.agent.HarnessAgent;

/**
 * {@code agentscope.json} 中 {@code agents.<agentId>} 下的单智能体配置。
 *
 * <p>智能体构建后，{@link HarnessAgent} 自动从解析后的 {@link #workspace} 目录加载额外工作区级配置，例如 {@code subagents/*.md}。
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentConfigEntry {

    @JsonProperty("name")
    private String name;

    @JsonProperty("description")
    private String description;

    @JsonProperty("sysPrompt")
    private String sysPrompt;

    /**
     * 此智能体的工作区根目录。相对路径相对启动器工作目录解析。
     */
    @JsonProperty("workspace")
    private String workspace;

    @JsonProperty("maxIters")
    private Integer maxIters;

    @JsonProperty("environmentMemory")
    private String environmentMemory;

    @JsonProperty("skillRepository")
    private SkillRepositoryConfigEntry skillRepository;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getSysPrompt() {
        return sysPrompt;
    }

    public void setSysPrompt(String sysPrompt) {
        this.sysPrompt = sysPrompt;
    }

    public String getWorkspace() {
        return workspace;
    }

    public void setWorkspace(String workspace) {
        this.workspace = workspace;
    }

    public Integer getMaxIters() {
        return maxIters;
    }

    public void setMaxIters(Integer maxIters) {
        this.maxIters = maxIters;
    }

    public String getEnvironmentMemory() {
        return environmentMemory;
    }

    public void setEnvironmentMemory(String environmentMemory) {
        this.environmentMemory = environmentMemory;
    }

    public SkillRepositoryConfigEntry getSkillRepository() {
        return skillRepository;
    }

    public void setSkillRepository(SkillRepositoryConfigEntry skillRepository) {
        this.skillRepository = skillRepository;
    }
}
