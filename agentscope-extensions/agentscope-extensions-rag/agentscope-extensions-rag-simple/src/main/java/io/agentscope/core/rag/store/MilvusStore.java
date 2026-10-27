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
package io.agentscope.core.rag.store;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.exception.VectorStoreException;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import io.agentscope.core.rag.store.dto.SearchDocumentDto;
import io.agentscope.core.util.JsonUtils;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.database.request.CreateDatabaseReq;
import io.milvus.v2.service.database.response.ListDatabasesResp;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.DeleteResp;
import io.milvus.v2.service.vector.response.InsertResp;
import io.milvus.v2.service.vector.response.SearchResp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Milvus 向量数据库存储实现。
 *
 * <p>通过云原生向量数据库 Milvus 存储与搜索向量，实现 VDBStoreBase 统一操作接口。
 *
 * <p>使用动态字段存储文档 ID、分块 ID、原始内容等元数据。
 *
 * <p>使用 Milvus Java SDK v2（io.milvus:milvus-sdk-java），依赖示例：
 * <pre>{@code
 * <dependency>
 *     <groupId>io.milvus</groupId>
 *     <artifactId>milvus-sdk-java</artifactId>
 *     <version>2.6.16</version>
 * </dependency>
 * }</pre>
 *
 * <p>用法示例：
 * <pre>{@code
 * // 使用带身份验证和超时选项的构建器
 * try (MilvusStore store = MilvusStore.builder()
 *         .uri("http://localhost:19530")
 *         .databaseName("my_database")
 *         .collectionName("my_collection")
 *         .dimensions(1024)
 *         .token("root:Milvus")
 *         .connectTimeoutMs(30000)
 *         .build()) {
 *     store.add(document).block();
 *     List<Document> results = store.search(queryEmbedding, 5).block();
 *     // 存储在此处自动关闭
 * }
 * }</pre>
 *
 * <p>认证选项：
 * <ul>
 * <li>令牌：token("username:password") 格式。</li>
 * <li>用户名/密码：使用 username() 和 password()。</li>
 * <li>无认证：本地实例可不填 token/username/password。</li>
 * </ul>
 *
 * <p>uri 应为包含协议与端口的完整 HTTP URL，例如 http://localhost:19530 或 https://milvus.example.com:19530。
 */
