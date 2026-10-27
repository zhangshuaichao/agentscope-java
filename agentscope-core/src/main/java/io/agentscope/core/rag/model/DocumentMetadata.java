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

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 包含内容和分块信息的文档元数据。
 *
 * <p>此类存储文档分块的元数据，包括内容（可以是文本、图像、视频等）、
 * 文档 ID、分块 ID，以及可选的自定义载荷字段。
 *
 * <p>content 字段使用 {@link ContentBlock}，它是支持不同内容类型的密封层次结构
 * （TextBlock、ImageBlock、VideoBlock 等）。
 *
 * <p>payload 字段允许存储文件名、部门、作者、创建时间、标签等自定义元数据
 * 及其他业务专用字段。这些字段以键值对形式存储，
 * 并与文档内容一同持久化到向量数据库。
 *
 * <p>带有载荷的使用示例：
 * <pre>{@code
 * Map<String, Object> payload = new HashMap<>();
 * payload.put("filename", "report.pdf");
 * payload.put("department", "Finance");
 * payload.put("author", "John Doe");
 * payload.put("created_at", "2024-01-15T10:30:00Z");
 * payload.put("tags", Arrays.asList("urgent", "quarterly"));
 *
 * TextBlock content = TextBlock.builder().text("Document content").build();
 * DocumentMetadata metadata = new DocumentMetadata(content, "doc-123", "chunk-0", payload);
 * }</pre>
 *
 * @deprecated 自 2.0.0 起弃用。rag 包已移除；请在应用层集成检索。
 */
@Deprecated(forRemoval = true, since = "2.0.0")
public class DocumentMetadata {

    private final ContentBlock content;
    private final String docId;
    private final String chunkId;
    private final Map<String, Object> payload;

    /**
     * 创建不含自定义载荷的新 DocumentMetadata 实例。
     *
     * <p>此构造器用于向后兼容。对于新代码，如果需要添加自定义元数据字段，
     * 可考虑使用带 payload 参数的构造器或构建器模式。
     *
     * @param content 内容块（文本、图像、视频等）
     * @param docId 文档 ID
     * @param chunkId 文档内的分块 ID
     */
    public DocumentMetadata(ContentBlock content, String docId, String chunkId) {
        this(content, docId, chunkId, null);
    }

    /**
     * 创建包含自定义载荷的新 DocumentMetadata 实例。
     *
     * <p>复制 payload 映射，以防外部修改。{@link #getPayload()} 返回的载荷是不可修改视图。
     *
     * @param content 内容块（文本、图像、视频等）
     * @param docId 文档 ID
     * @param chunkId 文档内的分块 ID
     * @param payload 自定义元数据字段（可为 null 或空映射）
     * @throws IllegalArgumentException 当 content、docId 或 chunkId 为 null 时抛出
     */
    public DocumentMetadata(
            ContentBlock content, String docId, String chunkId, Map<String, Object> payload) {
        if (content == null) {
            throw new IllegalArgumentException("Content cannot be null");
        }
        if (docId == null) {
            throw new IllegalArgumentException("Document ID cannot be null");
        }
        if (chunkId == null) {
            throw new IllegalArgumentException("Chunk ID cannot be null");
        }
        this.content = content;
        this.docId = docId;
        this.chunkId = chunkId;
        // 创建不可修改的副本，以防外部修改
        this.payload =
                payload != null && !payload.isEmpty()
                        ? Collections.unmodifiableMap(new HashMap<>(payload))
                        : Collections.emptyMap();
    }

    /**
     * 获取内容块。
     *
     * @return 内容块
     */
    public ContentBlock getContent() {
        return content;
    }

    /**
     * 获取文档 ID。
     *
     * @return 文档 ID
     */
    public String getDocId() {
        return docId;
    }

    /**
     * 获取分块 ID。
     *
     * @return 分块 ID
     */
    public String getChunkId() {
        return chunkId;
    }

    /**
     * 获取自定义载荷元数据。
     *
     * <p>返回自定义元数据字段的不可修改映射，其中包含文件名、部门、作者、标签等
     * 业务专用字段。映射绝不为 null，但未提供载荷时可能为空。
     *
     * @return 自定义元数据字段的不可修改映射（绝不为 null）
     */
    public Map<String, Object> getPayload() {
        return payload;
    }

