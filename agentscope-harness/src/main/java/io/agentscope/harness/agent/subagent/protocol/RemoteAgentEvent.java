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
package io.agentscope.harness.agent.subagent.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * 用于远程子智能体事件流的稳定协议 DTO。
 *
 * <p>字段按事件类型可选；忽略未知字段以保持向前兼容。
 *
 * <p>除按类型展平的字段外，事件还可携带 {@link #getPayload()}，即完整序列化的
 * 原始 {@link io.agentscope.core.event.AgentEvent}。支持该载荷的客户端可恢复
 * 原始事件（包括 ID、时间戳、元数据等）；旧客户端继续读取展平字段。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RemoteAgentEvent {

    private long seq;
    private RemoteEventType type;
    private String taskId;
    private String agentId;
    private String timestamp;
    private String text;
    private String toolCallId;
    private String toolName;
    private String toolInput;
    private List<RemotePendingConfirm> pendingConfirms;
    private String status;
    private String error;
    private String eventType;
    private String payload;

    public RemoteAgentEvent() {}

    public long getSeq() {
        return seq;
    }

    public void setSeq(long seq) {
        this.seq = seq;
    }

    public RemoteEventType getType() {
        return type;
    }

    public void setType(RemoteEventType type) {
        this.type = type;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public void setToolCallId(String toolCallId) {
        this.toolCallId = toolCallId;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public String getToolInput() {
        return toolInput;
    }

    public void setToolInput(String toolInput) {
        this.toolInput = toolInput;
    }

    public List<RemotePendingConfirm> getPendingConfirms() {
        return pendingConfirms;
    }

    public void setPendingConfirms(List<RemotePendingConfirm> pendingConfirms) {
        this.pendingConfirms = pendingConfirms;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    /**
     * 原始 {@link io.agentscope.core.event.AgentEventType} 的名称，例如
     * {@code MODEL_CALL_END}。客户端无需反序列化 {@link #getPayload()}
     * 即可过滤或记录事件。
     */
    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    /**
     * 以 JSON 表示的原始 {@link io.agentscope.core.event.AgentEvent}。
     * 对于服务器自行合成的事件（{@code STATUS}、{@code RUN_ERROR}）
     * 以及序列化失败的事件，该值为 {@code null}。
     */
    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }
}
