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
package io.agentscope.extensions.mysql.state;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.ListHashUtil;
import io.agentscope.core.state.State;
import io.agentscope.core.state.VersionedState;
import io.agentscope.core.util.JsonUtils;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import javax.sql.DataSource;

/**
 * 基于 MySQL 数据库的会话实现。
 *
 * <p>在 MySQL 表中按以下结构保存会话状态：
 * <ul>
 *   <li>单值状态：保存为 JSON，item_index = 0
 *   <li>列表状态：每项单独一行，item_index = 0、1、2……
 * </ul>
 *
 * <p>表结构（createIfNotExist=true 时自动创建）：
 * <pre>
 * CREATE TABLE IF NOT EXISTS agentscope_sessions (
 *     session_id VARCHAR(255) COLLATE utf8mb4_bin NOT NULL,
 *     state_key VARCHAR(255) COLLATE utf8mb4_bin NOT NULL,
 *     item_index INT NOT NULL DEFAULT 0,
 *     state_data LONGTEXT NOT NULL,
 *     created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
 *     updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 *     PRIMARY KEY (session_id, state_key, item_index)
 * ) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
 * </pre>
 *
 * <p>只有两个键列固定使用 {@code utf8mb4_bin}：{@code session_id} 和 {@code state_key} 是精确标识符，
 * 表默认排序规则（{@code utf8mb4_unicode_ci}）不区分大小写；若不使用二进制排序规则，仅大小写不同的两个 ID 会发生主键冲突并共享一行。载荷列保持表默认规则。
 *
 * <p>功能：
 * <ul>
 *   <li>真正增量存储列表，仅 INSERT 新项，不做读取—修改—写回</li>
 *   <li>使用 Jackson 进行类型安全的状态序列化</li>
 *   <li>自动创建表</li>
 *   <li>通过参数化查询防止 SQL 注入</li>
 * </ul>
 */
public class MysqlAgentStateStore implements AgentStateStore {

    private static final String DEFAULT_DATABASE_NAME = "agentscope";
    private static final String DEFAULT_TABLE_NAME = "agentscope_sessions";

    /**
 * 哈希存储键的后缀。
 */
    private static final String HASH_KEY_SUFFIX = ":_hash";

    /**
 * 单值状态的 item_index 值。
 */
    private static final int SINGLE_STATE_INDEX = 0;

