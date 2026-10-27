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
package io.agentscope.core.rag.integration.ragflow;

import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.RetrieveConfig;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * RAGFlow 知识库实现。
 *
 * <p>集成 RAGFlow 知识库服务，实现 Knowledge 接口，为 RAGFlow 托管知识库
 * 提供 RAG（检索增强生成）能力。
 *
 * <p>主要特性：
 * <ul>
 *   <li>支持本地或远程 RAGFlow 部署
 *   <li>带 OCR 的高级文档理解
 *   <li>知识图谱增强检索
 *   <li>支持重排序以改善结果
 *   <li>当前接口只传查询字符串，不传入对话历史
 *   <li>兼容百炼和 Dify RAG 插件（相同 Knowledge 接口）
 * </ul>
 *
 * <p><b>功能范围：</b>与百炼和 Dify RAG 插件类似，此插件仅聚焦检索能力。
 * 文档管理（上传／删除／更新）应通过 RAGFlow 控制台执行。
 *
 * <p><b>原始使用示例：</b>下方保留上游历史代码，部分构建 API 与当前版本不同；具体用法以本类 Builder 和现有测试为准。
 * <pre>{@code
 * RAGFlowConfig config = RAGFlowConfig.builder()
 *     .apiKey("ragflow-xxx")
 *     .baseUrl("http://localhost:9380")
 *     .knowledgeBaseId("kb-xxx")
 *     .topK(10)
 *     .similarityThreshold(0.5)
 *     .enableRerank(true)
 *     .build();
 *
 * RAGFlowKnowledge knowledge = RAGFlowKnowledge.builder()
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
 * <p><b>注意：</b>此插件不支持文档管理。请使用 RAGFlow 控制台上传、删除和管理文档。
 * 此设计保证与百炼和 Dify RAG 插件兼容，并保持插件职责集中。
 *
 * @author RAGFlow Integration Team
 */
public class RAGFlowKnowledge implements Knowledge {

    private static final Logger logger = LoggerFactory.getLogger(RAGFlowKnowledge.class);

    private final RAGFlowClient client;

    private final RAGFlowConfig config;

    private RAGFlowKnowledge(Builder builder) {
        this.config = builder.config;
        this.client = builder.client != null ? builder.client : new RAGFlowClient(config);
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * 从 RAGFlow 知识库检索文档。
     *
     * <p>搜索 RAGFlow 数据集中与给定查询相关的文档，实际客户端请求 /api/v1/retrieval。
     *
     * <p><b>历史注释中的端点（并非当前请求）：</b>{@code POST /api/v1/datasets/{dataset_id}/retrieve-chunks}
     *
     * <p><b>特性：</b>
     * <ul>
     *   <li>向量相似度搜索</li>
     *   <li>可配置 top-K 结果</li>
     *   <li>相似度阈值过滤</li>
     *   <li>基于元数据的过滤（通过配置）</li>
     * </ul>
     *
     * <p><b>注意：</b>与某些其他 RAG 系统不同，RAGFlow 的 retrieve-chunks API 不支持：
     * <ul>
     *   <li>❌ 用于上下文感知检索的对话历史</li>
     * </ul>
     *
     * @param query 查询文本（必填）
     * @param config 检索配置（数量限制、分数阈值）
     * @return 发出检索文档列表的 Mono，按相关性排序
     */
    @Override
    public Mono<List<Document>> retrieve(String query, RetrieveConfig config) {
        if (query == null || query.trim().isEmpty()) {
            logger.warn("Empty query provided, returning empty result");
            return Mono.just(new ArrayList<>());
        }

        logger.debug("RAGFlow retrieve: query={}, config={}", query, config);

        // 【学习提示】上游类说明、方法说明和示例存在历史差异；本路径实际只提取数量与
        // 分数阈值，没有传递对话历史，客户端实际请求 /api/v1/retrieval。学习行为应以调用链为准。
        // 提取检索参数
        Integer topK = config != null ? config.getLimit() : null;
        Double similarityThreshold = config != null ? config.getScoreThreshold() : null;

        // 调用 RAGFlow API（元数据条件来自配置）
        return client.retrieve(query, topK, similarityThreshold, null)
                .map(
                        response -> {
                            if (response.getData() == null
                                    || response.getData().getChunks() == null) {
                                logger.warn("RAGFlow returned empty response for query: {}", query);
                                return new ArrayList<Document>();
                            }

                            List<Document> documents =
                                    RAGFlowDocumentConverter.convertToDocuments(
                                            response.getData().getChunks());

                            logger.debug(
                                    "RAGFlow retrieved {} documents for query: {}",
                                    documents.size(),
                                    query);

                            return documents;
                        })
                .onErrorResume(
                        e -> {
                            logger.error("RAGFlow retrieval failed for query: {}", query, e);
                            return Mono.error(e);
                        });
    }

    /**
     * 向知识库添加文档。
     *
     * <p><b>注意：</b>此操作不受支持，与百炼和 Dify RAG 插件一致。
     * 文档管理应通过 RAGFlow 控制台执行：
     * <ol>
     *   <li>访问 RAGFlow 控制台（http://localhost:9380）</li>
     *   <li>进入 Knowledge Base 并选择数据集</li>
     *   <li>点击“Upload Document”添加文件</li>
     *   <li>等待处理完成</li>
     *   <li>随后可通过此插件检索这些文档</li>
     * </ol>
     *
     * @param documents 待添加的文档列表
     * @return 以“不支持此操作”错误结束的 Mono
     */
    @Override
    public Mono<Void> addDocuments(List<Document> documents) {
        return Mono.error(
                new UnsupportedOperationException(
                        "Document upload is not supported via this plugin. "
                                + "Please use RAGFlow console to manage documents. "
                                + "This design keeps the plugin focused on retrieval and "
                                + "compatible with Bailian and Dify RAG plugins."));
    }

    public static class Builder {

        private RAGFlowConfig config;

        private RAGFlowClient client;

        public Builder config(RAGFlowConfig config) {
            this.config = config;
            return this;
        }

        public Builder client(RAGFlowClient client) {
            this.client = client;
            return this;
        }

        public RAGFlowKnowledge build() {
            if (config == null) {
                throw new IllegalArgumentException("RAGFlowConfig is required");
            }
            return new RAGFlowKnowledge(this);
        }
    }
}
