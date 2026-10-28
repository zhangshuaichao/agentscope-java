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
package io.agentscope.extensions.mongodb.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mongodb.MongoCommandException;
import com.mongodb.MongoWriteException;
import com.mongodb.ServerAddress;
import com.mongodb.WriteError;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.stubbing.Answer;

class MongoBaseStoreTest {

    @Mock private MongoDatabase mongoDatabase;
    @Mock private MongoCollection<Document> collection;

    @SuppressWarnings("rawtypes")
    @Mock
    private FindIterable findIterable;

    private AutoCloseable mocks;
    private MongoBaseStore store;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        when(mongoDatabase.getCollection(anyString())).thenReturn(collection);

        when(collection.find(any(Bson.class))).thenReturn(findIterable);
        when(findIterable.projection(any())).thenReturn(findIterable);
        when(findIterable.sort(any())).thenReturn(findIterable);
        when(findIterable.skip(anyInt())).thenReturn(findIterable);
        when(findIterable.limit(anyInt())).thenReturn(findIterable);
        when(findIterable.first()).thenReturn(null);
        when(findIterable.into(any())).thenReturn(new ArrayList<>());

        UpdateResult updateResult = mock(UpdateResult.class);
        when(updateResult.wasAcknowledged()).thenReturn(true);
        when(collection.updateOne(any(Bson.class), any(Bson.class), any()))
                .thenReturn(updateResult);
        when(collection.updateOne(any(Bson.class), any(Bson.class))).thenReturn(updateResult);

        DeleteResult deleteResult = mock(DeleteResult.class);
        when(deleteResult.wasAcknowledged()).thenReturn(true);
        when(collection.deleteOne(any(Bson.class))).thenReturn(deleteResult);

