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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 由 JDBC 支撑的 {@link BaseStore}，通过 {@link JdbcStoreDialect} 开箱支持 MySQL、PostgreSQL、SQLite 和 H2。
 *
 * <h2>表结构</h2>
 * <pre>{@code
 * CREATE TABLE agentscope_store (
 *   namespace_path  -- VARCHAR (PG/MySQL/H2) or TEXT (SQLite), joined namespace + trailing 0x1F
 *   item_key        -- VARCHAR or TEXT, item key
 *   value_json      -- TEXT / LONGTEXT / CLOB, Jackson-serialised Map<String, Object>
 *   version         -- BIGINT, monotonically increasing per row
 *   updated_at      -- BIGINT, epoch millis of the last write
 *   PRIMARY KEY (namespace_path, item_key)
 * )
 * }</pre>
 *
 * <p>可选 {@code Builder#initializeSchema(true)} 标记在构造时执行方言的 {@code CREATE TABLE IF NOT EXISTS}。
 *
 * <h2>命名空间编码</h2>
 * <p>为保留 {@link InMemoryStore} 的前缀搜索行为，命名空间片段用 ASCII 单元分隔符 {@code 0x1F} 连接，并保留尾部分隔符。
 * 搜索命名空间 {@code [a, b]} 转为 {@code WHERE namespace_path LIKE 'ab%' ESCAPE '!'}，既匹配该命名空间，也匹配更深子空间。拒绝包含 {@code 0x1F} 的命名空间片段。
 *
 * <h2>CAS</h2>
 * <p>{@link #putIfVersion} 执行按 {@code version} 判断的单语句条件 UPDATE。
 * {@code expectedVersion == 0}（不存在时创建）则执行 INSERT，将主键冲突视为 CAS 失败。两条路径无需额外锁即可保持原子性，可供多个 JVM 共享写入同一数据库。
 */
public class JdbcStore implements BaseStore {

    private static final Logger LOG = LoggerFactory.getLogger(JdbcStore.class);

    /**
     * {@code namespace_path} 内命名空间片段之间使用的 ASCII 单元分隔符（U+001F）。它不与可打印标识符冲突，且罕见于 JSON／用户输入。拒绝包含此字符的命名空间片段。
     */
    private static final String NS_SEPARATOR = "";

    /**
 * 未自定义构建器时使用的默认表名。
 */
    public static final String DEFAULT_TABLE_NAME = "agentscope_store";

    private static final Pattern VALID_TABLE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final DataSource dataSource;
    private final JdbcStoreDialect dialect;
    private final ObjectMapper objectMapper;
    private final String tableName;

    // 预先解析的 SQL（表名只替换一次）
    private final String selectSql;
    private final String upsertSql;
    private final String insertSql;
    private final String casUpdateSql;
    private final String deleteSql;
    private final String searchSql;

    private JdbcStore(Builder b) {
        this.dataSource = b.dataSource;
        this.dialect = b.dialect != null ? b.dialect : JdbcStoreDialect.from(b.dataSource);
        this.objectMapper = b.objectMapper != null ? b.objectMapper : new ObjectMapper();
        this.tableName = b.tableName;
        this.selectSql = String.format(dialect.getSelectSql(), tableName);
        this.upsertSql = String.format(dialect.getUpsertSql(), tableName);
        this.insertSql = String.format(dialect.getInsertSql(), tableName);
        this.casUpdateSql = String.format(dialect.getCasUpdateSql(), tableName);
        this.deleteSql = String.format(dialect.getDeleteSql(), tableName);
        this.searchSql = String.format(dialect.getSearchSql(), tableName);
        if (b.initializeSchema) {
            initializeSchema();
        }
    }

    /**
 * 创建 {@link JdbcStore} 构建器。
 */
    public static Builder builder(DataSource dataSource) {
        return new Builder(dataSource);
    }

    private void initializeSchema() {
        String ddl = String.format(dialect.getCreateTableSql(), tableName);
        try (Connection c = dataSource.getConnection();
                Statement st = c.createStatement()) {
            st.executeUpdate(ddl);
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Failed to initialize JdbcStore schema for table " + tableName, e);
        }
    }

    // -------------------------------------------------------------------------
    // BaseStore 实现
    // -------------------------------------------------------------------------

    @Override
    public StoreItem get(List<String> namespace, String key) {
        validateKey(key);
        String nsPath = namespacePath(namespace);
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(selectSql)) {
            ps.setString(1, nsPath);
            ps.setString(2, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                String json = rs.getString(1);
                long version = rs.getLong(2);
                return new StoreItem(key, deserialize(json), version);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("JdbcStore get failed", e);
        }
    }

    @Override
    public void put(List<String> namespace, String key, Map<String, Object> value) {
        validateKey(key);
        String nsPath = namespacePath(namespace);
        String json = serialize(value);
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(upsertSql)) {
            ps.setString(1, nsPath);
            ps.setString(2, key);
            ps.setString(3, json);
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("JdbcStore put failed", e);
        }
    }

    @Override
    public boolean supportsAtomicSessionStorage() {
        return true;
    }

    @Override
    public boolean putIfVersion(
            List<String> namespace, String key, Map<String, Object> value, long expectedVersion) {
        validateKey(key);
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must be non-negative");
        }
        String nsPath = namespacePath(namespace);
        String json = serialize(value);
        long now = System.currentTimeMillis();

        if (expectedVersion == 0L) {
            // 不存在时创建：依赖主键约束检测已有行。
            try (Connection c = dataSource.getConnection();
                    PreparedStatement ps = c.prepareStatement(insertSql)) {
                ps.setString(1, nsPath);
                ps.setString(2, key);
                ps.setString(3, json);
                ps.setLong(4, now);
                ps.executeUpdate();
                return true;
            } catch (SQLIntegrityConstraintViolationException dup) {
                return false;
            } catch (SQLException e) {
                if (isDuplicateKey(e)) {
                    return false;
                }
                throw new IllegalStateException("JdbcStore putIfVersion (insert) failed", e);
            }
        }

        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(casUpdateSql)) {
            ps.setString(1, json);
            ps.setLong(2, now);
            ps.setString(3, nsPath);
            ps.setString(4, key);
            ps.setLong(5, expectedVersion);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("JdbcStore putIfVersion (update) failed", e);
        }
    }

    @Override
    public List<StoreItem> search(List<String> namespace, int limit, int offset) {
        if (limit <= 0) {
            return List.of();
        }
        int safeOffset = Math.max(offset, 0);
        String pattern = likePrefixPattern(namespace);
        List<StoreItem> result = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(searchSql)) {
            ps.setString(1, pattern);
            ps.setInt(2, limit);
            ps.setInt(3, safeOffset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String key = rs.getString(1);
                    String json = rs.getString(2);
                    long version = rs.getLong(3);
                    result.add(new StoreItem(key, deserialize(json), version));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("JdbcStore search failed", e);
        }
        return result;
    }

    @Override
    public void delete(List<String> namespace, String key) {
        validateKey(key);
        String nsPath = namespacePath(namespace);
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(deleteSql)) {
            ps.setString(1, nsPath);
            ps.setString(2, key);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("JdbcStore delete failed", e);
        }
    }

    // -------------------------------------------------------------------------
    // 辅助方法
    // -------------------------------------------------------------------------

    private String serialize(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to JSON-encode store value", e);
        }
    }

    private Map<String, Object> deserialize(String json) {
        if (json == null || json.isEmpty()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Failed to JSON-decode store value", e);
        }
    }

    /**
     * 用单元分隔符连接命名空间片段，并始终附加尾部分隔符，使前缀查询能区分 {@code "a/b"} 与 {@code "a/bc"}。
     */
    private static String namespacePath(List<String> namespace) {
        Objects.requireNonNull(namespace, "namespace must not be null");
        StringBuilder sb = new StringBuilder();
        for (String segment : namespace) {
            if (segment == null) {
                throw new IllegalArgumentException("namespace segment must not be null");
            }
            if (segment.indexOf(NS_SEPARATOR.charAt(0)) >= 0) {
                throw new IllegalArgumentException(
                        "namespace segment must not contain the unit separator (0x1F)");
            }
            sb.append(segment).append(NS_SEPARATOR);
        }
        return sb.toString();
    }

    private String likePrefixPattern(List<String> namespace) {
        char esc = dialect.getLikeEscapeChar();
        StringBuilder sb = new StringBuilder();
        for (char ch : namespacePath(namespace).toCharArray()) {
            if (ch == esc || ch == '%' || ch == '_') {
                sb.append(esc);
            }
            sb.append(ch);
        }
        sb.append('%');
        return sb.toString();
    }

    private static void validateKey(String key) {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("key must not be null or empty");
        }
    }

    /**
     * 给定异常表示主键／唯一约束冲突时返回 {@code true}。各 JDBC 驱动的信号不同：
     * <ul>
     *   <li>MySQL / MariaDB / Postgres / H2 抛出 {@link SQLIntegrityConstraintViolationException}，或设置 SQLSTATE {@code 23xxx}。
     *   <li>SQLite 抛出普通 {@code SQLiteException}，其 {@code sqlState=null} 且 {@code errorCode=19}（{@code SQLITE_CONSTRAINT}）；还检查驱动类名，避免吞掉其他厂商恰好为 19 的无关错误码。
     * </ul>
     */
    private static boolean isDuplicateKey(SQLException e) {
        if (e instanceof SQLIntegrityConstraintViolationException) {
            return true;
        }
        String state = e.getSQLState();
        if (state != null && state.startsWith("23")) {
            // 23xxx 表示 SQL:2003 完整性约束冲突
            return true;
        }
        // SQLite 以 errorCode=19、SQLSTATE 为 null 报告约束冲突。
        String className = e.getClass().getName();
        return e.getErrorCode() == 19 && className.startsWith("org.sqlite.");
    }

    // -------------------------------------------------------------------------
    // 构建器
    // -------------------------------------------------------------------------

    /**
 * {@link JdbcStore} 的构建器。
 */
    public static final class Builder {

        private final DataSource dataSource;
        private JdbcStoreDialect dialect;
        private ObjectMapper objectMapper;
        private String tableName = DEFAULT_TABLE_NAME;
        private boolean initializeSchema;

        private Builder(DataSource dataSource) {
            this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        }

        /**
         * 显式设置方言。省略时，在 {@link #build()} 时通过 {@link JdbcStoreDialect#from(DataSource)} 自动检测。
         */
        public Builder dialect(JdbcStoreDialect dialect) {
            this.dialect = dialect;
            return this;
        }

        /**
 * 设置用于值序列化的自定义 Jackson {@link ObjectMapper}。
 */
        public Builder objectMapper(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
            return this;
        }

        /**
         * 覆盖表名，必须匹配正则 {@code [A-Za-z_][A-Za-z0-9_]*}。这是表标识符唯一直接进入 SQL 字符串的位置，因此此处验证是防 SQL 注入边界。
         */
        public Builder tableName(String tableName) {
            if (tableName == null
                    || tableName.isBlank()
                    || !VALID_TABLE_NAME.matcher(tableName).matches()) {
                throw new IllegalArgumentException(
                        "tableName must match [A-Za-z_][A-Za-z0-9_]*, got: " + tableName);
            }
            this.tableName = tableName;
            return this;
        }

        /**
         * 为 {@code true} 时，在构造期间执行方言的 {@code CREATE TABLE IF NOT EXISTS}。默认 {@code false}，生产部署通常使用迁移工具管理表结构。
         */
        public Builder initializeSchema(boolean initializeSchema) {
            this.initializeSchema = initializeSchema;
            return this;
        }

        /**
 * 构建 {@link JdbcStore}。
 */
        public JdbcStore build() {
            JdbcStore store = new JdbcStore(this);
            LOG.debug(
                    "JdbcStore built: table={}, dialect={}",
                    store.tableName,
                    store.dialect.getClass().getSimpleName());
            return store;
        }
    }
}
