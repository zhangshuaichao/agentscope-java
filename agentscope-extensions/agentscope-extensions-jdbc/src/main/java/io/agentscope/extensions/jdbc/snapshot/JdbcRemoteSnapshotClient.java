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
package io.agentscope.extensions.jdbc.snapshot;

import io.agentscope.extensions.jdbc.dialect.BoundSql;
import io.agentscope.extensions.jdbc.dialect.table.SnapshotDialect;
import io.agentscope.harness.agent.sandbox.snapshot.RemoteSnapshotClient;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * 由 JDBC BLOB 列支持的 {@link RemoteSnapshotClient}，不内联 SQL。
 *
 * <p>全部 SQL 来自 {@link SnapshotDialect}，支持 MySQL、PostgreSQL、H2 和 SQLite。
 *
 * @author shanhongyu
 */
public class JdbcRemoteSnapshotClient implements RemoteSnapshotClient {

    private final DataSource dataSource;
    private final SnapshotDialect dialect;

    /**
     * 创建客户端，仅检查 null。表由 {@code AbstractJdbcDialect.from(dataSource).build()}
     * 一次性创建和验证，此客户端不操作表结构。
     *
     * @param dataSource JDBC 数据源
     * @param dialect 快照方言
     */
    public JdbcRemoteSnapshotClient(DataSource dataSource, SnapshotDialect dialect) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.dialect = Objects.requireNonNull(dialect, "dialect");
    }

    @Override
    public void upload(String snapshotId, InputStream data) throws Exception {
        Objects.requireNonNull(data, "data");
        BoundSql boundSql = dialect.snapshotUpsert(snapshotId, data);
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(boundSql.sql())) {
            bindParams(ps, boundSql.params());
            ps.executeUpdate();
        }
    }

    @Override
    public InputStream download(String snapshotId) throws Exception {
        BoundSql boundSql = dialect.snapshotSelect(snapshotId);
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(boundSql.sql())) {
            bindParams(ps, boundSql.params());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new java.io.FileNotFoundException(
                            "Snapshot not found in database: " + snapshotId);
                }
                return new ByteArrayInputStream(rs.getBytes("data"));
            }
        }
    }

    @Override
    public boolean exists(String snapshotId) throws Exception {
        BoundSql boundSql = dialect.snapshotExists(snapshotId);
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(boundSql.sql())) {
            bindParams(ps, boundSql.params());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static void bindParams(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            Object param = params.get(i);
            if (param instanceof InputStream stream) {
                // 从调用方归档直接流式传输快照 BLOB，避免
                // 先缓冲到堆内存（参见 snapshotUpsert 的 Javadoc）。
                ps.setBinaryStream(i + 1, stream);
            } else {
                ps.setObject(i + 1, param);
            }
        }
    }
}
