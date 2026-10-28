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

/**
 * {@code agentscope.json} 等配置中的声明式技能仓库设置。
 *
 * <p>使用 {@code type: "filesystem"} 配合 {@link #path}，或 {@code type: "git"} 配合 {@link #remoteUrl}。Git 支持要求类路径包含 {@code io.agentscope:agentscope-extensions-skill-git-repository}。
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
public class SkillRepositoryConfigEntry {

    /**
     * {@code filesystem}：从目录加载（{@link #path}，相对引导器 {@code cwd}）。
     *
     * <p>{@code git}：克隆／同步远程仓库（{@link #remoteUrl}，以及可选 {@link #branch}、{@link #localPath} 等）。
     */
    @JsonProperty("type")
    private String type;

    /**
     * 包含技能文件夹的目录（每个文件夹含 {@code SKILL.md}），用于 {@code type} 为 {@code filesystem} 的情况。
     */
    @JsonProperty("path")
    private String path;

    @JsonProperty("remoteUrl")
    private String remoteUrl;

    @JsonProperty("branch")
    private String branch;

    /**
     * 仓库内包含技能文件夹的可选子目录，仅 {@code type} 为 {@code git} 时使用，否则忽略。为空白时优先使用存在的 {@code skills/}，否则使用仓库根目录。必须是相对仓库根目录的路径；底层实现拒绝绝对路径及 {@code ..} 段。
     */
    @JsonProperty("skillsRoot")
    private String skillsRoot;

    /**
     * 本地克隆目录，设置后以引导器 {@code cwd} 为基准解析。对 {@code git} 可选，否则 {@code GitSkillRepository} 使用临时目录。
     */
    @JsonProperty("localPath")
    private String localPath;

    @JsonProperty("source")
    private String source;

    @JsonProperty("autoSync")
    private Boolean autoSync;

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getRemoteUrl() {
        return remoteUrl;
    }

    public void setRemoteUrl(String remoteUrl) {
        this.remoteUrl = remoteUrl;
    }

    public String getBranch() {
        return branch;
    }

    public void setBranch(String branch) {
        this.branch = branch;
    }

    public String getSkillsRoot() {
        return skillsRoot;
    }

    public void setSkillsRoot(String skillsRoot) {
        this.skillsRoot = skillsRoot;
    }

    public String getLocalPath() {
        return localPath;
    }

    public void setLocalPath(String localPath) {
        this.localPath = localPath;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public Boolean getAutoSync() {
        return autoSync;
    }

    public void setAutoSync(Boolean autoSync) {
        this.autoSync = autoSync;
    }
}
