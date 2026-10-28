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
package io.agentscope.core.rag.knowledge;

import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.embedding.EmbeddingUsage;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import io.agentscope.core.rag.model.RetrieveConfig;
import io.agentscope.core.rag.store.VDBStoreBase;
import io.agentscope.core.rag.store.dto.SearchDocumentDto;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 知识库的简单实现。
 *
 * <p>集成嵌入模型与向量存储，提供完整 RAG（检索增强生成）知识库，负责文档嵌入、存储及检索流程。
 *
 * <p>【学习提示】查询先嵌入，再让向量库按 limit 取候选，最后对有效候选过滤空分数及低于阈值的结果并降序排序。filter 虽含空文档防御判断，但不能据此承诺上游接受带 null 元素的结果列表。因此过滤后可能不足 limit，且没有额外重排序模型。此层只返回检索证据，不把文档变成系统指令；提示注入由上层消费方处理，检索到的文本不应自动获得指令权限。
 *
 * <p>【学习提示】入库使用 flatMap 生成向量，收集完成后再批量存储。缺少内容的文档会跳过，但嵌入错误会传播；已写回原 Document 的向量不会因此回滚。日志中的 doOnError 只是观察错误，不是恢复。
 *
 * <p>工作流：
 * <ul>
 * <li><b>addDocuments：</b>嵌入文档 → 将带元数据/载荷的文档存入向量库。</li>
 * <li><b>retrieve：</b>嵌入查询 → 搜索文档 → 按阈值过滤 → 返回文档。</li>
 * </ul>
 *
 * <p>用法示例：
 * <pre>{@code
 * EmbeddingModel embeddingModel = DashScopeTextEmbedding.builder()
 *     .apiKey(apiKey)
 *     .modelName("text-embedding-v3")
 *     .dimensions(1024)
 *     .build();
 * VDBStoreBase vectorStore = InMemoryStore.builder().dimensions(1024).build();
 *
 * SimpleKnowledge knowledge = SimpleKnowledge.builder()
 *     .embeddingModel(embeddingModel)
 *     .embeddingStore(vectorStore)
 *     .build();
 *
 * // 添加文档
 * List<Document> documents = reader.read(input).block();
 * knowledge.addDocuments(documents).block();
 *
 * // 检索文档
 * RetrieveConfig config = RetrieveConfig.builder().limit(5).scoreThreshold(0.5).build();
 * List<Document> results = knowledge.retrieve("query text", config).block();
 * }</pre>
 */
public class SimpleKnowledge implements Knowledge {

    private static final Logger log = LoggerFactory.getLogger(SimpleKnowledge.class);

    private final EmbeddingModel embeddingModel;
    private final VDBStoreBase embeddingStore;
    private final Consumer<EmbeddingUsage> usageListener;

    /**
     * 创建 SimpleKnowledge 实例。
     *
     * @param embeddingModel 用于生成向量的嵌入模型
     * @param embeddingStore 用于存储与搜索的向量库
     * @throws IllegalArgumentException 当任一参数为 null 时
     */
    private SimpleKnowledge(
            EmbeddingModel embeddingModel,
            VDBStoreBase embeddingStore,
            Consumer<EmbeddingUsage> usageListener) {
        if (embeddingModel == null) {
            throw new IllegalArgumentException("Embedding model cannot be null");
        }
        if (embeddingStore == null) {
            throw new IllegalArgumentException("Embedding store cannot be null");
        }
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.usageListener = usageListener;
    }

    @Override
    public Mono<Void> addDocuments(List<Document> documents) {
        if (documents == null) {
            return Mono.error(new IllegalArgumentException("Documents list cannot be null"));
        }
        if (documents.isEmpty()) {
            return Mono.empty();
        }

        return Flux.fromIterable(documents)
                .flatMap(
                        doc -> {
                            // 从文档元数据提取 ContentBlock。
                            ContentBlock contentBlock = extractContentBlock(doc.getMetadata());
                            if (contentBlock == null) {
                                log.warn(
                                        "Cannot extract ContentBlock from document: {}",
                                        doc.getId());
                                return Mono.empty();
                            }
                            // 为文档内容生成嵌入。
                            return embeddingModel
                                    .embed(contentBlock)
                                    .doOnNext(
                                            response -> {
                                                doc.setEmbedding(response.getEmbedding());
                                                notifyUsage(response.getUsage());
                                            })
                                    .thenReturn(doc);
                        })
                .collectList()
                .flatMap(
                        docsWithEmbeddings -> {
                            // 向向量库批量存储文档，包含元数据/载荷。
                            if (docsWithEmbeddings.isEmpty()) {
                                return Mono.empty();
                            }
                            return embeddingStore.add(docsWithEmbeddings);
                        })
                .doOnError(error -> log.error("Failed to add documents to knowledge base", error));
    }

