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
package io.agentscope.extensions.mongodb.snapshot;

import com.mongodb.MongoGridFSException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.gridfs.GridFSBucket;
import com.mongodb.client.gridfs.GridFSBuckets;
import com.mongodb.client.gridfs.model.GridFSFile;
import com.mongodb.client.gridfs.model.GridFSUploadOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import io.agentscope.extensions.mongodb.MongoConstants;
import io.agentscope.harness.agent.sandbox.snapshot.RemoteSnapshotClient;
import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.Date;
import java.util.Objects;
import org.bson.Document;
import org.bson.types.Binary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 由 MongoDB GridFS 支撑的 {@link RemoteSnapshotClient}。
 *
 * <p>将沙箱工作区 tar 归档保存在 GridFS 中，默认按 255 KiB 分块，从而突破单 BSON 文档 16 MiB 的大小限制。上游原注释提到的 16 GB 不应视为本类实施的容量上限；实际限制须结合驱动与部署评估。参见 <a href="https://www.mongodb.com/docs/manual/core/gridfs/">MongoDB GridFS 官方说明</a>。
 *
 * <p>快照没有独立 TTL，与无限期保留快照的 Postgres/JDBC/Redis 对齐。只有显式删除所属会话时，才由 {@code MongoDistributedStore} 驱动级联清理并调用 {@link #delete(String)} 回收快照。
 *
 * <p>为兼容旧数据，{@link #download} 和 {@link #exists} 在 GridFS 未找到文件时回退读取旧版单文档集合。
 */
public class MongoRemoteSnapshotClient implements RemoteSnapshotClient {

    private static final Logger log = LoggerFactory.getLogger(MongoRemoteSnapshotClient.class);

    private static final String META_CREATED_AT = "createdAt";
    private static final String LEGACY_FIELD_DATA = "data";
    private static final String GRIDFS_FIELD_FILENAME = "filename";

    private final GridFSBucket gridFSBucket;
    // 旧集合将快照保存为单个 BSON 文档 {_id, data: Binary}。
    // GridFS 使用 <bucketName>.files 和 <bucketName>.chunks，原集合
    // 与它们独立，必须检查以保持向后兼容。
    private final MongoCollection<Document> legacyCollection;
    private final String legacyCollectionName;

    public MongoRemoteSnapshotClient(
            MongoClient mongoClient, String databaseName, String collectionName) {
        Objects.requireNonNull(mongoClient, "mongoClient");
        this.legacyCollectionName =
                collectionName != null ? collectionName : MongoConstants.SNAPSHOTS_COLLECTION;
        MongoDatabase db =
                mongoClient.getDatabase(
                        databaseName != null ? databaseName : MongoConstants.DEFAULT_DATABASE);
        this.gridFSBucket = GridFSBuckets.create(db, legacyCollectionName);
        this.legacyCollection = db.getCollection(legacyCollectionName);
    }

    /**
     * 包级私有构造方法，供单元测试注入预构建依赖。
     */
    MongoRemoteSnapshotClient(
            GridFSBucket gridFSBucket, MongoCollection<Document> legacyCollection) {
        this.gridFSBucket = gridFSBucket;
        this.legacyCollection = legacyCollection;
        this.legacyCollectionName = null;
    }

    @Override
    public void upload(String snapshotId, InputStream data) throws Exception {
        Objects.requireNonNull(snapshotId, "snapshotId");
        Objects.requireNonNull(data, "data");

        // 删除已有同 ID 文件（插入或更新语义）。
        deleteIfExists(snapshotId);

        Document metadata = new Document(META_CREATED_AT, new Date());
        GridFSUploadOptions options = new GridFSUploadOptions().metadata(metadata);
        gridFSBucket.uploadFromStream(snapshotId, data, options);
    }

    @Override
    public InputStream download(String snapshotId) throws Exception {
        Objects.requireNonNull(snapshotId, "snapshotId");
        // openDownloadStream(String) 是惰性的：立即返回流，仅在
        // 文件缺失时第一次 read() 才抛出 MongoGridFSException。为
        // 保留立即抛出 FileNotFoundException 的契约并支持旧数据回退，
        // 先通过 find() 检查存在性，与 exists() 和 delete() 一致。
        GridFSFile file = gridFSBucket.find(Filters.eq(GRIDFS_FIELD_FILENAME, snapshotId)).first();
        if (file != null) {
            return gridFSBucket.openDownloadStream(file.getId());
        }
        // 回退到旧版单文档存储，保持向后兼容。
        return downloadLegacy(snapshotId);
    }

    @Override
    public boolean exists(String snapshotId) throws Exception {
        Objects.requireNonNull(snapshotId, "snapshotId");
        // GridFS _id 是自动生成的 ObjectId，因此按 filename 搜索。
        GridFSFile file = gridFSBucket.find(Filters.eq(GRIDFS_FIELD_FILENAME, snapshotId)).first();
        if (file != null) {
            return true;
        }
        // 回退检查旧版单文档。
        return existsLegacy(snapshotId);
    }

    /**
     * 从 GridFS 删除快照。
     *
     * @param snapshotId 快照标识符，保存为 GridFS 文件名
     * @return 删除了文件则为 {@code true}；不存在匹配快照则为 {@code false}
     * @throws Exception MongoDB 出错时抛出
     */
    public boolean delete(String snapshotId) throws Exception {
        Objects.requireNonNull(snapshotId, "snapshotId");
        GridFSFile file = gridFSBucket.find(Filters.eq(GRIDFS_FIELD_FILENAME, snapshotId)).first();
        if (file == null) {
            return deleteLegacy(snapshotId);
        }
        gridFSBucket.delete(file.getId());
        // 若旧版文档存在，也一并清理（迁移场景）。
        deleteLegacy(snapshotId);
        return true;
    }

    private void deleteIfExists(String snapshotId) {
        GridFSFile existing =
                gridFSBucket.find(Filters.eq(GRIDFS_FIELD_FILENAME, snapshotId)).first();
        if (existing == null) {
            return;
        }
        try {
            gridFSBucket.delete(existing.getId());
        } catch (MongoGridFSException e) {
            // 其他并发上传可能已在本次 find() 与 delete() 之间删除文件，
            // 可安全忽略，因为最终状态相同。
            log.debug("[mongo-snapshot] Concurrent delete of '{}', proceeding", snapshotId);
        }
    }

    // ────────────────── 旧版单文档回退 ──────────────────

    private InputStream downloadLegacy(String snapshotId) throws FileNotFoundException {
        Document doc =
                legacyCollection
                        .find(Filters.eq("_id", snapshotId))
                        .projection(Projections.include(LEGACY_FIELD_DATA))
                        .first();
        if (doc == null) {
            throw new FileNotFoundException("Snapshot not found in MongoDB: " + snapshotId);
        }
        Binary binary = doc.get(LEGACY_FIELD_DATA, Binary.class);
        if (binary == null) {
            throw new FileNotFoundException(
                    "Snapshot document has no data field in MongoDB: " + snapshotId);
        }
        return new ByteArrayInputStream(binary.getData());
    }

    private boolean existsLegacy(String snapshotId) {
        return legacyCollection
                        .find(Filters.eq("_id", snapshotId))
                        .projection(Projections.include("_id"))
                        .first()
                != null;
    }

    private boolean deleteLegacy(String snapshotId) {
        return legacyCollection.deleteOne(Filters.eq("_id", snapshotId)).getDeletedCount() > 0;
    }
}
