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
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/**
 * 单个用户拥有的技能市场的持久化表示。
 * <p>不同于 claw 在 {@code agentscope.json} 中的 {@code marketplaces} 配置块，
 * Builder 每个市场只属于一个用户，对其他用户不可见。{@code (user_id,
 * marketplace_id)} 组合唯一：同一用户不能创建同 ID 市场，不同用户则可复用 ID 而不冲突。
 */
@Entity
@Table(
        name = "dataagent_user_marketplace",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_user_marketplace",
                        columnNames = {"user_id", "marketplace_id"}),
        indexes = {@Index(name = "ix_user_marketplace_user", columnList = "user_id")})
public class UserMarketplaceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "user_id", length = 128, nullable = false)
    private String userId;

    @Column(name = "marketplace_id", length = 128, nullable = false)
    private String marketplaceId;

    @Column(name = "type", length = 32, nullable = false)
    private String type;

    /** 序列化为 JSON 的提供方属性。保存原始 JSON 以保持 MySQL/PostgreSQL/H2 模式中立，因为 Hibernate 的 JSON 列类型因方言而异。 */
    @Lob
    @Column(name = "properties_json")
    private String propertiesJson;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    private Instant updatedAt = Instant.now();

    public UserMarketplaceEntity() {}

    public UserMarketplaceEntity(
            String userId, String marketplaceId, String type, String propertiesJson) {
        this.userId = userId;
        this.marketplaceId = marketplaceId;
        this.type = type;
        this.propertiesJson = propertiesJson;
    }

    public Long getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getMarketplaceId() {
        return marketplaceId;
    }

    public void setMarketplaceId(String marketplaceId) {
        this.marketplaceId = marketplaceId;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getPropertiesJson() {
        return propertiesJson;
    }

    public void setPropertiesJson(String propertiesJson) {
        this.propertiesJson = propertiesJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
