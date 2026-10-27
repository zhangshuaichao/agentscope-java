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

import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.RetrieveConfig;
import java.util.List;
import reactor.core.publisher.Mono;

/**
 * 知识库接口。
 *
 * <p>【学习提示】这是已弃用的兼容接口，可用于理解检索抽象；当前版本的新应用
 * 应在应用层集成检索，不应据此认为 core.rag 是推荐的新扩展入口。
 *
 * <p>此接口提供在知识库中存储和检索文档的统一 API。
 * 知识库用于 RAG（Retrieval-Augmented Generation，检索增强生成）系统，
 * 为语言模型提供上下文。
 *
 * @deprecated 自 2.0.0 起弃用。rag 包已移除；请在应用层集成检索。
 */
@Deprecated(forRemoval = true, since = "2.0.0")
public interface Knowledge {

    /**
     * 向知识库添加文档。
     *
     * <p>将文档转换为嵌入向量并存入向量数据库，以供后续检索。
     *
     * @param documents 要添加的文档列表
     * @return 所有文档添加完成后结束的 Mono
     */
    Mono<Void> addDocuments(List<Document> documents);

    /**
     * 根据查询检索相关文档。
     *
     * <p>将查询转换为嵌入向量，用于搜索知识库中的相似文档。
     * 根据分数阈值过滤结果，并按配置的数量上限截取。
     *
     * @param query 检索查询文本
     * @param config 检索配置（数量上限、分数阈值等）
     * @return 发出相关 Document 对象列表的 Mono，列表按相关性排序
     */
    Mono<List<Document>> retrieve(String query, RetrieveConfig config);
}
