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
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.agentscope.core.tool.ToolSuspendException;
import java.beans.Transient;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 表示工具执行结果。
 *
 * 此类有两种用途：
 * 1. 作为工具方法返回值（id 和 name 为 null）
 * 2. 作为消息中的 ContentBlock（必须提供 id 和 name）
 *
 * 支持通过元数据传递额外执行信息。
 */
public final class ToolResultBlock extends ContentBlock {

    /**
     * 表示结果已挂起、等待外部执行的元数据键。
     *
     * <p>这是 {@link ToolResultState#SUSPENDED} 引入前的旧传输编码。为兼容不同版本，{@link #isSuspended()} 仍会识别它。新代码应使用 {@link ToolResultState#SUSPENDED} 或 {@link #isSuspended()}，而非此键。
     */
    @Deprecated public static final String METADATA_SUSPENDED = "agentscope_suspended";

    /**
     * 工具提供挂起原因时，携带该原因（{@code ToolSuspendException} 消息）的元数据键。此信息属于控制平面，不会通过 {@code output} 渲染。
     */
    public static final String METADATA_SUSPEND_REASON = "agentscope_suspend_reason";

    /**
     * 标记结果由提供商服务端工具生成的布尔元数据键。此类结果（例如 Anthropic 的 web_search_tool_result）由提供商在助手消息中返回，而不是由本地工具执行生成。
     */
    public static final String METADATA_SERVER_TOOL = ToolUseBlock.METADATA_SERVER_TOOL;

    private final String id;
    private final String name;
    private final List<ContentBlock> output;
    private final Map<String, Object> metadata;
    private final ToolResultState state;
    private final ToolExecutionDetails executionDetails;

    @JsonCreator
    public ToolResultBlock(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("output") List<ContentBlock> output,
            @JsonProperty("metadata") Map<String, Object> metadata,
            @JsonProperty("state") ToolResultState state,
            @JsonProperty("executionDetails") ToolExecutionDetails executionDetails) {
        this.id = id;
        this.name = name;
        this.output = output != null ? List.copyOf(output) : List.of();
        this.metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
        this.state = state != null ? state : ToolResultState.RUNNING;
        this.executionDetails = executionDetails;
    }

    public ToolResultBlock(
            String id,
            String name,
            List<ContentBlock> output,
            Map<String, Object> metadata,
            ToolResultState state) {
        this(id, name, output, metadata, state, null);
    }

    public ToolExecutionDetails getExecutionDetails() {
        return executionDetails;
    }

    public ToolResultBlock withExecutionDetails(ToolExecutionDetails details) {
        return new ToolResultBlock(id, name, output, metadata, state, details);
    }

    public ToolResultBlock(
            String id, String name, List<ContentBlock> output, Map<String, Object> metadata) {
        this(id, name, output, metadata, null);
    }

    /**
     * 创建以单个内容块为输出的工具结果块。
     *
     * @param id 工具调用 ID
     * @param name 工具名称
     * @param output 作为输出的单个内容块
     */
    public ToolResultBlock(String id, String name, ContentBlock output) {
        this(id, name, List.of(output), null, null);
    }

    /**
     * 创建以内容块列表为输出的工具结果块。
     *
     * @param id 工具调用 ID
     * @param name 工具名称
     * @param output 作为输出的内容块列表
     */
    public ToolResultBlock(String id, String name, List<ContentBlock> output) {
        this(id, name, output, null, null);
    }

    /**
     * 获取工具调用 ID。
     *
     * @return 工具调用 ID；未设置时为 null
     */
    public String getId() {
        return id;
    }

    /**
     * 获取工具名称。
     *
     * @return 工具名称；未设置时为 null
     */
    public String getName() {
        return name;
    }

    /**
     * 获取输出内容块。
     *
     * @return 表示工具输出的不可变内容块列表
     */
    public List<ContentBlock> getOutput() {
        return output;
    }

    /**
     * 获取工具结果关联的元数据。
     *
     * @return 不可变元数据映射；未设置时为空映射
     */
    public Map<String, Object> getMetadata() {
        return metadata;
    }

    /**
     * 获取工具结果状态。
     *
     * @return 工具结果状态
     */
    public ToolResultState getState() {
        return state;
    }

    /**
     * 返回使用给定状态的内容块副本。
     *
     * @param state 新状态
     * @return 更新了状态的新 ToolResultBlock
     */
    public ToolResultBlock withState(ToolResultState state) {
        return new ToolResultBlock(
                this.id, this.name, this.output, this.metadata, state, executionDetails);
    }

    /**
     * 检查此结果是否已挂起、等待外部执行。
     *
     * <p>同时识别标准编码（{@link ToolResultState#SUSPENDED}）和旧传输标记（{@link #METADATA_SUSPENDED}），使旧版本序列化的内容块继续有效。
     *
     * @return 已挂起时为 true，否则为 false
     */
    @Transient
    @JsonInclude
    public boolean isSuspended() {
        return state == ToolResultState.SUSPENDED
                || Boolean.TRUE.equals(metadata.get(METADATA_SUSPENDED));
    }

