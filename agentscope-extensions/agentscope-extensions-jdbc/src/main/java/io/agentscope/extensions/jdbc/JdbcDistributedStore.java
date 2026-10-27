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
package io.agentscope.extensions.jdbc;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.extensions.jdbc.dialect.AbstractJdbcDialect;
import io.agentscope.extensions.jdbc.sandbox.JdbcSandboxExecutionGuard;
import io.agentscope.extensions.jdbc.snapshot.JdbcSnapshotSpec;
import io.agentscope.extensions.jdbc.state.JdbcAgentStateStore;
import io.agentscope.extensions.jdbc.store.JdbcStore;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * 支持多种数据库、自动检测方言的 JDBC {@link DistributedStore} 实现。
 *
 * <p>传入任意 JDBC {@link DataSource}，存储通过 {@link AbstractJdbcDialect#from(DataSource)}
 * 自动检测数据库，然后组装四个组件：
 *
 * <ul>
 *   <li>{@link JdbcAgentStateStore} — 智能体会话状态
 *   <li>{@link JdbcStore} — 工作区文件系统键值存储
 *   <li>{@link JdbcSnapshotSpec} — 以 BLOB 保存沙箱快照
 *   <li>{@link JdbcSandboxExecutionGuard} — 通过方言锁策略提供分布式锁
 * </ul>
 *
 * <h2>用法</h2>
 *
 * <pre>{@code
 * DataSource dataSource = ... // HikariCP、Druid 等。
 *
 * HarnessAgent agent = HarnessAgent.builder()
 *     .name("my-agent")
 *     .model("dashscope:qwen-plus")
 *     .distributedStore(JdbcDistributedStore.create(dataSource))
 *     .filesystem(new DockerFilesystemSpec()
 *             .image("ubuntu:24.04"))
 *     .build();
 * }</pre>
 *
 * @author shanhongyu
 */
public class JdbcDistributedStore implements DistributedStore {

    private final DataSource dataSource;
    private final AbstractJdbcDialect dialect;

    // 惰性缓存：DistributedStore 契约未限制访问器的调用次数，且
    // 组件构造不操作模式（表由方言构建器一次性创建并验证），
    // 因此 volatile 写入可安全发布这些普通值对象。
    private volatile AgentStateStore agentStateStore;
    private volatile BaseStore baseStore;
    private volatile SandboxSnapshotSpec sandboxSnapshotSpec;

    private JdbcDistributedStore(DataSource dataSource, AbstractJdbcDialect dialect) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.dialect = Objects.requireNonNull(dialect, "dialect");
        this.dialect.bindDataSource(dataSource);
    }

    /**
     * 创建自动检测方言并创建表的 JDBC 分布式存储。
     *
     * @param dataSource 任一支持数据库的 JDBC 数据源
     * @return 新的分布式存储
     */
    public static JdbcDistributedStore create(DataSource dataSource) {
        AbstractJdbcDialect dialect = AbstractJdbcDialect.from(dataSource).build();
        return new JdbcDistributedStore(dataSource, dialect);
    }

    /**
     * 使用明确指定的方言创建 JDBC 分布式存储。方言必须来自
     * {@code AbstractJdbcDialect.from(dataSource).build()}；表结构仅在那里创建和验证，
     * 此路径不操作表结构。
     *
     * @param dataSource JDBC 数据源
     * @param dialect 预先构建的方言（跳过自动检测）
     * @return 新的分布式存储
     */
    public static JdbcDistributedStore create(DataSource dataSource, AbstractJdbcDialect dialect) {
        return new JdbcDistributedStore(dataSource, dialect);
    }

    @Override
    public AgentStateStore agentStateStore() {
        AgentStateStore store = this.agentStateStore;
        if (store == null) {
            synchronized (this) {
                store = this.agentStateStore;
                if (store == null) {
                    store = new JdbcAgentStateStore(dataSource, dialect);
                    this.agentStateStore = store;
                }
            }
        }
        return store;
    }

    @Override
    public BaseStore baseStore() {
        BaseStore store = this.baseStore;
        if (store == null) {
            synchronized (this) {
                store = this.baseStore;
                if (store == null) {
                    store = JdbcStore.builder(dataSource).dialect(dialect).build();
                    this.baseStore = store;
                }
            }
        }
        return store;
    }

    @Override
    public SandboxSnapshotSpec sandboxSnapshotSpec() {
        SandboxSnapshotSpec spec = this.sandboxSnapshotSpec;
        if (spec == null) {
            synchronized (this) {
                spec = this.sandboxSnapshotSpec;
                if (spec == null) {
                    spec = new JdbcSnapshotSpec(dataSource, dialect);
                    this.sandboxSnapshotSpec = spec;
                }
            }
        }
        return spec;
    }

    @Override
    public SandboxExecutionGuard sandboxExecutionGuard() {
        return JdbcSandboxExecutionGuard.builder(dialect).build();
    }
}
