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
package io.agentscope.extensions.jdbc.dialect.vendor;

import io.agentscope.extensions.jdbc.dialect.AbstractJdbcDialect;
import io.agentscope.extensions.jdbc.dialect.BoundSql;
import io.agentscope.harness.agent.sandbox.SandboxLease;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PostgreSQL 方言，以 ANSI 为业务 SQL 基线。
 *
 * <p>业务 SQL 继承表领域接口的 ANSI 默认实现；覆盖建表 DDL 和 UPSERT 语法，
 * 使用 PostgreSQL 专有的 {@code ON CONFLICT} 及 {@code TEXT}/{@code BYTEA} 等类型。
 * 另在锁策略方法中提供 PostgreSQL 建议锁。
 *
 * @author shanhongyu
 */
public class PostgresDialect extends AbstractJdbcDialect {

    private static final Logger LOG = LoggerFactory.getLogger(PostgresDialect.class);

    // ------------------------------------------------------------------
    //  StoreDialect
    // ------------------------------------------------------------------

    @Override
    public List<String> storeCreateTableDdls() {
        return List.of(
                "CREATE TABLE IF NOT EXISTS "
                        + storeTableName()
                        + " ("
                        + "  namespace_path VARCHAR(2048) NOT NULL,"
                        + "  item_key       VARCHAR(255)  NOT NULL,"
                        + "  value_json     TEXT          NOT NULL,"
                        + "  version        BIGINT        NOT NULL,"
                        + "  updated_at     BIGINT        NOT NULL,"
                        + "  PRIMARY KEY (namespace_path, item_key)"
                        + ")",
                // PostgreSQL 无法在 CREATE TABLE 内定义辅助索引。
                "CREATE INDEX IF NOT EXISTS "
                        + storeTableName()
                        + "_namespace_idx ON "
                        + storeTableName()
                        + " (namespace_path)");
    }

    @Override
    public BoundSql storeUpsert(String namespacePath, String key, String json, long timestamp) {
        String table = storeTableName();
        return new BoundSql(
                "INSERT INTO "
                        + table
                        + " (namespace_path, item_key, value_json, version, updated_at)"
                        + " VALUES (?, ?, ?, 1, ?)"
                        + " ON CONFLICT (namespace_path, item_key) DO UPDATE SET"
                        + "   value_json = EXCLUDED.value_json,"
                        + "   version    = "
                        + table
                        + ".version + 1,"
                        + "   updated_at = EXCLUDED.updated_at",
                namespacePath,
                key,
                json,
                timestamp);
    }

    // ------------------------------------------------------------------
    //  SessionStateDialect
    // ------------------------------------------------------------------

    @Override
    public List<String> sessionStateCreateTableDdls() {
        return List.of(
                "CREATE TABLE IF NOT EXISTS "
                        + sessionStateTableName()
                        + " ("
                        + "  session_id  VARCHAR(255) NOT NULL,"
                        + "  state_key   VARCHAR(255) NOT NULL,"
                        + "  item_index  INT          NOT NULL DEFAULT 0,"
                        + "  state_data  TEXT         NOT NULL,"
                        + "  version     BIGINT       NOT NULL DEFAULT 0,"
                        + "  created_at  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,"
                        + "  updated_at  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,"
                        + "  PRIMARY KEY (session_id, state_key, item_index)"
                        + ")",
                // PostgreSQL 无法在 CREATE TABLE 内定义辅助索引。
                "CREATE INDEX IF NOT EXISTS "
                        + sessionStateTableName()
                        + "_session_idx ON "
                        + sessionStateTableName()
                        + " (session_id)");
    }

    @Override
    public BoundSql sessionStateUpsert(
            String sessionId, String stateKey, int itemIndex, String stateData) {
        return new BoundSql(
                "INSERT INTO "
                        + sessionStateTableName()
                        + " (session_id, state_key, item_index, state_data, version)"
                        + " VALUES (?, ?, ?, ?, 1)"
                        + " ON CONFLICT (session_id, state_key, item_index) DO UPDATE SET"
                        + "   state_data = EXCLUDED.state_data,"
                        + "   version    = "
                        + sessionStateTableName()
                        + ".version + 1",
                sessionId,
                stateKey,
                itemIndex,
                stateData);
    }

    // ------------------------------------------------------------------
    //  SnapshotDialect
    // ------------------------------------------------------------------

    @Override
    public List<String> snapshotCreateTableDdls() {
        return List.of(
                "CREATE TABLE IF NOT EXISTS "
                        + snapshotTableName()
                        + " ("
                        + "  snapshot_id VARCHAR(512) NOT NULL PRIMARY KEY, "
                        + "  data BYTEA NOT NULL, "
                        + "  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP"
                        + ")");
    }

    @Override
    public BoundSql snapshotUpsert(String snapshotId, InputStream data) {
        return new BoundSql(
                "INSERT INTO "
                        + snapshotTableName()
                        + " (snapshot_id, data) VALUES (?, ?)"
                        + " ON CONFLICT (snapshot_id) DO UPDATE SET"
                        + "   data = EXCLUDED.data, created_at = CURRENT_TIMESTAMP",
                snapshotId,
                data);
    }

    // ------------------------------------------------------------------
    //  SkillDialect / SkillResourcesDialect
    // ------------------------------------------------------------------

