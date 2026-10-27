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
import java.io.InputStream;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;

/**
 * SQLite 方言。
 *
 * <p>使用 SQLite 原生 {@code TEXT}/{@code INTEGER} 类型。
 * UPSERT 使用 {@code ON CONFLICT DO UPDATE}（需要 SQLite 3.24+）；
 * 表存在性检查使用 {@code sqlite_master}。
 *
 * @author shanhongyu
 */
public class SqliteDialect extends AbstractJdbcDialect {

    // ------------------------------------------------------------------
    //  StoreDialect
    // ------------------------------------------------------------------

    @Override
    public List<String> storeCreateTableDdls() {
        return List.of(
                "CREATE TABLE IF NOT EXISTS "
                        + storeTableName()
                        + " ("
                        + "  namespace_path TEXT    NOT NULL,"
                        + "  item_key       TEXT    NOT NULL,"
                        + "  value_json     TEXT    NOT NULL,"
                        + "  version        INTEGER NOT NULL,"
                        + "  updated_at     INTEGER NOT NULL,"
                        + "  PRIMARY KEY (namespace_path, item_key)"
                        + ")",
                // SQLite 无法在 CREATE TABLE 内定义辅助索引；没有该索引时，
                // LIKE 前缀搜索退化为全表扫描。
                "CREATE INDEX IF NOT EXISTS "
                        + storeTableName()
                        + "_namespace_idx ON "
                        + storeTableName()
                        + " (namespace_path)");
    }

    @Override
    public BoundSql storeUpsert(String namespacePath, String key, String json, long timestamp) {
        return new BoundSql(
                "INSERT INTO "
                        + storeTableName()
                        + " (namespace_path, item_key, value_json, version, updated_at)"
                        + " VALUES (?, ?, ?, 1, ?)"
                        + " ON CONFLICT(namespace_path, item_key) DO UPDATE SET"
                        + "   value_json = excluded.value_json,"
                        + "   version    = version + 1,"
                        + "   updated_at = excluded.updated_at",
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
                        + "  session_id  TEXT    NOT NULL,"
                        + "  state_key   TEXT    NOT NULL,"
                        + "  item_index  INTEGER NOT NULL DEFAULT 0,"
                        + "  state_data  TEXT    NOT NULL,"
                        + "  version     INTEGER NOT NULL DEFAULT 0,"
                        + "  created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                        + "  updated_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                        + "  PRIMARY KEY (session_id, state_key, item_index)"
                        + ")",
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
                        + " ON CONFLICT(session_id, state_key, item_index) DO UPDATE SET"
                        + "   state_data = excluded.state_data,"
                        + "   version    = version + 1",
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
                        + "  snapshot_id TEXT NOT NULL PRIMARY KEY, "
                        + "  data BLOB NOT NULL, "
                        + "  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP"
                        + ")");
    }

    @Override
    public BoundSql snapshotUpsert(String snapshotId, InputStream data) {
        return new BoundSql(
                "INSERT INTO "
                        + snapshotTableName()
                        + " (snapshot_id, data) VALUES (?, ?)"
                        + " ON CONFLICT(snapshot_id) DO UPDATE SET"
                        + "   data = excluded.data, created_at = CURRENT_TIMESTAMP",
                snapshotId,
                data);
    }

    // ------------------------------------------------------------------
    //  SkillDialect / SkillResourcesDialect
    // ------------------------------------------------------------------

    /**
     * 转换为 SQLite 类型的技能表：ID 使用 {@code INTEGER PRIMARY KEY AUTOINCREMENT}
     * （通过 {@code last_insert_rowid()} 支持 {@code RETURN_GENERATED_KEYS} 所需），
     * 载荷使用 {@code TEXT}，并有级联外键。
     * SQLite 只有在调用方逐连接启用 {@code PRAGMA foreign_keys} 后才执行外键约束，
     * 因此仓库显式删除资源，级联保留为第二道防线。
     * 技能表携带 {@code namespace} 列，以 {@code UNIQUE(namespace, name)} 约束唯一性。
     */
    @Override
    public List<String> skillCreateTableDdls() {
        return List.of(
                "CREATE TABLE IF NOT EXISTS "
                        + skillTableName()
                        + " ("
                        + "  id            INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "  namespace     VARCHAR(64) NOT NULL DEFAULT 'default',"
                        + "  name          TEXT NOT NULL,"
                        + "  description   TEXT NOT NULL,"
                        + "  skill_content TEXT NOT NULL,"
                        + "  source        TEXT NOT NULL,"
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
                        + "  id               INTEGER NOT NULL,"
                        + "  namespace        VARCHAR(64)  NOT NULL DEFAULT 'default',"
                        + "  resource_path    TEXT NOT NULL,"
                        + "  resource_content TEXT NOT NULL,"
                        + "  created_at       TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                        + "  updated_at       TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                        + "  PRIMARY KEY (id, resource_path),"
                        + "  FOREIGN KEY (id) REFERENCES "
                        + skillTableName()
                        + "(id) ON DELETE CASCADE"
                        + ")",
                // SQLite 无法在 CREATE TABLE 内定义辅助索引；没有该索引时，
                // 按命名空间限定的资源批量语句会对共享表执行全表扫描。
                "CREATE INDEX IF NOT EXISTS "
                        + skillResourcesTableName()
                        + "_namespace_idx ON "
                        + skillResourcesTableName()
                        + " (namespace)");
    }

    // ------------------------------------------------------------------
    // 检测
    // ------------------------------------------------------------------

    @Override
    public boolean supports(DatabaseMetaData metaData) throws SQLException {
        return metaData.getDatabaseProductName().toLowerCase(Locale.ROOT).contains("sqlite");
    }
}
