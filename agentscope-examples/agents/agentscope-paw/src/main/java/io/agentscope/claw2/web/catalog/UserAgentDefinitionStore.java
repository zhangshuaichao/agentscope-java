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
package io.agentscope.claw2.web.catalog;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.agentscope.claw2.runtime.ClawBootstrap;
import io.agentscope.claw2.runtime.config.AgentConfigEntry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 以 {@code ${clawHome}/agents.json} 单个 JSON 文件存储的用户自定义智能体目录。
 *
 * <p>保存各自定义智能体的轻量元数据（ID、名称、描述、系统提示词、模型覆盖值、工具允许／拒绝列表等）。AGENTS.md、技能、子智能体和记忆等文件另存于各智能体工作区，由 {@link io.agentscope.claw2.web.api.AgentWorkspaceController} 管理。
 *
 * <p>通过临时文件加重命名替换配置，并以本实例的 {@link ReentrantReadWriteLock} 协调线程访问；具体原子移动回退与崩溃持久性以实现为准。
 */
@Component
public final class UserAgentDefinitionStore {

    private static final Logger log = LoggerFactory.getLogger(UserAgentDefinitionStore.class);
    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final Path storeFile;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final Map<String, StoredEntry> entries = new LinkedHashMap<>();

    @Autowired
    public UserAgentDefinitionStore(ClawBootstrap bootstrap) {
        this(bootstrap.clawHome().resolve("agents.json"));
    }

    public UserAgentDefinitionStore(Path storeFile) {
        this.storeFile = storeFile;
        load();
    }

    /** 从磁盘加载条目映射；文件缺失或空白时从空集合开始。 */
    private void load() {
        lock.writeLock().lock();
        try {
            entries.clear();
            if (!Files.isRegularFile(storeFile)) {
                return;
            }
            String json = Files.readString(storeFile, StandardCharsets.UTF_8);
            if (json.isBlank()) {
                return;
            }
            Map<String, StoredEntry> loaded =
                    MAPPER.readValue(
                            json, new TypeReference<LinkedHashMap<String, StoredEntry>>() {});
            if (loaded != null) {
                entries.putAll(loaded);
            }
            log.info("Loaded {} custom agent definitions from {}", entries.size(), storeFile);
        } catch (IOException e) {
            log.warn(
                    "Failed to load custom agent definitions from {}: {}",
                    storeFile,
                    e.getMessage());
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** 按插入顺序返回全部自定义智能体定义。 */
    public List<StoredEntry> list() {
        lock.readLock().lock();
        try {
            return List.copyOf(entries.values());
        } finally {
            lock.readLock().unlock();
        }
    }

    public Optional<StoredEntry> findById(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return Optional.empty();
        }
        lock.readLock().lock();
        try {
            return Optional.ofNullable(entries.get(agentId));
        } finally {
            lock.readLock().unlock();
        }
    }

    /** 插入或更新 {@code entry}，返回更新后的内存条目；写盘失败仅记录日志。 */
    public StoredEntry save(StoredEntry entry) {
        if (entry == null || entry.id() == null || entry.id().isBlank()) {
            throw new IllegalArgumentException("entry.id is required");
        }
        lock.writeLock().lock();
        try {
            entries.put(entry.id(), entry);
            flush();
            return entry;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** 删除 {@code agentId} 标识的条目；原条目存在时返回 {@code true}。 */
    public boolean delete(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return false;
        }
        lock.writeLock().lock();
        try {
            if (entries.remove(agentId) != null) {
                flush();
                return true;
            }
            return false;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** 底层 JSON 文件，通常为 {@code ${clawHome}/agents.json}。 */
    public Path storeFile() {
        return storeFile;
    }

    // 【学习提示】写盘失败只记录警告，调用方可能已收到更新后的内存条目；返回成功不等于持久化成功。
    // 原子移动失败会退回普通替换，也没有显式 fsync；它不提供数据库式事务与断电持久性。
    private void flush() {
        try {
            Path parent = storeFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = storeFile.resolveSibling(storeFile.getFileName() + ".tmp");
            byte[] bytes = MAPPER.writeValueAsBytes(entries);
            Files.write(tmp, bytes);
            try {
                Files.move(
                        tmp,
                        storeFile,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(tmp, storeFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.warn(
                    "Failed to persist custom agent definitions to {}: {}",
                    storeFile,
                    e.getMessage());
        }
    }

    // -----------------------------------------------------------------
    // 存储数据模型：稳定的 JSON 传输格式
    // -----------------------------------------------------------------

    /** 可序列化为 JSON 的智能体定义；除 {@code id} 外均为可选字段。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StoredEntry(
            String id,
            String name,
            String description,
            String sysPrompt,
            String model,
            Integer maxIters,
            List<String> toolsAllow,
            List<String> toolsDeny,
            String identityName,
            String identityEmoji,
            List<String> groupChatMentionPatterns,
            Boolean groupChatRequireMention,
            String sandboxMode,
            String sandboxScope,
            List<String> skillsAllow,
            List<String> skillsDeny,
            long createdAt,
            long updatedAt,
            String workspacePath) {

        public AgentDefinition toDefinition() {
            return new AgentDefinition(
                    id,
                    name != null ? name : id,
                    description,
                    sysPrompt,
                    model,
                    maxIters,
                    null, // 运行时解析实际工具列表
                    toolsAllow,
                    toolsDeny,
                    identityName,
                    identityEmoji,
                    groupChatMentionPatterns,
                    groupChatRequireMention,
                    sandboxMode,
                    sandboxScope,
                    skillsAllow,
                    skillsDeny,
                    createdAt,
                    updatedAt,
                    workspacePath,
                    false);
        }

        /** 构建部分 {@link AgentConfigEntry}，供运行时实例化智能体。 */
        public AgentConfigEntry toConfigEntry() {
            AgentConfigEntry e = new AgentConfigEntry();
            e.setName(name);
            e.setDescription(description);
            e.setSysPrompt(sysPrompt);
            e.setModel(model);
            e.setMaxIters(maxIters);
            if (toolsAllow != null || toolsDeny != null) {
                AgentConfigEntry.ToolsConfig tc = new AgentConfigEntry.ToolsConfig();
                tc.setAllow(toolsAllow);
                tc.setDeny(toolsDeny);
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
            if (sandboxMode != null || sandboxScope != null) {
                AgentConfigEntry.SandboxConfig sc = new AgentConfigEntry.SandboxConfig();
                sc.setMode(sandboxMode);
                sc.setScope(sandboxScope);
                e.setSandbox(sc);
            }
            if (skillsAllow != null || skillsDeny != null) {
                AgentConfigEntry.SkillsConfig sk = new AgentConfigEntry.SkillsConfig();
                sk.setAllow(skillsAllow);
                sk.setDeny(skillsDeny);
                e.setSkills(sk);
            }
            return e;
        }
    }
}
