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

import com.google.common.util.concurrent.ListenableFuture;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.exception.VectorStoreException;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import io.agentscope.core.rag.store.dto.SearchDocumentDto;
import io.agentscope.core.util.JsonUtils;
import io.qdrant.client.PointIdFactory;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.QueryFactory;
import io.qdrant.client.ValueFactory;
import io.qdrant.client.VectorFactory;
import io.qdrant.client.VectorsFactory;
import io.qdrant.client.WithPayloadSelectorFactory;
import io.qdrant.client.WithVectorsSelectorFactory;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.Common.PointId;
import io.qdrant.client.grpc.JsonWithInt;
import io.qdrant.client.grpc.JsonWithInt.Struct;
import io.qdrant.client.grpc.JsonWithInt.Value;
import io.qdrant.client.grpc.Points;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.QueryPoints;
import io.qdrant.client.grpc.Points.ScoredPoint;
import io.qdrant.client.grpc.Points.Vector;
import io.qdrant.client.grpc.Points.Vectors;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Qdrant 向量数据库存储实现。
 *
 * <p>通过 Qdrant 存储与搜索向量，实现 VDBStoreBase 的统一操作接口。
 *
 * <p>用 payload 字段保存文档 ID、分块 ID、原始内容等元数据。
 *
 * <p>使用 Qdrant Java SDK（io.qdrant:client），依赖示例：
 * <pre>{@code
 * <dependency>
 *     <groupId>io.qdrant</groupId>
 *     <artifactId>client</artifactId>
 *     <version>1.15.0</version>
 *     <scope>provided</scope>
 * </dependency>
 * }</pre>
 *
 * <p>用法示例：
 * <pre>{@code
 * // 使用带 TLS 和兼容性选项的构建器（推荐）
 * try (QdrantStore store = QdrantStore.builder()
 *         .location("http://localhost:6333")
 *         .collectionName("my_collection")
 *         .dimensions(1024)
 *         .apiKey("my-api-key")
 *         .useTransportLayerSecurity(true)
 *         .checkCompatibility(false)
 *         .build()) {
 *     store.add(document).block();
 *     List<Document> results = store.search(queryEmbedding, 5).block();
 *     // 存储在此处自动关闭
 * }
 *
 * // 使用静态工厂方法（更简单，采用默认值）
 * try (QdrantStore store = QdrantStore.create("http://localhost:6333", "my_collection", 1024)) {
 *     store.add(document).block();
 *     List<Document> results = store.search(queryEmbedding, 5).block();
 * }
 * }</pre>
 *
 * <p>location 参数形式：
 * <ul>
 * <li>HTTP URL：http://localhost:6333，连接时使用 gRPC 端口 6334。</li>
 * <li>gRPC 地址：localhost:6334，直接连接 gRPC。</li>
 * <li>本地路径：file:///path/to/qdrant，参见连接解析逻辑，并非在 JVM 中自动启动数据库。</li>
 * <li>内存标记：:memory:，仍依赖可连接的本地 Qdrant 服务。</li>
 * </ul>
 */
