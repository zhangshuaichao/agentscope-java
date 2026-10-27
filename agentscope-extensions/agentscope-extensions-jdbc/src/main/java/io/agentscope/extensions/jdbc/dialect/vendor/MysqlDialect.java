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
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MySQL／MariaDB 方言。
 *
 * <p>覆盖 DDL（{@code LONGTEXT}、{@code ENGINE=InnoDB}、{@code utf8mb4}）、
 * UPSERT 语法（{@code ON DUPLICATE KEY UPDATE}）、表存在性检查（{@code DATABASE()}）
 * 以及锁获取（{@code GET_LOCK}／{@code RELEASE_LOCK}）。其余业务 SQL 继承 ANSI 默认值。
 *
 * @author shanhongyu
 */
public class MysqlDialect extends AbstractJdbcDialect {

    private static final Logger log = LoggerFactory.getLogger(MysqlDialect.class);

    /**
     * MySQL {@code GET_LOCK} 名称长度上限，单位字符（ASCII 锁名每字符 1 字节）。
     */
    private static final int MAX_LOCK_NAME_LENGTH = 64;

    /**
     * 规范化锁名中嵌入的 SHA-256 摘要十六进制字符数；32 个字符即 128 位，符合上游评审建议的碰撞强度。
     */
    private static final int LOCK_NAME_HASH_HEX_LENGTH = 32;

    private static final String SHA_256 = "SHA-256";

    // ------------------------------------------------------------------
    //  StoreDialect
    // ------------------------------------------------------------------

