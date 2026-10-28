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
package io.agentscope.extensions.mysql.snapshot;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * {@link JdbcRemoteSnapshotClient} 生成的快照表 DDL 单元测试。
 *
 * <p>{@code snapshot_id} 是调用方提供的主键，其排序规则决定仅大小写不同的 ID 是两行还是同一行；后者会让第二次上传静默覆盖第一次。本测试无需真实 MySQL，检查实际发出的 DDL；运行行为由 agentscope-extensions-jdbc 中基于 Testcontainers 的 {@code MysqlIntegrationTest}（mysql:8.0）覆盖。
 */
@DisplayName("JdbcRemoteSnapshotClient snapshot table DDL")
class JdbcRemoteSnapshotClientTest {

    @Test
    @DisplayName("auto-created snapshot table pins a binary collation on the primary key")
    void initSchemaPinsBinaryCollation() throws Exception {
        String ddl = capturedInitDdl();

        assertTrue(
                ddl.contains("snapshot_id VARCHAR(512) COLLATE utf8mb4_bin NOT NULL PRIMARY KEY"),
                () -> "snapshot_id is not case-sensitive in: " + ddl);
        // 表同时声明字符集与引擎，使载荷列不依赖
        // 数据库模式的偶然默认值。
        assertTrue(ddl.contains("data LONGBLOB NOT NULL"));
        assertTrue(ddl.contains("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 ROW_FORMAT=DYNAMIC"));
    }

    @Test
    @DisplayName("only the key column is case-sensitive; the payload is left on the table default")
    void payloadStaysOnTableDefault() throws Exception {
        String ddl = capturedInitDdl();

        assertTrue(ddl.contains("data LONGBLOB NOT NULL"), () -> "unexpected payload DDL: " + ddl);
        assertFalse(
                ddl.contains("LONGBLOB COLLATE"),
                () -> "collation leaked onto the payload column: " + ddl);
    }

    /** 启用模式初始化运行构造器，返回其执行的 DDL。 */
    private static String capturedInitDdl() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);

        new JdbcRemoteSnapshotClient(dataSource, true);

        ArgumentCaptor<String> ddlCaptor = ArgumentCaptor.forClass(String.class);
        verify(statement).execute(ddlCaptor.capture());
        return ddlCaptor.getValue().replaceAll("\\s+", " ").trim();
    }
}
