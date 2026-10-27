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

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.integration.dify.model.DifyResponse;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 将 Dify API 响应转换为 AgentScope Document 的转换器。
 *
 * <p>在 Dify 检索响应格式与 AgentScope 内部 Document 表示间转换，以集成 RAG 系统。
 *
 * <p>Dify API 响应格式：
 * <pre>{@code
 * {
 *   "query": { "content": "..." },
 *   "records": [
 *     {
 *       "segment": {
 *         "id": "...",
 *         "document_id": "...",
 *         "content": "...",
 *         "document": { "id": "...", "name": "..." }
 *       },
 *       "score": 0.95
 *     }
 *   ]
 * }
 * }</pre>
 *
 * <p>顶层 {@code query} 字段在 Dify Cloud 上为字符串（{@code "query": "..."}），
 * 自托管 Dify 上为对象，参见 {@link io.agentscope.core.rag.integration.dify.model.DifyQueryDeserializer}。
 *
 * <p>用法示例：
 * <pre>{@code
 * DifyResponse response = difyClient.retrieve(...).block();
 * List<Document> documents = DifyDocumentConverter.fromDifyResponse(response);
 * }</pre>
 */
public class DifyDocumentConverter {

    private static final Logger log = LoggerFactory.getLogger(DifyDocumentConverter.class);

    private DifyDocumentConverter() {
        // 工具类，禁止实例化。
    }

    /**
     * 将 Dify API 响应转换为 Document 列表。
     *
     * <p>提取响应记录并逐条转换为 AgentScope Document；响应为 null 或空时返回空列表。
     *
     * @param response Dify API 响应
     * @return Document 列表，绝不为 null
     */
    public static List<Document> fromDifyResponse(DifyResponse response) {
        if (response == null) {
            log.debug("Dify response is null, returning empty list");
            return new ArrayList<>();
        }

        List<DifyResponse.Record> records = response.getRecords();
        if (records == null || records.isEmpty()) {
            log.debug("Dify response contains no records, returning empty list");
            return new ArrayList<>();
        }

        List<Document> documents = new ArrayList<>();
        for (DifyResponse.Record record : records) {
            try {
                Document doc = fromDifyRecord(record);
                if (doc != null) {
                    documents.add(doc);
                }
            } catch (Exception e) {
                log.warn("Failed to convert Dify record to document: {}", e.getMessage());
            }
        }

        log.debug("Converted {} Dify records to documents", documents.size());
        return documents;
    }

    /**
     * 将单条 Dify 记录转换为 Document。
     *
     * <p>提取分段内容、分数与元数据并构建 AgentScope Document；记录无效或缺少必要字段时返回 null。
     *
     * @param record API 响应中的 Dify 记录
     * @return Document；转换失败时为 null
     */
    public static Document fromDifyRecord(DifyResponse.Record record) {
        if (record == null) {
            log.debug("Dify record is null");
            return null;
        }

        DifyResponse.Segment segment = record.getSegment();
        if (segment == null) {
            log.debug("Dify record has no segment");
            return null;
        }

        // 提取并验证内容。
        String content = segment.getContent();
        if (content == null || content.trim().isEmpty()) {
            log.debug("Dify segment has empty content, skipping");
            return null;
        }

        // 提取文档 ID，优先 document_id，回退到分段 ID。
        String docId = segment.getDocumentId();
        if (docId == null || docId.trim().isEmpty()) {
            docId = segment.getId();
        }
        if (docId == null || docId.trim().isEmpty()) {
            docId = "unknown";
            log.debug("Dify segment has no document ID, using 'unknown'");
        }

        // 从嵌套 document 对象提取文档名称。
        String docName = null;
        if (segment.getDocument() != null) {
            docName = segment.getDocument().getName();
        }

        // 使用分段 ID 作为分块 ID。
        String chunkId = segment.getId();
        if (chunkId == null || chunkId.isEmpty()) {
            chunkId = "0";
        }

        // 根据分段元数据构建载荷。
        Map<String, Object> payload = buildPayloadFromSegment(segment);

        // 用载荷构建 DocumentMetadata。
        TextBlock textBlock = TextBlock.builder().text(content).build();
        DocumentMetadata docMetadata = new DocumentMetadata(textBlock, docId, chunkId, payload);

        // 创建 Document。
        Document document = new Document(docMetadata);

        // 从记录层级设置相似性分数。
        Double score = record.getScore();
        if (score != null) {
            document.setScore(score);
        } else {
            log.debug("Dify record has no score");
        }

        log.debug(
                "Converted Dify record to document: id={}, docName={}, score={}, contentLength={}",
                docId,
                docName,
                score,
                content.length());

        return document;
    }

