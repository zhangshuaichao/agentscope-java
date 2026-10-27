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

import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link JdbcStore} 的数据库专用 SQL。
 *
 * <p>每种方言生成四种因厂商而异的 SQL：
 * <ul>
 *   <li>{@link #getCreateTableSql}：初始化表结构，设置 {@code JdbcStore.Builder#initializeSchema(true)} 时使用。
 *   <li>{@link #getUpsertSql}：无条件写入，更新时递增 {@code version}。
 *   <li>{@link #getInsertSql}：仅创建的插入；{@link BaseStore#putIfVersion} CAS 路径在 {@code expectedVersion == 0} 时使用，主键冲突表示行已存在。
 *   <li>{@link #getCasUpdateSql}：条件更新；{@link BaseStore#putIfVersion} CAS 路径在 {@code expectedVersion > 0} 时使用，依赖 {@code WHERE version = ?} 在不匹配时返回零行。
 * </ul>
 *
 * <p>结构固定的 SQL（{@link #getSelectSql}、{@link #getDeleteSql}、{@link #getSearchSql}）在此接口提供安全默认实现。
 *
 * <p>所有模板用 {@code %s} 作为表名占位符，使调用方能通过 {@code JdbcStore.Builder#tableName} 自定义。
 */
public interface JdbcStoreDialect {

    Logger LOG = LoggerFactory.getLogger(JdbcStoreDialect.class);

    /**
     * 返回表尚不存在时创建存储表的 SQL。
     *
     * <p>表须包含列：{@code namespace_path VARCHAR(2048)}、{@code item_key VARCHAR(255)}、{@code value_json}（文本类型）、{@code version BIGINT}、{@code updated_at BIGINT}，以及 {@code (namespace_path, item_key)} 主键。
     *
     * @return CREATE TABLE 语句，表名必须使用 {@code %s}
     */
    String getCreateTableSql();

    /**
     * 返回插入或更新条目的 SQL，更新时递增 {@code version}。
     *
     * <p>参数依次绑定 {@code (namespace_path, item_key, value_json, updated_at)}；方言负责在插入时设置 {@code version=1}，更新时设置 {@code version=version+1}。
     *
     * @return UPSERT 语句，表名必须使用 {@code %s}
     */
    String getUpsertSql();

    /**
     * 返回以 {@code version=1} 插入新行的 SQL。
     *
     * <p>参数依次绑定 {@code (namespace_path, item_key, value_json, updated_at)}。用于 {@code putIfVersion(... expectedVersion=0)}：主键冲突表示行已存在，CAS 尝试失败。
     *
     * @return INSERT 语句，表名必须使用 {@code %s}
     */
    String getInsertSql();

    /**
     * 返回仅在存储版本匹配时更新条目的 SQL。
     *
     * <p>参数依次绑定 {@code (value_json, updated_at, namespace_path, item_key, expectedVersion)}。实现必须设置 {@code version = version + 1}。调用方将受影响行数为 {@code 1} 视为成功。
     *
     * @return UPDATE 语句，表名必须使用 {@code %s}
     */
    String getCasUpdateSql();

    /**
     * 返回获取单个条目的 SQL。
     *
     * <p>参数依次绑定 {@code (namespace_path, item_key)}。投影列必须按 {@code (value_json, version)} 顺序返回。
     */
    default String getSelectSql() {
        return "SELECT value_json, version FROM %s WHERE namespace_path = ? AND item_key = ?";
    }

    /**
     * 返回删除单个条目的 SQL。
     *
     * <p>参数依次绑定 {@code (namespace_path, item_key)}。
     */
    default String getDeleteSql() {
        return "DELETE FROM %s WHERE namespace_path = ? AND item_key = ?";
    }

    /**
     * 返回列出 namespace_path 匹配 {@code LIKE} 模式的条目的 SQL。
     *
     * <p>参数依次绑定 {@code (namespace_like_pattern, limit, offset)}，投影列必须按 {@code (item_key, value_json, version)} 顺序返回。
     *
     * <p>默认使用 {@code ESCAPE '!'}（MySQL、PostgreSQL、SQLite、H2 通用，避免反斜杠与 {@code standard_conforming_strings} 的差异问题），并用 {@code ORDER BY item_key} 匹配 {@link InMemoryStore} 的排序行为。{@code item_key} 排序足够接近内存实现的复合键排序，可满足分页。
     */
    default String getSearchSql() {
        return "SELECT item_key, value_json, version FROM %s"
                + " WHERE namespace_path LIKE ? ESCAPE '!'"
                + " ORDER BY item_key LIMIT ? OFFSET ?";
    }

    /**
     * 与 {@link #getSearchSql} 的 {@code ESCAPE} 子句配对的转义字符。构造 {@code LIKE} 模式时，调用方必须用它转义字面 {@code %}、{@code _} 以及转义字符自身。
     */
    default char getLikeEscapeChar() {
        return '!';
    }

    /**
     * 根据 {@link DataSource} 的数据库产品名称检测方言。
     *
     * <p>无法确定或无法识别产品时默认使用 {@link PostgresJdbcStoreDialect}，让未知引擎尝试使用 SQL 标准子集。
     *
     * @param dataSource 要探测的 JDBC 数据源，不得为 {@code null}
     * @return 适合底层数据库的方言
     */
    static JdbcStoreDialect from(DataSource dataSource) {
        String productName = null;
        try (var conn = dataSource.getConnection()) {
            DatabaseMetaData md = conn.getMetaData();
            productName = md.getDatabaseProductName();
        } catch (SQLException e) {
            LOG.warn(
                    "Failed to detect JDBC database product name; defaulting to Postgres dialect",
                    e);
        }
        if (productName == null || productName.isBlank()) {
            return new PostgresJdbcStoreDialect();
        }
        return switch (productName) {
            case "PostgreSQL" -> new PostgresJdbcStoreDialect();
            case "MySQL", "MariaDB" -> new MysqlJdbcStoreDialect();
            case "SQLite" -> new SqliteJdbcStoreDialect();
            case "H2" -> new H2JdbcStoreDialect();
            default -> {
                LOG.warn(
                        "Unrecognised JDBC database product '{}'; defaulting to Postgres dialect",
                        productName);
                yield new PostgresJdbcStoreDialect();
            }
        };
    }
}
