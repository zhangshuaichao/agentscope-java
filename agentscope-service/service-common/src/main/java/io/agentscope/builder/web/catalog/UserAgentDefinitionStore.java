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
package io.agentscope.builder.web.catalog;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentscope.builder.runtime.config.AgentConfigEntry;
import io.agentscope.builder.runtime.config.SkillRepositoryConfigEntry;
import io.agentscope.builder.web.catalog.spec.AgentSpecCodec;
import io.agentscope.builder.web.catalog.spec.AgentSpecTypes.AgentToolset;
import io.agentscope.builder.web.catalog.spec.AgentSpecTypes.McpServerSpec;
import io.agentscope.builder.web.catalog.spec.AgentSpecTypes.MultiagentSpec;
import io.agentscope.builder.web.catalog.spec.AgentSpecTypes.SkillRef;
import io.agentscope.builder.web.share.AgentShareGrant;
import java.util.List;
import java.util.Optional;

/**
 * 每用户自定义智能体定义注册表的抽象。
 * <p>智能体定义承载面向用户的元数据（ID、名称、描述、系统提示词、模型覆盖值、
 * tools/mcpServers/skills、身份配置、共享授权等）。工作区文件（技能、子智能体、记忆等）
 * 另存于智能体的命名空间工作区目录；此处只存轻量定义。
 * <p>随附的唯一实现是
 * {@link io.agentscope.builder.web.persistence.jpa.JpaUserAgentDefinitionStore}，
 * 通过 Spring Data JPA 持久化，与用户表保持软外键关联。默认 DataSource 为
 * {@code ${user.home}/.agentscope-builder/db} 下的嵌入式 H2；启用
 * {@code jdbc} Spring 配置环境（或设置 {@code BUILDER_DB_URL}）可使用 MySQL / PostgreSQL。
 * <p>实现应保证线程安全。
 */
public interface UserAgentDefinitionStore {

    /** 返回 {@code userId} 拥有的全部智能体定义，按插入顺序排列。 */
    List<StoredEntry> list(String userId);

    /** 为给定用户按 ID 查找单个智能体定义。 */
    Optional<StoredEntry> findById(String userId, String agentId);

    /** 为给定用户保存（创建或更新）智能体定义。实现应原子地持久化。 */
    StoredEntry save(String userId, StoredEntry entry);

    /** 删除智能体定义。条目存在且已移除时返回 {@code true}。 */
    boolean delete(String userId, String agentId);

    // -----------------------------------------------------------------
    // 存储数据模型——供应用各处使用的稳定传输格式
    // -----------------------------------------------------------------

    /**
     * 按用户存储、可进行 JSON 序列化的智能体定义。除 {@code
     * id} 外所有字段均可选。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record StoredEntry(
            String id,
            String name,
            String description,
            String system,
            String model,
            Integer maxIters,
            List<AgentToolset> tools,
            List<McpServerSpec> mcpServers,
            List<SkillRef> skills,
            MultiagentSpec multiagent,
            String identityName,
            String identityEmoji,
            List<String> groupChatMentionPatterns,
            Boolean groupChatRequireMention,
            long createdAt,
            long updatedAt,
            List<AgentShareGrant> shares,
            String runAs,
            String forkOf,
            String workspacePath,
            List<SkillRepositoryConfigEntry> skillRepositories,
            String sandboxMode,
            String sandboxScope,
            int version,
            Long archivedAt) {

        public AgentDefinition toDefinition(String ownerId) {
            return new AgentDefinition(
                    id,
                    name != null ? name : id,
                    description,
                    system,
                    model,
                    maxIters,
                    tools,
                    mcpServers,
                    skills,
                    multiagent,
                    identityName,
                    identityEmoji,
                    groupChatMentionPatterns,
                    groupChatRequireMention,
                    AgentDefinition.SCOPE_USER,
                    ownerId,
                    createdAt,
                    updatedAt,
                    shares,
                    runAs != null ? runAs : AgentDefinition.RUN_AS_INVOKER,
                    forkOf,
                    workspacePath,
                    sandboxMode,
                    sandboxScope,
                    version,
                    archivedAt,
                    null, // 元数据
                    null); // tierForCurrentUser — 由控制器填充
        }

        /** 转换为用于运行时构建智能体的部分 {@link AgentConfigEntry}。 */
        public AgentConfigEntry toConfigEntry() {
            AgentConfigEntry e = new AgentConfigEntry();
            e.setName(name);
            e.setDescription(description);
            e.setSysPrompt(system);
            e.setModel(model);
            e.setMaxIters(maxIters);
            var toolsConfig = AgentSpecCodec.toToolsConfig(tools, mcpServers);
            if (toolsConfig.getAllow() != null || toolsConfig.getDeny() != null) {
                AgentConfigEntry.ToolsConfig tc = new AgentConfigEntry.ToolsConfig();
                tc.setAllow(toolsConfig.getAllow());
                tc.setDeny(toolsConfig.getDeny());
                e.setTools(tc);
            }
            if (identityName != null || identityEmoji != null) {
                AgentConfigEntry.IdentityConfig ic = new AgentConfigEntry.IdentityConfig();
                ic.setName(identityName);
                ic.setEmoji(identityEmoji);
                e.setIdentity(ic);
            }
            if (groupChatMentionPatterns != null || groupChatRequireMention != null) {
                AgentConfigEntry.GroupChatConfig gc = new AgentConfigEntry.GroupChatConfig();
                gc.setMentionPatterns(groupChatMentionPatterns);
                gc.setRequireMention(groupChatRequireMention);
                e.setGroupChat(gc);
            }
            List<String> skillNames = AgentSpecCodec.workspaceSkillNames(skills);
            if (!skillNames.isEmpty()) {
                AgentConfigEntry.SkillsConfig sk = new AgentConfigEntry.SkillsConfig();
                sk.setAllow(skillNames);
                e.setSkills(sk);
            }
            if (skillRepositories != null && !skillRepositories.isEmpty()) {
                e.setSkillRepositories(skillRepositories);
            }
            return e;
        }
    }
}
