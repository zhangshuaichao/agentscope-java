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
package io.agentscope.extensions.mysql.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * {@link MysqlAgentStateStore} 乐观并发版本控制单元测试。
 *
 * <p>以 Mockito 模拟 JDBC，无需真实 MySQL。覆盖 {@code saveIfVersion} 的 UNVERSIONED 路径及 {@code readVersion} 方法。
 */
@DisplayName("MysqlAgentStateStore versioning")
class MysqlAgentStateStoreTest {

    record TestState(String value) implements State {}

    @Mock private DataSource dataSource;
    @Mock private Connection connection;
    @Mock private PreparedStatement preparedStatement;
    @Mock private ResultSet resultSet;

    private AutoCloseable mocks;

    @BeforeEach
    void setUp() throws SQLException {
        mocks = MockitoAnnotations.openMocks(this);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(preparedStatement.executeUpdate()).thenReturn(1);
        // newStore() 使用 createIfNotExist=false，构造器依次执行
        // verifyDatabaseExists、verifyTableExists、ensureVersionColumn；前三次需要
        // resultSet.next() 为 true，之后默认 false，
        // 避免
        // 始终返回 true 的模拟结果集使读取循环无限运行。
        when(resultSet.next()).thenReturn(true, true, true, false);
        // ensureVersionColumn 的 COUNT(*) 返回 1，列已存在，无需 ALTER
        when(resultSet.getInt(1)).thenReturn(1);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (mocks != null) {
            mocks.close();
        }
    }

    private MysqlAgentStateStore newStore() {
        return new MysqlAgentStateStore(dataSource, "agentscope", "agentscope_sessions", false);
    }

    // ------------------------------------------------------------------
    // saveIfVersion UNVERSIONED：不得反序列化 State.class
    // ------------------------------------------------------------------

    @Test
    @DisplayName("saveIfVersion with UNVERSIONED returns readVersion without deserializing State")
    void saveIfVersionUnconditionalUsesReadVersion() throws SQLException {
        // UNVERSIONED 路径调用 save() 再调用 readVersion()；检查后者
        // 只读取 version 列，从不访问 state_data。
        MysqlAgentStateStore store = newStore();

        // save() 执行 INSERT ... ON DUPLICATE KEY UPDATE
        when(preparedStatement.executeUpdate()).thenReturn(1);
        // readVersion() 执行 SELECT version FROM ...
        when(resultSet.next()).thenReturn(true, true, true, true, true);
        when(resultSet.getLong("version")).thenReturn(42L);

        long version =
                store.saveIfVersion(
                        "user",
                        "session",
                        "agent_state",
                        new TestState("v"),
                        AgentStateStore.UNVERSIONED);

        assertEquals(42L, version);
        // 回归：readVersion 不得把 state_data 反序列化为 State.class
        verify(resultSet, never()).getString("state_data");
    }

    @Test
    @DisplayName("saveIfVersion with UNVERSIONED on absent key returns version 0")
    void saveIfVersionUnconditionalAbsentKeyReturnsZero() throws SQLException {
        // 键不存在时，readVersion 返回 0L。
        MysqlAgentStateStore store = newStore();

        // save() 成功
        when(preparedStatement.executeUpdate()).thenReturn(1);
        // readVersion() 的 rs.next() 返回 false，无行
        when(resultSet.next()).thenReturn(true, true, true, true, false);

        long version =
                store.saveIfVersion(
                        "user",
                        "session",
                        "absent_key",
                        new TestState("v"),
                        AgentStateStore.UNVERSIONED);

        assertEquals(0L, version);
        verify(resultSet, never()).getString("state_data");
    }

    // ------------------------------------------------------------------
    // readVersion 边界情况
    // ------------------------------------------------------------------

    @Test
    @DisplayName("readVersion returns version when row exists")
    void readVersionReturnsVersionWhenRowExists() throws SQLException {
        // saveIfVersion 的 UNVERSIONED 路径在内部覆盖 readVersion。
        MysqlAgentStateStore store = newStore();

        when(preparedStatement.executeUpdate()).thenReturn(1);
        when(resultSet.next()).thenReturn(true, true, true, true, true);
        when(resultSet.getLong("version")).thenReturn(7L);

        long version =
                store.saveIfVersion(
                        "user",
                        "session",
                        "agent_state",
                        new TestState("v"),
                        AgentStateStore.UNVERSIONED);

        assertEquals(7L, version);
    }

