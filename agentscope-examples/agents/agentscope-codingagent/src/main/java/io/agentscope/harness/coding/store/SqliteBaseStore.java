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
package io.agentscope.harness.coding.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 基于 SQLite 的 {@link BaseStore} 实现。
 *
 * <p>所有条目保存在同一表，列包括 {@code namespace}（文本）、{@code key}（文本）、{@code value}（JSON 文本）、{@code version}（单调递增整数）。namespace + key 构成主键。
 *
 * <p>命名空间列表序列化为用 {@code /} 连接的字符串，例如 {@code ["threads", "abc"]} → {@code "threads/abc"}。
 *
 * <p>作为以下数据的统一后端：
 * <ul>
 * <li>{@code ["threads", thread_id]}：线程元数据</li>
 * <li>{@code ["queue", thread_id]}：消息队列</li>
 * <li>{@code ["deliveries"]}：webhook 投递去重</li>
 * <li>{@code ["findings", thread_id]}：审查发现项</li>
 * </ul>
 *
 * <p>【学习提示】单条条件更新/插入提供的是单键 CAS 原子性，不能把多个独立 get/put/delete 自动组合成事务。命名空间以斜杠直接连接，因此调用方还需约束段值，避免不同段列表拼成同一字符串；它本身不是租户鉴权机制。
 */
public class SqliteBaseStore implements BaseStore, AutoCloseable {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final Connection connection;
    private final ObjectMapper mapper = new ObjectMapper();

    public SqliteBaseStore(String dbPath) throws SQLException {
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        this.connection.setAutoCommit(true);
        init();
    }

    private void init() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS store (
                        namespace TEXT NOT NULL,
                        key       TEXT NOT NULL,
                        value     TEXT NOT NULL,
                        version   INTEGER NOT NULL DEFAULT 1,
                        updated_at INTEGER NOT NULL DEFAULT (strftime('%s', 'now')),
                        PRIMARY KEY (namespace, key)
                    )
                    """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_store_ns ON store(namespace)");
            // 迁移早于 version 列的旧数据库。
            if (!hasColumn("store", "version")) {
                stmt.execute("ALTER TABLE store ADD COLUMN version INTEGER NOT NULL DEFAULT 1");
            }
        }
    }

    private boolean hasColumn(String table, String column) throws SQLException {
        try (PreparedStatement ps =
                        connection.prepareStatement("PRAGMA table_info(" + table + ")");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public StoreItem get(List<String> namespace, String key) {
        String ns = toNs(namespace);
        try (PreparedStatement ps =
                connection.prepareStatement(
                        "SELECT value, version FROM store WHERE namespace=? AND key=?")) {
            ps.setString(1, ns);
            ps.setString(2, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new StoreItem(
                            key, mapper.readValue(rs.getString(1), MAP_TYPE), rs.getLong(2));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("SqliteBaseStore.get failed", e);
        }
        return null;
    }

    @Override
    public void put(List<String> namespace, String key, Map<String, Object> value) {
        String ns = toNs(namespace);
        try {
            String json = mapper.writeValueAsString(value);
            // UPSERT：以 version=1 插入，冲突时递增已有版本。
            try (PreparedStatement ps =
                    connection.prepareStatement(
                            "INSERT INTO store(namespace, key, value, version, updated_at)"
                                    + " VALUES(?,?,?,1,strftime('%s','now'))"
                                    + " ON CONFLICT(namespace, key) DO UPDATE SET"
                                    + " value=excluded.value,"
                                    + " version=store.version+1,"
                                    + " updated_at=strftime('%s','now')")) {
                ps.setString(1, ns);
                ps.setString(2, key);
                ps.setString(3, json);
                ps.executeUpdate();
            }
        } catch (Exception e) {
            throw new RuntimeException("SqliteBaseStore.put failed", e);
        }
    }

    /**
     * 基于 SQLite 的原子比较并交换。{@code expectedVersion == 0} 时仅在行尚不存在时成功，即不存在才创建；否则仅在存储行满足 {@code version == expectedVersion} 时成功。
     *
     * <p>单条 UPDATE / INSERT-OR-IGNORE 保证数据库侧原子性；竞争同一预期版本的写入仅一次返回 {@code true}，其余返回 {@code false}。
     */
    @Override
    public boolean putIfVersion(
            List<String> namespace, String key, Map<String, Object> value, long expectedVersion) {
        String ns = toNs(namespace);
        try {
            String json = mapper.writeValueAsString(value);
            if (expectedVersion == 0L) {
                try (PreparedStatement ps =
                        connection.prepareStatement(
                                "INSERT OR IGNORE INTO store(namespace, key, value, version,"
                                        + " updated_at)"
                                        + " VALUES(?,?,?,1,strftime('%s','now'))")) {
                    ps.setString(1, ns);
                    ps.setString(2, key);
                    ps.setString(3, json);
                    return ps.executeUpdate() == 1;
                }
            }
            try (PreparedStatement ps =
                    connection.prepareStatement(
                            "UPDATE store SET value=?, version=version+1,"
                                    + " updated_at=strftime('%s','now')"
                                    + " WHERE namespace=? AND key=? AND version=?")) {
                ps.setString(1, json);
                ps.setString(2, ns);
                ps.setString(3, key);
                ps.setLong(4, expectedVersion);
                return ps.executeUpdate() == 1;
            }
        } catch (Exception e) {
            throw new RuntimeException("SqliteBaseStore.putIfVersion failed", e);
        }
    }

    @Override
    public List<StoreItem> search(List<String> namespace, int limit, int offset) {
        String ns = toNs(namespace);
        List<StoreItem> result = new ArrayList<>();
        try (PreparedStatement ps =
                connection.prepareStatement(
                        "SELECT key, value, version FROM store WHERE namespace=?"
                                + " ORDER BY updated_at DESC LIMIT ? OFFSET ?")) {
            ps.setString(1, ns);
            ps.setInt(2, limit > 0 ? limit : Integer.MAX_VALUE);
            ps.setInt(3, offset >= 0 ? offset : 0);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(
                            new StoreItem(
                                    rs.getString(1),
                                    mapper.readValue(rs.getString(2), MAP_TYPE),
                                    rs.getLong(3)));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("SqliteBaseStore.search failed", e);
        }
        return result;
    }

    @Override
    public void delete(List<String> namespace, String key) {
        String ns = toNs(namespace);
        try (PreparedStatement ps =
                connection.prepareStatement("DELETE FROM store WHERE namespace=? AND key=?")) {
            ps.setString(1, ns);
            ps.setString(2, key);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("SqliteBaseStore.delete failed", e);
        }
    }

    /**
     * 统计命名空间内条目数。
     */
    public int count(List<String> namespace) {
        String ns = toNs(namespace);
        try (PreparedStatement ps =
                connection.prepareStatement("SELECT COUNT(*) FROM store WHERE namespace=?")) {
            ps.setString(1, ns);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException("SqliteBaseStore.count failed", e);
        }
    }

    @Override
    public void close() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }

    private static String toNs(List<String> namespace) {
        if (namespace == null || namespace.isEmpty()) return "/";
        return String.join("/", namespace);
    }
}
