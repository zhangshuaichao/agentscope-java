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
package io.agentscope.core.message;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.HashMap;
import java.util.Map;

/**
 * 表示消息中的推理或思考内容。
 *
 * <p>此内容块用于捕获智能体采取行动前的内部推理过程，呈现其如何做出决策或选择工具。
 *
 * <p>特别适用于 ReAct 智能体和其他推理密集型系统，有助于调试和分析智能体的思考过程。
 *
 * <p>可选的 metadata 字段可保存额外推理信息，例如 OpenRouter 的 reasoning_details（reasoning.text、reasoning.encrypted、reasoning.summary）；将消息格式化回 API 时需要保留并恢复这些信息。
 */
public final class ThinkingBlock extends ContentBlock {

    /**
     * 用于存储 OpenRouter/Gemini 推理详情列表的元数据键。
     */
    public static final String METADATA_REASONING_DETAILS = "reasoningDetails";

    private final String thinking;
    private final Map<String, Object> metadata;

    /**
     * 创建用于 JSON 反序列化的思考块。
     *
     * @param text 思考内容（null 会转换为空字符串）
     * @param metadata 用于存储额外推理信息的可选元数据
     */
    @JsonCreator
    private ThinkingBlock(
            @JsonProperty("thinking") String text,
            @JsonProperty("metadata") Map<String, Object> metadata) {
        this.thinking = text != null ? text : "";
        this.metadata = metadata != null ? new HashMap<>(metadata) : null;
    }

    /**
     * 获取此块的思考或推理内容。
     *
     * @return 思考内容
     */
    public String getThinking() {
        return thinking;
    }

    /**
     * 获取此思考块关联的元数据。
     *
     * <p>元数据可包含额外推理信息，例如：
     *
     * <ul>
     * <li>{@link #METADATA_REASONING_DETAILS}：来自 OpenRouter/Gemini 的 OpenAIReasoningDetail 对象列表
     * </ul>
     *
     * @return 元数据映射；未设置时为 null
     */
    public Map<String, Object> getMetadata() {
        return metadata;
    }

    /**
     * 创建用于构造 ThinkingBlock 实例的构建器。
     *
     * @return 新的构建器实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 用于构造 ThinkingBlock 实例的构建器。
     */
    public static class Builder {

        private String thinking;
        private Map<String, Object> metadata;

        /**
         * 设置内容块的思考内容。
         *
         * @param thinking 思考内容
         * @return 当前构建器，支持链式调用
         */
        public Builder thinking(String thinking) {
            this.thinking = thinking;
            return this;
        }

        /**
         * 设置内容块的元数据。
         *
         * <p>元数据可存储需要保留的额外推理信息，例如 OpenRouter 的 reasoning_details。
         *
         * @param metadata 元数据映射
         * @return 当前构建器，支持链式调用
         */
        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }

        /**
         * 使用配置的思考内容和元数据构建 ThinkingBlock。
         *
         * @return 新的 ThinkingBlock 实例（null 思考内容会转换为空字符串）
         */
        public ThinkingBlock build() {
            return new ThinkingBlock(thinking != null ? thinking : "", metadata);
        }
    }
}
