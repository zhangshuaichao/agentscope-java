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
package io.agentscope.core.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.agentscope.core.agent.ExecutionIdentity;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 所有细粒度智能体事件的基类。
 *
 * <p>每个事件携带唯一 ID、创建时间戳和类型判别标记。
 * 事件在智能体执行期间发出，可通过响应式流消费。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = AgentStartEvent.class, name = "AGENT_START"),
    @JsonSubTypes.Type(value = AgentEndEvent.class, name = "AGENT_END"),
    @JsonSubTypes.Type(value = AgentResultEvent.class, name = "AGENT_RESULT"),
    @JsonSubTypes.Type(value = ModelCallStartEvent.class, name = "MODEL_CALL_START"),
    @JsonSubTypes.Type(value = ModelCallEndEvent.class, name = "MODEL_CALL_END"),
    @JsonSubTypes.Type(value = TextBlockStartEvent.class, name = "TEXT_BLOCK_START"),
    @JsonSubTypes.Type(value = TextBlockDeltaEvent.class, name = "TEXT_BLOCK_DELTA"),
    @JsonSubTypes.Type(value = TextBlockEndEvent.class, name = "TEXT_BLOCK_END"),
    @JsonSubTypes.Type(value = ThinkingBlockStartEvent.class, name = "THINKING_BLOCK_START"),
    @JsonSubTypes.Type(value = ThinkingBlockDeltaEvent.class, name = "THINKING_BLOCK_DELTA"),
    @JsonSubTypes.Type(value = ThinkingBlockEndEvent.class, name = "THINKING_BLOCK_END"),
    @JsonSubTypes.Type(value = DataBlockStartEvent.class, name = "DATA_BLOCK_START"),
    @JsonSubTypes.Type(value = DataBlockDeltaEvent.class, name = "DATA_BLOCK_DELTA"),
    @JsonSubTypes.Type(value = DataBlockEndEvent.class, name = "DATA_BLOCK_END"),
    @JsonSubTypes.Type(value = ToolCallStartEvent.class, name = "TOOL_CALL_START"),
    @JsonSubTypes.Type(value = ToolCallDeltaEvent.class, name = "TOOL_CALL_DELTA"),
    @JsonSubTypes.Type(value = ToolCallEndEvent.class, name = "TOOL_CALL_END"),
    @JsonSubTypes.Type(value = ToolResultStartEvent.class, name = "TOOL_RESULT_START"),
    @JsonSubTypes.Type(value = ToolProgressEvent.class, name = "TOOL_PROGRESS"),
    @JsonSubTypes.Type(value = ToolResultTextDeltaEvent.class, name = "TOOL_RESULT_TEXT_DELTA"),
    @JsonSubTypes.Type(value = ToolResultDataDeltaEvent.class, name = "TOOL_RESULT_DATA_DELTA"),
    @JsonSubTypes.Type(value = ToolResultEndEvent.class, name = "TOOL_RESULT_END"),
    @JsonSubTypes.Type(value = ExceedMaxItersEvent.class, name = "EXCEED_MAX_ITERS"),
    @JsonSubTypes.Type(value = RequireUserConfirmEvent.class, name = "REQUIRE_USER_CONFIRM"),
    @JsonSubTypes.Type(
            value = RequireExternalExecutionEvent.class,
            name = "REQUIRE_EXTERNAL_EXECUTION"),
    @JsonSubTypes.Type(value = UserConfirmResultEvent.class, name = "USER_CONFIRM_RESULT"),
    @JsonSubTypes.Type(
            value = ExternalExecutionResultEvent.class,
            name = "EXTERNAL_EXECUTION_RESULT"),
    @JsonSubTypes.Type(value = RequestStopEvent.class, name = "REQUEST_STOP"),
    @JsonSubTypes.Type(value = SubagentExposedEvent.class, name = "SUBAGENT_EXPOSED"),
    @JsonSubTypes.Type(value = HintBlockEvent.class, name = "HINT_BLOCK"),
    @JsonSubTypes.Type(value = AllToolsDeniedEvent.class, name = "ALL_TOOLS_DENIED"),
    @JsonSubTypes.Type(value = CustomEvent.class, name = "CUSTOM")
})
public abstract class AgentEvent {

