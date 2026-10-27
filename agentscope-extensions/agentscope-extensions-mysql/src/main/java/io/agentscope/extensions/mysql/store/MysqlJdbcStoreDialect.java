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
package io.agentscope.extensions.mysql.store;

/**
 * {@link JdbcStore} 的 MySQL / MariaDB 方言。
 */
public class MysqlJdbcStoreDialect implements JdbcStoreDialect {

    @Override
    public String getCreateTableSql() {
        // 将复合主键保持在 InnoDB utf8mb4 的 3072 字节限制以内。
        // 键列固定使用二进制排序规则：utf8mb4 的默认规则
        // 不区分大小写（MySQL 8.0+ 为 utf8mb4_0900_ai_ci，5.7 和
        // MariaDB 为 utf8mb4_general_ci），会使 "README.md" 和 "readme.md" 发生主键冲突，
        // 后一次 put 悄然覆盖前一次。载荷列保留表默认规则。
        return "CREATE TABLE IF NOT EXISTS %s ("
                + "  namespace_path VARCHAR(512)  COLLATE utf8mb4_bin NOT NULL,"
                + "  item_key       VARCHAR(255)  COLLATE utf8mb4_bin NOT NULL,"
                + "  value_json     LONGTEXT      NOT NULL,"
                + "  version        BIGINT        NOT NULL,"
                + "  updated_at     BIGINT        NOT NULL,"
                + "  PRIMARY KEY (namespace_path, item_key)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";
    }

    @Override
    public String getUpsertSql() {
        return "INSERT INTO %s (namespace_path, item_key, value_json, version, updated_at)"
                + " VALUES (?, ?, ?, 1, ?)"
                + " ON DUPLICATE KEY UPDATE"
                + "   value_json = VALUES(value_json),"
                + "   version    = version + 1,"
                + "   updated_at = VALUES(updated_at)";
    }

    @Override
    public String getInsertSql() {
        return "INSERT INTO %s (namespace_path, item_key, value_json, version, updated_at)"
                + " VALUES (?, ?, ?, 1, ?)";
    }

    @Override
    public String getCasUpdateSql() {
        return "UPDATE %s SET value_json = ?, version = version + 1, updated_at = ?"
                + " WHERE namespace_path = ? AND item_key = ? AND version = ?";
    }
}
