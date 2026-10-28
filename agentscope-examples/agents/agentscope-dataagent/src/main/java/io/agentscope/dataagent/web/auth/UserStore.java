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
package io.agentscope.dataagent.web.auth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Optional;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 用户注册表抽象。
 * <p>随附的唯一实现为
 * {@link io.agentscope.dataagent.web.persistence.jpa.JpaUserStore}，使用 Spring Data JPA 持久化。
 * 默认 DataSource 为文件模式的嵌入式 H2，位于
 * {@code ${user.home}/.agentscope-builder/db}；启用 {@code jdbc} Spring 配置环境
 * （或设置 {@code BUILDER_DB_URL}）可切换至 MySQL 或 PostgreSQL。两种 JDBC 驱动均以运行时依赖随附。
 * <p>实现应保证线程安全，并在首次启动且存储为空时初始化默认 {@code admin} 用户。
 */
public interface UserStore {

    /** 返回用于密码哈希与验证的编码器。 */
    PasswordEncoder passwordEncoder();

    /** 按稳定标识符查找用户。 */
    Optional<UserRecord> findById(String userId);

    /** 按用户名查找用户（忽略大小写）。 */
    Optional<UserRecord> findByUsername(String username);

    /** 返回所有注册用户，结果列表为不可变快照。 */
    List<UserRecord> listAll();

    /** 创建用户。如果 userId 或用户名已被占用，则抛出 {@link IllegalArgumentException}。 */
    UserRecord createUser(String userId, String username, String password, List<String> roles);

    /** 使用已存储 {@link UserRecord} 的哈希验证明文密码。 */
    boolean verifyPassword(UserRecord user, String rawPassword);

    /** 更新已有用户的密码。返回更新后的记录，用户不存在时返回空。 */
    Optional<UserRecord> updatePassword(String userId, String newPassword);

    /** 更新已有用户的角色。返回更新后的记录，用户不存在时返回空。 */
    Optional<UserRecord> updateRoles(String userId, List<String> newRoles);

    /** 按 userId 删除用户。用户存在且已移除时返回 {@code true}。不能删除最后一个管理员（{@link IllegalStateException}）。 */
    boolean deleteUser(String userId);

    /**
     * 可进行 JSON 序列化的用户记录。
     * @param userId 稳定唯一标识符（用作 HarnessAgent 命名空间键）
     * @param username 显示名称
     * @param passwordHash BCrypt 哈希
     * @param roles 角色字符串列表（例如 {@code ["user"]}、{@code ["user","admin"]}）
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record UserRecord(String userId, String username, String passwordHash, List<String> roles) {
        public boolean hasRole(String role) {
            return roles != null && roles.contains(role);
        }
    }
}