    /**
     * 用于验证数据库和表名的模式。仅允许字母、数字、下划线和连字符，且必须以字母或下划线开头，防止通过恶意数据库／表名进行 SQL 注入。
     *
     * <p>注意：包含连字符的标识符在 SQL 查询中必须用反引号转义。
     */
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_-]*$");

    private static final int MAX_IDENTIFIER_LENGTH = 64; // MySQL 标识符长度限制

    private final DataSource dataSource;
    private final String databaseName;
    private final String tableName;

    @FunctionalInterface
    private interface SqlOperation {
        void execute() throws Exception;
    }

    /**
     * 使用默认设置创建 MysqlAgentStateStore。
     *
     * <p>使用默认数据库名（{@code agentscope}）和表名（{@code agentscope_sessions}），不自动创建数据库或表。不存在时抛出 {@link IllegalStateException}。
     *
     * @param dataSource 用于数据库连接的数据源
     * @throws IllegalArgumentException dataSource 为 null 时抛出
     * @throws IllegalStateException 数据库或表不存在时抛出
     */
    public MysqlAgentStateStore(DataSource dataSource) {
        this(dataSource, DEFAULT_DATABASE_NAME, DEFAULT_TABLE_NAME, false);
    }

    /**
     * 创建可选自动建库建表的 MysqlAgentStateStore。
     *
     * <p>使用默认数据库名（{@code agentscope}）和表名（{@code agentscope_sessions}）。{@code createIfNotExist} 为 true 时自动创建缺失的数据库和表；为 false 且不存在时抛出 {@link IllegalStateException}。
     *
     * @param dataSource 用于数据库连接的数据源
     * @param createIfNotExist true 自动建库建表；false 要求已存在
     * @throws IllegalArgumentException dataSource 为 null 时抛出
     * @throws IllegalStateException createIfNotExist 为 false 且数据库／表不存在时抛出
     */
    public MysqlAgentStateStore(DataSource dataSource, boolean createIfNotExist) {
        this(dataSource, DEFAULT_DATABASE_NAME, DEFAULT_TABLE_NAME, createIfNotExist);
    }

    /**
     * 使用自定义数据库名、表名和可选自动创建行为构造 MysqlAgentStateStore。
     *
     * <p>{@code createIfNotExist} 为 true 时自动创建缺失的数据库和表；为 false 且不存在时抛出 {@link IllegalStateException}。
     *
     * @param dataSource 用于数据库连接的数据源
     * @param databaseName 自定义数据库名，null 或空时使用默认值
     * @param tableName 自定义表名，null 或空时使用默认值
     * @param createIfNotExist true 自动建库建表；false 要求已存在
     * @throws IllegalArgumentException dataSource 为 null 时抛出
     * @throws IllegalStateException createIfNotExist 为 false 且数据库／表不存在时抛出
     */
    public MysqlAgentStateStore(
            DataSource dataSource,
            String databaseName,
            String tableName,
            boolean createIfNotExist) {
        if (dataSource == null) {
            throw new IllegalArgumentException("DataSource cannot be null");
        }

        this.dataSource = dataSource;
        this.databaseName =
                (databaseName == null || databaseName.trim().isEmpty())
                        ? DEFAULT_DATABASE_NAME
                        : databaseName.trim();
        this.tableName =
                (tableName == null || tableName.trim().isEmpty())
                        ? DEFAULT_TABLE_NAME
                        : tableName.trim();

        // 验证数据库和表名，防止 SQL 注入
        validateIdentifier(this.databaseName, "Database name");
        validateIdentifier(this.tableName, "Table name");

        if (createIfNotExist) {
            // 不存在时创建数据库和表
            createDatabaseIfNotExist();
            createTableIfNotExist();
        } else {
            // 验证数据库和表存在
            verifyDatabaseExists();
            verifyTableExists();
        }
        ensureVersionColumn();
    }

    private void ensureVersionColumn() {
        String checkSql =
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS"
                        + " WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? AND COLUMN_NAME = 'version'";
        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(checkSql)) {
            stmt.setString(1, databaseName);
            stmt.setString(2, tableName);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next() && rs.getInt(1) == 0) {
                    // DEFAULT 1 而非 0：ALTER 会以默认值回填已有行，
                    // 而 0 是 getVersioned() 表示“行不存在”的哨兵值。
                    // 回填 0 会让 saveIfVersion(..., 0) 将所有旧行误判为不存在，
                    // 从而进入 INSERT 分支并触发
                    // 重复键，形成虚假的 CAS 冲突。两条写入路径都从
                    // 版本 1 开始，因此迁移行应使用 1。
                    String alterSql =
                            "ALTER TABLE "
                                    + getFullTableName()
                                    + " ADD COLUMN version BIGINT NOT NULL DEFAULT 1";
                    try (PreparedStatement alter = conn.prepareStatement(alterSql)) {
                        alter.execute();
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to ensure version column on table: " + tableName, e);
        }
    }

    /**
     * 数据库不存在时创建。
     *
     * <p>使用 UTF-8（utf8mb4）字符集和 Unicode 排序规则，支持国际化。数据库名用反引号转义，以安全处理连字符等特殊字符。
     */
    private void createDatabaseIfNotExist() {
        String createDatabaseSql =
                "CREATE DATABASE IF NOT EXISTS `"
                        + databaseName
                        + "` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci";

        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(createDatabaseSql)) {
            stmt.execute();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create database: " + databaseName, e);
        }
    }

    /**
     * 会话表不存在时创建。
     *
     * <p>表名用反引号转义，以安全处理连字符等特殊字符。
     */
    private void createTableIfNotExist() {
        // session_id 和 state_key 是区分大小写的标识符，因此固定使用二进制排序规则：
        // 表默认 utf8mb4_unicode_ci 不区分大小写，会让仅大小写不同的会话 ID
        // 或状态键共享同一行。utf8mb4_bin 使用 PAD SPACE，
        // 因此仅尾随空格不同的值仍视为相等。载荷列保持默认规则。
        String createTableSql =
                "CREATE TABLE IF NOT EXISTS "
                        + getFullTableName()
                        + " (session_id VARCHAR(255) COLLATE utf8mb4_bin NOT NULL,"
                        + " state_key VARCHAR(255) COLLATE utf8mb4_bin NOT NULL,"
                        + " item_index INT NOT NULL DEFAULT 0, state_data LONGTEXT NOT NULL,"
                        + " version BIGINT NOT NULL DEFAULT 0, created_at DATETIME DEFAULT"
                        + " CURRENT_TIMESTAMP, updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON"
                        + " UPDATE CURRENT_TIMESTAMP, PRIMARY KEY (session_id, state_key,"
                        + " item_index)) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci";

        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(createTableSql)) {
            stmt.execute();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create session table: " + tableName, e);
        }
    }

    /**
     * 验证数据库存在。
     *
     * @throws IllegalStateException 数据库不存在时抛出
     */
    private void verifyDatabaseExists() {
        String checkSql =
                "SELECT SCHEMA_NAME FROM INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME = ?";

        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(checkSql)) {
            stmt.setString(1, databaseName);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException(
                            "Database does not exist: "
                                    + databaseName
                                    + ". Use MysqlAgentStateStore(dataSource, true) to"
                                    + " auto-create.");
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to check database existence: " + databaseName, e);
        }
    }

    /**
     * 验证会话表存在。
     *
     * @throws IllegalStateException 表不存在时抛出
     */
    private void verifyTableExists() {
        String checkSql =
                "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES "
                        + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?";

        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(checkSql)) {
            stmt.setString(1, databaseName);
            stmt.setString(2, tableName);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException(
                            "Table does not exist: "
                                    + databaseName
                                    + "."
                                    + tableName
                                    + ". Use MysqlAgentStateStore(dataSource, true) to"
                                    + " auto-create.");
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to check table existence: " + tableName, e);
        }
    }

    /**
     * 获取带数据库前缀并以反引号正确转义的完整表名。
     *
     * <p>使用反引号转义可能包含连字符等特殊字符的标识符；MySQL 要求对标准字符集以外的标识符这样处理。
     *
     * @return 以反引号转义的完整表名（`database`.`table`）
     */
    private String getFullTableName() {
        return "`" + databaseName + "`.`" + tableName + "`";
    }

    /**
     * 在显式事务中执行写操作。
     *
     * <p>MysqlAgentStateStore 每次调用写方法都获取并拥有新的 JDBC 连接。即使底层 DataSource 默认 {@code autoCommit=false}，此辅助方法也使写入语义一致，并在连接归还池前恢复原自动提交模式。
     */
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
                "INSERT INTO "
                        + getFullTableName()
                        + " (session_id, state_key, item_index, state_data, version)"
                        + " VALUES (?, ?, ?, ?, 1)"
                        + " ON DUPLICATE KEY UPDATE state_data = VALUES(state_data),"
                        + " version = version + 1";

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

        String selectSql =
                "SELECT state_data, version FROM "
                        + getFullTableName()
                        + " WHERE session_id = ? AND state_key = ? AND item_index = ?";

        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(selectSql)) {

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
        String selectSql =
                "SELECT version FROM "
                        + getFullTableName()
                        + " WHERE session_id = ? AND state_key = ? AND item_index = ?";
        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(selectSql)) {
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
                "INSERT INTO "
                        + getFullTableName()
                        + " (session_id, state_key, item_index, state_data, version)"
                        + " VALUES (?, ?, ?, ?, 1)";

        try (PreparedStatement stmt = conn.prepareStatement(insertSql)) {
            String json = JsonUtils.getJsonCodec().toJson(value);
            stmt.setString(1, slotId);
            stmt.setString(2, key);
            stmt.setInt(3, SINGLE_STATE_INDEX);
            stmt.setString(4, json);
            try {
                return stmt.executeUpdate() == 1 ? 1L : UNVERSIONED;
            } catch (SQLException e) {
                if (e.getErrorCode() == 1062 || "23000".equals(e.getSQLState())) {
                    return UNVERSIONED;
                }
                throw e;
            }
        }
    }

    private long updateIfVersion(
            Connection conn, String slotId, String key, State value, long expectedVersion)
            throws Exception {
        String updateSql =
                "UPDATE "
                        + getFullTableName()
                        + " SET state_data = ?, version = ? WHERE session_id = ? AND state_key = ?"
                        + " AND item_index = ? AND version = ?";

        try (PreparedStatement stmt = conn.prepareStatement(updateSql)) {
            String json = JsonUtils.getJsonCodec().toJson(value);
            long newVersion = expectedVersion + 1L;
            stmt.setString(1, json);
            stmt.setLong(2, newVersion);
            stmt.setString(3, slotId);
            stmt.setString(4, key);
            stmt.setInt(5, SINGLE_STATE_INDEX);
            stmt.setLong(6, expectedVersion);
            return stmt.executeUpdate() == 1 ? newVersion : UNVERSIONED;
        }
    }

    /**
     * 使用基于哈希的变更检测保存状态值列表。
     *
     * <p>同时处理仅追加与可变列表：
     * <ul>
     *   <li>哈希变化（列表已修改）时，删除所有旧项并重写
     *   <li>列表缩短时，删除所有旧项并重写
     *   <li>列表仅增长（追加）时，只插入新项
     *   <li>无变化时跳过操作
     * </ul>
     *
     * @param userId 用户标识符
     * @param sessionId 会话标识符
     * @param key 状态键（例如 "memory_messages"）
     * @param values 要保存的状态值列表
     */
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

    /**
     * 获取列表已存储的哈希值。
     *
     * @param conn 数据库连接
     * @param sessionId 会话标识符
     * @param hashKey 哈希键（例如 "memory_messages:_hash"）
     * @return 已存储的哈希，未找到时为 null
     */
    private String getStoredHash(Connection conn, String sessionId, String hashKey)
            throws SQLException {
        String selectSql =
                "SELECT state_data FROM "
                        + getFullTableName()
                        + " WHERE session_id = ? AND state_key = ? AND item_index = ?";

        try (PreparedStatement stmt = conn.prepareStatement(selectSql)) {
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

    /**
     * 保存列表哈希值。
     *
     * @param conn 数据库连接
     * @param sessionId 会话标识符
     * @param hashKey 哈希键
     * @param hash 要保存的哈希值
     */
    private void saveHash(Connection conn, String sessionId, String hashKey, String hash)
            throws SQLException {
        String upsertSql =
                "INSERT INTO "
                        + getFullTableName()
                        + " (session_id, state_key, item_index, state_data)"
                        + " VALUES (?, ?, ?, ?)"
                        + " ON DUPLICATE KEY UPDATE state_data = VALUES(state_data)";

        try (PreparedStatement stmt = conn.prepareStatement(upsertSql)) {
            stmt.setString(1, sessionId);
            stmt.setString(2, hashKey);
            stmt.setInt(3, SINGLE_STATE_INDEX);
            stmt.setString(4, hash);
            stmt.executeUpdate();
        }
    }

    /**
     * 删除列表状态的所有项。
     *
     * @param conn 数据库连接
     * @param sessionId 会话标识符
     * @param key 状态键
     */
    private void deleteListItems(Connection conn, String sessionId, String key)
            throws SQLException {
        String deleteSql =
                "DELETE FROM " + getFullTableName() + " WHERE session_id = ? AND state_key = ?";

        try (PreparedStatement stmt = conn.prepareStatement(deleteSql)) {
            stmt.setString(1, sessionId);
            stmt.setString(2, key);
            stmt.executeUpdate();
        }
    }

    /**
     * 插入列表状态的全部项。
     *
     * @param conn 数据库连接
     * @param sessionId 会话标识符
     * @param key 状态键
     * @param values 要插入的值
     */
    private void insertAllItems(
            Connection conn, String sessionId, String key, List<? extends State> values)
            throws Exception {
        insertItems(conn, sessionId, key, values, 0);
    }

    /**
     * 从给定索引开始插入列表状态项。
     *
     * @param conn 数据库连接
     * @param sessionId 会话标识符
     * @param key 状态键
     * @param items 要插入的项
     * @param startIndex item_index 起始索引
     */
    private void insertItems(
            Connection conn,
            String sessionId,
            String key,
            List<? extends State> items,
            int startIndex)
            throws Exception {
        String insertSql =
                "INSERT INTO "
                        + getFullTableName()
                        + " (session_id, state_key, item_index, state_data)"
                        + " VALUES (?, ?, ?, ?)";

        try (PreparedStatement stmt = conn.prepareStatement(insertSql)) {
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

    /**
     * 获取列表状态项数（最大索引加 1）。
     */
    private int getListCount(Connection conn, String sessionId, String key) throws SQLException {
        String selectSql =
                "SELECT MAX(item_index) as max_index FROM "
                        + getFullTableName()
                        + " WHERE session_id = ? AND state_key = ?";

        try (PreparedStatement stmt = conn.prepareStatement(selectSql)) {
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

        String selectSql =
                "SELECT state_data FROM "
                        + getFullTableName()
                        + " WHERE session_id = ? AND state_key = ? AND item_index = ?";

        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(selectSql)) {

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

        String selectSql =
                "SELECT state_data FROM "
                        + getFullTableName()
                        + " WHERE session_id = ? AND state_key = ?"
                        + " ORDER BY item_index";

        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(selectSql)) {

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

        String existsSql = "SELECT 1 FROM " + getFullTableName() + " WHERE session_id = ? LIMIT 1";

        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(existsSql)) {

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

        String deleteSql = "DELETE FROM " + getFullTableName() + " WHERE session_id = ?";

        try (Connection conn = dataSource.getConnection()) {
            executeInWriteTransaction(
                    conn,
                    () -> {
                        try (PreparedStatement stmt = conn.prepareStatement(deleteSql)) {
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
        String listSql =
                "SELECT DISTINCT session_id FROM "
                        + getFullTableName()
                        + " WHERE session_id LIKE ? ORDER BY session_id";

        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(listSql)) {

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

    /**
     * 将 {@code (userId, sessionId)} 组合为单个 {@code session_id} 列值。使用 {@code :} 分隔，以便现有 {@link #validateSessionId(String)}（禁止 {@code /} 和 {@code \\}）仍能接受组合字符串。
     */
    private static String slotId(String userId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        return normalizeUser(userId) + ":" + sessionId;
    }

    /**
     * 关闭会话并释放资源。
     *
     * <p>注意：不关闭 DataSource，因为它可能被多个会话共享。数据源生命周期由调用方管理。
     */
    @Override
    public void close() {
        // DataSource 由外部管理，此处不关闭
    }

    /**
     * 获取保存会话的数据库名。
     *
     * @return 数据库名
     */
    public String getDatabaseName() {
        return databaseName;
    }

    /**
     * 获取保存会话的表名。
     *
     * @return 表名
     */
    public String getTableName() {
        return tableName;
    }

    /**
     * 获取数据库连接所用 DataSource。
     *
     * @return DataSource 实例
     */
    public DataSource getDataSource() {
        return dataSource;
    }

    /**
     * 清空数据库中所有会话（用于测试或清理）。
     *
     * @return 删除的行数
     * @deprecated 请改用 {@link #truncateAllSessions()}
     */
    @Deprecated
    public int clearAllSessions() {
        String clearSql = "DELETE FROM " + getFullTableName();

        try (Connection conn = dataSource.getConnection()) {
            int[] deletedRows = new int[1];
            executeInWriteTransaction(
                    conn,
                    () -> {
                        try (PreparedStatement stmt = conn.prepareStatement(clearSql)) {
                            deletedRows[0] = stmt.executeUpdate();
                        }
                    });
            return deletedRows[0];
        } catch (Exception e) {
            throw new RuntimeException("Failed to clear sessions", e);
        }
    }

    /**
     * 清空数据库中的会话表（用于测试或清理）。
     *
     * <p>通过对会话表执行 TRUNCATE TABLE 清空全部会话记录。TRUNCATE 不记录逐行删除，而是重置表并立即回收存储空间，通常快于 DELETE。
     *
     * <p><strong>注意：</strong>MySQL 中 {@code TRUNCATE TABLE} 是 DDL，会触发隐式提交且不能回滚。因此此方法直接执行语句，不经过 {@link #executeInWriteTransaction(Connection, SqlOperation)}。
     *
     * <p><strong>注意：</strong>MySQL 的 TRUNCATE 操作需要 DROP 权限。
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

    /**
     * 验证会话 ID 格式。
     *
     * @param sessionId 要验证的 AgentStateStore ID
     * @throws IllegalArgumentException 会话 ID 无效时抛出
     */
    protected void validateSessionId(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            throw new IllegalArgumentException("AgentStateStore ID cannot be null or empty");
        }
        if (sessionId.length() > 255) {
            throw new IllegalArgumentException("AgentStateStore ID cannot exceed 255 characters");
        }
    }

    /**
     * 验证状态键格式。
     *
     * @param key 要验证的状态键
     * @throws IllegalArgumentException 状态键无效时抛出
     */
    private void validateStateKey(String key) {
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalArgumentException("State key cannot be null or empty");
        }
        if (key.length() > 255) {
            throw new IllegalArgumentException("State key cannot exceed 255 characters");
        }
    }

    /**
     * 验证数据库或表标识符，防止 SQL 注入。
     *
     * <p>确保标识符仅包含安全字符（字母、数字、下划线和连字符），且以字母或下划线开头。数据库与表名无法作为预处理语句参数绑定，因此此检查十分重要。
     *
     * @param identifier 要验证的标识符（数据库名或表名）
     * @param identifierType 用于错误消息的标识符类型描述
     * @throws IllegalArgumentException 标识符无效或包含不安全字符时抛出
     */
    private void validateIdentifier(String identifier, String identifierType) {
        if (identifier == null || identifier.isEmpty()) {
            throw new IllegalArgumentException(identifierType + " cannot be null or empty");
        }
        if (identifier.length() > MAX_IDENTIFIER_LENGTH) {
            throw new IllegalArgumentException(
                    identifierType + " cannot exceed " + MAX_IDENTIFIER_LENGTH + " characters");
        }
        if (!IDENTIFIER_PATTERN.matcher(identifier).matches()) {
            throw new IllegalArgumentException(
                    identifierType
                            + " contains invalid characters. Only alphanumeric characters,"
                            + " underscores, and hyphens are allowed, and it must start with a"
                            + " letter or underscore. Invalid value: "
                            + identifier);
        }
    }
}