public class QdrantStore implements VDBStoreBase, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(QdrantStore.class);

    private final String location;
    private final String collectionName;
    private final int dimensions;
    private final String apiKey;
    private final boolean useTransportLayerSecurity;
    private final boolean checkCompatibility;
    private final QdrantClient qdrantClient;
    private final QdrantGrpcClient grpcClient;
    private volatile boolean closed = false;

    /**
     * 按构建器配置创建 QdrantStore。
     *
     * @param builder 构建器实例
     * @throws VectorStoreException 当客户端初始化或创建集合失败时
     */
    private QdrantStore(Builder builder) throws VectorStoreException {
        this.location = builder.location;
        this.collectionName = builder.collectionName;
        this.dimensions = builder.dimensions;
        this.apiKey = builder.apiKey;
        this.useTransportLayerSecurity = builder.useTransportLayerSecurity;
        this.checkCompatibility = builder.checkCompatibility;

        // 立即初始化客户端与集合。
        QdrantGrpcClient tempGrpcClient = null;
        QdrantClient tempQdrantClient = null;

        try {
            ConnectionInfo connInfo = parseLocation(location);

            // 使用 TLS 和兼容性检查选项构建 gRPC 客户端。
            QdrantGrpcClient.Builder grpcClientBuilder =
                    QdrantGrpcClient.newBuilder(
                            connInfo.host,
                            connInfo.port,
                            useTransportLayerSecurity,
                            checkCompatibility);

            if (apiKey != null && !apiKey.trim().isEmpty()) {
                grpcClientBuilder = grpcClientBuilder.withApiKey(apiKey);
            }

            tempGrpcClient = grpcClientBuilder.build();

            // 创建 QdrantClient。
            tempQdrantClient = new QdrantClient(tempGrpcClient);

            log.debug(
                    "Initialized Qdrant client: host={}, port={}, collection={}",
                    connInfo.host,
                    connInfo.port,
                    collectionName);

            // 确保集合存在。
            ensureCollection(tempQdrantClient);

            // 仅在初始化成功后赋值给 final 字段。
            this.grpcClient = tempGrpcClient;
            this.qdrantClient = tempQdrantClient;

            log.debug("QdrantStore initialized successfully for collection: {}", collectionName);
        } catch (Exception e) {
            // 初始化失败时清理资源。
            try {
                if (tempQdrantClient != null) {
                    tempQdrantClient.close();
                }
            } catch (Exception cleanupException) {
                log.warn(
                        "Error closing QdrantClient after initialization failure",
                        cleanupException);
            }
            try {
                if (tempGrpcClient != null) {
                    tempGrpcClient.close();
                }
            } catch (Exception cleanupException) {
                log.warn(
                        "Error closing QdrantGrpcClient after initialization failure",
                        cleanupException);
            }
            throw new VectorStoreException("Failed to initialize QdrantStore", e);
        }
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
                                addDocumentsToQdrant(documents);
                                return null;
                            } catch (Exception e) {
                                throw new VectorStoreException(
                                        "Failed to add documents to Qdrant", e);
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(
                        e ->
                                e instanceof VectorStoreException
                                        ? e
                                        : new VectorStoreException(
                                                "Failed to add documents to Qdrant", e))
                .then();
    }

    @Override
    public Mono<List<Document>> search(SearchDocumentDto searchDocumentDto) {
        String vectorName = searchDocumentDto.getVectorName();
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
                                return queryDocumentsInQdrant(
                                        vectorName, queryEmbedding, limit, scoreThreshold);
                            } catch (Exception e) {
                                throw new VectorStoreException(
                                        "Failed to search documents in Qdrant", e);
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(
                        e ->
                                e instanceof VectorStoreException
                                        ? e
                                        : new VectorStoreException(
                                                "Failed to search documents in Qdrant", e));
    }

    @Override
    public Mono<Boolean> delete(String id) {
        if (id == null || id.trim().isEmpty()) {
            return Mono.error(new IllegalArgumentException("Document ID cannot be null or empty"));
        }

        return Mono.fromCallable(
                        () -> {
                            try {
                                ensureNotClosed();
                                return deleteDocumentsInQdrant(id);
                            } catch (Exception e) {
                                throw new VectorStoreException(
                                        "Failed to delete document in Qdrant", e);
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(
                        e ->
                                e instanceof VectorStoreException
                                        ? e
                                        : new VectorStoreException(
                                                "Failed to delete document in Qdrant", e));
    }

    /**
     * 获取底层 Qdrant 客户端以执行高级操作。
     *
     * <p>用于超出 VDBStoreBase 接口的操作，例如：
     * <ul>
     * <li>自定义索引配置。</li>
     * <li>高级过滤与分面。</li>
     * <li>集合管理。</li>
     * <li>批量操作。</li>
     * </ul>
     *
     * @return QdrantClient 实例
     * @throws VectorStoreException 当存储已关闭时
     */
    public QdrantClient getClient() throws VectorStoreException {
        ensureNotClosed();
        return qdrantClient;
    }

    /**
     * 获取底层 gRPC 客户端以执行低层操作。
     *
     * <p>为需要细粒度控制 Qdrant 操作的用户提供直接访问。
     *
     * @return QdrantGrpcClient 实例
     * @throws VectorStoreException 当存储已关闭时
     */
    public QdrantGrpcClient getGrpcClient() throws VectorStoreException {
        ensureNotClosed();
        return grpcClient;
    }

    /**
     * 操作前确保存储未关闭。
     *
     * @throws VectorStoreException 当存储已关闭时
     */
    private void ensureNotClosed() throws VectorStoreException {
        if (closed) {
            throw new VectorStoreException("QdrantStore has been closed");
        }
    }

    /**
     * 确保集合存在，必要时创建。
     *
     * @param client 使用的 QdrantClient
     * @throws VectorStoreException 当创建集合失败时
     */
    private void ensureCollection(QdrantClient client) throws VectorStoreException {
        try {
            // 检查集合是否存在。
            ListenableFuture<Boolean> existsFuture = client.collectionExistsAsync(collectionName);
            Boolean exists = existsFuture.get();
            if (Boolean.TRUE.equals(exists)) {
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
     * 按指定维度创建集合。
     *
     * @param client 使用的 QdrantClient
     * @throws Exception 当创建集合失败时
     */
    private void createCollection(QdrantClient client) throws Exception {
        VectorParams vectorParams =
                VectorParams.newBuilder().setDistance(Distance.Cosine).setSize(dimensions).build();

        ListenableFuture<?> future = client.createCollectionAsync(collectionName, vectorParams);
        future.get();
    }

    /**
     * 向 Qdrant 添加多条文档。
     *
     * <p>payload 保存文档 ID、分块 ID 和原始内容等元数据。
     *
     * <p>根据 doc_id、chunk_id 和内容生成确定性 UUID，匹配 Python 的 _map_text_to_uuid 函数。
     *
     * @param documents 待存储文档，必须已设置嵌入
     * @throws Exception 当操作失败时
     */
    private void addDocumentsToQdrant(List<Document> documents) throws Exception {
        List<PointStruct> points =
                documents.stream()
                        .map(
                                document -> {
                                    try {
                                        // 将 double[] 转换为 List<Float>。
                                        double[] embedding = document.getEmbedding();
                                        List<Float> floatList = new ArrayList<>(embedding.length);
                                        for (double d : embedding) {
                                            floatList.add((float) d);
                                        }

                                        // 生成确定性 UUID 点 ID。
                                        PointId pointId =
                                                PointIdFactory.id(
                                                        UUID.fromString(document.getId()));

                                        // 构建 Vector。
                                        Vector vector = VectorFactory.vector(floatList);
                                        String vectorName = document.getVectorName();

                                        Vectors vectors =
                                                vectorName == null
                                                        ? VectorsFactory.vectors(vector)
                                                        : VectorsFactory.namedVectors(
                                                                Map.of(vectorName, vector));

                                        // 构建含向量与载荷的 PointStruct。
                                        PointStruct.Builder pointBuilder =
                                                PointStruct.newBuilder()
                                                        .setId(pointId)
                                                        .setVectors(vectors);

                                        // 将 DocumentMetadata 转换为载荷映射。
                                        Map<String, Value> payloadMap =
                                                convertMetadataToPayload(document.getMetadata());
                                        pointBuilder.putAllPayload(payloadMap);

                                        return pointBuilder.build();
                                    } catch (Exception e) {
                                        throw new RuntimeException(
                                                "Failed to convert document to PointStruct", e);
                                    }
                                })
                        .collect(Collectors.toList());

        // 插入或更新向量点。
        ListenableFuture<?> future = qdrantClient.upsertAsync(collectionName, points);
        future.get();
    }

    /**
     * 将 DocumentMetadata 转换为 Qdrant 载荷映射。
     *
     * <p>将 ContentBlock 序列化为 JSON 并保存为 Qdrant Value，同时保存全部自定义载荷字段。
     *
     * @param metadata 文档元数据
     * @return 载荷键值映射
     */
    private Map<String, Value> convertMetadataToPayload(DocumentMetadata metadata) {
        Map<String, Value> payloadMap = new HashMap<>();

        try {
            // 将 ContentBlock 序列化为 JSON，再转为 Qdrant Value。
            String contentJson = JsonUtils.getJsonCodec().toJson(metadata.getContent());
            @SuppressWarnings("unchecked")
            Map<String, Object> contentMap =
                    JsonUtils.getJsonCodec().fromJson(contentJson, Map.class);
            Value contentValue = convertObjectToValue(contentMap);
            payloadMap.put("content", contentValue);
        } catch (Exception e) {
            log.error("Failed to serialize ContentBlock, using fallback", e);
            // 回退：作为字符串保存。
            Value contentValue = ValueFactory.value(metadata.getContentText());
            payloadMap.put("content", contentValue);
        }

        // 保存 doc_id。
        Value docIdValue = ValueFactory.value(metadata.getDocId());
        payloadMap.put("doc_id", docIdValue);

        // 保存 chunk_id。
        Value chunkIdValue = ValueFactory.value(metadata.getChunkId());
        payloadMap.put("chunk_id", chunkIdValue);

        // 将自定义载荷保存为嵌套 Struct（字典）。
        Map<String, Object> customPayload = metadata.getPayload();
        if (customPayload != null && !customPayload.isEmpty()) {
            Value payloadValue = convertObjectToValue(customPayload);
            payloadMap.put("payload", payloadValue);
        }

        return payloadMap;
    }

    /**
     * 将 Java Object 转换为 Qdrant Value。
     *
     * @param obj 待转换对象
     * @return Qdrant 值对象
     */
    private Value convertObjectToValue(Object obj) {
        if (obj == null) {
            return ValueFactory.nullValue();
        } else if (obj instanceof String) {
            return ValueFactory.value((String) obj);
        } else if (obj instanceof Number) {
            if (obj instanceof Double || obj instanceof Float) {
                return ValueFactory.value(((Number) obj).doubleValue());
            } else {
                return ValueFactory.value(((Number) obj).longValue());
            }
        } else if (obj instanceof Boolean) {
            return ValueFactory.value((Boolean) obj);
        } else if (obj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Value> valueMap =
                    ((Map<String, Object>) obj)
                            .entrySet().stream()
                                    .collect(
                                            Collectors.toMap(
                                                    Map.Entry::getKey,
                                                    entry ->
                                                            convertObjectToValue(
                                                                    entry.getValue())));
            return ValueFactory.value(valueMap);
        } else if (obj instanceof List) {
            @SuppressWarnings("unchecked")
            List<Value> values =
                    ((List<Object>) obj)
                            .stream().map(this::convertObjectToValue).collect(Collectors.toList());
            return ValueFactory.list(values);
        } else {
            // 自定义对象先用 ObjectMapper 序列化为 Map。
            try {
                String json = JsonUtils.getJsonCodec().toJson(obj);
                @SuppressWarnings("unchecked")
                Map<String, Object> map = JsonUtils.getJsonCodec().fromJson(json, Map.class);
                return convertObjectToValue(map);
            } catch (Exception e) {
                log.warn(
                        "Failed to serialize custom object of type "
                                + obj.getClass().getName()
                                + ", using string representation",
                        e);
                return ValueFactory.value(obj.toString());
            }
        }
    }

    /**
     * 在 Qdrant 搜索相似文档。
     *
     * @param vectorName 向量名称
     * @param queryEmbedding 查询向量
     * @param limit 最大结果数
     * @param scoreThreshold 可选最低分数阈值
     * @return 已设置分数和嵌入的文档列表
     * @throws Exception 当操作失败时
     */
    private List<Document> queryDocumentsInQdrant(
            String vectorName, double[] queryEmbedding, int limit, Double scoreThreshold)
            throws Exception {
        // 将 double[] 转换为 List<Float>。
        List<Float> floatList = new ArrayList<>(queryEmbedding.length);
        for (double d : queryEmbedding) {
            floatList.add((float) d);
        }

        // 构建 QueryPoints。
        QueryPoints.Builder queryBuilder =
                QueryPoints.newBuilder()
                        .setCollectionName(collectionName)
                        .setQuery(QueryFactory.nearest(floatList))
                        .setLimit(limit)
                        .setWithPayload(
                                WithPayloadSelectorFactory.enable(
                                        true)) // 请求结果包含载荷。
                        .setWithVectors(
                                WithVectorsSelectorFactory.enable(
                                        true)); // 请求结果包含嵌入向量。

        // 指定时加入向量名称。
        if (vectorName != null) {
            queryBuilder.setUsing(vectorName);
        }

        // 指定时加入分数阈值。
        if (scoreThreshold != null) {
            queryBuilder.setScoreThreshold(scoreThreshold.floatValue());
        }

        QueryPoints queryPoints = queryBuilder.build();

        // 执行搜索。
        ListenableFuture<List<ScoredPoint>> future = qdrantClient.queryAsync(queryPoints);
        List<ScoredPoint> scoredPoints = future.get();

        // 提取结果并重建文档。
        List<Document> results = new ArrayList<>();
        for (ScoredPoint scoredPoint : scoredPoints) {
            // 获取 ID（UUID 字符串）。
            PointId pointId = scoredPoint.getId();
            String id = pointId.getUuid();

            // 获取分数。
            double score = scoredPoint.getScore();

            // 从载荷与向量重建文档。
            Document document = reconstructDocumentFromPayload(scoredPoint, id, vectorName, score);
            if (document != null) {
                results.add(document);
            } else {
                log.warn("Failed to reconstruct document from payload for ID: {}", id);
            }
        }

        return results;
    }

    private Boolean deleteDocumentsInQdrant(String id) throws Exception {
        ListenableFuture<Points.UpdateResult> future =
                qdrantClient.deleteAsync(
                        collectionName, List.of(PointIdFactory.id(UUID.fromString(id))));
        Points.UpdateResult updateResult = future.get();
        return updateResult.getStatus() == Points.UpdateStatus.Completed;
    }

    /**
     * 从 Qdrant 载荷和向量重建 Document。
     *
     * <p>直接从载荷映射重建 DocumentMetadata 并提取向量，类似 Python：Document(metadata=DocMetadata(**point.payload), embedding=point.vector, score=point.score)。
     *
     * @param scoredPoint Qdrant 返回的带分数向量点
     * @param id 文档 ID（UUID 字符串）
     * @param vectorName 向量名称
     * @param score 相似度分数
     * @return 重建文档；失败时为 null
     */
    private Document reconstructDocumentFromPayload(
            ScoredPoint scoredPoint, String id, String vectorName, double score) {
        try {
            Map<String, Value> payload = scoredPoint.getPayloadMap();
            if (payload == null || payload.isEmpty()) {
                log.warn("Payload missing for document ID: {}", id);
                return null;
            }

            // 从载荷重建 DocumentMetadata。
            DocumentMetadata metadata = reconstructMetadataFromPayload(payload);

            Document document = new Document(metadata);
            document.setScore(score);

            if (vectorName != null) {
                document.setVectorName(vectorName);
            }

            // 存在时从 vectors 提取嵌入。
            if (scoredPoint.hasVectors()) {
                var vectorsOutput = scoredPoint.getVectors();
                if (vectorsOutput.hasVector()) {
                    var vectorOutput = vectorsOutput.getVector();
                    List<Float> floatList = vectorOutput.getDense().getDataList();
                    double[] embedding = new double[floatList.size()];
                    for (int i = 0; i < floatList.size(); i++) {
                        embedding[i] = floatList.get(i);
                    }
                    document.setEmbedding(embedding);
                }
            }

            return document;
        } catch (Exception e) {
            log.error("Failed to reconstruct document from payload for ID: {}", id, e);
            return null;
        }
    }

    /**
     * 从 Qdrant 载荷映射重建 DocumentMetadata。
     *
     * <p>将 content 从 JSON 反序列化为 ContentBlock，并提取全部自定义载荷字段。
     *
     * @param payload Qdrant 载荷映射
     * @return 重建后的 DocumentMetadata
     * @throws IllegalArgumentException 当缺少必填字段时
     */
    private DocumentMetadata reconstructMetadataFromPayload(Map<String, Value> payload) {
        // 提取 content 并反序列化为 ContentBlock。
        Value contentValue = payload.get("content");
        if (contentValue == null) {
            throw new IllegalArgumentException("Payload missing 'content' field");
        }

        ContentBlock content;
        try {
            // Qdrant Value 先转 Map，再转 JSON，最后转 ContentBlock。
            Object contentObj = convertValueToObject(contentValue);
            String contentJson = JsonUtils.getJsonCodec().toJson(contentObj);
            content = JsonUtils.getJsonCodec().fromJson(contentJson, ContentBlock.class);
        } catch (Exception e) {
            log.error("Failed to deserialize ContentBlock from payload, using fallback", e);
            // 回退：从字符串表示创建 TextBlock。
            String textContent = contentValue.toString();
            content = TextBlock.builder().text(textContent).build();
        }

        // 提取 doc_id。
        Value docIdValue = payload.get("doc_id");
        if (docIdValue == null || !docIdValue.hasStringValue()) {
            throw new IllegalArgumentException("Payload missing 'doc_id' field");
        }
        String docId = docIdValue.getStringValue();

        // 提取 chunk_id。
        Value chunkIdValue = payload.get("chunk_id");
        if (chunkIdValue == null || !chunkIdValue.hasStringValue()) {
            throw new IllegalArgumentException("Payload missing 'chunk_id' field");
        }
        String chunkId = chunkIdValue.getStringValue();

        // 从 payload 字段（嵌套 Struct）提取自定义载荷。
        Map<String, Object> customPayload = new HashMap<>();
        Value payloadValue = payload.get("payload");
        if (payloadValue != null) {
            Object payloadObj = convertValueToObject(payloadValue);
            if (payloadObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> payloadMap = (Map<String, Object>) payloadObj;
                customPayload = payloadMap;
            } else {
                log.warn(
                        "Expected Map type for payload but got "
                                + (payloadObj != null ? payloadObj.getClass().getName() : "null"));
                customPayload = new HashMap<>();
            }
        }

        return new DocumentMetadata(content, docId, chunkId, customPayload);
    }

    /**
     * 将 Qdrant Value 转换为 Java Object。
     *
     * @param value Qdrant 值对象
     * @return Java 对象
     */
    private Object convertValueToObject(Value value) {
        if (value.hasNullValue()) {
            return null;
        } else if (value.hasStringValue()) {
            return value.getStringValue();
        } else if (value.hasIntegerValue()) {
            return value.getIntegerValue();
        } else if (value.hasDoubleValue()) {
            return value.getDoubleValue();
        } else if (value.hasBoolValue()) {
            return value.getBoolValue();
        } else if (value.hasStructValue()) {
            Struct struct = value.getStructValue();
            Map<String, Object> map = new HashMap<>();
            for (Map.Entry<String, Value> entry : struct.getFieldsMap().entrySet()) {
                map.put(entry.getKey(), convertValueToObject(entry.getValue()));
            }
            return map;
        } else if (value.hasListValue()) {
            JsonWithInt.ListValue listValue = value.getListValue();
            List<Object> list = new ArrayList<>();
            for (Value item : listValue.getValuesList()) {
                list.add(convertValueToObject(item));
            }
            return list;
        } else {
            throw new IllegalArgumentException("Unknown value type: " + value.getKindCase());
        }
    }

    /**
     * 解析 location 字符串，提取主机与端口。
     *
     * <p>【学习提示】:memory: 与 file:// 在这里都映射到 localhost:6334，不会嵌入式启动服务或读取该目录。HTTPS 地址也不能替代显式 TLS 配置；连接位置解析和传输加密开关是不同配置。
     *
     * @param location 位置字符串，HTTP URL、gRPC 地址或文件路径
     * @return 连接信息
     */
    private ConnectionInfo parseLocation(String location) {
        // 处理 :memory: 标记。
        if (":memory:".equals(location)) {
            return new ConnectionInfo("localhost", 6334);
        }

        // 处理 file:// 协议（本地 Qdrant）。
        if (location.startsWith("file://")) {
            return new ConnectionInfo("localhost", 6334);
        }

        try {
            URI uri = URI.create(location);
            String host = uri.getHost();
            int port = uri.getPort();

            // HTTP URL 默认端口 6333 转用 gRPC 端口 6334。
            if ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) {
                if (port == 6333 || port == -1) {
                    port = 6334; // 默认 gRPC 端口。
                }
            }

            // 未指定协议和端口时按直接 gRPC 连接处理。
            if (host == null && port == -1) {
                // 尝试按 host:port 解析。
                if (location.contains(":")) {
                    String[] parts = location.split(":", 2);
                    host = parts[0];
                    try {
                        port = Integer.parseInt(parts[1]);
                    } catch (NumberFormatException e) {
                        port = 6334; // 默认 gRPC 端口。
                    }
                } else {
                    host = location;
                    port = 6334; // 默认 gRPC 端口。
                }
            }

            if (host == null) {
                host = "localhost";
            }
            if (port == -1) {
                port = 6334; // 默认 gRPC 端口。
            }

            return new ConnectionInfo(host, port);
        } catch (Exception e) {
            // 回退：按主机名处理。
            log.warn("Failed to parse location '{}', using as hostname", location, e);
            return new ConnectionInfo(location, 6334);
        }
    }

    /**
     * 连接信息容器。
     */
    private static class ConnectionInfo {
        final String host;
        final int port;

        ConnectionInfo(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }

    /**
     * 获取 Qdrant 服务位置。
     *
     * @return 位置
     */
    public String getLocation() {
        return location;
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
     * 获取 API 密钥（若已设置）。
     *
     * @return API 密钥；未设置时为 null
     */
    public String getApiKey() {
        return apiKey;
    }

    /**
     * 关闭 Qdrant 客户端并释放全部资源。
     *
     * <p>关闭 QdrantClient 与底层 QdrantGrpcClient，释放全部 gRPC 连接及关联资源。操作幂等，可安全重复调用。
     *
     * <p>关闭后的存储操作抛出 VectorStoreException。建议使用 try-with-resources 自动管理；下方保留上游示例：
     * <pre>{@code
     * try (QdrantStore store = new QdrantStore("http://localhost:6333", "collection", 1024)) {
     *     store.add(1L, embedding).block();
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
                if (qdrantClient != null) {
                    log.debug("Closing QdrantClient for collection: {}", collectionName);
                    qdrantClient.close();
                }
            } catch (Exception e) {
                log.warn("Error closing QdrantClient", e);
            }

            try {
                if (grpcClient != null) {
                    log.debug("Closing QdrantGrpcClient for collection: {}", collectionName);
                    grpcClient.close();
                }
            } catch (Exception e) {
                log.warn("Error closing QdrantGrpcClient", e);
            }

            log.debug("QdrantStore closed for collection: {}", collectionName);
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
     * 通过流式配置创建 QdrantStore 的构建器。
     *
     * <p>用法示例：
     * <pre>{@code
     * QdrantStore store = QdrantStore.builder()
     *     .location("http://localhost:6333")
     *     .collectionName("my_collection")
     *     .dimensions(1024)
     *     .apiKey("my-api-key")
     *     .useTransportLayerSecurity(true)
     *     .checkCompatibility(false)
     *     .build();
     * }</pre>
     */
    public static class Builder {
        private String location;
        private String collectionName;
        private int dimensions;
        private String apiKey;
        private boolean useTransportLayerSecurity = true;
        private boolean checkCompatibility = true;

        private Builder() {}

        /**
         * 设置 Qdrant 服务位置。
         *
         * @param location 服务位置，如 http://localhost:6333 或 localhost:6334
         * @return 当前构建器
         */
        public Builder location(String location) {
            this.location = location;
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
         * 设置认证 API 密钥。
         *
         * @param apiKey 可选 API 密钥，null 表示无认证
         * @return 当前构建器
         */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /**
         * 设置 gRPC 连接是否使用 TLS。
         *
         * <p>默认 false，设为 true 可启用 TLS 加密。
         *
         * @param useTransportLayerSecurity true 启用 TLS，否则禁用
         * @return 当前构建器
         */
        public Builder useTransportLayerSecurity(boolean useTransportLayerSecurity) {
            this.useTransportLayerSecurity = useTransportLayerSecurity;
            return this;
        }

        /**
         * 设置是否检查客户端与服务端兼容性。
         *
         * <p>默认 true。设为 false 跳过版本检查，适用于连接旧服务端或兼容性检查误报等情况。
         *
         * @param checkCompatibility true 启用检查，false 跳过
         * @return 当前构建器
         */
        public Builder checkCompatibility(boolean checkCompatibility) {
            this.checkCompatibility = checkCompatibility;
            return this;
        }

        /**
         * 构建 QdrantStore 实例。
         *
         * <p>构造时立即建立客户端连接。连接或创建集合失败时抛出 VectorStoreException。
         *
         * @return 新的 QdrantStore 实例
         * @throws IllegalArgumentException 当必填参数无效时
         * @throws VectorStoreException 当客户端初始化或创建集合失败时
         */
        public QdrantStore build() throws VectorStoreException {
            if (location == null || location.trim().isEmpty()) {
                throw new IllegalArgumentException("Location cannot be null or empty");
            }
            if (collectionName == null || collectionName.trim().isEmpty()) {
                throw new IllegalArgumentException("Collection name cannot be null or empty");
            }
            if (dimensions <= 0) {
                throw new IllegalArgumentException("Dimensions must be positive");
            }

            return new QdrantStore(this);
        }
    }

    /**
     * 创建 QdrantStore 构建器。
     *
     * @return 新的构建器实例
     */
    public static Builder builder() {
        return new Builder();
    }
}