    @Test
    @DisplayName("saveIfVersion with UNVERSIONED propagates SQLException from readVersion")
    void saveIfVersionUnconditionalPropagatesReadVersionException() throws SQLException {
        MysqlAgentStateStore store = newStore();

        // save() 成功
        when(preparedStatement.executeUpdate()).thenReturn(1);
        // readVersion() 抛 SQLException
        when(resultSet.next()).thenReturn(true, true, true, true, true);
        when(resultSet.getLong("version")).thenThrow(new SQLException("read failed"));

        assertThrows(
                RuntimeException.class,
                () ->
                        store.saveIfVersion(
                                "user",
                                "session",
                                "agent_state",
                                new TestState("v"),
                                AgentStateStore.UNVERSIONED));
    }

    // ------------------------------------------------------------------
    // saveIfVersion expectedVersion=0：迁移回填行（#3162）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("saveIfVersion(0) falls back to CAS update when INSERT hits a duplicate key")
    void saveIfVersionZeroFallsBackToUpdateOnDuplicateKey() throws SQLException {
        // Issue #3162 回归：ALTER TABLE 迁移回填的版本 0 行
        // 与“行不存在”哨兵重合。INSERT 遇到主键冲突时，
        // 存储应回退到 UPDATE ... WHERE version = 0，
        // 而非报告虚假 CAS 冲突。
        MysqlAgentStateStore store = newStore();
        when(preparedStatement.executeUpdate())
                .thenThrow(new SQLException("Duplicate entry", "23000", 1062))
                .thenReturn(1);

        long newVersion =
                store.saveIfVersion("user", "session", "agent_state", new TestState("v"), 0L);

        assertEquals(1L, newVersion);
    }

    @Test
    @DisplayName("saveIfVersion(0) still reports conflict when the existing row is past version 0")
    void saveIfVersionZeroConflictWhenRowAlreadyVersioned() throws SQLException {
        // 回退 UPDATE 仅匹配版本 0；已有版本至少为 1 的行表示
        // 真实并发写入胜出，必须继续返回 UNVERSIONED。
        MysqlAgentStateStore store = newStore();
        when(preparedStatement.executeUpdate())
                .thenThrow(new SQLException("Duplicate entry", "23000", 1062))
                .thenReturn(0);

        long result = store.saveIfVersion("user", "session", "agent_state", new TestState("v"), 0L);

        assertEquals(AgentStateStore.UNVERSIONED, result);
    }

    @Test
    @DisplayName("auto-created sessions table pins a binary collation on session_id and state_key")
    void createTablePinsBinaryCollationOnKeyColumnsOnly() throws Exception {
        // 表默认 utf8mb4_unicode_ci 不区分大小写，否则仅大小写
        // 不同的用户 ID 会共享会话状态。
        //
        // createIfNotExist=true 时，构造器创建后检查存在性（next 为
        // true），再检查 INFORMATION_SCHEMA 版本列（next 为 true）。
        // 从捕获语句按内容而非位置找出 DDL，
        // 使断言不依赖构造器发出的语句数量。
        when(resultSet.next()).thenReturn(true, true, false);
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);

        new MysqlAgentStateStore(dataSource, "agentscope", "agentscope_sessions", true);

        verify(connection, atLeastOnce()).prepareStatement(sqlCaptor.capture());
        String ddl =
                sqlCaptor.getAllValues().stream()
                        .filter(sql -> sql.startsWith("CREATE TABLE"))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new AssertionError(
                                                "No CREATE TABLE was issued: "
                                                        + sqlCaptor.getAllValues()));

        assertTrue(ddl.contains("session_id VARCHAR(255) COLLATE utf8mb4_bin NOT NULL"));
        assertTrue(ddl.contains("state_key VARCHAR(255) COLLATE utf8mb4_bin NOT NULL"));
        assertTrue(ddl.contains("state_data LONGTEXT NOT NULL"));
        assertFalse(ddl.contains("LONGTEXT COLLATE"));
    }
}