    /**
     * {@link #metadata} 的约定键，将转发的子智能体事件与 Harness 的
     * {@code TaskRecord}／远程 Agent Protocol 任务 ID 关联。
     * 与 {@link #source} 不同，后者标识来源智能体路径（{@code parentSession/agentId}）。
     */
    public static final String METADATA_TASK_ID = "taskId";

    /**
     * {@link #metadata} 的约定键，标识发起远程子智能体执行的父智能体会话。
     * 与 {@link #source}（将同一 ID 嵌入为路径前缀）以及
     * {@link #METADATA_TASK_ID}（标识 Harness 任务）不同。
     */
    public static final String METADATA_PARENT_SESSION_ID = "parentSessionId";

    /**
     * {@link #metadata} 的约定键，携带文本投影背后的 {@code GenerateReason} 名称。
     * 当前用于 {@code returnDirect} 结束消息所合成的 {@code TextBlock} 事件，
     * 使观察方能够区分“这是模型说的”与“工具结果替代了模型的最终回答”。
     * 普通模型生成的文本事件不携带此项。
     */
    public static final String METADATA_GENERATE_REASON = "generate_reason";

    private final String id;
    private final String createdAt;
    private String source;

    @JsonProperty("execution")
    private ExecutionIdentity execution;

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private Map<String, Object> metadata;

    protected AgentEvent() {
        this.id = UUID.randomUUID().toString().replace("-", "");
        this.createdAt = Instant.now().toString();
    }

    protected AgentEvent(String id, String createdAt) {
        this.id = id;
        this.createdAt = createdAt;
    }

    /** 与原生历史关联；转发的子智能体事件保留自身身份。 */
    public ExecutionIdentity getExecution() {
        return execution;
    }

    public AgentEvent withExecution(ExecutionIdentity identity) {
        if (execution == null) execution = identity;
        return this;
    }

    public abstract AgentEventType getType();

    public String getId() {
        return id;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    /**
     * 返回标识来源智能体的路径。顶层（父）智能体的事件为 {@code null}；
     * 子智能体转发的事件为斜杠分隔路径（例如 {@code "main/researcher"}）。
     */
    public String getSource() {
        return source;
    }

    /**
     * 设置来源路径并返回此事件。子智能体事件转发机制使用它，在将子事件
     * 注入父事件流之前添加标记。
     */
    public AgentEvent withSource(String source) {
        this.source = source;
        return this;
    }

    /**
     * 返回附加到此事件的可选元数据。可能为 {@code null} 或空集合。
     */
    public Map<String, Object> getMetadata() {
        return metadata;
    }

    /**
     * 向此事件附加任意键值元数据，并返回事件以支持链式调用。
     */
    public AgentEvent withMetadata(Map<String, Object> metadata) {
        this.metadata = metadata != null ? new LinkedHashMap<>(metadata) : null;
        return this;
    }

    /**
     * 将单个元数据条目合并到此事件中（映射不存在时创建），并返回事件以支持链式调用。
     * 传入 {@code null} 值时，移除已存在的对应键。
     */
    public AgentEvent withMetadataEntry(String key, Object value) {
        if (key == null || key.isBlank()) {
            return this;
        }
        Map<String, Object> next = new LinkedHashMap<>();
        if (this.metadata != null) {
            next.putAll(this.metadata);
        }
        if (value == null) {
            next.remove(key);
        } else {
            next.put(key, value);
        }
        this.metadata = next.isEmpty() ? null : next;
        return this;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{id='" + id + "', type=" + getType() + '}';
    }
}
