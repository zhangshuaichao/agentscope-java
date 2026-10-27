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
package io.agentscope.core.rag.model;

import io.agentscope.core.util.JsonUtils;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 表示 RAG 系统中文档分块的文档类。
 *
 * <p>这是 RAG 操作的核心数据结构。每份文档包含元数据、可选的嵌入向量
 * 以及可选的相似度分数。
 *
 * <p>根据文档元数据（doc_id、chunk_id 和 content）自动生成确定性 UUID 作为文档 ID，
 * 确保相同内容在不同运行中获得一致的 ID。
 *
 * @deprecated 自 2.0.0 起弃用。rag 包已移除；请在应用层集成检索。
 */
@Deprecated(forRemoval = true, since = "2.0.0")
public class Document {

    private final String id;
    private final DocumentMetadata metadata;
    private double[] embedding;
    private Double score;
    private String vectorName;

    /**
     * 创建新的 Document 实例。
     *
     * <p>根据元数据（doc_id、chunk_id 和 content）自动生成确定性 UUID 作为文档 ID。
     *
     * @param metadata 文档元数据
     */
    public Document(DocumentMetadata metadata) {
        if (metadata == null) {
            throw new IllegalArgumentException("Metadata cannot be null");
        }
        this.metadata = metadata;
        this.id = generateDocumentId(metadata);
    }

    /**
     * 获取文档 ID。
     *
     * @return 文档 ID（UUID 字符串）
     */
    public String getId() {
        return id;
    }

    /**
     * 获取文档元数据。
     *
     * @return 文档元数据
     */
    public DocumentMetadata getMetadata() {
        return metadata;
    }

    /**
     * 获取嵌入向量。
     *
     * @return 嵌入向量；未设置时为 null
     */
    public double[] getEmbedding() {
        return embedding;
    }

    /**
     * 设置嵌入向量。
     *
     * @param embedding 嵌入向量
     */
    public void setEmbedding(double[] embedding) {
        this.embedding = embedding;
    }

    /**
     * 获取相似度分数。
     *
     * @return 相似度分数；未设置时为 null
     */
    public Double getScore() {
        return score;
    }

    /**
     * 设置相似度分数。
     *
     * @param score 相似度分数
     */
    public void setScore(Double score) {
        this.score = score;
    }

    /**
     * 获取文档元数据中的自定义载荷。
     *
     * <p>这是委派给元数据 getPayload() 的便捷方法。载荷包含文件名、部门、
     * 作者、标签等业务专用字段，以及其他自定义元数据。
     *
     * @return 自定义元数据字段的不可修改映射（绝不为 null）
     */
    public Map<String, Object> getPayload() {
        return metadata.getPayload();
    }

    /**
     * 按键获取指定载荷值。
     *
     * <p>这是委派给元数据 getPayloadValue() 的便捷方法。
     * 使用它可获取单个载荷值，无须访问整个映射。
     *
     * @param key 载荷键
     * @return 载荷值；键不存在时为 null
     * @throws NullPointerException 当 key 为 null 时抛出
     */
    public Object getPayloadValue(String key) {
        return metadata.getPayloadValue(key);
    }

    /**
     * 按键获取指定载荷值，并转换为指定类型。
     *
     * <p>当载荷包含复杂对象（例如自定义 POJO），且存储时被序列化为 Map 时，
     * 此方法很有用。它使用 Jackson 的 ObjectMapper，将 Map 转回原始类型。
     *
     * @param <T> 目标类型
     * @param key 载荷键
     * @param targetClass 要转换到的目标类
     * @return 转换为指定类型的载荷值；键不存在时为 null
     * @throws IllegalArgumentException 值无法转换为目标类型时抛出
     * @throws NullPointerException 当 key 或 clazz 为 null 时抛出
     */
    public <T> T getPayloadValueAs(String key, Class<T> targetClass) {
        Object value = getPayloadValue(key);
        if (value == null) {
            return null;
        }
        try {
            return JsonUtils.getJsonCodec().convertValue(value, targetClass);
        } catch (IllegalArgumentException e) {
            throw new RuntimeException(
                    String.format(
                            "Failed to convert payload value for key '%s' to type %s",
                            key, targetClass.getName()),
                    e);
        }
    }

    /**
     * 检查文档载荷是否包含指定键。
     *
     * <p>这是委派给元数据 hasPayloadKey() 的便捷方法。
     * 获取载荷字段之前，可用它安全检查该字段是否存在。
     *
     * @param key 要检查的载荷键
     * @return 载荷中存在该键时为 true，否则为 false
     * @throws NullPointerException 当 key 为 null 时抛出
     */
    public boolean hasPayloadKey(String key) {
        return metadata.hasPayloadKey(key);
    }

    /**
     * 获取文档向量名称。
     *
     * @return 文档名称；未设置时为 null
     */
    public String getVectorName() {
        return vectorName;
    }

    /**
     * 设置文档向量名称。
     *
     * @param vectorName 文档名称
     */
    public void setVectorName(String vectorName) {
        this.vectorName = vectorName;
    }

    /**
     * 根据元数据生成确定性的文档 ID。
     *
     * <p>此方法根据文档关键字段（doc_id、chunk_id、content）的 JSON 表示，
     * 创建 UUID v3（基于名称，使用 MD5）。这确保相同文档内容始终生成相同 ID。
     *
     * @param metadata 文档元数据
     * @return 确定性的 UUID 字符串
     */
    private static String generateDocumentId(DocumentMetadata metadata) {
        // 创建包含 doc_id、chunk_id 和 content 的映射
        Map<String, Object> keyMap = new LinkedHashMap<>();
        keyMap.put("doc_id", metadata.getDocId());
        keyMap.put("chunk_id", metadata.getChunkId());
        keyMap.put("content", metadata.getContent());

        // 使用默认 UTF-8 编码序列化为 JSON
        String jsonKey = JsonUtils.getJsonCodec().toJson(keyMap);

        // 根据 JSON 字符串生成 UUID v3（基于名称，使用 MD5）
        return UUID.nameUUIDFromBytes(jsonKey.getBytes(StandardCharsets.UTF_8)).toString();
    }

    @Override
    public String toString() {
        return String.format(
                "Document(id=%s, name=%s, score=%s, content=%s)",
                id,
                vectorName != null ? vectorName : "null",
                score != null ? String.format("%.3f", score) : "null",
                metadata.getContentText());
    }
}
