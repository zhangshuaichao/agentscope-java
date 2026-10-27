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
package io.agentscope.core.rag.integration.bailian;

import com.aliyun.bailian20231229.models.RetrieveResponse;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
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
 * 百炼云知识库实现。
 *
 * <p>集成阿里云百炼知识库服务，实现 Knowledge 接口，
 * 为云端托管知识库提供 RAG（检索增强生成）能力。
 *
 * <p>主要特性：
 * <ul>
 *   <li>云端托管知识库，无需本地基础设施</li>
 *   <li>百炼服务自动解析文档并生成嵌入</li>
 *   <li>支持重排序和过滤的企业级搜索</li>
 *   <li>支持结构化、非结构化及图像知识库</li>
 * </ul>
 *
 * <p>使用示例：
 * <pre>{@code
 * // 1. 配置百炼连接
 * BailianConfig config = BailianConfig.builder()
 *     .accessKeyId(System.getenv("ALIBABA_CLOUD_ACCESS_KEY_ID"))
 *     .accessKeySecret(System.getenv("ALIBABA_CLOUD_ACCESS_KEY_SECRET"))
 *     .workspaceId("llm-xxx")
 *     .indexId("mymxbdxxxx")
 *     .build();
 *
 * // 2. 创建知识库实例
 * BailianKnowledge knowledge = BailianKnowledge.builder()
 *     .config(config)
 *     .build();
 *
 * // 3. 检索文档
 * RetrieveConfig retrieveConfig = RetrieveConfig.builder()
 *     .limit(5)
 *     .scoreThreshold(0.5)
 *     .build();
 *
 * List<Document> results = knowledge.retrieve("query text", retrieveConfig).block();
 *
 * // 4. 通过 KnowledgeRetrievalTools 与智能体配合使用
 * KnowledgeRetrievalTools tools = new KnowledgeRetrievalTools(knowledge);
 * Toolkit toolkit = new Toolkit();
 * toolkit.registerObject(tools);
 *
 * ReActAgent agent = ReActAgent.builder()
 *     .name("Assistant")
 *     .model(chatModel)
 *     .toolkit(toolkit)
 *     .build();
 * }</pre>
 *
 * <p><b>注意：</b>当前仅支持通过 API 执行检索。
 * 文档上传与管理必须通过百炼控制台进行，或等待未来版本支持。
 */
public class BailianKnowledge implements Knowledge {

    private static final Logger log = LoggerFactory.getLogger(BailianKnowledge.class);

    private final BailianClient client;
    private final BailianConfig config;
    private final String indexId;

    /**
     * 创建新的 BailianKnowledge 实例。
     *
     * @param client 百炼 API 客户端
     * @param config 百炼配置（客户端单独创建时可以为 null）
     * @param indexId 知识库索引 ID
     */
    private BailianKnowledge(BailianClient client, BailianConfig config, String indexId) {
        if (client == null) {
            throw new IllegalArgumentException("BailianClient cannot be null");
        }
        if (indexId == null || indexId.trim().isEmpty()) {
            throw new IllegalArgumentException("IndexId cannot be null or empty");
        }

        this.client = client;
        this.config = config;
        this.indexId = indexId;

        log.info(
                "BailianKnowledge initialized for index: {} in workspace: {}",
                indexId,
                client.getWorkspaceId());
    }

    /**
     * 向知识库添加文档。
     *
     * <p><b>注意：</b>目前尚不支持通过 API 执行此操作。
     * 请使用百炼控制台向知识库上传文档，或等待未来 API 支持。
     *
     * @param documents 待添加的文档列表
     * @return 以“不支持此操作”错误结束的 Mono
     */
    @Override
    public Mono<Void> addDocuments(List<Document> documents) {
        return Mono.error(
                new UnsupportedOperationException(
                        "Document upload via API is not yet supported. "
                                + "Please use Bailian console to manage documents, "
                                + "or this feature will be added in future releases."));
    }

    /**
     * 根据查询检索相关文档。
     *
     * <p>搜索百炼知识库中与给定查询相关的文档。查询由百炼服务自动生成嵌入，
     * 结果按配置指定的分数阈值过滤，并限制数量。
     *
     * <p>百炼检索 API 包括：
     * <ul>
     *   <li>自动查询嵌入</li>
     *   <li>向量相似度搜索</li>
     *   <li>可选重排序，以改善相关性</li>
     *   <li>支持元数据过滤</li>
     * </ul>
     *
     * <p>RetrieveConfig 提供对话历史且 BailianConfig 启用查询改写时，
     * 根据对话上下文改写查询，以改善检索效果。
     *
     * @param query 搜索查询文本
     * @param config 检索配置（数量限制、分数阈值、对话历史）
     * @return 发出相关 Document 对象列表的 Mono，按相关性排序
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
                "Retrieving documents for query: '{}' (limit: {}, threshold: {}, historySize: {})",
                query,
                config.getLimit(),
                config.getScoreThreshold(),
                config.getConversationHistory() != null
                        ? config.getConversationHistory().size()
                        : 0);

        // 将 Msg 对话历史转换为 QueryHistoryEntry
        List<QueryHistoryEntry> queryHistory =
                (config.getConversationHistory() != null
                                && !config.getConversationHistory().isEmpty())
                        ? convertToQueryHistory(config.getConversationHistory())
                        : null;

        // 【学习提示】服务端检索后，本地再统一执行分数阈值过滤、降序排序和数量限制；
        // 因而远端召回候选较多，也不代表最终交给智能体的文档同样多。
        return client.retrieve(indexId, query, config.getLimit(), queryHistory)
                .map(RetrieveResponse::getBody)
                .map(BailianDocumentConverter::fromBailianResponse)
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
                .doOnError(error -> log.error("Failed to retrieve documents from Bailian", error));
    }

    /**
     * 将 Msg 对象列表转换为百炼 API 的 QueryHistoryEntry 格式。
     *
     * <p>从对话历史提取 USER 和 ASSISTANT 消息，转换为百炼多轮改写 API
     * 要求的格式。仅提取消息文本内容，忽略其他内容类型。
     *
     * @param messages Msg 对象形式的对话历史
     * @return QueryHistoryEntry 对象列表；无有效消息时为空列表
     */
    private List<QueryHistoryEntry> convertToQueryHistory(List<Msg> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }

        return messages.stream()
                .filter(msg -> msg.getRole() != null)
                .filter(msg -> msg.getRole() == MsgRole.USER || msg.getRole() == MsgRole.ASSISTANT)
                .map(
                        msg -> {
                            String role = msg.getRole() == MsgRole.USER ? "user" : "assistant";
                            String content = extractTextContent(msg);
                            return new QueryHistoryEntry(role, content);
                        })
                .filter(entry -> entry.getContent() != null && !entry.getContent().isEmpty())
                .collect(Collectors.toList());
    }

    /**
     * 从 Msg 对象提取文本内容。
     *
     * <p>连接消息内容中的所有文本块，忽略图像、工具使用等非文本类型。
     *
     * @param msg 待提取文本的消息
     * @return 连接后的文本；无文本内容时为空字符串
     */
    private String extractTextContent(Msg msg) {
        if (msg.getContent() == null || msg.getContent().isEmpty()) {
            return "";
        }

        StringBuilder text = new StringBuilder();
        for (ContentBlock block : msg.getContent()) {
            if (block instanceof TextBlock textBlock) {
                if (text.length() > 0) {
                    text.append("\n");
                }
                text.append(textBlock.getText());
            }
        }
        return text.toString();
    }

    /**
     * 获取知识库索引 ID。
     *
     * @return 索引 ID
     */
    public String getIndexId() {
        return indexId;
    }

    /**
     * 获取百炼 API 客户端。
     *
     * @return 客户端
     */
    public BailianClient getClient() {
        return client;
    }

    /**
     * 为 BailianKnowledge 创建新构建器。
     *
     * @return 新的 Builder 实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * BailianKnowledge 的构建器。
     */
    public static class Builder {
        private BailianConfig config;
        private BailianClient client;
        private String indexId;

        private Builder() {}

        /**
         * 设置百炼配置。
         *
         * <p>设置后会根据配置自动创建 BailianClient。
         * 不能同时设置 config 与 client，必须选择一种方式。
         *
         * @param config 百炼配置
         * @return 此构建器，支持链式调用
         */
        public Builder config(BailianConfig config) {
            this.config = config;
            return this;
        }

        /**
         * 直接设置百炼客户端。
         *
         * <p>多个 BailianKnowledge 实例需要共享客户端时使用。
         * 不能同时设置 config 与 client。
         *
         * @param client 百炼客户端
         * @return 此构建器，支持链式调用
         */
        public Builder client(BailianClient client) {
            this.client = client;
            return this;
        }

        /**
         * 设置知识库索引 ID。
         *
         * <p>同时提供时，此值覆盖 config 中的 indexId。
         *
         * @param indexId 知识库索引 ID
         * @return 此构建器，支持链式调用
         */
        public Builder indexId(String indexId) {
            this.indexId = indexId;
            return this;
        }

        /**
         * 构建新的 BailianKnowledge 实例。
         *
         * @return 新的 BailianKnowledge 实例
         * @throws IllegalArgumentException 缺少必填参数时抛出
         * @throws IllegalStateException 同时设置 config 与 client 时抛出
         */
        public BailianKnowledge build() {
            // 验证 config 与 client 仅设置其中一个
            if (config != null && client != null) {
                throw new IllegalStateException(
                        "Cannot set both config and client. Choose one approach.");
            }

            // 按需根据配置创建客户端
            BailianClient finalClient = client;
            if (finalClient == null) {
                if (config == null) {
                    throw new IllegalArgumentException("Either config or client must be provided");
                }
                try {
                    finalClient = new BailianClient(config);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to create BailianClient from config", e);
                }
            }

            // 确定最终 indexId
            String finalIndexId = indexId;
            if (finalIndexId == null && config != null) {
                finalIndexId = config.getIndexId();
            }

            if (finalIndexId == null || finalIndexId.trim().isEmpty()) {
                throw new IllegalArgumentException(
                        "IndexId must be provided either via config or builder");
            }

            return new BailianKnowledge(finalClient, config, finalIndexId);
        }
    }
}