        store = new MongoBaseStore(mongoDatabase, "test_base");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (mocks != null) {
            mocks.close();
        }
    }

    @Test
    void constructorCreatesStore() {
        assertNotNull(store);
        verify(mongoDatabase).getCollection("test_base");
    }

    @Test
    void getReturnsNullWhenNotFound() {
        StoreItem item = store.get(List.of("ns"), "key");
        assertNull(item);
    }

    @Test
    void getReturnsItemWhenFound() {
        Document doc =
                new Document()
                        .append("key", "mykey")
                        .append("value", new Document("data", "hello"))
                        .append("version", 3L);
        when(findIterable.first()).thenReturn(doc);

        StoreItem item = store.get(List.of("ns"), "mykey");
        assertNotNull(item);
        assertEquals("mykey", item.key());
        assertEquals(3L, item.version());
        assertEquals("hello", item.value().get("data"));
    }

    @Test
    void putStoresItem() {
        store.put(List.of("ns"), "key", Map.of("data", "value"));
        verify(collection).updateOne(any(Bson.class), any(Bson.class), any());
    }

    @Test
    void putRetriesOnDuplicateKey() {
        // 首次 upsert 遇到并发首次插入的重复键，重试作为更新成功。
        WriteError writeError =
                new WriteError(11000, "E11000 duplicate key error", new BsonDocument());
        UpdateResult updateResult = mock(UpdateResult.class);
        when(collection.updateOne(any(Bson.class), any(Bson.class), any()))
                .thenThrow(new MongoWriteException(writeError, new ServerAddress()))
                .thenReturn(updateResult);

        store.put(List.of("ns"), "key", Map.of("data", "v"));

        verify(collection, times(2)).updateOne(any(Bson.class), any(Bson.class), any());
    }

    @Test
    void putRetriesOnDuplicateKeyCommandException() {
        // 部分 MongoDB 驱动对重复键抛出 MongoCommandException，
        // 而非 MongoWriteException；检查重试兼容二者。
        BsonDocument response = new BsonDocument();
        response.append("code", new BsonInt32(11000));
        response.append("errmsg", new BsonString("E11000 duplicate key error"));
        UpdateResult updateResult = mock(UpdateResult.class);
        when(collection.updateOne(any(Bson.class), any(Bson.class), any()))
                .thenThrow(new MongoCommandException(response, new ServerAddress()))
                .thenReturn(updateResult);

        store.put(List.of("ns"), "key", Map.of("data", "v"));

        verify(collection, times(2)).updateOne(any(Bson.class), any(Bson.class), any());
    }

    @Test
    void putIfVersionReturnsFalseWhenVersionMismatch() {
        // 版本过滤不匹配时 findOneAndUpdate 返回 null
        when(collection.findOneAndUpdate(
                        any(Bson.class), any(Bson.class), any(FindOneAndUpdateOptions.class)))
                .thenReturn(null);

        boolean result = store.putIfVersion(List.of("ns"), "key", Map.of("data", "v"), 5L);

        assertFalse(result);
    }

    @Test
    void putIfVersionZeroCreatesWhenAbsent() {
        // insertOne 成功，创建新文档
        boolean result = store.putIfVersion(List.of("ns"), "key", Map.of("data", "v"), 0L);

        assertTrue(result);
        verify(collection).insertOne(any(Document.class));
    }

    @Test
    void putIfVersionZeroReturnsFalseWhenAlreadyExists() {
        // insertOne 抛出 E11000 重复键，文档已存在
        WriteError writeError =
                new WriteError(11000, "E11000 duplicate key error", new BsonDocument());
        doThrow(new MongoWriteException(writeError, new ServerAddress()))
                .when(collection)
                .insertOne(any(Document.class));

        boolean result = store.putIfVersion(List.of("ns"), "key", Map.of("data", "v"), 0L);

        assertFalse(result);
    }

    @Test
    void putIfVersionZeroReturnsFalseOnCommandException() {
        // 部分 MongoDB 驱动以 MongoCommandException 报告重复键
        BsonDocument response = new BsonDocument();
        response.append("code", new BsonInt32(11000));
        response.append("errmsg", new BsonString("E11000 duplicate key error"));
        doThrow(new MongoCommandException(response, new ServerAddress()))
                .when(collection)
                .insertOne(any(Document.class));

        boolean result = store.putIfVersion(List.of("ns"), "key", Map.of("data", "v"), 0L);

        assertFalse(result);
    }

    @Test
    void putIfVersionZeroPropagatesNonDuplicateKeyError() {
        // insertOne 抛出非重复键错误，应传播
        WriteError writeError = new WriteError(12345, "some other error", new BsonDocument());
        doThrow(new MongoWriteException(writeError, new ServerAddress()))
                .when(collection)
                .insertOne(any(Document.class));

        assertThrows(
                MongoWriteException.class,
                () -> store.putIfVersion(List.of("ns"), "key", Map.of("data", "v"), 0L));
    }

    @Test
    void putIfVersionSuccessWhenVersionMatches() {
        // findOneAndUpdate 返回新版本为 expectedVersion + 1 的文档
        when(collection.findOneAndUpdate(
                        any(Bson.class), any(Bson.class), any(FindOneAndUpdateOptions.class)))
                .thenReturn(new Document("_id", "ns\0key").append("version", 3L));

        boolean result = store.putIfVersion(List.of("ns"), "key", Map.of("data", "v"), 2L);

        assertTrue(result);
    }

    @Test
    void searchReturnsEmptyList() {
        List<StoreItem> items = store.search(List.of("ns"), 10, 0);
        assertTrue(items.isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchReturnsItems() {
        Document doc1 =
                new Document()
                        .append("key", "a")
                        .append("value", new Document("x", "1"))
                        .append("version", 1L);
        Document doc2 =
                new Document()
                        .append("key", "b")
                        .append("value", new Document("x", "2"))
                        .append("version", 2L);
        ArrayList<Document> docs = new ArrayList<>(List.of(doc1, doc2));
        when(findIterable.into(any())).thenReturn(docs);

        List<StoreItem> items = store.search(List.of("ns"), 10, 0);
        assertEquals(2, items.size());
        assertEquals("a", items.get(0).key());
        assertEquals("b", items.get(1).key());
    }

    @Test
    void deleteRemovesItem() {
        store.delete(List.of("ns"), "key");
        verify(collection).deleteOne(any(Bson.class));
    }

    @Test
    void rejectsNullMongoDatabase() {
        assertThrows(NullPointerException.class, () -> new MongoBaseStore(null, "test"));
    }

    // ────────────────── 键转义测试 ──────────────────

    private Bson captureUpdateBson(Map<String, Object> value) {
        Bson[] captured = new Bson[1];
        doAnswer(
                        (Answer<Void>)
                                inv -> {
                                    captured[0] = inv.getArgument(1);
                                    return null;
                                })
                .when(collection)
                .updateOne(any(Bson.class), any(Bson.class), any());
        store.put(List.of("ns"), "k", value);
        return captured[0];
    }

    @Test
    void putEscapesDotInValueKeys() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("a.b", "val");
        Bson update = captureUpdateBson(value);
        String rendered = update.toString();
        // "a.b" → "a\Eb"，点替换为 \E
        assertTrue(rendered.contains("a\\Eb"), "Dot should be escaped: " + rendered);
    }

    @Test
    void putEscapesDollarInValueKeys() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("$meta", "val");
        Bson update = captureUpdateBson(value);
        String rendered = update.toString();
        // "$meta" → "\$meta"，美元符号前加反斜杠
        assertTrue(rendered.contains("\\$meta"), "Dollar should be escaped: " + rendered);
    }

    @Test
    void putEscapesBackslashInValueKeys() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("a\\b", "val");
        Bson update = captureUpdateBson(value);
        String rendered = update.toString();
        // "a\b" → "a\\b"，反斜杠加倍
        assertTrue(rendered.contains("a\\\\b"), "Backslash should be escaped: " + rendered);
    }

    @Test
    @SuppressWarnings("unchecked")
    void getUnescapesKeysRoundTrip() {
        // 模拟存有转义键的文档："a.b" 存为 "a\Eb"
        Document escapedValue = new Document("a\\Eb", "hello").append("\\$field", "world");
        Document doc =
                new Document()
                        .append("key", "k")
                        .append("value", escapedValue)
                        .append("version", 1L);
        reset(collection, findIterable);
        when(collection.createIndex(any(Bson.class), any())).thenReturn("_id_");
        when(collection.find(any(Bson.class))).thenReturn(findIterable);
        when(findIterable.projection(any())).thenReturn(findIterable);
        when(findIterable.first()).thenReturn(doc);
        when(collection.updateOne(any(Bson.class), any(Bson.class), any()))
                .thenReturn(mock(UpdateResult.class));
        when(collection.deleteOne(any(Bson.class))).thenReturn(mock(DeleteResult.class));

        MongoBaseStore freshStore = new MongoBaseStore(mongoDatabase, "test_base");
        StoreItem item = freshStore.get(List.of("ns"), "k");
        assertNotNull(item, "Item should be found");
        assertEquals("hello", item.value().get("a.b"), "Escaped key should be unescaped on read");
        assertEquals("world", item.value().get("$field"), "Escaped dollar should be unescaped");
    }

    @Test
    void putEscapesNestedMapKeys() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("inner.key", "deep");
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("outer", nested);
        Bson update = captureUpdateBson(value);
        String rendered = update.toString();
        // "inner.key" → "inner\Ekey"
        assertTrue(rendered.contains("inner\\Ekey"), "Nested keys should be escaped: " + rendered);
    }

    @Test
    void putEscapesNullCharacterInValueKeys() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("a\0b", "val");
        Bson update = captureUpdateBson(value);
        String rendered = update.toString();
        // "a\0b" → "a\Nb"，空字符替换为 \N
        assertTrue(rendered.contains("a\\Nb"), "Null character should be escaped: " + rendered);
    }

    @Test
    void putSerializesJavaTimeTypes() {
        // 共享 JsonCodec 注册了 JavaTimeModule；裸 new ObjectMapper()
        // 转换 java.time 类型时会抛 InvalidDefinitionException。注册后，
        // Instant 默认按 epoch 秒序列化，因为 WRITE_DATES_AS_TIMESTAMPS 默认为 true。
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("timestamp", Instant.parse("2026-09-10T10:00:00Z"));
        Bson update = captureUpdateBson(value);
        assertNotNull(update);
        assertTrue(
                update.toString().contains("1789034400"),
                "JavaTimeModule should serialize Instant: " + update);
    }
}
