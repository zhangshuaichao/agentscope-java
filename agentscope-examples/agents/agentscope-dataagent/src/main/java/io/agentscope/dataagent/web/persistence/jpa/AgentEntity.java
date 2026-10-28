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
package io.agentscope.dataagent.web.persistence.jpa;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.ArrayList;
import java.util.List;

/**
 * 用户自定义智能体定义的持久化表示。以合成 {@code row_id} 为主键，
 * 由 {@code (owner_id, agent_id)} 组合唯一标识。
 * <h2>与用户表的关系</h2>
 * <p>{@code owner_id} 是指向 {@link UserEntity#getUserId()} 的软外键。JPA 不管理级联删除；
 * 应用层 {@link io.agentscope.dataagent.web.api.AdminUserController#delete}
 * 负责撤销授权及删除被删用户拥有的智能体。模式将 {@code owner_id} 保留为带索引普通列，
 * 部署方可按需通过迁移脚本增加数据库级 {@code FOREIGN KEY ... ON DELETE CASCADE}。
 * <h2>存储布局</h2>
 * <ul>
 *   <li>标量元数据（名称、描述、模型、runAs 等）存入独立列。</li>
 *   <li>列表设置（工具允许/拒绝、技能允许/拒绝、群聊提及模式）各以 JSON 字符串保存于一列，
 *       避免为有限配置列表建立关联表，保持 MySQL / PostgreSQL / H2 兼容，
 *       并与 JSON 文件后端传输格式一致，便于双向迁移。</li>
 *   <li>{@link AgentShareEntity} 通过启用 {@code orphanRemoval = true} 的一对多关系映射；
 *       共享列表在同一事务中整体原子替换。</li>
 * </ul>
 */
@Entity
@Table(
        name = "dataagent_agent",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "ux_dataagent_agent_owner_agent",
                        columnNames = {"owner_id", "agent_id"}),
        indexes = {
            @Index(name = "ix_dataagent_agent_owner", columnList = "owner_id"),
            @Index(name = "ix_dataagent_agent_agent_id", columnList = "agent_id")
        })
