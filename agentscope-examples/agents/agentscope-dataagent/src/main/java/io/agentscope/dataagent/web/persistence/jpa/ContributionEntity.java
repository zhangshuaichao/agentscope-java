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

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

/** 用户提交的技能/子智能体/记忆/AGENTS.md/知识资产贡献提案，经管理员批准后提升到共享工作区。<p>生命周期：{@code PENDING} → {@code APPROVED}（载荷写入 {@code ${dataagentHome}/shared/agents/<targetAgentId>/<targetType>/<targetPath>}）或 {@code REJECTED}（附理由关闭）。<p>{@code payload} 列保存 {@code FileEntry { relPath, content }} JSON 数组。单文件贡献一项，多文件技能包每文件一项。原始 {@code payload} 原样保留以供审计；可空的 {@code approvedPayload} 保存管理员编辑后用于落盘的副本。<p>{@code targetAgentId} 将贡献限定到单个智能体维度，对应 Harness 的 {@code IsolationScope.AGENT}（AGENT 命名空间为 {@code ["agents", <agentId>, "shared"]}）。不同智能体不共用各自共享层。 */
@Entity
@Table(
        name = "dataagent_contribution",
        indexes = {
            @Index(name = "ix_dataagent_contribution_status", columnList = "status"),
            @Index(name = "ix_dataagent_contribution_source_user", columnList = "source_user_id"),
            @Index(
                    name = "ix_dataagent_contribution_target",
                    columnList = "target_agent_id, target_type, target_path")
        })
public class ContributionEntity {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";

    public static final String TARGET_SKILL = "skill";
    public static final String TARGET_SUBAGENT = "subagent";
    public static final String TARGET_MEMORY = "memory";
    public static final String TARGET_AGENTS_MD = "agents_md";
    public static final String TARGET_KNOWLEDGE = "knowledge";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "status", length = 16, nullable = false)
    private String status = STATUS_PENDING;

    /** 贡献者用户 ID。 */
    @Column(name = "source_user_id", length = 128, nullable = false)
    private String sourceUserId;

    /** 贡献者智能体 ID（提取贡献内容的工作区）。 */
    @Column(name = "source_agent_id", length = 128)
    private String sourceAgentId;

    /** 贡献要提升到的目标智能体 ID。提交时省略则默认为 {@code sourceAgentId}；若要初始化其他智能体的共享层，须显式指定。审批写入 {@code shared/agents/<targetAgentId>/}。 */
    @Column(name = "target_agent_id", length = 128, nullable = false)
    private String targetAgentId;

    /** {@link #TARGET_SKILL}、{@link #TARGET_SUBAGENT}、{@link #TARGET_MEMORY}、{@link #TARGET_AGENTS_MD}、{@link #TARGET_KNOWLEDGE} 之一。 */
    @Column(name = "target_type", length = 32, nullable = false)
    private String targetType;

    /** 载荷在 {@code shared/agents/<targetAgentId>/<targetType>/} 下落地的工作区相对路径。技能使用目录名（例如 {@code "cohort-builder"}），子智能体使用文件名（例如 {@code "report-writer.md"}），记忆使用片段文件名；{@code agents_md} 固定为 {@code "AGENTS.md"}；{@code knowledge} 使用 {@code knowledge/} 下的相对路径。 */
    @Column(name = "target_path", length = 512, nullable = false)
    private String targetPath;

    @Column(name = "rationale", length = 1024)
    private String rationale;

    /**
     * 提案提交时载荷的原样快照，以 {@code
     * FileEntry[]} 进行 JSON 序列化；审批后仍保留以供审计。
     */
    @Lob
    @Column(name = "payload", nullable = false)
    private String payload;

    /** 管理员编辑后的载荷（同样是 JSON 序列化 {@code FileEntry[]}）。设置后，审批使用此值而非 {@link #payload} 写入。管理员原样接受时为 null。 */
    @Lob
    @Column(name = "approved_payload")
    private String approvedPayload;

    @Column(name = "reviewer_user_id", length = 128)
    private String reviewerUserId;

    @Column(name = "reviewer_note", length = 1024)
    private String reviewerNote;

    @Column(name = "created_at", nullable = false)
    private long createdAt;

    @Column(name = "updated_at", nullable = false)
    private long updatedAt;

    public ContributionEntity() {}

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getSourceUserId() {
        return sourceUserId;
    }

    public void setSourceUserId(String sourceUserId) {
        this.sourceUserId = sourceUserId;
    }

    public String getSourceAgentId() {
        return sourceAgentId;
    }

    public void setSourceAgentId(String sourceAgentId) {
        this.sourceAgentId = sourceAgentId;
    }

    public String getTargetAgentId() {
        return targetAgentId;
    }

    public void setTargetAgentId(String targetAgentId) {
        this.targetAgentId = targetAgentId;
    }

    public String getTargetType() {
        return targetType;
    }

    public void setTargetType(String targetType) {
        this.targetType = targetType;
    }

    public String getTargetPath() {
        return targetPath;
    }

    public void setTargetPath(String targetPath) {
        this.targetPath = targetPath;
    }

    public String getRationale() {
        return rationale;
    }

    public void setRationale(String rationale) {
        this.rationale = rationale;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public String getApprovedPayload() {
        return approvedPayload;
    }

    public void setApprovedPayload(String approvedPayload) {
        this.approvedPayload = approvedPayload;
    }

    public String getReviewerUserId() {
        return reviewerUserId;
    }

    public void setReviewerUserId(String reviewerUserId) {
        this.reviewerUserId = reviewerUserId;
    }

    public String getReviewerNote() {
        return reviewerNote;
    }

    public void setReviewerNote(String reviewerNote) {
        this.reviewerNote = reviewerNote;
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
}
