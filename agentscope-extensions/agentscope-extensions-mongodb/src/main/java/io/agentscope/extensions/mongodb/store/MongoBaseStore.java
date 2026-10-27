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

import com.fasterxml.jackson.core.type.TypeReference;
import com.mongodb.MongoCommandException;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import io.agentscope.core.util.JsonCodec;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.extensions.mongodb.MongoIndexUtils;
import io.agentscope.extensions.mongodb.MongoKeyEscaper;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.bson.Document;
import org.bson.conversions.Bson;

/**
 * 由 MongoDB 支撑的 {@link BaseStore} 实现。
 *
 * <p>每个条目保存为独立 MongoDB 文档。命名空间路径与键使用 {@code \\u001F}（ASCII 单元分隔符）连接并编码成复合 {@code _id} 以确保唯一。
 * {@code namespace} 字段保存带尾部分隔符的完整命名空间路径，通过复合索引上的范围查询（{@code $gte}/{$lt}）进行前缀匹配，与 {@code PostgresBaseStore} 和 {@code JdbcStore} 一致。
 *
 * <p>通过 {@code version} 字段支持乐观并发控制。值序列化使用 {@link JsonCodec#convertValue}，避免中间 JSON 字符串往返。
 */
public class MongoBaseStore implements BaseStore {

    private static final String FIELD_ID = "_id";
    private static final String FIELD_KEY = "key";
    private static final String FIELD_NAMESPACE = "namespace";
    private static final String FIELD_VALUE = "value";
    private static final String FIELD_VERSION = "version";

    static final char NS_SEPARATOR = '\u001F';

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final MongoCollection<Document> collection;
    private final JsonCodec codec;

    /**
     * 使用共享 {@link JsonUtils#getJsonCodec()} 创建新实例。
     *
     * @param database MongoDB 数据库
     * @param collectionName 集合名
     */
    public MongoBaseStore(MongoDatabase database, String collectionName) {
        this(database, collectionName, JsonUtils.getJsonCodec());
    }

    /**
     * 使用自定义 {@link JsonCodec} 创建新实例。
     *
     * @param database MongoDB 数据库
     * @param collectionName 集合名
     * @param codec 用于值序列化的 JSON 编解码器
     */
    public MongoBaseStore(MongoDatabase database, String collectionName, JsonCodec codec) {
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(collectionName, "collectionName");
        this.collection = database.getCollection(collectionName);
        this.codec = codec;
        ensureIndexes();
    }

    @Override
    public StoreItem get(List<String> namespace, String key) {
        validateKey(key);
        String id = itemDocId(namespace, key);
        Document doc =
                collection
                        .find(Filters.eq(id))
                        .projection(Projections.include(FIELD_VALUE, FIELD_VERSION))
                        .first();
        if (doc == null) {
            return null;
        }
        Map<String, Object> value = parseValue(doc.get(FIELD_VALUE));
        long version = doc.containsKey(FIELD_VERSION) ? doc.getLong(FIELD_VERSION) : 0L;
        return new StoreItem(key, value, version);
    }

    @Override
    public void put(List<String> namespace, String key, Map<String, Object> value) {
        validateKey(key);
        String id = itemDocId(namespace, key);
        String nsKey = namespacePath(namespace);
        Document valueDoc = toDocument(value);
        Bson setFields =
                Updates.combine(Updates.set(FIELD_VALUE, valueDoc), Updates.inc(FIELD_VERSION, 1L));
        Bson setOnInsert =
                Updates.combine(
                        Updates.setOnInsert(FIELD_ID, id),
                        Updates.setOnInsert(FIELD_KEY, key),
                        Updates.setOnInsert(FIELD_NAMESPACE, nsKey));
        // 重复键错误 11000 只会发生在全新条目的首次并发插入：
        // 两个写入者对同一 _id 执行 upsert。因此重试一次，第二次
        // 时文档已经存在，upsert 退化为普通更新。
        int attempt = 0;
        while (true) {
            try {
                collection.updateOne(
                        Filters.eq(id), Updates.combine(setFields, setOnInsert), upsert());
                return;
            } catch (MongoWriteException e) {
                if (e.getError().getCode() != 11000 || attempt > 0) {
                    throw e;
                }
            } catch (MongoCommandException e) {
                if (e.getErrorCode() != 11000 || attempt > 0) {
                    throw e;
                }
            }
            attempt++;
        }
    }

    @Override
    public boolean supportsAtomicSessionStorage() {
        return true;
    }

