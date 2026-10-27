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
package io.agentscope.core.model;

import io.agentscope.core.message.ContentBlock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 表示语言模型的聊天补全响应。
 *
 * <p>此不可变数据类包含模型处理聊天请求后返回的内容、用量信息及可选元数据。
 */
public class ChatResponse {

    private final String id;
    private final List<ContentBlock> content;
    private final ChatUsage usage;
    private final Map<String, Object> metadata;
    private final String finishReason;

    /**
     * 创建新的 ChatResponse 实例。
     *
     * @param id 此响应的唯一标识符
     * @param content 承载响应内容的内容块列表
     * @param usage 此响应的词元用量信息
     * @param metadata 模型服务商提供的附加元数据
     * @param finishReason 生成结束的原因
     */
    public ChatResponse(
            String id,
            List<ContentBlock> content,
            ChatUsage usage,
            Map<String, Object> metadata,
            String finishReason) {
        this.id = id;
        this.content = content;
        this.usage = usage;
        this.metadata = metadata;
        this.finishReason = finishReason;
    }

    /**
     * 获取此响应的唯一标识符。
     *
     * @return 响应标识符
     */
    public String getId() {
        return id;
    }

    /**
     * 获取承载响应内容的内容块。
     *
     * @return 内容块列表
     */
    public List<ContentBlock> getContent() {
        return content;
    }

    /**
     * 获取此响应的词元用量信息。
     *
     * @return 用量信息
     */
    public ChatUsage getUsage() {
        return usage;
    }

    /**
     * 获取模型服务商提供的元数据。
     *
     * @return 元数据映射
     */
    public Map<String, Object> getMetadata() {
        return metadata;
    }

    /**
     * 获取模型停止生成词元的原因。
     *
     * @return 结束原因
     */
    public String getFinishReason() {
        return finishReason;
    }

    /**
     * 使用指定 ID 创建新的 ChatResponse，并从当前实例复制其他所有字段。
     *
     * @param newId 新标识符
     * @return 新的 ChatResponse 实例
     */
    public ChatResponse withId(String newId) {
        return new ChatResponse(newId, this.content, this.usage, this.metadata, this.finishReason);
    }

    /**
     * 创建新的 ChatResponse 建造者。
     *
     * @return 新的 Builder 实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /** 创建 ChatResponse 实例的建造者。 */
    public static class Builder {
        private String id;
        private List<ContentBlock> content;
        private ChatUsage usage;
        private Map<String, Object> metadata;
        private String finishReason;

        /**
         * 设置响应标识符。
         *
         * @param id 此响应的唯一标识符
         * @return 当前建造者实例
         */
        public Builder id(String id) {
            this.id = id;
            return this;
        }

        /**
         * 设置响应的内容块。
         *
         * @param content 承载响应内容的内容块列表
         * @return 当前建造者实例
         */
        public Builder content(List<ContentBlock> content) {
            this.content = content;
            return this;
        }

        /**
         * 设置响应的用量信息。
         *
         * @param usage 词元用量信息
         * @return 当前建造者实例
         */
        public Builder usage(ChatUsage usage) {
            this.usage = usage;
            return this;
        }

        /**
         * 设置响应的元数据。
         *
         * @param metadata 模型服务商提供的附加元数据
         * @return 当前建造者实例
         */
        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }

        /**
         * 设置响应的结束原因。
         *
         * @param finishReason 模型服务商发送的结束原因
         * @return 当前建造者实例
         */
        public Builder finishReason(String finishReason) {
            this.finishReason = finishReason;
            return this;
        }

        /**
         * 使用已设置的值构建新的 ChatResponse 实例。
         * <p>
         * 若未设置 id（或为空字符串／空白字符串），则自动生成随机 UUID，
         * 兼容 Ollama 等不会在 API 响应中返回 id 字段的模型服务商。
         *
         * @return 新的 ChatResponse 实例
         */
        public ChatResponse build() {
            // 未设置 id 或 id 为空／空白时自动生成（适用于 Ollama 等服务商）
            String responseId = this.id;
            if (responseId == null || responseId.trim().isEmpty()) {
                responseId = UUID.randomUUID().toString();
            }
            return new ChatResponse(responseId, content, usage, metadata, finishReason);
        }
    }
}
