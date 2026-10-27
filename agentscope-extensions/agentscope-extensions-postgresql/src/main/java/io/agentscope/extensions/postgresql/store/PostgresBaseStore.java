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
package io.agentscope.extensions.postgresql.store;

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
 * 由 PostgreSQL 支撑的 {@link BaseStore}。
 *
 * <h2>表结构</h2>
 * <pre>{@code
 * CREATE TABLE agentscope_store (
 *   namespace_path  VARCHAR(2048) NOT NULL,
 *   item_key        VARCHAR(255)  NOT NULL,
 *   value_json      TEXT          NOT NULL,
 *   version         BIGINT        NOT NULL,
 *   updated_at      BIGINT        NOT NULL,
 *   PRIMARY KEY (namespace_path, item_key)
 * )
 * }</pre>
 *
 * <p>可选 {@code Builder#initializeSchema(true)} 标记在构造时执行 {@code CREATE TABLE IF NOT EXISTS}。
 *
 * <h2>命名空间编码</h2>
 * <p>为保留 {@link InMemoryStore} 的前缀搜索行为，命名空间片段用 ASCII 单元分隔符 {@code 0x1F} 连接，并保留尾部分隔符。
 * 搜索命名空间 {@code [a, b]} 转为 {@code WHERE namespace_path LIKE 'ab%' ESCAPE '!'}，既匹配该命名空间，也匹配更深子空间。拒绝包含 {@code 0x1F} 的命名空间片段。
 *
 * <h2>CAS</h2>
 * <p>{@link #putIfVersion} 使用按 {@code version} 判断的单语句条件 {@code UPDATE}。
 * {@code expectedVersion == 0} 时执行 {@code INSERT}，将主键冲突视为 CAS 失败。两条路径无需额外锁即可保持原子性，可供多个 JVM 共享写入同一数据库。
 */
public class PostgresBaseStore implements BaseStore {

    private static final Logger LOG = LoggerFactory.getLogger(PostgresBaseStore.class);

    /**
     * {@code namespace_path} 内命名空间片段之间使用的 ASCII 单元分隔符（U+001F）。它不与可打印标识符冲突，且罕见于 JSON／用户输入。拒绝包含此字符的命名空间片段。
     */
    private static final String NS_SEPARATOR = "";

    /**
 * 未自定义构建器时使用的默认表名。
 */
    public static final String DEFAULT_TABLE_NAME = "agentscope_store";

    private static final Pattern VALID_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private static final String CREATE_TABLE_SQL =
            """
            CREATE TABLE IF NOT EXISTS %s (
                namespace_path VARCHAR(2048) NOT NULL,
                item_key       VARCHAR(255)  NOT NULL,
                value_json     TEXT          NOT NULL,
                version        BIGINT        NOT NULL,
                updated_at     BIGINT        NOT NULL,
                PRIMARY KEY (namespace_path, item_key)
            )
            """;

    private static final String SELECT_SQL =
            "SELECT value_json, version FROM %s WHERE namespace_path = ? AND item_key = ?";

    private static final String UPSERT_SQL =
            """
            INSERT INTO %s (namespace_path, item_key, value_json, version, updated_at)
            VALUES (?, ?, ?, 1, ?)
            ON CONFLICT (namespace_path, item_key) DO UPDATE SET
                value_json = EXCLUDED.value_json,
                version    = %1$s.version + 1,
                updated_at = EXCLUDED.updated_at
            """;

    private static final String INSERT_SQL =
            """
            INSERT INTO %s (namespace_path, item_key, value_json, version, updated_at)
            VALUES (?, ?, ?, 1, ?)
            """;

    private static final String CAS_UPDATE_SQL =
            """
            UPDATE %s SET
                value_json = ?,
                version    = version + 1,
                updated_at = ?
            WHERE namespace_path = ? AND item_key = ? AND version = ?
            """;

    private static final String DELETE_SQL =
            "DELETE FROM %s WHERE namespace_path = ? AND item_key = ?";

    private static final String SEARCH_SQL =
            """
            SELECT item_key, value_json, version FROM %s
            WHERE namespace_path LIKE ? ESCAPE '!'
            ORDER BY item_key
            LIMIT ? OFFSET ?
            """;

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;
    private final String schemaName;
    private final String tableName;
    private final String qualifiedTableName;

    private final String selectSql;
    private final String upsertSql;
    private final String insertSql;
    private final String casUpdateSql;
    private final String deleteSql;
    private final String searchSql;

    private PostgresBaseStore(Builder b) {
        this.dataSource = b.dataSource;
        this.objectMapper = b.objectMapper != null ? b.objectMapper : new ObjectMapper();
        this.schemaName = b.schemaName;
        this.tableName = b.tableName;
        this.qualifiedTableName = getQualifiedTableName(schemaName, tableName);
        this.selectSql = String.format(SELECT_SQL, qualifiedTableName);
        this.upsertSql = String.format(UPSERT_SQL, qualifiedTableName);
        this.insertSql = String.format(INSERT_SQL, qualifiedTableName);
        this.casUpdateSql = String.format(CAS_UPDATE_SQL, qualifiedTableName);
        this.deleteSql = String.format(DELETE_SQL, qualifiedTableName);
        this.searchSql = String.format(SEARCH_SQL, qualifiedTableName);
        if (b.initializeSchema) {
            initializeSchema();
        }
    }

    /**
 * 创建 {@link PostgresBaseStore} 构建器。
 */
    public static Builder builder(DataSource dataSource) {
        return new Builder(dataSource);
    }

    private void initializeSchema() {
        String ddl = String.format(CREATE_TABLE_SQL, qualifiedTableName);
        try (Connection c = dataSource.getConnection();
                Statement st = c.createStatement()) {
            if (schemaName != null) {
                st.executeUpdate("CREATE SCHEMA IF NOT EXISTS \"" + schemaName + "\"");
            }
            st.executeUpdate(ddl);
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Failed to initialize PostgresBaseStore schema for table " + qualifiedTableName,
                    e);
        }
    }

    private static String getQualifiedTableName(String schemaName, String tableName) {
        if (schemaName == null) {
            return tableName;
        }
        return "\"" + schemaName + "\".\"" + tableName + "\"";
    }

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
            throw new IllegalStateException("PostgresBaseStore get failed", e);
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
            throw new IllegalStateException("PostgresBaseStore put failed", e);
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
            try (Connection c = dataSource.getConnection();
                    PreparedStatement ps = c.prepareStatement(insertSql)) {
                ps.setString(1, nsPath);
                ps.setString(2, key);
                ps.setString(3, json);
                ps.setLong(4, now);
                ps.executeUpdate();
                return true;
            } catch (SQLException e) {
                if (isDuplicateKey(e)) {
                    return false;
                }
                throw new IllegalStateException(
                        "PostgresBaseStore putIfVersion (insert) failed", e);
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
            throw new IllegalStateException("PostgresBaseStore putIfVersion (update) failed", e);
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
                    String itemKey = rs.getString(1);
                    String json = rs.getString(2);
                    long version = rs.getLong(3);
                    result.add(new StoreItem(itemKey, deserialize(json), version));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("PostgresBaseStore search failed", e);
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
            throw new IllegalStateException("PostgresBaseStore delete failed", e);
        }
    }

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
        char esc = '!';
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

    private static boolean isDuplicateKey(SQLException e) {
        if (e instanceof SQLIntegrityConstraintViolationException) {
            return true;
        }
        String state = e.getSQLState();
        return state != null && state.startsWith("23");
    }

    /**
 * {@link PostgresBaseStore} 的构建器。
 */
    public static final class Builder {

        private final DataSource dataSource;
        private ObjectMapper objectMapper;
        private String schemaName;
        private String tableName = DEFAULT_TABLE_NAME;
        private boolean initializeSchema;

        private Builder(DataSource dataSource) {
            this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        }

        /**
 * 设置用于值序列化的自定义 Jackson {@link ObjectMapper}。
 */
        public Builder objectMapper(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
            return this;
        }

        /**
         * 设置使用的 PostgreSQL 模式名。未设置时，为兼容旧行为，SQL 使用不带限定符的表名。
         *
         * <p>提供的模式名必须非 null、非空白，且匹配 {@code [A-Za-z_][A-Za-z0-9_]*}。启用 {@link #initializeSchema(boolean)} 时创建模式。
         */
        public Builder schemaName(String schemaName) {
            this.schemaName = validateIdentifier(schemaName, "schemaName");
            return this;
        }

        /**
         * 覆盖表名，标识符必须匹配正则 {@code [A-Za-z_][A-Za-z0-9_]*}。
         *
         * <p>模式和表标识符会嵌入 SQL（配置模式时对标识符使用引用），因此此处验证是防 SQL 注入边界。
         */
        public Builder tableName(String tableName) {
            this.tableName = validateIdentifier(tableName, "tableName");
            return this;
        }

        /**
         * 为 {@code true} 时，在构造期间执行 {@code CREATE TABLE IF NOT EXISTS}。默认 {@code false}，生产部署通常使用迁移工具管理表结构。
         */
        public Builder initializeSchema(boolean initializeSchema) {
            this.initializeSchema = initializeSchema;
            return this;
        }

        /**
 * 构建 {@link PostgresBaseStore}。
 */
        public PostgresBaseStore build() {
            PostgresBaseStore store = new PostgresBaseStore(this);
            LOG.debug("PostgresBaseStore built: table={}", store.qualifiedTableName);
            return store;
        }

        private static String validateIdentifier(String identifier, String identifierName) {
            if (identifier == null
                    || identifier.isBlank()
                    || !VALID_IDENTIFIER.matcher(identifier).matches()) {
                throw new IllegalArgumentException(
                        identifierName + " must match [A-Za-z_][A-Za-z0-9_]*, got: " + identifier);
            }
            return identifier;
        }
    }
}
