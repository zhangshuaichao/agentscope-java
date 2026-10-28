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
package io.agentscope.builder.web.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** Managed Agents 风格会话：将智能体引用与环境配对，并通过仅追加事件日志（{@link SessionEventEntity}）跟踪状态。 */
@Entity
@Table(
        name = "builder_session",
        indexes = {
            @Index(name = "ix_builder_session_owner", columnList = "owner_id"),
            @Index(name = "ix_builder_session_agent", columnList = "agent_id"),
            @Index(name = "ix_builder_session_env", columnList = "environment_id"),
            @Index(name = "ix_builder_session_external_key", columnList = "external_key")
        })
public class ManagedSessionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "row_id")
    private Long rowId;

    @Column(name = "session_id", length = 64, nullable = false, unique = true)
    private String sessionId;

    @Column(name = "owner_id", length = 128, nullable = false)
    private String ownerId;

    @Column(name = "agent_id", length = 128, nullable = false)
    private String agentId;

    @Column(name = "agent_owner_id", length = 128)
    private String agentOwnerId;

    /** 会话运行所用的已解析智能体版本。以 latest 创建且尚未具体化时为 null。 */
    @Column(name = "agent_version")
    private Integer agentVersion;

    /** 智能体引用形式：{@code latest} | {@code pinned} | {@code overrides}。覆盖载荷保存在 {@link #agentOverridesJson}。 */
    @Column(name = "agent_ref_type", length = 32, nullable = false)
    private String agentRefType;

    @Lob
    @Column(name = "agent_overrides_json")
    private String agentOverridesJson;

    @Column(name = "environment_id", length = 64, nullable = false)
    private String environmentId;

    /**
     * 此会话对应的外部通道对话的稳定身份（例如 {@code MsgContext#canonicalKey()}，
     * 如 {@code feishu|r:ou_abc123}）。直接通过 {@code /api/sessions} 创建的会话
     * （聊天界面 / API 调用方）为 null。{@code
     * ManagedSessionChannelBridge} 据此为指定 IM 通道对端查找或创建托管会话，
     * 而不是每收到一条入站消息都新建会话。
     */
    @Column(name = "external_key", length = 256)
    private String externalKey;

    @Lob
    @Column(name = "memory_store_ids_json")
    private String memoryStoreIdsJson;

    @Lob
    @Column(name = "vault_ids_json")
    private String vaultIdsJson;

    /**
     * JSON 编码的资源挂载描述符列表（例如 {@code github_repository} 或 {@code
     * file}），在构建时应用到会话工作区。参见 {@code
     * SessionResourceMountService}。
     */
    @Lob
    @Column(name = "resources_json")
    private String resourcesJson;

    /**
     * {@code created} | {@code running} | {@code idle} | {@code requires_action} | {@code errored}
     * | {@code archived}
     */
    @Column(name = "status", length = 32, nullable = false)
    private String status;

    @Lob
    @Column(name = "stop_reason_json")
    private String stopReasonJson;

    @Column(name = "created_at", nullable = false)
    private long createdAt;

    @Column(name = "updated_at", nullable = false)
    private long updatedAt;

    @Column(name = "archived_at")
    private Long archivedAt;

    /** 用于控制/数据平面并发状态更新的乐观锁。 */
    @Version
    @Column(name = "version")
    private Long version;

    public ManagedSessionEntity() {}

    public Long getRowId() {
        return rowId;
    }

    public void setRowId(Long rowId) {
        this.rowId = rowId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
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

    public String getAgentOwnerId() {
        return agentOwnerId;
    }

    public void setAgentOwnerId(String agentOwnerId) {
        this.agentOwnerId = agentOwnerId;
    }

    public Integer getAgentVersion() {
        return agentVersion;
    }

    public void setAgentVersion(Integer agentVersion) {
        this.agentVersion = agentVersion;
    }

    public String getAgentRefType() {
        return agentRefType;
    }

    public void setAgentRefType(String agentRefType) {
        this.agentRefType = agentRefType;
    }

    public String getAgentOverridesJson() {
        return agentOverridesJson;
    }

    public void setAgentOverridesJson(String agentOverridesJson) {
        this.agentOverridesJson = agentOverridesJson;
    }

    public String getEnvironmentId() {
        return environmentId;
    }

    public void setEnvironmentId(String environmentId) {
        this.environmentId = environmentId;
    }

    public String getExternalKey() {
        return externalKey;
    }

    public void setExternalKey(String externalKey) {
        this.externalKey = externalKey;
    }

    public String getMemoryStoreIdsJson() {
        return memoryStoreIdsJson;
    }

    public void setMemoryStoreIdsJson(String memoryStoreIdsJson) {
        this.memoryStoreIdsJson = memoryStoreIdsJson;
    }

    public String getVaultIdsJson() {
        return vaultIdsJson;
    }

    public void setVaultIdsJson(String vaultIdsJson) {
        this.vaultIdsJson = vaultIdsJson;
    }

    public String getResourcesJson() {
        return resourcesJson;
    }

    public void setResourcesJson(String resourcesJson) {
        this.resourcesJson = resourcesJson;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getStopReasonJson() {
        return stopReasonJson;
    }

    public void setStopReasonJson(String stopReasonJson) {
        this.stopReasonJson = stopReasonJson;
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

    public Long getArchivedAt() {
        return archivedAt;
    }

    public void setArchivedAt(Long archivedAt) {
        this.archivedAt = archivedAt;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}
