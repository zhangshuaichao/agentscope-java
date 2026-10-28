/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.extensions.mongodb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.extensions.mongodb.sandbox.MongoSandboxExecutionGuard;
import io.agentscope.extensions.mongodb.snapshot.MongoRemoteSnapshotClient;
import io.agentscope.extensions.mongodb.state.MongoAgentStateStore;
import io.agentscope.extensions.mongodb.store.MongoBaseStore;
import io.agentscope.extensions.mongodb.testutil.RequireDocker;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.bson.Document;
import org.bson.types.Binary;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 验证扩展创建的 MongoDB 集合具有正确索引参数，包括 TTL、稀疏标记及复合键。
 *
 * <p>覆盖 mock 单元／契约测试难以发现的问题：TTL=0 导致静默数据丢失，或升级时出现 IndexOptionsConflict。
 *
 * <p>通过 Testcontainers 启动真实 MongoDB，可在具备容器环境的 CI 执行。
 */
@Testcontainers
@RequireDocker
@DisplayName("Index lifecycle — MongoDB")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MongoIndexLifecycleContractTest {

    private static final long THIRTY_DAYS_SECONDS = 30L * 24 * 3600;

    @Container static final MongoDBContainer mongoContainer = new MongoDBContainer("mongo:7");

    private static MongoClient client;
    private static String dbName;

    @BeforeAll
    static void connect() {
        dbName = "test_idx_lifecycle_" + System.currentTimeMillis();
        client = MongoClients.create(mongoContainer.getConnectionString());
    }

    @AfterAll
    static void disconnect() {
        if (client != null) {
            client.getDatabase(dbName).drop();
            client.close();
        }
    }

    // ────────────────── AgentStateStore 索引 ──────────────────

    @Test
    @Order(1)
    @DisplayName("AgentStateStore: compound index on (user_id, session_id) exists")
    void agentStateStore_compoundIndex() {
        MongoAgentStateStore.builder()
                .mongoClient(client)
                .databaseName(dbName)
                .collectionName("idx_sessions")
                .build();

        Map<String, Document> indexes = indexMap(dbName, "idx_sessions");

        Document compound =
                indexes.values().stream()
                        .filter(
                                i -> {
                                    Object key = i.get("key");
                                    return key instanceof Document d
                                            && d.containsKey("user_id")
                                            && d.containsKey("session_id");
                                })
                        .findFirst()
                        .orElse(null);

        assertNotNull(compound, "Compound index (user_id, session_id) must exist");
    }

    @Test
    @Order(2)
    @DisplayName("AgentStateStore: no TTL index by default (aligned with Postgres/JDBC/Redis)")
    void agentStateStore_noTtlByDefault() {
        Map<String, Document> indexes = indexMap(dbName, "idx_sessions");

        Document ttlIndex = indexes.get("_updated_at_1");
        assertEquals(
                null,
                ttlIndex,
                "TTL index must NOT exist by default — sessions are retained indefinitely,"
                        + " aligned with Postgres/JDBC/Redis");
    }

    @Test
    @Order(3)
    @DisplayName("AgentStateStore: opt-in TTL creates index with specified days")
    void agentStateStore_optInTtl() {
        String ttlDb = "test_idx_ttl_optin_" + System.currentTimeMillis();
        String collName = "ttl_sessions";

        MongoAgentStateStore.builder()
                .mongoClient(client)
                .databaseName(ttlDb)
                .collectionName(collName)
                .ttlDays(30)
                .build();

        Map<String, Document> indexes = indexMap(ttlDb, collName);
        Document ttlIndex = indexes.get("_updated_at_1");
        assertNotNull(ttlIndex, "TTL index must exist when ttlDays=30");
        assertEquals(
                THIRTY_DAYS_SECONDS,
                ((Number) ttlIndex.get("expireAfterSeconds")).longValue(),
                "TTL must be 30 days (2592000s)");
        assertEquals(true, ttlIndex.getBoolean("sparse"), "TTL index must be sparse");

        // 清理
        client.getDatabase(ttlDb).drop();
    }

    // ────────────────── BaseStore 索引 ──────────────────

    @Test
    @Order(4)
    @DisplayName("BaseStore: compound index on (namespace, key) exists")
    void baseStore_compoundIndex() {
        new MongoBaseStore(client.getDatabase(dbName), "idx_base");

        Map<String, Document> indexes = indexMap(dbName, "idx_base");

        Document compound =
                indexes.values().stream()
                        .filter(
                                i -> {
                                    Object key = i.get("key");
                                    return key instanceof Document d
                                            && d.containsKey("namespace")
                                            && d.containsKey("key")
                                            && d.size() == 2;
                                })
                        .findFirst()
                        .orElse(null);

        assertNotNull(compound, "Compound index (namespace, key) must exist");
    }

    // ────────────────── SandboxExecutionGuard 索引 ──────────────────

    @Test
    @Order(5)
    @DisplayName("SandboxExecutionGuard: TTL index on expiresAt with immediate expiry (0s)")
    void sandboxGuard_ttlIndex_immediate() {
        MongoSandboxExecutionGuard.builder(client)
                .databaseName(dbName)
                .collectionName("idx_locks")
                .build();

        Map<String, Document> indexes = indexMap(dbName, "idx_locks");

        Document ttlIndex =
                indexes.values().stream()
                        .filter(
                                i -> {
                                    Object key = i.get("key");
                                    return key instanceof Document d && d.containsKey("expiresAt");
                                })
                        .findFirst()
                        .orElse(null);

        assertNotNull(ttlIndex, "TTL index on 'expiresAt' must exist");
        assertEquals(
                0L,
                ((Number) ttlIndex.get("expireAfterSeconds")).longValue(),
                "Lock TTL must be 0 (immediate expiry after expiresAt)");
    }

    // ────────────────── RemoteSnapshotClient 索引 ──────────────────

    @Test
    @Order(6)
    @DisplayName("RemoteSnapshotClient: no TTL index on snapshots (aligned with siblings)")
    void snapshotClient_noTtlIndex() throws Exception {
        MongoRemoteSnapshotClient snapshotClient =
                new MongoRemoteSnapshotClient(client, dbName, "idx_snapshots");
        // 创建 GridFS 集合以检查索引。
        snapshotClient.upload(
                "idx-snap", new ByteArrayInputStream("data".getBytes(StandardCharsets.UTF_8)));

        // GridFS 在 <bucket>.files 保存快照文件元数据，实际数据分块另存；
        // 文件元数据集合不能带会删除快照引用的 TTL 索引。
        assertFalse(
                hasTtlIndex(dbName, "idx_snapshots.files"),
                "GridFS files collection must NOT have any TTL index — aligned with"
                        + " Postgres/JDBC/Redis which have no independent snapshot expiry.");
        assertFalse(
                hasTtlIndex(dbName, "idx_snapshots"),
                "Legacy snapshot collection must NOT have any TTL index either.");
    }

    private static boolean hasTtlIndex(String database, String collection) {
        return indexMap(database, collection).values().stream()
                .anyMatch(i -> i.containsKey("expireAfterSeconds"));
    }

    @Test
    @Order(7)
    @DisplayName("Cascade: session delete removes the sandbox snapshot")
    void snapshot_cascadeDeletedWithSession() throws Exception {
        String cascadeDb = "test_cascade_" + System.currentTimeMillis();
        String snapshotId = "snap-" + System.currentTimeMillis();
        // 属于另一会话的无关快照，级联时必须保留。
        String unrelatedSnapshotId = "snap-other-" + System.currentTimeMillis();

        MongoDistributedStore store = MongoDistributedStore.create(client, cascadeDb);
        try {
            AgentStateStore stateStore = store.agentStateStore();

            // 按 SESSION 作用域的合成 sessionId 保存沙箱状态，模拟
            // SessionSandboxStateStore 保存序列化 SandboxState JSON 的方式。
            String sandboxJson =
                    "{\"type\":\"docker\",\"sessionId\":\""
                            + snapshotId
                            + "\",\"snapshot\":{\"type\":\"remote\",\"id\":\""
                            + snapshotId
                            + "\"}}";
            stateStore.save(
                    null,
                    "sandbox/session/sess1",
                    "_sandbox_state",
                    new SandboxSlotState(sandboxJson));

            // 与生产实现一样，通过双参数接口上传工作区快照。
            MongoRemoteSnapshotClient snapshotClient =
                    new MongoRemoteSnapshotClient(
                            client, cascadeDb, MongoConstants.SNAPSHOTS_COLLECTION);
            byte[] data = "workspace-tar".getBytes(StandardCharsets.UTF_8);
            snapshotClient.upload(snapshotId, new ByteArrayInputStream(data));

            // 上传未关联任何会话沙箱状态的无关快照。
            snapshotClient.upload(
                    unrelatedSnapshotId,
                    new ByteArrayInputStream("other".getBytes(StandardCharsets.UTF_8)));

            assertTrue(snapshotClient.exists(snapshotId), "Snapshot must exist after upload");
            assertTrue(
                    snapshotClient.exists(unrelatedSnapshotId),
                    "Unrelated snapshot must exist after upload");

            // 删除会话，级联清理必须移除关联快照。
            stateStore.delete("user1", "sess1");

            assertFalse(
                    snapshotClient.exists(snapshotId),
                    "Snapshot must be cascade-deleted when its owning session is removed");
            assertTrue(
                    snapshotClient.exists(unrelatedSnapshotId),
                    "Unrelated snapshot must NOT be affected by cascade delete");
        } finally {
            store.close();
            client.getDatabase(cascadeDb).drop();
        }
    }

    @Test
    @Order(8)
    @DisplayName("Legacy fallback: download reads old single-document snapshot format")
    void downloadFallsBackToLegacyFormat() throws Exception {
        String legacyDb = "test_legacy_" + System.currentTimeMillis();
        String snapshotId = "legacy-snap-" + System.currentTimeMillis();
        byte[] payload = "legacy-workspace-tar".getBytes(StandardCharsets.UTF_8);

        try {
            // 直接插入旧格式快照文档，即包含 {_id, data: Binary}
            // 的单个 BSON 文档，绕过 GridFS 上传路径。
            MongoCollection<Document> legacyColl =
                    client.getDatabase(legacyDb).getCollection(MongoConstants.SNAPSHOTS_COLLECTION);
            legacyColl.insertOne(
                    new Document("_id", snapshotId).append("data", new Binary(payload)));

            MongoRemoteSnapshotClient snapshotClient =
                    new MongoRemoteSnapshotClient(
                            client, legacyDb, MongoConstants.SNAPSHOTS_COLLECTION);

            assertTrue(
                    snapshotClient.exists(snapshotId),
                    "exists() must find legacy snapshot via fallback");
            assertTrue(
                    snapshotClient.exists("nonexistent-" + System.currentTimeMillis()) == false,
                    "exists() must return false when snapshot is absent everywhere");

            byte[] downloaded = snapshotClient.download(snapshotId).readAllBytes();
            assertEquals(
                    payload.length,
                    downloaded.length,
                    "download() must read legacy snapshot via fallback path");
        } finally {
            client.getDatabase(legacyDb).drop();
        }
    }

    /** 与 {@code SessionSandboxStateStore.SandboxStateSlot} 对应的状态槽结构。 */
    private static class SandboxSlotState implements State {
        private String json;
        private boolean deleted;

        @SuppressWarnings("unused")
        SandboxSlotState() {}

        SandboxSlotState(String json) {
            this.json = json;
        }

        public String getJson() {
            return json;
        }

        public boolean getDeleted() {
            return deleted;
        }
    }

    // ────────────────── 辅助方法 ──────────────────

    private static Map<String, Document> indexMap(String database, String collection) {
        MongoCollection<Document> coll = client.getDatabase(database).getCollection(collection);
        List<Document> idxDocs = new ArrayList<>();
        coll.listIndexes().into(idxDocs);
        return idxDocs.stream().collect(Collectors.toMap(d -> d.getString("name"), d -> d));
    }
}
