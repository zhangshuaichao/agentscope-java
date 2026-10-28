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
package io.agentscope.builder.web.managed.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.builder.runtime.config.SkillRepositoryConfigEntry;
import io.agentscope.builder.web.catalog.UserAgentDefinitionStore;
import io.agentscope.builder.web.catalog.spec.AgentSpecCodec;
import io.agentscope.builder.web.managed.AgentVersionSnapshot;
import io.agentscope.builder.web.persistence.jpa.AgentEntity;
import io.agentscope.builder.web.persistence.jpa.AgentEntityRepository;
import io.agentscope.builder.web.persistence.jpa.AgentVersionEntity;
import io.agentscope.builder.web.persistence.jpa.AgentVersionEntityRepository;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 管理用户自定义智能体的不可变版本快照。 */
@Service
@Transactional
public class AgentVersionService {

    /** 全局（项目级）智能体没有自身的 {@code ownerId}，使用此合成所有者 ID 作为版本行的键。全局智能体的版本在用户间共享；参见 {@link #ensureGlobalVersion}。 */
    public static final String GLOBAL_OWNER = "__global__";

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private static final TypeReference<List<SkillRepositoryConfigEntry>> SKILL_REPO_LIST =
            new TypeReference<>() {};

    private final AgentVersionEntityRepository versionRepository;
    private final AgentEntityRepository agentRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AgentVersionService(
            AgentVersionEntityRepository versionRepository, AgentEntityRepository agentRepository) {
        this.versionRepository = versionRepository;
        this.agentRepository = agentRepository;
    }

    /** 从存储的目录条目构建快照。 */
    public AgentVersionSnapshot snapshotFromStoredEntry(
            UserAgentDefinitionStore.StoredEntry entry) {
        return new AgentVersionSnapshot(
                entry.name(),
                entry.description(),
                entry.system(),
                entry.model(),
                entry.maxIters(),
                entry.tools(),
                entry.mcpServers(),
                entry.skills(),
                entry.multiagent(),
                entry.identityName(),
                entry.identityEmoji(),
                entry.groupChatMentionPatterns(),
                entry.groupChatRequireMention(),
                entry.skillRepositories(),
                entry.sandboxMode(),
                entry.sandboxScope());
    }

    /** 从持久化智能体实体构建快照。会话没有可用版本行时作为后备（例如创建于版本快照机制之前的旧会话）。 */
    public AgentVersionSnapshot snapshotFromEntity(AgentEntity entity) {
        return new AgentVersionSnapshot(
                entity.getName(),
                entity.getDescription(),
                entity.getSysPrompt(),
                entity.getModel(),
                entity.getMaxIters(),
                AgentSpecCodec.readTools(entity.getToolsJson()),
                AgentSpecCodec.readMcpServers(entity.getMcpServersJson()),
                AgentSpecCodec.readSkills(entity.getSkillsJson()),
                AgentSpecCodec.readMultiagent(entity.getMultiagentJson()),
                entity.getIdentityName(),
                entity.getIdentityEmoji(),
                readStringList(entity.getGroupChatMentionPatternsJson()),
                entity.getGroupChatRequireMention(),
                readSkillRepositories(entity.getSkillRepositoriesJson()),
                entity.getSandboxMode(),
                entity.getSandboxScope());
    }

