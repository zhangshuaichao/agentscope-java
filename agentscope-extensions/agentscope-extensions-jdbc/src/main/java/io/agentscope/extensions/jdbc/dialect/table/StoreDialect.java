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
package io.agentscope.extensions.jdbc.dialect.table;

import io.agentscope.extensions.jdbc.dialect.BoundSql;
import java.util.List;

/**
 * 键值存储表的表领域方言接口。
 *
 * <p>定义建表 DDL（抽象方法，依数据库而异）与 ANSI 标准业务 SQL
 * （返回 {@link BoundSql} 的默认方法）。聚合类 {@code AbstractJdbcDialect}
 * 实现此接口及其他表领域接口，数据库专用类继承聚合类，仅覆盖存在差异的部分。
 *
 * <p>方法名以 {@code store} 为前缀，避免聚合类实现多个表领域接口时名称冲突。
 *
 * <p>辅助索引：{@link #storeSearch} 的 {@code namespace_path LIKE 'prefix%'} 查询
 * 需要 {@code namespace_path} 索引，避免大型存储全表扫描。
 * 支持内联索引的数据库（MySQL）在 {@code CREATE TABLE} 中写入
 * {@code INDEX idx_namespace (namespace_path)}；其他数据库在
 * {@link #storeCreateTableDdls()} 列表中追加幂等
 * {@code CREATE INDEX IF NOT EXISTS} 作为第二条语句。
 *
 * @author shanhongyu
 */
public interface StoreDialect {

    /**
     * 基础表名（不带前缀）。
     */
    default String storeTableName() {
        return "store";
    }

    // ------------------------------------------------------------------
    // 抽象定义：每种数据库都必须覆盖
    // ------------------------------------------------------------------

    /**
     * 创建键值存储表的一条或多条 DDL 语句，必须幂等。
     */
    List<String> storeCreateTableDdls();

    /**
     * UPSERT：以 version=1 插入新行，或更新已有行并将 version 加 1。
     */
    BoundSql storeUpsert(String namespacePath, String key, String json, long timestamp);

    // ------------------------------------------------------------------
    // 默认实现：ANSI 基线
    // ------------------------------------------------------------------

    /**
     * 仅创建路径的 INSERT（版本从 1 开始）；主键冲突表示行已存在。
     */
    default BoundSql storeInsert(String namespacePath, String key, String json, long timestamp) {
        return new BoundSql(
                "INSERT INTO "
                        + storeTableName()
                        + " (namespace_path, item_key, value_json, version, updated_at)"
                        + " VALUES (?, ?, ?, 1, ?)",
                namespacePath,
                key,
                json,
                timestamp);
    }

    /**
     * CAS 路径的条件 UPDATE，返回受影响行数（1 表示成功）。
     */
    default BoundSql storeCasUpdate(
            String json, long timestamp, String namespacePath, String key, long expectedVersion) {
        return new BoundSql(
                "UPDATE "
                        + storeTableName()
                        + " SET value_json = ?, version = version + 1, updated_at = ?"
                        + " WHERE namespace_path = ? AND item_key = ? AND version = ?",
                json,
                timestamp,
                namespacePath,
                key,
                expectedVersion);
    }

    /**
     * 单条目查询，返回列：value_json、version。
     */
    default BoundSql storeSelect(String namespacePath, String key) {
        return new BoundSql(
                "SELECT value_json, version FROM "
                        + storeTableName()
                        + " WHERE namespace_path = ? AND item_key = ?",
                namespacePath,
                key);
    }

    /**
     * 删除单个条目。
     */
    default BoundSql storeDelete(String namespacePath, String key) {
        return new BoundSql(
                "DELETE FROM " + storeTableName() + " WHERE namespace_path = ? AND item_key = ?",
                namespacePath,
                key);
    }

    /**
     * 带分页的命名空间前缀搜索，返回列：item_key、value_json、version。
     */
    default BoundSql storeSearch(String likePattern, int limit, int offset) {
        return new BoundSql(
                "SELECT item_key, value_json, version FROM "
                        + storeTableName()
                        + " WHERE namespace_path LIKE ? ESCAPE '"
                        + storeLikeEscapeChar()
                        + "'"
                        + " ORDER BY item_key LIMIT ? OFFSET ?",
                likePattern,
                limit,
                offset);
    }

    /**
     * {@link #storeSearch} 中 LIKE 模式的转义字符。
     */
    default char storeLikeEscapeChar() {
        return '!';
    }
}
