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

import io.agentscope.extensions.jdbc.dialect.table.SessionStateDialect;
import io.agentscope.extensions.jdbc.dialect.table.SkillDialect;
import io.agentscope.extensions.jdbc.dialect.table.SkillResourcesDialect;
import io.agentscope.extensions.jdbc.dialect.table.SnapshotDialect;
import io.agentscope.extensions.jdbc.dialect.table.StoreDialect;
import io.agentscope.harness.agent.sandbox.SandboxLease;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 聚合方言抽象类，实现全部表领域接口及 {@link SandboxLockStrategy} 契约。
 *
 * <p>保存统一表前缀和逐表名称覆盖。数据库专用类继承此类，仅覆盖其 SQL
 * 与 ANSI 默认值不同的方法。锁行为也可覆盖：默认 {@link #tryEnter}
 * 使用可移植的表锁；具有原生建议锁的方言（例如 MySQL {@code GET_LOCK}、
 * PostgreSQL {@code pg_try_advisory_lock}）覆盖 {@code tryEnter}。
 *
 * <p>表名解析优先级：<strong>逐表覆盖 &gt; 前缀 + 基础名</strong>。
 * {@code final} 解析方法将逻辑固定在基类；专用方言继承并读取组装后的名称。
 *
 * <p>字段在组装期间可变（由 {@link AbstractJdbcDialectBuilder} 设置），
 * {@code build()} 返回后实际上只读。
 *
 * <h2>自动检测</h2>
 *
 * <p>{@link #from(DataSource)} 返回 {@link AbstractJdbcDialectBuilder}，通过 JDK SPI
 * （{@link java.util.ServiceLoader}）发现类路径中的全部方言实现。
 * 按 {@link #getOrder()} 升序排序，再按继承深度排序（子类在父类前）。
 * 第一个 {@link #supports(DatabaseMetaData)} 返回 {@code true} 的方言胜出。
 *
 * @author shanhongyu
 */
public abstract class AbstractJdbcDialect
        implements StoreDialect,
                SessionStateDialect,
                SnapshotDialect,
                SkillDialect,
                SkillResourcesDialect,
                SandboxLockStrategy {

    private static final Logger LOG = LoggerFactory.getLogger(AbstractJdbcDialect.class);

    private String tablePrefix = "agentscope_";
    private String storeTableNameOverride;
    private String sessionStateTableNameOverride;
    private String snapshotTableNameOverride;
    private String skillTableNameOverride;
    private String skillResourcesTableNameOverride;

    /**
     * 基础表组（store、sessions、snapshots）是否参与表结构处理；默认 true。
     */
    private boolean baseTablesEnabled = true;

    /**
     * 技能表组（skills、skill_resources）是否参与表结构处理；默认 false。
     */
    private boolean skillTablesEnabled;

    private DataSource dataSource;

    /**
     * 此方言实例是否已创建锁表。保护 {@code CREATE TABLE IF NOT EXISTS} DDL，
     * 使后续 {@link #tryEnter(String, int)} 获取锁通常无需重复执行。
     * 不能设为 static：每个实例（以及测试中的每个新内存数据库）都需要自己的幂等创建。
     *
     * <p>volatile 使观察到 {@code true} 的线程看到执行线程在发布标志前完成的初始化，
     * 避免两个线程并发获取锁时在此标志上出现 Java 内存模型数据竞争。
     */
    private volatile boolean lockTableEnsured;

    // ------------------------------------------------------------------
    // 不可覆盖的表名解析（逐表覆盖 > 前缀 + 基础名）
    // ------------------------------------------------------------------

    @Override
    public final String storeTableName() {
        return Objects.requireNonNullElseGet(
                storeTableNameOverride, () -> tablePrefix + StoreDialect.super.storeTableName());
    }

    @Override
    public final String sessionStateTableName() {
        return Objects.requireNonNullElseGet(
                sessionStateTableNameOverride,
                () -> tablePrefix + SessionStateDialect.super.sessionStateTableName());
    }

    @Override
    public final String snapshotTableName() {
        return Objects.requireNonNullElseGet(
                snapshotTableNameOverride,
                () -> tablePrefix + SnapshotDialect.super.snapshotTableName());
    }

    @Override
    public final String skillTableName() {
        return Objects.requireNonNullElseGet(
                skillTableNameOverride, () -> tablePrefix + SkillDialect.super.skillTableName());
    }

    @Override
    public final String skillResourcesTableName() {
        return Objects.requireNonNullElseGet(
                skillResourcesTableNameOverride,
                () -> tablePrefix + SkillResourcesDialect.super.skillResourcesTableName());
    }

    // ------------------------------------------------------------------
    // 全部表 DDL 收集（供构建器集中使用）
    // ------------------------------------------------------------------

    /**
     * 按解析后的表名收集每个已启用表的建表 DDL。
     * 这是构建器执行及验证的唯一来源，新增表后无需额外装配即可创建和检查。
     * 每个表领域方法可返回一条或多条语句（例如 {@code CREATE TABLE} 加辅助
     * {@code CREATE INDEX}），不限制每表仅有一条 SQL。
     *
     * <p>保留插入顺序（store → sessions → snapshots → skills → skill_resources，去除禁用组）；
     * 覆盖实现应保留此顺序（例如使用 {@link LinkedHashMap}），使 DDL 执行可预测；
     * 资源表必须位于它引用的技能表之后。
     *
     * @return 解析后的表名到该表 DDL 语句的映射
     */
    protected Map<String, List<String>> createTableDdls() {
        Map<String, List<String>> ddls = new LinkedHashMap<>();
        if (baseTablesEnabled) {
            putDdls(ddls, storeTableName(), storeCreateTableDdls());
            putDdls(ddls, sessionStateTableName(), sessionStateCreateTableDdls());
            putDdls(ddls, snapshotTableName(), snapshotCreateTableDdls());
        }
        if (skillTablesEnabled) {
            putDdls(ddls, skillTableName(), skillCreateTableDdls());
            putDdls(ddls, skillResourcesTableName(), skillResourcesCreateTableDdls());
        }
        return ddls;
    }

    /**
     * 放入一个领域的 DDL；若表名已被其他领域占用则拒绝，
     * 否则名称冲突会静默丢失一个领域的创建与验证。
     *
     * @param ddls 正在构造的映射
     * @param tableName 该领域解析后的表名
     * @param statements 该领域的 DDL 语句
     * @throws IllegalStateException 其他领域已使用 {@code tableName} 时抛出
     */
    private static void putDdls(
            Map<String, List<String>> ddls, String tableName, List<String> statements) {
        if (ddls.put(tableName, statements) != null) {
            throw new IllegalStateException(
                    "Duplicate JDBC table name '"
                            + tableName
                            + "': each table domain needs a distinct table name");
        }
    }

    // ------------------------------------------------------------------
    // 包级可见设置器（仅供构建器组装）
    // ------------------------------------------------------------------

    final void tablePrefix(String tablePrefix) {
        this.tablePrefix = tablePrefix;
    }

    final void storeTableName(String tableName) {
        this.storeTableNameOverride = tableName;
    }

    final void sessionStateTableName(String tableName) {
        this.sessionStateTableNameOverride = tableName;
    }

    final void snapshotTableName(String tableName) {
        this.snapshotTableNameOverride = tableName;
    }

    final void skillTableName(String tableName) {
        this.skillTableNameOverride = tableName;
    }

    final void skillResourcesTableName(String tableName) {
        this.skillResourcesTableNameOverride = tableName;
    }

    final void baseTablesEnabled(boolean enabled) {
        this.baseTablesEnabled = enabled;
    }

    final void skillTablesEnabled(boolean enabled) {
        this.skillTablesEnabled = enabled;
    }

    /**
     * 此实例的表结构处理中是否启用基础表组。
     */
    public boolean isBaseTablesEnabled() {
        return baseTablesEnabled;
    }

    /**
     * 此实例的表结构处理中是否启用技能表组。
     */
    public boolean isSkillTablesEnabled() {
        return skillTablesEnabled;
    }

    /**
     * 绑定获取锁使用的 DataSource。由构建器和 {@code JdbcDistributedStore} 调用，
     * 不面向应用直接使用。
     *
     * <p>已绑定其他 DataSource（例如构建方言时所用数据源）时，替换会记日志，
     * 提醒调用方注意不匹配的 {@code JdbcDistributedStore.create(dataSource, dialect)}。
     */
    public void bindDataSource(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        if (this.dataSource != null && this.dataSource != dataSource) {
            LOG.warn(
                    "Replacing already-bound DataSource {} with {} — the dialect's lock "
                            + "strategy will use the new one",
                    this.dataSource,
                    dataSource);
        }
        this.dataSource = dataSource;
    }

    /**
     * 返回已绑定的 DataSource（供数据库专用锁实现使用）。
     */
    protected DataSource getDataSource() {
        if (dataSource == null) {
            throw new IllegalStateException(
                    "DataSource not bound; use AbstractJdbcDialect.from(ds).build()"
                            + " or JdbcDistributedStore.create(ds)");
        }
        return dataSource;
    }

    /**
     * 安静关闭 JDBC 连接；连接池连接则归还池中。支持 null；关闭失败仅记 debug 日志，不向外传播。
     * 供在 {@code tryEnter} 中获取专用锁连接的数据库方言使用。
     */
    public static void closeConnection(Connection con) {
        if (con != null) {
            try {
                con.close();
            } catch (SQLException ex) {
                LOG.debug("Could not close JDBC Connection", ex);
            } catch (Throwable ex) {
                LOG.debug("Unexpected exception on closing JDBC Connection", ex);
            }
        }
    }

    /**
     * 安静关闭 JDBC 语句。支持 null；失败只记日志，不向外传播。
     */
    public static void closeStatement(Statement stmt) {
        if (stmt != null) {
            try {
                stmt.close();
            } catch (SQLException ex) {
                LOG.trace("Could not close JDBC Statement", ex);
            } catch (Throwable ex) {
                LOG.trace("Unexpected exception on closing JDBC Statement", ex);
            }
        }
    }

    /**
     * 安静关闭 JDBC 结果集。支持 null；失败只记日志，不向外传播。
     */
    public static void closeResultSet(ResultSet rs) {
        if (rs != null) {
            try {
                rs.close();
            } catch (SQLException ex) {
                LOG.trace("Could not close JDBC ResultSet", ex);
            } catch (Throwable ex) {
                LOG.trace("Unexpected exception on closing JDBC ResultSet", ex);
            }
        }
    }

    // ------------------------------------------------------------------
    // 数据库专用实现契约
    // ------------------------------------------------------------------

    /**
     * 此方言是否支持目标数据库。
     *
     * @param metaData JDBC 元数据（连接保持打开）
     * @return 此方言可处理该数据库时为 true
     * @throws SQLException 读取元数据失败时抛出
     */
    public abstract boolean supports(DatabaseMetaData metaData) throws SQLException;

    /**
     * 检测优先级（越小越优先），默认 100。
     */
    public int getOrder() {
        return 100;
    }

    // ------------------------------------------------------------------
    // SandboxLockStrategy：默认基于表的实现
    // ------------------------------------------------------------------

    private static final int INITIAL_BACKOFF_MS = 50;
    private static final int MAX_BACKOFF_MS = 1000;

    /**
     * 锁表名称，由表前缀派生。
     */
    protected String lockTableName() {
        return tablePrefix + "distributed_locks";
    }

    @Override
    public SandboxLease tryEnter(String lockName, int timeoutSeconds) throws InterruptedException {
        Objects.requireNonNull(lockName, "lockName");
        if (timeoutSeconds < 0) {
            throw new IllegalArgumentException("timeoutSeconds must be non-negative");
        }
        DataSource ds = getDataSource();

        ensureLockTable(ds);
        String insertSql = "INSERT INTO " + lockTableName() + " (lock_name) VALUES (?)";
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        int backoff = INITIAL_BACKOFF_MS;

        while (true) {
            try (Connection conn = ds.getConnection();
                    PreparedStatement ps = conn.prepareStatement(insertSql)) {
                ps.setString(1, lockName);
                ps.executeUpdate();
                LOG.debug("[table-lock] Acquired '{}'", lockName);
                return new TableLease(ds, lockTableName(), lockName);
            } catch (SQLException e) {
                if (!isDuplicateKey(e)) {
                    throw new RuntimeException(
                            "Failed to acquire table-based lock: " + lockName, e);
                }
                if (System.currentTimeMillis() >= deadline) {
                    throw new InterruptedException(
                            "Timed out waiting for table-based lock: "
                                    + lockName
                                    + " (timeout="
                                    + timeoutSeconds
                                    + "s)");
                }
                Thread.sleep(backoff);
                backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
            }
        }
    }

    // 【学习提示】volatile 只保证标志可见，不把“检查后执行 DDL”变成原子操作。
    // 并发首次访问仍可能多次执行 CREATE TABLE IF NOT EXISTS，安全性依赖幂等 DDL。
    private void ensureLockTable(DataSource ds) {
        if (lockTableEnsured) {
            return;
        }
        String ddl =
                "CREATE TABLE IF NOT EXISTS "
                        + lockTableName()
                        + " (lock_name VARCHAR(255) NOT NULL PRIMARY KEY)";
        try (Connection conn = ds.getConnection();
                Statement stmt = conn.createStatement()) {
            stmt.execute(ddl);
            lockTableEnsured = true;
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Failed to initialize lock table: " + lockTableName(), e);
        }
    }

    private static boolean isDuplicateKey(SQLException e) {
        if (e instanceof SQLIntegrityConstraintViolationException) {
            return true;
        }
        String state = e.getSQLState();
        if (state != null && state.startsWith("23")) {
            // SQL:2003 中 23xxx 表示完整性约束违反
            return true;
        }
        // SQLite 以 errorCode=19 和 null SQLSTATE 报告约束违反。
        String className = e.getClass().getName();
        return e.getErrorCode() == 19 && className.startsWith("org.sqlite.");
    }

    /**
     * 通过对锁表执行 DELETE 来释放的租约。
     *
     * <p>【学习提示】此表只存 lock_name，没有到期时间或持有者令牌。进程异常退出
     * 不会在此实现中自动清理遗留行；重复关闭旧租约也可能删除后来同名的锁。
     * 因而表锁不等于具备自动续租、失效恢复和 fencing 的完整租约协议。
     */
    private static final class TableLease implements SandboxLease {

        private final DataSource dataSource;
        private final String lockTableName;
        private final String lockName;

        TableLease(DataSource dataSource, String lockTableName, String lockName) {
            this.dataSource = dataSource;
            this.lockTableName = lockTableName;
            this.lockName = lockName;
        }

        @Override
        public void close() {
            String deleteSql = "DELETE FROM " + lockTableName + " WHERE lock_name = ?";
            try (Connection conn = dataSource.getConnection();
                    PreparedStatement ps = conn.prepareStatement(deleteSql)) {
                ps.setString(1, lockName);
                ps.executeUpdate();
                LOG.debug("[table-lock] Released '{}'", lockName);
            } catch (SQLException e) {
                LOG.warn("[table-lock] Failed to release '{}': {}", lockName, e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------
    // 入口
    // ------------------------------------------------------------------

    /**
     * 自动检测入口，返回支持链式配置的构建器。
     */
    public static AbstractJdbcDialectBuilder from(DataSource dataSource) {
        if (dataSource == null) {
            throw new IllegalArgumentException("dataSource cannot be null");
        }
        return new AbstractJdbcDialectBuilder(dataSource);
    }
}
