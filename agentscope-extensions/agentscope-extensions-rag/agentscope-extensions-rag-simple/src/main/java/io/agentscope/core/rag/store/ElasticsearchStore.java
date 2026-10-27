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

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.mapping.DenseVectorProperty;
import co.elastic.clients.elasticsearch._types.mapping.DenseVectorSimilarity;
import co.elastic.clients.elasticsearch._types.mapping.KeywordProperty;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch._types.mapping.TextProperty;
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest5_client.Rest5ClientTransport;
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.exception.VectorStoreException;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import io.agentscope.core.rag.store.dto.SearchDocumentDto;
import io.agentscope.core.util.JsonUtils;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.net.ssl.SSLContext;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.client5.http.ssl.DefaultClientTlsStrategy;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.ssl.SSLContextBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Elasticsearch 向量数据库实现。
 *
 * <p>使用官方 {@code elasticsearch-java} 客户端存储和搜索向量。
 *
 * <p>用 {@code dense_vector} 字段保存嵌入，通过 kNN 搜索检索。
 *
 * <p>用法示例：
 * <pre>{@code
 * // Using builder with authentication
 * try (ElasticsearchStore store = ElasticsearchStore.builder()
 * .url("http://localhost:9200")
 * .indexName("my_rag_index")
 * .dimensions(1024)
 * .username("elastic")
 * .password("changeme")
 * .build()) {
 *
 * store.add(document).block();
 * List<Document> results = store.search(queryEmbedding, 5, 0.7).block();
 * }
 * }</pre>
 */
