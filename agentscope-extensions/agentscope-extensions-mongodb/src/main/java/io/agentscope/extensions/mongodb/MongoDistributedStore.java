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
package io.agentscope.extensions.mongodb;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.extensions.mongodb.sandbox.MongoSandboxExecutionGuard;
import io.agentscope.extensions.mongodb.snapshot.MongoRemoteSnapshotClient;
import io.agentscope.extensions.mongodb.snapshot.MongoSnapshotSpec;
import io.agentscope.extensions.mongodb.state.MongoAgentStateStore;
import io.agentscope.extensions.mongodb.store.MongoBaseStore;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 由 MongoDB 支撑的 {@link DistributedStore}。
 *
 * <p>用法：
 * <pre>{@code
 * MongoClient mongoClient = MongoClients.create("mongodb://localhost:27017");
 *
 * HarnessAgent agent = HarnessAgent.builder()
 *     .name("my-agent")
 *     .model("dashscope:qwen-plus")
 *     .distributedStore(MongoDistributedStore.create(mongoClient, "agentscope"))
 *     .build();
 * }</pre>
 *
 * <p>此配置包括：
 * <ul>
 *   <li>{@link MongoAgentStateStore}：MongoDB 中的智能体会话状态
 *   <li>{@link MongoBaseStore}：MongoDB 中的工作区文件系统键值数据
 *   <li>{@link MongoSandboxExecutionGuard}：MongoDB 中的沙箱执行锁
 *   <li>{@link MongoSnapshotSpec}：MongoDB 中的沙箱工作区快照
 * </ul>
 *
 * <p>通过 {@link #create(MongoClient)} 创建时，调用方拥有 {@link MongoClient} 生命周期，{@link #close()} 不关闭客户端。
 * 通过 {@link #fromConnectionString(String)} 创建时，存储拥有客户端，{@link #close()} 会关闭它。
 * 两种情况下，{@link #close()} 都会级联关闭已缓存的 {@link MongoSandboxExecutionGuard} 执行器。
 */
public class MongoDistributedStore implements DistributedStore, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MongoDistributedStore.class);

    private final MongoClient mongoClient;
    private final boolean ownsClient;
    private final String databaseName;

    private volatile AgentStateStore cachedAgentStateStore;
    private volatile BaseStore cachedBaseStore;
    private volatile SandboxSnapshotSpec cachedSnapshotSpec;
    private volatile SandboxExecutionGuard cachedExecutionGuard;

    private MongoDistributedStore(MongoClient mongoClient, String databaseName) {
        this(mongoClient, databaseName, false);
    }

    private MongoDistributedStore(
            MongoClient mongoClient, String databaseName, boolean ownsClient) {
        this.mongoClient = Objects.requireNonNull(mongoClient, "mongoClient");
        this.ownsClient = ownsClient;
        this.databaseName = databaseName != null ? databaseName : MongoConstants.DEFAULT_DATABASE;
    }

    /**
     * 使用默认数据库名（{@code "agentscope"}）创建 MongoDB 分布式存储。
     *
     * @param mongoClient MongoDB 客户端
     * @return 新 MongoDB 分布式存储
     */
    public static MongoDistributedStore create(MongoClient mongoClient) {
        return new MongoDistributedStore(mongoClient, null);
    }

    /**
     * 创建 MongoDB 分布式存储。
     *
     * @param mongoClient MongoDB 客户端
     * @param databaseName 数据库名
     * @return 新 MongoDB 分布式存储
     */
    public static MongoDistributedStore create(MongoClient mongoClient, String databaseName) {
        return new MongoDistributedStore(mongoClient, databaseName);
    }

    /**
     * 根据连接字符串创建 MongoDB 分布式存储。内部新建 {@link MongoClient} 并由存储拥有；{@link #close()} 会关闭它。
     *
     * <p>连接字符串包含数据库路径时（例如 {@code "mongodb://host:27017/mydb"}），使用该数据库而非默认值。
     *
     * @param connectionString MongoDB 连接字符串
     * @return 新 MongoDB 分布式存储
     */
    public static MongoDistributedStore fromConnectionString(String connectionString) {
        ConnectionString cs = new ConnectionString(connectionString);
        MongoClientSettings settings =
                MongoClientSettings.builder().applyConnectionString(cs).build();
        return new MongoDistributedStore(MongoClients.create(settings), cs.getDatabase(), true);
    }

    @Override
    public AgentStateStore agentStateStore() {
        AgentStateStore result = cachedAgentStateStore;
        if (result == null) {
            synchronized (this) {
                result = cachedAgentStateStore;
                if (result == null) {
                    result =
                            MongoAgentStateStore.builder()
                                    .mongoClient(mongoClient)
                                    .databaseName(databaseName)
                                    .collectionName(MongoConstants.SESSIONS_COLLECTION)
                                    .onDeleteCallback(this::cascadeDeleteSnapshots)
                                    .build();
                    cachedAgentStateStore = result;
                }
            }
        }
        return result;
    }

    /**
     * 级联删除与被删除会话关联的沙箱快照。只有显式移除所属会话时才回收快照，与没有独立快照过期机制的 Postgres/JDBC/Redis 对齐。
     *
     * <p>仅 SESSION 隔离作用域可级联清理，因为该作用域沙箱与会话一一对应。USER/AGENT/GLOBAL 作用域在多个会话之间共享沙箱，不能因删除其中一个会话而回收。
     *
     * <p>此操作尽力而为：快照查找或删除失败时，会话仍会删除，并记录警告。由此遗留的孤立快照只能通过以后对同一快照 ID 显式调用 {@link MongoRemoteSnapshotClient#delete(String)} 回收，因为快照没有独立 TTL。
     */
    private void cascadeDeleteSnapshots(String userId, String sessionId) {
        try {
            String snapshotId = findSessionSnapshotId(sessionId);
            if (snapshotId == null) {
                return;
            }
            MongoRemoteSnapshotClient snapshotClient =
                    new MongoRemoteSnapshotClient(
                            mongoClient, databaseName, MongoConstants.SNAPSHOTS_COLLECTION);
            if (snapshotClient.delete(snapshotId)) {
                log.info(
                        "[mongo-cascade] Deleted snapshot '{}' for session {}",
                        snapshotId,
                        sessionId);
            }
        } catch (Exception e) {
            log.warn(
                    "[mongo-cascade] Failed to cascade-delete snapshots for session {}",
                    sessionId,
                    e);
        }
    }

    // ── 与 Harness 的 SessionSandboxStateStore 内部实现耦合 ──
    // 这些名称对应 io.agentscope.harness.agent.sandbox.SessionSandboxStateStore
    // 和 SandboxSnapshot，并非通过共享常量重新导出。如果
    // Harness 侧命名变化，级联清理会悄然变为空操作，
    // 快照便成为孤立数据。
    //
    // 注意：MongoIndexLifecycleContractTest 中的契约测试使用真实 MongoDB
    // 验证级联机制，但与此类使用相同的硬编码字符串。
    // 若 SessionSandboxStateStore 更改内部字段名，它无法检测这种偏差；
    // 需要穿过 Harness 模块的端到端测试才能检测，
    // 这超出当前模块范围。
    //
    // sandbox/session/<sessionId>：SESSION 作用域的合成 sessionId
    // _sandbox_state：序列化 SandboxState JSON 的状态键
    // snapshot.type == "remote"：远程与本地快照的判别标记
    // snapshot.id：GridFS 文件名（即沙箱 UUID）
    private static final String SANDBOX_SESSION_PREFIX = "sandbox/session/";
    private static final String SANDBOX_STATE_KEY = "_sandbox_state";

    private String findSessionSnapshotId(String sessionId) {
        Optional<SandboxSlotView> slot =
                agentStateStore()
                        .get(
                                null,
                                SANDBOX_SESSION_PREFIX + sessionId,
                                SANDBOX_STATE_KEY,
                                SandboxSlotView.class);
        if (slot.isEmpty() || slot.get().getJson() == null) {
            return null;
        }
        return extractRemoteSnapshotId(slot.get().getJson());
    }

    /**
 * 对应 {@code SessionSandboxStateStore.SandboxStateSlot} 的状态槽位视图。
 */
    private static class SandboxSlotView implements State {
        private String json;

        @SuppressWarnings("unused")
        SandboxSlotView() {}

        public String getJson() {
            return json;
        }

        public void setJson(String json) {
            this.json = json;
        }
    }

    @SuppressWarnings("unchecked")
    private static String extractRemoteSnapshotId(String json) {
        try {
            Map<String, Object> root = JsonUtils.getJsonCodec().fromJson(json, Map.class);
            Object snapshot = root.get("snapshot");
            if (!(snapshot instanceof Map<?, ?> snapMap)) {
                return null;
            }
            if (!"remote".equals(snapMap.get("type"))) {
                return null;
            }
            Object id = snapMap.get("id");
            return (id instanceof String s && !s.isBlank()) ? s : null;
        } catch (Exception e) {
            log.warn("[mongo-cascade] Failed to parse sandbox state JSON", e);
            return null;
        }
    }

    @Override
    public BaseStore baseStore() {
        BaseStore result = cachedBaseStore;
        if (result == null) {
            synchronized (this) {
                result = cachedBaseStore;
                if (result == null) {
                    MongoDatabase db = mongoClient.getDatabase(databaseName);
                    result = new MongoBaseStore(db, MongoConstants.BASE_STORE_COLLECTION);
                    cachedBaseStore = result;
                }
            }
        }
        return result;
    }

    @Override
    public SandboxSnapshotSpec sandboxSnapshotSpec() {
        SandboxSnapshotSpec result = cachedSnapshotSpec;
        if (result == null) {
            synchronized (this) {
                result = cachedSnapshotSpec;
                if (result == null) {
                    result = new MongoSnapshotSpec(mongoClient, databaseName);
                    cachedSnapshotSpec = result;
                }
            }
        }
        return result;
    }

    @Override
    public SandboxExecutionGuard sandboxExecutionGuard() {
        SandboxExecutionGuard result = cachedExecutionGuard;
        if (result == null) {
            synchronized (this) {
                result = cachedExecutionGuard;
                if (result == null) {
                    result =
                            MongoSandboxExecutionGuard.builder(mongoClient)
                                    .databaseName(databaseName)
                                    .collectionName(MongoConstants.SANDBOX_LOCKS_COLLECTION)
                                    .build();
                    cachedExecutionGuard = result;
                }
            }
        }
        return result;
    }

    @Override
    public void close() {
        if (cachedExecutionGuard instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                log.warn("Failed to close sandbox execution guard", e);
            }
        }
        if (ownsClient) {
            mongoClient.close();
        }
    }
}
