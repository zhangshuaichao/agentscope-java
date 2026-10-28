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
package io.agentscope.extensions.mongodb.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mongodb.MongoClientSettings;
import com.mongodb.MongoWriteException;
import com.mongodb.ServerAddress;
import com.mongodb.WriteError;
import com.mongodb.client.AggregateIterable;
import com.mongodb.client.DistinctIterable;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.core.state.VersionedState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.bson.BsonDocument;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

class MongoAgentStateStoreTest {

    record TestState(String value) implements State {}

    static class TestMapState implements State {
        private Map<String, Object> attributes;

        @SuppressWarnings("unused")
        TestMapState() {}

        TestMapState(Map<String, Object> attributes) {
            this.attributes = attributes;
        }

        public Map<String, Object> getAttributes() {
            return attributes;
        }

        public void setAttributes(Map<String, Object> attributes) {
            this.attributes = attributes;
        }
    }

    @Mock private MongoClient mongoClient;
    @Mock private MongoDatabase mongoDatabase;
    @Mock private MongoCollection<Document> collection;

    @SuppressWarnings("rawtypes")
    @Mock
    private FindIterable findIterable;

    @SuppressWarnings("rawtypes")
    @Mock
    private AggregateIterable aggregateIterable;

    @SuppressWarnings("rawtypes")
    @Mock
    private DistinctIterable distinctIterable;

    private AutoCloseable mocks;
    private MongoAgentStateStore store;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        when(mongoClient.getDatabase(anyString())).thenReturn(mongoDatabase);
        when(mongoDatabase.getCollection(anyString())).thenReturn(collection);

        when(collection.find(any(Bson.class))).thenReturn(findIterable);
        when(findIterable.projection(any())).thenReturn(findIterable);
        when(findIterable.sort(any())).thenReturn(findIterable);
        when(findIterable.skip(ArgumentMatchers.anyInt())).thenReturn(findIterable);
        when(findIterable.limit(ArgumentMatchers.anyInt())).thenReturn(findIterable);
        when(findIterable.first()).thenReturn(null);

        // save(List) 现在使用 aggregate + $size，而非 find + projection
        when(collection.aggregate(any())).thenReturn(aggregateIterable);
        when(aggregateIterable.first()).thenReturn(null);

        UpdateResult updateResult = mock(UpdateResult.class);
        when(updateResult.wasAcknowledged()).thenReturn(true);
        when(collection.updateOne(any(Bson.class), any(Bson.class), any()))
                .thenReturn(updateResult);
        when(collection.updateOne(any(Bson.class), any(Bson.class))).thenReturn(updateResult);

        DeleteResult deleteResult = mock(DeleteResult.class);
        when(deleteResult.wasAcknowledged()).thenReturn(true);
        when(collection.deleteOne(any(Bson.class))).thenReturn(deleteResult);

        when(collection.countDocuments(any(Bson.class))).thenReturn(0L);

        when(collection.distinct(anyString(), any(Bson.class), any(Class.class)))
                .thenReturn(distinctIterable);
        when(distinctIterable.into(any())).thenReturn(new ArrayList<>());

