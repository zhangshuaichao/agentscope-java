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

import com.mongodb.MongoCommandException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;
import org.bson.BsonDocument;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 创建 MongoDB 索引的工具，在 {@code IndexOptionsConflict}（错误码 85）时自动迁移。
 *
 * <p>现有索引选项冲突时（例如 TTL 值改变），通过 {@code listIndexes()} 按键模式寻找<em>实际</em>索引名，而不是猜测默认名称，因此 DBA 重命名过索引也能处理。
 */
public final class MongoIndexUtils {

    private static final Logger log = LoggerFactory.getLogger(MongoIndexUtils.class);

    private static final int MAX_MIGRATION_ATTEMPTS = 3;

    private MongoIndexUtils() {}

    /**
     * 创建索引；现有索引选项冲突（错误 85）时执行迁移。
     *
     * <p>{@code createIndex} 因错误 85 失败时：
     * <ol>
     *   <li>调用 {@code listIndexes()}，寻找键模式匹配 {@code indexKeys} 的现有索引。</li>
     *   <li>按实际名称删除索引，不猜测默认名；若其他节点已删除，容忍“索引不存在”。</li>
     *   <li>使用新定义重建索引。</li>
     * </ol>
     *
     * <p>删除再重建路径容忍多节点启动竞争：其他节点并发删除或重建相同索引时，尝试幂等处理而非直接使应用启动失败。
     *
     * @param collection MongoDB 集合
     * @param indexKeys 索引键规范（例如 {@code Indexes.ascending("field")}）
     * @param options 索引选项（例如 TTL、稀疏、唯一）
     */
    public static void createIndexWithMigration(
            MongoCollection<Document> collection, Bson indexKeys, IndexOptions options) {
        try {
            collection.createIndex(indexKeys, options);
        } catch (MongoCommandException e) {
            if (e.getErrorCode() != 85) {
                throw e;
            }
            migrateConflictingIndex(collection, indexKeys, options);
        }
    }

    /**
     * 通过删除冲突索引并用新选项重建处理错误 85。删除和重建均容忍其他节点并发操作，并尝试幂等完成。最多尝试 {@value MAX_MIGRATION_ATTEMPTS} 次，以处理多节点并发启动竞争。
     */
    private static void migrateConflictingIndex(
            MongoCollection<Document> collection, Bson indexKeys, IndexOptions options) {
        String conflictingName = findIndexNameByKeyPattern(collection, indexKeys);
        if (conflictingName != null) {
            log.info(
                    "[mongo-index] Index conflict (error 85), dropping '{}' and recreating"
                            + " with new options",
                    conflictingName);
            dropIndexTolerant(collection, conflictingName);
        }

        // 两种情况共用一条 createIndex 路径（冲突索引由本节点删除，或
        // 检查时已不存在），使迁移途中发生的并发重建仍能被捕获
        // 并重试，而不是成为未处理错误。
        for (int attempt = 1; attempt <= MAX_MIGRATION_ATTEMPTS; attempt++) {
            try {
                collection.createIndex(indexKeys, options);
                return;
            } catch (MongoCommandException retryEx) {
                if (retryEx.getErrorCode() != 85) {
                    throw retryEx;
                }
                if (attempt < MAX_MIGRATION_ATTEMPTS) {
                    log.warn(
                            "[mongo-index] Concurrent conflict (error 85) on attempt {}/{}"
                                    + " — another node recreated the index; re-dropping and"
                                    + " retrying",
                            attempt,
                            MAX_MIGRATION_ATTEMPTS,
                            retryEx);
                    // 重试前重新删除当前冲突索引，确保持续冲突
                    // （例如滚动升级时旧节点重建旧索引）
                    // 能真正解决，而不是对未变化状态反复重试。
                    String current = findIndexNameByKeyPattern(collection, indexKeys);
                    if (current != null) {
                        dropIndexTolerant(collection, current);
                    }
                } else {
                    log.warn(
                            "[mongo-index] Exhausted {} migration attempts — propagating"
                                    + " final error 85",
                            MAX_MIGRATION_ATTEMPTS,
                            retryEx);
                    throw retryEx;
                }
            }
        }
    }

    /**
     * 按名称删除索引，容忍其他节点并发删除时出现的“索引不存在”（码 27）及“命名空间不存在”错误。
     */
    private static void dropIndexTolerant(MongoCollection<Document> collection, String name) {
        try {
            collection.dropIndex(name);
        } catch (MongoCommandException e) {
            if (e.getErrorCode() == 27 || e.getMessage().contains("ns not found")) {
                log.debug("[mongo-index] Index '{}' already dropped by another node", name);
            } else {
                throw e;
            }
        }
    }

    /**
     * 寻找键模式与给定键匹配的现有索引名称。
     *
     * @param collection MongoDB 集合
     * @param indexKeys 要匹配的索引键规范
     * @return 索引名称，无匹配索引时为 {@code null}
     */
    private static String findIndexNameByKeyPattern(
            MongoCollection<Document> collection, Bson indexKeys) {
        BsonDocument keyPattern =
                indexKeys.toBsonDocument(BsonDocument.class, collection.getCodecRegistry());
        for (Document index : collection.listIndexes()) {
            Document existingKeys = index.get("key", Document.class);
            if (existingKeys != null) {
                BsonDocument existingBson =
                        existingKeys.toBsonDocument(
                                BsonDocument.class, collection.getCodecRegistry());
                if (existingBson.equals(keyPattern)) {
                    return index.getString("name");
                }
            }
        }
        return null;
    }
}