public class ElasticsearchStore implements VDBStoreBase, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ElasticsearchStore.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // Elasticsearch 映射字段名。
    private static final String FIELD_ID = "id";
    private static final String FIELD_VECTOR = "vector";
    private static final String FIELD_DOC_ID = "doc_id";
    private static final String FIELD_CHUNK_ID = "chunk_id";
    private static final String FIELD_CONTENT = "content";
    private static final String FIELD_PAYLOAD = "payload";

    private final String indexName;
    private final int dimensions;
    private final Rest5Client restClient;
    private final ElasticsearchTransport transport;
    private final ElasticsearchClient client;
    private final boolean disableSslVerification;

    private volatile boolean closed = false;

    private ElasticsearchStore(Builder builder) throws VectorStoreException {
        this.indexName = builder.indexName;
        this.dimensions = builder.dimensions;
        this.disableSslVerification = builder.disableSslVerification;
        try {
            // 1. 配置低层 RestClient。
            BasicCredentialsProvider credsProv = new BasicCredentialsProvider();
            if (builder.username != null && builder.password != null) {
                credsProv.setCredentials(
                        new AuthScope(null, -1),
                        new UsernamePasswordCredentials(
                                builder.username, builder.password.toCharArray()));
            }

            final SSLContext sslContext;
            if (this.disableSslVerification) {
                sslContext =
                        SSLContextBuilder.create()
                                .loadTrustMaterial(null, (chain, authType) -> true)
                                .build();
            } else {
                sslContext = SSLContext.getDefault();
            }

            HttpHost host = HttpHost.create(builder.url);

            this.restClient =
                    Rest5Client.builder(host)
                            .setHttpClientConfigCallback(
                                    httpClientBuilder -> {
                                        if (builder.username != null) {
                                            httpClientBuilder.setDefaultCredentialsProvider(
                                                    credsProv);
                                        }
                                    })
                            .setConnectionManagerCallback(
                                    connectionManager -> {
                                        if (builder.url != null
                                                && builder.url.startsWith("https")
                                                && builder.disableSslVerification) {
                                            connectionManager.setTlsStrategy(
                                                    new DefaultClientTlsStrategy(
                                                            sslContext,
                                                            NoopHostnameVerifier.INSTANCE));
                                        }
                                    })
                            .build();

            // 2. 创建传输层与客户端。
            this.transport =
                    new Rest5ClientTransport(restClient, new JacksonJsonpMapper(OBJECT_MAPPER));
            this.client = new ElasticsearchClient(transport);

            // 3. 确保索引存在。
            ensureIndex();

            log.debug("ElasticsearchStore initialized successfully for index: {}", indexName);

        } catch (Exception e) {
            // 失败时清理资源。
            closeQuietly();
            throw new VectorStoreException("Failed to initialize ElasticsearchStore", e);
        }
    }

    /**
     * 向 Elasticsearch 索引添加文档列表。
     *
     * @param documents 待添加文档
     * @return 操作结束时完成的 Mono
     */
    @Override
    public Mono<Void> add(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return Mono.empty();
        }

        return Mono.fromCallable(
                        () -> {
                            ensureNotClosed();
                            executeBulkAdd(documents);
                            return null;
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .doOnError(e -> log.error("Failed to add documents to Elasticsearch", e))
                .onErrorMap(
                        e -> !(e instanceof VectorStoreException),
                        e -> new VectorStoreException("Failed to add documents", e))
                .then();
    }

    private void executeBulkAdd(List<Document> documents) throws Exception {
        BulkRequest.Builder br = new BulkRequest.Builder();

        for (Document doc : documents) {
            validateDocument(doc);
            Map<String, Object> esDoc = mapToEsDocument(doc);

            br.operations(
                    op -> op.index(idx -> idx.index(indexName).id(doc.getId()).document(esDoc)));
        }

        BulkResponse response = client.bulk(br.build());
        if (response.errors()) {
            for (BulkResponseItem item : response.items()) {
                if (item.error() != null) {
                    log.error("Error indexing ID {}: {}", item.id(), item.error().reason());
                }
            }
            throw new VectorStoreException("Elasticsearch bulk indexing failed");
        }
    }

    /**
     * 在 Elasticsearch 索引搜索与查询向量匹配的文档。
     *
     * @param searchDocumentDto 包含查询向量、结果数上限及分数阈值的条件
     * @return 包含匹配文档列表的 Mono
     */
    @Override
    public Mono<List<Document>> search(SearchDocumentDto searchDocumentDto) {
        double[] queryEmbedding = searchDocumentDto.getQueryEmbedding();
        int limit = searchDocumentDto.getLimit();
        Double scoreThreshold = searchDocumentDto.getScoreThreshold();
        return Mono.fromCallable(
                        () -> {
                            ensureNotClosed();
                            return executeSearch(queryEmbedding, limit, scoreThreshold);
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .doOnError(e -> log.error("Error during vector search", e))
                .onErrorMap(e -> new VectorStoreException("Vector search failed", e));
    }

    private List<Document> executeSearch(double[] queryEmbedding, int limit, Double scoreThreshold)
            throws Exception {
        if (queryEmbedding.length != dimensions) {
            throw new VectorStoreException("Embedding dimension mismatch");
        }

        List<Float> queryVector = new ArrayList<>();
        for (double v : queryEmbedding) {
            queryVector.add((float) v);
        }

        SearchRequest searchRequest =
                SearchRequest.of(
                        s ->
                                s.index(indexName)
                                        .knn(
                                                k ->
                                                        k.field(FIELD_VECTOR)
                                                                .queryVector(queryVector)
                                                                .k(limit)
                                                                .numCandidates(
                                                                        Math.max(limit * 2, 50)))
                                        .size(limit)
                                        .minScore(scoreThreshold != null ? scoreThreshold : 0.0));

        SearchResponse<Map> response = client.search(searchRequest, Map.class);
        List<Document> results = new ArrayList<>();

        for (Hit<Map> hit : response.hits().hits()) {
            Document doc = mapFromEsHit(hit);
            if (doc != null) {
                results.add(doc);
            }
        }
        return results;
    }

    /**
     * 按 ID 从 Elasticsearch 索引删除文档。
     *
     * @param id 待删除文档 ID
     * @return 删除成功时发出 true，未找到时发出 false 的 Mono
     */
    @Override
    public Mono<Boolean> delete(String id) {
        if (id == null || id.trim().isEmpty()) {
            return Mono.error(new IllegalArgumentException("Document ID cannot be null or empty"));
        }

        return Mono.fromCallable(
                        () -> {
                            ensureNotClosed();
                            return client.delete(d -> d.index(indexName).id(id));
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .map(response -> response.result().name().equals("Deleted"))
                .onErrorMap(
                        e ->
                                new VectorStoreException(
                                        "Failed to delete document from Elasticsearch", e));
    }

    @Override
    public void close() {
        closeQuietly();
    }

    private void closeQuietly() {
        if (closed) return;
        synchronized (this) {
            if (closed) return;
            closed = true;
            try {
                if (transport != null) transport.close();
                if (restClient != null) restClient.close();
                log.debug("ElasticsearchStore closed for index: {}", indexName);
            } catch (Exception e) {
                log.warn("Error closing Elasticsearch client", e);
            }
        }
    }

    private void ensureNotClosed() throws VectorStoreException {
        if (closed) {
            throw new VectorStoreException("ElasticsearchStore has been closed");
        }
    }

    private void ensureIndex() throws VectorStoreException {
        try {
            ExistsRequest existsRequest = new ExistsRequest.Builder().index(indexName).build();

            boolean exists = client.indices().exists(existsRequest).value();
            if (exists) {
                log.debug("Index '{}' already exists", indexName);
                return;
            }

            log.debug("Creating index '{}' with dimensions {}", indexName, dimensions);

            // 定义字段映射。
            Property idProperty =
                    new Property.Builder().keyword(new KeywordProperty.Builder().build()).build();

            Property contentProperty =
                    new Property.Builder()
                            .text(new TextProperty.Builder().index(false).build())
                            .build();

            Property vectorProperty =
                    new Property.Builder()
                            .denseVector(
                                    new DenseVectorProperty.Builder()
                                            .dims(dimensions)
                                            .index(true)
                                            .similarity(DenseVectorSimilarity.Cosine)
                                            .build())
                            .build();

            Map<String, Property> properties = new HashMap<>();
            properties.put(FIELD_ID, idProperty);
            properties.put(FIELD_DOC_ID, idProperty);
            properties.put(FIELD_CHUNK_ID, idProperty);
            properties.put(FIELD_CONTENT, contentProperty);
            properties.put(FIELD_VECTOR, vectorProperty);
            properties.put(FIELD_PAYLOAD, contentProperty);

            TypeMapping mapping = new TypeMapping.Builder().properties(properties).build();

            CreateIndexRequest createRequest =
                    new CreateIndexRequest.Builder().index(indexName).mappings(mapping).build();

            client.indices().create(createRequest);
        } catch (Exception e) {
            throw new VectorStoreException("Failed to ensure index exists: " + indexName, e);
        }
    }

    private void validateDocument(Document document) throws VectorStoreException {
        if (document == null) {
            throw new IllegalArgumentException("Document cannot be null");
        }
        if (document.getEmbedding() == null) {
            throw new IllegalArgumentException("Document must have embedding set");
        }
        if (document.getEmbedding().length != dimensions) {
            throw new VectorStoreException(
                    String.format(
                            "Embedding dimension mismatch: expected %d, got %d",
                            dimensions, document.getEmbedding().length));
        }
    }

    private Map<String, Object> mapToEsDocument(Document doc) {
        Map<String, Object> map = new HashMap<>();
        map.put(FIELD_ID, doc.getId());

        // 将嵌入转换为列表。
        List<Float> embedding = new ArrayList<>(doc.getEmbedding().length);
        for (double d : doc.getEmbedding()) {
            embedding.add((float) d);
        }
        map.put(FIELD_VECTOR, embedding);

        // 元数据。
        DocumentMetadata meta = doc.getMetadata();
        map.put(FIELD_DOC_ID, meta.getDocId());
        map.put(FIELD_CHUNK_ID, meta.getChunkId());

        // 将 ContentBlock 序列化为 JSON 字符串，确保可存储和还原。
        try {
            String contentJson = OBJECT_MAPPER.writeValueAsString(meta.getContent());
            map.put(FIELD_CONTENT, contentJson);
        } catch (Exception e) {
            log.warn("Failed to serialize content, using text representation", e);
            map.put(FIELD_CONTENT, meta.getContentText());
        }

        Map<String, Object> customPayload = meta.getPayload();
        if (customPayload != null && !customPayload.isEmpty()) {
            map.put(FIELD_PAYLOAD, JsonUtils.getJsonCodec().toJson(customPayload));
        }

        return map;
    }

    @SuppressWarnings("unchecked")
    private Document mapFromEsHit(Hit<Map> hit) {
        try {
            Map<String, Object> source = hit.source();
            if (source == null) return null;

            String docId = (String) source.get(FIELD_DOC_ID);
            String chunkId = (String) source.get(FIELD_CHUNK_ID);
            String contentJson = (String) source.get(FIELD_CONTENT);
            String payloadJson = (String) source.get(FIELD_PAYLOAD);

            // 重建 ContentBlock。
            ContentBlock content;
            try {
                content = OBJECT_MAPPER.readValue(contentJson, ContentBlock.class);
            } catch (Exception e) {
                log.debug("Failed to deserialize ContentBlock, creating TextBlock", e);
                content = TextBlock.builder().text(contentJson).build();
            }

            Map<String, Object> customPayload = new HashMap<>();
            if (payloadJson != null && !payloadJson.isBlank()) {
                try {
                    customPayload =
                            JsonUtils.getJsonCodec()
                                    .fromJson(payloadJson, new TypeReference<>() {});
                } catch (Exception e) {
                    log.warn("Failed to deserialize payload, using empty map", e);
                }
            }

            DocumentMetadata metadata =
                    new DocumentMetadata(content, docId, chunkId, customPayload);
            Document doc = new Document(metadata);

            // 存在分数时设置。
            if (hit.score() != null) {
                doc.setScore(hit.score());
            }

            // 请求源数据时提取嵌入，默认会请求。
            // 部分 RAG 流程不一定需要返回向量，
            // 但若存在，仍解析它。
            if (source.containsKey(FIELD_VECTOR)) {
                Object vecObj = source.get(FIELD_VECTOR);
                if (vecObj instanceof List) {
                    List<Number> vecList = (List<Number>) vecObj;
                    double[] embedding = new double[vecList.size()];
                    for (int i = 0; i < vecList.size(); i++) {
                        embedding[i] = vecList.get(i).doubleValue();
                    }
                    doc.setEmbedding(embedding);
                }
            }
            return doc;
        } catch (Exception e) {
            log.error("Failed to map Elasticsearch hit to Document", e);
            return null;
        }
    }

    /**
     * 创建 ElasticsearchStore 构建器。
     * @return 新的 Builder 实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * ElasticsearchStore 的构建器。
     */
    public static class Builder {
        private String url = "http://localhost:9200";
        private String indexName;
        private int dimensions;
        private String username;
        private String password;
        private boolean disableSslVerification = false;

        private Builder() {}

        /**
         * 设置 Elasticsearch 连接 URL。
         * @param url URL
         * @return 当前构建器
         */
        public Builder url(String url) {
            this.url = url;
            return this;
        }

        /**
         * 设置索引名。
         * @param indexName 索引名
         * @return 当前构建器
         */
        public Builder indexName(String indexName) {
            this.indexName = indexName;
            return this;
        }

        /**
         * 设置向量维度。
         * @param dimensions 维数
         * @return 当前构建器
         */
        public Builder dimensions(int dimensions) {
            this.dimensions = dimensions;
            return this;
        }

        /**
         * 设置认证用户名。
         * @param username 用户名
         * @return 当前构建器
         */
        public Builder username(String username) {
            this.username = username;
            return this;
        }

        /**
         * 设置认证密码。
         * @param password 密码
         * @return 当前构建器
         */
        public Builder password(String password) {
            this.password = password;
            return this;
        }

        /**
         * 设置是否禁用 SSL 校验。
         * <p><strong>警告：</strong>禁用校验不安全，只应在开发环境使用。
         * @param disableSslVerification true 表示禁用校验
         * @return 当前构建器
         */
        public Builder disableSslVerification(boolean disableSslVerification) {
            this.disableSslVerification = disableSslVerification;
            return this;
        }

        /**
         * 构建 ElasticsearchStore。
         * @return 存储实例
         * @throws VectorStoreException 当配置无效时
         */
        public ElasticsearchStore build() throws VectorStoreException {
            if (url == null || url.trim().isEmpty()) {
                throw new IllegalArgumentException("URL cannot be null or empty");
            }
            if (indexName == null || indexName.trim().isEmpty()) {
                throw new IllegalArgumentException("Index name cannot be null or empty");
            }
            if (dimensions <= 0) {
                throw new IllegalArgumentException("Dimensions must be positive");
            }
            return new ElasticsearchStore(this);
        }
    }
}