    @Override
    public boolean putIfVersion(
            List<String> namespace, String key, Map<String, Object> value, long expectedVersion) {
        validateKey(key);
        String id = itemDocId(namespace, key);
        String nsKey = namespacePath(namespace);
        Document valueDoc = toDocument(value);

        Document result;
        if (expectedVersion == 0) {
            Document doc =
                    new Document(FIELD_ID, id)
                            .append(FIELD_VALUE, valueDoc)
                            .append(FIELD_KEY, key)
                            .append(FIELD_NAMESPACE, nsKey)
                            .append(FIELD_VERSION, 1L);
            try {
                collection.insertOne(doc);
                return true;
            } catch (MongoWriteException e) {
                if (e.getError().getCode() == 11000) {
                    return false;
                }
                throw e;
            } catch (MongoCommandException e) {
                if (e.getErrorCode() == 11000) {
                    return false;
                }
                throw e;
            }
        } else {
            Bson filter = Filters.and(Filters.eq(id), Filters.eq(FIELD_VERSION, expectedVersion));
            Bson update =
                    Updates.combine(
                            Updates.set(FIELD_VALUE, valueDoc),
                            Updates.set(FIELD_KEY, key),
                            Updates.set(FIELD_NAMESPACE, nsKey),
                            Updates.inc(FIELD_VERSION, 1L));
            result =
                    collection.findOneAndUpdate(
                            filter,
                            update,
                            new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        }

        return result != null;
    }

    /**
     * 搜索命名空间内的条目，包括子命名空间（前缀匹配）。
     *
     * <p>命名空间路径编码后带尾部分隔符，使范围查询 {@code gte(nsPrefix)} / {@code lt(nsPrefix + \uFFFF)} 同时匹配准确命名空间和所有后代子空间，与 {@code InMemoryStore}、{@code PostgresBaseStore}、{@code JdbcStore} 一致。
     *
     * <p><b>注意：</b>上界使用 {@code Character.MAX_VALUE}（{@code \uFFFF}），不匹配包含 Unicode 补充字符（高于 {@code U+FFFF} 的码点，例如 emoji）的命名空间片段。
     * 这是命名空间字符集的边界限制；范围查询能充分利用 B 树索引，不能仅凭常见 ASCII 名称测试就推断全部 Unicode 名称均可匹配。原实现以此区别于 {@code $regex} 前缀匹配。
     */
    @Override
    public List<StoreItem> search(List<String> namespace, int limit, int offset) {
        if (limit <= 0) {
            return List.of();
        }
        String nsPrefix = namespacePath(namespace);
        List<Document> docs =
                collection
                        .find(
                                Filters.and(
                                        Filters.gte(FIELD_NAMESPACE, nsPrefix),
                                        Filters.lt(
                                                FIELD_NAMESPACE, nsPrefix + Character.MAX_VALUE)))
                        .sort(Sorts.ascending(FIELD_KEY))
                        .skip(Math.max(offset, 0))
                        .limit(limit)
                        .projection(Projections.include(FIELD_KEY, FIELD_VALUE, FIELD_VERSION))
                        .into(new ArrayList<>());
        List<StoreItem> result = new ArrayList<>(docs.size());
        for (Document doc : docs) {
            String key = doc.getString(FIELD_KEY);
            Map<String, Object> value = parseValue(doc.get(FIELD_VALUE));
            long version = doc.containsKey(FIELD_VERSION) ? doc.getLong(FIELD_VERSION) : 0L;
            result.add(new StoreItem(key, value, version));
        }
        return result;
    }

    @Override
    public void delete(List<String> namespace, String key) {
        validateKey(key);
        String id = itemDocId(namespace, key);
        collection.deleteOne(Filters.eq(id));
    }

    // ────────────────── 内部辅助方法 ──────────────────

    private void ensureIndexes() {
        MongoIndexUtils.createIndexWithMigration(
                collection,
                Indexes.compoundIndex(
                        Indexes.ascending(FIELD_NAMESPACE), Indexes.ascending(FIELD_KEY)),
                new IndexOptions());
    }

    private static void validateKey(String key) {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("key must not be null or empty");
        }
        if (key.indexOf(NS_SEPARATOR) >= 0) {
            throw new IllegalArgumentException("key must not contain the unit separator (0x1F)");
        }
    }

    private static String itemDocId(List<String> namespace, String key) {
        return namespacePath(namespace) + key;
    }

    /**
     * 将命名空间路径编码为适合前缀匹配的字符串。用 {@code \\u001F}（ASCII 单元分隔符）连接片段，并附加尾部分隔符，使范围查询（{@code gte}/{@code lt}）既匹配准确命名空间，也匹配全部后代子空间，与 {@code PostgresBaseStore} 和 {@code JdbcStore} 一致。
     *
     * @throws NullPointerException namespace 为 null 时抛出
     * @throws IllegalArgumentException 任一片段为 null 或包含分隔字符时抛出
     */
    private static String namespacePath(List<String> namespace) {
        Objects.requireNonNull(namespace, "namespace must not be null");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < namespace.size(); i++) {
            String segment = namespace.get(i);
            if (segment == null) {
                throw new IllegalArgumentException("namespace segment must not be null");
            }
            if (segment.indexOf(NS_SEPARATOR) >= 0) {
                throw new IllegalArgumentException(
                        "namespace segment must not contain the unit separator (0x1F)");
            }
            if (i > 0) {
                sb.append(NS_SEPARATOR);
            }
            sb.append(segment);
        }
        sb.append(NS_SEPARATOR);
        return sb.toString();
    }

    private Document toDocument(Map<String, Object> value) {
        Map<String, Object> map = codec.convertValue(value == null ? Map.of() : value, MAP_TYPE);
        return new Document(MongoKeyEscaper.escape(map));
    }

    private Map<String, Object> parseValue(Object raw) {
        if (raw instanceof Document doc) {
            return MongoKeyEscaper.unescape(doc);
        }
        if (raw instanceof String s) {
            Map<String, Object> parsed = codec.fromJson(s, MAP_TYPE);
            return parsed != null ? parsed : Map.of();
        }
        throw new IllegalStateException(
                "Unexpected store value type: "
                        + (raw == null ? "null" : raw.getClass().getName()));
    }

    private static UpdateOptions upsert() {
        return new UpdateOptions().upsert(true);
    }
}
