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

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoCommandException;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.ListHashUtil;
import io.agentscope.core.state.State;
import io.agentscope.core.state.VersionedState;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.extensions.mongodb.MongoConstants;
import io.agentscope.extensions.mongodb.MongoIndexUtils;
import io.agentscope.extensions.mongodb.MongoKeyEscaper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 由 MongoDB 支撑的 {@link AgentStateStore} 实现。
 *
 * <p>每个会话保存为单个 MongoDB 文档，使用 {@code {user, session}} 复合 {@code _id} 避免跨租户键碰撞。
 * 状态键保存在 {@code states} 子文档中（点号表示法，例如 {@code states.foo}），与 {@code _id}、{@code user_id}、{@code session_id} 和 {@code _updated_at} 等保留顶层字段隔离。
 * 各键版本位于 {@code versions} 子文档，列表内容哈希位于 {@code hashes} 子文档。
 *
 * <p>通过 {@code versions.<key>} 字段支持乐观并发控制。列表状态使用 {@link ListHashUtil} 检测变化，避免不必要的完整重写。
 * 元素数量由服务端 {@code $size} 聚合获得，避免仅为调用 {@code .size()} 而传输整个数组。
 *
 * <p>用法：
 * <pre>{@code
 * MongoAgentStateStore store = MongoAgentStateStore.builder()
 *     .connectionString("mongodb://localhost:27017")
 *     .databaseName("agentscope")
 *     .collectionName("sessions")
 *     .build();
 * }</pre>
 *
 * <h2>键约束</h2>
 * <ul>
 *   <li><b>状态键字符集：</b>传给 {@link #save} / {@link #get} 的键必须匹配 {@code ^[a-zA-Z_][a-zA-Z0-9_]*$}，不允许点号、美元符号或 Unicode。{@code Map<String, Object>} 字段内部的键由 {@link MongoKeyEscaper} 自动转义，不受此状态键字符集约束。
 *   <li><b>快照使用 GridFS：</b>沙箱工作区快照保存在 GridFS，而非单个 BSON 文档，因此不再受单文档 16 MiB 限制。实际容量约束见 MongoRemoteSnapshotClient。
 *   <li><b>默认不启用会话 TTL：</b>与 Postgres/JDBC/Redis 对齐，无限期保留。传入正整数 {@code MongoAgentStateStore.builder().ttlDays(n)} 可启用自动过期。TTL 索引为稀疏索引，仅具有非 null 的 {@code _updated_at} 字段的文档参与过期处理。
 * </ul>
 */
public class MongoAgentStateStore implements AgentStateStore, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MongoAgentStateStore.class);

    private static final String ANON_USER = "__anon__";
    private static final String FIELD_USER_ID = "user_id";
    private static final String FIELD_SESSION_ID = "session_id";
    private static final String FIELD_UPDATED_AT = "_updated_at";
    private static final String FIELD_STATES = "states";
    private static final String FIELD_VERSIONS = "versions";
    private static final String FIELD_HASHES = "hashes";
    private static final Pattern SAFE_KEY_PATTERN = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

    private final MongoClient mongoClient;
    private final boolean ownsClient;
    private final MongoCollection<Document> collection;
    private final BiConsumer<String, String> onDeleteCallback;

    private MongoAgentStateStore(Builder builder) {
        if (builder.mongoClient != null) {
            this.mongoClient = builder.mongoClient;
            this.ownsClient = false;
        } else if (builder.connectionString != null) {
            MongoClientSettings settings =
                    MongoClientSettings.builder()
                            .applyConnectionString(new ConnectionString(builder.connectionString))
                            .build();
            this.mongoClient = MongoClients.create(settings);
            this.ownsClient = true;
        } else {
            throw new IllegalArgumentException(
                    "Either mongoClient or connectionString must be provided");
        }

        String dbName;
        if (builder.databaseName != null) {
            dbName = builder.databaseName;
        } else if (builder.connectionString != null) {
            String uriDb = new ConnectionString(builder.connectionString).getDatabase();
            dbName = uriDb != null ? uriDb : MongoConstants.DEFAULT_DATABASE;
        } else {
            dbName = MongoConstants.DEFAULT_DATABASE;
        }
        String collName =
                builder.collectionName != null
                        ? builder.collectionName
                        : MongoConstants.SESSIONS_COLLECTION;

        MongoDatabase db = this.mongoClient.getDatabase(dbName);
        this.collection = db.getCollection(collName);
        this.onDeleteCallback = builder.onDeleteCallback;

        ensureIndexes(builder.ttlDays);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public boolean supportsVersioning() {
        return true;
    }

    // ────────────────── 索引管理 ──────────────────

    private void ensureIndexes(Integer ttlDays) {
        MongoIndexUtils.createIndexWithMigration(
                collection,
                Indexes.compoundIndex(
                        Indexes.ascending(FIELD_USER_ID), Indexes.ascending(FIELD_SESSION_ID)),
                new IndexOptions());

        // 会话 TTL 须显式启用，与 Postgres/JDBC/Redis 对齐（默认不强制过期）。
        if (ttlDays != null && ttlDays > 0) {
            long ttlSeconds = (long) ttlDays * 24 * 3600;
            MongoIndexUtils.createIndexWithMigration(
                    collection,
                    Indexes.ascending(FIELD_UPDATED_AT),
                    new IndexOptions().expireAfter(ttlSeconds, TimeUnit.SECONDS).sparse(true));
        }
    }

    // ────────────────── 单值增删改查 ──────────────────

    @Override
    public void save(String userId, String sessionId, String key, State value) {
        validateStateKey(key);
        Document slotId = slotId(userId, sessionId);
        String stateField = FIELD_STATES + "." + key;
        String versionField = FIELD_VERSIONS + "." + key;
        Document valueDoc = toDocument(value);
        Bson setFields =
                Updates.combine(
                        Updates.set(stateField, valueDoc),
                        Updates.inc(versionField, 1L),
                        Updates.set(FIELD_UPDATED_AT, new Date()));
        Bson setOnInsert =
                Updates.combine(
                        Updates.setOnInsert(FIELD_USER_ID, normalizeUser(userId)),
                        Updates.setOnInsert(FIELD_SESSION_ID, sessionId));
        collection.updateOne(Filters.eq(slotId), Updates.combine(setFields, setOnInsert), upsert());
    }

    @Override
    public <T extends State> Optional<T> get(
            String userId, String sessionId, String key, Class<T> type) {
        validateStateKey(key);
        Document slotId = slotId(userId, sessionId);
        String stateField = FIELD_STATES + "." + key;
        Document doc =
                collection
                        .find(Filters.eq(slotId))
                        .projection(Projections.include(stateField))
                        .first();
        if (doc == null) {
            return Optional.empty();
        }
        Document states = doc.get(FIELD_STATES, Document.class);
        if (states == null || !states.containsKey(key)) {
            return Optional.empty();
        }
        return Optional.ofNullable(deserializeValue(states.get(key), type));
    }

    // ────────────────── 列表增删改查 ──────────────────

    /**
     * 使用增量追加优化保存状态值列表。
     *
     * <p>通过服务端 {@code $size} 聚合获取元素数量，只传输一个整数而非完整数组，避免大对话历史抵消增量追加的网络节省。
     *
     * <p><b>并发注意：</b>此方法先读再写，以决定增量追加或完整替换，因此有意不提供整体原子性。针对同一 {@code (userId, sessionId, key)} 的并发写入可能交错，造成重复追加或丢失追加。可通过 {@link io.agentscope.harness.agent.sandbox.SandboxExecutionGuard} 按隔离槽位串行化，外部直接并发写入者需自行同步。
     *
     * <p>【学习提示】保护器是否真正串行化取决于实际配置，默认 noop 并不提供互斥。不能把“运行在 Harness 中”当作 saveList 天然并发安全的保证。
     */
    @Override
    public void save(String userId, String sessionId, String key, List<? extends State> values) {
        validateStateKey(key);
        Document slotId = slotId(userId, sessionId);
        String stateField = FIELD_STATES + "." + key;
        String hashField = FIELD_HASHES + "." + key;

        // 通过 $size 聚合在服务端获取现有数量及已存储哈希。
        // 只传输数字和哈希字符串，不传输完整数组。
        // $cond($isArray(stateField), $size(stateField), 0) 同时兼容数组、
        // 非数组和字段缺失情况。
        Document countExpr =
                new Document(
                        "$cond",
                        new Document("if", new Document("$isArray", "$" + stateField))
                                .append("then", new Document("$size", "$" + stateField))
                                .append("else", 0));
        Document aggResult =
                collection
                        .aggregate(
                                Arrays.asList(
                                        Aggregates.match(Filters.eq(slotId)),
                                        Aggregates.project(
                                                Projections.fields(
                                                        Projections.computed("count", countExpr),
                                                        Projections.computed(
                                                                "storedHash", "$" + hashField)))))
                        .first();

        String storedHash = null;
        int existingCount = 0;
        if (aggResult != null) {
            storedHash = aggResult.getString("storedHash");
            Integer count = aggResult.getInteger("count");
            if (count != null) {
                existingCount = count;
            }
        }

        String currentHash = ListHashUtil.computeHash(values);

        if (ListHashUtil.needsFullRewrite(values, storedHash, existingCount)) {
            List<Document> bsonList = toDocumentList(values);
            Bson setFields =
                    Updates.combine(
                            Updates.set(stateField, bsonList),
                            Updates.set(hashField, currentHash),
                            Updates.set(FIELD_UPDATED_AT, new Date()));
            Bson setOnInsert =
                    Updates.combine(
                            Updates.setOnInsert(FIELD_USER_ID, normalizeUser(userId)),
                            Updates.setOnInsert(FIELD_SESSION_ID, sessionId));
            collection.updateOne(
                    Filters.eq(slotId), Updates.combine(setFields, setOnInsert), upsert());
        } else if (values.size() > existingCount) {
            List<? extends State> newItems = values.subList(existingCount, values.size());
            List<Document> newDocs = toDocumentList(newItems);
            // 这里同样需要 $setOnInsert：新会话首次写入非空列表时
            // 会进入此追加分支（文档不存在时 needsFullRewrite 返回 false）。
            // 缺少它时，upsert 新建的文档将没有
            // user_id/session_id 字段，listSessionIds 永远看不到它，即使之后
            // 执行完整重写也无法补齐，因为 $setOnInsert 只在首次插入时生效。
            Bson update =
                    Updates.combine(
                            Updates.pushEach(stateField, newDocs),
                            Updates.set(hashField, currentHash),
                            Updates.set(FIELD_UPDATED_AT, new Date()),
                            Updates.setOnInsert(FIELD_USER_ID, normalizeUser(userId)),
                            Updates.setOnInsert(FIELD_SESSION_ID, sessionId));
            collection.updateOne(Filters.eq(slotId), update, upsert());
        } else {
            // 哈希未变但数量减少（移除了元素），强制完整重写。
            List<Document> bsonList = toDocumentList(values);
            Bson setFields =
                    Updates.combine(
                            Updates.set(stateField, bsonList),
                            Updates.set(hashField, currentHash),
                            Updates.set(FIELD_UPDATED_AT, new Date()));
            Bson setOnInsert =
                    Updates.combine(
                            Updates.setOnInsert(FIELD_USER_ID, normalizeUser(userId)),
                            Updates.setOnInsert(FIELD_SESSION_ID, sessionId));
            collection.updateOne(
                    Filters.eq(slotId), Updates.combine(setFields, setOnInsert), upsert());
        }
    }

    @Override
    public <T extends State> List<T> getList(
            String userId, String sessionId, String key, Class<T> itemType) {
        validateStateKey(key);
        Document slotId = slotId(userId, sessionId);
        String stateField = FIELD_STATES + "." + key;
        Document doc =
                collection
                        .find(Filters.eq(slotId))
                        .projection(Projections.include(stateField))
                        .first();
        if (doc == null) {
            return List.of();
        }
        Document states = doc.get(FIELD_STATES, Document.class);
        if (states == null || !states.containsKey(key)) {
            return List.of();
        }
        List<?> rawList = states.getList(key, Object.class);
        if (rawList == null) {
            return List.of();
        }
        List<T> result = new ArrayList<>(rawList.size());
        for (Object item : rawList) {
            result.add(deserializeValue(item, itemType));
        }
        return result;
    }

    // ────────────────── 版本控制 ──────────────────

    @Override
    public <T extends State> VersionedState<T> getVersioned(
            String userId, String sessionId, String key, Class<T> type) {
        validateStateKey(key);
        Document slotId = slotId(userId, sessionId);
        String stateField = FIELD_STATES + "." + key;
        String versionField = FIELD_VERSIONS + "." + key;
        Document doc =
                collection
                        .find(Filters.eq(slotId))
                        .projection(Projections.include(stateField, versionField))
                        .first();
        if (doc == null) {
            return new VersionedState<>(null, 0L);
        }
        Document states = doc.get(FIELD_STATES, Document.class);
        if (states == null || !states.containsKey(key)) {
            return new VersionedState<>(null, 0L);
        }
        Document versions = doc.get(FIELD_VERSIONS, Document.class);
        long version = (versions != null && versions.containsKey(key)) ? versions.getLong(key) : 0L;
        T value = deserializeValue(states.get(key), type);
        return new VersionedState<>(value, version);
    }

    @Override
    public long saveIfVersion(
            String userId, String sessionId, String key, State value, long expectedVersion) {
        validateStateKey(key);
        if (expectedVersion == UNVERSIONED) {
            // 无条件覆盖（后写入者胜出）。重复键错误 11000 只会发生在
            // 全新槽位的首次并发插入：两个写入者
            // 对同一 _id 执行 upsert。因此重试一次，第二次时文档
            // 已存在，upsert 退化为普通更新。
            Document slotId = slotId(userId, sessionId);
            String stateField = FIELD_STATES + "." + key;
            String versionField = FIELD_VERSIONS + "." + key;
            Document valueDoc = toDocument(value);
            Bson setFields =
                    Updates.combine(
                            Updates.set(stateField, valueDoc),
                            Updates.inc(versionField, 1L),
                            Updates.set(FIELD_UPDATED_AT, new Date()));
            Bson setOnInsert =
                    Updates.combine(
                            Updates.setOnInsert(FIELD_USER_ID, normalizeUser(userId)),
                            Updates.setOnInsert(FIELD_SESSION_ID, sessionId));
            int attempt = 0;
            while (true) {
                try {
                    Document result =
                            collection.findOneAndUpdate(
                                    Filters.eq(slotId),
                                    Updates.combine(setFields, setOnInsert),
                                    new FindOneAndUpdateOptions()
                                            .upsert(true)
                                            .returnDocument(ReturnDocument.AFTER));
                    if (result == null) {
                        return UNVERSIONED;
                    }
                    Document versions = result.get(FIELD_VERSIONS, Document.class);
                    Long v = versions != null ? versions.getLong(key) : null;
                    return v != null ? v : 0L;
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

        Document slotId = slotId(userId, sessionId);
        String stateField = FIELD_STATES + "." + key;
        String versionField = FIELD_VERSIONS + "." + key;
        Document valueDoc = toDocument(value);

        if (expectedVersion == 0) {
            Bson filter = Filters.and(Filters.eq(slotId), Filters.exists(versionField, false));
            Bson update =
                    Updates.combine(
                            Updates.set(stateField, valueDoc),
                            Updates.set(versionField, 1L),
                            Updates.set(FIELD_UPDATED_AT, new Date()),
                            Updates.setOnInsert(FIELD_USER_ID, normalizeUser(userId)),
                            Updates.setOnInsert(FIELD_SESSION_ID, sessionId));
            try {
                Document result =
                        collection.findOneAndUpdate(
                                filter,
                                update,
                                new FindOneAndUpdateOptions()
                                        .upsert(true)
                                        .returnDocument(ReturnDocument.AFTER));
                if (result == null) {
                    return UNVERSIONED;
                }
                Document versions = result.get(FIELD_VERSIONS, Document.class);
                Long newVersion = versions != null ? versions.getLong(key) : null;
                return newVersion != null && newVersion == 1L ? 1L : UNVERSIONED;
            } catch (MongoWriteException e) {
                if (e.getError().getCode() == 11000) {
                    return UNVERSIONED;
                }
                throw e;
            } catch (MongoCommandException e) {
                if (e.getErrorCode() == 11000) {
                    return UNVERSIONED;
                }
                throw e;
            }
        }

        Bson filter = Filters.and(Filters.eq(slotId), Filters.eq(versionField, expectedVersion));
        Bson update =
                Updates.combine(
                        Updates.set(stateField, valueDoc),
                        Updates.inc(versionField, 1L),
                        Updates.set(FIELD_UPDATED_AT, new Date()));
        Document result =
                collection.findOneAndUpdate(
                        filter,
                        update,
                        new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        if (result == null) {
            return UNVERSIONED;
        }
        Document versions = result.get(FIELD_VERSIONS, Document.class);
        Long newVersion = versions != null ? versions.getLong(key) : null;
        return newVersion != null ? newVersion : UNVERSIONED;
    }

    // ────────────────── 会话增删改查 ──────────────────

    @Override
    public boolean exists(String userId, String sessionId) {
        Document slotId = slotId(userId, sessionId);
        return collection.find(Filters.eq(slotId)).projection(Projections.include("_id")).first()
                != null;
    }

    @Override
    public void delete(String userId, String sessionId) {
        Document slotId = slotId(userId, sessionId);
        if (onDeleteCallback != null) {
            try {
                onDeleteCallback.accept(userId, sessionId);
            } catch (Exception e) {
                log.warn(
                        "[mongo-state] onDeleteCallback failed for session {}, proceeding"
                                + " with deletion",
                        sessionId,
                        e);
            }
        }
        collection.deleteOne(Filters.eq(slotId));
    }

    @Override
    public void delete(String userId, String sessionId, String key) {
        validateStateKey(key);
        Document slotId = slotId(userId, sessionId);
        Bson unsetFields =
                Updates.combine(
                        Updates.unset(FIELD_STATES + "." + key),
                        Updates.unset(FIELD_VERSIONS + "." + key),
                        Updates.unset(FIELD_HASHES + "." + key),
                        Updates.set(FIELD_UPDATED_AT, new Date()));
        collection.updateOne(Filters.eq(slotId), unsetFields);
    }

    @Override
    public Set<String> listSessionIds(String userId) {
        String normalizedUser = normalizeUser(userId);
        List<String> ids =
                collection
                        .distinct(
                                FIELD_SESSION_ID,
                                Filters.eq(FIELD_USER_ID, normalizedUser),
                                String.class)
                        .into(new ArrayList<>());
        return new LinkedHashSet<>(ids);
    }

    // ────────────────── 关闭 ──────────────────

    @Override
    public void close() {
        if (ownsClient) {
            mongoClient.close();
        }
    }

    // ────────────────── 内部辅助方法 ──────────────────

    private static String normalizeUser(String userId) {
        return (userId == null || userId.isBlank()) ? ANON_USER : userId;
    }

    /**
     * 从 {@code userId} 与 {@code sessionId} 构造复合 {@code _id} 文档。使用结构化 _id 而非 {@code ":"} 分隔字符串，避免任一部分合法包含分隔符时发生跨租户碰撞。
     */
    private static Document slotId(String userId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        return new Document("user", normalizeUser(userId)).append("session", sessionId);
    }

    /**
     * 验证状态键，使其可通过点号表示法用作 {@code states} 子文档内字段名。键不得包含 {@code .} 或 {@code $}，防止点号路径遍历及操作符注入。{@code _id}、{@code user_id} 等保留顶层字段名不再构成冲突，因为状态键位于 {@code states.<key>}，不能与文档结构碰撞。
     */
    private static void validateStateKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key must not be blank");
        }
        if (!SAFE_KEY_PATTERN.matcher(key).matches()) {
            throw new IllegalArgumentException(
                    "key must match pattern "
                            + SAFE_KEY_PATTERN
                            + " but was: "
                            + key
                            + " (MongoDB field names cannot contain '.' or '$')");
        }
    }

    @SuppressWarnings("unchecked")
    private Document toDocument(State value) {
        Map<String, Object> map = JsonUtils.getJsonCodec().convertValue(value, Map.class);
        return new Document(MongoKeyEscaper.escape(map == null ? Map.of() : map));
    }

    private List<Document> toDocumentList(List<? extends State> values) {
        List<Document> result = new ArrayList<>(values.size());
        for (State item : values) {
            result.add(toDocument(item));
        }
        return result;
    }

    private <T extends State> T deserializeValue(Object fieldValue, Class<T> type) {
        if (fieldValue == null) {
            return null;
        }
        Object unescaped = fieldValue;
        if (fieldValue instanceof Document doc) {
            unescaped = MongoKeyEscaper.unescape(doc);
        }
        return JsonUtils.getJsonCodec().convertValue(unescaped, type);
    }

    private static UpdateOptions upsert() {
        return new UpdateOptions().upsert(true);
    }

    // ────────────────── 构建器 ──────────────────

    /**
     * {@link MongoAgentStateStore} 的构建器。
     */
    public static class Builder {
        private MongoClient mongoClient;
        private String connectionString;
        private String databaseName;
        private String collectionName;
        private Integer ttlDays;
        private BiConsumer<String, String> onDeleteCallback;

        /**
         * 使用现有 {@link MongoClient}，由调用方拥有其生命周期；{@link MongoAgentStateStore#close()} 不会关闭通过此方法提供的客户端。
         *
         * @param mongoClient 要使用的客户端
         * @return 当前构建器
         */
        public Builder mongoClient(MongoClient mongoClient) {
            this.mongoClient = mongoClient;
            return this;
        }

        /**
         * MongoDB 连接字符串（例如 {@code "mongodb://localhost:27017"}）。
         *
         * <p>内部创建新 {@link MongoClient}，在调用 {@link MongoAgentStateStore#close()} 时关闭。
         *
         * @param connectionString 连接字符串
         * @return 当前构建器
         */
        public Builder connectionString(String connectionString) {
            this.connectionString = connectionString;
            return this;
        }

        /**
         * 数据库名，默认 {@code "agentscope"}。提供连接字符串时，先使用 URI 中的数据库名（若存在），再回退默认值。
         *
         * @param databaseName 数据库名
         * @return 当前构建器
         */
        public Builder databaseName(String databaseName) {
            this.databaseName = databaseName;
            return this;
        }

        /**
         * 集合名，默认 {@code "agentscope_sessions"}。
         *
         * @param collectionName 集合名
         * @return 当前构建器
         */
        public Builder collectionName(String collectionName) {
            this.collectionName = collectionName;
            return this;
        }

        /**
         * 可选会话 TTL 天数。设为正值时，在 {@code _updated_at} 上创建 TTL 索引，由 MongoDB 自动删除超过指定天数未更新的文档。
         *
         * <p>默认 {@code null}（无 TTL），与默认无限期保留会话的 Postgres/JDBC/Redis 对齐。
         *
         * @param ttlDays 天数；{@code null} 禁用自动过期
         * @return 当前构建器
         */
        public Builder ttlDays(Integer ttlDays) {
            if (ttlDays != null && ttlDays <= 0) {
                throw new IllegalArgumentException("ttlDays must be positive or null");
            }
            this.ttlDays = ttlDays;
            return this;
        }

        /**
         * 通过 {@link MongoAgentStateStore#delete(String, String)} 删除会话文档前调用的可选回调，接收 userId 和 sessionId。由 {@link io.agentscope.extensions.mongodb.MongoDistributedStore} 用于级联删除关联快照。
         *
         * @param onDeleteCallback 会话删除前执行的回调 (userId, sessionId)
         * @return 当前构建器
         */
        public Builder onDeleteCallback(BiConsumer<String, String> onDeleteCallback) {
            this.onDeleteCallback = onDeleteCallback;
            return this;
        }

        /**
         * 构建 {@link MongoAgentStateStore}。
         *
         * @return 新实例
         */
        public MongoAgentStateStore build() {
            return new MongoAgentStateStore(this);
        }
    }
}
