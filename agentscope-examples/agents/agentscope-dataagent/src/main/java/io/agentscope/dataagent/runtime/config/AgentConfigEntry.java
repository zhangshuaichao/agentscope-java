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
import io.agentscope.harness.agent.HarnessAgent;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code agentscope.json} 中 {@code agents.<agentId>} 下的单个智能体配置。
 *
 * <p>构建智能体后，{@link HarnessAgent} 自动从解析后的 {@link
 * #workspace} 目录加载工作区范围的附加配置（例如 {@code subagents/*.md}）。
 *
 * <p>字段对应 OpenClaw 的智能体定义模式：
 * <ul>
 *   <li>{@link #model} — 覆盖模型 ID（例如 {@code "anthropic/claude-opus-4-7"}）
 *   <li>{@link #tools} — 内置工具允许/拒绝列表
 *   <li>{@link #identity} — 覆盖显示名称和表情
 *   <li>{@link #groupChat} — 群组/房间通道的提及触发门控
 *   <li>{@link #skills} — 技能允许/拒绝列表
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

    /** 智能体的工作区根目录。相对路径以引导工作目录为基准解析。 */
    @JsonProperty("workspace")
    private String workspace;

    @JsonProperty("maxIters")
    private Integer maxIters;

    @JsonProperty("environmentMemory")
    private String environmentMemory;

    /**
     * 旧版单值技能仓库。保留此字段以兼容旧 {@code agentscope.json} 文件的反序列化；
     * 若存在，则在生成有效列表时将其并入 {@link #skillRepositories} 的首部。
     *
     * @deprecated 推荐使用 {@link #skillRepositories}，支持工作区技能、市场安装技能与内置技能的分层模式。
     */
    @Deprecated
    @JsonProperty("skillRepository")
    private SkillRepositoryConfigEntry skillRepository;

    /** 分层技能仓库。各项按顺序加入智能体的有效 {@link io.agentscope.core.skill.repository.SkillRepository} 列表，因此技能重名时较早的项优先。{@code workspace/skills/} 覆盖层由 {@link io.agentscope.dataagent.web.workspace.WorkspaceManagerFactory} 自动隐式添加，请勿在此重复列出。 */
    @JsonProperty("skillRepositories")
    private List<SkillRepositoryConfigEntry> skillRepositories;

    /** 可选模型 ID 覆盖值（例如 {@code "anthropic/claude-opus-4-7"}）。为 null 时使用引导层模型。 */
    @JsonProperty("model")
    private String model;

    /** 工具允许/拒绝列表。{@code allow} 非空时，仅向智能体提供名称在其中的工具。无论是否在 {@code allow} 中，{@code deny} 中的工具都会被移除。 */
    @JsonProperty("tools")
    private ToolsConfig tools;

    /** 显示身份覆盖值（名称、表情）。 */
    @JsonProperty("identity")
    private IdentityConfig identity;

    /** 群聊门控配置（提及模式、requireMention）。 */
    @JsonProperty("groupChat")
    private GroupChatConfig groupChat;

    /** 技能允许/拒绝列表。 */
    @JsonProperty("skills")
    private SkillsConfig skills;

    // -----------------------------------------------------------------
    // 访问器与设置器
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

    @Deprecated
    public SkillRepositoryConfigEntry getSkillRepository() {
        return skillRepository;
    }

    @Deprecated
    public void setSkillRepository(SkillRepositoryConfigEntry skillRepository) {
        this.skillRepository = skillRepository;
    }

    public List<SkillRepositoryConfigEntry> getSkillRepositories() {
        return skillRepositories;
    }

    public void setSkillRepositories(List<SkillRepositoryConfigEntry> skillRepositories) {
        this.skillRepositories = skillRepositories;
    }

    /** 返回有效且有序的技能仓库配置列表：将旧 {@link #skillRepository} 值（若存在）并入首部，保证旧配置仍可原样加载。不会为 null，但可以为空。 */
    public List<SkillRepositoryConfigEntry> effectiveSkillRepositories() {
        List<SkillRepositoryConfigEntry> out = new ArrayList<>();
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

        /** 列表非空时，仅向智能体提供其中的工具。 */
        @JsonProperty("allow")
        private List<String> allow;

        /** 始终移除此列表中的工具，即使也在 {@code allow} 中。 */
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

        /** 显示名称覆盖值（用于聊天界面和日志）。 */
        @JsonProperty("name")
        private String name;

        /** 便于视觉辨识的表情简写。 */
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

        /** 触发智能体响应群消息的模式列表（精确字符串或前缀）。为空时响应群中的所有消息。 */
        @JsonProperty("mentionPatterns")
        private List<String> mentionPatterns;

        /** 为 {@code true} 时，仅在提及模式匹配时响应。默认为 {@code false}（响应全部消息）。 */
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
    public static class SkillsConfig {

        /** 列表非空时，仅为智能体加载其中的技能。 */
        @JsonProperty("allow")
        private List<String> allow;

        /** 永不加载此列表中的技能，即使也在 {@code allow} 中。 */
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
