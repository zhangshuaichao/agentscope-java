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
package io.agentscope.extensions.jdbc.state;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.ListHashUtil;
import io.agentscope.core.state.State;
import io.agentscope.core.state.VersionedState;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.extensions.jdbc.dialect.BoundSql;
import io.agentscope.extensions.jdbc.dialect.table.SessionStateDialect;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Savepoint;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 由 {@link SessionStateDialect} 支持、与数据库种类无关的会话状态存储。
 *
 * <p>实现 {@link AgentStateStore}，不内联 SQL；每条语句都由方言通过 {@link BoundSql} 提供。
 *
 * @author shanhongyu
 */
public class JdbcAgentStateStore implements AgentStateStore {

    private static final Logger LOG = LoggerFactory.getLogger(JdbcAgentStateStore.class);

    private static final String HASH_KEY_SUFFIX = ":_hash";
    private static final int SINGLE_STATE_INDEX = 0;

    private final DataSource dataSource;
    private final SessionStateDialect dialect;

    /**
     * 创建存储，仅检查 null。表由 {@code AbstractJdbcDialect.from(dataSource).build()}
     * 一次性创建并验证；此存储不操作表结构。
     *
     * @param dataSource JDBC 数据源
     * @param dialect 会话状态方言
     */
    public JdbcAgentStateStore(DataSource dataSource, SessionStateDialect dialect) {
        this.dataSource = requireNonNull(dataSource, "dataSource");
        this.dialect = requireNonNull(dialect, "dialect");
    }

    // -------------------------------------------------------------------------
    // AgentStateStore 实现
    // -------------------------------------------------------------------------

    /**
     * 无条件保存单值状态。带版本写入使用同一 SQL 辅助方法，但不调用此公开方法；
     * 需要拦截写入的子类应同时覆盖本方法和 {@link #saveIfVersion}。
     *
     * <p>JSON 序列化在事务开始前执行，因此编解码错误直接传播，
     * 不会被包装成 JDBC 失败使用的 {@code "Failed to save state"} RuntimeException。
     */
    @Override
    public void save(String userId, String sessionId, String key, State value) {
        String slotId = slotId(userId, sessionId);
        validateSlotId(slotId);
        validateStateKey(key);
        String json = JsonUtils.getJsonCodec().toJson(value);
        executeInWriteTransaction(
                "Failed to save state: " + key,
                conn -> {
                    upsertSingleState(conn, slotId, key, json);
                    return null;
                });
    }

    @Override
    public void save(String userId, String sessionId, String key, List<? extends State> values) {
        String slotId = slotId(userId, sessionId);
        validateSlotId(slotId);
        validateStateKey(key);

        if (values.isEmpty()) {
            return;
        }

        String hashKey = key + HASH_KEY_SUFFIX;

        executeInWriteTransaction(
                "Failed to save list: " + key,
                conn -> {
                    String currentHash = ListHashUtil.computeHash(values);
                    String storedHash = getStoredHash(conn, slotId, hashKey);
                    int existingCount = getListCount(conn, slotId, key);
                    boolean needsFullRewrite =
                            ListHashUtil.needsFullRewrite(values, storedHash, existingCount);

                    if (needsFullRewrite) {
                        LOG.debug(
                                "List rewrite for key '{}': existing={}, incoming={}",
                                key,
                                existingCount,
                                values.size());
                        deleteListItems(conn, slotId, key);
                        insertItems(conn, slotId, key, values, 0);
                        saveHash(conn, slotId, hashKey, currentHash);
                    } else if (values.size() > existingCount) {
                        // 增量追加：已存哈希匹配传入列表前缀，
                        // 因此仅插入尾部。此处哈希碰撞会
                        // 使已存状态静默偏离，记录日志
                        // 便于排障。
                        LOG.debug(
                                "Incremental append for key '{}': appending {} items after"
                                        + " existing {}",
                                key,
                                values.size() - existingCount,
                                existingCount);
                        List<? extends State> newItems =
                                values.subList(existingCount, values.size());
                        insertItems(conn, slotId, key, newItems, existingCount);
                        saveHash(conn, slotId, hashKey, currentHash);
                    }
                    return null;
                });
    }

