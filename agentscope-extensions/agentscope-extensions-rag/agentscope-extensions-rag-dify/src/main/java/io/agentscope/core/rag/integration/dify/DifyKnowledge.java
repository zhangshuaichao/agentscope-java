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
package io.agentscope.core.rag.integration.dify;

import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.RetrieveConfig;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Dify 云知识库实现。
 *
 * <p>实现 Knowledge 接口，集成云端或自托管 Dify 知识库，为 RAG（检索增强生成）提供检索能力。
 *
 * <p>主要能力：
 * <ul><li>支持云端及自托管部署。</li><li>支持关键词、语义、混合和全文检索。</li>
 * <li>支持重排序和分数过滤。</li><li>与百炼 RAG 插件共用 Knowledge 接口。</li></ul>
 *
 * <p><b>功能范围：</b>与百炼插件类似，仅负责检索。文档上传、删除与更新应通过 Dify 控制台管理。
 *
 * <p>用法示例：
 * <pre>{@code
 * // 1. Configure Dify connection
 * DifyRAGConfig config = DifyRAGConfig.builder()
 *     .apiKey(System.getenv("DIFY_RAG_API_KEY"))
 *     .datasetId("your-dataset-id")  // Configure in code
 *     .retrievalMode(RetrievalMode.HYBRID_SEARCH)
 *     .enableRerank(true)
 *     .build();
 *
 * // 2. Create knowledge base instance
 * DifyKnowledge knowledge = DifyKnowledge.builder()
 *     .config(config)
 *     .build();
 *
 * // 3. Retrieve documents
 * RetrieveConfig retrieveConfig = RetrieveConfig.builder()
 *     .limit(5)
 *     .scoreThreshold(0.5)
 *     .build();
 *
 * List<Document> results = knowledge.retrieve("query text", retrieveConfig).block();
 *
 * // 4. Use with agents via built-in RAG configuration
 * ReActAgent agent = ReActAgent.builder()
 *     .name("Assistant")
 *     .model(chatModel)
 *     .knowledge(knowledge)
 *     .ragMode(RAGMode.AGENTIC)
 *     .build();
 * }</pre>
 *
 * <p><b>注意：</b>此插件不支持文档管理，请通过 Dify 控制台操作。
 * 此设计保持与百炼 RAG 插件的接口一致，并使职责集中。
 *
 * <p>【学习提示】此实现向检索端点提交的是 query 字符串，不会自动发送完整会话历史。
 * 需要上下文感知检索时，应由上层先改写查询，不能仅因实现 Knowledge 接口就假定具备历史记忆。
 */
public class DifyKnowledge implements Knowledge {

    private static final Logger log = LoggerFactory.getLogger(DifyKnowledge.class);

    private final DifyRAGClient client;
    private final DifyRAGConfig config;

    /**
     * 创建 DifyKnowledge 实例。
     *
     * @param client Dify API 客户端
     * @param config Dify 配置，单独创建客户端时可为 null
     */
    private DifyKnowledge(DifyRAGClient client, DifyRAGConfig config) {
        if (client == null) {
            throw new IllegalArgumentException("DifyRAGClient cannot be null");
        }

        this.client = client;
        this.config = config;

        log.info("DifyKnowledge initialized for dataset: {}", client.getConfig().getDatasetId());
    }

    /**
     * 向知识库添加文档。
     *
     * <p><b>注意：</b>此操作不受支持，与百炼 RAG 插件一致。请通过 Dify 控制台管理：
     * <ol><li>登录 Dify 控制台 https://cloud.dify.ai。</li><li>进入知识库并选择数据集。</li>
     * <li>上传文档。</li><li>等待处理完成。</li><li>随后即可通过此插件检索。</li></ol>
     *
     * @param documents 要添加的文档列表
     * @return 以不支持操作错误结束的 Mono
     */
    @Override
    public Mono<Void> addDocuments(List<Document> documents) {
        return Mono.error(
                new UnsupportedOperationException(
                        "Document upload is not supported via this plugin. "
                                + "Please use Dify console to manage documents. "
                                + "This design keeps the plugin focused on retrieval and "
                                + "compatible with Bailian RAG plugin."));
    }

