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
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** 非智能体托管资源（环境 / 记忆存储 / 密钥库）上的单条共享授权。沿用 {@code io.agentscope.builder.web.share.AgentShareGrant} 的智能体共享模型，但以通用 {@code (resourceType, resourceId)} 为键，无需为每种资源单独建表。 */
@Entity
@Table(
        name = "builder_resource_share",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "ux_builder_resource_share_grant",
                        columnNames = {
                            "resource_type",
                            "resource_id",
                            "grantee_type",
                            "grantee_id"
                        }),
        indexes = {
            @Index(
                    name = "ix_builder_resource_share_resource",
                    columnList = "resource_type, resource_id")
        })
public class ResourceShareEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "row_id")
    private Long rowId;

    @Column(name = "share_id", length = 64, nullable = false, unique = true)
    private String shareId;

    /** 例如 {@code environment}、{@code memory_store}、{@code vault} */
    @Column(name = "resource_type", length = 32, nullable = false)
    private String resourceType;

    @Column(name = "resource_id", length = 64, nullable = false)
    private String resourceId;

    @Column(name = "owner_id", length = 128, nullable = false)
    private String ownerId;

    /** {@code USER} 或 {@code WORKSPACE}，参见 {@code AgentShareGrant}。 */
    @Column(name = "grantee_type", length = 16, nullable = false)
    private String granteeType;

    @Column(name = "grantee_id", length = 128, nullable = false)
    private String granteeId;

    /** {@code CLONE}、{@code RUN} 或 {@code EDIT}。 */
    @Column(name = "tier", length = 16, nullable = false)
    private String tier;

    @Column(name = "created_at", nullable = false)
    private long createdAt;

    @Column(name = "created_by", length = 128)
    private String createdBy;

    public ResourceShareEntity() {}

    public Long getRowId() {
        return rowId;
    }

    public void setRowId(Long rowId) {
        this.rowId = rowId;
    }

    public String getShareId() {
        return shareId;
    }

    public void setShareId(String shareId) {
        this.shareId = shareId;
    }

    public String getResourceType() {
        return resourceType;
    }

    public void setResourceType(String resourceType) {
        this.resourceType = resourceType;
    }

    public String getResourceId() {
        return resourceId;
    }

    public void setResourceId(String resourceId) {
        this.resourceId = resourceId;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
    }

    public String getGranteeType() {
        return granteeType;
    }

    public void setGranteeType(String granteeType) {
        this.granteeType = granteeType;
    }

    public String getGranteeId() {
        return granteeId;
    }

    public void setGranteeId(String granteeId) {
        this.granteeId = granteeId;
    }

    public String getTier() {
        return tier;
    }

    public void setTier(String tier) {
        this.tier = tier;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }
}
