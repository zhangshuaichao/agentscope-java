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
package io.agentscope.extensions.mysql;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.extensions.mysql.sandbox.JdbcSandboxExecutionGuard;
import io.agentscope.extensions.mysql.snapshot.JdbcSnapshotSpec;
import io.agentscope.extensions.mysql.state.MysqlAgentStateStore;
import io.agentscope.extensions.mysql.store.JdbcStore;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * 由 MySQL/JDBC 支撑的 {@link DistributedStore}。
 *
 * <p>用法：
 * <pre>{@code
 * DataSource dataSource = ... // HikariCP、Druid 等。
 *
 * HarnessAgent agent = HarnessAgent.builder()
 *     .name("my-agent")
 *     .model("dashscope:qwen-plus")
 *     .distributedStore(MysqlDistributedStore.create(dataSource))
 *     .filesystem(new DockerFilesystemSpec()
 *             .image("ubuntu:24.04"))
 *     .build();
 * }</pre>
 *
 * <p>此配置包括：
 * <ul>
 *   <li>{@link MysqlAgentStateStore}：MySQL 中的智能体会话状态</li>
 *   <li>{@link JdbcStore}：MySQL 中的工作区文件系统键值数据</li>
 *   <li>{@link JdbcSnapshotSpec}：以 BLOB 保存在 MySQL 中的沙箱快照</li>
 *   <li>{@link JdbcSandboxExecutionGuard}：通过 MySQL {@code GET_LOCK()} 实现分布式锁</li>
 * </ul>
 *
 * @deprecated 请改用 {@code agentscope-extensions-jdbc} 模块中的 {@code io.agentscope.extensions.jdbc.JdbcDistributedStore}。新模块通过统一的多数据库方言抽象，在单个聚合接口下支持 MySQL、PostgreSQL、H2 和 SQLite。此类为向后兼容而保留，将在未来主版本移除。
 */
@Deprecated(since = "2.1", forRemoval = true)
public class MysqlDistributedStore implements DistributedStore {

    private final DataSource dataSource;

    private MysqlDistributedStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /**
     * 创建 MySQL 分布式存储。
     *
     * @param dataSource MySQL JDBC 数据源
     * @return 新 MySQL 分布式存储
     */
    public static MysqlDistributedStore create(DataSource dataSource) {
        return new MysqlDistributedStore(dataSource);
    }

    @Override
    public AgentStateStore agentStateStore() {
        return new MysqlAgentStateStore(dataSource, true);
    }

    @Override
    public BaseStore baseStore() {
        return JdbcStore.builder(dataSource).initializeSchema(true).build();
    }

    @Override
    public SandboxSnapshotSpec sandboxSnapshotSpec() {
        return new JdbcSnapshotSpec(dataSource);
    }

    @Override
    public SandboxExecutionGuard sandboxExecutionGuard() {
        return JdbcSandboxExecutionGuard.builder(dataSource).build();
    }
}