public class AgentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "row_id")
    private Long rowId;

    @Column(name = "owner_id", length = 128, nullable = false)
    private String ownerId;

    @Column(name = "agent_id", length = 128, nullable = false)
    private String agentId;

    /** 用户提供的智能体工作区路径（原样保存，可为 {@code null}）。非 null 时绝对路径直接使用，相对路径在 {@code ${cwd}/.agentscope/} 下解析；{@code null} 时使用智能体 ID。完整规则见 {@link io.agentscope.dataagent.web.workspace.WorkspaceManagerFactory#resolveAgentDataPath}。此列为权威来源，运行时据此定位逐智能体磁盘数据。 */
    @Column(name = "workspace_path", length = 1024)
    private String workspacePath;

    @Column(name = "name", length = 200)
    private String name;

    @Lob
    @Column(name = "description")
    private String description;

    @Lob
    @Column(name = "sys_prompt")
    private String sysPrompt;

    @Column(name = "model", length = 100)
    private String model;

    @Column(name = "max_iters")
    private Integer maxIters;

    @Lob
    @Column(name = "tools_allow_json")
    private String toolsAllowJson;

    @Lob
    @Column(name = "tools_deny_json")
    private String toolsDenyJson;

    @Column(name = "identity_name", length = 200)
    private String identityName;

    @Column(name = "identity_emoji", length = 32)
    private String identityEmoji;

    @Lob
    @Column(name = "group_chat_mention_patterns_json")
    private String groupChatMentionPatternsJson;

    @Column(name = "group_chat_require_mention")
    private Boolean groupChatRequireMention;

    @Lob
    @Column(name = "skills_allow_json")
    private String skillsAllowJson;

    @Lob
    @Column(name = "skills_deny_json")
    private String skillsDenyJson;

    @Column(name = "run_as", length = 20)
    private String runAs;

    @Column(name = "fork_of", length = 128)
    private String forkOf;

    /** JSON 序列化的 {@link io.agentscope.dataagent.runtime.config.SkillRepositoryConfigEntry} 列表，与 {@code agentscope.json} 的 {@code skillRepositories} 部分采用相同传输格式，支持双向迁移。智能体仅使用隐式工作区覆盖层时为 null。 */
    @Lob
    @Column(name = "skill_repositories_json")
    private String skillRepositoriesJson;

    /** 沙箱执行模式（{@code local} / {@code sandbox}）；null 表示运行时使用平台默认值。 */
    @Column(name = "sandbox_mode", length = 16)
    private String sandboxMode;

    /** 沙箱隔离范围（{@code SESSION} / {@code USER} / {@code AGENT} / {@code GLOBAL}）；仅在 {@link #sandboxMode} 为 {@code sandbox} 时有意义。 */
    @Column(name = "sandbox_scope", length = 16)
    private String sandboxScope;

    @Column(name = "created_at")
    private long createdAt;

    @Column(name = "updated_at")
    private long updatedAt;

    @OneToMany(
            mappedBy = "agent",
            cascade = CascadeType.ALL,
            orphanRemoval = true,
            fetch = FetchType.EAGER)
    private List<AgentShareEntity> shares = new ArrayList<>();

    public AgentEntity() {}

    public Long getRowId() {
        return rowId;
    }

    public void setRowId(Long rowId) {
        this.rowId = rowId;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public String getWorkspacePath() {
        return workspacePath;
    }

    public void setWorkspacePath(String workspacePath) {
        this.workspacePath = workspacePath;
    }

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

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Integer getMaxIters() {
        return maxIters;
    }

    public void setMaxIters(Integer maxIters) {
        this.maxIters = maxIters;
    }

    public String getToolsAllowJson() {
        return toolsAllowJson;
    }

    public void setToolsAllowJson(String toolsAllowJson) {
        this.toolsAllowJson = toolsAllowJson;
    }

    public String getToolsDenyJson() {
        return toolsDenyJson;
    }

    public void setToolsDenyJson(String toolsDenyJson) {
        this.toolsDenyJson = toolsDenyJson;
    }

    public String getIdentityName() {
        return identityName;
    }

    public void setIdentityName(String identityName) {
        this.identityName = identityName;
    }

    public String getIdentityEmoji() {
        return identityEmoji;
    }

    public void setIdentityEmoji(String identityEmoji) {
        this.identityEmoji = identityEmoji;
    }

    public String getGroupChatMentionPatternsJson() {
        return groupChatMentionPatternsJson;
    }

    public void setGroupChatMentionPatternsJson(String groupChatMentionPatternsJson) {
        this.groupChatMentionPatternsJson = groupChatMentionPatternsJson;
    }

    public Boolean getGroupChatRequireMention() {
        return groupChatRequireMention;
    }

    public void setGroupChatRequireMention(Boolean groupChatRequireMention) {
        this.groupChatRequireMention = groupChatRequireMention;
    }

    public String getSkillsAllowJson() {
        return skillsAllowJson;
    }

    public void setSkillsAllowJson(String skillsAllowJson) {
        this.skillsAllowJson = skillsAllowJson;
    }

    public String getSkillsDenyJson() {
        return skillsDenyJson;
    }

    public void setSkillsDenyJson(String skillsDenyJson) {
        this.skillsDenyJson = skillsDenyJson;
    }

    public String getRunAs() {
        return runAs;
    }

    public void setRunAs(String runAs) {
        this.runAs = runAs;
    }

    public String getForkOf() {
        return forkOf;
    }

    public void setForkOf(String forkOf) {
        this.forkOf = forkOf;
    }

    public String getSkillRepositoriesJson() {
        return skillRepositoriesJson;
    }

    public void setSkillRepositoriesJson(String skillRepositoriesJson) {
        this.skillRepositoriesJson = skillRepositoriesJson;
    }

    public String getSandboxMode() {
        return sandboxMode;
    }

    public void setSandboxMode(String sandboxMode) {
        this.sandboxMode = sandboxMode;
    }

    public String getSandboxScope() {
        return sandboxScope;
    }

    public void setSandboxScope(String sandboxScope) {
        this.sandboxScope = sandboxScope;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }

    public List<AgentShareEntity> getShares() {
        return shares;
    }

    public void setShares(List<AgentShareEntity> shares) {
        this.shares = shares;
    }
}
