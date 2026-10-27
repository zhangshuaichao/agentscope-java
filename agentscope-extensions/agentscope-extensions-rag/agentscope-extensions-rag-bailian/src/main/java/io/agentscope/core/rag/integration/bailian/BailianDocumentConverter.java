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

import com.aliyun.bailian20231229.models.RetrieveResponseBody;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 将百炼 API 响应转换为 AgentScope Document 对象的转换器。
 *
 * <p>负责百炼检索响应格式与 AgentScope 内部 Document 表示之间的转换，
 * 使其集成到 RAG 系统。
 */
public class BailianDocumentConverter {

    private BailianDocumentConverter() {
        // 工具类，禁止实例化
    }

    /**
     * 将百炼检索响应转换为 Document 列表。
     *
     * @param response 百炼检索响应
     * @return Document 对象列表
     */
    public static List<Document> fromBailianResponse(RetrieveResponseBody response) {
        if (response == null || response.getData() == null) {
            return new ArrayList<>();
        }

        RetrieveResponseBody.RetrieveResponseBodyData data = response.getData();
        if (data.getNodes() == null || data.getNodes().isEmpty()) {
            return new ArrayList<>();
        }

        List<Document> documents = new ArrayList<>();
        for (RetrieveResponseBody.RetrieveResponseBodyDataNodes node : data.getNodes()) {
            Document doc = fromBailianNode(node);
            if (doc != null) {
                documents.add(doc);
            }
        }

        return documents;
    }

    /**
     * 将单个百炼节点转换为 Document。
     *
     * @param node 检索响应中的百炼节点
     * @return Document 对象；转换失败时为 null
     */
    public static Document fromBailianNode(
            RetrieveResponseBody.RetrieveResponseBodyDataNodes node) {
        if (node == null) {
            return null;
        }

        try {
            // 提取文本内容
            String text = node.getText();
            if (text == null || text.trim().isEmpty()) {
                return null;
            }

            // 提取元数据（安全地将 Object 转为 Map）
            Map<String, Object> nodeMetadata = new HashMap<>();
            Object metadataObj = node.getMetadata();
            if (metadataObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> metadataMap = (Map<String, Object>) metadataObj;
                nodeMetadata.putAll(metadataMap);
            }

            // 从元数据提取关键字段
            String docId = extractStringFromMetadata(nodeMetadata, "doc_id");
            if (docId == null || docId.isEmpty()) {
                docId = "unknown";
            }

            // 提取分块 ID：百炼使用字符串 "_id" 字段
            String chunkId = extractStringFromMetadata(nodeMetadata, "_id");

            // 根据节点元数据构建载荷（排除保留字段）
            Map<String, Object> payload = new HashMap<>();
            if (!nodeMetadata.isEmpty()) {
                for (Map.Entry<String, Object> entry : nodeMetadata.entrySet()) {
                    String key = entry.getKey();
                    // 跳过已用于核心元数据的保留字段
                    if (!"doc_id".equals(key) && !"_id".equals(key)) {
                        payload.put(key, entry.getValue());
                    }
                }
            }

            // 使用载荷构建 DocumentMetadata
            TextBlock content = TextBlock.builder().text(text).build();
            DocumentMetadata metadata = new DocumentMetadata(content, docId, chunkId, payload);

            // 创建 Document
            Document document = new Document(metadata);

            // 设置相似度分数
            if (node.getScore() != null) {
                document.setScore(node.getScore());
            }

            return document;
        } catch (Exception e) {
            // 记录错误并返回 null，以跳过此文档
            return null;
        }
    }

    /**
     * 从元数据映射安全提取字符串值。
     *
     * @param metadata 元数据映射
     * @param key 待提取的键
     * @return 字符串值；未找到或不是字符串时为 null
     */
    private static String extractStringFromMetadata(Map<String, Object> metadata, String key) {
        if (metadata == null || key == null) {
            return null;
        }

        Object value = metadata.get(key);
        if (value instanceof String) {
            return (String) value;
        }

        return value != null ? value.toString() : null;
    }
}