    @Override
    public Mono<List<Document>> retrieve(String query, RetrieveConfig config) {
        if (query == null) {
            return Mono.error(new IllegalArgumentException("Query cannot be null"));
        }
        if (config == null) {
            return Mono.error(new IllegalArgumentException("RetrieveConfig cannot be null"));
        }
        if (query.trim().isEmpty()) {
            return Mono.just(new ArrayList<>());
        }

        // 将查询字符串转换为 TextBlock。
        TextBlock queryBlock = TextBlock.builder().text(query).build();
        return embeddingModel
                .embed(queryBlock)
                .doOnNext(response -> notifyUsage(response.getUsage()))
                .flatMap(
                        response ->
                                embeddingStore.search(
                                        SearchDocumentDto.builder()
                                                .vectorName(config.getVectorName())
                                                .queryEmbedding(response.getEmbedding())
                                                .limit(config.getLimit())
                                                .scoreThreshold(null)
                                                .build()))
                .flatMap(
                        results ->
                                Flux.fromIterable(results)
                                        .filter(
                                                doc ->
                                                        doc != null
                                                                && doc.getScore() != null
                                                                && doc.getScore()
                                                                        >= config
                                                                                .getScoreThreshold())
                                        .sort(
                                                Comparator.comparing(
                                                        Document::getScore,
                                                        Comparator.reverseOrder()))
                                        .collectList());
    }

    /**
     * 获取知识库使用的嵌入模型。
     *
     * @return 嵌入模型
     */
    public EmbeddingModel getEmbeddingModel() {
        return embeddingModel;
    }

    /**
     * 获取知识库使用的向量存储。
     *
     * @return 向量存储
     */
    public VDBStoreBase getEmbeddingStore() {
        return embeddingStore;
    }

    /**
     * 从 DocumentMetadata 提取 ContentBlock。
     *
     * <p>元数据直接保存 ContentBlock，因此此方法直接返回它。
     *
     * @param metadata 文档元数据
     * @return 元数据中的 ContentBlock；元数据为空或无内容时为 null
     */
    private ContentBlock extractContentBlock(DocumentMetadata metadata) {
        if (metadata == null) {
            return null;
        }
        return metadata.getContent();
    }

    /**
     * 收到嵌入响应后通知可选使用统计监听器。
     *
     * <p>提供者未报告使用统计时不调用。监听器运行在发出响应的线程上，应保持非阻塞且线程安全。
     *
     * @param usage 嵌入提供者报告的使用统计
     */
    private void notifyUsage(EmbeddingUsage usage) {
        if (usageListener != null && usage != null) {
            usageListener.accept(usage);
        }
    }

    /**
     * 创建 SimpleKnowledge 构建器。
     *
     * @return 新的 Builder 实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * SimpleKnowledge 的构建器。
     */
    public static class Builder {
        private EmbeddingModel embeddingModel;
        private VDBStoreBase embeddingStore;
        private Consumer<EmbeddingUsage> usageListener;

        private Builder() {}

        /**
         * 设置嵌入模型。
         *
         * @param embeddingModel 使用的嵌入模型
         * @return 当前构建器，支持链式调用
         */
        public Builder embeddingModel(EmbeddingModel embeddingModel) {
            this.embeddingModel = embeddingModel;
            return this;
        }

        /**
         * 设置嵌入存储（向量数据库）。
         *
         * @param embeddingStore 使用的向量存储
         * @return 当前构建器，支持链式调用
         */
        public Builder embeddingStore(VDBStoreBase embeddingStore) {
            this.embeddingStore = embeddingStore;
            return this;
        }

        /**
         * 设置提供者嵌入使用统计的可选监听器。
         *
         * <p>监听器在发出响应的线程上运行。其异常会沿响应式链传播，使外围 {@code addDocuments} 或 {@code retrieve} 调用失败。调用方应在监听器内部捕获异常或确保它不会抛出。
         *
         * @param usageListener 每次报告使用统计的嵌入调用后执行的监听器
         * @return 当前构建器实例
         */
        public Builder usageListener(Consumer<EmbeddingUsage> usageListener) {
            this.usageListener = usageListener;
            return this;
        }

        /**
         * 构建 SimpleKnowledge 实例。
         *
         * @return 新的 SimpleKnowledge 实例
         * @throws IllegalArgumentException 当缺少必填参数时
         */
        public SimpleKnowledge build() {
            if (embeddingModel == null) {
                throw new IllegalArgumentException("Embedding model is required");
            }
            if (embeddingStore == null) {
                throw new IllegalArgumentException("Embedding store is required");
            }
            return new SimpleKnowledge(embeddingModel, embeddingStore, usageListener);
        }
    }
}
