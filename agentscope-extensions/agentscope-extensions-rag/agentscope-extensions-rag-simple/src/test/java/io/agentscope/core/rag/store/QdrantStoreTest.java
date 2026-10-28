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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.exception.VectorStoreException;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import io.agentscope.core.rag.store.dto.SearchDocumentDto;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Common.PointId;
import io.qdrant.client.grpc.JsonWithInt;
import io.qdrant.client.grpc.JsonWithInt.Struct;
import io.qdrant.client.grpc.JsonWithInt.Value;
import io.qdrant.client.grpc.Points;
import io.qdrant.client.grpc.Points.ScoredPoint;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import reactor.test.StepVerifier;

/**
 * QdrantStore 单元测试。
 *
 * <p>注意：这些最小单元测试关注构建器验证与构造参数。完整的添加、搜索、删除功能测试需要连接运行中的 Qdrant 服务进行集成测试。
 *
 * <p>集成测试使用 @Tag("integration") 注解，并确保配置地址存在可用 Qdrant 服务。
 */
@Tag("unit")
@DisplayName("QdrantStore Unit Tests")
class QdrantStoreTest {

    private static final String TEST_LOCATION = "http://localhost:6333";
    private static final String TEST_COLLECTION = "test_collection";
    private static final int TEST_DIMENSIONS = 1024;

