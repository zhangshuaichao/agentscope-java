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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.exception.VectorStoreException;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import io.agentscope.core.rag.store.dto.SearchDocumentDto;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

/**
 * InMemoryStore 单元测试。
 */
@Tag("unit")
@DisplayName("InMemoryStore Unit Tests")
class InMemoryStoreTest {

    private InMemoryStore store;
    private static final int DIMENSIONS = 3;

    @BeforeEach
    void setUp() {
        store = InMemoryStore.builder().dimensions(DIMENSIONS).build();
    }

    @Test
    @DisplayName("Should create store with specified dimensions")
    void testCreateStore() {
        InMemoryStore newStore = InMemoryStore.builder().dimensions(1024).build();
        assertEquals(1024, newStore.getDimensions());
        assertTrue(newStore.isEmpty());
    }

    @Test
    @DisplayName("Should create store with default dimensions")
    void testCreateStoreDefault() {
        InMemoryStore defaultStore = InMemoryStore.builder().build();
        assertEquals(1024, defaultStore.getDimensions());
    }

    @Test
    @DisplayName("Should throw exception for invalid dimensions")
    void testCreateStoreInvalidDimensions() {
        assertThrows(
                IllegalArgumentException.class,
                () -> InMemoryStore.builder().dimensions(0).build());
        assertThrows(
                IllegalArgumentException.class,
                () -> InMemoryStore.builder().dimensions(-1).build());
    }

    @Test
    @DisplayName("Should add documents to store")
    void testAdd() {
        Document doc = createDocument("doc-1", "Test content", new double[] {1.0, 2.0, 3.0});

        StepVerifier.create(store.add(List.of(doc))).verifyComplete();

        assertEquals(1, store.size());
    }

    @Test
    @DisplayName("Should add documents to store with vector name")
    void testAddWithVectorName() {
        Document doc = createDocument("doc-1", "Test content", new double[] {1.0, 2.0, 3.0});
        doc.setVectorName("test-vector");

        StepVerifier.create(store.add(List.of(doc))).verifyComplete();

        assertEquals(1, store.size());
    }

    @Test
    @DisplayName("Should throw error when adding null document list")
    void testAddNullList() {
        StepVerifier.create(store.add(null)).expectError(IllegalArgumentException.class).verify();
    }

