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
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 表示消息中的工具使用请求。
 *
 * <p>智能体请求执行工具时使用此内容块。它包含工具调用的唯一标识符、工具名称、输入参数，以及用于流式工具调用的可选原始内容。
 *
 * <p>工具输入保存为字符串键到对象值的通用映射，便于向不同工具实现灵活传参。
 */
public final class ToolUseBlock extends ContentBlock {

    /**
     * Gemini 思考签名的元数据键（值为 byte[]）。
     */
    public static final String METADATA_THOUGHT_SIGNATURE = "thoughtSignature";

    /**
     * 标记工具调用属于提供商服务端工具的布尔元数据键。服务端工具（例如 Anthropic 内置的 web_search）在提供商基础设施上执行，不得分派给本地工具集；结果在同一助手消息中以带相同标记的 {@link ToolResultBlock} 返回。
     */
    public static final String METADATA_SERVER_TOOL = "serverTool";

    /**
     * 标记流式工具调用累积的原始参数始终未形成有效 JSON 对象的布尔元数据键，常见原因是输出 token 上限导致截断。此类调用不得使用空参数执行。
     */
    public static final String METADATA_MALFORMED_ARGUMENTS = "malformedArguments";

    private final String id;
    private final String name;
    private final Map<String, Object> input;
    private final String content; // 流式工具调用的原始内容
    private final Map<String, Object> metadata; // 提供商特定的元数据
    private final ToolCallState state;

    /**
     * 创建带元数据的工具使用块（便捷构造器）。
     *
     * @param id 工具调用的唯一标识符
     * @param name 要执行的工具名称
     * @param input 工具输入参数（进行防御性复制）
     * @param metadata 提供商特定的元数据（进行防御性复制）
     */
    public ToolUseBlock(
            String id, String name, Map<String, Object> input, Map<String, Object> metadata) {
        this(id, name, input, null, metadata, null);
    }

    /**
     * 创建不带元数据的工具使用块（便捷构造器）。
     *
     * @param id 工具调用的唯一标识符
     * @param name 要执行的工具名称
     * @param input 工具输入参数（进行防御性复制）
     */
    public ToolUseBlock(String id, String name, Map<String, Object> input) {
        this(id, name, input, null, null, null);
    }

    /**
     * 创建包含流式处理原始内容的工具使用块。
     *
     * @param id 工具调用的唯一标识符
     * @param name 要执行的工具名称
     * @param input 工具输入参数（进行防御性复制）
     * @param content 流式工具调用的原始内容
     * @param metadata 提供商特定的元数据（进行防御性复制）
     */
    public ToolUseBlock(
            String id,
            String name,
            Map<String, Object> input,
            String content,
            Map<String, Object> metadata) {
        this(id, name, input, content, metadata, null);
    }

    /**
     * 使用全部字段创建工具使用块。
     *
     * @param id 工具调用的唯一标识符
     * @param name 要执行的工具名称
     * @param input 工具输入参数（进行防御性复制）
     * @param content 流式工具调用的原始内容
     * @param metadata 提供商特定的元数据（进行防御性复制）
     * @param state 工具调用状态，为 null 时默认为 PENDING
     */
    @JsonCreator
    public ToolUseBlock(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("input") Map<String, Object> input,
            @JsonProperty("content") String content,
            @JsonProperty("metadata") Map<String, Object> metadata,
            @JsonProperty("state") ToolCallState state) {
        this.id = id;
        this.name = name;
        // 进行防御性复制，避免外部修改
        this.input =
                input == null
                        ? Collections.emptyMap()
                        : Collections.unmodifiableMap(new HashMap<>(input));
        this.content = content;
        this.metadata =
                metadata == null
                        ? Collections.emptyMap()
                        : Collections.unmodifiableMap(new HashMap<>(metadata));
        this.state = state != null ? state : ToolCallState.PENDING;
    }

    /**
     * 获取工具调用的唯一标识符。
     *
     * @return 工具调用 ID
     */
    public String getId() {
        return id;
    }