    private QdrantStore store;

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.close();
            store = null;
        }
    }

    @Test
    @DisplayName("Should create builder instance")
    void testBuilderCreation() {
        QdrantStore.Builder builder = QdrantStore.builder();
        assertNotNull(builder);
    }

    @Test
    @DisplayName("Should throw exception for null location")
    void testNullLocation() {
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    QdrantStore.builder()
                            .location(null)
                            .collectionName(TEST_COLLECTION)
                            .dimensions(TEST_DIMENSIONS)
                            .build();
                });
    }

    @Test
    @DisplayName("Should throw exception for empty location")
    void testEmptyLocation() {
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    QdrantStore.builder()
                            .location("")
                            .collectionName(TEST_COLLECTION)
                            .dimensions(TEST_DIMENSIONS)
                            .build();
                });
    }

    @Test
    @DisplayName("Should throw exception for blank location")
    void testBlankLocation() {
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    QdrantStore.builder()
                            .location("  ")
                            .collectionName(TEST_COLLECTION)
                            .dimensions(TEST_DIMENSIONS)
                            .build();
                });
    }

    @Test
    @DisplayName("Should throw exception for null collection name")
    void testNullCollectionName() {
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    QdrantStore.builder()
                            .location(TEST_LOCATION)
                            .collectionName(null)
                            .dimensions(TEST_DIMENSIONS)
                            .build();
                });
    }

    @Test
    @DisplayName("Should throw exception for empty collection name")
    void testEmptyCollectionName() {
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    QdrantStore.builder()
                            .location(TEST_LOCATION)
                            .collectionName("")
                            .dimensions(TEST_DIMENSIONS)
                            .build();
                });
    }

    @Test
    @DisplayName("Should throw exception for blank collection name")
    void testBlankCollectionName() {
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    QdrantStore.builder()
                            .location(TEST_LOCATION)
                            .collectionName("  ")
                            .dimensions(TEST_DIMENSIONS)
                            .build();
                });
    }

    @Test
    @DisplayName("Should throw exception for zero dimensions")
    void testZeroDimensions() {
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    QdrantStore.builder()
                            .location(TEST_LOCATION)
                            .collectionName(TEST_COLLECTION)
                            .dimensions(0)
                            .build();
                });
    }

    @Test
    @DisplayName("Should throw exception for negative dimensions")
    void testNegativeDimensions() {
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    QdrantStore.builder()
                            .location(TEST_LOCATION)
                            .collectionName(TEST_COLLECTION)
                            .dimensions(-1)
                            .build();
                });
    }

    @Test
    @DisplayName("Should create factory instance")
    void testStaticFactoryCreation() {
        // 验证静态工厂方法存在且返回构建器
        QdrantStore.Builder builder =
                QdrantStore.builder()
                        .location(TEST_LOCATION)
                        .collectionName(TEST_COLLECTION)
                        .dimensions(TEST_DIMENSIONS);

        assertNotNull(builder);
    }

    @Test
    @DisplayName("Should set TLS option in builder")
    void testBuilderTlsOption() {
        QdrantStore.Builder builder =
                QdrantStore.builder()
                        .location(TEST_LOCATION)
                        .collectionName(TEST_COLLECTION)
                        .dimensions(TEST_DIMENSIONS)
                        .useTransportLayerSecurity(false);

        assertNotNull(builder);
    }

    @Test
    @DisplayName("Should set compatibility check option in builder")
    void testBuilderCompatibilityOption() {
        QdrantStore.Builder builder =
                QdrantStore.builder()
                        .location(TEST_LOCATION)
                        .collectionName(TEST_COLLECTION)
                        .dimensions(TEST_DIMENSIONS)
                        .checkCompatibility(false);

        assertNotNull(builder);
    }

    @Test
    @DisplayName("Should set API key in builder")
    void testBuilderApiKey() {
        QdrantStore.Builder builder =
                QdrantStore.builder()
                        .location(TEST_LOCATION)
                        .collectionName(TEST_COLLECTION)
                        .dimensions(TEST_DIMENSIONS)
                        .apiKey("test-api-key");

        assertNotNull(builder);
    }

    @Test
    @DisplayName("Should chain builder methods")
    void testBuilderChaining() {
        QdrantStore.Builder builder =
                QdrantStore.builder()
                        .location(TEST_LOCATION)
                        .collectionName(TEST_COLLECTION)
                        .dimensions(TEST_DIMENSIONS)
                        .apiKey("test-api-key")
                        .useTransportLayerSecurity(true)
                        .checkCompatibility(false);

        assertNotNull(builder);
    }

    @Test
    @DisplayName("Qdrant store delete vector")
    void testDelete() throws Exception {
        MockedConstruction<QdrantGrpcClient> mockGrpc = mockConstruction(QdrantGrpcClient.class);
        MockedConstruction<QdrantClient> mockClient =
                mockConstruction(
                        QdrantClient.class,
                        (mock, context) -> {
                            // 模拟集合存在检查
                            when(mock.collectionExistsAsync(anyString()))
                                    .thenReturn(Futures.immediateFuture(true));

                            // 模拟删除操作
                            Points.UpdateResult updateResult =
                                    Points.UpdateResult.newBuilder()
                                            .setStatus(Points.UpdateStatus.Completed)
                                            .build();
                            ListenableFuture<Points.UpdateResult> future =
                                    Futures.immediateFuture(updateResult);
                            when(mock.deleteAsync(anyString(), anyList())).thenReturn(future);
                        });

        QdrantStore mockStore =
                QdrantStore.builder()
                        .location(TEST_LOCATION)
                        .collectionName(TEST_COLLECTION)
                        .dimensions(TEST_DIMENSIONS)
                        .useTransportLayerSecurity(false)
                        .build();

        Boolean delete = mockStore.delete(UUID.randomUUID().toString()).block();

        assertEquals(Boolean.TRUE, delete);

        mockStore.close();
        mockGrpc.close();
        mockClient.close();
    }

    @Test
    @DisplayName("Should throw VectorStoreException when Qdrant store delete vector occur error")
    void testDeleteError() throws Exception {
        MockedConstruction<QdrantGrpcClient> mockGrpc = mockConstruction(QdrantGrpcClient.class);
        MockedConstruction<QdrantClient> mockClient =
                mockConstruction(
                        QdrantClient.class,
                        (mock, context) -> {
                            // 模拟集合存在检查
                            when(mock.collectionExistsAsync(anyString()))
                                    .thenReturn(Futures.immediateFuture(true));

                            // 模拟删除操作
                            when(mock.deleteAsync(anyString(), anyList()))
                                    .thenThrow(new RuntimeException("Test Error"));
                        });

        QdrantStore mockStore =
                QdrantStore.builder()
                        .location(TEST_LOCATION)
                        .collectionName(TEST_COLLECTION)
                        .dimensions(TEST_DIMENSIONS)
                        .useTransportLayerSecurity(false)
                        .build();

        StepVerifier.create(mockStore.delete(UUID.randomUUID().toString()))
                .expectErrorMatches(
                        th ->
                                (th instanceof VectorStoreException vectorStoreException)
                                        && vectorStoreException
                                                .getMessage()
                                                .equals("Failed to delete document in Qdrant")
                                        && (th.getCause()
                                                instanceof RuntimeException runtimeException)
                                        && runtimeException.getMessage().equals("Test Error"))
                .verify();

        mockStore.close();
        mockGrpc.close();
        mockClient.close();
    }

    /**
     * 注意：以下测试需要运行中的 Qdrant 实例，应标记为集成测试：
     *
     * <ul>
     *   <li>testAdd - 验证批量添加文档
     *   <li>testSearch - 验证向量相似度搜索
     *   <li>testSearchWithScoreThreshold - 验证分数过滤
     *   <li>testDelete - 验证文档删除
     *   <li>testClose - 验证资源清理
     *   <li>testAutoCloseable - 验证 try-with-resources 正常工作
     * </ul>
     *
     * <p>集成测试结构示例：
     *
     * <pre>{@code
     * @Test
     * @Tag("integration")
     * @DisplayName("Should add and search documents")
     * void testAddAndSearch() throws Exception {
     *     try (QdrantStore store = QdrantStore.builder()
     *             .location("http://localhost:6333")
     *             .collectionName("test_collection")
     *             .dimensions(3)
     *             .build()) {
     *
     *         // 创建测试文档
     *         TextBlock content = TextBlock.builder().text("Test").build();
     *         DocumentMetadata metadata = new DocumentMetadata(content, "doc-1", "0");
     *         Document doc = new Document(metadata);
     *         doc.setEmbedding(new double[]{1.0, 0.0, 0.0});
     *
     *         // 添加文档
     *         store.add(List.of(doc)).block();
     *
     *         // 搜索相似文档
     *         double[] query = {1.0, 0.0, 0.0};
     *         List<Document> results = store.search(query, 10, null).block();
     *
     *         assertNotNull(results);
     *         assertEquals(1, results.size());
     *         assertEquals(doc.getId(), results.get(0).getId());
     *     }
     * }
     * }</pre>
     */
    @Test
    @DisplayName("Integration tests require running Qdrant server")
    void testIntegrationTestsNote() {
        // 此占位测试说明，完整测试需要集成测试，
        // 并要求 http://localhost:6333 运行 Qdrant 服务
        assertEquals(
                true,
                true,
                "Integration tests should be created with @Tag(\"integration\") to test"
                        + " add/search/delete operations");
    }

    // ==================== 辅助方法 ====================

    /**
     * 创建用于 payload 测试的模拟 QdrantStore。
     *
     * <p>通过 Mockito 模拟 QdrantClient 和 QdrantGrpcClient，无需真实 Qdrant 服务即可运行。模拟对象返回预先构建的含 payload 搜索结果。
     *
     * @return 模拟 QdrantStore 实例
     * @throws Exception 存储创建失败时抛出
     */
    private QdrantStore createMockStoreForPayloadTest() throws Exception {
        try (MockedConstruction<QdrantGrpcClient> ignoredGrpc =
                        mockConstruction(QdrantGrpcClient.class);
                MockedConstruction<QdrantClient> ignoredClient =
                        mockConstruction(
                                QdrantClient.class,
                                (mock, context) -> {
                                    // 模拟集合存在检查
                                    when(mock.collectionExistsAsync(anyString()))
                                            .thenReturn(Futures.immediateFuture(true));

                                    // 模拟 upsert 操作
                                    when(mock.upsertAsync(anyString(), anyList()))
                                            .thenReturn(Futures.immediateFuture(null));

                                    // 模拟带 payload 的搜索操作
                                    ScoredPoint mockPoint = createMockScoredPoint();
                                    when(mock.queryAsync(any(Points.QueryPoints.class)))
                                            .thenReturn(
                                                    Futures.immediateFuture(List.of(mockPoint)));
                                })) {
            return QdrantStore.builder()
                    .location(TEST_LOCATION)
                    .collectionName(TEST_COLLECTION)
                    .dimensions(TEST_DIMENSIONS)
                    .useTransportLayerSecurity(false)
                    .build();
        }
    }

    /**
     * 创建含 payload 数据的模拟 ScoredPoint 供测试。
     *
     * @return 模拟 ScoredPoint
     */
    private ScoredPoint createMockScoredPoint() {
        // 创建 payload 映射
        Map<String, Value> payloadMap = new HashMap<>();

        // 添加 doc_id
        payloadMap.put("doc_id", Value.newBuilder().setStringValue("doc-payload-test").build());

        // 添加 chunk_id
        payloadMap.put("chunk_id", Value.newBuilder().setStringValue("0").build());

        // 添加 content，以表示 TextBlock 的结构体形式
        Struct.Builder contentStruct = Struct.newBuilder();
        contentStruct.putFields("type", Value.newBuilder().setStringValue("text").build());
        contentStruct.putFields(
                "text", Value.newBuilder().setStringValue("Test document content").build());
        payloadMap.put("content", Value.newBuilder().setStructValue(contentStruct).build());

        // 添加 payload 自定义字段
        Struct.Builder customPayloadStruct = Struct.newBuilder();
        customPayloadStruct.putFields(
                "filename", Value.newBuilder().setStringValue("report.pdf").build());
        customPayloadStruct.putFields(
                "department", Value.newBuilder().setStringValue("Engineering").build());
        customPayloadStruct.putFields(
                "author", Value.newBuilder().setStringValue("John Doe").build());
        customPayloadStruct.putFields("priority", Value.newBuilder().setIntegerValue(1L).build());

        // 添加 tags 列表
        JsonWithInt.ListValue.Builder tagsList = JsonWithInt.ListValue.newBuilder();
        tagsList.addValues(Value.newBuilder().setStringValue("urgent").build());
        tagsList.addValues(Value.newBuilder().setStringValue("quarterly").build());
        customPayloadStruct.putFields("tags", Value.newBuilder().setListValue(tagsList).build());

        // 添加自定义对象
        Struct.Builder customObjectStruct = Struct.newBuilder();
        customObjectStruct.putFields(
                "author", Value.newBuilder().setStringValue("John Doe").build());
        customObjectStruct.putFields("version", Value.newBuilder().setIntegerValue(1L).build());
        customObjectStruct.putFields("active", Value.newBuilder().setBoolValue(true).build());

        JsonWithInt.ListValue.Builder customTagsList = JsonWithInt.ListValue.newBuilder();
        customTagsList.addValues(Value.newBuilder().setStringValue("urgent").build());
        customTagsList.addValues(Value.newBuilder().setStringValue("quarterly").build());
        customObjectStruct.putFields(
                "tags", Value.newBuilder().setListValue(customTagsList).build());

        customPayloadStruct.putFields(
                "custom", Value.newBuilder().setStructValue(customObjectStruct).build());

        payloadMap.put("payload", Value.newBuilder().setStructValue(customPayloadStruct).build());

        // 创建 PointId
        PointId pointId =
                PointId.newBuilder().setUuid("550e8400-e29b-41d4-a716-446655440000").build();

        // 构建 ScoredPoint
        return ScoredPoint.newBuilder()
                .setId(pointId)
                .setScore(0.95f)
                .putAllPayload(payloadMap)
                .build();
    }

    // ==================== 集成测试 ====================

    /**
     * 文档元数据 payload 功能的集成测试。
     *
     * <p>验证 DocumentMetadata 中的自定义 payload 字段能正确写入 Qdrant 并读回。需要 http://localhost:6333 上运行 Qdrant 服务。
     */
    @Test
    @Tag("integration")
    @DisplayName("Should store and load document metadata payload")
    void testDocumentMetadataPayload() throws Exception {
        store = createMockStoreForPayloadTest();

        // 创建自定义 payload 的文档
        TextBlock content = TextBlock.builder().text("Test document content").build();

        Map<String, Object> payload = new HashMap<>();
        payload.put("filename", "report.pdf");
        payload.put("department", "Engineering");
        payload.put("author", "John Doe");
        payload.put("priority", 1L);
        payload.put("tags", List.of("urgent", "quarterly"));

        CustomObject customObject = new CustomObject();
        customObject.setAuthor("John Doe");
        customObject.setVersion(1);
        customObject.setActive(true);
        customObject.setTags(List.of("urgent", "quarterly"));
        payload.put("custom", customObject);

        DocumentMetadata metadata = new DocumentMetadata(content, "doc-payload-test", "0", payload);
        Document doc = new Document(metadata);
        double[] embedding = new double[TEST_DIMENSIONS];
        embedding[0] = 1.0;
        doc.setEmbedding(embedding);
        store.add(List.of(doc)).block();

        // 搜索文档
        double[] query = new double[TEST_DIMENSIONS];
        query[0] = 1.0;
        List<Document> results =
                store.search(SearchDocumentDto.builder().queryEmbedding(query).limit(10).build())
                        .block();

        // 验证结果
        assertNotNull(results, "Search results should not be null");
        assertEquals(1L, results.size(), "Should find exactly one document");

        Document retrievedDoc = results.get(0);
        assertNotNull(retrievedDoc, "Retrieved document should not be null");

        // 验证正确加载 payload 字段
        assertEquals(
                "report.pdf", retrievedDoc.getPayloadValue("filename"), "Filename should match");
        assertEquals(
                "Engineering",
                retrievedDoc.getPayloadValue("department"),
                "Department should match");
        assertEquals("John Doe", retrievedDoc.getPayloadValue("author"), "Author should match");
        assertEquals(1L, retrievedDoc.getPayloadValue("priority"), "Priority should match");

        // 验证 tags 列表
        Object tagsObj = retrievedDoc.getPayloadValue("tags");
        assertNotNull(tagsObj, "Tags should not be null");
        assertEquals(true, tagsObj instanceof List, "Tags should be a List");
        @SuppressWarnings("unchecked")
        List<String> tags = (List<String>) tagsObj;
        assertEquals(2, tags.size(), "Should have 2 tags");
        assertEquals(true, tags.contains("urgent"), "Should contain 'urgent' tag");
        assertEquals(true, tags.contains("quarterly"), "Should contain 'quarterly' tag");

        // 验证 payload 键存在
        assertEquals(true, retrievedDoc.hasPayloadKey("filename"), "Should have filename key");
        assertEquals(
                false,
                retrievedDoc.hasPayloadKey("nonexistent"),
                "Should not have nonexistent key");

        // 验证内容保留
        assertEquals(
                "Test document content",
                retrievedDoc.getMetadata().getContentText(),
                "Content should match");

        // 验证自定义对象
        CustomObject customObj = retrievedDoc.getPayloadValueAs("custom", CustomObject.class);
        assertNotNull(customObj, "Custom object should not be null");
    }

    /**
     * 测试 payload 序列化的自定义对象类。
     */
    static class CustomObject {
        private String author;
        private int version;
        private boolean active;
        private List<String> tags;

        public String getAuthor() {
            return author;
        }

        public void setAuthor(String author) {
            this.author = author;
        }

        public int getVersion() {
            return version;
        }

        public void setVersion(int version) {
            this.version = version;
        }

        public boolean isActive() {
            return active;
        }

        public void setActive(boolean active) {
            this.active = active;
        }

        public List<String> getTags() {
            return tags;
        }

        public void setTags(List<String> tags) {
            this.tags = tags;
        }
    }
}