    @Test
    @DisplayName("Should throw error when adding null document")
    void testAddNullDocument() {
        // 创建包含 null 的列表以触发验证
        List<Document> listWithNull = new ArrayList<>();
        listWithNull.add(null);

        StepVerifier.create(store.add(listWithNull))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    @DisplayName("Should throw error when adding document without embedding")
    void testAddNoEmbedding() {
        Document doc = createDocument("doc-1", "Test", null);

        StepVerifier.create(store.add(List.of(doc)))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    @DisplayName("Should throw error when embedding dimension mismatch")
    void testAddDimensionMismatch() {
        Document doc = createDocument("doc-1", "Test", new double[] {1.0, 2.0}); // 维度错误

        StepVerifier.create(store.add(List.of(doc)))
                .expectError(VectorStoreException.class)
                .verify();
    }

    @Test
    @DisplayName("Should replace existing document with same UUID")
    void testAddReplace() {
        // docId 和内容相同的文档具有相同 UUID
        Document doc1 = createDocument("doc-1", "Same content", new double[] {1.0, 2.0, 3.0});
        Document doc2 = createDocument("doc-1", "Same content", new double[] {4.0, 5.0, 6.0});

        store.add(List.of(doc1)).block();
        store.add(List.of(doc2)).block();

        // 应具有相同 UUID，因此替换原项
        assertEquals(doc1.getId(), doc2.getId());
        assertEquals(1, store.size());
    }

    @Test
    @DisplayName("Should add multiple documents in batch")
    void testAddBatch() {
        Document doc1 = createDocument("doc-1", "Content 1", new double[] {1.0, 2.0, 3.0});
        Document doc2 = createDocument("doc-2", "Content 2", new double[] {4.0, 5.0, 6.0});

        StepVerifier.create(store.add(List.of(doc1, doc2))).verifyComplete();

        assertEquals(2, store.size());
    }

    @Test
    @DisplayName("Should handle empty document list")
    void testAddEmptyList() {
        StepVerifier.create(store.add(List.of())).verifyComplete();

        assertEquals(0, store.size());
    }

    @Test
    @DisplayName("Should search for similar vectors")
    void testSearch() {
        // 添加具有不同嵌入的文档
        Document doc1 = createDocument("doc-1", "Content 1", new double[] {1.0, 0.0, 0.0});
        Document doc2 = createDocument("doc-2", "Content 2", new double[] {0.0, 1.0, 0.0});
        Document doc3 = createDocument("doc-3", "Content 3", new double[] {0.0, 0.0, 1.0});

        store.add(List.of(doc1, doc2, doc3)).block();

        // 搜索与 doc1 嵌入相似的向量
        double[] query = {1.0, 0.0, 0.0};

        StepVerifier.create(
                        store.search(
                                SearchDocumentDto.builder().queryEmbedding(query).limit(2).build()))
                .assertNext(
                        results -> {
                            assertEquals(2, results.size());
                            // 首个结果应为 doc-1，完全一致，相似度 = 1.0
                            assertEquals(doc1.getId(), results.get(0).getId());
                            assertEquals(1.0, results.get(0).getScore(), 1e-9);
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should return empty list when store is empty")
    void testSearchEmptyStore() {
        double[] query = {1.0, 2.0, 3.0};

        StepVerifier.create(
                        store.search(
                                SearchDocumentDto.builder().queryEmbedding(query).limit(5).build()))
                .assertNext(results -> assertTrue(results.isEmpty()))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should return top K results")
    void testSearchTopK() {
        // 添加 5 份文档
        for (int i = 0; i < 5; i++) {
            Document doc =
                    createDocument("doc-" + i, "Content " + i, new double[] {(double) i, 0.0, 0.0});
            store.add(List.of(doc)).block();
        }

        double[] query = {0.0, 0.0, 0.0};

        StepVerifier.create(
                        store.search(
                                SearchDocumentDto.builder().queryEmbedding(query).limit(3).build()))
                .assertNext(results -> assertEquals(3, results.size()))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should return results sorted by similarity")
    void testSearchSorted() {
        Document doc1 =
                createDocument("doc-1", "Content 1", new double[] {1.0, 0.0, 0.0}); // 最相似
        Document doc2 =
                createDocument("doc-2", "Content 2", new double[] {0.5, 0.5, 0.0}); // 较不相似
        Document doc3 =
                createDocument("doc-3", "Content 3", new double[] {0.0, 1.0, 0.0}); // 最不相似

        store.add(List.of(doc1, doc2, doc3)).block();

        double[] query = {1.0, 0.0, 0.0};

        StepVerifier.create(
                        store.search(
                                SearchDocumentDto.builder().queryEmbedding(query).limit(3).build()))
                .assertNext(
                        results -> {
                            assertEquals(3, results.size());
                            // 结果应按相似度降序排序
                            assertTrue(results.get(0).getScore() >= results.get(1).getScore());
                            assertTrue(results.get(1).getScore() >= results.get(2).getScore());
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should filter by score threshold")
    void testSearchWithScoreThreshold() {
        Document doc1 = createDocument("doc-1", "Content 1", new double[] {1.0, 0.0, 0.0});
        Document doc2 = createDocument("doc-2", "Content 2", new double[] {0.5, 0.5, 0.0});
        Document doc3 = createDocument("doc-3", "Content 3", new double[] {0.0, 1.0, 0.0});

        store.add(List.of(doc1, doc2, doc3)).block();

        double[] query = {1.0, 0.0, 0.0};

        // 设置高阈值，过滤较不相似文档
        StepVerifier.create(
                        store.search(
                                SearchDocumentDto.builder()
                                        .queryEmbedding(query)
                                        .limit(10)
                                        .scoreThreshold(0.9)
                                        .build()))
                .assertNext(
                        results -> {
                            // 仅 doc1 应达到阈值，相似度 = 1.0
                            assertEquals(1, results.size());
                            assertEquals(doc1.getId(), results.get(0).getId());
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should throw error when searching with null query")
    void testSearchNullQuery() {
        StepVerifier.create(
                        store.search(
                                SearchDocumentDto.builder().queryEmbedding(null).limit(5).build()))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    @DisplayName("Should throw error when searching with invalid limit")
    void testSearchInvalidLimit() {
        double[] query = {1.0, 2.0, 3.0};

        StepVerifier.create(
                        store.search(
                                SearchDocumentDto.builder().queryEmbedding(query).limit(0).build()))
                .expectError(IllegalArgumentException.class)
                .verify();

        StepVerifier.create(
                        store.search(
                                SearchDocumentDto.builder()
                                        .queryEmbedding(query)
                                        .limit(-1)
                                        .build()))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    @DisplayName("Should search for similar vectors filtered by vector name")
    void testSearchWithVectorName() {
        // 添加具有不同嵌入的文档
        Document doc1 = createDocument("doc-1", "Content", new double[] {1.0, 0.0, 0.0});
        doc1.setVectorName("test-vector");
        Document doc2 = createDocument("doc-2", "Content", new double[] {1.0, 0.0, 0.0});

        store.add(List.of(doc1, doc2)).block();

        double[] query = {1.0, 0.0, 0.0};

        StepVerifier.create(
                        store.search(
                                SearchDocumentDto.builder()
                                        .queryEmbedding(query)
                                        .vectorName("test-vector")
                                        .limit(3)
                                        .build()))
                .assertNext(
                        results -> {
                            assertEquals(1, results.size());
                            // 首个结果应为 doc-1，完全一致，相似度 = 1.0
                            assertEquals(doc1.getId(), results.get(0).getId());
                            assertEquals(1.0, results.get(0).getScore(), 1e-9);
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should delete document from store")
    void testDelete() {
        Document doc = createDocument("doc-1", "Test content", new double[] {1.0, 2.0, 3.0});

        store.add(List.of(doc)).block();
        assertEquals(1, store.size());

        StepVerifier.create(store.delete(doc.getId()))
                .assertNext(deleted -> assertTrue(deleted))
                .verifyComplete();

        assertEquals(0, store.size());
    }

    @Test
    @DisplayName("Should return false when deleting non-existent document")
    void testDeleteNonExistent() {
        StepVerifier.create(store.delete("non-existent-uuid"))
                .assertNext(deleted -> assertFalse(deleted))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should throw error when deleting with null ID")
    void testDeleteNullId() {
        StepVerifier.create(store.delete(null))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    @DisplayName("Should clear all documents")
    void testClear() {
        Document doc1 = createDocument("doc-1", "Content 1", new double[] {1.0, 2.0, 3.0});
        Document doc2 = createDocument("doc-2", "Content 2", new double[] {4.0, 5.0, 6.0});

        store.add(List.of(doc1, doc2)).block();

        assertEquals(2, store.size());

        store.clear();

        assertEquals(0, store.size());
        assertTrue(store.isEmpty());
    }

    @Test
    @DisplayName("Should maintain thread safety")
    void testThreadSafety() throws InterruptedException {
        int numThreads = 10;
        int docsPerThread = 10;
        Thread[] threads = new Thread[numThreads];

        // 并发添加
        for (int i = 0; i < numThreads; i++) {
            final int threadId = i;
            threads[i] =
                    new Thread(
                            () -> {
                                for (int j = 0; j < docsPerThread; j++) {
                                    String docId = "doc-" + threadId + "-" + j;
                                    Document doc =
                                            createDocument(
                                                    docId,
                                                    "Content " + threadId + "-" + j,
                                                    new double[] {
                                                        (double) threadId, (double) j, 0.0
                                                    });
                                    store.add(List.of(doc)).block();
                                }
                            });
            threads[i].start();
        }

        // 等待全部线程
        for (Thread thread : threads) {
            thread.join();
        }

        assertEquals(numThreads * docsPerThread, store.size());
    }

    @Test
    @DisplayName("Should not modify original embedding array")
    void testImmutableEmbedding() {
        double[] original = {1.0, 2.0, 3.0};
        Document doc = createDocument("doc-1", "Test", original);

        store.add(List.of(doc)).block();

        // 修改原数组
        original[0] = 999.0;

        // 因已做防御性复制，搜索仍应使用原值
        double[] query = {1.0, 2.0, 3.0};
        List<Document> results =
                store.search(SearchDocumentDto.builder().queryEmbedding(query).limit(1).build())
                        .block();

        assertNotNull(results);
        assertEquals(1, results.size());
        // 存储的向量不应受修改影响
        // 通过检查搜索结果仍与原查询相似来验证
        assertTrue(results.get(0).getScore() > 0.9);
    }

    @Test
    @DisplayName("Should store and retrieve document with custom payload")
    void testDocumentWithPayload() {
        // 创建自定义 payload 的文档
        TextBlock content = TextBlock.builder().text("Test document content").build();

        Map<String, Object> payload = new HashMap<>();
        payload.put("filename", "report.pdf");
        payload.put("department", "Engineering");
        payload.put("author", "John Doe");
        payload.put("priority", 1);
        payload.put("tags", List.of("urgent", "quarterly"));

        // 创建自定义对象
        CustomObject customObject = new CustomObject();
        customObject.setAuthor("Alice");
        customObject.setVersion(2);
        customObject.setActive(true);
        customObject.setTags(List.of("important", "reviewed"));
        payload.put("custom", customObject);

        DocumentMetadata metadata = new DocumentMetadata(content, "doc-payload-test", "0", payload);
        Document doc = new Document(metadata);
        doc.setEmbedding(new double[] {1.0, 0.0, 0.0});

        // 添加文档
        StepVerifier.create(store.add(List.of(doc))).verifyComplete();

        // 搜索文档
        double[] query = new double[] {1.0, 0.0, 0.0};
        StepVerifier.create(
                        store.search(
                                SearchDocumentDto.builder()
                                        .queryEmbedding(query)
                                        .limit(10)
                                        .build()))
                .assertNext(
                        results -> {
                            assertNotNull(results, "Search results should not be null");
                            assertEquals(1, results.size(), "Should find exactly one document");

                            Document retrievedDoc = results.get(0);
                            assertNotNull(retrievedDoc, "Retrieved document should not be null");

                            // 验证正确加载 payload 字段
                            assertEquals(
                                    "report.pdf",
                                    retrievedDoc.getPayloadValue("filename"),
                                    "Filename should match");
                            assertEquals(
                                    "Engineering",
                                    retrievedDoc.getPayloadValue("department"),
                                    "Department should match");
                            assertEquals(
                                    "John Doe",
                                    retrievedDoc.getPayloadValue("author"),
                                    "Author should match");
                            assertEquals(
                                    1,
                                    retrievedDoc.getPayloadValue("priority"),
                                    "Priority should match");

                            // 验证 tags 列表
                            Object tagsObj = retrievedDoc.getPayloadValue("tags");
                            assertNotNull(tagsObj, "Tags should not be null");
                            assertTrue(tagsObj instanceof List, "Tags should be a List");
                            @SuppressWarnings("unchecked")
                            List<String> tags = (List<String>) tagsObj;
                            assertEquals(2, tags.size(), "Should have 2 tags");
                            assertTrue(tags.contains("urgent"), "Should contain 'urgent' tag");
                            assertTrue(
                                    tags.contains("quarterly"), "Should contain 'quarterly' tag");

                            // 验证 payload 键存在
                            assertTrue(
                                    retrievedDoc.hasPayloadKey("filename"),
                                    "Should have filename key");
                            assertFalse(
                                    retrievedDoc.hasPayloadKey("nonexistent"),
                                    "Should not have nonexistent key");

                            // 验证内容保留
                            assertEquals(
                                    "Test document content",
                                    retrievedDoc.getMetadata().getContentText(),
                                    "Content should match");

                            // 使用 getPayloadValueAs 验证自定义对象
                            CustomObject retrievedCustom =
                                    retrievedDoc.getPayloadValueAs("custom", CustomObject.class);
                            assertNotNull(retrievedCustom, "Custom object should not be null");
                        })
                .verifyComplete();
    }

    /**
     * 创建测试文档的辅助方法。
     */
    private Document createDocument(String docId, String content, double[] embedding) {
        TextBlock textBlock = TextBlock.builder().text(content).build();
        DocumentMetadata metadata = new DocumentMetadata(textBlock, docId, "0");
        Document doc = new Document(metadata);
        if (embedding != null) {
            doc.setEmbedding(embedding);
        }
        return doc;
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