    /**
     * 检查结果是否由提供商服务端工具生成。
     *
     * @return 结果来自服务端工具执行时为 true
     */
    @JsonIgnore
    public boolean isServerTool() {
        return Boolean.TRUE.equals(metadata.get(METADATA_SERVER_TOOL));
    }

    /**
     * 根据 ToolSuspendException 创建挂起的工具结果。
     *
     * <p>结果不携带输出：工具尚未执行，真实结果将在恢复时由调用方提供。状态为 {@link ToolResultState#SUSPENDED}；若异常提供了原因，则通过 {@link #METADATA_SUSPEND_REASON} 元数据传递。
     *
     * @param toolUse 触发异常的工具使用块
     * @param exception 工具抛出的异常
     * @return 挂起的 ToolResultBlock
     */
    public static ToolResultBlock suspended(ToolUseBlock toolUse, ToolSuspendException exception) {
        Map<String, Object> metadata = new HashMap<>();
        // 在过渡期内保留此标记：旧读取方仍通过这个旧标记检测挂起，
        // 当前代码则读取上方的 SUSPENDED 状态。
        metadata.put(METADATA_SUSPENDED, true);
        if (exception.getReason() != null) {
            metadata.put(METADATA_SUSPEND_REASON, exception.getReason());
        }
        return new ToolResultBlock(
                toolUse.getId(),
                toolUse.getName(),
                List.of(),
                Map.copyOf(metadata),
                ToolResultState.SUSPENDED);
    }

    /**
     * 使用默认消息创建挂起的工具结果。
     *
     * @param toolUse 需要外部执行的工具使用块
     * @return 挂起的 ToolResultBlock
     */
    public static ToolResultBlock suspended(ToolUseBlock toolUse) {
        return suspended(toolUse, new ToolSuspendException());
    }

    /**
     * 结果已挂起且工具提供原因时，获取挂起原因。
     *
     * @return 挂起原因；不存在或结果未挂起时为 null
     */
    @Transient
    @JsonIgnore
    public String getSuspendReason() {
        if (!isSuspended()) {
            return null;
        }
        Object reason = metadata.get(METADATA_SUSPEND_REASON);
        return reason instanceof String s ? s : null;
    }

    /**
     * 创建简单文本结果（用于工具方法返回值）。
     *
     * @param text 文本内容
     * @return 带文本输出的 ToolResultBlock
     */
    public static ToolResultBlock text(String text) {
        return new ToolResultBlock(
                null, null, List.of(TextBlock.builder().text(text).build()), null);
    }

    /**
     * 创建带文本输出的成功结果（用于工具方法返回值）。
     *
     * <p>与 {@link #text(String)} 不同，此方法显式设置 {@link ToolResultState#SUCCESS}。未设置的状态会被构造器转换为 {@link ToolResultState#RUNNING}，智能体运行时将其视为“状态未知”，并退回到检查输出文本错误前缀的方式。因此，成功输出为外部内容（文件内容、HTTP 响应体、Shell 输出）的工具应优先使用此工厂方法，避免仅仅看起来像错误消息的内容被误判为调用失败。
     *
     * @param text 文本内容
     * @return 带文本输出且状态为 SUCCESS 的 ToolResultBlock
     */
    public static ToolResultBlock success(String text) {
        return new ToolResultBlock(
                null,
                null,
                List.of(TextBlock.builder().text(text).build()),
                null,
                ToolResultState.SUCCESS);
    }

    /**
     * 创建错误结果（用于工具方法返回值）。
     *
     * @param errorMessage 错误消息
     * @return 带错误输出的 ToolResultBlock
     */
    public static ToolResultBlock error(String errorMessage) {
        return new ToolResultBlock(
                null,
                null,
                List.of(TextBlock.builder().text("Error: " + errorMessage).build()),
                null,
                ToolResultState.ERROR);
    }

    /**
     * 为工具调用创建错误结果。
     *
     * <p>智能体运行时使用 {@code [ERROR]} 前缀，在生成的工具结果中保留错误标记。
     *
     * @param toolId 工具调用 ID
     * @param errorMessage 错误消息
     * @return 用于消息的错误 ToolResultBlock
     */
    public static ToolResultBlock error(String toolId, String errorMessage) {
        return new ToolResultBlock(
                toolId,
                null,
                List.of(TextBlock.builder().text("[ERROR] " + errorMessage).build()),
                null,
                ToolResultState.ERROR);
    }

    /**
     * 创建仅包含输出的结果（用于工具方法返回值）。
     *
     * @param output 输出内容块
     * @return 带给定输出的 ToolResultBlock
     */
    public static ToolResultBlock of(ContentBlock output) {
        return new ToolResultBlock(null, null, List.of(output), null);
    }

    /**
     * 创建仅包含输出列表的结果（用于工具方法返回值）。
     *
     * @param output 内容块列表
     * @return 带给定输出的 ToolResultBlock
     */
    public static ToolResultBlock of(List<ContentBlock> output) {
        return new ToolResultBlock(null, null, output, null);
    }

