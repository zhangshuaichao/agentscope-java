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
package io.agentscope.core.embedding;

import io.agentscope.core.message.ContentBlock;
import reactor.core.publisher.Mono;

/**
 * 支持多模态内容的嵌入模型接口。
 *
 * <p>提供从内容块生成向量嵌入的统一 API。
 *
 * <p>具体实现决定支持哪些内容类型：
 * <ul>
 * <li>纯文本模型（如 DashScopeTextEmbedding）支持 {@link io.agentscope.core.message.TextBlock}。</li>
 * <li>多模态模型（如 DashScopeMultiModalEmbedding）支持 {@link io.agentscope.core.message.TextBlock} 和 {@link io.agentscope.core.message.ImageBlock}。</li>
 * </ul>
 *
 * <p>RAG（检索增强生成）系统使用嵌入模型将内容转成向量，用于语义搜索和相似度匹配。
 */
public interface EmbeddingModel {

    /**
     * 为单个内容块生成嵌入响应。
     *
     * <p>实现应检查内容块类型，不支持时抛出 {@link EmbeddingException}。
     *
     * @param block 待嵌入内容块，如 TextBlock、ImageBlock
     * @return 发出包含向量与使用统计的 {@link EmbeddingResponse} 的 Mono
     * @throws EmbeddingException 当嵌入生成失败或内容块类型不受支持时
     */
    Mono<EmbeddingResponse> embed(ContentBlock block);

    /**
     * 获取用于日志与标识的模型名称。
     *
     * @return 模型名称
     */
    String getModelName();

    /**
     * 获取模型生成向量的维度。未指定时默认 1024。
     *
     * @return 嵌入向量维度
     */
    int getDimensions();
}