    /**
     * 按键获取指定载荷值。
     *
     * <p>这是获取单个载荷值的便捷方法，无须访问整个载荷映射。
     *
     * @param key 载荷键
     * @return 载荷值；键不存在时为 null
     * @throws NullPointerException 当 key 为 null 时抛出
     */
    public Object getPayloadValue(String key) {
        if (key == null) {
            throw new NullPointerException("Payload key cannot be null");
        }
        return payload.get(key);
    }

    /**
     * 检查载荷是否包含指定键。
     *
     * <p>尝试获取载荷字段值之前，可用此方法安全检查该字段是否存在。
     *
     * @param key 要检查的载荷键
     * @return 载荷中存在该键时为 true，否则为 false
     * @throws NullPointerException 当 key 为 null 时抛出
     */
    public boolean hasPayloadKey(String key) {
        if (key == null) {
            throw new NullPointerException("Payload key cannot be null");
        }
        return payload.containsKey(key);
    }

    /**
     * 从内容块获取文本内容。
     *
     * <p>这是从 ContentBlock 提取文本的便捷方法。对于 TextBlock 返回文本；
     * 对于其他块类型，返回其字符串表示。
     *
     * @return 文本内容；不可用时为空字符串
     */
    public String getContentText() {
        if (content instanceof TextBlock textBlock) {
            return textBlock.getText();
        }
        return content != null ? content.toString() : "";
    }

    /**
     * 创建用于构造 DocumentMetadata 实例的新构建器。
     *
     * <p>构建器模式提供流式 API，用于创建带有可选载荷字段的 DocumentMetadata。
     * 需要添加多个自定义元数据字段时，这种方式尤其方便。
     *
     * <p>使用示例：
     * <pre>{@code
     * DocumentMetadata metadata = DocumentMetadata.builder()
     *     .content(TextBlock.builder().text("Content").build())
     *     .docId("doc-123")
     *     .chunkId("chunk-0")
     *     .addPayload("filename", "report.pdf")
     *     .addPayload("department", "Finance")
     *     .build();
     * }</pre>
     *
     * @return 新的构建器实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 通过流式 API 构造 DocumentMetadata 实例的构建器。
     *
     * <p>此构建器提供创建 DocumentMetadata 对象的便捷方式，尤其适合处理自定义载荷字段。
     * 所有字段都可独立设置，调用 {@link #build()} 时会验证必填字段。
     */
    public static class Builder {
        private ContentBlock content;
        private String docId;
        private String chunkId;
        private Map<String, Object> payload;

        private Builder() {}

        /**
         * 设置内容块。
         *
         * @param content 内容块（文本、图像、视频等）
         * @return 当前构建器，以支持方法链式调用
         */
        public Builder content(ContentBlock content) {
            this.content = content;
            return this;
        }

        /**
         * 设置文档 ID。
         *
         * @param docId 文档 ID
         * @return 当前构建器，以支持方法链式调用
         */
        public Builder docId(String docId) {
            this.docId = docId;
            return this;
        }

        /**
         * 设置分块 ID。
         *
         * @param chunkId 文档内的分块 ID
         * @return 当前构建器，以支持方法链式调用
         */
        public Builder chunkId(String chunkId) {
            this.chunkId = chunkId;
            return this;
        }

        /**
         * 设置完整的载荷映射。
         *
         * <p>这会替换此前设置的载荷。映射会被复制，以防外部修改。
         *
         * @param payload 自定义元数据字段（可为 null）
         * @return 当前构建器，以支持方法链式调用
         */
        public Builder payload(Map<String, Object> payload) {
            this.payload = payload != null ? new HashMap<>(payload) : null;
            return this;
        }

        /**
         * 添加单个载荷字段。
         *
         * <p>此方法允许逐个添加载荷字段；载荷映射尚不存在时，会自动创建。
         *
         * @param key 载荷键
         * @param value 载荷值
         * @return 当前构建器，以支持方法链式调用
         * @throws NullPointerException 当 key 为 null 时抛出
         */
        public Builder addPayload(String key, Object value) {
            if (key == null) {
                throw new NullPointerException("Payload key cannot be null");
            }
            if (this.payload == null) {
                this.payload = new HashMap<>();
            }
            this.payload.put(key, value);
            return this;
        }

        /**
         * 根据已配置的值构建新的 DocumentMetadata 实例。
         *
         * @return 新的 DocumentMetadata 实例
         * @throws IllegalArgumentException 必填字段（content、docId、chunkId）为 null 时抛出
         */
        public DocumentMetadata build() {
            return new DocumentMetadata(content, docId, chunkId, payload);
        }
    }
}
