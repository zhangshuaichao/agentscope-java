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
package io.agentscope.extensions.jdbc.dialect;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import javax.sql.DataSource;

/**
 * {@link AbstractJdbcDialect} 的构建器：链式配置后调用 {@link #build()}。
 *
 * <p>{@code build()} 执行三步：通过 SPI 检测数据库类型 → 组装表名和表组 →
 * 在一个连接中初始化并验证全部已启用表的结构；表结构处理仅发生于此。
 * {@code autoCreateTable = true}（默认）时先执行 {@link AbstractJdbcDialect#createTableDdls()}
 * （已有表上的 {@code IF NOT EXISTS} 不执行操作），再验证列，防止静默接纳同名既有表。
 * 为 {@code false} 时不执行 DDL，直接进行同样验证。正常返回后，已启用表具备声明的列，
 * 后续组件构造方法不再操作表结构。
 *
 * <p>表组：{@link #enableBaseTables(boolean)}（默认启用）与
 * {@link #enableSkillTables(boolean)}（默认禁用）限定 DDL 执行和验证范围，
 * 与 {@code autoCreateTable} 独立。至少保留一个启用组；两者均禁用时 {@link #build()}
 * 立即失败，避免无表方言到首次使用才出错。
 *
 * <p>检测使用 JDK {@link ServiceLoader} 发现类路径中的全部 {@link AbstractJdbcDialect} 实现。
 * 按 {@link AbstractJdbcDialect#getOrder()} 升序排序，同序号按继承深度打破平局（子类先于父类）。
 * 第一个 {@link AbstractJdbcDialect#supports(DatabaseMetaData)} 返回 {@code true} 的候选胜出。
 *
 * <p>第三方扩展：在 jar 中放置
 * {@code META-INF/services/io.agentscope.extensions.jdbc.dialect.AbstractJdbcDialect} 文件，
 * 列出方言类名。下游添加依赖后即可自动发现，无需修改代码。
 *
 * @author shanhongyu
 */
public class AbstractJdbcDialectBuilder {

    private final DataSource dataSource;
    private String tablePrefix = "agentscope_";
    private String storeTableName;
    private String sessionStateTableName;
    private String snapshotTableName;
    private String skillTableName;
    private String skillResourcesTableName;
    private boolean autoCreateTable = true;
    private boolean enableBaseTables = true;
    private boolean enableSkillTables;

    AbstractJdbcDialectBuilder(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * 设置统一表前缀（默认 {@code agentscope_}）。
     */
    public AbstractJdbcDialectBuilder tablePrefix(String tablePrefix) {
        this.tablePrefix = validateIdentifier(tablePrefix, "tablePrefix");
        return this;
    }

    /**
     * 覆盖完整键值存储表名（优先于前缀加基础名）。
     */
    public AbstractJdbcDialectBuilder storeTableName(String name) {
        this.storeTableName = validateIdentifier(name, "storeTableName");
        return this;
    }

    /**
     * 覆盖完整会话状态表名。
     */
    public AbstractJdbcDialectBuilder sessionStateTableName(String name) {
        this.sessionStateTableName = validateIdentifier(name, "sessionStateTableName");
        return this;
    }

    /**
     * 覆盖完整快照表名。
     */
    public AbstractJdbcDialectBuilder snapshotTableName(String name) {
        this.snapshotTableName = validateIdentifier(name, "snapshotTableName");
        return this;
    }

    /**
     * 覆盖完整技能表名。
     */
    public AbstractJdbcDialectBuilder skillTableName(String name) {
        this.skillTableName = validateIdentifier(name, "skillTableName");
        return this;
    }

    /**
     * 覆盖完整技能资源表名。
     */
    public AbstractJdbcDialectBuilder skillResourcesTableName(String name) {
        this.skillResourcesTableName = validateIdentifier(name, "skillResourcesTableName");
        return this;
    }

    /**
     * 基础表组（store、sessions、snapshots）是否参与此次构建的表结构处理（默认 true）。
     * 表组选项与 {@link #autoCreateTable(boolean)} 的创建／验证选项独立；
     * 采用设置器语义，最后一次调用生效，{@code false} 是幂等禁用，不是错误。
     *
     * <p>表组标志仅限定表结构处理范围。锁表（{@code
     * <prefix>distributed_locks}）不属于任何组：默认 {@code tryEnter} 在首次用锁时惰性创建，
     * 因此仅启用技能的部署（{@code enableBaseTables(false).enableSkillTables(true)}）
     * 若获取沙箱锁，仍会创建并使用锁表。
     */
    public AbstractJdbcDialectBuilder enableBaseTables(boolean enabled) {
        this.enableBaseTables = enabled;
        return this;
    }

    /**
     * 技能表组（skills、skill_resources）是否参与此次构建的表结构处理
     * （默认 false，需显式启用）。语义与 {@link #enableBaseTables(boolean)} 相同。
     */
    public AbstractJdbcDialectBuilder enableSkillTables(boolean enabled) {
        this.enableSkillTables = enabled;
        return this;
    }

    /**
     * 是否在 {@link #build()} 时自动建表（默认 true）。无论何种取值，
     * 之后都会验证所有已启用表组：{@code true} 先执行幂等 DDL；{@code false} 不执行 DDL，
     * 因此缺表或缺列会使组装失败，并在错误中附带参考 DDL。
     */
    public AbstractJdbcDialectBuilder autoCreateTable(boolean autoCreateTable) {
        this.autoCreateTable = autoCreateTable;
        return this;
    }

    /**
     * 检测方言，组装表名和表组，再创建并验证。
     */
    public AbstractJdbcDialect build() {
        if (!enableBaseTables && !enableSkillTables) {
            throw new IllegalStateException(
                    "No table groups enabled: enableBaseTables(false) combined with"
                            + " enableSkillTables(false) leaves build() nothing to create or"
                            + " validate. Enable at least one group.");
        }
        AbstractJdbcDialect dialect = detectDialect();
        dialect.tablePrefix(this.tablePrefix);
        if (this.storeTableName != null) {
            dialect.storeTableName(this.storeTableName);
        }
        if (this.sessionStateTableName != null) {
            dialect.sessionStateTableName(this.sessionStateTableName);
        }
        if (this.snapshotTableName != null) {
            dialect.snapshotTableName(this.snapshotTableName);
        }
        if (this.skillTableName != null) {
            dialect.skillTableName(this.skillTableName);
        }
        if (this.skillResourcesTableName != null) {
            dialect.skillResourcesTableName(this.skillResourcesTableName);
        }
        dialect.baseTablesEnabled(this.enableBaseTables);
        dialect.skillTablesEnabled(this.enableSkillTables);
        dialect.bindDataSource(this.dataSource);
        initializeAndValidateSchema(dialect);
        return dialect;
    }

    /**
     * SPI 检测：第一个 {@code supports()} 接受目标数据库的候选胜出。
     *
     * @return 匹配的方言实例
     */
    private AbstractJdbcDialect detectDialect() {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData metaData = conn.getMetaData();
            List<AbstractJdbcDialect> candidates = new ArrayList<>();
            ServiceLoader.load(AbstractJdbcDialect.class).forEach(candidates::add);
            candidates.sort(
                    (a, b) -> {
                        int byOrder = Integer.compare(a.getOrder(), b.getOrder());
                        if (byOrder != 0) {
                            return byOrder;
                        }
                        return Integer.compare(
                                dialectDepth(b.getClass()), dialectDepth(a.getClass()));
                    });
            for (AbstractJdbcDialect candidate : candidates) {
                if (candidate.supports(metaData)) {
                    return candidate;
                }
            }
            throw new IllegalStateException(
                    "No JDBC dialect found for database '"
                            + metaData.getDatabaseProductName()
                            + "'; verify the dialect jar and META-INF/services/"
                            + AbstractJdbcDialect.class.getName()
                            + " registration");
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Failed to obtain JDBC connection or metadata for dialect detection", e);
        }
    }

