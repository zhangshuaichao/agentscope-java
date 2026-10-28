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
package io.agentscope.extensions.mongodb.sandbox;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mongodb.MongoClientSettings;
import com.mongodb.MongoCommandException;
import com.mongodb.MongoWriteException;
import com.mongodb.ServerAddress;
import com.mongodb.WriteError;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.result.UpdateResult;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.sandbox.SandboxIsolationKey;
import io.agentscope.harness.agent.sandbox.SandboxLease;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.function.UnaryOperator;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class MongoSandboxExecutionGuardTest {

    @Mock private MongoClient mongoClient;
    @Mock private MongoDatabase mongoDatabase;
    @Mock private MongoCollection<Document> collection;

    @SuppressWarnings("rawtypes")
    @Mock
    private FindIterable findIterable;

    private AutoCloseable mocks;
    private final List<MongoSandboxExecutionGuard> guards = new ArrayList<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        when(mongoClient.getDatabase(anyString())).thenReturn(mongoDatabase);
        when(mongoDatabase.getCollection(anyString())).thenReturn(collection);

        when(collection.find(any(Bson.class))).thenReturn(findIterable);
        when(findIterable.projection(any())).thenReturn(findIterable);
        when(findIterable.first()).thenReturn(null);
    }

    @AfterEach
    void tearDown() throws Exception {
        // 关闭全部保护器实例，停止其续租执行线程
        for (MongoSandboxExecutionGuard guard : guards) {
            try {
                guard.close();
            } catch (Exception ignored) {
                // 尽力清理
            }
        }
        guards.clear();
        if (mocks != null) {
            mocks.close();
        }
    }

    private MongoSandboxExecutionGuard createGuard(
            UnaryOperator<MongoSandboxExecutionGuard.Builder> customizer) {
        MongoSandboxExecutionGuard.Builder builder =
                MongoSandboxExecutionGuard.builder(mongoClient);
        MongoSandboxExecutionGuard guard = customizer.apply(builder).build();
        guards.add(guard);
        return guard;
    }

    private MongoSandboxExecutionGuard createDefaultGuard() {
        return createGuard(b -> b);
    }

    private SandboxIsolationKey key() {
        return SandboxIsolationKey.resolve(
                        IsolationScope.SESSION,
                        new io.agentscope.core.agent.RuntimeContext.Builder()
                                .sessionId("session-1")
                                .build(),
                        "agent")
                .orElseThrow();
    }

    @Test
    void builderRejectsNullMongoClient() {
        assertThrows(NullPointerException.class, () -> MongoSandboxExecutionGuard.builder(null));
    }

    @Test
    void builderWithDefaultsCreatesGuard() {
        MongoSandboxExecutionGuard guard = createDefaultGuard();
        assertNotNull(guard);
    }

    @Test
    void builderWithCustomDatabaseName() {
        MongoSandboxExecutionGuard guard = createGuard(b -> b.databaseName("custom_db"));
        assertNotNull(guard);
    }

    @Test
    void builderWithCustomCollectionName() {
        MongoSandboxExecutionGuard guard = createGuard(b -> b.collectionName("custom_locks"));
        assertNotNull(guard);
    }

    @Test
    void builderWithCustomTimeout() {
        MongoSandboxExecutionGuard guard = createGuard(b -> b.lockTimeout(Duration.ofSeconds(5)));
        assertNotNull(guard);
    }

    @Test
    void builderRejectsNonPositiveTimeout() {
        assertThrows(
                IllegalArgumentException.class,
                () -> createGuard(b -> b.lockTimeout(Duration.ZERO)));
        assertThrows(
                IllegalArgumentException.class,
                () -> createGuard(b -> b.lockTimeout(Duration.ofSeconds(-1))));
    }

    @Test
    void builderWithCustomRetryInterval() {
        MongoSandboxExecutionGuard guard =
                createGuard(b -> b.retryInterval(Duration.ofMillis(200)));
        assertNotNull(guard);
    }

    @Test
    void builderRejectsNonPositiveRetryInterval() {
        assertThrows(
                IllegalArgumentException.class,
                () -> createGuard(b -> b.retryInterval(Duration.ZERO)));
        assertThrows(
                IllegalArgumentException.class,
                () -> createGuard(b -> b.retryInterval(Duration.ofMillis(-1))));
    }

    @Test
    void builderWithCustomOwner() {
        MongoSandboxExecutionGuard guard = createGuard(b -> b.owner("custom-owner"));
        assertNotNull(guard);
    }

    @Test
    void builderWithCustomLeaseTtl() {
        MongoSandboxExecutionGuard guard = createGuard(b -> b.leaseTtl(Duration.ofMinutes(10)));
        assertNotNull(guard);
    }

    @Test
    void builderRejectsNonPositiveLeaseTtl() {
        assertThrows(
                IllegalArgumentException.class, () -> createGuard(b -> b.leaseTtl(Duration.ZERO)));
        assertThrows(
                IllegalArgumentException.class,
                () -> createGuard(b -> b.leaseTtl(Duration.ofSeconds(-1))));
    }

    @Test
    void tryEnterAcquiresLockViaInsert() throws Exception {
        MongoSandboxExecutionGuard guard = createGuard(b -> b.lockTimeout(Duration.ofSeconds(5)));
        // insertOne 成功，无重复键，立即取得锁
        SandboxLease lease = guard.tryEnter(key());

        assertNotNull(lease);
        verify(collection).insertOne(any(Document.class));
    }

    @Test
    void tryEnterReclaimsExpiredLock() throws Exception {
        MongoSandboxExecutionGuard guard = createGuard(b -> b.lockTimeout(Duration.ofSeconds(10)));
        // 步骤 1：insertOne 因重复键失败，说明锁文档已存在
        WriteError dupError = new WriteError(11000, "duplicate key", new BsonDocument());
        when(collection.insertOne(any(Document.class)))
                .thenThrow(new MongoWriteException(dupError, new ServerAddress()));
        // 步骤 2：findOneAndUpdate 成功，说明已回收过期锁
        Document reclaimed =
                new Document("_id", "lock:abc")
                        .append("owner", "old-owner")
                        .append("expiresAt", new Date(0L));
        when(collection.findOneAndUpdate(
                        any(Bson.class), any(Bson.class), any(FindOneAndUpdateOptions.class)))
                .thenReturn(reclaimed);

        SandboxLease lease = guard.tryEnter(key());

        assertNotNull(lease);
        verify(collection).insertOne(any(Document.class));
        verify(collection)
                .findOneAndUpdate(
                        any(Bson.class), any(Bson.class), any(FindOneAndUpdateOptions.class));
    }

    @Test
    void tryEnterPollsWhenLockHeldThenAcquires() throws Exception {
        MongoSandboxExecutionGuard guard = createGuard(b -> b.lockTimeout(Duration.ofSeconds(10)));
        // insertOne 始终因重复键失败
        WriteError dupError = new WriteError(11000, "duplicate key", new BsonDocument());
        when(collection.insertOne(any(Document.class)))
                .thenThrow(new MongoWriteException(dupError, new ServerAddress()));
        // 首次 findOneAndUpdate 返回 null，锁未过期；第二次返回已回收文档
        Document reclaimed =
                new Document("_id", "lock:abc")
                        .append("owner", "old-owner")
                        .append("expiresAt", new Date(0L));
        when(collection.findOneAndUpdate(
                        any(Bson.class), any(Bson.class), any(FindOneAndUpdateOptions.class)))
                .thenReturn(null)
                .thenReturn(reclaimed);

        SandboxLease lease = guard.tryEnter(key());

        assertNotNull(lease);
        // insertOne 调用两次，每次轮询一次
        verify(collection, times(2)).insertOne(any(Document.class));
    }

    @Test
    void tryEnterTimesOutWhenLockNeverAcquired() {
        MongoSandboxExecutionGuard guard = createGuard(b -> b.lockTimeout(Duration.ofMillis(200)));
        // insertOne 始终因重复键失败
        WriteError dupError = new WriteError(11000, "duplicate key", new BsonDocument());
        when(collection.insertOne(any(Document.class)))
                .thenThrow(new MongoWriteException(dupError, new ServerAddress()));
        // findOneAndUpdate 始终返回 null，锁始终未过期
        when(collection.findOneAndUpdate(
                        any(Bson.class), any(Bson.class), any(FindOneAndUpdateOptions.class)))
                .thenReturn(null);

        assertThrows(InterruptedException.class, () -> guard.tryEnter(key()));
    }

    @Test
    void tryEnterPropagatesNonDuplicateKeyWriteException() {
        MongoSandboxExecutionGuard guard = createGuard(b -> b.lockTimeout(Duration.ofSeconds(5)));
        // insertOne 的非重复键写入错误应向外传播
        WriteError otherError = new WriteError(99999, "disk full", new BsonDocument());
        when(collection.insertOne(any(Document.class)))
                .thenThrow(new MongoWriteException(otherError, new ServerAddress()));

        assertThrows(RuntimeException.class, () -> guard.tryEnter(key()));
    }

    @Test
    void tryEnterPropagatesNonWriteException() {
        MongoSandboxExecutionGuard guard = createGuard(b -> b.lockTimeout(Duration.ofSeconds(5)));
        RuntimeException unexpected = new RuntimeException("connection lost");
        when(collection.insertOne(any(Document.class))).thenThrow(unexpected);

        assertThrows(RuntimeException.class, () -> guard.tryEnter(key()));
    }

    @Test
    void tryEnterReclaimsExpiredLockOnCommandException() throws Exception {
        MongoSandboxExecutionGuard guard = createGuard(b -> b.lockTimeout(Duration.ofSeconds(10)));
        // 部分 MongoDB 驱动版本以 MongoCommandException 报告重复键
        BsonDocument response = new BsonDocument();
        response.append("code", new BsonInt32(11000));
        response.append("errmsg", new BsonString("E11000 duplicate key error"));
        when(collection.insertOne(any(Document.class)))
                .thenThrow(new MongoCommandException(response, new ServerAddress()));
        Document reclaimed =
                new Document("_id", "lock:abc")
                        .append("owner", "old-owner")
                        .append("expiresAt", new Date(0L));
        when(collection.findOneAndUpdate(
                        any(Bson.class), any(Bson.class), any(FindOneAndUpdateOptions.class)))
                .thenReturn(reclaimed);

        SandboxLease lease = guard.tryEnter(key());

        assertNotNull(lease);
    }

    @Test
    void leaseCloseReleasesLockWithTokenCheck() throws Exception {
        MongoSandboxExecutionGuard guard = createGuard(b -> b.lockTimeout(Duration.ofSeconds(5)));
        SandboxLease lease = guard.tryEnter(key());
        lease.close();

        // deleteOne 应同时按 _id 和 token 过滤，而非 owner
        ArgumentCaptor<Bson> filterCaptor = ArgumentCaptor.forClass(Bson.class);
        verify(collection).deleteOne(filterCaptor.capture());

        String filterJson =
                filterCaptor
                        .getValue()
                        .toBsonDocument(
                                BsonDocument.class, MongoClientSettings.getDefaultCodecRegistry())
                        .toJson();
        assertTrue(filterJson.contains("token"), "release filter must be token-scoped");
    }

    @Test
    void leaseCloseHandlesReleaseFailure() throws Exception {
        MongoSandboxExecutionGuard guard = createGuard(b -> b.lockTimeout(Duration.ofSeconds(5)));
        SandboxLease lease = guard.tryEnter(key());
        doThrow(new RuntimeException("network error")).when(collection).deleteOne(any(Bson.class));

        // 不得抛错，close() 吞掉错误
        lease.close();
    }

    @Test
    void lockDocumentExpiryUsesLeaseTtlNotAcquisitionTimeout() throws Exception {
        MongoSandboxExecutionGuard guard =
                createGuard(
                        b -> b.lockTimeout(Duration.ofSeconds(1)).leaseTtl(Duration.ofMinutes(10)));

        SandboxLease lease = guard.tryEnter(key());
        lease.close();

        ArgumentCaptor<Document> docCaptor = ArgumentCaptor.forClass(Document.class);
        verify(collection).insertOne(docCaptor.capture());
        Date expiresAt = docCaptor.getValue().getDate("expiresAt");
        assertNotNull(expiresAt);

        long deltaMs = expiresAt.getTime() - System.currentTimeMillis();
        assertTrue(
                deltaMs > Duration.ofMinutes(9).toMillis(),
                "expiresAt must be ~leaseTtl (10 min) away, was " + deltaMs + "ms");
        assertTrue(
                deltaMs <= Duration.ofMinutes(10).toMillis() + 5_000,
                "expiresAt must not exceed leaseTtl (10 min), was " + deltaMs + "ms");
    }

    @Test
    void leaseRenewsPeriodicallyWhileOpen() throws Exception {
        UpdateResult renewResult = mock(UpdateResult.class);
        when(renewResult.getMatchedCount()).thenReturn(1L);
        when(collection.updateOne(any(Bson.class), any(Bson.class))).thenReturn(renewResult);

        // leaseTtl 为 300 毫秒，每 100 毫秒续租
        MongoSandboxExecutionGuard guard =
                createGuard(
                        b -> b.lockTimeout(Duration.ofSeconds(5)).leaseTtl(Duration.ofMillis(300)));
        SandboxLease lease = guard.tryEnter(key());
        try {
            ArgumentCaptor<Bson> filterCaptor = ArgumentCaptor.forClass(Bson.class);
            ArgumentCaptor<Bson> updateCaptor = ArgumentCaptor.forClass(Bson.class);
            verify(collection, timeout(3_000).atLeast(2))
                    .updateOne(filterCaptor.capture(), updateCaptor.capture());

            // 续租必须推进 expiresAt，且仅匹配自身锁文档
            String filterJson =
                    filterCaptor
                            .getValue()
                            .toBsonDocument(
                                    BsonDocument.class,
                                    MongoClientSettings.getDefaultCodecRegistry())
                            .toJson();
            assertTrue(filterJson.contains("token"), "renewal filter must be token-scoped");

            BsonDocument updateDoc =
                    updateCaptor
                            .getValue()
                            .toBsonDocument(
                                    BsonDocument.class,
                                    MongoClientSettings.getDefaultCodecRegistry());
            assertTrue(
                    updateDoc.getDocument("$set").containsKey("expiresAt"),
                    "renewal must extend expiresAt");
        } finally {
            lease.close();
        }
    }

    @Test
    void leaseCloseStopsRenewal() throws Exception {
        UpdateResult renewResult = mock(UpdateResult.class);
        when(renewResult.getMatchedCount()).thenReturn(1L);
        when(collection.updateOne(any(Bson.class), any(Bson.class))).thenReturn(renewResult);

        // leaseTtl 为 150 毫秒，每 50 毫秒续租
        MongoSandboxExecutionGuard guard =
                createGuard(
                        b -> b.lockTimeout(Duration.ofSeconds(5)).leaseTtl(Duration.ofMillis(150)));
        SandboxLease lease = guard.tryEnter(key());
        verify(collection, timeout(3_000).atLeast(2)).updateOne(any(Bson.class), any(Bson.class));

        lease.close();
        // 等待远超一个续租周期，使在途时钟回调结束；看门狗
        // 每 leaseTtl/3 = 50 毫秒续租，300 毫秒覆盖 6 个周期，未取消时
        // 会多续租约 6 次，足以区别噪声。
        Thread.sleep(300);
        // 宽限期后再捕获计数，验证不继续增长
        int renewalsAfterClose = countUpdateOneInvocations();

        // 多个续租周期内计数不得增加，说明 close() 已取消看门狗
        verify(collection, after(300).times(renewalsAfterClose))
                .updateOne(any(Bson.class), any(Bson.class));
    }

    @Test
    void renewalContinuesAfterLockLost() throws Exception {
        // matchedCount 为 0 表示锁被其他方回收；看门狗只能警告并继续，
        // 不能向外抛错使调度器终止。
        UpdateResult lostResult = mock(UpdateResult.class);
        when(lostResult.getMatchedCount()).thenReturn(0L);
        when(collection.updateOne(any(Bson.class), any(Bson.class))).thenReturn(lostResult);

        MongoSandboxExecutionGuard guard =
                createGuard(
                        b -> b.lockTimeout(Duration.ofSeconds(5)).leaseTtl(Duration.ofMillis(150)));
        SandboxLease lease = guard.tryEnter(key());
        try {
            verify(collection, timeout(3_000).atLeast(3))
                    .updateOne(any(Bson.class), any(Bson.class));
        } finally {
            lease.close();
        }
    }

    private int countUpdateOneInvocations() {
        return (int)
                org.mockito.Mockito.mockingDetails(collection).getInvocations().stream()
                        .filter(invocation -> invocation.getMethod().getName().equals("updateOne"))
                        .count();
    }
}
