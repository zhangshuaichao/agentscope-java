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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Result;
import co.elastic.clients.elasticsearch._types.mapping.DenseVectorSimilarity;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.DeleteResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.HitsMetadata;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.CreateIndexResponse;
import co.elastic.clients.elasticsearch.indices.ElasticsearchIndicesClient;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import co.elastic.clients.transport.endpoints.BooleanResponse;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.exception.VectorStoreException;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import io.agentscope.core.rag.store.dto.SearchDocumentDto;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import reactor.test.StepVerifier;

@Tag("unit")
@DisplayName("ElasticsearchStore Unit Tests")
public class ElasticsearchStoreTest {
    private static final String TEST_URL = "http://localhost:9200";
    private static final String TEST_INDEX = "test_index";
    private static final int TEST_DIMENSIONS = 1536;

    private ElasticsearchStore store;

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.close();
            store = null;
        }
    }

    // ==================== 构建器验证测试 ====================

    @Test
    @DisplayName("Should create builder instance")
    void testBuilderCreation() {
        ElasticsearchStore.Builder builder = ElasticsearchStore.builder();
        assertNotNull(builder);
    }

    @Test
    @DisplayName("Should throw exception for null URL")
    void testNullUrl() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ElasticsearchStore.builder()
                                .url(null)
                                .indexName(TEST_INDEX)
                                .dimensions(TEST_DIMENSIONS)
                                .build());
    }

    @Test
    @DisplayName("Should throw exception for empty URL")
    void testEmptyUrl() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ElasticsearchStore.builder()
                                .url("")
                                .indexName(TEST_INDEX)
                                .dimensions(TEST_DIMENSIONS)
                                .build());
    }

    @Test
    @DisplayName("Should throw exception for null index name")
    void testNullIndexName() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ElasticsearchStore.builder()
                                .url(TEST_URL)
                                .indexName(null)
                                .dimensions(TEST_DIMENSIONS)
                                .build());
    }

    @Test
    @DisplayName("Should throw exception for zero dimensions")
    void testZeroDimensions() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ElasticsearchStore.builder()
                                .url(TEST_URL)
                                .indexName(TEST_INDEX)
                                .dimensions(0)
                                .build());
    }

    // ==================== 基于模拟的功能测试 ====================

    /**
     * 使用模拟 ElasticsearchClient 创建存储的辅助方法。
     * 模拟包括 ensureIndex() 在内的构造器初始化。
     */
    private ElasticsearchStore createMockStore() throws VectorStoreException {
        // 模拟 ElasticsearchClient 的构造
        try (MockedConstruction<ElasticsearchClient> ignored =
                mockConstruction(
                        ElasticsearchClient.class,
                        (mock, context) -> {
                            // 为 ensureIndex() 模拟索引客户端
                            ElasticsearchIndicesClient indicesClient =
                                    mock(ElasticsearchIndicesClient.class);
                            when(mock.indices()).thenReturn(indicesClient);

                            // 模拟索引存在检查，返回 true 以跳过创建逻辑
                            BooleanResponse boolResp = mock(BooleanResponse.class);
                            when(boolResp.value()).thenReturn(true);
                            when(indicesClient.exists(any(ExistsRequest.class)))
                                    .thenReturn(boolResp);
                        })) {
            return ElasticsearchStore.builder()
                    .url(TEST_URL)
                    .indexName(TEST_INDEX)
                    .dimensions(TEST_DIMENSIONS)
                    .build();
        }
    }

    @Test
    @DisplayName("Should build store with mock client")
    void testBuildWithMockClient() throws VectorStoreException {
        store = createMockStore();
        assertNotNull(store);
    }

    @Test
    @DisplayName("Should close store successfully")
    void testCloseStore() throws VectorStoreException {
        store = createMockStore();
        store.close();
        // ElasticsearchStore 不暴露 isClosed()，但可验证不抛异常
    }

    @Test
    @DisplayName("Should configure SSL context when URL starts with https")
    void testBuildWithHttpsUrl() throws VectorStoreException {
        // 模拟构造过程，避免真实网络连接
        try (MockedConstruction<ElasticsearchClient> ignored =
                mockConstruction(
                        ElasticsearchClient.class,
                        (mock, context) -> {
                            // 模拟索引检查，避免构造器抛异常
                            ElasticsearchIndicesClient indicesClient =
                                    mock(ElasticsearchIndicesClient.class);
                            when(mock.indices()).thenReturn(indicesClient);
                            BooleanResponse boolResp = mock(BooleanResponse.class);
                            when(boolResp.value()).thenReturn(true);
                            when(indicesClient.exists(any(ExistsRequest.class)))
                                    .thenReturn(boolResp);
                        })) {

            // 1. 设置 HTTPS URL 和认证凭证
            // 这覆盖 ElasticsearchStore 构造器中 SSL 处理与
            // setDefaultCredentialsProvider 的代码路径
            ElasticsearchStore httpsStore =
                    ElasticsearchStore.builder()
                            .url("https://localhost:9200")
                            .indexName(TEST_INDEX)
                            .dimensions(TEST_DIMENSIONS)
                            .username("admin")
                            .password("secret")
                            .disableSslVerification(true)
                            .build();

            assertNotNull(httpsStore);
            httpsStore.close();
        }
    }

    @Test
    @DisplayName("Should create index with correct mappings when index does not exist")
    void testEnsureIndexCreatesIndex() throws VectorStoreException, IOException {
        // 使用 MockedConstruction 拦截 ElasticsearchClient 创建
        try (MockedConstruction<ElasticsearchClient> ignored =
                mockConstruction(
                        ElasticsearchClient.class,
                        (mock, context) -> {
                            ElasticsearchIndicesClient indicesClient =
                                    mock(ElasticsearchIndicesClient.class);
                            when(mock.indices()).thenReturn(indicesClient);

                            // 1. 模拟 exists() 返回 false，强制进入索引
                            // 创建分支
                            BooleanResponse existsResp = mock(BooleanResponse.class);
                            when(existsResp.value()).thenReturn(false);
                            when(indicesClient.exists(any(ExistsRequest.class)))
                                    .thenReturn(existsResp);

                            // 2. 模拟 create() 调用，避免 NullPointerException
                            CreateIndexResponse createResp = mock(CreateIndexResponse.class);
                            when(indicesClient.create(any(CreateIndexRequest.class)))
                                    .thenReturn(createResp);
                        })) {

            // 初始化存储，触发构造器的 ensureIndex()
            ElasticsearchStore newStore =
                    ElasticsearchStore.builder()
                            .url(TEST_URL)
                            .indexName(TEST_INDEX)
                            .dimensions(TEST_DIMENSIONS)
                            .build();

            // 取得模拟对象以便验证
            ElasticsearchClient mockClient = ignored.constructed().get(0);
            ElasticsearchIndicesClient indicesClient = mockClient.indices();

            // 3. 捕获 CreateIndexRequest 参数，验证 Mapping 设置
            ArgumentCaptor<CreateIndexRequest> captor =
                    ArgumentCaptor.forClass(CreateIndexRequest.class);
            verify(indicesClient).create(captor.capture());

            CreateIndexRequest request = captor.getValue();

            // 验证索引名称
            assertEquals(TEST_INDEX, request.index());

            // 验证关键属性，覆盖 154—160 行
            Map<String, Property> props = request.mappings().properties();

            // 验证向量字段
            assertTrue(props.containsKey("vector"), "Should contain vector field");
            Property vectorProp = props.get("vector");
            assertTrue(vectorProp.isDenseVector());
            assertEquals(TEST_DIMENSIONS, vectorProp.denseVector().dims());
            assertEquals(DenseVectorSimilarity.Cosine, vectorProp.denseVector().similarity());
            assertTrue(vectorProp.denseVector().index());

            // 验证内容字段
            assertTrue(props.containsKey("content"), "Should contain content field");
            assertTrue(props.get("content").isText());
            assertEquals(Boolean.FALSE, props.get("content").text().index());

            // 验证 ID 字段
            assertTrue(props.containsKey("id"), "Should contain id field");
            assertTrue(props.get("id").isKeyword());

            newStore.close();
        }
    }

    // ==================== 添加方法测试 ====================

    private ElasticsearchStore createMockStoreForAdd(boolean success) throws VectorStoreException {
        try (MockedConstruction<ElasticsearchClient> ignored =
                mockConstruction(
                        ElasticsearchClient.class,
                        (mock, context) -> {
                            // 处理 ensureIndex
                            ElasticsearchIndicesClient indicesClient =
                                    mock(ElasticsearchIndicesClient.class);
                            when(mock.indices()).thenReturn(indicesClient);
                            BooleanResponse boolResp = mock(BooleanResponse.class);
                            when(boolResp.value()).thenReturn(true);
                            when(indicesClient.exists(any(ExistsRequest.class)))
                                    .thenReturn(boolResp);

                            // 处理 Bulk
                            BulkResponse bulkResp = mock(BulkResponse.class);
                            when(bulkResp.errors()).thenReturn(!success);
                            // 失败时存储遍历各项；此简单测试仅
                            // 验证出错时抛异常
                            when(bulkResp.items()).thenReturn(Collections.emptyList());
                            when(mock.bulk(any(BulkRequest.class))).thenReturn(bulkResp);
                        })) {
            return ElasticsearchStore.builder()
                    .url(TEST_URL)
                    .indexName(TEST_INDEX)
                    .dimensions(TEST_DIMENSIONS)
                    .build();
        }
    }

    @Test
    @DisplayName("Should complete for empty documents list")
    void testAddEmptyDocuments() throws VectorStoreException {
        store = createMockStoreForAdd(true);
        StepVerifier.create(store.add(List.of())).verifyComplete();
    }

    @Test
    @DisplayName("Should add documents successfully")
    void testAddSuccess() throws VectorStoreException {
        store = createMockStoreForAdd(true);

        TextBlock content = TextBlock.builder().text("Test content").build();
        DocumentMetadata metadata = new DocumentMetadata(content, "doc-1", "chunk-1");
        Document doc = new Document(metadata);
        doc.setEmbedding(new double[TEST_DIMENSIONS]);

        StepVerifier.create(store.add(List.of(doc))).verifyComplete();
    }

    @Test
    @DisplayName("Should throw exception on bulk error")
    void testAddFailure() throws VectorStoreException {
        store = createMockStoreForAdd(false); // 模拟失败

        TextBlock content = TextBlock.builder().text("Test content").build();
        DocumentMetadata metadata = new DocumentMetadata(content, "doc-1", "chunk-1");
        Document doc = new Document(metadata);
        doc.setEmbedding(new double[TEST_DIMENSIONS]);

        StepVerifier.create(store.add(List.of(doc)))
                .expectError(VectorStoreException.class)
                .verify();
    }

    @Test
    @DisplayName("Should validate document dimensions")
    void testAddDimensionMismatch() throws VectorStoreException {
        store = createMockStoreForAdd(true);

        TextBlock content = TextBlock.builder().text("Test").build();
        DocumentMetadata metadata = new DocumentMetadata(content, "doc-1", "0");
        Document doc = new Document(metadata);
        doc.setEmbedding(new double[1]); // 维度错误

        StepVerifier.create(store.add(List.of(doc)))
                .expectError(VectorStoreException.class)
                .verify();
    }

    @Test
    @DisplayName("Should add documents with payload")
    void testAddPayload() throws VectorStoreException {
        store = createMockStoreForAdd(true);

        TextBlock content = TextBlock.builder().text("Test content").build();
        DocumentMetadata metadata =
                new DocumentMetadata(content, "doc-1", "chunk-1", Map.of("k", "v"));
        Document doc = new Document(metadata);
        doc.setEmbedding(new double[TEST_DIMENSIONS]);

        StepVerifier.create(store.add(List.of(doc))).verifyComplete();
    }

    // ==================== 搜索方法测试 ====================

    @SuppressWarnings("unchecked")
    private ElasticsearchStore createMockStoreForSearch(List<Map<String, Object>> mockHits)
            throws VectorStoreException {
        try (MockedConstruction<ElasticsearchClient> ignored =
                mockConstruction(
                        ElasticsearchClient.class,
                        (mock, context) -> {
                            // 处理 ensureIndex
                            ElasticsearchIndicesClient indicesClient =
                                    mock(ElasticsearchIndicesClient.class);
                            when(mock.indices()).thenReturn(indicesClient);
                            BooleanResponse boolResp = mock(BooleanResponse.class);
                            when(boolResp.value()).thenReturn(true);
                            when(indicesClient.exists(any(ExistsRequest.class)))
                                    .thenReturn(boolResp);

                            // 处理 Search
                            SearchResponse<Map> searchResp = mock(SearchResponse.class);
                            HitsMetadata<Map> hitsMetadata = mock(HitsMetadata.class);
                            List<Hit<Map>> hitList = new ArrayList<>();

                            for (Map<String, Object> source : mockHits) {
                                Hit<Map> hit = mock(Hit.class);
                                when(hit.source()).thenReturn(source);
                                when(hit.score()).thenReturn(0.95);
                                hitList.add(hit);
                            }

                            when(hitsMetadata.hits()).thenReturn(hitList);
                            when(searchResp.hits()).thenReturn(hitsMetadata);

                            // 模拟泛型 search 调用
                            when(mock.search(any(SearchRequest.class), eq(Map.class)))
                                    .thenReturn(searchResp);
                        })) {
            return ElasticsearchStore.builder()
                    .url(TEST_URL)
                    .indexName(TEST_INDEX)
                    .dimensions(TEST_DIMENSIONS)
                    .build();
        }
    }

    @Test
    @DisplayName("Should search successfully")
    void testSearchSuccess() throws VectorStoreException {
        // 准备模拟结果
        Map<String, Object> source = new HashMap<>();
        source.put("doc_id", "doc-1");
        source.put("chunk_id", "0");
        source.put("content", "{\"type\":\"text\",\"text\":\"content\"}");
        // 模拟返回向量
        List<Double> vector = new ArrayList<>();
        for (int i = 0; i < TEST_DIMENSIONS; i++) vector.add(0.0);
        source.put("vector", vector);
        source.put("payload", "{\"k\":\"v\"}");

        store = createMockStoreForSearch(List.of(source));

        double[] query = new double[TEST_DIMENSIONS];
        StepVerifier.create(
                        store.search(
                                SearchDocumentDto.builder()
                                        .queryEmbedding(query)
                                        .limit(5)
                                        .scoreThreshold(0.5)
                                        .build()))
                .assertNext(
                        results -> {
                            assertNotNull(results);
                            assertEquals(1, results.size());
                            assertEquals("doc-1", results.get(0).getMetadata().getDocId());
                            assertEquals(0.95, results.get(0).getScore());
                            assertEquals("v", results.get(0).getMetadata().getPayloadValue("k"));
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should throw exception for dimension mismatch in query")
    void testSearchDimensionMismatch() throws VectorStoreException {
        store = createMockStoreForSearch(Collections.emptyList());
        double[] query = new double[1]; // 维度错误

        StepVerifier.create(
                        store.search(
                                SearchDocumentDto.builder()
                                        .queryEmbedding(query)
                                        .limit(5)
                                        .scoreThreshold(null)
                                        .build()))
                .expectError(VectorStoreException.class)
                .verify();
    }

    // ==================== 删除方法测试 ====================

    private ElasticsearchStore createMockStoreForDelete(boolean deleted)
            throws VectorStoreException {
        try (MockedConstruction<ElasticsearchClient> ignored =
                mockConstruction(
                        ElasticsearchClient.class,
                        (mock, context) -> {
                            // 处理 ensureIndex
                            ElasticsearchIndicesClient indicesClient =
                                    mock(ElasticsearchIndicesClient.class);
                            when(mock.indices()).thenReturn(indicesClient);
                            BooleanResponse boolResp = mock(BooleanResponse.class);
                            when(boolResp.value()).thenReturn(true);
                            when(indicesClient.exists(any(ExistsRequest.class)))
                                    .thenReturn(boolResp);

                            // 处理 Delete
                            DeleteResponse deleteResp = mock(DeleteResponse.class);
                            Result result = deleted ? Result.Deleted : Result.NotFound;
                            when(deleteResp.result()).thenReturn(result);

                            // when(mock.delete(any(DeleteRequest.class))).thenReturn(deleteResp);
                            when(mock.delete(any(Function.class))).thenReturn(deleteResp);
                        })) {
            return ElasticsearchStore.builder()
                    .url(TEST_URL)
                    .indexName(TEST_INDEX)
                    .dimensions(TEST_DIMENSIONS)
                    .build();
        }
    }

    @Test
    @DisplayName("Should delete document successfully")
    void testDeleteSuccess() throws VectorStoreException {
        store = createMockStoreForDelete(true);
        StepVerifier.create(store.delete("doc-1"))
                .assertNext(Assertions::assertTrue)
                .verifyComplete();
    }

    @Test
    @DisplayName("Should return false when document not found")
    void testDeleteNotFound() throws VectorStoreException {
        store = createMockStoreForDelete(false);
        StepVerifier.create(store.delete("doc-1"))
                .assertNext(Assertions::assertFalse)
                .verifyComplete();
    }

    @Test
    @DisplayName("Should throw exception for null ID")
    void testDeleteNullId() throws VectorStoreException {
        store = createMockStoreForDelete(true);
        StepVerifier.create(store.delete(null))
                .expectError(IllegalArgumentException.class)
                .verify();
    }
}