    /**
     * 在一个连接上完成全部表结构处理：可选 DDL 执行，然后按各表自身 DDL 验证列。
     * 两者都来自 {@link AbstractJdbcDialect#createTableDdls()}，即表与语句配对的唯一来源。
     *
     * @param dialect 已组装的方言
     * @throws IllegalStateException 缺表或缺少声明列时抛出
     */
    private void initializeAndValidateSchema(AbstractJdbcDialect dialect) {
        Map<String, List<String>> ddlsByTable = dialect.createTableDdls();
        try (Connection conn = dataSource.getConnection()) {
            createTablesIfNeeded(conn, ddlsByTable);
            for (Map.Entry<String, List<String>> table : ddlsByTable.entrySet()) {
                TableSchemaValidator.validate(conn, table.getKey(), table.getValue());
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Failed to obtain a connection for schema initialization during dialect"
                            + " assembly",
                    e);
        }
    }

    /**
     * 在 {@code conn} 上执行每条 CREATE DDL；未启用 {@code autoCreateTable} 时不执行操作。
     *
     * @param conn 组装连接
     * @param ddlsByTable 按解析后表名索引的各表 DDL 语句
     */
    private void createTablesIfNeeded(Connection conn, Map<String, List<String>> ddlsByTable) {
        if (!this.autoCreateTable) {
            return;
        }
        try (Statement stmt = conn.createStatement()) {
            for (List<String> ddls : ddlsByTable.values()) {
                for (String ddl : ddls) {
                    stmt.execute(ddl);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Auto-create table(s) failed during dialect assembly: " + e.getMessage(), e);
        }
    }

    /**
     * 相对 {@link AbstractJdbcDialect} 的继承深度（直接子类为 1）。
     *
     * @param clazz 方言类
     * @return {@code clazz} 与 {@code AbstractJdbcDialect} 之间的类层数
     */
    private static int dialectDepth(Class<?> clazz) {
        int depth = 0;
        for (Class<?> c = clazz;
                c != AbstractJdbcDialect.class && c != null;
                c = c.getSuperclass()) {
            depth++;
        }
        return depth;
    }

    /**
     * 验证标识符可安全插入 SQL 字符串。预编译语句不能参数化表名及前缀，
     * 因此此正则表达式是这里的注入防护。接受 {@code [A-Za-z_][A-Za-z0-9_]*}，
     * 不允许连字符、空格或特殊字符。
     *
     * @param identifier 调用方提供的标识符
     * @param paramName 标识符参数名，用于错误消息
     * @return 已验证的标识符
     */
    private static String validateIdentifier(String identifier, String paramName) {
        if (identifier == null
                || identifier.isBlank()
                || !TableSchemaValidator.VALID_IDENTIFIER.matcher(identifier).matches()) {
            throw new IllegalArgumentException(
                    paramName + " must match [A-Za-z_][A-Za-z0-9_]*, got: " + identifier);
        }
        return identifier;
    }
}