    /**
     * 根据查询检索相关文档。
     *
     * <p>按配置的关键词、语义、混合或全文模式向 Dify 查询，再按分数阈值过滤并限制结果数量。
     *
     * <p>Dify 检索 API 可提供多种检索模式、语义/混合模式的查询嵌入、
     * 向量相似性检索、可选重排序和元数据过滤。
     *
     * <p>过程：
     * <ol><li>携带配置参数将查询发送到 Dify。</li><li>Dify 按检索模式搜索。</li>
     * <li>启用时由服务端执行重排序。</li><li>客户端再按调用级分数阈值过滤。</li>
     * <li>按分数降序返回最多 K 个文档。</li></ol>
     *
     * <p>【学习提示】服务端配置阈值与调用级 RetrieveConfig 阈值是两道不同过滤。
     * 客户端只能再删减已召回结果，不能恢复服务端已经过滤掉的文档；分数也不等于事实可信度。
     *
     * @param query 查询文本
     * @param config 检索配置，包括数量和分数阈值
     * @return 发出相关 Document 列表的 Mono，按分数排序
     */
    @Override
    public Mono<List<Document>> retrieve(String query, RetrieveConfig config) {
        if (query == null) {
            return Mono.error(new IllegalArgumentException("Query cannot be null"));
        }
        if (config == null) {
            return Mono.error(new IllegalArgumentException("RetrieveConfig cannot be null"));
        }
        if (query.trim().isEmpty()) {
            log.debug("Empty query received, returning empty result");
            return Mono.just(List.of());
        }

        log.debug(
                "Retrieving documents for query: '{}' (limit: {}, threshold: {})",
                truncate(query, 50),
                config.getLimit(),
                config.getScoreThreshold());

        return client.retrieve(query, config.getLimit())
                .map(DifyDocumentConverter::fromDifyResponse)
                .map(
                        documents ->
                                documents.stream()
                                        .filter(
                                                doc ->
                                                        doc.getScore() != null
                                                                && doc.getScore()
                                                                        >= config
                                                                                .getScoreThreshold())
                                        .sorted(
                                                Comparator.comparing(
                                                        Document::getScore,
                                                        Comparator.reverseOrder()))
                                        .limit(config.getLimit())
                                        .collect(Collectors.toList()))
                .doOnSuccess(
                        docs ->
                                log.debug(
                                        "Successfully retrieved {} documents (after filtering)",
                                        docs.size()))
                .doOnError(error -> log.error("Failed to retrieve documents from Dify", error));
    }

    /**
     * 获取 Dify API 客户端。
     *
     * @return 客户端
     */
    public DifyRAGClient getClient() {
        return client;
    }

    /**
     * 获取 Dify 配置。
     *
     * @return 配置，不可用时为 null
     */
    public DifyRAGConfig getConfig() {
        return config;
    }

    /**
     * 将字符串截断到指定长度并附加省略号。
     *
     * @param text 待截断文本
     * @param maxLength 最大长度
     * @return 截断后的文本
     */
    private String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength - 3) + "...";
    }

    /**
     * 创建 DifyKnowledge 构建器。
     *
     * @return 新的 Builder 实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /** DifyKnowledge 的构建器。 */
    public static class Builder {
        private DifyRAGConfig config;
        private DifyRAGClient client;

        private Builder() {}

        /**
         * 设置 Dify 配置。
         *
         * <p>设置后自动从配置创建 DifyRAGClient。config 和 client 不能同时设置，应选择一种方式。
         *
         * @param config Dify 配置
         * @return 当前构建器，支持链式调用
         */
        public Builder config(DifyRAGConfig config) {
            this.config = config;
            return this;
        }

        /**
         * 直接设置 Dify 客户端。
         *
         * <p>多个 DifyKnowledge 实例需要共享客户端时使用。不能同时设置 config 和 client。
         *
         * @param client Dify 客户端
         * @return 当前构建器，支持链式调用
         */
        public Builder client(DifyRAGClient client) {
            this.client = client;
            return this;
        }

        /**
         * 构建 DifyKnowledge 实例。
         *
         * @return 新的 DifyKnowledge 实例
         * @throws IllegalArgumentException 缺少必要参数时抛出
         * @throws IllegalStateException 同时设置 config 和 client 时抛出
         */
        public DifyKnowledge build() {
            // 验证 config 与 client 只能设置一个。
            if (config != null && client != null) {
                throw new IllegalStateException(
                        "Cannot set both config and client. Choose one approach.");
            }

            // 需要时从配置创建客户端。
            DifyRAGClient finalClient = client;
            if (finalClient == null) {
                if (config == null) {
                    throw new IllegalArgumentException("Either config or client must be provided");
                }
                finalClient = new DifyRAGClient(config);
            }

            return new DifyKnowledge(finalClient, config);
        }
    }
}
