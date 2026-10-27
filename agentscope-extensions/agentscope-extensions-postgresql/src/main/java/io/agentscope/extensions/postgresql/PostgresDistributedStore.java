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
package io.agentscope.extensions.postgresql;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.extensions.postgresql.sandbox.PostgresSandboxExecutionGuard;
import io.agentscope.extensions.postgresql.snapshot.PostgresSnapshotSpec;
import io.agentscope.extensions.postgresql.state.PostgresAgentStateStore;
import io.agentscope.extensions.postgresql.store.PostgresBaseStore;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * 由 PostgreSQL 支撑的 {@link DistributedStore}。
 *
 * <p>用法：
 * <pre>{@code
 * DataSource dataSource = ... // HikariCP、Druid 等。
 *
 * HarnessAgent agent = HarnessAgent.builder()
 *     .name("my-agent")
 *     .model("dashscope:qwen-plus")
 *     .distributedStore(PostgresDistributedStore.create(dataSource))
 *     .filesystem(new DockerFilesystemSpec()
 *             .image("ubuntu:24.04"))
 *     .build();
 * }</pre>
 *
 * <p>此配置包括：
 * <ul>
 *   <li>{@link PostgresAgentStateStore}：PostgreSQL 中的智能体会话状态</li>
 *   <li>{@link PostgresBaseStore}：PostgreSQL 中的工作区文件系统键值数据</li>
 *   <li>{@link PostgresSnapshotSpec}：以 BYTEA 保存在 PostgreSQL 中的沙箱快照</li>
 *   <li>{@link PostgresSandboxExecutionGuard}：通过 PostgreSQL 咨询锁实现分布式锁</li>
 * </ul>
 *
 * <p>目标数据库必须已存在；默认自动创建模式和表。
 *
 * @deprecated 请改用 {@code agentscope-extensions-jdbc} 模块中的 {@code io.agentscope.extensions.jdbc.JdbcDistributedStore}。新模块通过统一的多数据库方言抽象，在单个聚合接口下支持 MySQL、PostgreSQL、H2 和 SQLite。此类为向后兼容而保留，将在未来主版本移除。
 */
@Deprecated(since = "2.1", forRemoval = true)
public class PostgresDistributedStore implements DistributedStore {

    private final DataSource dataSource;

    private PostgresDistributedStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /**
     * 创建 PostgreSQL 分布式存储。
     *
     * @param dataSource PostgreSQL JDBC 数据源
     * @return 新 PostgreSQL 分布式存储
     */
    public static PostgresDistributedStore create(DataSource dataSource) {
        return new PostgresDistributedStore(dataSource);
    }

    @Override
    public AgentStateStore agentStateStore() {
        return new PostgresAgentStateStore(dataSource, true);
    }

    @Override
    public BaseStore baseStore() {
        return PostgresBaseStore.builder(dataSource).initializeSchema(true).build();
    }

    @Override
    public SandboxSnapshotSpec sandboxSnapshotSpec() {
        return new PostgresSnapshotSpec(dataSource);
    }

    @Override
    public SandboxExecutionGuard sandboxExecutionGuard() {
        return PostgresSandboxExecutionGuard.builder(dataSource).build();
    }
}
