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
package io.agentscope.extensions.postgresql.state;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.ListHashUtil;
import io.agentscope.core.state.State;
import io.agentscope.core.state.VersionedState;
import io.agentscope.core.util.JsonUtils;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import javax.sql.DataSource;

/**
 * 基于 PostgreSQL 数据库的 {@link AgentStateStore} 实现。
 *
 * <p>在以下 PostgreSQL 表结构中保存会话状态：
 * <pre>
 * CREATE TABLE IF NOT EXISTS "agentscope"."agentscope_sessions" (
 *     session_id VARCHAR(255) NOT NULL,
 *     state_key  VARCHAR(255) NOT NULL,
 *     item_index INT          NOT NULL DEFAULT 0,
 *     state_data TEXT         NOT NULL,
 *     created_at TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
 *     updated_at TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
 *     PRIMARY KEY (session_id, state_key, item_index)
 * );
 * </pre>
 *
 * <ul>
 *   <li>单值状态：保存为 JSON，item_index = 0</li>
 *   <li>列表状态：每项单独一行，item_index = 0、1、2……</li>
 * </ul>
 */
public class PostgresAgentStateStore implements AgentStateStore {

    private static final String DEFAULT_SCHEMA_NAME = "agentscope";
    private static final String DEFAULT_TABLE_NAME = "agentscope_sessions";

    /**
     * 哈希存储键的后缀。
     */
    private static final String HASH_KEY_SUFFIX = ":_hash";

    /**
     * 单值状态的 item_index 值。
     */
    private static final int SINGLE_STATE_INDEX = 0;

    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");
    private static final int MAX_IDENTIFIER_LENGTH = 63;

    private final DataSource dataSource;
    private final String schemaName;
    private final String tableName;

    @FunctionalInterface
    private interface SqlOperation {
        void execute() throws Exception;
    }

    /**
     * 使用默认设置创建 PostgresAgentStateStore。
     *
     * <p>使用默认模式（{@code agentscope}）和表（{@code agentscope_sessions}），不自动创建模式或表。
     */
    public PostgresAgentStateStore(DataSource dataSource) {
        this(dataSource, DEFAULT_SCHEMA_NAME, DEFAULT_TABLE_NAME, false);
    }

    /**
     * 创建可选自动创建表结构的 PostgresAgentStateStore。
     *
     * <p>使用默认模式和表。{@code createIfNotExist} 为 true 时，自动创建缺失的模式与表。
     */
    public PostgresAgentStateStore(DataSource dataSource, boolean createIfNotExist) {
        this(dataSource, DEFAULT_SCHEMA_NAME, DEFAULT_TABLE_NAME, createIfNotExist);
    }

    /**
     * 使用自定义模式／表以及可选自动创建行为构造 PostgresAgentStateStore。
     */
    public PostgresAgentStateStore(
            DataSource dataSource, String schemaName, String tableName, boolean createIfNotExist) {
        if (dataSource == null) {
            throw new IllegalArgumentException("DataSource cannot be null");
        }

        this.dataSource = dataSource;
        this.schemaName =
                (schemaName == null || schemaName.trim().isEmpty())
                        ? DEFAULT_SCHEMA_NAME
                        : schemaName.trim();
        this.tableName =
                (tableName == null || tableName.trim().isEmpty())
                        ? DEFAULT_TABLE_NAME
                        : tableName.trim();

        validateIdentifier(this.schemaName, "Schema name");
        validateIdentifier(this.tableName, "Table name");

        if (createIfNotExist) {
            createSchemaIfNotExist();
            createTableIfNotExist();
        } else {
            verifySchemaExists();
            verifyTableExists();
        }
        ensureVersionColumn();
    }