public class MilvusStore implements VDBStoreBase, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MilvusStore.class);
    private static final Gson GSON = new Gson();

    // Milvus 集合模式字段名。
    private static final String FIELD_ID = "id";
    private static final String FIELD_VECTOR = "vector";
    private static final String FIELD_DOC_ID = "doc_id";
    private static final String FIELD_CHUNK_ID = "chunk_id";
    private static final String FIELD_CONTENT = "content";
    private static final String FIELD_PAYLOAD = "payload";

    // 默认配置值。
    private static final String DEFAULT_DATABASE_NAME = "default";
    private static final long DEFAULT_CONNECT_TIMEOUT_MS = 30000L;

    private final String uri;
    private final String collectionName;
    private final String databaseName;
    private final int dimensions;
    private final IndexParam.MetricType metricType;
    private final MilvusClientV2 milvusClient;
    private volatile boolean closed = false;

    /**
     * 按构建器配置创建 MilvusStore。
     *
     * @param builder 构建器实例
     * @throws VectorStoreException 当客户端初始化或创建集合失败时
     */
    @SuppressWarnings("rawtypes") // Milvus SDK 的构建器使用原始类型。
    private MilvusStore(Builder builder) throws VectorStoreException {
        this.uri = builder.uri;
        this.collectionName = builder.collectionName;
        this.dimensions = builder.dimensions;
        this.metricType = builder.metricType;
        this.databaseName = builder.databaseName;
        Map<String, String> databaseProperties = builder.databaseProperties;
        String token = builder.token;
        String username = builder.username;
        String password = builder.password;
        long connectTimeoutMs = builder.connectTimeoutMs;

        // 立即初始化客户端与集合。
        MilvusClientV2 tempClient = null;

        try {
            // 构建连接配置。
            ConnectConfig.ConnectConfigBuilder configBuilder = ConnectConfig.builder().uri(uri);

            // 设置认证。
            if (token != null && !token.trim().isEmpty()) {
                configBuilder.token(token);
            } else if (username != null && !username.trim().isEmpty()) {
                configBuilder.token(username + ":" + (password != null ? password : ""));
            }

            // 设置超时。
            if (connectTimeoutMs > 0) {
                configBuilder.connectTimeoutMs(connectTimeoutMs);
            }

            ConnectConfig config = configBuilder.build();
            tempClient = new MilvusClientV2(config);

            log.debug("Initialized Milvus client: uri={}, collection={}", uri, collectionName);

            // 确保数据库存在。
            if (!DEFAULT_DATABASE_NAME.equals(databaseName)) {
                ensureDatabase(tempClient, databaseProperties);
            }
            tempClient.useDatabase(databaseName);

            // 确保集合存在。
            ensureCollection(tempClient);

            // 初始化成功后才赋值给 final 字段。
            this.milvusClient = tempClient;

            log.debug("MilvusStore initialized successfully for collection: {}", collectionName);
        } catch (Exception e) {
            // 初始化失败时清理资源。
            try {
                if (tempClient != null) {
                    tempClient.close();
                }
            } catch (Exception cleanupException) {
                log.warn(
                        "Error closing MilvusClient after initialization failure",
                        cleanupException);
            }
            throw new VectorStoreException("Failed to initialize MilvusStore", e);
        }
    }

    /**
     * 使用最小配置创建 MilvusStore。
     *
     * @param uri Milvus 服务 URI，如 http://localhost:19530
     * @param collectionName 使用的集合名称
     * @param dimensions 待存储向量维度
     * @return 新的 MilvusStore 实例
     * @throws VectorStoreException 当初始化失败时
     * @deprecated 请使用 {@link MilvusStore#builder()}。
     */
    @Deprecated(since = "1.0.11", forRemoval = true)
    public static MilvusStore create(String uri, String collectionName, int dimensions)
            throws VectorStoreException {
        return builder().uri(uri).collectionName(collectionName).dimensions(dimensions).build();
    }

    @Override
    public Mono<Void> add(List<Document> documents) {
        if (documents == null) {
            return Mono.error(new IllegalArgumentException("Documents list cannot be null"));
        }
        if (documents.isEmpty()) {
            return Mono.empty();
        }

        // 校验全部文档。
        for (Document document : documents) {
            if (document == null) {
                return Mono.error(new IllegalArgumentException("Document cannot be null"));
            }
            if (document.getEmbedding() == null) {
                return Mono.error(new IllegalArgumentException("Document must have embedding set"));
            }
            if (document.getEmbedding().length != dimensions) {
                return Mono.error(
                        new VectorStoreException(
                                String.format(
                                        "Embedding dimension mismatch: expected %d, got %d",
                                        dimensions, document.getEmbedding().length)));
            }
        }

        return Mono.fromCallable(
                        () -> {
                            try {
                                ensureNotClosed();
                                addDocumentsToMilvus(documents);
                                return null;
                            } catch (Exception e) {
                                throw new VectorStoreException(
                                        "Failed to add documents to Milvus", e);
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(
                        e ->
                                e instanceof VectorStoreException
                                        ? e
                                        : new VectorStoreException(
                                                "Failed to add documents to Milvus", e))
                .then();
    }

    @Override
    public Mono<List<Document>> search(SearchDocumentDto searchDocumentDto) {
        double[] queryEmbedding = searchDocumentDto.getQueryEmbedding();
        int limit = searchDocumentDto.getLimit();
        Double scoreThreshold = searchDocumentDto.getScoreThreshold();
        if (queryEmbedding == null) {
            return Mono.error(new IllegalArgumentException("Query embedding cannot be null"));
        }
        if (queryEmbedding.length != dimensions) {
            return Mono.error(
                    new VectorStoreException(
                            String.format(
                                    "Query embedding dimension mismatch: expected %d, got %d",
                                    dimensions, queryEmbedding.length)));
        }
        if (limit <= 0) {
            return Mono.error(new IllegalArgumentException("Limit must be positive"));
        }

        return Mono.fromCallable(
                        () -> {
                            try {
                                ensureNotClosed();
                                return searchDocumentsInMilvus(
                                        queryEmbedding, limit, scoreThreshold);
                            } catch (Exception e) {
                                throw new VectorStoreException(
                                        "Failed to search documents in Milvus", e);
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(
                        e ->
                                e instanceof VectorStoreException
                                        ? e
                                        : new VectorStoreException(
                                                "Failed to search documents in Milvus", e));
    }

    @Override
    public Mono<Boolean> delete(String docId) {
        if (docId == null || docId.trim().isEmpty()) {
            return Mono.error(new IllegalArgumentException("Document ID cannot be null or empty"));
        }

        return Mono.fromCallable(
                        () -> {
                            try {
                                ensureNotClosed();
                                return deleteDocumentFromMilvus(docId);
                            } catch (Exception e) {
                                throw new VectorStoreException(
                                        "Failed to delete document from Milvus", e);
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(
                        e ->
                                e instanceof VectorStoreException
                                        ? e
                                        : new VectorStoreException(
                                                "Failed to delete document from Milvus", e));
    }

    /**
     * 获取底层 Milvus 客户端以执行高级操作。
     *
     * <p>用于超出 VDBStoreBase 接口的操作，例如：
     * <ul>
     * <li>自定义索引配置。</li>
     * <li>高级过滤与分面。</li>
     * <li>集合管理。</li>
     * <li>批量操作。</li>
     * </ul>
     *
     * @return MilvusClientV2 实例
     * @throws VectorStoreException 当存储已关闭时
     */
    public MilvusClientV2 getClient() throws VectorStoreException {
        ensureNotClosed();
        return milvusClient;
    }

    /**
     * 操作前确保存储未关闭。
     *
     * @throws VectorStoreException 当存储已关闭时
     */
    private void ensureNotClosed() throws VectorStoreException {
        if (closed) {
            throw new VectorStoreException("MilvusStore has been closed");
        }
    }

    /**
     * 确保集合存在，必要时创建。
     *
     * @param client 使用的 MilvusClientV2
     * @throws VectorStoreException 当创建集合失败时
     */
    private void ensureCollection(MilvusClientV2 client) throws VectorStoreException {
        try {
            // 检查集合是否存在。
            HasCollectionReq hasCollectionReq =
                    HasCollectionReq.builder()
                            .databaseName(databaseName)
                            .collectionName(collectionName)
                            .build();
            boolean exists = client.hasCollection(hasCollectionReq);

            if (exists) {
                log.debug("Collection '{}' already exists", collectionName);
                return;
            }

            // 创建集合。
            createCollection(client);
            log.debug("Created collection '{}' with dimensions {}", collectionName, dimensions);
        } catch (Exception e) {
            throw new VectorStoreException(
                    "Failed to ensure collection exists: " + collectionName, e);
        }
    }

    /**
     * 确保数据库存在，必要时创建。
     *
     * @param client 使用的 MilvusClientV2
     * @throws VectorStoreException 当创建数据库失败时
     */
    private void ensureDatabase(MilvusClientV2 client, Map<String, String> databaseProperties)
            throws VectorStoreException {
        try {
            // 检查数据库是否存在。
            ListDatabasesResp listDatabasesResp = client.listDatabases();
            boolean exists = listDatabasesResp.getDatabaseNames().contains(databaseName);
            if (exists) {
                log.debug("DatabaseName '{}' already exists", databaseName);
                return;
            }

            // 创建数据库。
            createDatabase(client, databaseProperties);
            log.debug("Created database '{}'", databaseName);
        } catch (Exception e) {
            throw new VectorStoreException(
                    "Failed to ensure databaseName exists: " + databaseName, e);
        }
    }

    /**
     * 按指定维度和模式创建集合。
     *
     * @param client 使用的 MilvusClientV2
     */
    private void createCollection(MilvusClientV2 client) {
        // 创建启用动态字段的模式，以保存元数据。
        CreateCollectionReq.CollectionSchema schema = MilvusClientV2.CreateSchema();
        schema.setEnableDynamicField(true);

        // 添加主键字段（UUID 字符串）。
        schema.addField(
                AddFieldReq.builder()
                        .fieldName(FIELD_ID)
                        .dataType(DataType.VarChar)
                        .maxLength(64)
                        .isPrimaryKey(true)
                        .autoID(false)
                        .build());

        // 添加向量字段。
        schema.addField(
                AddFieldReq.builder()
                        .fieldName(FIELD_VECTOR)
                        .dataType(DataType.FloatVector)
                        .dimension(dimensions)
                        .build());

        // 添加用于过滤的 doc_id 字段。
        schema.addField(
                AddFieldReq.builder()
                        .fieldName(FIELD_DOC_ID)
                        .dataType(DataType.VarChar)
                        .maxLength(256)
                        .build());

        // 添加 chunk_id 字段。
        schema.addField(
                AddFieldReq.builder().fieldName(FIELD_CHUNK_ID).dataType(DataType.VarChar).build());

        // 添加保存序列化内容的 content 字段。
        schema.addField(
                AddFieldReq.builder()
                        .fieldName(FIELD_CONTENT)
                        .dataType(DataType.VarChar)
                        .maxLength(65535)
                        .build());

        // 添加以 JSON 保存自定义载荷的 payload 字段。
        schema.addField(
                AddFieldReq.builder().fieldName(FIELD_PAYLOAD).dataType(DataType.JSON).build());

        // 创建向量字段的索引参数。
        List<IndexParam> indexParams = new ArrayList<>();
        indexParams.add(
                IndexParam.builder()
                        .fieldName(FIELD_VECTOR)
                        .indexType(IndexParam.IndexType.AUTOINDEX)
                        .metricType(metricType)
                        .build());

        // 使用模式和索引创建集合。
        CreateCollectionReq createCollectionReq =
                CreateCollectionReq.builder()
                        .databaseName(databaseName)
                        .collectionName(collectionName)
                        .collectionSchema(schema)
                        .indexParams(indexParams)
                        .build();

        client.createCollection(createCollectionReq);
    }

    /**
     * 按指定属性创建数据库。
     *
     * @param client 使用的 MilvusClientV2
     * @param databaseProperties 数据库属性
     */
    private void createDatabase(MilvusClientV2 client, Map<String, String> databaseProperties) {
        CreateDatabaseReq createDatabaseReq =
                CreateDatabaseReq.builder()
                        .databaseName(databaseName)
                        .properties(databaseProperties)
                        .build();
        client.createDatabase(createDatabaseReq);
    }

    /**
     * 向 Milvus 添加多条文档。
     *
     * <p>根据 doc_id、chunk_id 和内容生成确定性 UUID，匹配 Python 实现的 _map_text_to_uuid 函数。
     *
     * @param documents 待存储文档，必须已设置嵌入
     */
    private void addDocumentsToMilvus(List<Document> documents) {
        List<JsonObject> rows = new ArrayList<>();

        for (Document document : documents) {
            JsonObject row = new JsonObject();

            // 设置主键（文档 ID）。
            row.addProperty(FIELD_ID, document.getId());

            // 将 double[] 转换为 float[] 向量。
            double[] embedding = document.getEmbedding();
            List<Float> floatList = new ArrayList<>(embedding.length);
            for (double d : embedding) {
                floatList.add((float) d);
            }
            row.add(FIELD_VECTOR, GSON.toJsonTree(floatList));

            // 设置元数据字段。
            DocumentMetadata metadata = document.getMetadata();
            row.addProperty(FIELD_DOC_ID, metadata.getDocId());
            row.addProperty(FIELD_CHUNK_ID, metadata.getChunkId());

            // 将内容序列化为 JSON 字符串。
            String contentJson;
            try {
                contentJson = JsonUtils.getJsonCodec().toJson(metadata.getContent());
            } catch (Exception e) {
                log.warn("Failed to serialize content, using text representation", e);
                contentJson = metadata.getContentText();
            }
            row.addProperty(FIELD_CONTENT, contentJson);

            // 将自定义载荷字段加入动态字段。
            Map<String, Object> customPayload = metadata.getPayload();
            if (customPayload != null && !customPayload.isEmpty()) {
                JsonObject payloadObject = GSON.toJsonTree(customPayload).getAsJsonObject();
                row.add(FIELD_PAYLOAD, payloadObject);
            } else {
                row.add(FIELD_PAYLOAD, new JsonObject());
            }

            rows.add(row);
        }

        // 插入数据。
        InsertReq insertReq =
                InsertReq.builder()
                        .databaseName(databaseName)
                        .collectionName(collectionName)
                        .data(rows)
                        .build();

        InsertResp insertResp = milvusClient.insert(insertReq);
        log.debug(
                "Inserted {} documents into collection '{}'",
                insertResp.getInsertCnt(),
                collectionName);
    }

    /**
     * 在 Milvus 搜索相似文档。
     *
     * @param queryEmbedding 查询向量
     * @param limit 最大结果数
     * @param scoreThreshold 可选最低分数阈值
     * @return 已设置分数的文档列表
     */
    @SuppressWarnings("rawtypes") // Milvus SDK 的构建器使用原始类型。
    private List<Document> searchDocumentsInMilvus(
            double[] queryEmbedding, int limit, Double scoreThreshold) {
        // 将查询向量从 double[] 转为 float[]。
        float[] floatArray = new float[queryEmbedding.length];
        for (int i = 0; i < queryEmbedding.length; i++) {
            floatArray[i] = (float) queryEmbedding[i];
        }
        FloatVec queryVector = new FloatVec(floatArray);

        // 构建搜索请求。
        SearchReq.SearchReqBuilder searchBuilder =
                SearchReq.builder()
                        .databaseName(databaseName)
                        .collectionName(collectionName)
                        .data(Collections.singletonList(queryVector))
                        .metricType(metricType)
                        .limit(limit)
                        .outputFields(
                                Arrays.asList(
                                        FIELD_ID,
                                        FIELD_DOC_ID,
                                        FIELD_CHUNK_ID,
                                        FIELD_CONTENT,
                                        FIELD_PAYLOAD));

        SearchReq searchReq = searchBuilder.build();

        // 执行搜索。
        SearchResp searchResp = milvusClient.search(searchReq);

        // 处理结果。
        List<Document> results = new ArrayList<>();
        List<List<SearchResp.SearchResult>> searchResults = searchResp.getSearchResults();

        if (searchResults != null && !searchResults.isEmpty()) {
            for (SearchResp.SearchResult result : searchResults.get(0)) {
                try {
                    // Milvus 的 L2 返回距离，先转换为越高越相似的分数，
                    // 再应用统一阈值。
                    double score = normalizeScore(result.getScore());

                    // 应用已指定的分数阈值。
                    if (scoreThreshold != null && score < scoreThreshold) {
                        continue;
                    }

                    // 从结果重建文档。
                    Document document = reconstructDocumentFromResult(result, score);
                    if (document != null) {
                        results.add(document);
                    }
                } catch (Exception e) {
                    log.warn("Failed to reconstruct document from search result", e);
                }
            }
        }

        return results;
    }

    /**
     * 将 Milvus 原始结果转换为 AgentScope 分数约定。
     *
     * <p>L2 距离越小越相似，因此映射为 [0, 1] 内单调递减分数。其他支持的度量已符合分数方向，保持不变。
     *
     * @param rawScore Milvus 原始分数或距离
     * @return 越高越相似的分数
     */
    private double normalizeScore(double rawScore) {
        if (metricType == IndexParam.MetricType.L2) {
            return 1.0 / (1.0 + rawScore);
        }
        return rawScore;
    }

    /**
     * 从 Milvus 搜索结果重建 Document。
     *
     * @param result Milvus 搜索结果
     * @param score 相似度分数
     * @return 重建文档；失败时为 null
     */
    private Document reconstructDocumentFromResult(SearchResp.SearchResult result, double score) {
        try {
            Map<String, Object> entity = result.getEntity();

            // 提取元数据字段。
            String docId = String.valueOf(entity.get(FIELD_DOC_ID));
            String chunkId = String.valueOf(entity.get(FIELD_CHUNK_ID));
            String contentJson = String.valueOf(entity.get(FIELD_CONTENT));

            // 反序列化内容。
            ContentBlock content;
            try {
                content = JsonUtils.getJsonCodec().fromJson(contentJson, ContentBlock.class);
            } catch (Exception e) {
                log.debug("Failed to deserialize ContentBlock, creating TextBlock from content", e);
                content = TextBlock.builder().text(contentJson).build();
            }

            // 从 payload 字段反序列化自定义载荷。
            Map<String, Object> customPayload = new HashMap<>();
            Object payloadObj = entity.get(FIELD_PAYLOAD);
            if (payloadObj != null) {
                String payloadJson = String.valueOf(payloadObj);
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> deserializedPayload =
                            JsonUtils.getJsonCodec().fromJson(payloadJson, Map.class);
                    customPayload = deserializedPayload;
                } catch (Exception e) {
                    log.warn("Failed to deserialize payload, using empty map", e);
                }
            }

            // 创建元数据与带载荷的文档。
            DocumentMetadata metadata =
                    new DocumentMetadata(content, docId, chunkId, customPayload);
            Document document = new Document(metadata);
            document.setScore(score);

            return document;
        } catch (Exception e) {
            log.error("Failed to reconstruct document from search result", e);
            return null;
        }
    }

    /**
     * 按文档 ID 从 Milvus 删除文档。
     *
     * @param docId 待删除文档 ID
     * @return 删除成功时为 true
     */
    private boolean deleteDocumentFromMilvus(String docId) {
        DeleteReq deleteReq =
                DeleteReq.builder()
                        .databaseName(databaseName)
                        .collectionName(collectionName)
                        .filter(FIELD_DOC_ID + " in [\"" + docId + "\"]")
                        .build();

        DeleteResp deleteResp = milvusClient.delete(deleteReq);
        long deleteCnt = deleteResp.getDeleteCnt();
        log.debug("Deleted {} documents from collection '{}'", deleteCnt, collectionName);

        return deleteCnt > 0;
    }

    /**
     * 获取 Milvus 服务 URI。
     *
     * @return URI
     */
    public String getUri() {
        return uri;
    }

    /**
     * 获取集合名称。
     *
     * @return 集合名称
     */
    public String getCollectionName() {
        return collectionName;
    }

    /**
     * 获取向量维度。
     *
     * @return 维度
     */
    public int getDimensions() {
        return dimensions;
    }

    /**
     * 获取相似度搜索使用的度量类型。
     *
     * @return 度量类型
     */
    public IndexParam.MetricType getMetricType() {
        return metricType;
    }

    /**
     * 获取数据库名称。
     *
     * @return 数据库名称
     */
    public String getDatabaseName() {
        return databaseName;
    }

    /**
     * 关闭 Milvus 客户端并释放全部资源。
     *
     * <p>关闭 MilvusClientV2 及其连接和关联资源，操作幂等，可安全重复调用。
     *
     * <p>关闭后的存储操作抛出 VectorStoreException，建议通过 try-with-resources 自动管理：
     * <pre>{@code
     * try (MilvusStore store = MilvusStore.builder()
     *         .uri("http://localhost:19530")
     *         .collectionName("collection")
     *         .dimensions(1024)
     *         .build()) {
     *     store.add(documents).block();
     *     // 存储在此处自动关闭
     * }
     * }</pre>
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }

        synchronized (this) {
            if (closed) {
                return;
            }

            closed = true;

            try {
                if (milvusClient != null) {
                    log.debug("Closing MilvusClient for collection: {}", collectionName);
                    milvusClient.close();
                }
            } catch (Exception e) {
                log.warn("Error closing MilvusClient", e);
            }

            log.debug("MilvusStore closed for collection: {}", collectionName);
        }
    }

    /**
     * 检查存储是否已关闭。
     *
     * @return 已关闭时为 true，否则为 false
     */
    public boolean isClosed() {
        return closed;
    }

    /**
     * 通过流式配置创建 MilvusStore 的构建器。
     *
     * <p>用法示例：
     * <pre>{@code
     * MilvusStore store = MilvusStore.builder()
     *     .uri("http://localhost:19530")
     *     .collectionName("my_collection")
     *     .dimensions(1024)
     *     .token("root:Milvus")
     *     .connectTimeoutMs(30000)
     *     .metricType(IndexParam.MetricType.COSINE)
     *     .build();
     * }</pre>
     */
    public static class Builder {
        private String uri;
        private String collectionName;
        private int dimensions;
        private String token;
        private String username;
        private String password;
        private long connectTimeoutMs = DEFAULT_CONNECT_TIMEOUT_MS;
        private IndexParam.MetricType metricType = IndexParam.MetricType.COSINE;
        private String databaseName = DEFAULT_DATABASE_NAME;
        private Map<String, String> databaseProperties;

        private Builder() {}

        /**
         * 设置 Milvus 服务 URI。
         *
         * @param uri Milvus URI，如 http://localhost:19530
         * @return 当前构建器
         */
        public Builder uri(String uri) {
            this.uri = uri;
            return this;
        }

        /**
         * 设置集合名称。
         *
         * @param collectionName 使用的集合名称
         * @return 当前构建器
         */
        public Builder collectionName(String collectionName) {
            this.collectionName = collectionName;
            return this;
        }

        /**
         * 设置向量维度。
         *
         * @param dimensions 待存储向量维度
         * @return 当前构建器
         */
        public Builder dimensions(int dimensions) {
            this.dimensions = dimensions;
            return this;
        }

        /**
         * 设置认证令牌。
         *
         * <p>基础认证令牌使用 username:password 格式。
         *
         * @param token 认证令牌
         * @return 当前构建器
         */
        public Builder token(String token) {
            this.token = token;
            return this;
        }

        /**
         * 设置认证用户名。
         *
         * <p>可替代 token()；同时设置时 token 优先。
         *
         * @param username 用户名
         * @return 当前构建器
         */
        public Builder username(String username) {
            this.username = username;
            return this;
        }

        /**
         * 设置认证密码。
         *
         * <p>应与 username() 配合使用。
         *
         * @param password 密码
         * @return 当前构建器
         */
        public Builder password(String password) {
            this.password = password;
            return this;
        }

        /**
         * 设置连接超时（毫秒）。
         *
         * <p>默认 30000（30 秒）；0 或负数禁用超时。
         *
         * @param connectTimeoutMs 连接超时毫秒数
         * @return 当前构建器
         */
        public Builder connectTimeoutMs(long connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
            return this;
        }

        /**
         * 设置向量相似度搜索度量类型。
         *
         * <p>默认 COSINE，其他选项为 L2（欧氏）和 IP（内积）。打开已有集合时必须与其向量索引匹配。L2 距离在阈值过滤前转换为 {@code 1 / (1 + distance)} 分数。
         *
         * @param metricType 度量类型
         * @return 当前构建器
         */
        public Builder metricType(IndexParam.MetricType metricType) {
            this.metricType = metricType;
            return this;
        }

        /**
         * 设置数据库名称。
         *
         * @param databaseName 数据库名称
         * @return 当前构建器
         */
        public Builder databaseName(String databaseName) {
            this.databaseName = databaseName;
            return this;
        }

        /**
         * 设置数据库属性。
         *
         * @param databaseProperties 数据库属性
         * @return 当前构建器
         */
        public Builder databaseProperties(Map<String, String> databaseProperties) {
            this.databaseProperties = databaseProperties;
            return this;
        }

        /**
         * 构建 MilvusStore 实例。
         *
         * <p>构造时立即建立客户端连接，连接或创建集合失败时抛出 VectorStoreException。
         *
         * @return 新的 MilvusStore 实例
         * @throws IllegalArgumentException 当必填参数无效时
         * @throws VectorStoreException 当客户端初始化或创建集合失败时
         */
        public MilvusStore build() throws VectorStoreException {
            if (uri == null || uri.trim().isEmpty()) {
                throw new IllegalArgumentException("URI cannot be null or empty");
            }
            if (collectionName == null || collectionName.trim().isEmpty()) {
                throw new IllegalArgumentException("Collection name cannot be null or empty");
            }
            if (dimensions <= 0) {
                throw new IllegalArgumentException("Dimensions must be positive");
            }
            if (metricType == null) {
                throw new IllegalArgumentException("Metric type cannot be null");
            }
            if (databaseName == null || databaseName.trim().isEmpty()) {
                throw new IllegalArgumentException("Database name cannot be null or empty");
            }
            return new MilvusStore(this);
        }
    }

    /**
     * 创建 MilvusStore 构建器。
     *
     * @return 新的构建器实例
     */
    public static Builder builder() {
        return new Builder();
    }
}