    /**
     * 从已弃用 skill-postgresql-repository 模块移植的技能表：
     * {@code BIGSERIAL} ID、{@code TEXT} 载荷和级联外键。
     * 技能表携带 {@code namespace} 列，以 {@code UNIQUE(namespace, name)} 约束唯一性。
     * 仅表名改由方言解析，不再硬编码 {@code schema.table} 前缀；
     * 与基础表一样，表现在位于连接的当前 schema。
     */
    @Override
    public List<String> skillCreateTableDdls() {
        return List.of(
                "CREATE TABLE IF NOT EXISTS "
                        + skillTableName()
                        + " ("
                        + "  id            BIGSERIAL PRIMARY KEY,"
                        + "  namespace     VARCHAR(64) NOT NULL DEFAULT 'default',"
                        + "  name          VARCHAR(255) NOT NULL,"
                        + "  description   TEXT NOT NULL,"
                        + "  skill_content TEXT NOT NULL,"
                        + "  source        VARCHAR(255) NOT NULL,"
                        + "  metadata_json TEXT NULL,"
                        + "  created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                        + "  updated_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                        + "  CONSTRAINT uk_namespace_name UNIQUE (namespace, name)"
                        + ")");
    }

    @Override
    public List<String> skillResourcesCreateTableDdls() {
        return List.of(
                "CREATE TABLE IF NOT EXISTS "
                        + skillResourcesTableName()
                        + " ("
                        + "  id               BIGINT NOT NULL,"
                        + "  namespace        VARCHAR(64)  NOT NULL DEFAULT 'default',"
                        + "  resource_path    VARCHAR(500) NOT NULL,"
                        + "  resource_content TEXT NOT NULL,"
                        + "  created_at       TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                        + "  updated_at       TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                        + "  PRIMARY KEY (id, resource_path),"
                        + "  FOREIGN KEY (id) REFERENCES "
                        + skillTableName()
                        + "(id) ON DELETE CASCADE"
                        + ")",
                // PostgreSQL 无法在 CREATE TABLE 内定义辅助索引；
                // 没有该索引时，按命名空间限定的资源批量语句会
                // 对共享表执行全表扫描。
                "CREATE INDEX IF NOT EXISTS "
                        + skillResourcesTableName()
                        + "_namespace_idx ON "
                        + skillResourcesTableName()
                        + " (namespace)");
    }

    // ------------------------------------------------------------------
    // SandboxLockStrategy：PostgreSQL 原生建议锁
    // ------------------------------------------------------------------

    @Override
    public SandboxLease tryEnter(String lockName, int timeoutSeconds) throws InterruptedException {
        Objects.requireNonNull(lockName, "lockName");
        if (timeoutSeconds < 0) {
            throw new IllegalArgumentException("timeoutSeconds must be non-negative");
        }
        long lockKey = composeLockKey(lockName);
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        LOG.debug("[postgres-lock] Acquiring: {} -> {}", lockName, lockKey);

        try {
            Connection conn = getDataSource().getConnection();
            try {
                while (true) {
                    try (PreparedStatement ps =
                            conn.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
                        ps.setLong(1, lockKey);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next() && rs.getBoolean(1)) {
                                LOG.debug("[postgres-lock] Acquired: {}", lockKey);
                                return new PostgresLease(conn, lockKey);
                            }
                        }
                    }
                    if (System.currentTimeMillis() >= deadline) {
                        throw new InterruptedException(
                                "Timed out waiting for PostgreSQL advisory lock: "
                                        + lockName
                                        + " (timeout="
                                        + timeoutSeconds
                                        + "s)");
                    }
                    TimeUnit.MILLISECONDS.sleep(100);
                }
            } catch (InterruptedException e) {
                closeConnection(conn);
                throw e;
            } catch (SQLException e) {
                closeConnection(conn);
                throw new RuntimeException(
                        "Failed to acquire PostgreSQL advisory lock: " + lockName, e);
            }
        } catch (SQLException e) {
            throw new RuntimeException(
                    "Failed to acquire PostgreSQL advisory lock: " + lockName, e);
        }
    }

    /**
     * 将锁名的 SHA-256 摘要前 8 字节组合为 64 位建议锁键，
     * 与旧 PostgreSQL 模块使用的 MurmurHash3 键宽一致。
     *
     * <p>【学习提示】64 位映射仍非无碰撞：理想均匀条件下，指定两个不同名称相撞的概率约为 2^-64；
     * 大量名称的碰撞风险按生日效应增长，不能把上游约 2^-32 的概述当成每对名称的概率。
     */
    private static long composeLockKey(String lockName) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(lockName.getBytes(StandardCharsets.UTF_8));
            long key = 0L;
            for (int i = 0; i < Long.BYTES; i++) {
                key = (key << 8) | (digest[i] & 0xFFL);
            }
            return key;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * 通过 {@code pg_advisory_unlock} 及关闭连接释放的租约。
     * 数据库会话真正结束也会释放其建议锁，作为兜底。
     *
     * <p>【学习提示】池化 JDBC Connection.close() 通常只是归还池，不能等同于物理会话断开；
     * 因此显式解锁仍然重要，调用方也必须按生命周期关闭租约。
     */
    private static final class PostgresLease implements SandboxLease {

        private final Connection conn;
        private final long lockKey;

        PostgresLease(Connection conn, long lockKey) {
            this.conn = conn;
            this.lockKey = lockKey;
        }

        @Override
        public void close() {
            try (PreparedStatement ps = conn.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                ps.setLong(1, lockKey);
                ps.executeQuery();
                LOG.debug("[postgres-lock] Released: {}", lockKey);
            } catch (Exception e) {
                LOG.warn(
                        "[postgres-lock] Failed to release PostgreSQL advisory lock {}: {}",
                        lockKey,
                        e.getMessage(),
                        e);
            } finally {
                closeConnection(conn);
            }
        }
    }

    // ------------------------------------------------------------------
    // 检测
    // ------------------------------------------------------------------

    @Override
    public boolean supports(DatabaseMetaData metaData) throws SQLException {
        return metaData.getDatabaseProductName().toLowerCase(Locale.ROOT).contains("postgresql");
    }
}
