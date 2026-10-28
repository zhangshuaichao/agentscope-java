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
import io.agentscope.harness.agent.HarnessAgent;
import java.util.List;

/**
 * {@code agentscope.json} 中 {@code agents.<agentId>} 下的逐智能体配置。
 *
 * <p>构建智能体后，{@link HarnessAgent} 自动从解析后的 {@link #workspace} 目录加载工作区级附加配置（例如 {@code subagents/*.md}）。
 * <ul>
 *   <li>{@link #model}：覆盖模型 ID（如 {@code "anthropic/claude-opus-4-7"}）
 *   <li>{@link #tools}：内置工具允许／拒绝列表
 *   <li>{@link #identity}：显示名称与表情覆盖
 *   <li>{@link #groupChat}：群组／房间通道的提及门控
 *   <li>{@link #sandbox}：沙箱模式与作用域
 *   <li>{@link #skills}：技能允许／拒绝列表
 * </ul>
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
     * 此智能体的工作区根目录。相对路径以引导器工作目录为基准解析。
     */
    @JsonProperty("workspace")
    private String workspace;

    @JsonProperty("maxIters")
    private Integer maxIters;

    @JsonProperty("environmentMemory")
    private String environmentMemory;

    /**
     * 旧版单仓库字段，保留以兼容已有 {@code agentscope.json} 文件。新配置应优先使用 {@link #skillRepositories}；两者都设置时，单值插入列表头部。
     */
    @JsonProperty("skillRepository")
    private SkillRepositoryConfigEntry skillRepository;

    /**
     * 绑定到智能体的有序技能仓库列表，采用叠加方式。
     */
    @JsonProperty("skillRepositories")
    private List<SkillRepositoryConfigEntry> skillRepositories;

    /**
     * 可选模型 ID 覆盖（如 {@code "anthropic/claude-opus-4-7"}）；为 null 时使用引导器级模型。
     */
    @JsonProperty("model")
    private String model;

    /**
     * 工具允许／拒绝列表。{@code allow} 非空时，仅向智能体提供名称在其中的工具；{@code deny} 中的工具始终移除，不受 {@code allow} 影响。
     */
    @JsonProperty("tools")
    private ToolsConfig tools;

    /**
     * 显示身份覆盖（名称、表情）。
     */
    @JsonProperty("identity")
    private IdentityConfig identity;

    /**
     * 群聊门控配置（提及模式、requireMention）。
     */
    @JsonProperty("groupChat")
    private GroupChatConfig groupChat;

    /**
     * 沙箱模式与作用域。
     */
    @JsonProperty("sandbox")
    private SandboxConfig sandbox;

    /**
     * 技能允许／拒绝列表。
     */
    @JsonProperty("skills")
    private SkillsConfig skills;

    // -----------------------------------------------------------------
    // 读写访问器
    // -----------------------------------------------------------------

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

    public List<SkillRepositoryConfigEntry> getSkillRepositories() {
        return skillRepositories;
    }

    public void setSkillRepositories(List<SkillRepositoryConfigEntry> skillRepositories) {
        this.skillRepositories = skillRepositories;
    }

    /**
     * 返回实际生效的有序技能仓库配置列表，将旧版单值 {@link #skillRepository} 合并到 {@link #skillRepositories} 头部；均未设置时返回空列表。
     */
    public List<SkillRepositoryConfigEntry> getEffectiveSkillRepositories() {
        java.util.ArrayList<SkillRepositoryConfigEntry> out = new java.util.ArrayList<>();
        if (skillRepository != null) {
            out.add(skillRepository);
        }
        if (skillRepositories != null) {
            for (SkillRepositoryConfigEntry e : skillRepositories) {
                if (e != null) out.add(e);
            }
        }
        return out;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public ToolsConfig getTools() {
        return tools;
    }

    public void setTools(ToolsConfig tools) {
        this.tools = tools;
    }

    public IdentityConfig getIdentity() {
        return identity;
    }

    public void setIdentity(IdentityConfig identity) {
        this.identity = identity;
    }

    public GroupChatConfig getGroupChat() {
        return groupChat;
    }

    public void setGroupChat(GroupChatConfig groupChat) {
        this.groupChat = groupChat;
    }

    public SandboxConfig getSandbox() {
        return sandbox;
    }

    public void setSandbox(SandboxConfig sandbox) {
        this.sandbox = sandbox;
    }

    public SkillsConfig getSkills() {
        return skills;
    }

    public void setSkills(SkillsConfig skills) {
        this.skills = skills;
    }

    // -----------------------------------------------------------------
    // 嵌套配置类型
    // -----------------------------------------------------------------

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ToolsConfig {

        /**
         * 非空时，仅向智能体提供列表中的工具。
         */
        @JsonProperty("allow")
        private List<String> allow;

        /**
         * 此列表中的工具始终移除，即使也出现在 {@code allow} 中。
         */
        @JsonProperty("deny")
        private List<String> deny;

        public List<String> getAllow() {
            return allow;
        }

        public void setAllow(List<String> allow) {
            this.allow = allow;
        }

        public List<String> getDeny() {
            return deny;
        }

        public void setDeny(List<String> deny) {
            this.deny = deny;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class IdentityConfig {

        /**
         * 显示名称覆盖，显示于聊天 UI 和日志。
         */
        @JsonProperty("name")
        private String name;

        /**
         * 用于快速视觉识别的表情简称。
         */
        @JsonProperty("emoji")
        private String emoji;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getEmoji() {
            return emoji;
        }

        public void setEmoji(String emoji) {
            this.emoji = emoji;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GroupChatConfig {

        /**
         * 在群消息中触发智能体的匹配模式列表（精确字符串或前缀）；为空时响应群内全部消息。
         */
        @JsonProperty("mentionPatterns")
        private List<String> mentionPatterns;

        /**
         * 为 {@code true} 时，仅在提及模式匹配时响应；默认 {@code false}，即响应全部消息。
         */
        @JsonProperty("requireMention")
        private Boolean requireMention;

        public List<String> getMentionPatterns() {
            return mentionPatterns;
        }

        public void setMentionPatterns(List<String> mentionPatterns) {
            this.mentionPatterns = mentionPatterns;
        }

        public Boolean getRequireMention() {
            return requireMention;
        }

        public void setRequireMention(Boolean requireMention) {
            this.requireMention = requireMention;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SandboxConfig {

        /**
         * 沙箱模式：{@code "off"}（默认，不启用沙箱）、{@code "all"}（始终使用沙箱）、{@code "prompt"}（逐会话由用户批准）。
         */
        @JsonProperty("mode")
        private String mode;

        /**
         * 沙箱作用域：{@code "agent"}（每智能体一个容器）、{@code "session"}（每会话一个容器）、{@code "shared"}（智能体间共享）。
         */
        @JsonProperty("scope")
        private String scope;

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }

        public String getScope() {
            return scope;
        }

        public void setScope(String scope) {
            this.scope = scope;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SkillsConfig {

        /**
         * 非空时，仅为智能体加载此列表中的技能。
         */
        @JsonProperty("allow")
        private List<String> allow;

        /**
         * 此列表中的技能绝不加载，即使也出现在 {@code allow} 中。
         */
        @JsonProperty("deny")
        private List<String> deny;

        public List<String> getAllow() {
            return allow;
        }

        public void setAllow(List<String> allow) {
            this.allow = allow;
        }

        public List<String> getDeny() {
            return deny;
        }

        public void setDeny(List<String> deny) {
            this.deny = deny;
        }
    }
}
