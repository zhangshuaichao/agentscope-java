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

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.integration.haystack.model.HayStackDocument;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 将 HayStack API 响应转换为 AgentScope Document 的转换器。
 *
 * <p>在 HayStack RAG 文档格式与 AgentScope 内部 Document 表示之间转换，以集成 RAG 系统。
 */
public class HayStackDocumentConverter {

    private static final Logger logger = LoggerFactory.getLogger(HayStackDocumentConverter.class);

    private HayStackDocumentConverter() {
        // 工具类，禁止实例化。
    }

    /**
     * 将 HayStackDocument 列表转换为 Document 列表。
     *
     * @param hayStackDocuments 检索响应中的文档列表
     * @return Document 列表
     */
    public static List<Document> convertToDocuments(List<HayStackDocument> hayStackDocuments) {
        if (hayStackDocuments == null || hayStackDocuments.isEmpty()) {
            return new ArrayList<>();
        }

        List<Document> documents =
                hayStackDocuments.stream()
                        .map(HayStackDocumentConverter::convertToDocument)
                        .filter(Objects::nonNull)
                        .peek(
                                doc ->
                                        logger.debug(
                                                "Converted HayStackDocument to Document, id={}",
                                                doc.getId()))
                        .toList();

        logger.debug("Converted {} HayStackDocuments to Documents", hayStackDocuments.size());

        return documents;
    }

    /**
     * 将单个 HayStack 文档块转换为 Document。
     *
     * @param hayStackDocument 检索响应中的文档
     * @return Document；转换失败时为 null
     */
    public static Document convertToDocument(HayStackDocument hayStackDocument) {
        if (hayStackDocument == null) {
            logger.debug("HayStackDocument is null.");
            return null;
        }

        try {
            // 内容转换为 TextBlock。
            String content = hayStackDocument.getContent();
            if (content == null || content.isBlank()) {
                logger.debug(
                        "HayStackDocument content is null or empty, id={}",
                        hayStackDocument.getId());
                return null;
            }

            TextBlock textBlock = TextBlock.builder().text(content).build();

            // docId 与 chunkId 转为 DocumentMetadata。
            Document document = getDocumentWithMetadata(hayStackDocument, textBlock);

            // 嵌入向量。
            if (hayStackDocument.getEmbedding() != null
                    && !hayStackDocument.getEmbedding().isEmpty()) {
                double[] embedding =
                        hayStackDocument.getEmbedding().stream()
                                .mapToDouble(Double::doubleValue)
                                .toArray();
                document.setEmbedding(embedding);
            }

            // 分数。
            if (hayStackDocument.getScore() != null) {
                document.setScore(hayStackDocument.getScore());
            }

            return document;

        } catch (Exception e) {
            logger.warn(
                    "Failed to convert HayStackDocument to Document, id={}",
                    hayStackDocument.getId(),
                    e);
            return null;
        }
    }

    private static @NonNull Document getDocumentWithMetadata(
            HayStackDocument hayStackDocument, TextBlock textBlock) {
        String docId = null;
        String chunkId = null;

        Map<String, Object> meta = hayStackDocument.getMeta();
        if (meta != null) {
            // 使用 file_path 作为 docId。
            Object filePath = meta.get("file_path");
            if (filePath != null) {
                docId = filePath.toString();
            } else {
                // SentenceWindowRetriever 的文件路径保存在 source 键下。
                Object sourceFilePath = meta.get("source");
                if (sourceFilePath != null) {
                    docId = sourceFilePath.toString();
                }
            }
        }

        // 回退策略。
        String id = hayStackDocument.getId();
        if (docId == null || docId.isEmpty()) {
            docId = (id == null || id.isBlank()) ? "unknown" : id;
        }

        chunkId = hayStackDocument.getId() != null ? hayStackDocument.getId() : "0";

        // 由 meta 构建载荷，保留 HayStack 全部元数据。
        Map<String, Object> payload = new HashMap<>();
        if (meta != null && !meta.isEmpty()) {
            payload.putAll(meta);
        }

        // 用载荷构建元数据。
        DocumentMetadata metadata = new DocumentMetadata(textBlock, docId, chunkId, payload);

        // 构建文档。
        return new Document(metadata);
    }
}
