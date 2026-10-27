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

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.integration.ragflow.model.RAGFlowChunk;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 将 RAGFlow API 响应转换为 AgentScope Document 对象的转换器。
 *
 * <p>处理 RAGFlow 分块格式与 AgentScope 内部 Document 表示之间的转换，
 * 使其集成到 RAG 系统。
 */
public class RAGFlowDocumentConverter {

    private static final Logger logger = LoggerFactory.getLogger(RAGFlowDocumentConverter.class);

    private RAGFlowDocumentConverter() {
        // 工具类，禁止实例化
    }

    /**
     * 将 RAGFlow 分块列表转换为 Document。
     *
     * @param chunks 检索响应中的 RAGFlow 分块
     * @return Document 对象列表
     */
    public static List<Document> convertToDocuments(List<RAGFlowChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return new ArrayList<>();
        }

        List<Document> documents = new ArrayList<>();
        for (RAGFlowChunk chunk : chunks) {
            Document doc = convertToDocument(chunk);
            if (doc != null) {
                documents.add(doc);
            }
        }
        return documents;
    }

    /**
     * 将单个 RAGFlow 分块转换为 Document。
     *
     * @param chunk 检索响应中的 RAGFlow 分块
     * @return Document 对象；转换失败时返回 null
     */
    public static Document convertToDocument(RAGFlowChunk chunk) {
        if (chunk == null) {
            return null;
        }

        try {
            // 提取文本内容
            String text = chunk.getContent();
            if (text == null || text.trim().isEmpty()) {
                return null;
            }

            // 提取文档 ID（回退到分块 ID）
            String docId = chunk.getDocumentId();
            if (docId == null || docId.isEmpty()) {
                docId = chunk.getId() != null ? chunk.getId() : "unknown";
            }

            // 提取分块 ID
            String chunkId = chunk.getId();
            if (chunkId == null || chunkId.isEmpty()) {
                chunkId = "0";
            }

            // 根据分块元数据构建载荷
            Map<String, Object> payload = buildPayloadFromChunk(chunk);

            // 使用载荷构建 DocumentMetadata
            TextBlock content = TextBlock.builder().text(text).build();
            DocumentMetadata metadata = new DocumentMetadata(content, docId, chunkId, payload);

            // 创建 Document
            Document document = new Document(metadata);

            // 设置相似度分数
            if (chunk.getScore() != null) {
                document.setScore(chunk.getScore());
            } else {
                document.setScore(0.0);
            }

            logger.debug(
                    "Converted RAGFlow chunk to document: id={}, docName={}, score={},"
                            + " contentLength={}",
                    docId,
                    payload.getOrDefault("document_name", "unknown"),
                    chunk.getScore(),
                    text.length());

            return document;
        } catch (Exception e) {
            logger.warn("Failed to convert RAGFlow chunk to document: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 根据 RAGFlow 分块元数据构建载荷映射。
     *
     * <p>从 RAGFlow Chunk 对象中提取相关元数据字段，转换为载荷映射，
     * 保留原始结构与命名。
     *
     * <p>主要原则：
     * <ul>
     *   <li>沿用 API 响应的下划线命名</li>
     *   <li>跳过已使用字段（id、document_id、content）</li>
     *   <li>保留其他全部元数据字段</li>
     * </ul>
     *
     * @param chunk 包含元数据字段的 RAGFlow 分块
     * @return 元数据键值映射（绝不为 null）
     */
    private static Map<String, Object> buildPayloadFromChunk(RAGFlowChunk chunk) {
        Map<String, Object> payload = new HashMap<>();

        // 文档信息（跳过 document_id，因为已用作 docId）
        if (chunk.getDocumentKeyword() != null) {
            payload.put("document_keyword", chunk.getDocumentKeyword());
        }
        if (chunk.getKbId() != null) {
            payload.put("kb_id", chunk.getKbId());
        }

        // 分块信息（跳过 id 和 content，因为已使用）
        if (chunk.getContentLtks() != null && !chunk.getContentLtks().isEmpty()) {
            payload.put("content_ltks", chunk.getContentLtks());
        }
        if (chunk.getHighlight() != null && !chunk.getHighlight().isEmpty()) {
            payload.put("highlight", chunk.getHighlight());
        }

        // 相似度分数
        if (chunk.getVectorSimilarity() != null) {
            payload.put("vector_similarity", chunk.getVectorSimilarity());
        }
        if (chunk.getTermSimilarity() != null) {
            payload.put("term_similarity", chunk.getTermSimilarity());
        }

        // 位置信息
        if (chunk.getPositions() != null && !chunk.getPositions().isEmpty()) {
            payload.put("positions", chunk.getPositions());
        }

        // 附加字段
        if (chunk.getImageId() != null && !chunk.getImageId().isEmpty()) {
            payload.put("image_id", chunk.getImageId());
        }
        if (chunk.getImportantKeywords() != null && !chunk.getImportantKeywords().isEmpty()) {
            payload.put("important_keywords", chunk.getImportantKeywords());
        }

        // 自定义元数据（若有），保留为嵌套对象
        if (chunk.getMetadata() != null && !chunk.getMetadata().isEmpty()) {
            payload.put("metadata", chunk.getMetadata());
        }

        if (chunk.getDatasetId() != null) {
            payload.put("dataset_id", chunk.getDatasetId());
        }

        if (chunk.getDocumentName() != null) {
            payload.put("document_name", chunk.getDocumentName());
        }

        return payload;
    }
}