    /**
     * 获取要执行的工具名称。
     *
     * @return 工具名称
     */
    public String getName() {
        return name;
    }

    /**
     * 获取工具输入参数。
     *
     * @return 工具输入参数映射
     */
    public Map<String, Object> getInput() {
        return input;
    }

    /**
     * 获取流式工具调用的原始内容。
     *
     * @return 原始内容；未设置时为 null
     */
    public String getContent() {
        return content;
    }

    /**
     * 获取提供商特定的元数据。
     *
     * <p>对于 Gemini，可能在 {@link #METADATA_THOUGHT_SIGNATURE} 键下包含思考签名。
     *
     * @return 元数据映射；未设置时为空映射
     */
    public Map<String, Object> getMetadata() {
        return metadata;
    }

    /**
     * 获取工具调用状态。
     *
     * @return 工具调用状态
     */
    public ToolCallState getState() {
        return state;
    }

    /**
     * 检查工具调用是否由提供商在服务端执行（通过 {@link #METADATA_SERVER_TOOL} 标记）。
     *
     * @return 属于服务端工具调用时为 true
     */
    @JsonIgnore
    public boolean isServerTool() {
        return Boolean.TRUE.equals(metadata.get(METADATA_SERVER_TOOL));
    }

    /**
     * 检查工具调用的流式参数是否始终未形成有效 JSON 对象（通过 {@link #METADATA_MALFORMED_ARGUMENTS} 标记），例如参数传输到一半被截断。
     *
     * @return 累积参数格式错误时为 true
     */
    @JsonIgnore
    public boolean hasMalformedArguments() {
        return Boolean.TRUE.equals(metadata.get(METADATA_MALFORMED_ARGUMENTS));
    }

    /**
     * 返回使用给定状态的内容块副本。
     *
     * @param state 新状态
     * @return 更新了状态的新 ToolUseBlock
     */
    public ToolUseBlock withState(ToolCallState state) {
        return new ToolUseBlock(this.id, this.name, this.input, this.content, this.metadata, state);
    }

    /**
     * 创建用于构造 ToolUseBlock 的构建器。
     *
     * @return 新的构建器实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 用于构造 ToolUseBlock 实例的构建器。
     */
    public static class Builder {
        private String id;
        private String name;
        private Map<String, Object> input;
        private String content;
        private Map<String, Object> metadata;
        private ToolCallState state;

        /**
         * 设置工具调用的唯一标识符。
         *
         * @param id 工具调用 ID
         * @return 当前构建器，支持链式调用
         */
        public Builder id(String id) {
            this.id = id;
            return this;
        }

        /**
         * 设置要执行的工具名称。
         *
         * @param name 工具名称
         * @return 当前构建器，支持链式调用
         */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * 设置工具输入参数。
         *
         * @param input 工具输入参数映射
         * @return 当前构建器，支持链式调用
         */
        public Builder input(Map<String, Object> input) {
            this.input = input;
            return this;
        }

        /**
         * 设置流式工具调用的原始内容。
         *
         * @param content 流式处理的原始内容
         * @return 当前构建器，支持链式调用
         */
        public Builder content(String content) {
            this.content = content;
            return this;
        }

        /**
         * 设置提供商特定的元数据。
         *
         * <p>对于 Gemini，使用 {@link ToolUseBlock#METADATA_THOUGHT_SIGNATURE} 作为键来存储思考签名。
         *
         * @param metadata 元数据映射
         * @return 当前构建器，支持链式调用
         */
        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }

        /**
         * 设置工具调用状态。
         *
         * @param state 工具调用状态
         * @return 当前构建器，支持链式调用
         */
        public Builder state(ToolCallState state) {
            this.state = state;
            return this;
        }

        /**
         * 使用配置的属性构建 ToolUseBlock。
         *
         * @return 新的 ToolUseBlock 实例
         */
        public ToolUseBlock build() {
            return new ToolUseBlock(id, name, input, content, metadata, state);
        }
    }
}
