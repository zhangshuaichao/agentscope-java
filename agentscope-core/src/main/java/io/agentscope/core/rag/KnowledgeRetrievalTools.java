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
package io.agentscope.core.rag;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.RetrieveConfig;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.List;
import reactor.core.publisher.Mono;

/**
 * Agentic RAG 模式的知识检索工具。
 *
 * <p>此类提供可注册到智能体的工具方法，实现自主知识检索。
 * 智能体需要信息时，可以调用这些工具搜索知识库。
 *
 * <p>这是 Agentic 模式的实现：由智能体决定何时以及如何从知识库检索知识。
 *
 * <p>使用示例：
 * <pre>{@code
 * KnowledgeBase knowledgeBase = new SimpleKnowledge(embeddingModel, vectorStore);
 * KnowledgeRetrievalTools tools = new KnowledgeRetrievalTools(knowledgeBase, RetrieveConfig.builder().build());
 *
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
 * @deprecated 自 2.0.0 起弃用。rag 包已移除；请在应用层集成检索。
 */
@Deprecated(forRemoval = true, since = "2.0.0")
public class KnowledgeRetrievalTools {

    private final Knowledge knowledge;

    private final RetrieveConfig defaultConfig;

    /**
     * 使用默认配置创建新的 KnowledgeRetrievalTools 实例。
     *
     * <p>默认配置：
     * <ul>
     *   <li>数量上限：5 篇文档</li>
     *   <li>分数阈值：0.5</li>
     * </ul>
     *
     * @param knowledge 用于检索的知识库
     * @throws IllegalArgumentException 当 knowledgeBase 为 null 时抛出
     */
    public KnowledgeRetrievalTools(Knowledge knowledge) {
        this(knowledge, RetrieveConfig.builder().build());
    }

    /**
     * 创建新的 KnowledgeRetrievalTools 实例。
     *
     * @param knowledge     用于检索的知识库
     * @param defaultConfig 默认检索配置
     * @throws IllegalArgumentException 当 knowledgeBase 为 null 时抛出
     */
    public KnowledgeRetrievalTools(Knowledge knowledge, RetrieveConfig defaultConfig) {
        if (knowledge == null) {
            throw new IllegalArgumentException("Knowledge base cannot be null");
        }
        if (defaultConfig == null) {
            throw new IllegalArgumentException("Retrieve config cannot be null");
        }
        this.knowledge = knowledge;
        this.defaultConfig = defaultConfig;
    }

    /**
     * 从知识库检索相关文档。
     *
     * <p>此工具方法允许智能体在知识库中搜索与查询相关的信息。
     * 智能体可以指定要检索的文档数量。
     *
     * <p>如果智能体记忆中存在对话历史，会自动将其纳入检索配置。
     * 支持多轮对话上下文的知识库（例如百炼）可以利用这些历史提高检索准确性。
     *
     * <p>以下情况可使用此工具：
     * <ul>
     *   <li>用户询问已存储知识相关的问题
     *   <li>需要从知识库查找特定信息
     *   <li>希望基于存储文档给出结合上下文的回答
     * </ul>
     *
     * @param query 用于查找相关文档的检索查询
     * @param limit 要检索的最大文档数量（默认：5）
     * @param agent 发起调用的智能体（由框架自动注入）
     * @return 格式化字符串，包含检索文档及其分数
     */
    @Tool(
            name = "retrieve_knowledge",
            description =
                    "Retrieve relevant documents from knowledge base. Use this tool when you need"
                        + " to find specific information or when user asks questions about stored"
                        + " knowledge.")
    public Mono<ToolResultBlock> retrieveKnowledge(
            @ToolParam(
                            name = "query",
                            description =
                                    "The search query to find relevant documents in the knowledge"
                                            + " base")
                    String query,
            @ToolParam(
                            name = "limit",
                            description = "Maximum number of documents to retrieve (default: 5)",
                            required = false)
                    Integer limit,
            Agent agent,
            RuntimeContext ctx) {

        // 【学习提示】与 GenericRAGHook 的自动检索不同，此路径只有工具被调用才检索。
        // 工具注解的 description 会发给模型，属于运行时输入，因此学习版保留英文原值。
        // 设置默认值
        if (limit == null) {
            limit = 5;
        }

        // 若存在调用级状态，则从中提取对话历史
        List<Msg> conversationHistory = null;
        if (agent instanceof ReActAgent) {
            var state = RuntimeContext.resolveAgentState(ctx, agent);
            if (state != null) {
                conversationHistory = state.getContext();
            }
        }

        // 构建包含对话历史的检索配置
        RetrieveConfig config =
                this.defaultConfig
                        .mutate()
                        .limit(limit)
                        .conversationHistory(conversationHistory)
                        .build();

        return knowledge
                .retrieve(query, config)
                .map(result -> ToolResultBlock.success(formatDocumentsForTool(result)))
                .onErrorResume(
                        e ->
                                Mono.just(
                                        ToolResultBlock.error(
                                                "Failed to retrieve knowledge for query: "
                                                        + query)));
    }

    /**
     * 将检索文档格式化为工具返回格式。
     *
     * <p>将文档列表转换为便于阅读的字符串格式，供智能体在推理过程中使用。
     *
     * @param documents 检索到的文档列表
     * @return 格式化的字符串表示
     */
    private String formatDocumentsForTool(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return "No relevant documents found in the knowledge base.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Retrieved ").append(documents.size()).append(" relevant document(s):\n\n");

        for (int i = 0; i < documents.size(); i++) {
            Document doc = documents.get(i);
            sb.append("Document ").append(i + 1);
            if (doc.getScore() != null) {
                sb.append(" (Score: ").append(String.format("%.3f", doc.getScore())).append(")");
            }
            sb.append(":\n");
            sb.append(doc.getMetadata().getContentText()).append("\n\n");
        }

        return sb.toString();
    }

    /**
     * 获取此工具使用的知识库。
     *
     * @return 知识库
     */
    public Knowledge getKnowledgeBase() {
        return knowledge;
    }

    /**
     * 获取默认检索配置。
     *
     * @return 默认配置
     */
    public RetrieveConfig getDefaultConfig() {
        return defaultConfig;
    }
}
