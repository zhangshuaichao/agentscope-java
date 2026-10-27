/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.agui.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.agui.model.RunAgentInput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 所有 AG-UI 协议事件的密封接口。
 *
 * <p>协议中的所有事件实现此接口，提供事件类型、线程 ID 和运行 ID 等公共属性。密封接口结合 record，使实现更清晰简洁。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonSubTypes({
    @JsonSubTypes.Type(value = AguiEvent.RunStarted.class, name = "RUN_STARTED"),
    @JsonSubTypes.Type(value = AguiEvent.RunFinished.class, name = "RUN_FINISHED"),
    @JsonSubTypes.Type(value = AguiEvent.TextMessageStart.class, name = "TEXT_MESSAGE_START"),
    @JsonSubTypes.Type(value = AguiEvent.TextMessageContent.class, name = "TEXT_MESSAGE_CONTENT"),
    @JsonSubTypes.Type(value = AguiEvent.TextMessageEnd.class, name = "TEXT_MESSAGE_END"),
    @JsonSubTypes.Type(value = AguiEvent.ToolCallStart.class, name = "TOOL_CALL_START"),
    @JsonSubTypes.Type(value = AguiEvent.ToolCallArgs.class, name = "TOOL_CALL_ARGS"),
    @JsonSubTypes.Type(value = AguiEvent.ToolCallEnd.class, name = "TOOL_CALL_END"),
    @JsonSubTypes.Type(value = AguiEvent.ToolCallResult.class, name = "TOOL_CALL_RESULT"),
    @JsonSubTypes.Type(value = AguiEvent.StateSnapshot.class, name = "STATE_SNAPSHOT"),
    @JsonSubTypes.Type(value = AguiEvent.StateDelta.class, name = "STATE_DELTA"),
    @JsonSubTypes.Type(value = AguiEvent.Raw.class, name = "RAW"),
    @JsonSubTypes.Type(value = AguiEvent.Custom.class, name = "CUSTOM"),
    @JsonSubTypes.Type(value = AguiEvent.ReasoningStart.class, name = "REASONING_START"),
    @JsonSubTypes.Type(
            value = AguiEvent.ReasoningMessageStart.class,
            name = "REASONING_MESSAGE_START"),
    @JsonSubTypes.Type(
            value = AguiEvent.ReasoningMessageContent.class,
            name = "REASONING_MESSAGE_CONTENT"),
    @JsonSubTypes.Type(value = AguiEvent.ReasoningMessageEnd.class, name = "REASONING_MESSAGE_END"),
    @JsonSubTypes.Type(
            value = AguiEvent.ReasoningMessageChunk.class,
            name = "REASONING_MESSAGE_CHUNK"),
    @JsonSubTypes.Type(value = AguiEvent.ReasoningEnd.class, name = "REASONING_END"),
    @JsonSubTypes.Type(value = AguiEvent.RunError.class, name = "RUN_ERROR"),
    @JsonSubTypes.Type(value = AguiEvent.StepStarted.class, name = "STEP_STARTED"),
    @JsonSubTypes.Type(value = AguiEvent.StepFinished.class, name = "STEP_FINISHED"),
    @JsonSubTypes.Type(value = AguiEvent.TextMessageChunk.class, name = "TEXT_MESSAGE_CHUNK"),
    @JsonSubTypes.Type(value = AguiEvent.ToolCallChunk.class, name = "TOOL_CALL_CHUNK"),
    @JsonSubTypes.Type(value = AguiEvent.MessagesSnapshot.class, name = "MESSAGES_SNAPSHOT"),
    @JsonSubTypes.Type(value = AguiEvent.ActivitySnapshot.class, name = "ACTIVITY_SNAPSHOT"),
    @JsonSubTypes.Type(value = AguiEvent.ActivityDelta.class, name = "ACTIVITY_DELTA"),
    @JsonSubTypes.Type(
            value = AguiEvent.ReasoningEncryptedValue.class,
            name = "REASONING_ENCRYPTED_VALUE")
})
public sealed interface AguiEvent
        permits AguiEvent.RunStarted,
                AguiEvent.RunFinished,
                AguiEvent.RunError,
                AguiEvent.StepStarted,
                AguiEvent.StepFinished,
                AguiEvent.TextMessageStart,
                AguiEvent.TextMessageContent,
                AguiEvent.TextMessageEnd,
                AguiEvent.TextMessageChunk,
                AguiEvent.ToolCallStart,
                AguiEvent.ToolCallArgs,
                AguiEvent.ToolCallEnd,
                AguiEvent.ToolCallChunk,
                AguiEvent.ToolCallResult,
                AguiEvent.StateSnapshot,
                AguiEvent.StateDelta,
                AguiEvent.MessagesSnapshot,
                AguiEvent.ActivitySnapshot,
                AguiEvent.ActivityDelta,
                AguiEvent.Raw,
                AguiEvent.Custom,
                AguiEvent.ReasoningStart,
                AguiEvent.ReasoningMessageStart,
                AguiEvent.ReasoningMessageContent,
                AguiEvent.ReasoningMessageEnd,
                AguiEvent.ReasoningMessageChunk,
                AguiEvent.ReasoningEnd,
                AguiEvent.ReasoningEncryptedValue {

    /**
     * 获取事件类型。
     *
     * @return 事件类型
     */
    @JsonIgnore
    AguiEventType getType();

    /**
     * 获取关联线程 ID。
     *
     * @return 线程 ID
     */
    String getThreadId();

    /**
     * 获取关联运行 ID。
     *
     * @return 运行 ID
     */
    String getRunId();

    /**
     * 获取表示事件创建时间的可选时间戳。
     *
     * @return 事件时间戳；未提供时为 null
     */
    Long timestamp();

    /**
     * 获取转换前原始事件数据的可选字段。
     *
     * @return 原始源事件；未提供时为 null
     */
    Object rawEvent();

    /**
     * 表示智能体运行开始的事件，是智能体开始处理请求时发送的首个事件。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record RunStarted(
            String threadId,
            String runId,
            String parentRunId,
            RunAgentInput input,
            Long timestamp,
            Object rawEvent)
            implements AguiEvent {

        @JsonCreator
        public RunStarted(
                @JsonProperty("threadId") String threadId,
                @JsonProperty("runId") String runId,
                @JsonProperty("parentRunId") String parentRunId,
                @JsonProperty("input") RunAgentInput input,
                @JsonProperty("timestamp") Long timestamp,
                @JsonProperty("rawEvent") Object rawEvent) {
            this.threadId = Objects.requireNonNull(threadId, "threadId cannot be null");
            this.runId = Objects.requireNonNull(runId, "runId cannot be null");
            this.parentRunId = parentRunId;
            this.input = input;

            this.timestamp = timestamp;
            this.rawEvent = rawEvent;
        }

        public RunStarted(String threadId, String runId, String parentRunId, RunAgentInput input) {
            this(threadId, runId, parentRunId, input, null, null);
        }

        public RunStarted(String threadId, String runId) {
            this(threadId, runId, null, null);
        }

        @Override
        public AguiEventType getType() {
            return AguiEventType.RUN_STARTED;
        }

        @Override
        public String getThreadId() {
            return threadId;
        }

        @Override
        public String getRunId() {
            return runId;
        }
    }

    /**
     * 表示智能体运行完成的事件，是智能体完成请求处理时发送的最后事件。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record RunFinished(
            String threadId,
            String runId,
            Object result,
            RunFinishedOutcome outcome,
            Long timestamp,
            Object rawEvent)
            implements AguiEvent {

        @JsonCreator
        public RunFinished(
                @JsonProperty("threadId") String threadId,
                @JsonProperty("runId") String runId,
                @JsonProperty("result") Object result,
                @JsonProperty("outcome") RunFinishedOutcome outcome,
                @JsonProperty("timestamp") Long timestamp,
                @JsonProperty("rawEvent") Object rawEvent) {
            this.threadId = Objects.requireNonNull(threadId, "threadId cannot be null");
            this.runId = Objects.requireNonNull(runId, "runId cannot be null");
            this.result = result;
            this.outcome = outcome;

            this.timestamp = timestamp;
            this.rawEvent = rawEvent;
        }

        public RunFinished(
                String threadId, String runId, Object result, RunFinishedOutcome outcome) {
            this(threadId, runId, result, outcome, null, null);
        }

        public RunFinished(String threadId, String runId) {
            this(threadId, runId, null, null);
        }

        @Override
        public AguiEventType getType() {
            return AguiEventType.RUN_FINISHED;
        }

        @Override
        public String getThreadId() {
            return threadId;
        }

        @Override
        public String getRunId() {
            return runId;
        }
    }

    /**
     * 表示文本消息开始的事件，在智能体开始生成文本回复时发送。
     */
    record TextMessageStart(
            String threadId,
            String runId,
            String messageId,
            String role,
            Long timestamp,
            Object rawEvent)
            implements AguiEvent {

        @JsonCreator
        public TextMessageStart(
                @JsonProperty("threadId") String threadId,
                @JsonProperty("runId") String runId,
                @JsonProperty("messageId") String messageId,
                @JsonProperty("role") String role,
                @JsonProperty("timestamp") Long timestamp,
                @JsonProperty("rawEvent") Object rawEvent) {
            this.threadId = Objects.requireNonNull(threadId, "threadId cannot be null");
            this.runId = Objects.requireNonNull(runId, "runId cannot be null");
            this.messageId = Objects.requireNonNull(messageId, "messageId cannot be null");
            this.role = Objects.requireNonNull(role, "role cannot be null");

            this.timestamp = timestamp;
            this.rawEvent = rawEvent;
        }

        public TextMessageStart(String threadId, String runId, String messageId, String role) {
            this(threadId, runId, messageId, role, null, null);
        }

        @Override
        public AguiEventType getType() {
            return AguiEventType.TEXT_MESSAGE_START;
        }

        @Override
        public String getThreadId() {
            return threadId;
        }

        @Override
        public String getRunId() {
            return runId;
        }
    }

    /**
     * 包含消息文本增量的事件，在流式传输过程中分块交付文本。
     */
    record TextMessageContent(
            String threadId,
            String runId,
            String messageId,
            String delta,
            Long timestamp,
            Object rawEvent)
            implements AguiEvent {

        @JsonCreator
        public TextMessageContent(
                @JsonProperty("threadId") String threadId,
                @JsonProperty("runId") String runId,
                @JsonProperty("messageId") String messageId,
                @JsonProperty("delta") String delta,
                @JsonProperty("timestamp") Long timestamp,
                @JsonProperty("rawEvent") Object rawEvent) {
            this.threadId = Objects.requireNonNull(threadId, "threadId cannot be null");
            this.runId = Objects.requireNonNull(runId, "runId cannot be null");
            this.messageId = Objects.requireNonNull(messageId, "messageId cannot be null");
            this.delta = Objects.requireNonNull(delta, "delta cannot be null");

            this.timestamp = timestamp;
            this.rawEvent = rawEvent;
        }

        public TextMessageContent(String threadId, String runId, String messageId, String delta) {
            this(threadId, runId, messageId, delta, null, null);
        }

        @Override
        public AguiEventType getType() {
            return AguiEventType.TEXT_MESSAGE_CONTENT;
        }

        @Override
        public String getThreadId() {
            return threadId;
        }

        @Override
        public String getRunId() {
            return runId;
        }
    }

    /**
     * 表示文本消息结束的事件，在智能体完成文本消息生成时发送。
     */
    record TextMessageEnd(
            String threadId, String runId, String messageId, Long timestamp, Object rawEvent)
            implements AguiEvent {

        @JsonCreator
        public TextMessageEnd(
                @JsonProperty("threadId") String threadId,
                @JsonProperty("runId") String runId,
                @JsonProperty("messageId") String messageId,
                @JsonProperty("timestamp") Long timestamp,
                @JsonProperty("rawEvent") Object rawEvent) {
            this.threadId = Objects.requireNonNull(threadId, "threadId cannot be null");
            this.runId = Objects.requireNonNull(runId, "runId cannot be null");
            this.messageId = Objects.requireNonNull(messageId, "messageId cannot be null");

            this.timestamp = timestamp;
            this.rawEvent = rawEvent;
        }

        public TextMessageEnd(String threadId, String runId, String messageId) {
            this(threadId, runId, messageId, null, null);
        }

        @Override
        public AguiEventType getType() {
            return AguiEventType.TEXT_MESSAGE_END;
        }

        @Override
        public String getThreadId() {
            return threadId;
        }

        @Override
        public String getRunId() {
            return runId;
        }
    }

    /**
     * 表示工具调用开始的事件，在智能体开始一次工具调用时发送。
     */
    record ToolCallStart(
            String threadId,
            String runId,
            String toolCallId,
            String toolCallName,
            Long timestamp,
            Object rawEvent)
            implements AguiEvent {

        @JsonCreator
        public ToolCallStart(
                @JsonProperty("threadId") String threadId,
                @JsonProperty("runId") String runId,
                @JsonProperty("toolCallId") String toolCallId,
                @JsonProperty("toolCallName") String toolCallName,
                @JsonProperty("timestamp") Long timestamp,
                @JsonProperty("rawEvent") Object rawEvent) {
            this.threadId = Objects.requireNonNull(threadId, "threadId cannot be null");
            this.runId = Objects.requireNonNull(runId, "runId cannot be null");
            this.toolCallId = Objects.requireNonNull(toolCallId, "toolCallId cannot be null");
            this.toolCallName = Objects.requireNonNull(toolCallName, "toolCallName cannot be null");

            this.timestamp = timestamp;
            this.rawEvent = rawEvent;
        }

        public ToolCallStart(
                String threadId, String runId, String toolCallId, String toolCallName) {
            this(threadId, runId, toolCallId, toolCallName, null, null);
        }

        @Override
        public AguiEventType getType() {
            return AguiEventType.TOOL_CALL_START;
        }

        @Override
        public String getThreadId() {
            return threadId;
        }

        @Override
        public String getRunId() {
            return runId;
        }
    }

    /**
     * 包含工具调用流式参数的事件；增量为完整工具参数的一部分 JSON 片段。
     */
    record ToolCallArgs(
            String threadId,
            String runId,
            String toolCallId,
            String delta,
            Long timestamp,
            Object rawEvent)
            implements AguiEvent {

        @JsonCreator
        public ToolCallArgs(
                @JsonProperty("threadId") String threadId,
                @JsonProperty("runId") String runId,
                @JsonProperty("toolCallId") String toolCallId,
                @JsonProperty("delta") String delta,
                @JsonProperty("timestamp") Long timestamp,
                @JsonProperty("rawEvent") Object rawEvent) {
            this.threadId = Objects.requireNonNull(threadId, "threadId cannot be null");
            this.runId = Objects.requireNonNull(runId, "runId cannot be null");
            this.toolCallId = Objects.requireNonNull(toolCallId, "toolCallId cannot be null");
            this.delta = Objects.requireNonNull(delta, "delta cannot be null");

            this.timestamp = timestamp;
            this.rawEvent = rawEvent;
        }

        public ToolCallArgs(String threadId, String runId, String toolCallId, String delta) {
            this(threadId, runId, toolCallId, delta, null, null);
        }

        @Override
        public AguiEventType getType() {
            return AguiEventType.TOOL_CALL_ARGS;
        }

        @Override
        public String getThreadId() {
            return threadId;
        }

        @Override
        public String getRunId() {
            return runId;
        }
    }

    /**
     * 表示工具调用参数阶段结束的事件；实际结果由工具结果事件交付。
     */
    record ToolCallEnd(
            String threadId, String runId, String toolCallId, Long timestamp, Object rawEvent)
            implements AguiEvent {

        @JsonCreator
        public ToolCallEnd(
                @JsonProperty("threadId") String threadId,
                @JsonProperty("runId") String runId,
                @JsonProperty("toolCallId") String toolCallId,
                @JsonProperty("timestamp") Long timestamp,
                @JsonProperty("rawEvent") Object rawEvent) {
            this.threadId = Objects.requireNonNull(threadId, "threadId cannot be null");
            this.runId = Objects.requireNonNull(runId, "runId cannot be null");
            this.toolCallId = Objects.requireNonNull(toolCallId, "toolCallId cannot be null");

            this.timestamp = timestamp;
            this.rawEvent = rawEvent;
        }

        public ToolCallEnd(String threadId, String runId, String toolCallId) {
            this(threadId, runId, toolCallId, null, null);
        }

        @Override
        public AguiEventType getType() {
            return AguiEventType.TOOL_CALL_END;
        }

        @Override
        public String getThreadId() {
            return threadId;
        }

        @Override
        public String getRunId() {
            return runId;
        }
    }

    /**
     * 包含工具调用结果的事件。
     */
    record ToolCallResult(
            String threadId,
            String runId,
            String toolCallId,
            String content,
            String role,
            String messageId,
            Long timestamp,
            Object rawEvent)
            implements AguiEvent {

        @JsonCreator
        public ToolCallResult(
                @JsonProperty("threadId") String threadId,
                @JsonProperty("runId") String runId,
                @JsonProperty("toolCallId") String toolCallId,
                @JsonProperty("content") String content,
                @JsonProperty("role") String role,
                @JsonProperty("messageId") String messageId,
                @JsonProperty("timestamp") Long timestamp,
                @JsonProperty("rawEvent") Object rawEvent) {
            this.threadId = Objects.requireNonNull(threadId, "threadId cannot be null");
            this.runId = Objects.requireNonNull(runId, "runId cannot be null");
            this.toolCallId = Objects.requireNonNull(toolCallId, "toolCallId cannot be null");
            this.content = content;
            this.role = role;
            this.messageId = messageId;

            this.timestamp = timestamp;
            this.rawEvent = rawEvent;
        }

        public ToolCallResult(
                String threadId,
                String runId,
                String toolCallId,
                String content,
                String role,
                String messageId) {
            this(threadId, runId, toolCallId, content, role, messageId, null, null);
        }

        @Override
        public AguiEventType getType() {
            return AguiEventType.TOOL_CALL_RESULT;
        }

        @Override
        public String getThreadId() {
            return threadId;
        }

        @Override
        public String getRunId() {
            return runId;
        }

        public String getRole() {
            return role;
        }

        public String getMessageId() {
            return messageId;
        }
    }

    /**
     * 包含完整状态快照的事件，用所提供快照替换客户端整个状态。
     */
    record StateSnapshot(
            String threadId,
            String runId,
            Map<String, Object> snapshot,
            Long timestamp,
            Object rawEvent)
            implements AguiEvent {

        @JsonCreator
        public StateSnapshot(
                @JsonProperty("threadId") String threadId,
                @JsonProperty("runId") String runId,
                @JsonProperty("snapshot") Map<String, Object> snapshot,
                @JsonProperty("timestamp") Long timestamp,
                @JsonProperty("rawEvent") Object rawEvent) {
            this.threadId = Objects.requireNonNull(threadId, "threadId cannot be null");
            this.runId = Objects.requireNonNull(runId, "runId cannot be null");
            this.snapshot =
                    snapshot != null
                            ? Collections.unmodifiableMap(new HashMap<>(snapshot))
                            : Collections.emptyMap();

            this.timestamp = timestamp;
            this.rawEvent = rawEvent;
        }

        public StateSnapshot(String threadId, String runId, Map<String, Object> snapshot) {
            this(threadId, runId, snapshot, null, null);
        }

        @Override
        public AguiEventType getType() {
            return AguiEventType.STATE_SNAPSHOT;
        }

        @Override
        public String getThreadId() {
            return threadId;
        }

        @Override
        public String getRunId() {
            return runId;
        }
    }

    /**
     * 包含状态增量的事件，携带应应用于当前客户端状态的 JSON Patch 操作列表（RFC 6902）。
     */
    record StateDelta(
            String threadId,
            String runId,
            List<JsonPatchOperation> delta,
            Long timestamp,
            Object rawEvent)
            implements AguiEvent {

        @JsonCreator
        public StateDelta(
                @JsonProperty("threadId") String threadId,
                @JsonProperty("runId") String runId,
                @JsonProperty("delta") List<JsonPatchOperation> delta,
                @JsonProperty("timestamp") Long timestamp,
                @JsonProperty("rawEvent") Object rawEvent) {
            this.threadId = Objects.requireNonNull(threadId, "threadId cannot be null");
            this.runId = Objects.requireNonNull(runId, "runId cannot be null");
            this.delta =
                    delta != null ? Collections.unmodifiableList(delta) : Collections.emptyList();

            this.timestamp = timestamp;
            this.rawEvent = rawEvent;
        }

        public StateDelta(String threadId, String runId, List<JsonPatchOperation> delta) {
            this(threadId, runId, delta, null, null);
        }

        @Override
        public AguiEventType getType() {
            return AguiEventType.STATE_DELTA;
        }

        @Override
        public String getThreadId() {
            return threadId;
        }

        @Override
        public String getRunId() {
            return runId;
        }
    }

    /**
     * 包含原始/自定义数据的事件，用于透传不适合标准 AG-UI 事件类型的自定义数据。
     */
    record Raw(
            String threadId,
            String runId,
            Object event,
            String source,
            Long timestamp,
            Object rawEvent)
            implements AguiEvent {

        @JsonCreator
        public Raw(
                @JsonProperty("threadId") String threadId,
                @JsonProperty("runId") String runId,
                @JsonProperty("event") Object event,
                @JsonProperty("source") String source,
                @JsonProperty("timestamp") Long timestamp,
                @JsonProperty("rawEvent") Object rawEvent) {
            this.threadId = Objects.requireNonNull(threadId, "threadId cannot be null");
            this.runId = Objects.requireNonNull(runId, "runId cannot be null");
            this.event = event;
            this.source = source;
            this.timestamp = timestamp;
            this.rawEvent = rawEvent;
        }

        public Raw(String threadId, String runId, Object event, String source) {
            this(threadId, runId, event, source, null, null);
        }

        public Raw(String threadId, String runId, Object event) {
            this(threadId, runId, event, null, null, null);
        }

        @Override
        public AguiEventType getType() {
            return AguiEventType.RAW;
        }

        @Override
        public String getThreadId() {
            return threadId;
        }

        @Override
        public String getRunId() {
            return runId;
        }
    }

    /**
     * Custom 事件提供扩展机制，用于实现标准事件类型未覆盖的功能。
     */
    record Custom(
            String threadId,
            String runId,
            String name,
            Object value,
            Long timestamp,
            Object rawEvent)
            implements AguiEvent {

        @JsonCreator
        public Custom(
                @JsonProperty("threadId") String threadId,
                @JsonProperty("runId") String runId,
                @JsonProperty("name") String name,
                @JsonProperty("value") Object value,
                @JsonProperty("timestamp") Long timestamp,
                @JsonProperty("rawEvent") Object rawEvent) {
            this.threadId = Objects.requireNonNull(threadId, "threadId cannot be null");
            this.runId = Objects.requireNonNull(runId, "runId cannot be null");
            this.name = Objects.requireNonNull(name, "name cannot be null");
            this.value = value; // 可为 null

            this.timestamp = timestamp;
            this.rawEvent = rawEvent;
        }

        public Custom(String threadId, String runId, String name, Object value) {
            this(threadId, runId, name, value, null, null);
        }

        @Override
        public AguiEventType getType() {
            return AguiEventType.CUSTOM;
        }

        @Override
        public String getThreadId() {
            return threadId;
        }

        @Override
        public String getRunId() {
            return runId;
        }
    }

    /**
     * 表示推理/思考阶段开始的事件，在智能体开始内部推理过程时发送。
     *
     * <p>遵循 AG-UI Reasoning 草案规范。
     */
    record ReasoningStart(
            String threadId,
            String runId,
            String messageId,
            String encryptedContent,
            Long timestamp,
            Object rawEvent)
            implements AguiEvent {

        @JsonCreator
        public ReasoningStart(
                @JsonProperty("threadId") String threadId,
                @JsonProperty("runId") String runId,
                @JsonProperty("messageId") String messageId,
                @JsonProperty("encryptedContent") String encryptedContent,
                @JsonProperty("timestamp") Long timestamp,
                @JsonProperty("rawEvent") Object rawEvent) {
            this.threadId = Objects.requireNonNull(threadId, "threadId cannot be null");
            this.runId = Objects.requireNonNull(runId, "runId cannot be null");
            this.messageId = Objects.requireNonNull(messageId, "messageId cannot be null");
            this.encryptedContent = encryptedContent; // 可选

            this.timestamp = timestamp;
            this.rawEvent = rawEvent;
        }

        public ReasoningStart(
                String threadId, String runId, String messageId, String encryptedContent) {
            this(threadId, runId, messageId, encryptedContent, null, null);
        }

        @Override
        public AguiEventType getType() {
            return AguiEventType.REASONING_START;
        }

        @Override
        public String getThreadId() {
            return threadId;
        }

        @Override
        public String getRunId() {
            return runId;
        }
    }

    /**
     * 表示推理消息开始的事件。
     *
     * <p>遵循 AG-UI Reasoning 草案规范。
     */
    record ReasoningMessageStart(
            String threadId,
            String runId,
            String messageId,
            String role,
            Long timestamp,
            Object rawEvent)
            implements AguiEvent {

        @JsonCreator
        public ReasoningMessageStart(
                @JsonProperty("threadId") String threadId,
                @JsonProperty("runId") String runId,
                @JsonProperty("messageId") String messageId,
                @JsonProperty("role") String role,
                @JsonProperty("timestamp") Long timestamp,
                @JsonProperty("rawEvent") Object rawEvent) {
            this.threadId = Objects.requireNonNull(threadId, "threadId cannot be null");
            this.runId = Objects.requireNonNull(runId, "runId cannot be null");
            this.messageId = Objects.requireNonNull(messageId, "messageId cannot be null");
            this.role = Objects.requireNonNull(role, "role cannot be null");

            this.timestamp = timestamp;
            this.rawEvent = rawEvent;
        }

        public ReasoningMessageStart(String threadId, String runId, String messageId, String role) {
            this(threadId, runId, messageId, role, null, null);
        }

        @Override
        public AguiEventType getType() {
            return AguiEventType.REASONING_MESSAGE_START;
        }

        @Override
        public String getThreadId() {
            return threadId;
        }

        @Override
        public String getRunId() {
            return runId;
        }
    }

    /**
     * 包含流式推理消息内容块的事件。
     *
     * <p>遵循 AG-UI Reasoning 草案规范。
     */
    record ReasoningMessageContent(
            String threadId,
            String runId,
            String messageId,
            String delta,
            Long timestamp,
            Object rawEvent)
            implements AguiEvent {

        @JsonCreator
        public ReasoningMessageContent(
                @JsonProperty("threadId") String threadId,
                @JsonProperty("runId") String runId,
                @JsonProperty("messageId") String messageId,
                @JsonProperty("delta") String delta,
                @JsonProperty("timestamp") Long timestamp,
                @JsonProperty("rawEvent") Object rawEvent) {
            this.threadId = Objects.requireNonNull(threadId, "threadId cannot be null");
            this.runId = Objects.requireNonNull(runId, "runId cannot be null");
            this.messageId = Objects.requireNonNull(messageId, "messageId cannot be null");
            this.delta = Objects.requireNonNull(delta, "delta cannot be null");

            this.timestamp = timestamp;
            this.rawEvent = rawEvent;
        }

        public ReasoningMessageContent(
                String threadId, String runId, String messageId, String delta) {
            this(threadId, runId, messageId, delta, null, null);
        }

        @Override
        public AguiEventType getType() {
            return AguiEventType.REASONING_MESSAGE_CONTENT;
        }

        @Override
        public String getThreadId() {
            return threadId;
        }

        @Override
        public String getRunId() {
            return runId;
        }
    }

    /**
     * 表示推理消息结束的事件。
     *
     * <p>遵循 AG-UI Reasoning 草案规范。
     */
    record ReasoningMessageEnd(
            String threadId, String runId, String messageId, Long timestamp, Object rawEvent)
            implements AguiEvent {

        @JsonCreator
        public ReasoningMessageEnd(
                @JsonProperty("threadId") String threadId,
                @JsonProperty("runId") String runId,
                @JsonProperty("messageId") String messageId,
                @JsonProperty("timestamp") Long timestamp,
                @JsonProperty("rawEvent") Object rawEvent) {
            this.threadId = Objects.requireNonNull(threadId, "threadId cannot be null");
            this.runId = Objects.requireNonNull(runId, "runId cannot be null");
            this.messageId = Objects.requireNonNull(messageId, "messageId cannot be null");

            this.timestamp = timestamp;
            this.rawEvent = rawEvent;
        }

        public ReasoningMessageEnd(String threadId, String runId, String messageId) {
            this(threadId, runId, messageId, null, null);
        }

        @Override
        public AguiEventType getType() {
            return AguiEventType.REASONING_MESSAGE_END;
        }

        @Override
        public String getThreadId() {
            return threadId;
        }

        @Override
        public String getRunId() {
            return runId;
        }
    }

    /**
     * 自动开启/关闭推理消息的便捷事件。
     *
     * <p>遵循 AG-UI Reasoning 草案规范。
     */
    record ReasoningMessageChunk(
            String threadId,
            String runId,
            String messageId,
            String delta,
            Long timestamp,
            Object rawEvent)
            implements AguiEvent {

        @JsonCreator
        public ReasoningMessageChunk(
                @JsonProperty("threadId") String threadId,
                @JsonProperty("runId") String runId,
                @JsonProperty("messageId") String messageId,
                @JsonProperty("delta") String delta,
                @JsonProperty("timestamp") Long timestamp,
                @JsonProperty("rawEvent") Object rawEvent) {
            this.threadId = Objects.requireNonNull(threadId, "threadId cannot be null");
            this.runId = Objects.requireNonNull(runId, "runId cannot be null");
            this.messageId = messageId; // 可选
            this.delta = delta; // 可选

            this.timestamp = timestamp;
            this.rawEvent = rawEvent;
        }

        public ReasoningMessageChunk(
                String threadId, String runId, String messageId, String delta) {
            this(threadId, runId, messageId, delta, null, null);
        }

        @Override
        public AguiEventType getType() {
            return AguiEventType.REASONING_MESSAGE_CHUNK;
        }

        @Override
        public String getThreadId() {
            return threadId;
        }

        @Override
        public String getRunId() {
            return runId;
        }
    }

    /**
     * 表示推理/思考阶段结束的事件，在智能体完成内部推理过程时发送。
     *
     * <p>遵循 AG-UI Reasoning 草案规范。
     */
    record ReasoningEnd(
            String threadId, String runId, String messageId, Long timestamp, Object rawEvent)
            implements AguiEvent {

        @JsonCreator
        public ReasoningEnd(
                @JsonProperty("threadId") String threadId,
                @JsonProperty("runId") String runId,
                @JsonProperty("messageId") String messageId,
                @JsonProperty("timestamp") Long timestamp,
                @JsonProperty("rawEvent") Object rawEvent) {
            this.threadId = Objects.requireNonNull(threadId, "threadId cannot be null");
            this.runId = Objects.requireNonNull(runId, "runId cannot be null");
            this.messageId = Objects.requireNonNull(messageId, "messageId cannot be null");

            this.timestamp = timestamp;
            this.rawEvent = rawEvent;
        }

        public ReasoningEnd(String threadId, String runId, String messageId) {
            this(threadId, runId, messageId, null, null);
        }

        @Override
        public AguiEventType getType() {
            return AguiEventType.REASONING_END;
        }

        @Override
        public String getThreadId() {
            return threadId;
        }

        @Override
        public String getRunId() {
            return runId;
        }
    }

    /**
     * 表示智能体运行出错的事件，在执行中遇到错误时发送，替代旧版 Raw+RunFinished 模式。
     */
    record RunError(
            String threadId,
            String runId,
            String message,
            String code,
            Long timestamp,
            Object rawEvent)
            implements AguiEvent {

        @JsonCreator
        public RunError(
                @JsonProperty("threadId") String threadId,
                @JsonProperty("runId") String runId,
                @JsonProperty("message") String message,
                @JsonProperty("code") String code,
                @JsonProperty("timestamp") Long timestamp,
                @JsonProperty("rawEvent") Object rawEvent) {
            this.threadId = Objects.requireNonNull(threadId, "threadId cannot be null");
            this.runId = Objects.requireNonNull(runId, "runId cannot be null");
            this.message = Objects.requireNonNull(message, "message cannot be null");
            this.code = code;

            this.timestamp = timestamp;
            this.rawEvent = rawEvent;
        }

        public RunError(String threadId, String runId, String message, String code) {
            this(threadId, runId, message, code, null, null);
        }

        @Override
        public AguiEventType getType() {
            return AguiEventType.RUN_ERROR;
        }

        @Override
        public String getThreadId() {
            return threadId;
        }

        @Override
        public String getRunId() {
            return runId;
        }
    }

    /**
     * 智能体步骤开始执行时发送的事件。
     */
    record StepStarted(
            String threadId, String runId, String stepName, Long timestamp, Object rawEvent)
            implements AguiEvent {

        @JsonCreator
        public StepStarted(
                @JsonProperty("threadId") String threadId,
                @JsonProperty("runId") String runId,
                @JsonProperty("stepName") String stepName,
                @JsonProperty("timestamp") Long timestamp,
                @JsonProperty("rawEvent") Object rawEvent) {
            this.threadId = Objects.requireNonNull(threadId, "threadId cannot be null");
            this.runId = Objects.requireNonNull(runId, "runId cannot be null");
            this.stepName = Objects.requireNonNull(stepName, "stepName cannot be null");

            this.timestamp = timestamp;
            this.rawEvent = rawEvent;
        }

        public StepStarted(String threadId, String runId, String stepName) {
            this(threadId, runId, stepName, null, null);
        }

        @Override
        public AguiEventType getType() {
            return AguiEventType.STEP_STARTED;
        }

        @Override
        public String getThreadId() {
            return threadId;
        }

        @Override
        public String getRunId() {
            return runId;
        }
    }

    /**
     * 智能体步骤完成执行时发送的事件。
     */
    record StepFinished(
            String threadId, String runId, String stepName, Long timestamp, Object rawEvent)
            implements AguiEvent {

        @JsonCreator
        public StepFinished(
                @JsonProperty("threadId") String threadId,
                @JsonProperty("runId") String runId,
                @JsonProperty("stepName") String stepName,
                @JsonProperty("timestamp") Long timestamp,
                @JsonProperty("rawEvent") Object rawEvent) {
            this.threadId = Objects.requireNonNull(threadId, "threadId cannot be null");
            this.runId = Objects.requireNonNull(runId, "runId cannot be null");
            this.stepName = Objects.requireNonNull(stepName, "stepName cannot be null");

            this.timestamp = timestamp;
            this.rawEvent = rawEvent;
        }

        public StepFinished(String threadId, String runId, String stepName) {
            this(threadId, runId, stepName, null, null);
        }

        @Override
        public AguiEventType getType() {
            return AguiEventType.STEP_FINISHED;
        }

        @Override
        public String getThreadId() {
            return threadId;
        }

        @Override
        public String getRunId() {
            return runId;
        }
    }

    /**
     * 自动开启/关闭文本消息的便捷事件（兼容分块模式）。
     */
    record TextMessageChunk(
            String threadId,
            String runId,
            String messageId,
            String role,
            String delta,
            String name,
            Long timestamp,
            Object rawEvent)
            implements AguiEvent {

        @JsonCreator
        public TextMessageChunk(
                @JsonProperty("threadId") String threadId,
                @JsonProperty("runId") String runId,
                @JsonProperty("messageId") String messageId,
                @JsonProperty("role") String role,
                @JsonProperty("delta") String delta,
                @JsonProperty("name") String name,
                @JsonProperty("timestamp") Long timestamp,
                @JsonProperty("rawEvent") Object rawEvent) {
            this.threadId = Objects.requireNonNull(threadId, "threadId cannot be null");
            this.runId = Objects.requireNonNull(runId, "runId cannot be null");
            this.messageId = messageId;
            this.role = role;
            this.delta = delta;
            this.name = name;

            this.timestamp = timestamp;
            this.rawEvent = rawEvent;
        }

        public TextMessageChunk(
                String threadId,
                String runId,
                String messageId,
                String role,
                String delta,
                String name) {
            this(threadId, runId, messageId, role, delta, name, null, null);
        }

        @Override
        public AguiEventType getType() {
            return AguiEventType.TEXT_MESSAGE_CHUNK;
        }

        @Override
        public String getThreadId() {
            return threadId;
        }

        @Override
        public String getRunId() {
            return runId;
        }
    }

    /**
     * 自动开启/关闭工具调用的便捷事件（兼容分块模式）。
     */
    record ToolCallChunk(
            String threadId,
            String runId,
            String toolCallId,
            String toolCallName,
            String parentMessageId,
            String delta,
            Long timestamp,
            Object rawEvent)
            implements AguiEvent {

        @JsonCreator
        public ToolCallChunk(
                @JsonProperty("threadId") String threadId,
                @JsonProperty("runId") String runId,
                @JsonProperty("toolCallId") String toolCallId,
                @JsonProperty("toolCallName") String toolCallName,
                @JsonProperty("parentMessageId") String parentMessageId,
                @JsonProperty("delta") String delta,
                @JsonProperty("timestamp") Long timestamp,
                @JsonProperty("rawEvent") Object rawEvent) {
            this.threadId = Objects.requireNonNull(threadId, "threadId cannot be null");
            this.runId = Objects.requireNonNull(runId, "runId cannot be null");
            this.toolCallId = toolCallId;
            this.toolCallName = toolCallName;
            this.parentMessageId = parentMessageId;
            this.delta = delta;

            this.timestamp = timestamp;
            this.rawEvent = rawEvent;
        }

        public ToolCallChunk(
                String threadId,
                String runId,
                String toolCallId,
                String toolCallName,
                String parentMessageId,
                String delta) {
            this(threadId, runId, toolCallId, toolCallName, parentMessageId, delta, null, null);
        }

        @Override
        public AguiEventType getType() {
            return AguiEventType.TOOL_CALL_CHUNK;
        }

        @Override
        public String getThreadId() {
            return threadId;
        }

        @Override
        public String getRunId() {
            return runId;
        }
    }

    /**
     * 包含会话消息完整快照的事件，通常在 RUN_FINISHED 后发送以同步完整消息状态。
     */
    record MessagesSnapshot(
            String threadId,
            String runId,
            List<AguiMessage> messages,
            Long timestamp,
            Object rawEvent)
            implements AguiEvent {

        @JsonCreator
        public MessagesSnapshot(
                @JsonProperty("threadId") String threadId,
                @JsonProperty("runId") String runId,
                @JsonProperty("messages") List<AguiMessage> messages,
                @JsonProperty("timestamp") Long timestamp,
                @JsonProperty("rawEvent") Object rawEvent) {
            this.threadId = Objects.requireNonNull(threadId, "threadId cannot be null");
            this.runId = Objects.requireNonNull(runId, "runId cannot be null");
            this.messages =
                    messages != null
                            ? Collections.unmodifiableList(new ArrayList<>(messages))
                            : Collections.emptyList();

            this.timestamp = timestamp;
            this.rawEvent = rawEvent;
        }

        public MessagesSnapshot(String threadId, String runId, List<AguiMessage> messages) {
            this(threadId, runId, messages, null, null);
        }

        @Override
        public AguiEventType getType() {
            return AguiEventType.MESSAGES_SNAPSHOT;
        }

        @Override
        public String getThreadId() {
            return threadId;
        }

        @Override
        public String getRunId() {
            return runId;
        }
    }

    /**
     * 包含活动状态快照的事件，以结构化活动数据替换给定消息在客户端的整个活动状态。
     */
    record ActivitySnapshot(
            String threadId,
            String runId,
            String messageId,
            String activityType,
            Map<String, Object> content,
            Boolean replace,
            Long timestamp,
            Object rawEvent)
            implements AguiEvent {

        @JsonCreator
        public ActivitySnapshot(
                @JsonProperty("threadId") String threadId,
                @JsonProperty("runId") String runId,
                @JsonProperty("messageId") String messageId,
                @JsonProperty("activityType") String activityType,
                @JsonProperty("content") Map<String, Object> content,
                @JsonProperty("replace") Boolean replace,
                @JsonProperty("timestamp") Long timestamp,
                @JsonProperty("rawEvent") Object rawEvent) {
            this.threadId = Objects.requireNonNull(threadId, "threadId cannot be null");
            this.runId = Objects.requireNonNull(runId, "runId cannot be null");
            this.messageId = Objects.requireNonNull(messageId, "messageId cannot be null");
            this.activityType = Objects.requireNonNull(activityType, "activityType cannot be null");
            this.content =
                    content != null
                            ? Collections.unmodifiableMap(new HashMap<>(content))
                            : Collections.emptyMap();
            this.replace = replace != null ? replace : true;

            this.timestamp = timestamp;
            this.rawEvent = rawEvent;
        }

        public ActivitySnapshot(
                String threadId,
                String runId,
                String messageId,
                String activityType,
                Map<String, Object> content,
                Boolean replace) {
            this(threadId, runId, messageId, activityType, content, replace, null, null);
        }

        public ActivitySnapshot(
                String threadId,
                String runId,
                String messageId,
                String activityType,
                Map<String, Object> content) {
            this(threadId, runId, messageId, activityType, content, true);
        }

        @Override
        public AguiEventType getType() {
            return AguiEventType.ACTIVITY_SNAPSHOT;
        }

        @Override
        public String getThreadId() {
            return threadId;
        }

        @Override
        public String getRunId() {
            return runId;
        }
    }

    /**
     * 包含活动状态增量更新的事件；补丁携带应应用于给定消息客户端活动状态的 JSON Patch 操作列表（RFC 6902）。
     */
    record ActivityDelta(
            String threadId,
            String runId,
            String messageId,
            String activityType,
            List<JsonPatchOperation> patch,
            Long timestamp,
            Object rawEvent)
            implements AguiEvent {

        @JsonCreator
        public ActivityDelta(
                @JsonProperty("threadId") String threadId,
                @JsonProperty("runId") String runId,
                @JsonProperty("messageId") String messageId,
                @JsonProperty("activityType") String activityType,
                @JsonProperty("patch") List<JsonPatchOperation> patch,
                @JsonProperty("timestamp") Long timestamp,
                @JsonProperty("rawEvent") Object rawEvent) {
            this.threadId = Objects.requireNonNull(threadId, "threadId cannot be null");
            this.runId = Objects.requireNonNull(runId, "runId cannot be null");
            this.messageId = Objects.requireNonNull(messageId, "messageId cannot be null");
            this.activityType = Objects.requireNonNull(activityType, "activityType cannot be null");
            this.patch =
                    patch != null ? Collections.unmodifiableList(patch) : Collections.emptyList();

            this.timestamp = timestamp;
            this.rawEvent = rawEvent;
        }

        public ActivityDelta(
                String threadId,
                String runId,
                String messageId,
                String activityType,
                List<JsonPatchOperation> patch) {
            this(threadId, runId, messageId, activityType, patch, null, null);
        }

        @Override
        public AguiEventType getType() {
            return AguiEventType.ACTIVITY_DELTA;
        }

        @Override
        public String getThreadId() {
            return threadId;
        }

        @Override
        public String getRunId() {
            return runId;
        }
    }

    /**
     * 包含加密推理值的事件，用于加密推理内容（ZDR 模式），敏感推理 token 在传输前加密。
     */
    record ReasoningEncryptedValue(
            String threadId,
            String runId,
            String subtype,
            String entityId,
            String encryptedValue,
            Long timestamp,
            Object rawEvent)
            implements AguiEvent {

        @JsonCreator
        public ReasoningEncryptedValue(
                @JsonProperty("threadId") String threadId,
                @JsonProperty("runId") String runId,
                @JsonProperty("subtype") String subtype,
                @JsonProperty("entityId") String entityId,
                @JsonProperty("encryptedValue") String encryptedValue,
                @JsonProperty("timestamp") Long timestamp,
                @JsonProperty("rawEvent") Object rawEvent) {
            this.threadId = Objects.requireNonNull(threadId, "threadId cannot be null");
            this.runId = Objects.requireNonNull(runId, "runId cannot be null");
            this.subtype = Objects.requireNonNull(subtype, "subtype cannot be null");
            this.entityId = Objects.requireNonNull(entityId, "entityId cannot be null");
            this.encryptedValue =
                    Objects.requireNonNull(encryptedValue, "encryptedValue cannot be null");

            this.timestamp = timestamp;
            this.rawEvent = rawEvent;
        }

        public ReasoningEncryptedValue(
                String threadId,
                String runId,
                String subtype,
                String entityId,
                String encryptedValue) {
            this(threadId, runId, subtype, entityId, encryptedValue, null, null);
        }

        @Override
        public AguiEventType getType() {
            return AguiEventType.REASONING_ENCRYPTED_VALUE;
        }

        @Override
        public String getThreadId() {
            return threadId;
        }

        @Override
        public String getRunId() {
            return runId;
        }
    }

    // ---- 值类型 ----

    /**
     * 已完成运行的结果，可以是成功或中断。
     */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = RunFinishedSuccessOutcome.class, name = "success"),
        @JsonSubTypes.Type(value = RunFinishedInterruptOutcome.class, name = "interrupt")
    })
    sealed interface RunFinishedOutcome
            permits RunFinishedSuccessOutcome, RunFinishedInterruptOutcome {}

    /**
     * 运行成功的结果。
     */
    record RunFinishedSuccessOutcome() implements RunFinishedOutcome {

        @JsonCreator
        public RunFinishedSuccessOutcome {}
    }

    /**
     * 表示运行被中断的结果，含一个或多个等待用户处理的中断。
     */
    record RunFinishedInterruptOutcome(List<Interrupt> interrupts) implements RunFinishedOutcome {

        @JsonCreator
        public RunFinishedInterruptOutcome(@JsonProperty("interrupts") List<Interrupt> interrupts) {
            this.interrupts =
                    interrupts != null
                            ? Collections.unmodifiableList(interrupts)
                            : Collections.emptyList();
        }
    }

    /**
     * 表示智能体执行中发生的中断，需要用户输入处理后才能继续。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Interrupt(
            String id,
            String reason,
            String message,
            String toolCallId,
            Map<String, Object> responseSchema,
            String expiresAt,
            Map<String, Object> metadata) {

        @JsonCreator
        public Interrupt(
                @JsonProperty("id") String id,
                @JsonProperty("reason") String reason,
                @JsonProperty("message") String message,
                @JsonProperty("toolCallId") String toolCallId,
                @JsonProperty("responseSchema") Map<String, Object> responseSchema,
                @JsonProperty("expiresAt") String expiresAt,
                @JsonProperty("metadata") Map<String, Object> metadata) {
            this.id = Objects.requireNonNull(id, "id cannot be null");
            this.reason = Objects.requireNonNull(reason, "reason cannot be null");
            this.message = message;
            this.toolCallId = toolCallId;
            this.responseSchema = responseSchema;
            this.expiresAt = expiresAt;
            this.metadata = metadata;
        }
    }

    /**
     * 表示 JSON Patch 操作（RFC 6902），用于 {@link StateDelta} 事件的增量状态更新。
     */
    @JsonSerialize(using = JsonPatchOperation.JsonPatchOperationSerializer.class)
    record JsonPatchOperation(String op, String path, Object value, String from) {

        @JsonCreator
        public JsonPatchOperation(
                @JsonProperty("op") String op,
                @JsonProperty("path") String path,
                @JsonProperty("value") Object value,
                @JsonProperty("from") String from) {
            this.op = Objects.requireNonNull(op, "op cannot be null");
            this.path = Objects.requireNonNull(path, "path cannot be null");
            this.value = value;
            this.from = from;
        }

        /**
         * 创建 "add" 操作。
         *
         * @param path 添加位置的路径
         * @param value 要添加的值
         * @return 新增操作
         */
        public static JsonPatchOperation add(String path, Object value) {
            return new JsonPatchOperation("add", path, value, null);
        }

        /**
         * 创建 "remove" 操作。
         *
         * @param path 要移除的路径
         * @return 移除操作
         */
        public static JsonPatchOperation remove(String path) {
            return new JsonPatchOperation("remove", path, null, null);
        }

        /**
         * 创建 "replace" 操作。
         *
         * @param path 要替换的路径
         * @param value 新值
         * @return 替换操作
         */
        public static JsonPatchOperation replace(String path, Object value) {
            return new JsonPatchOperation("replace", path, value, null);
        }

        /**
         * 此操作是否按照 RFC 6902 携带 {@code value} 成员。
         *
         * @return 未定义值的操作返回 false
         */
        private boolean includesValue() {
            return !"remove".equals(op) && !"move".equals(op) && !"copy".equals(op);
        }

        /**
         * 序列化 JSON Patch 操作；未定义值的操作省略 {@code value}，无来源时省略 {@code from}。
         *
         * <p>对 {@code add}、{@code replace} 和 {@code test} 操作，即使 {@code value} 为 {@code null} 也保留该成员，因为 RFC 6902 要求其存在。
         */
        public static final class JsonPatchOperationSerializer
                extends JsonSerializer<JsonPatchOperation> {

            @Override
            public void serialize(
                    JsonPatchOperation operation, JsonGenerator gen, SerializerProvider serializers)
                    throws IOException {
                gen.writeStartObject();
                gen.writeStringField("op", operation.op());
                gen.writeStringField("path", operation.path());
                if (operation.includesValue()) {
                    gen.writeFieldName("value");
                    gen.writeObject(operation.value());
                }
                if (operation.from() != null) {
                    gen.writeStringField("from", operation.from());
                }
                gen.writeEndObject();
            }
        }
    }
}
