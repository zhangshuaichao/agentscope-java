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
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;

/** Builder 用户的持久化表示，以稳定 {@code userId} 为键（与文件模式中 HarnessAgent 磁盘命名空间键相同），从 JSON 存储迁移时可保留全部引用。<p>角色保存为逗号分隔字符串，无需关联表即可兼容 MySQL、PostgreSQL 和 H2。{@code username} 列唯一且带索引，支持快速 {@code findByUsername} 查询。 */
@Entity
@Table(
        name = "dataagent_user",
        indexes = {
            @Index(name = "ix_dataagent_user_username", columnList = "username", unique = true)
        })
public class UserEntity {

    @Id
    @Column(name = "user_id", length = 128, nullable = false)
    private String userId;

    @Column(name = "username", length = 191, nullable = false)
    private String username;

    @Column(name = "password_hash", length = 200, nullable = false)
    private String passwordHash;

    /** 逗号分隔的角色列表。API 边界将 {@code null} 或空值视为 {@code "user"}。 */
    @Column(name = "roles_csv", length = 500)
    private String rolesCsv;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    @Lob
    @Column(name = "bindings_json")
    private String bindingsJson;

    public UserEntity() {}

    public UserEntity(String userId, String username, String passwordHash, String rolesCsv) {
        this.userId = userId;
        this.username = username;
        this.passwordHash = passwordHash;
        this.rolesCsv = rolesCsv;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getRolesCsv() {
        return rolesCsv;
    }

    public void setRolesCsv(String rolesCsv) {
        this.rolesCsv = rolesCsv;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public String getBindingsJson() {
        return bindingsJson;
    }

    public void setBindingsJson(String bindingsJson) {
        this.bindingsJson = bindingsJson;
    }
}
