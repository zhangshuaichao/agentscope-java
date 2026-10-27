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

import io.agentscope.core.rag.exception.VectorStoreException;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.store.dto.SearchDocumentDto;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import reactor.core.publisher.Mono;

/**
 * 内存向量数据库实现。
 *
 * <p>使用 ConcurrentHashMap 存储向量以支持并发，向量搜索采用常见的余弦相似度。
 *
 * <p>【学习提示】这里遍历全部文档计算余弦相似度，再排序截取，不使用近似最近邻索引。ConcurrentHashMap 支持并发单项操作，但不意味着一次搜索看到的是跨全部文档的事务快照，也不使返回的可变文档自动线程安全。
 *
 * <p>适用场景：
 * <ul>
 * <li>开发与测试。</li>
 * <li>中小型数据集。</li>
 * <li>RAG 系统原型。</li>
 * </ul>
 *
 * <p>大规模生产数据可考虑 Qdrant、ChromaDB、Pinecone 等专用向量数据库。
 *
 * <p>用法示例：
 * <pre>{@code
 * InMemoryStore store = InMemoryStore.builder()
 *     .dimensions(1024)
 *     .build();
 *
 * // 添加文档
 * store.add(documents).block();
 *
 * // 搜索
 * List<Document> results = store.search(queryEmbedding, 5, 0.8).block();
 * }</pre>
 *
 * <p><b>异常处理：</b>
 * <ul>
 * <li>{@link IllegalArgumentException}：输入无效，如 null 文档、null 向量或无效 limit。</li>
 * <li>{@link VectorStoreException}：向量专属错误，如维度不匹配。</li>
 * </ul>
 */
public class InMemoryStore implements VDBStoreBase {

    private final Map<String, Document> documents;
    private final int dimensions;

    /**
     * 使用指定向量维度创建 InMemoryStore。
     *
     * @param dimensions 待存储向量维度
     * @throws IllegalArgumentException 当维度不是正数时
     */
    private InMemoryStore(final int dimensions) {
        if (dimensions <= 0) {
            throw new IllegalArgumentException("Dimensions must be positive");
        }
        this.dimensions = dimensions;
        this.documents = new ConcurrentHashMap<>();
    }

    @Override
    public Mono<Void> add(final List<Document> documentList) {
        if (documentList == null) {
            return Mono.error(new IllegalArgumentException("Document list cannot be null"));
        }
        if (documentList.isEmpty()) {
            return Mono.empty();
        }

        return Mono.fromCallable(
                () -> {
                    for (Document document : documentList) {
                        if (document == null) {
                            throw new IllegalArgumentException("Document cannot be null");
                        }
                        if (document.getEmbedding() == null) {
                            throw new IllegalArgumentException("Document must have embedding set");
                        }

                        validateDimensions(document.getEmbedding(), "Embedding");

                        // 为嵌入向量创建防御性副本。
                        double[] embeddingCopy =
                                Arrays.copyOf(
                                        document.getEmbedding(), document.getEmbedding().length);
                        Document docCopy = new Document(document.getMetadata());
                        docCopy.setEmbedding(embeddingCopy);
                        docCopy.setVectorName(document.getVectorName());
                        documents.put(document.getId(), docCopy);
                    }
                    return null;
                });
    }

    @Override
    public Mono<List<Document>> search(SearchDocumentDto searchDocumentDto) {
        String vectorName = searchDocumentDto.getVectorName();
        double[] queryEmbedding = searchDocumentDto.getQueryEmbedding();
        int limit = searchDocumentDto.getLimit();
        Double scoreThreshold = searchDocumentDto.getScoreThreshold();

        try {
            validateDimensions(queryEmbedding, "Query embedding");
        } catch (Exception e) {
            return Mono.error(e);
        }

        if (limit <= 0) {
            return Mono.error(new IllegalArgumentException("Limit must be positive"));
        }

        return Mono.fromCallable(
                () -> {
                    if (documents.isEmpty()) {
                        return new ArrayList<>();
                    }

                    List<Document> results = new ArrayList<>();

                    // 计算全部文档的相似度。
                    for (Document doc : documents.values()) {
                        if (vectorName != null && !vectorName.equals(doc.getVectorName())) {
                            continue;
                        }

                        double similarity =
                                DistanceCalculator.cosineSimilarity(
                                        queryEmbedding, doc.getEmbedding());

                        // 应用已指定分数阈值。
                        if (scoreThreshold != null && similarity < scoreThreshold) {
                            continue;
                        }

                        Document docWithScore = new Document(doc.getMetadata());
                        docWithScore.setEmbedding(doc.getEmbedding());
                        docWithScore.setScore(similarity);
                        results.add(docWithScore);
                    }

                    // 按相似度降序排序并取前若干结果。
                    return results.stream()
                            .sorted(
                                    Comparator.comparing(
                                            Document::getScore,
                                            Comparator.nullsLast(Comparator.reverseOrder())))
                            .limit(limit)
                            .toList();
                });
    }

    @Override
    public Mono<Boolean> delete(final String id) {
        if (id == null) {
            return Mono.error(new IllegalArgumentException("Document ID cannot be null"));
        }
        return Mono.fromCallable(
                () -> {
                    Document removed = documents.remove(id);
                    return removed != null;
                });
    }

    /**
     * 校验嵌入维度是否正确。
     *
     * @param embedding 待校验向量
     * @param paramName 错误消息使用的参数名称
     * @throws IllegalArgumentException 当向量为 null 时
     * @throws VectorStoreException 当维度与预期不符时
     */
    private void validateDimensions(final double[] embedding, final String paramName)
            throws VectorStoreException {
        if (embedding == null) {
            throw new IllegalArgumentException(paramName + " cannot be null");
        }
        if (embedding.length != dimensions) {
            throw new VectorStoreException(
                    String.format(
                            "%s dimension mismatch: expected %d, got %d",
                            paramName, dimensions, embedding.length));
        }
    }

    /**
     * 获取当前存储文档数。
     *
     * <p>此方法线程安全，返回当前大小的快照。
     *
     * @return 文档数，始终非负
     */
    public int size() {
        return documents.size();
    }

    /**
     * 检查存储是否为空。
     *
     * <p>等价于 {@code size() == 0}，此方法线程安全。
     *
     * @return 无文档时为 true，否则为 false
     */
    public boolean isEmpty() {
        return documents.isEmpty();
    }

    /**
     * 清空全部文档。
     *
     * <p>清空后 {@link #size()} 返回 0，{@link #isEmpty()} 返回 true；并发写入可能随后改变此状态。此操作线程安全。
     */
    public void clear() {
        documents.clear();
    }

    /**
     * 获取存储向量的维度。
     *
     * @return 向量维度
     */
    public int getDimensions() {
        return dimensions;
    }

    /**
     * 创建 InMemoryStore 构建器。
     *
     * @return 新的 Builder 实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * InMemoryStore 的构建器。
     */
    public static class Builder {
        private int dimensions = 1024; // 默认维度。

        private Builder() {}

        /**
         * 设置向量维度。
         *
         * @param dimensions 待存储向量维度，必须为正数
         * @return 当前构建器，支持链式调用
         */
        public Builder dimensions(int dimensions) {
            this.dimensions = dimensions;
            return this;
        }

        /**
         * 构建 InMemoryStore 实例。
         *
         * @return 新的 InMemoryStore 实例
         * @throws IllegalArgumentException 当维度不是正数时
         */
        public InMemoryStore build() {
            return new InMemoryStore(dimensions);
        }
    }
}
