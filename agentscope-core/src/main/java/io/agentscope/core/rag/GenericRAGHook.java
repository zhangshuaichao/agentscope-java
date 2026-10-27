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

import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.RetrieveConfig;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * 在推理前自动检索知识的通用 RAG 钩子。
 *
 * <p>此钩子实现 Generic RAG 模式：每一步推理之前自动检索知识并注入提示词。
 * 与由智能体决定检索时机的 Agentic 模式不同，Generic 模式始终针对用户查询
 * 检索相关知识。
 *
 * <p>此钩子拦截 {@link PreReasoningEvent}，执行以下步骤：
 * <ol>
 *   <li>从用户消息提取查询</li>
 *   <li>从知识库检索相关文档</li>
 *   <li>将检索到的知识作为用户消息注入</li>
 *   <li>修改输入消息，将知识上下文包含在内</li>
 * </ol>
 *
 * <p>使用示例：
 * <pre>{@code
 * KnowledgeBase knowledgeBase = new SimpleKnowledge(embeddingModel, vectorStore);
 * GenericRAGHook ragHook = new GenericRAGHook(knowledgeBase);
 *
 * ReActAgent agent = ReActAgent.builder()
 *     .name("Assistant")
 *     .model(chatModel)
 *     .hook(ragHook)
 *     .build();
 * }</pre>
 *
 * <p>配置选项：
 * <ul>
 *   <li>{@code defaultConfig} - 检索配置（数量上限、分数阈值）</li>
 * </ul>
 *
 * @deprecated 自 2.0.0 起弃用。rag 包已移除；请在应用层集成检索。
 */
@Deprecated(forRemoval = true, since = "2.0.0")
public class GenericRAGHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(GenericRAGHook.class);

    private final Knowledge knowledge;
    private final RetrieveConfig defaultConfig;

    /**
     * 使用默认配置创建 GenericRAGHook。
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
    public GenericRAGHook(Knowledge knowledge) {
        this(knowledge, RetrieveConfig.builder().build());
    }

    /**
     * 使用自定义配置创建 GenericRAGHook。
     *
     * @param knowledge 用于检索的知识库
     * @param defaultConfig 默认检索配置
     * @throws IllegalArgumentException 当 knowledgeBase 为 null 时抛出
     */
    public GenericRAGHook(Knowledge knowledge, RetrieveConfig defaultConfig) {
        if (knowledge == null) {
            throw new IllegalArgumentException("Knowledge base cannot be null");
        }
        if (defaultConfig == null) {
            throw new IllegalArgumentException("Default config cannot be null");
        }
        this.knowledge = knowledge;
        this.defaultConfig = defaultConfig;
    }

    // 【学习提示】此兼容实现实际拦截 PreCallEvent，而非类注释中沿用的
    // PreReasoningEvent。阅读旧接口时，以 instanceof 分支和方法参数类型为准。
    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PreCallEvent preCallEvent) {
            @SuppressWarnings("unchecked")
            Mono<T> result = (Mono<T>) handlePreCall(preCallEvent);
            return result;
        }
        return Mono.just(event);
    }

    @Override
    public int priority() {
        // 使用较高优先级，以便在钩子链中尽早执行
        return 50;
    }

    /**
     * 通过检索知识并增强消息来处理 PreCallEvent。
     *
     * @param event PreCallEvent 事件
     * @return 包含可能已修改事件的 Mono
     */
    private Mono<PreCallEvent> handlePreCall(PreCallEvent event) {
        List<Msg> inputMessages = event.getInputMessages();
        if (inputMessages == null || inputMessages.isEmpty()) {
            return Mono.just(event);
        }

        // 从消息中提取查询文本（从后向前查找最后一条用户消息）
        String query = extractQueryFromMessages(inputMessages);
        if (query == null || query.trim().isEmpty()) {
            return Mono.just(event);
        }

        // 【学习提示】检索只是取得外部知识，并不训练或修改模型参数。
        // 这里把检索结果追加为输入消息，后续模型调用才能使用这些内容。
        // 检索相关文档
        return knowledge
                .retrieve(query, defaultConfig)
                .flatMap(
                        retrievedDocs -> {
                            if (retrievedDocs == null || retrievedDocs.isEmpty()) {
                                return Mono.just(event);
                            }
                            List<Msg> enhancedMessages = new ArrayList<>();
                            // 构建包含知识上下文的增强消息
                            Msg enhancedMessage = createEnhancedMessages(retrievedDocs);
                            enhancedMessages.addAll(inputMessages);
                            enhancedMessages.add(enhancedMessage);
                            event.setInputMessages(enhancedMessages);
                            return Mono.just(event);
                        })
                .onErrorResume(
                        error -> {
                            // 记录错误，但不中断流程
                            log.warn("Generic RAG retrieval failed: {}", error.getMessage(), error);
                            return Mono.just(event);
                        });
    }

    /**
     * 从消息列表中提取查询文本。
     *
     * <p>以最后一条用户消息作为查询来源，而不是简单使用最后一条消息，
     * 因为 ReAct 循环中的最后一条可能属于 ASSISTANT 或 TOOL。
     *
     * @param messages 消息列表
     * @return 提取的查询文本；未找到用户消息时为空字符串
     */
    private String extractQueryFromMessages(List<Msg> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }

        // 查找最后一条用户消息（不是简单使用最后一条消息，因为后者可能是
        // ReAct 循环中的 ASSISTANT 或 TOOL）
        for (int i = messages.size() - 1; i >= 0; i--) {
            Msg msg = messages.get(i);
            if (msg.getRole() == MsgRole.USER) {
                return msg.getTextContent();
            }
        }
        return "";
    }

    /** 创建保留检索文本和多模态内容的用户消息。 */
    private Msg createEnhancedMessages(List<Document> retrievedDocs) {
        List<ContentBlock> content = new ArrayList<>();
        content.add(
                TextBlock.builder()
                        .text(
                                "<retrieved_knowledge>Use the following content from the knowledge"
                                        + " base(s) if it is helpful:\n\n")
                        .build());
        for (Document doc : retrievedDocs) {
            content.add(
                    TextBlock.builder()
                            .text(
                                    "- Score: "
                                            + String.format(
                                                    "%.3f",
                                                    doc.getScore() != null ? doc.getScore() : 0.0)
                                            + ", Content: ")
                            .build());
            content.add(doc.getMetadata().getContent());
        }
        content.add(TextBlock.builder().text("\n</retrieved_knowledge>").build());
        return Msg.builder().name("user").role(MsgRole.USER).content(content).build();
    }

    /**
     * 获取此钩子使用的知识库。
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