        store =
                MongoAgentStateStore.builder()
                        .mongoClient(mongoClient)
                        .databaseName("testdb")
                        .collectionName("test_sessions")
                        .build();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (mocks != null) {
            mocks.close();
        }
    }

    @Test
    void supportsVersioningReturnsTrue() {
        assertTrue(store.supportsVersioning());
    }

    @Test
    void builderRejectsMissingClientAndConnectionString() {
        assertThrows(IllegalArgumentException.class, () -> MongoAgentStateStore.builder().build());
    }

    @Test
    void ttlDaysRejectsNonPositive() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        MongoAgentStateStore.builder()
                                .mongoClient(mongoClient)
                                .databaseName("testdb")
                                .collectionName("test_sessions")
                                .ttlDays(0)
                                .build());
    }

    @Test
    void builderWithMongoClientCreatesStore() {
        assertNotNull(store);
    }

    @Test
    void builderWithDefaultsCreatesStore() {
        MongoAgentStateStore defaultStore =
                MongoAgentStateStore.builder().mongoClient(mongoClient).build();
        assertNotNull(defaultStore);
    }

    @Test
    void saveSingleState() {
        store.save("user", "session", "key", new TestState("value"));
        verify(collection).updateOne(any(Bson.class), any(Bson.class), any());
    }

    @Test
    void getSingleStateReturnsEmptyWhenMissing() {
        Optional<TestState> result = store.get("user", "session", "key", TestState.class);
        assertTrue(result.isEmpty());
    }

    @Test
    void getSingleStateReturnsValueWhenPresent() {
        String json = "{\"value\":\"found\"}";
        Document doc = new Document("states", new Document("key", Document.parse(json)));
        when(findIterable.first()).thenReturn(doc);

        Optional<TestState> result = store.get("user", "session", "key", TestState.class);
        assertTrue(result.isPresent());
        assertEquals("found", result.get().value());
    }

    @Test
    void saveListState() {
        store.save("user", "session", "list", List.of(new TestState("a"), new TestState("b")));
        verify(collection).updateOne(any(Bson.class), any(Bson.class), any());
    }

    @Test
    void saveListAppendOnNewSessionIncludesSetOnInsert() {
        // 新会话尚无文档，首次写入非空列表：
        // needsFullRewrite(values, null, 0) 为 false，进入追加分支；
        // upsert 必须携带 $setOnInsert(user_id, session_id)，否则
        // 文档缺少会话标识，listSessionIds 无法发现，
        // 这正是代码评审发现的阻断问题。
        store.save("user", "session", "list", List.of(new TestState("a")));

        ArgumentCaptor<Bson> updateCaptor = ArgumentCaptor.forClass(Bson.class);
        verify(collection).updateOne(any(Bson.class), updateCaptor.capture(), any());

        BsonDocument updateDoc =
                updateCaptor
                        .getValue()
                        .toBsonDocument(
                                BsonDocument.class, MongoClientSettings.getDefaultCodecRegistry());
        assertTrue(updateDoc.containsKey("$push"), "expected the append branch ($push)");

        BsonDocument setOnInsert = updateDoc.getDocument("$setOnInsert");
        assertEquals("user", setOnInsert.getString("user_id").getValue());
        assertEquals("session", setOnInsert.getString("session_id").getValue());
    }

    @Test
    void saveListFullRewriteIncludesSetOnInsert() {
        // 已有文档列表缩短，进入完整重写分支；upsert 也必须
        // 携带 $setOnInsert，使恰好成为槽首次写入的重写
        // 仍记录会话标识。
        // 聚合返回 count=3 和已存哈希，列表从 3 项缩为 1 项，
        // 因此 needsFullRewrite 为 true（currentSize < existingCount）。
        Document aggResult = new Document("count", 3).append("storedHash", "old_hash_value");
        when(aggregateIterable.first()).thenReturn(aggResult);

        store.save("user", "session", "list", List.of(new TestState("x")));

        ArgumentCaptor<Bson> updateCaptor = ArgumentCaptor.forClass(Bson.class);
        verify(collection).updateOne(any(Bson.class), updateCaptor.capture(), any());

        BsonDocument updateDoc =
                updateCaptor
                        .getValue()
                        .toBsonDocument(
                                BsonDocument.class, MongoClientSettings.getDefaultCodecRegistry());
        assertTrue(updateDoc.containsKey("$set"), "expected the rewrite branch ($set)");

        BsonDocument setOnInsert = updateDoc.getDocument("$setOnInsert");
        assertEquals("user", setOnInsert.getString("user_id").getValue());
        assertEquals("session", setOnInsert.getString("session_id").getValue());
    }

    @Test
    void saveListShorteningPerformsFullRewrite() {
        // 模拟已有文档存储 3 项，聚合返回 count=3，
        // 保存 2 项短列表时 needsFullRewrite 为 true。
        Document aggResult = new Document("count", 3).append("storedHash", "old_hash_value");
        when(aggregateIterable.first()).thenReturn(aggResult);

        // 保存 2 项短列表，仍必须调用 updateOne 完整重写
        store.save("user", "session", "list", List.of(new TestState("x"), new TestState("y")));
        verify(collection).updateOne(any(Bson.class), any(Bson.class), any());
    }

    @Test
    void getListReturnsEmptyWhenMissing() {
        List<TestState> result = store.getList("user", "session", "list", TestState.class);
        assertTrue(result.isEmpty());
    }

    @Test
    void getListReturnsValuesWhenPresent() {
        List<Document> list =
                List.of(Document.parse("{\"value\":\"a\"}"), Document.parse("{\"value\":\"b\"}"));
        Document doc = new Document("states", new Document("list", list));
        when(findIterable.first()).thenReturn(doc);

        List<TestState> result = store.getList("user", "session", "list", TestState.class);
        assertEquals(2, result.size());
        assertEquals("a", result.get(0).value());
        assertEquals("b", result.get(1).value());
    }

    @Test
    void getVersionedReturnsZeroWhenMissing() {
        VersionedState<TestState> result =
                store.getVersioned("user", "session", "key", TestState.class);
        assertNotNull(result);
        assertEquals(0L, result.version());
    }

    @Test
    void getVersionedReturnsValueAndVersion() {
        String json = "{\"value\":\"v1\"}";
        Document doc =
                new Document("states", new Document("key", Document.parse(json)))
                        .append("versions", new Document("key", 5L));
        when(findIterable.first()).thenReturn(doc);

        VersionedState<TestState> result =
                store.getVersioned("user", "session", "key", TestState.class);
        assertEquals(5L, result.version());
        assertEquals("v1", result.value().value());
    }

    @Test
    void saveIfVersionWithUnversionedDelegatesToSave() {
        // UNVERSIONED 路径使用原子 findOneAndUpdate，而非 save 后 find
        Document result = new Document("versions", new Document("key", 42L));
        when(collection.findOneAndUpdate(
                        any(Bson.class), any(Bson.class), any(FindOneAndUpdateOptions.class)))
                .thenReturn(result);

        long version =
                store.saveIfVersion(
                        "user", "session", "key", new TestState("v"), AgentStateStore.UNVERSIONED);

        verify(collection)
                .findOneAndUpdate(
                        any(Bson.class), any(Bson.class), any(FindOneAndUpdateOptions.class));
        assertEquals(42L, version);
    }

    @Test
    void saveIfVersionZeroCreatesWhenAbsent() {
        // findOneAndUpdate 返回 versions.key=1，表示成功创建版本 1
        Document result = new Document("versions", new Document("key", 1L));
        when(collection.findOneAndUpdate(
                        any(Bson.class), any(Bson.class), any(FindOneAndUpdateOptions.class)))
                .thenReturn(result);

        long newVersion = store.saveIfVersion("user", "session", "key", new TestState("v"), 0L);

        assertEquals(1L, newVersion);
    }

    @Test
    void saveIfVersionZeroReturnsUnversionedWhenAlreadyExists() {
        // findOneAndUpdate 返回 versions.key=5，不匹配 expectedVersion=0
        Document result = new Document("versions", new Document("key", 5L));
        when(collection.findOneAndUpdate(
                        any(Bson.class), any(Bson.class), any(FindOneAndUpdateOptions.class)))
                .thenReturn(result);

        long newVersion = store.saveIfVersion("user", "session", "key", new TestState("v"), 0L);

        assertEquals(AgentStateStore.UNVERSIONED, newVersion);
    }

    @Test
    void saveIfVersionZeroReturnsUnversionedOnDuplicateKey() {
        // 带 upsert 的 findOneAndUpdate 抛出 E11000，表示文档已存在
        WriteError writeError =
                new WriteError(11000, "E11000 duplicate key error", new BsonDocument());
        when(collection.findOneAndUpdate(
                        any(Bson.class), any(Bson.class), any(FindOneAndUpdateOptions.class)))
                .thenThrow(new MongoWriteException(writeError, new ServerAddress()));

        long newVersion = store.saveIfVersion("user", "session", "key", new TestState("v"), 0L);

        assertEquals(AgentStateStore.UNVERSIONED, newVersion);
    }

    @Test
    void saveIfVersionUnversionedRetriesOnDuplicateKey() {
        // 首次 upsert 遇到并发首次插入的重复键，重试时文档已存在，
        // 作为更新成功并返回新版本。
        WriteError writeError =
                new WriteError(11000, "E11000 duplicate key error", new BsonDocument());
        Document result = new Document("versions", new Document("key", 42L));
        when(collection.findOneAndUpdate(
                        any(Bson.class), any(Bson.class), any(FindOneAndUpdateOptions.class)))
                .thenThrow(new MongoWriteException(writeError, new ServerAddress()))
                .thenReturn(result);

        long version =
                store.saveIfVersion(
                        "user", "session", "key", new TestState("v"), AgentStateStore.UNVERSIONED);

        assertEquals(42L, version);
        verify(collection, Mockito.times(2))
                .findOneAndUpdate(
                        any(Bson.class), any(Bson.class), any(FindOneAndUpdateOptions.class));
    }

    @Test
    void saveIfVersionReturnsNewVersionOnCasSuccess() {
        // findOneAndUpdate 返回版本已递增的文档
        Document result = new Document("versions", new Document("key", 3L));
        when(collection.findOneAndUpdate(
                        any(Bson.class), any(Bson.class), any(FindOneAndUpdateOptions.class)))
                .thenReturn(result);

        long newVersion =
                store.saveIfVersion("user", "session", "key", new TestState("updated"), 2L);

        assertEquals(3L, newVersion);
    }

    @Test
    void saveIfVersionReturnsUnversionedWhenCasFails() {
        // findOneAndUpdate 返回 null，表示版本不匹配
        when(collection.findOneAndUpdate(
                        any(Bson.class), any(Bson.class), any(FindOneAndUpdateOptions.class)))
                .thenReturn(null);

        long newVersion = store.saveIfVersion("user", "session", "key", new TestState("v"), 99L);

        assertEquals(AgentStateStore.UNVERSIONED, newVersion);
    }

    @Test
    void existsReturnsFalseWhenNoDocument() {
        when(findIterable.first()).thenReturn(null);
        assertFalse(store.exists("user", "session"));
    }

    @Test
    void existsReturnsTrueWhenDocumentExists() {
        when(findIterable.first())
                .thenReturn(
                        new Document(
                                "_id",
                                new Document("user", "__anon__").append("session", "session")));
        assertTrue(store.exists("user", "session"));
    }

    @Test
    void deleteSession() {
        store.delete("user", "session");
        verify(collection).deleteOne(any(Bson.class));
    }

    @Test
    void deleteKey() {
        store.delete("user", "session", "key");
        verify(collection).updateOne(any(Bson.class), any(Bson.class));
    }

    @Test
    void listSessionIdsReturnsEmptyWhenNone() {
        Set<String> ids = store.listSessionIds("user");
        assertTrue(ids.isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void listSessionIdsReturnsIds() {
        ArrayList<String> ids = new ArrayList<>(List.of("s1", "s2"));
        when(distinctIterable.into(any())).thenReturn(ids);

        Set<String> result = store.listSessionIds("user");
        assertEquals(2, result.size());
        assertTrue(result.contains("s1"));
        assertTrue(result.contains("s2"));
    }

    @Test
    void listSessionIdsForAnonymousUser() {
        // null userId 应规范化为 "__anon__" 并正确查询
        store.listSessionIds(null);
        verify(collection).distinct(eq("session_id"), any(Bson.class), eq(String.class));
    }

    @Test
    void listSessionIdsForBlankUser() {
        // 空白 userId 与 null 一样规范化为 "__anon__"
        store.listSessionIds("  ");
        verify(collection).distinct(eq("session_id"), any(Bson.class), eq(String.class));
    }

    @Test
    void rejectsNullSessionId() {
        assertThrows(
                IllegalArgumentException.class,
                () -> store.save("user", null, "key", new TestState("v")));
    }

    @Test
    void rejectsBlankSessionId() {
        assertThrows(
                IllegalArgumentException.class,
                () -> store.save("user", "  ", "key", new TestState("v")));
    }

    @Test
    void rejectsKeyWithDot() {
        assertThrows(
                IllegalArgumentException.class,
                () -> store.save("user", "session", "bad.key", new TestState("v")));
    }

    @Test
    void rejectsKeyWithDollar() {
        assertThrows(
                IllegalArgumentException.class,
                () -> store.save("user", "session", "$bad", new TestState("v")));
    }

    @Test
    void saveEscapesDotsInValueMapKeys() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("a.b", "val");
        store.save("user", "session", "key", new TestMapState(attributes));

        ArgumentCaptor<Bson> updateCaptor = ArgumentCaptor.forClass(Bson.class);
        verify(collection).updateOne(any(Bson.class), updateCaptor.capture(), any());

        String rendered = updateCaptor.getValue().toString();
        // "a.b" → "a\Eb"，点替换为反斜杠加 E
        assertTrue(rendered.contains("a\\Eb"), "dot in value key must be escaped: " + rendered);
        assertFalse(rendered.contains("a.b"), "raw dot must not leak into the document");
    }

    @Test
    void getUnescapesValueMapKeys() {
        Document escapedValue = new Document("attributes", new Document("a\\Eb", "hello"));
        Document doc = new Document("states", new Document("key", escapedValue));
        when(findIterable.first()).thenReturn(doc);

        Optional<TestMapState> result = store.get("user", "session", "key", TestMapState.class);
        assertTrue(result.isPresent());
        assertEquals("hello", result.get().getAttributes().get("a.b"));
    }

    @Test
    void rejectsBlankKey() {
        assertThrows(
                IllegalArgumentException.class,
                () -> store.save("user", "session", "  ", new TestState("v")));
    }

    @Test
    void closeWithExternalClientDoesNotCloseClient() {
        store.close();
        verify(mongoClient, Mockito.never()).close();
    }
}
