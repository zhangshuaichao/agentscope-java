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
package io.agentscope.core.rag.integration.haystack;

import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.integration.haystack.model.HayStackDocument;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.RetrieveConfig;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * HayStack RAG 知识库实现。
 *
 * <p>实现 Knowledge 接口，将 HayStack 知识库检索接入 RAG（检索增强生成）。
 *
 * <p>主要能力：
 * <ul><li>可连接以 HayStack 构建的检索流水线。</li><li>支持本地或远程部署。</li>
 * <li>与百炼、Dify RAG 插件共用 Knowledge 接口。</li></ul>
 *
 * <p><b>功能范围：</b>本插件仅负责检索，文档上传、删除与更新由 HayStack 服务端管理。
 * 服务端可以自行组合检索、排序和生成流水线，但本适配器不自动实现全部流程。
 *
 * <p><b>用法示例：</b>
 * <pre>{@code
 * HayStackConfig config = HayStackConfig.builder()
 *     .baseUrl("http://localhost:8080")
 *     .topK(10)
 *     .build();
 *
 * HayStackKnowledge knowledge = HayStackKnowledge.builder()
 *     .config(config)
 *     .build();
 *
 * // 检索文档
 * List<Document> docs = knowledge.retrieve("What is AI?", retrieveConfig).block();
 *
 * // 与智能体配合使用
 * ReActAgent agent = ReActAgent.builder()
 *     .knowledge(knowledge)
 *     .ragMode(RAGMode.AGENTIC)
 *     .build();
 * }</pre>
 *
 * <p><b>注意：</b>请通过 HayStack 服务端管理文档；本插件的职责保持在检索，
 * 以便与百炼、Dify 插件保持统一接口。
 */
public class HayStackKnowledge implements Knowledge {

    private static final Logger logger = LoggerFactory.getLogger(HayStackKnowledge.class);

    private final HayStackClient client;

    private final HayStackConfig config;

    private HayStackKnowledge(Builder builder) {
        this.config = builder.config;
        this.client = builder.client != null ? builder.client : new HayStackClient(config);
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * 向 Haystack 知识库添加文档。
     *
     * <p><b>注意：</b>此操作不受支持，文档导入与索引由 Haystack 服务自身管理。
     *
     * <p>添加或更新文档的典型流程：
     * <ol><li>在配置的数据目录准备源文件。</li><li>重新运行索引流水线或按部署方式重启服务。</li>
     * <li>文档写入 Chroma 等文档存储。</li><li>完成索引后通过此 API 检索。</li></ol>
     *
     * <p>这样将文档管理与检索职责明确分离，避免由本适配器引入不一致的索引状态。
     *
     * @param documents 待添加文档列表
     * @return 以不支持操作错误结束的 Mono
     */
    @Override
    public Mono<Void> addDocuments(List<Document> documents) {
        return Mono.error(
                new UnsupportedOperationException(
                        "Document upload is not supported via this plugin. "
                                + "Please use HayStack to manage documents. "
                                + "This design keeps the plugin focused on retrieval and "
                                + "compatible with Bailian RAG plugin."));
    }

    /**
     * 从 HayStack 知识库检索相关文档，并支持分数过滤。
     *
     * <p>目标服务可提供向量相似性检索、相关句子的邻近上下文、可配置 top K 与分数阈值过滤。
     *
     * <p><b>注意：</b>本适配器的请求协议没有会话历史或内置重排序参数，
     * 需要这些能力时应由服务端流水线或上层应用补充。
     *
     * <p>【学习提示】保留服务端结果顺序，不在本地重新排序；没有分数的句子窗口结果直接通过过滤。
     * 有分数时本地仍使用“大于等于阈值”，不能把后端的距离度量语义自动等同为相似度分数。
     *
     * @param query 查询文本，必填
     * @param config 检索配置，包括数量和分数
     * @return 发出检索文档列表的 Mono，保留服务端返回顺序
     */
    @Override
    public Mono<List<Document>> retrieve(String query, RetrieveConfig config) {
        if (query == null) {
            return Mono.error(new IllegalArgumentException("Query cannot be null or empty"));
        }

        if (config == null) {
            return Mono.error(new IllegalArgumentException("RetrieveConfig cannot be null"));
        }

        if (query.trim().isEmpty()) {
            logger.debug("Empty query received, returning empty result");
            return Mono.just(List.of());
        }

        logger.debug(
                "Retrieving documents for query: '{}' (limit: {}, threshold: {})",
                query,
                config.getLimit(),
                config.getScoreThreshold());

        // 提取检索参数。
        Integer topK = config.getLimit() > 0 ? config.getLimit() : null;

        Double scoreThreshold = config.getScoreThreshold() > 0 ? config.getScoreThreshold() : null;

        // 调用 HayStack API。
        return client.retrieve(query, topK, scoreThreshold)
                .map(
                        response -> {
                            if (response.getDocuments() == null
                                    && response.getContextDocuments() == null) {
                                logger.warn(
                                        "HayStack returned empty response for query: {}", query);
                                return new ArrayList<Document>();
                            }

                            // 优先取 documents，否则使用 contextDocuments。
                            List<HayStackDocument> sourceDocs =
                                    response.getDocuments() != null
                                            ? response.getDocuments()
                                            : response.getContextDocuments();

                            List<Document> documents =
                                    HayStackDocumentConverter.convertToDocuments(sourceDocs);

                            logger.debug(
                                    "HayStack retrieved {} documents for query: {}",
                                    documents.size(),
                                    query);

                            return documents;
                        })
                .map(
                        documents ->
                                documents.stream()
                                        .filter(
                                                doc -> {
                                                    // 使用 SentenceWindowRetriever 时可能不返回分数，
                                                    // 此时跳过过滤。
                                                    if (doc.getScore() == null) return true;
                                                    return doc.getScore()
                                                            >= config.getScoreThreshold();
                                                })
                                        .toList())
                .onErrorResume(
                        e -> {
                            logger.error("HayStack retrieval failed for query: {}", query, e);
                            return Mono.error(e);
                        });
    }

    public static class Builder {
        private HayStackConfig config;
        private HayStackClient client;

        public Builder config(HayStackConfig config) {
            this.config = config;
            return this;
        }

        public Builder client(HayStackClient client) {
            this.client = client;
            return this;
        }

        public HayStackKnowledge build() {
            if (config == null) {
                throw new IllegalArgumentException("HayStackConfig is required");
            }

            return new HayStackKnowledge(this);
        }
    }
}
