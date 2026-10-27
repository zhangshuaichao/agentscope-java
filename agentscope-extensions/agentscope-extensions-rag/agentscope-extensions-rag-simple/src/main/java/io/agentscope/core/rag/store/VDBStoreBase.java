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

import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.store.dto.SearchDocumentDto;
import java.util.List;
import reactor.core.publisher.Mono;

/**
 * 向量数据库存储接口。
 *
 * <p>提供统一向量存储和搜索 API，可基于内存、Qdrant、ChromaDB 等实现。
 *
 * <p>直接保存包含元数据/载荷的 Document，而不只保存向量，以便返回完整文档。
 *
 * <p>主要功能：
 * <ul>
 * <li>高效批量添加文档。</li>
 * <li>支持可选分数阈值的相似度搜索。</li>
 * <li>返回带嵌入与元数据的文档。</li>
 * </ul>
 */
public interface VDBStoreBase {

    /**
     * 向存储添加多条文档。
     *
     * <p>全部文档必须已设置向量，同时持久化向量与元数据/载荷。
     *
     * <p>支持高效批量操作，实现应优化批量插入。
     *
     * @param documents 待存储文档，必须已设置嵌入
     * @return 全部文档存储完成时结束的 Mono
     */
    Mono<Void> add(List<Document> documents);

    /**
     * 搜索相似文档。
     *
     * <p>执行向量相似度搜索，返回最相似文档及其元数据和分数。是否附带向量取决于实现。
     *
     * @param searchDocumentDto 搜索参数 DTO
     * @return 发出按相似度降序排列、已设置分数的 Document 列表的 Mono
     */
    Mono<List<Document>> search(SearchDocumentDto searchDocumentDto);

    /**
     * 从存储删除文档（可选）。
     *
     * <p>默认实现抛出 UnsupportedOperationException，支持删除的实现应重写此方法。
     *
     * @param id 待删除文档 ID（UUID 字符串）
     * @return 删除成功时发出 true，否则发出 false 的 Mono
     */
    Mono<Boolean> delete(String id);
}
