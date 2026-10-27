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

import io.agentscope.extensions.jdbc.dialect.table.SnapshotDialect;
import io.agentscope.harness.agent.sandbox.snapshot.RemoteSnapshotSpec;
import javax.sql.DataSource;

/**
 * 面向 JDBC 快照存储的便捷
 * {@link io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec}。
 *
 * @author shanhongyu
 */
public class JdbcSnapshotSpec extends RemoteSnapshotSpec {

    /**
     * 创建快照配置。包装的客户端不操作表结构；表由
     * {@code AbstractJdbcDialect.from(dataSource).build()} 一次性创建并验证。
     *
     * @param dataSource JDBC 数据源
     * @param dialect 快照方言
     */
    public JdbcSnapshotSpec(DataSource dataSource, SnapshotDialect dialect) {
        super(new JdbcRemoteSnapshotClient(dataSource, dialect));
    }
}