    /**
     * 创建包含输出和元数据的结果（用于工具方法返回值）。
     *
     * @param output 输出内容块
     * @param metadata 元数据映射
     * @return 带输出和元数据的 ToolResultBlock
     */
    public static ToolResultBlock of(ContentBlock output, Map<String, Object> metadata) {
        return new ToolResultBlock(null, null, List.of(output), metadata);
    }

    /**
     * 创建包含输出列表和元数据的结果（用于工具方法返回值）。
     *
     * @param output 内容块列表
     * @param metadata 元数据映射
     * @return 带输出和元数据的 ToolResultBlock
     */
    public static ToolResultBlock of(List<ContentBlock> output, Map<String, Object> metadata) {
        return new ToolResultBlock(null, null, output, metadata);
    }

    /**
     * 创建包含 id、name 和输出的结果（用作消息 ContentBlock）。
     *
     * @param id 工具调用 ID
     * @param name 工具名称
     * @param output 输出内容块
     * @return 用于消息的 ToolResultBlock
     */
    public static ToolResultBlock of(String id, String name, ContentBlock output) {
        return new ToolResultBlock(id, name, List.of(output), null);
    }

    /**
     * 创建包含 id、name 和输出列表的结果（用作消息 ContentBlock）。
     *
     * @param id 工具调用 ID
     * @param name 工具名称
     * @param output 内容块列表
     * @return 用于消息的 ToolResultBlock
     */
    public static ToolResultBlock of(String id, String name, List<ContentBlock> output) {
        return new ToolResultBlock(id, name, output, null);
    }

    /**
     * 创建包含全部字段的结果（用作带元数据的消息 ContentBlock）。
     *
     * @param id 工具调用 ID
     * @param name 工具名称
     * @param output 输出内容块
     * @param metadata 元数据映射
     * @return 包含全部字段的 ToolResultBlock
     */
    public static ToolResultBlock of(
            String id, String name, ContentBlock output, Map<String, Object> metadata) {
        return new ToolResultBlock(id, name, List.of(output), metadata);
    }

    /**
     * 创建包含输出列表在内的全部字段的结果（用作带元数据的消息 ContentBlock）。
     *
     * @param id 工具调用 ID
     * @param name 工具名称
     * @param output 内容块列表
     * @param metadata 元数据映射
     * @return 包含全部字段的 ToolResultBlock
     */
    public static ToolResultBlock of(
            String id, String name, List<ContentBlock> output, Map<String, Object> metadata) {
        return new ToolResultBlock(id, name, output, metadata);
    }

    /**
     * 通过设置 id 和 name 创建用于消息的 ToolResultBlock。
     *
     * @param id 工具调用 ID
     * @param name 工具名称
     * @return 设置了 id 和 name 的新 ToolResultBlock
     */
    public ToolResultBlock withIdAndName(String id, String name) {
        return new ToolResultBlock(
                id, name, this.output, this.metadata, this.state, executionDetails);
    }

    /**
     * 创建用于构造 ToolResultBlock 实例的构建器。
     *
     * @return 新的构建器实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 用于构造 ToolResultBlock 实例的构建器。
     */
    public static class Builder {
        private String id;
        private String name;
        private List<ContentBlock> output;
        private Map<String, Object> metadata;
        private ToolResultState state;
        private ToolExecutionDetails executionDetails;

        public Builder executionDetails(ToolExecutionDetails details) {
            this.executionDetails = details;
            return this;
        }

        /**
         * 设置工具调用 ID。
         *
         * @param id 工具调用 ID
         * @return 当前构建器，支持链式调用
         */
        public Builder id(String id) {
            this.id = id;
            return this;
        }

        /**
         * 设置工具名称。
         *
         * @param name 工具名称
         * @return 当前构建器，支持链式调用
         */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * 设置单个内容块作为输出。
         *
         * @param output 单个内容块
         * @return 当前构建器，支持链式调用
         */
        public Builder output(ContentBlock output) {
            this.output = List.of(output);
            return this;
        }

        /**
         * 设置内容块列表作为输出。
         *
         * @param output 内容块列表
         * @return 当前构建器，支持链式调用
         */
        public Builder output(List<ContentBlock> output) {
            this.output = output;
            return this;
        }

        /**
         * 设置工具结果的元数据。
         *
         * @param metadata 元数据映射
         * @return 当前构建器，支持链式调用
         */
        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }

        /**
         * 设置工具结果状态。
         *
         * @param state 工具结果状态
         * @return 当前构建器，支持链式调用
         */
        public Builder state(ToolResultState state) {
            this.state = state;
            return this;
        }

        /**
         * 使用配置的属性构建 ToolResultBlock。
         *
         * @return 新的 ToolResultBlock 实例
         */
        public ToolResultBlock build() {
            return new ToolResultBlock(id, name, output, metadata, state, executionDetails);
        }
    }
}