    private void ensureVersionColumn() {
        // DEFAULT 1 而非 0：ALTER 会以默认值回填已有行，而 0 是
        // getVersioned() 表示“行不存在”的哨兵值。回填 0 会使
        // saveIfVersion(..., 0) 将旧行误判为不存在，从而进入 INSERT 分支
        // 并报告虚假的 CAS 冲突。两条写入路径都从版本 1 开始，
        // 因此迁移行应使用 1。
        String sql =
                "ALTER TABLE "
                        + getFullTableName()
                        + " ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 1";
        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.execute();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to ensure version column on table: " + tableName, e);
        }
    }

    private void createSchemaIfNotExist() {
        String sql = "CREATE SCHEMA IF NOT EXISTS \"" + schemaName + "\"";
        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.execute();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create schema: " + schemaName, e);
        }
    }

    private void createTableIfNotExist() {
        String createTableSql =
                """
                CREATE TABLE IF NOT EXISTS %s (
                    session_id VARCHAR(255) NOT NULL,
                    state_key  VARCHAR(255) NOT NULL,
                    item_index INT          NOT NULL DEFAULT 0,
                    state_data TEXT         NOT NULL,
                    version    BIGINT       NOT NULL DEFAULT 0,
                    created_at TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (session_id, state_key, item_index)
                )
                """
                        .formatted(getFullTableName());
        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(createTableSql)) {
            stmt.execute();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create session table: " + tableName, e);
        }
    }

    private void verifySchemaExists() {
        String sql = "SELECT SCHEMA_NAME FROM INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME = ?";
        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, schemaName);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException(
                            "Schema does not exist: "
                                    + schemaName
                                    + ". Use PostgresAgentStateStore(dataSource, true) or"
                                    + " builder(dataSource).createIfNotExist(true).build() to"
                                    + " auto-create.");
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to check schema existence: " + schemaName, e);
        }
    }

    private void verifyTableExists() {
        String sql =
                """
                SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES
                WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?
                """;
        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, schemaName);
            stmt.setString(2, tableName);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException(
                            "Table does not exist: "
                                    + schemaName
                                    + "."
                                    + tableName
                                    + ". Use PostgresAgentStateStore(dataSource, true) or"
                                    + " builder(dataSource).createIfNotExist(true).build() to"
                                    + " auto-create.");
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to check table existence: " + tableName, e);
        }
    }

    private String getFullTableName() {
        return "\"" + schemaName + "\".\"" + tableName + "\"";
    }

    private void executeInWriteTransaction(Connection conn, SqlOperation operation)
            throws Exception {
        boolean originalAutoCommit = conn.getAutoCommit();
        if (originalAutoCommit) {
            conn.setAutoCommit(false);
        }
        try {
            operation.execute();
            conn.commit();
        } catch (Exception e) {
            try {
                conn.rollback();
            } catch (SQLException rollbackException) {
                e.addSuppressed(rollbackException);
            }
            throw e;
        } finally {
            if (conn.getAutoCommit() != originalAutoCommit) {
                conn.setAutoCommit(originalAutoCommit);
            }
        }
    }

    @Override
    public boolean supportsVersioning() {
        return true;
    }

    @Override
    public void save(String userId, String sessionId, String key, State value) {
        String slotId = slotId(userId, sessionId);
        validateSessionId(slotId);
        validateStateKey(key);

        String upsertSql =
                """
                INSERT INTO %s (session_id, state_key, item_index, state_data, version, updated_at)
                VALUES (?, ?, ?, ?, 1, ?)
                ON CONFLICT (session_id, state_key, item_index) DO UPDATE SET
                    state_data = EXCLUDED.state_data,
                    version = %s.version + 1,
                    updated_at = EXCLUDED.updated_at
                """
                        .formatted(getFullTableName(), getFullTableName());
        Timestamp now = new Timestamp(System.currentTimeMillis());

        try (Connection conn = dataSource.getConnection()) {
            executeInWriteTransaction(
                    conn,
                    () -> {
                        try (PreparedStatement stmt = conn.prepareStatement(upsertSql)) {
                            String json = JsonUtils.getJsonCodec().toJson(value);
                            stmt.setString(1, slotId);
                            stmt.setString(2, key);
                            stmt.setInt(3, SINGLE_STATE_INDEX);
                            stmt.setString(4, json);
                            stmt.setTimestamp(5, now);
                            stmt.executeUpdate();
                        }
                    });
        } catch (Exception e) {
            throw new RuntimeException("Failed to save state: " + key, e);
        }
    }

    @Override
    public <T extends State> VersionedState<T> getVersioned(
            String userId, String sessionId, String key, Class<T> type) {
        String slotId = slotId(userId, sessionId);
        validateSessionId(slotId);
        validateStateKey(key);

        String sql =
                "SELECT state_data, version FROM "
                        + getFullTableName()
                        + " WHERE session_id = ? AND state_key = ? AND item_index = ?";
        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, slotId);
            stmt.setString(2, key);
            stmt.setInt(3, SINGLE_STATE_INDEX);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return new VersionedState<>(null, 0L);
                }
                String json = rs.getString("state_data");
                long version = rs.getLong("version");
                return new VersionedState<>(JsonUtils.getJsonCodec().fromJson(json, type), version);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to get versioned state: " + key, e);
        }
    }

    private long readVersion(String userId, String sessionId, String key) {
        String slotId = slotId(userId, sessionId);
        validateSessionId(slotId);
        validateStateKey(key);

        // 只读取 version 列，绝不反序列化 state_data。
        // 无法反序列化到 `State` 标记接口（没有可构造的具体类型），
        // 所以读取版本不得触碰载荷。
        String sql =
                "SELECT version FROM "
                        + getFullTableName()
                        + " WHERE session_id = ? AND state_key = ? AND item_index = ?";
        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, slotId);
            stmt.setString(2, key);
            stmt.setInt(3, SINGLE_STATE_INDEX);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getLong("version") : 0L;
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to read version: " + key, e);
        }
    }

    @Override
    public long saveIfVersion(
            String userId, String sessionId, String key, State value, long expectedVersion) {
        if (expectedVersion == UNVERSIONED) {
            save(userId, sessionId, key, value);
            return readVersion(userId, sessionId, key);
        }

        String slotId = slotId(userId, sessionId);
        validateSessionId(slotId);
        validateStateKey(key);

        try (Connection conn = dataSource.getConnection()) {
            long[] result = new long[1];
            executeInWriteTransaction(
                    conn,
                    () -> {
                        if (expectedVersion == 0L) {
                            result[0] = insertIfAbsent(conn, slotId, key, value);
                            if (result[0] == UNVERSIONED) {
                                // 行已经存在。如果存储版本仍为 0
                                // （例如旧 ALTER TABLE 迁移回填的值），
                                // 则满足 CAS，将 0 升到 1。如果并发写入者
                                // 已将其提升至大于 0，这里不会匹配任何行，
                                // 并正确报告 UNVERSIONED。
                                result[0] = updateIfVersion(conn, slotId, key, value, 0L);
                            }
                        } else {
                            result[0] = updateIfVersion(conn, slotId, key, value, expectedVersion);
                        }
                    });
            return result[0];
        } catch (Exception e) {
            throw new RuntimeException("Failed to save state if version: " + key, e);
        }
    }

    private long insertIfAbsent(Connection conn, String slotId, String key, State value)
            throws Exception {
        String insertSql =
                """
                INSERT INTO %s (session_id, state_key, item_index, state_data, version, updated_at)
                VALUES (?, ?, ?, ?, 1, ?)
                ON CONFLICT (session_id, state_key, item_index) DO NOTHING
                """
                        .formatted(getFullTableName());
        Timestamp now = new Timestamp(System.currentTimeMillis());
        try (PreparedStatement stmt = conn.prepareStatement(insertSql)) {
            String json = JsonUtils.getJsonCodec().toJson(value);
            stmt.setString(1, slotId);
            stmt.setString(2, key);
            stmt.setInt(3, SINGLE_STATE_INDEX);
            stmt.setString(4, json);
            stmt.setTimestamp(5, now);
            return stmt.executeUpdate() == 1 ? 1L : UNVERSIONED;
        }
    }

    private long updateIfVersion(
            Connection conn, String slotId, String key, State value, long expectedVersion)
            throws Exception {
        String updateSql =
                """
                UPDATE %s SET state_data = ?, version = ?, updated_at = ?
                WHERE session_id = ? AND state_key = ? AND item_index = ? AND version = ?
                """
                        .formatted(getFullTableName());
        Timestamp now = new Timestamp(System.currentTimeMillis());
        try (PreparedStatement stmt = conn.prepareStatement(updateSql)) {
            String json = JsonUtils.getJsonCodec().toJson(value);
            long newVersion = expectedVersion + 1L;
            stmt.setString(1, json);
            stmt.setLong(2, newVersion);
            stmt.setTimestamp(3, now);
            stmt.setString(4, slotId);
            stmt.setString(5, key);
            stmt.setInt(6, SINGLE_STATE_INDEX);
            stmt.setLong(7, expectedVersion);
            return stmt.executeUpdate() == 1 ? newVersion : UNVERSIONED;
        }
    }

    @Override
    public void save(String userId, String sessionId, String key, List<? extends State> values) {
        String slotId = slotId(userId, sessionId);
        validateSessionId(slotId);
        validateStateKey(key);

        if (values.isEmpty()) {
            return;
        }

        String hashKey = key + HASH_KEY_SUFFIX;

        try (Connection conn = dataSource.getConnection()) {
            executeInWriteTransaction(
                    conn,
                    () -> {
                        String currentHash = ListHashUtil.computeHash(values);
                        String storedHash = getStoredHash(conn, slotId, hashKey);
                        int existingCount = getListCount(conn, slotId, key);
                        boolean needsFullRewrite =
                                ListHashUtil.needsFullRewrite(values, storedHash, existingCount);

                        if (needsFullRewrite) {
                            deleteListItems(conn, slotId, key);
                            insertAllItems(conn, slotId, key, values);
                            saveHash(conn, slotId, hashKey, currentHash);
                        } else if (values.size() > existingCount) {
                            List<? extends State> newItems =
                                    values.subList(existingCount, values.size());
                            insertItems(conn, slotId, key, newItems, existingCount);
                            saveHash(conn, slotId, hashKey, currentHash);
                        }
                    });
        } catch (Exception e) {
            throw new RuntimeException("Failed to save list: " + key, e);
        }
    }

    private String getStoredHash(Connection conn, String sessionId, String hashKey)
            throws SQLException {
        String sql =
                "SELECT state_data FROM "
                        + getFullTableName()
                        + " WHERE session_id = ? AND state_key = ? AND item_index = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, sessionId);
            stmt.setString(2, hashKey);
            stmt.setInt(3, SINGLE_STATE_INDEX);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("state_data");
                }
                return null;
            }
        }
    }

    private void saveHash(Connection conn, String sessionId, String hashKey, String hash)
            throws SQLException {
        String upsertSql =
                """
                INSERT INTO %s (session_id, state_key, item_index, state_data, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (session_id, state_key, item_index) DO UPDATE SET
                    state_data = EXCLUDED.state_data,
                    updated_at = EXCLUDED.updated_at
                """
                        .formatted(getFullTableName());
        Timestamp now = new Timestamp(System.currentTimeMillis());
        try (PreparedStatement stmt = conn.prepareStatement(upsertSql)) {
            stmt.setString(1, sessionId);
            stmt.setString(2, hashKey);
            stmt.setInt(3, SINGLE_STATE_INDEX);
            stmt.setString(4, hash);
            stmt.setTimestamp(5, now);
            stmt.executeUpdate();
        }
    }

    private void deleteListItems(Connection conn, String sessionId, String key)
            throws SQLException {
        String sql =
                "DELETE FROM " + getFullTableName() + " WHERE session_id = ? AND state_key = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, sessionId);
            stmt.setString(2, key);
            stmt.executeUpdate();
        }
    }

    private void insertAllItems(
            Connection conn, String sessionId, String key, List<? extends State> values)
            throws Exception {
        insertItems(conn, sessionId, key, values, 0);
    }

    private void insertItems(
            Connection conn,
            String sessionId,
            String key,
            List<? extends State> items,
            int startIndex)
            throws Exception {
        String sql =
                "INSERT INTO "
                        + getFullTableName()
                        + " (session_id, state_key, item_index, state_data) VALUES (?, ?, ?, ?)";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            int index = startIndex;
            for (State item : items) {
                String json = JsonUtils.getJsonCodec().toJson(item);
                stmt.setString(1, sessionId);
                stmt.setString(2, key);
                stmt.setInt(3, index);
                stmt.setString(4, json);
                stmt.addBatch();
                index++;
            }
            stmt.executeBatch();
        }
    }

    private int getListCount(Connection conn, String sessionId, String key) throws SQLException {
        String sql =
                "SELECT MAX(item_index) as max_index FROM "
                        + getFullTableName()
                        + " WHERE session_id = ? AND state_key = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, sessionId);
            stmt.setString(2, key);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    int maxIndex = rs.getInt("max_index");
                    if (rs.wasNull()) {
                        return 0;
                    }
                    return maxIndex + 1;
                }
                return 0;
            }
        }
    }

    @Override
    public <T extends State> Optional<T> get(
            String userId, String sessionId, String key, Class<T> type) {
        String slotId = slotId(userId, sessionId);
        validateSessionId(slotId);
        validateStateKey(key);

        String sql =
                "SELECT state_data FROM "
                        + getFullTableName()
                        + " WHERE session_id = ? AND state_key = ? AND item_index = ?";
        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, slotId);
            stmt.setString(2, key);
            stmt.setInt(3, SINGLE_STATE_INDEX);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    String json = rs.getString("state_data");
                    return Optional.of(JsonUtils.getJsonCodec().fromJson(json, type));
                }
                return Optional.empty();
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to get state: " + key, e);
        }
    }

    @Override
    public <T extends State> List<T> getList(
            String userId, String sessionId, String key, Class<T> itemType) {
        String slotId = slotId(userId, sessionId);
        validateSessionId(slotId);
        validateStateKey(key);

        String sql =
                "SELECT state_data FROM "
                        + getFullTableName()
                        + " WHERE session_id = ? AND state_key = ? ORDER BY item_index";
        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, slotId);
            stmt.setString(2, key);
            try (ResultSet rs = stmt.executeQuery()) {
                List<T> result = new ArrayList<>();
                while (rs.next()) {
                    String json = rs.getString("state_data");
                    result.add(JsonUtils.getJsonCodec().fromJson(json, itemType));
                }
                return result;
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to get list: " + key, e);
        }
    }

    @Override
    public boolean exists(String userId, String sessionId) {
        String slotId = slotId(userId, sessionId);
        validateSessionId(slotId);

        String sql = "SELECT 1 FROM " + getFullTableName() + " WHERE session_id = ? LIMIT 1";
        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, slotId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to check session existence: " + slotId, e);
        }
    }

    @Override
    public void delete(String userId, String sessionId) {
        String slotId = slotId(userId, sessionId);
        validateSessionId(slotId);

        String sql = "DELETE FROM " + getFullTableName() + " WHERE session_id = ?";
        try (Connection conn = dataSource.getConnection()) {
            executeInWriteTransaction(
                    conn,
                    () -> {
                        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                            stmt.setString(1, slotId);
                            stmt.executeUpdate();
                        }
                    });
        } catch (Exception e) {
            throw new RuntimeException("Failed to delete session: " + slotId, e);
        }
    }

    @Override
    public Set<String> listSessionIds(String userId) {
        String userSegment = normalizeUser(userId);
        String prefix = userSegment + ":";
        String sql =
                "SELECT DISTINCT session_id FROM "
                        + getFullTableName()
                        + " WHERE session_id LIKE ? ORDER BY session_id";
        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, prefix + "%");
            try (ResultSet rs = stmt.executeQuery()) {
                Set<String> sessionIds = new HashSet<>();
                while (rs.next()) {
                    String slot = rs.getString("session_id");
                    sessionIds.add(slot.substring(prefix.length()));
                }
                return sessionIds;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to list sessions", e);
        }
    }

    private static final String ANON_USER = "__anon__";

    private static String normalizeUser(String userId) {
        return userId == null || userId.isBlank() ? ANON_USER : userId;
    }

    private static String slotId(String userId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        return normalizeUser(userId) + ":" + sessionId;
    }

    @Override
    public void close() {
        // DataSource 由外部管理，此处不关闭
    }

    /**
     * 清空数据库中的会话表（用于测试或清理）。
     *
     * <p>通过对会话表执行 {@code TRUNCATE TABLE} 清空全部会话记录。它不逐行扫描删除，而是重置表并立即回收空间，通常快于 DELETE。
     *
     * <p><strong>注意：</strong>当前实现直接执行语句，不经过 {@link #executeInWriteTransaction(Connection, SqlOperation)}。
     *
     * <p>【学习提示】上游旧注释将 PostgreSQL 与 MySQL 混淆：PostgreSQL 的 {@code TRUNCATE TABLE} 对表数据支持事务回滚，并非必然触发隐式提交。此方法的提交效果须结合连接 autoCommit 配置理解，不能由“DDL”直接推断。参见 <a href="https://www.postgresql.org/docs/current/sql-truncate.html">PostgreSQL TRUNCATE 官方说明</a>。
     *
     * @return 成功时通常为 0
     */
    public int truncateAllSessions() {
        String clearSql = "TRUNCATE TABLE " + getFullTableName();

        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(clearSql)) {
            return stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to truncate sessions", e);
        }
    }

    public String getSchemaName() {
        return schemaName;
    }

    public String getTableName() {
        return tableName;
    }

    public DataSource getDataSource() {
        return dataSource;
    }

    protected void validateSessionId(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            throw new IllegalArgumentException("AgentStateStore ID cannot be null or empty");
        }
        // 允许路径分隔符：槽位 ID 在这里只作为 PreparedStatement
        // 参数绑定，从不当作文件系统路径；SessionSandboxStateStore
        // 会合法生成斜杠分隔的槽位 ID（"sandbox/session/<id>"），拒绝这些值
        // 曾导致所有沙箱恢复状态被悄然丢弃（#3231）。
        if (sessionId.length() > 255) {
            throw new IllegalArgumentException("AgentStateStore ID cannot exceed 255 characters");
        }
    }

    private void validateStateKey(String key) {
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalArgumentException("State key cannot be null or empty");
        }
        if (key.length() > 255) {
            throw new IllegalArgumentException("State key cannot exceed 255 characters");
        }
    }

    private void validateIdentifier(String identifier, String identifierType) {
        if (identifier.length() > MAX_IDENTIFIER_LENGTH) {
            throw new IllegalArgumentException(
                    identifierType + " cannot exceed " + MAX_IDENTIFIER_LENGTH + " characters");
        }
        if (!IDENTIFIER_PATTERN.matcher(identifier).matches()) {
            throw new IllegalArgumentException(
                    identifierType
                            + " contains invalid characters. Only alphanumeric characters and"
                            + " underscores are allowed, and it must start with a letter or"
                            + " underscore. Invalid value: "
                            + identifier);
        }
    }

    /**
     * {@link PostgresAgentStateStore} 的构建器。
     */
    public static final class Builder {

        private final DataSource dataSource;
        private String schemaName = DEFAULT_SCHEMA_NAME;
        private String tableName = DEFAULT_TABLE_NAME;
        private boolean createIfNotExist = true;

        private Builder(DataSource dataSource) {
            if (dataSource == null) {
                throw new IllegalArgumentException("DataSource cannot be null");
            }
            this.dataSource = dataSource;
        }

        public Builder schemaName(String schemaName) {
            this.schemaName = schemaName;
            return this;
        }

        public Builder tableName(String tableName) {
            this.tableName = tableName;
            return this;
        }

        public Builder createIfNotExist(boolean createIfNotExist) {
            this.createIfNotExist = createIfNotExist;
            return this;
        }

        public PostgresAgentStateStore build() {
            return new PostgresAgentStateStore(dataSource, schemaName, tableName, createIfNotExist);
        }
    }

    public static Builder builder(DataSource dataSource) {
        return new Builder(dataSource);
    }
}
