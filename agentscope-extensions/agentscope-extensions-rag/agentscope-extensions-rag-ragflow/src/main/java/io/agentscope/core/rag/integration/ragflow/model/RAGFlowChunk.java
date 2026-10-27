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
package io.agentscope.core.rag.integration.ragflow.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * RAGFlow 分块（文档片段）模型。
 *
 * <p>表示 RAGFlow 检索 API 返回的分块（文本片段）。
 *
 * <p><b>实际 API 响应格式：</b>
 *
 * <pre>{@code
 * {
 *   "id": "d78435d142bd5cf6704da62c778795c5",
 *   "content": "ragflow content",
 *   "content_ltks": "ragflow content",
 *   "document_id": "5c5999ec7be811ef9cab0242ac120005",
 *   "document_keyword": "1.txt",
 *   "highlight": "<em>ragflow</em> content",
 *   "kb_id": "c7ee74067a2c11efb21c0242ac120006",
 *   "similarity": 0.9669436601210759,
 *   "vector_similarity": 0.8898122004035864,
 *   "term_similarity": 1.0,
 *   "positions": [[0, 100], [200, 300]],
 *   "image_id": "",
 *   "important_keywords": ["ragflow", "content"]
 * }
 * }</pre>
 *
 * <p><b>注意：</b>{@code @JsonIgnoreProperties(ignoreUnknown = true)}
 * 保证对 API 变化的向前兼容。
 *
 * @author RAGFlow Integration Team
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RAGFlowChunk {

    // 核心字段
    @JsonProperty("id")
    private String id;

    @JsonProperty("content")
    private String content;

    @JsonProperty("content_ltks")
    private String contentLtks;

    @JsonProperty("highlight")
    private String highlight;

    // 文档信息
    @JsonProperty("document_id")
    private String documentId;

    @JsonProperty("document_keyword")
    private String documentKeyword;

    @JsonProperty("kb_id")
    private String kbId;

    // 相似度分数
    @JsonProperty("similarity")
    private Double similarity;

    @JsonProperty("vector_similarity")
    private Double vectorSimilarity;

    @JsonProperty("term_similarity")
    private Double termSimilarity;

    // 附加字段
    @JsonProperty("positions")
    private List<List<Integer>> positions;

    @JsonProperty("image_id")
    private String imageId;

    @JsonProperty("important_keywords")
    private List<String> importantKeywords;

    // 自定义元数据（若有）
    @JsonProperty("metadata")
    private Map<String, Object> metadata;

    // 为兼容性保留的旧字段
    @JsonProperty("score")
    private Double score;

    @JsonProperty("dataset_id")
    private String datasetId;

    @JsonProperty("document_name")
    private String documentName;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getContentLtks() {
        return contentLtks;
    }

    public void setContentLtks(String contentLtks) {
        this.contentLtks = contentLtks;
    }

    public String getHighlight() {
        return highlight;
    }

    public void setHighlight(String highlight) {
        this.highlight = highlight;
    }

    public String getDocumentId() {
        return documentId;
    }

    public void setDocumentId(String documentId) {
        this.documentId = documentId;
    }

    public String getDocumentKeyword() {
        return documentKeyword;
    }

    public void setDocumentKeyword(String documentKeyword) {
        this.documentKeyword = documentKeyword;
    }

    public String getKbId() {
        return kbId;
    }

    public void setKbId(String kbId) {
        this.kbId = kbId;
    }

    public Double getSimilarity() {
        return similarity;
    }

    public void setSimilarity(Double similarity) {
        this.similarity = similarity;
    }

    public Double getVectorSimilarity() {
        return vectorSimilarity;
    }

    public void setVectorSimilarity(Double vectorSimilarity) {
        this.vectorSimilarity = vectorSimilarity;
    }

    public Double getTermSimilarity() {
        return termSimilarity;
    }

    public void setTermSimilarity(Double termSimilarity) {
        this.termSimilarity = termSimilarity;
    }

    public List<List<Integer>> getPositions() {
        return positions;
    }

    public void setPositions(List<List<Integer>> positions) {
        this.positions = positions;
    }

    public String getImageId() {
        return imageId;
    }

    public void setImageId(String imageId) {
        this.imageId = imageId;
    }

    public List<String> getImportantKeywords() {
        return importantKeywords;
    }

    public void setImportantKeywords(List<String> importantKeywords) {
        this.importantKeywords = importantKeywords;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    // 旧版兼容字段
    public String getDatasetId() {
        // 存在 kb_id 时使用它，否则回退到 datasetId
        return kbId != null ? kbId : datasetId;
    }

    public void setDatasetId(String datasetId) {
        this.datasetId = datasetId;
    }

    public String getDocumentName() {
        // 存在 document_keyword 时使用它，否则回退到 documentName
        return documentKeyword != null ? documentKeyword : documentName;
    }

    public void setDocumentName(String documentName) {
        this.documentName = documentName;
    }

    /**
     * 获取相似度分数。
     *
     * <p>RAGFlow API 使用 {@code similarity} 作为主要分数字段。
     * 此方法兼容两种字段名称。
     *
     * @return 相似度分数（0.0–1.0）；不可用时为 null
     */
    public Double getScore() {
        // RAGFlow 使用 similarity 作为主要分数
        return similarity != null ? similarity : score;
    }

    public void setScore(Double score) {
        this.score = score;
    }
}