    @Override
    public List<String> storeCreateTableDdls() {
        // 将组合主键控制在 InnoDB utf8mb4 的 3072 字节限制内：
        // (512 + 255) * 4 = 3068 字节；idx_namespace 的 512 * 4 = 2048 字节也符合限制。
        // 键列固定使用二进制排序规则：utf8mb4 默认排序规则
        // 不区分大小写（MySQL 8.0+ 为 utf8mb4_0900_ai_ci，5.7
        // 和 MariaDB 为 utf8mb4_general_ci），导致 "README.md" 与 "readme.md" 在主键上
        // 冲突，使第二次 put 静默覆盖第一次。载荷列仍使用
        // 表的默认排序规则。
        return List.of(
                "CREATE TABLE IF NOT EXISTS "
                        + storeTableName()
                        + " ("
                        + "  namespace_path VARCHAR(512)  COLLATE utf8mb4_bin NOT NULL,"
                        + "  item_key       VARCHAR(255)  COLLATE utf8mb4_bin NOT NULL,"
                        + "  value_json     LONGTEXT      NOT NULL,"
                        + "  version        BIGINT        NOT NULL,"
                        + "  updated_at     BIGINT        NOT NULL,"
                        + "  PRIMARY KEY (namespace_path, item_key),"
                        + "  INDEX idx_namespace (namespace_path)"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    }

    @Override
    public BoundSql storeUpsert(String namespacePath, String key, String json, long timestamp) {
        return new BoundSql(
                "INSERT INTO "
                        + storeTableName()
                        + " (namespace_path, item_key, value_json, version, updated_at)"
                        + " VALUES (?, ?, ?, 1, ?)"
                        + " ON DUPLICATE KEY UPDATE"
                        + "   value_json = VALUES(value_json),"
                        + "   version    = version + 1,"
                        + "   updated_at = VALUES(updated_at)",
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
        // 与 storeCreateTableDdls() 理由相同：会话 ID 和状态键是精确标识符，
        // 不能因不区分大小写的比较而冲突。
        return List.of(
                """
                CREATE TABLE IF NOT EXISTS %s (
                  session_id  VARCHAR(255) COLLATE utf8mb4_bin NOT NULL,
                  state_key   VARCHAR(255) COLLATE utf8mb4_bin NOT NULL,
                  item_index  INT          NOT NULL DEFAULT 0,
                  state_data  LONGTEXT     NOT NULL,
                  version     BIGINT       NOT NULL DEFAULT 0,
                  created_at  DATETIME     DEFAULT CURRENT_TIMESTAMP,
                  updated_at  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                  PRIMARY KEY (session_id, state_key, item_index),
                  INDEX idx_session (session_id)
                ) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci\
                """
                        .formatted(sessionStateTableName()));
    }

    @Override
    public BoundSql sessionStateUpsert(
            String sessionId, String stateKey, int itemIndex, String stateData) {
        return new BoundSql(
                "INSERT INTO "
                        + sessionStateTableName()
                        + " (session_id, state_key, item_index, state_data, version)"
                        + " VALUES (?, ?, ?, ?, 1)"
                        + " ON DUPLICATE KEY UPDATE state_data = VALUES(state_data),"
                        + "   version = version + 1",
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
        // snapshot_id 是调用方提供的主键标识，因此与其他键列一样固定使用
        // 二进制排序规则；表默认规则不区分大小写，
        // 否则仅大小写不同的两个快照 ID 会共用一行。与存储表一样，
        // 此表显式声明字符集及存储引擎。
        return List.of(
                "CREATE TABLE IF NOT EXISTS "
                        + snapshotTableName()
                        + " ("
                        + "  snapshot_id VARCHAR(512) COLLATE utf8mb4_bin NOT NULL PRIMARY KEY, "
                        + "  data LONGBLOB NOT NULL, "
                        + "  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    }

    @Override
    public BoundSql snapshotUpsert(String snapshotId, InputStream data) {
        return new BoundSql(
                "INSERT INTO "
                        + snapshotTableName()
                        + " (snapshot_id, data) VALUES (?, ?)"
                        + " ON DUPLICATE KEY UPDATE"
                        + "   data = VALUES(data),"
                        + "   created_at = CURRENT_TIMESTAMP",
                snapshotId,
                data);
    }

    // ------------------------------------------------------------------
    //  SkillDialect / SkillResourcesDialect
    // ------------------------------------------------------------------

    /**
     * 从已弃用 skill-mysql-repository 模块移植的技能表：自增 ID、内联
     * {@code UNIQUE KEY uk_namespace_name (namespace, name)} 实现每命名空间名称唯一、
     * utf8mb4 Unicode 排序规则、更新时自动更新时间戳，以及资源的组合主键和级联外键。
     * {@code namespace} 列固定为 {@code utf8mb4_bin}：命名空间是隔离边界，
     * 不能使用表默认的不区分大小写规则折叠名称，与存储／会话键列的理由相同。
     * {@code name} 则保留默认规则，以维持旧查找行为。
     * 仅表名改由方言解析，不再硬编码 {@code database.table} 前缀；
     * 与基础表一样，表现在位于连接的当前数据库。
     */
    @Override
    public List<String> skillCreateTableDdls() {
        return List.of(
                "CREATE TABLE IF NOT EXISTS "
                        + skillTableName()
                        + " ("
                        + "  id            BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                        + "  namespace     VARCHAR(64) COLLATE utf8mb4_bin NOT NULL"
                        + " DEFAULT 'default',"
                        + "  name          VARCHAR(255) NOT NULL,"
                        + "  description   TEXT NOT NULL,"
                        + "  skill_content LONGTEXT NOT NULL,"
                        + "  source        VARCHAR(255) NOT NULL,"
                        + "  metadata_json LONGTEXT NULL,"
                        + "  created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                        + "  updated_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP"
                        + "    ON UPDATE CURRENT_TIMESTAMP,"
                        + "  UNIQUE KEY uk_namespace_name (namespace, name)"
                        + ") DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
    }

    @Override
    public List<String> skillResourcesCreateTableDdls() {
        // idx_namespace 与存储表索引相同：资源批量语句在各命名空间共享的表上
        // 按 namespace 筛选，而 PRIMARY KEY (id,
        // resource_path) 无法服务该条件，因为 namespace 不是其前缀。
        return List.of(
                "CREATE TABLE IF NOT EXISTS "
                        + skillResourcesTableName()
                        + " ("
                        + "  id               BIGINT NOT NULL,"
                        + "  namespace        VARCHAR(64) COLLATE utf8mb4_bin NOT NULL"
                        + " DEFAULT 'default',"
                        + "  resource_path    VARCHAR(500) NOT NULL,"
                        + "  resource_content LONGTEXT NOT NULL,"
                        + "  created_at       TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                        + "  updated_at       TIMESTAMP DEFAULT CURRENT_TIMESTAMP"
                        + "    ON UPDATE CURRENT_TIMESTAMP,"
                        + "  PRIMARY KEY (id, resource_path),"
                        + "  INDEX idx_namespace (namespace),"
                        + "  FOREIGN KEY (id) REFERENCES "
                        + skillTableName()
                        + "(id) ON DELETE CASCADE"
                        + ") DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
    }

    // ------------------------------------------------------------------
    // SandboxLockStrategy：MySQL 原生 GET_LOCK
    // ------------------------------------------------------------------

    @Override
    public SandboxLease tryEnter(String lockName, int timeoutSeconds) throws InterruptedException {
        String normalized = normalizeLockName(lockName);
        log.debug("[mysql-lock] Acquiring: {}", normalized);

        try {
            Connection conn = getDataSource().getConnection();
            try (PreparedStatement ps = conn.prepareStatement("SELECT GET_LOCK(?, ?)")) {
                ps.setString(1, normalized);
                ps.setInt(2, timeoutSeconds);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && rs.getInt(1) == 1) {
                        log.debug("[mysql-lock] Acquired: {}", normalized);
                        return new MysqlLease(conn, normalized);
                    }
                }
            } catch (Exception e) {
                closeConnection(conn);
                throw e;
            }
            closeConnection(conn);
            throw new InterruptedException(
                    "Timed out waiting for MySQL lock: "
                            + normalized
                            + " (timeout="
                            + timeoutSeconds
                            + "s)");
        } catch (InterruptedException e) {
            throw e;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to acquire MySQL lock: " + normalized, e);
        }
    }

    private static String normalizeLockName(String lockName) {
        if (lockName.length() <= MAX_LOCK_NAME_LENGTH) {
            return lockName;
        }
        // 截断前缀并追加 SHA-256 摘要，降低不同长锁名规范化后相撞的风险。
        // 【学习提示】摘要仍可能碰撞，不能将上游“绝不相同”的说法当作数学保证；
        // String.hashCode 只有 32 位，高基数下更易相撞。这里的摘要用于区分锁名，
        // 不是认证机制，也不改变数据库按连接持有命名锁的语义。
        String hash = sha256Hex(lockName);
        int prefixLength = MAX_LOCK_NAME_LENGTH - 1 - LOCK_NAME_HASH_HEX_LENGTH;
        return lockName.substring(0, prefixLength)
                + ":"
                + hash.substring(0, LOCK_NAME_HASH_HEX_LENGTH);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance(SHA_256);
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * 通过 MySQL {@code RELEASE_LOCK()} 及关闭连接释放的租约。
     */
    private static final class MysqlLease implements SandboxLease {

        private final Connection conn;
        private final String lockName;

        MysqlLease(Connection conn, String lockName) {
            this.conn = conn;
            this.lockName = lockName;
        }

        @Override
        public void close() {
            try (PreparedStatement ps = conn.prepareStatement("SELECT RELEASE_LOCK(?)")) {
                ps.setString(1, lockName);
                ps.executeQuery();
                log.debug("[mysql-lock] Released: {}", lockName);
            } catch (Exception e) {
                log.warn("[mysql-lock] Failed to release {}: {}", lockName, e.getMessage(), e);
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
        String name = metaData.getDatabaseProductName().toLowerCase(Locale.ROOT);
        return name.contains("mysql") || name.contains("mariadb");
    }
}