    /** 将快照序列化为 JSON。 */
    public String toJson(AgentVersionSnapshot snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException ex) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "Failed to serialize snapshot", ex);
        }
    }

    /** 从 JSON 反序列化快照。 */
    public AgentVersionSnapshot fromJson(String json) {
        try {
            return objectMapper.readValue(json, AgentVersionSnapshot.class);
        } catch (JsonProcessingException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid snapshot JSON", ex);
        }
    }

    /** 为新建智能体创建版本 1，并更新头指针。 */
    public int createInitialVersion(String ownerId, String agentId, AgentVersionSnapshot snapshot) {
        AgentEntity agent =
                agentRepository
                        .findByOwnerIdAndAgentId(ownerId, agentId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Agent not found: " + agentId));
        long now = System.currentTimeMillis();
        AgentVersionEntity version = new AgentVersionEntity();
        version.setOwnerId(ownerId);
        version.setAgentId(agentId);
        version.setVersion(1);
        version.setSnapshotJson(toJson(snapshot));
        version.setCreatedAt(now);
        versionRepository.save(version);
        agent.setHeadVersion(1);
        agentRepository.save(agent);
        return 1;
    }

    /** 快照发生变化时追加新版本。返回当前头版本（快照与最新存储版本相同时保持不变）。 */
    public int appendVersion(String ownerId, String agentId, AgentVersionSnapshot snapshot) {
        AgentEntity agent =
                agentRepository
                        .findByOwnerIdAndAgentId(ownerId, agentId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Agent not found: " + agentId));
        String newJson = toJson(snapshot);
        int head = agent.getHeadVersion();
        var latest = versionRepository.findByOwnerIdAndAgentIdAndVersion(ownerId, agentId, head);
        if (latest.isPresent() && Objects.equals(latest.get().getSnapshotJson(), newJson)) {
            return head;
        }
        int next = head + 1;
        long now = System.currentTimeMillis();
        AgentVersionEntity version = new AgentVersionEntity();
        version.setOwnerId(ownerId);
        version.setAgentId(agentId);
        version.setVersion(next);
        version.setSnapshotJson(newJson);
        version.setCreatedAt(now);
        versionRepository.save(version);
        agent.setHeadVersion(next);
        agent.setUpdatedAt(now);
        agentRepository.save(agent);
        return next;
    }

    /** 确保全局智能体存在版本行。快照内容变化时追加新版本（全局智能体没有 {@link AgentEntity} 头指针，因此通过最大版本号确定版本）。 */
    public int ensureGlobalVersion(String agentId, AgentVersionSnapshot snapshot) {
        List<AgentVersionEntity> versions =
                versionRepository.findByOwnerIdAndAgentIdOrderByVersionAsc(GLOBAL_OWNER, agentId);
        String json = toJson(snapshot);
        if (versions.isEmpty()) {
            AgentVersionEntity version = new AgentVersionEntity();
            version.setOwnerId(GLOBAL_OWNER);
            version.setAgentId(agentId);
            version.setVersion(1);
            version.setSnapshotJson(json);
            version.setCreatedAt(System.currentTimeMillis());
            versionRepository.save(version);
            return 1;
        }
        AgentVersionEntity head = versions.get(versions.size() - 1);
        if (json != null && json.equals(head.getSnapshotJson())) {
            return head.getVersion();
        }
        int next = head.getVersion() + 1;
        AgentVersionEntity version = new AgentVersionEntity();
        version.setOwnerId(GLOBAL_OWNER);
        version.setAgentId(agentId);
        version.setVersion(next);
        version.setSnapshotJson(json);
        version.setCreatedAt(System.currentTimeMillis());
        versionRepository.save(version);
        return next;
    }

    /** 按升序列出智能体的所有版本。 */
    @Transactional(readOnly = true)
    public List<AgentVersionEntity> listVersions(String ownerId, String agentId) {
        return versionRepository.findByOwnerIdAndAgentIdOrderByVersionAsc(ownerId, agentId);
    }

    /** 返回指定版本的快照行。 */
    @Transactional(readOnly = true)
    public AgentVersionEntity getVersion(String ownerId, String agentId, int version) {
        return versionRepository
                .findByOwnerIdAndAgentIdAndVersion(ownerId, agentId, version)
                .orElseThrow(
                        () ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND,
                                        "Agent version not found: " + agentId + "@" + version));
    }

    private List<String> readStringList(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    private List<SkillRepositoryConfigEntry> readSkillRepositories(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, SKILL_REPO_LIST);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }
}