    /**
     * 根据 Dify 分段元数据构建载荷映射。
     *
     * <p>从 Segment 提取相关字段并转换为载荷。百炼直接以 Map 提供元数据，
     * 而 Dify 的元数据散布在 Segment 多个字段中，因此需要手动收集。
     *
     * <p>所选元数据包括：
     * <ul><li>文档信息：document_id、document_name、data_source_type。</li>
     * <li>分段信息：segment_id、position、word_count、tokens。</li>
     * <li>索引信息：index_node_id、index_node_hash。</li>
     * <li>状态信息：status、enabled、hit_count。</li>
     * <li>时间戳：created_at、indexing_at、completed_at。</li><li>关键词列表。</li></ul>
     *
     * @param segment 含元数据字段的 Dify 分段
     * @return 元数据键值映射，绝不为 null
     */
    private static Map<String, Object> buildPayloadFromSegment(DifyResponse.Segment segment) {
        Map<String, Object> payload = new HashMap<>();

        // 文档信息：保留嵌套结构。
        if (segment.getDocument() != null) {
            Map<String, Object> documentInfo = new HashMap<>();
            DifyResponse.DocumentInfo doc = segment.getDocument();

            if (doc.getId() != null) {
                documentInfo.put("id", doc.getId());
            }
            if (doc.getName() != null) {
                documentInfo.put("name", doc.getName());
            }
            if (doc.getDataSourceType() != null) {
                documentInfo.put("data_source_type", doc.getDataSourceType());
            }

            if (!documentInfo.isEmpty()) {
                payload.put("document", documentInfo);
            }
        }

        // 分段信息：跳过已使用的 id、document_id 和 content。
        if (segment.getPosition() != null) {
            payload.put("position", segment.getPosition());
        }
        if (segment.getWordCount() != null) {
            payload.put("word_count", segment.getWordCount());
        }
        if (segment.getTokens() != null) {
            payload.put("tokens", segment.getTokens());
        }
        if (segment.getAnswer() != null && !segment.getAnswer().isEmpty()) {
            payload.put("answer", segment.getAnswer());
        }

        // 索引信息。
        if (segment.getIndexNodeId() != null) {
            payload.put("index_node_id", segment.getIndexNodeId());
        }
        if (segment.getIndexNodeHash() != null) {
            payload.put("index_node_hash", segment.getIndexNodeHash());
        }

        // 状态信息。
        if (segment.getStatus() != null) {
            payload.put("status", segment.getStatus());
        }
        if (segment.getEnabled() != null) {
            payload.put("enabled", segment.getEnabled());
        }
        if (segment.getDisabledAt() != null) {
            payload.put("disabled_at", segment.getDisabledAt());
        }
        if (segment.getDisabledBy() != null) {
            payload.put("disabled_by", segment.getDisabledBy());
        }
        if (segment.getHitCount() != null) {
            payload.put("hit_count", segment.getHitCount());
        }

        // 时间戳信息，保留响应的原始时间戳数值。
        if (segment.getCreatedAt() != null) {
            payload.put("created_at", segment.getCreatedAt());
        }
        if (segment.getCreatedBy() != null) {
            payload.put("created_by", segment.getCreatedBy());
        }
        if (segment.getIndexingAt() != null) {
            payload.put("indexing_at", segment.getIndexingAt());
        }
        if (segment.getCompletedAt() != null) {
            payload.put("completed_at", segment.getCompletedAt());
        }
        if (segment.getStoppedAt() != null) {
            payload.put("stopped_at", segment.getStoppedAt());
        }

        // 关键词。
        if (segment.getKeywords() != null && !segment.getKeywords().isEmpty()) {
            payload.put("keywords", segment.getKeywords());
        }

        // 错误信息（若有）。
        if (segment.getError() != null && !segment.getError().isEmpty()) {
            payload.put("error", segment.getError());
        }

        return payload;
    }
}