    @Override
    public boolean supportsVersioning() {
        return true;
    }

    @Override
    public <T extends State> VersionedState<T> getVersioned(
            String userId, String sessionId, String key, Class<T> type) {
        String slotId = slotId(userId, sessionId);
        validateSlotId(slotId);
        validateStateKey(key);

        BoundSql boundSql = dialect.sessionStateSelectVersioned(slotId, key, SINGLE_STATE_INDEX);
        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(boundSql.sql())) {
            bindParams(stmt, boundSql.params());
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return new VersionedState<>(null, 0L);
                }
                String json = rs.getString("state_data");
                long version = rs.getLong("version");
                return new VersionedState<>(JsonUtils.getJsonCodec().fromJson(json, type), version);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to get versioned state: " + key, e);
        }
    }

    private long readVersion(Connection conn, String slotId, String key) throws SQLException {
        // 仅读取版本列，绝不反序列化 state_data。不能反序列化到
        // `State` 标记接口（没有可构造的具体类型），因此读取
        // 版本不得触及载荷。
        BoundSql boundSql = dialect.sessionStateSelectVersioned(slotId, key, SINGLE_STATE_INDEX);
        try (PreparedStatement stmt = conn.prepareStatement(boundSql.sql())) {
            bindParams(stmt, boundSql.params());
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getLong("version") : 0L;
            }
        }
    }

    /**
     * 在单个数据库事务中写入并取得分配的版本。
     * 始终在释放写锁前获取版本，确保它属于本次调用刚写入的行。
     * {@code UNVERSIONED} 是无条件写入而非 CAS，但仍返回分配的版本。
     *
     * <p>{@code expectedVersion} 必须为 {@link AgentStateStore#UNVERSIONED}、{@code 0}
     * 或正版本；其他负值在任何数据库操作前由 {@link IllegalArgumentException} 拒绝。
     * 此检查和 JSON 序列化均在事务外进行，因此原样传播错误，
     * 不包装成 JDBC 失败使用的 {@code "Failed to save state if version"} RuntimeException。
     */
    @Override
    public long saveIfVersion(
            String userId, String sessionId, String key, State value, long expectedVersion) {
        String slotId = slotId(userId, sessionId);
        validateSlotId(slotId);
        validateStateKey(key);
        if (expectedVersion != UNVERSIONED && expectedVersion < 0L) {
            throw new IllegalArgumentException(
                    "expectedVersion must be UNVERSIONED, 0, or a positive version; got "
                            + expectedVersion);
        }
        String json = JsonUtils.getJsonCodec().toJson(value);

        if (expectedVersion == UNVERSIONED) {
            return executeInWriteTransaction(
                    "Failed to save state if version: " + key,
                    conn -> {
                        upsertSingleState(conn, slotId, key, json);
                        // 在同一个写事务内读取版本：
                        // 释放写锁前获取它，保证返回版本
                        // 属于本次写入行，而不是并发写入者的版本。
                        return readVersion(conn, slotId, key);
                    });
        }
        return executeInWriteTransaction(
                "Failed to save state if version: " + key,
                conn -> executeCasWrite(conn, slotId, key, json, expectedVersion));
    }

    /**
     * CAS 写入主体：{@code expectedVersion == 0} 时不存在才插入
     * （受保存点保护，并将回填的版本 0 提升到 1）；更高版本走带版本条件的 UPDATE。
     * 返回新版本；比较失败、未写入时返回 {@link AgentStateStore#UNVERSIONED}。
     * {@code saveIfVersion} 在分发前验证 {@code expectedVersion}，
     * 确保不是负值也不是哨兵，因此这里只有 {@code 0} 或正值。
     */
    private long executeCasWrite(
            Connection conn, String slotId, String key, String json, long expectedVersion)
            throws SQLException {
        if (expectedVersion > 0L) {
            return executeUpdateIfVersion(conn, slotId, key, json, expectedVersion);
        }
        BoundSql insertSql =
                dialect.sessionStateInsertIfAbsent(slotId, key, SINGLE_STATE_INDEX, json);
        // 用保存点保护 INSERT：Postgres 等数据库中的失败语句
        // 会使整个事务进入失败状态，否则下方回退 UPDATE 也无法继续。
        // 【学习提示】保存点只回退失败的插入尝试，而非整个写事务；回退后才可尝试版本 0 的更新。
        // 数据库 CAS 约束的是状态行，不会撤销本轮此前发生的工具或外部服务副作用。
        Savepoint savepoint = conn.setSavepoint("cas_insert_if_absent");
        try (PreparedStatement stmt = conn.prepareStatement(insertSql.sql())) {
            bindParams(stmt, insertSql.params());
            if (stmt.executeUpdate() == 1) {
                return 1L;
            }
        } catch (SQLException e) {
            if (!isDuplicateKey(e)) {
                throw e;
            }
            conn.rollback(savepoint);
        }
        // 行已存在。若其已存版本仍为 0（例如通过
        // ALTER TABLE 迁移回填），则满足 CAS，并提升到 1。若并发
        // 写入者已将版本推进到 0 之后，此语句不匹配任何行，正确返回
        // UNVERSIONED。
        return executeUpdateIfVersion(conn, slotId, key, json, 0L);
    }

    /**
     * 无条件单状态写入的唯一定义，由 save() 及 saveIfVersion() 的 UNVERSIONED 分支共用，
     * 避免两条路径逐渐偏离。
     */
    private void upsertSingleState(Connection conn, String slotId, String key, String json)
            throws SQLException {
        BoundSql boundSql = dialect.sessionStateUpsert(slotId, key, SINGLE_STATE_INDEX, json);
        try (PreparedStatement stmt = conn.prepareStatement(boundSql.sql())) {
            bindParams(stmt, boundSql.params());
            stmt.executeUpdate();
        }
    }

    private long executeUpdateIfVersion(
            Connection conn, String slotId, String key, String json, long expectedVersion)
            throws SQLException {
        BoundSql boundSql =
                dialect.sessionStateUpdateIfVersion(
                        slotId, key, SINGLE_STATE_INDEX, json, expectedVersion);
        try (PreparedStatement stmt = conn.prepareStatement(boundSql.sql())) {
            bindParams(stmt, boundSql.params());
            return stmt.executeUpdate() == 1 ? expectedVersion + 1L : UNVERSIONED;
        }
    }

    @Override
    public <T extends State> Optional<T> get(
            String userId, String sessionId, String key, Class<T> type) {
        String slotId = slotId(userId, sessionId);
        validateSlotId(slotId);
        validateStateKey(key);

        BoundSql boundSql = dialect.sessionStateSelect(slotId, key, SINGLE_STATE_INDEX);
        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(boundSql.sql())) {
            bindParams(stmt, boundSql.params());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(
                            JsonUtils.getJsonCodec().fromJson(rs.getString("state_data"), type));
                }
                return Optional.empty();
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to get state: " + key, e);
        }
    }

    @Override
    public <T extends State> List<T> getList(
            String userId, String sessionId, String key, Class<T> itemType) {
        String slotId = slotId(userId, sessionId);
        validateSlotId(slotId);
        validateStateKey(key);

        BoundSql boundSql = dialect.sessionStateSelectList(slotId, key);
        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(boundSql.sql())) {
            bindParams(stmt, boundSql.params());
            try (ResultSet rs = stmt.executeQuery()) {
                List<T> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(
                            JsonUtils.getJsonCodec()
                                    .fromJson(rs.getString("state_data"), itemType));
                }
                return result;
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to get list: " + key, e);
        }
    }

    @Override
    public boolean exists(String userId, String sessionId) {
        String slotId = slotId(userId, sessionId);
        validateSlotId(slotId);

        BoundSql boundSql = dialect.sessionStateExists(slotId);
        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(boundSql.sql())) {
            bindParams(stmt, boundSql.params());
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to check session existence: " + slotId, e);
        }
    }

    @Override
    public void delete(String userId, String sessionId) {
        String slotId = slotId(userId, sessionId);
        validateSlotId(slotId);
        executeInWriteTransaction(
                "Failed to delete session: " + slotId,
                conn -> {
                    BoundSql boundSql = dialect.sessionStateDeleteSession(slotId);
                    try (PreparedStatement stmt = conn.prepareStatement(boundSql.sql())) {
                        bindParams(stmt, boundSql.params());
                        stmt.executeUpdate();
                    }
                    return null;
                });
    }

    @Override
    public Set<String> listSessionIds(String userId) {
        String userSegment = normalizeUser(userId);
        String prefix = userSegment + ":";

        // 转义 LIKE 通配符（_、%）及转义字符本身，避免仅仅看起来像
        // 用户前缀的会话 ID（例如真实用户 "u_anon_x" 与匿名
        // 命名空间 "__anon__"）被错误模式匹配。
        BoundSql boundSql = dialect.sessionStateListSessionIds(likePrefixPattern(prefix));
        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(boundSql.sql())) {
            bindParams(stmt, boundSql.params());
            try (ResultSet rs = stmt.executeQuery()) {
                Set<String> sessionIds = new HashSet<>();
                while (rs.next()) {
                    String slot = rs.getString("session_id");
                    sessionIds.add(slot.substring(prefix.length()));
                }
                return sessionIds;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to list sessions", e);
        }
    }

    /**
     * 构造 {@code LIKE} 前缀模式，精确匹配 {@code prefix} 后的任意内容；
     * 转义每个 {@code _}、{@code %} 及方言转义字符，使用户片段按字面匹配。
     */
    private String likePrefixPattern(String prefix) {
        char esc = dialect.sessionStateLikeEscapeChar();
        StringBuilder sb = new StringBuilder();
        for (char ch : prefix.toCharArray()) {
            if (ch == esc || ch == '%' || ch == '_') {
                sb.append(esc);
            }
            sb.append(ch);
        }
        sb.append('%');
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // 内部辅助方法：列表状态操作
    // -------------------------------------------------------------------------

    private String getStoredHash(Connection conn, String slotId, String hashKey)
            throws SQLException {
        BoundSql boundSql = dialect.sessionStateSelect(slotId, hashKey, SINGLE_STATE_INDEX);
        try (PreparedStatement stmt = conn.prepareStatement(boundSql.sql())) {
            bindParams(stmt, boundSql.params());
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getString("state_data") : null;
            }
        }
    }

    private void saveHash(Connection conn, String slotId, String hashKey, String hash)
            throws SQLException {
        BoundSql boundSql = dialect.sessionStateUpsert(slotId, hashKey, SINGLE_STATE_INDEX, hash);
        try (PreparedStatement stmt = conn.prepareStatement(boundSql.sql())) {
            bindParams(stmt, boundSql.params());
            stmt.executeUpdate();
        }
    }

    private void deleteListItems(Connection conn, String slotId, String key) throws SQLException {
        BoundSql boundSql = dialect.sessionStateDeleteByKey(slotId, key);
        try (PreparedStatement stmt = conn.prepareStatement(boundSql.sql())) {
            bindParams(stmt, boundSql.params());
            stmt.executeUpdate();
        }
    }

    private void insertItems(
            Connection conn, String slotId, String key, List<? extends State> items, int startIndex)
            throws Exception {
        // 所有方言的 sessionStateInsert 返回相同 SQL 模板（仅参数不同），
        // 因此只准备一次并批量执行，对应 MySQL 模块的 addBatch/executeBatch 模式。
        String firstJson = JsonUtils.getJsonCodec().toJson(items.get(0));
        BoundSql firstBound = dialect.sessionStateInsert(slotId, key, startIndex, firstJson);
        try (PreparedStatement stmt = conn.prepareStatement(firstBound.sql())) {
            bindParams(stmt, firstBound.params());
            stmt.addBatch();
            int index = startIndex + 1;
            for (int i = 1; i < items.size(); i++) {
                String json = JsonUtils.getJsonCodec().toJson(items.get(i));
                BoundSql boundSql = dialect.sessionStateInsert(slotId, key, index, json);
                bindParams(stmt, boundSql.params());
                stmt.addBatch();
                index++;
            }
            stmt.executeBatch();
        }
    }

    private int getListCount(Connection conn, String slotId, String key) throws SQLException {
        BoundSql boundSql = dialect.sessionStateSelectMaxIndex(slotId, key);
        try (PreparedStatement stmt = conn.prepareStatement(boundSql.sql())) {
            bindParams(stmt, boundSql.params());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    int maxIndex = rs.getInt(1);
                    if (rs.wasNull()) {
                        return 0;
                    }
                    return maxIndex + 1;
                }
                return 0;
            }
        }
    }

    // -------------------------------------------------------------------------
    // 事务与验证辅助方法
    // -------------------------------------------------------------------------

    @FunctionalInterface
    private interface SqlCall<T> {
        T execute(Connection conn) throws Exception;
    }

    /**
     * 写入基础流程的唯一定义：打开连接，在写事务内执行调用，失败时回滚
     * （回滚失败以 suppressed 附加），恢复自动提交模式，并将失败包装为携带
     * {@code errorMessage} 的 {@link RuntimeException}。
     * 调用方只提供 SQL 工作与面向用户的错误前缀，各写入路径无需重复连接、事务和异常处理。
     */
    // 【学习提示】本方法自己获取并关闭连接，提交／回滚都属于这个局部事务。
    // 回滚异常作为 suppressed 保留，但 finally 中恢复 autoCommit 的异常没有同样保护，
    // 阅读错误传播时不要假设所有清理错误都不可能遮盖原始异常。
    private <T> T executeInWriteTransaction(String errorMessage, SqlCall<T> operation) {
        try (Connection conn = dataSource.getConnection()) {
            boolean originalAutoCommit = conn.getAutoCommit();
            if (originalAutoCommit) {
                conn.setAutoCommit(false);
            }
            try {
                T result = operation.execute(conn);
                conn.commit();
                return result;
            } catch (Exception e) {
                try {
                    conn.rollback();
                } catch (SQLException rollbackException) {
                    e.addSuppressed(rollbackException);
                }
                throw e;
            } finally {
                if (conn.getAutoCommit() != originalAutoCommit) {
                    conn.setAutoCommit(originalAutoCommit);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(errorMessage, e);
        }
    }

    private static void bindParams(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            ps.setObject(i + 1, params.get(i));
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

    private static final String ANON_USER = "__anon__";

    private static String normalizeUser(String userId) {
        return userId == null || userId.isBlank() ? ANON_USER : userId;
    }

    private static String slotId(String userId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        return normalizeUser(userId) + ":" + sessionId;
    }

    private static void validateSlotId(String slotId) {
        if (slotId == null || slotId.trim().isEmpty()) {
            throw new IllegalArgumentException("Session ID cannot be null or empty");
        }
        // 允许路径分隔符：槽位 ID 在这里是不透明的预编译语句绑定值，
        // 不是文件系统路径。SessionSandboxStateStore 合法地生成
        // 以斜杠分隔的槽位 ID（"sandbox/session/<id>"、"sandbox/user/<agentId>/<id>"）；
        // 拒绝它们曾导致 JDBC 后端静默丢失全部沙箱恢复状态（#3231）。
        // 基于路径的存储自行保证路径段安全，例如 JsonFileAgentStateStore 通过
        // safeSegment 编码每个片段。
        if (slotId.length() > 255) {
            throw new IllegalArgumentException("Session ID cannot exceed 255 characters");
        }
    }

    private static void validateStateKey(String key) {
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalArgumentException("State key cannot be null or empty");
        }
        if (key.length() > 255) {
            throw new IllegalArgumentException("State key cannot exceed 255 characters");
        }
    }

    private static <T> T requireNonNull(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return value;
    }
}
