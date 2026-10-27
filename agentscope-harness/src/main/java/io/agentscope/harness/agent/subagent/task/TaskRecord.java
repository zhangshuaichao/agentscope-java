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
package io.agentscope.harness.agent.subagent.task;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentscope.harness.agent.subagent.protocol.RemotePendingConfirm;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 后台子智能体任务的持久化元数据，以 JSON 存储在工作区的
 * {@code agents/<parentAgentId>/tasks/<parentSessionId>.json}。
 *
 * <p>这是任务状态的权威事实来源。内存 {@link BackgroundTask} 句柄只是本地性能优化层；
 * {@code TaskRecord} 可跨 JVM 重启及分布式部署中的请求迁移保留。
 *
 * <p>所有字段使用 Jackson 进行 JSON 序列化。静默忽略未知字段，
 * 以支持向前兼容的 schema 演进。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TaskRecord {

    private String taskId;
    private String subAgentId;
    private String parentAgentId;
    private String parentSessionId;
    private String subSessionId;
    private TaskStatus status;
    private String result;
    private String errorMessage;
    private boolean cancelRequested;
    private Instant createdAt;
    private Instant lastCheckedAt;
    private Instant lastUpdatedAt;

    /**
     * 此终态任务结果以合成 {@code <system-reminder>} 消息推回父级智能体的时间；
     * 尚未投递时为 {@code null}。
     * {@link io.agentscope.harness.agent.middleware.SubagentsMiddleware}
     * 据此避免在每轮推理中重复投递同一个完成结果。
     */
    private Instant deliveredAt;

    /**
     * 为 {@code "agent-protocol"} 时，此任务由 {@link AgentProtocolTaskClient} 驱动。
     */
    private String transportType;

    private String remoteBaseUrl;

    private Map<String, String> remoteHeaders;

    /**
     * 为 {@code true} 时，远程或本地智能体暂停等待用户确认工具。
     * 状态保持为 {@link TaskStatus#RUNNING}，使等待屏障继续等待。
     */
    private boolean awaitingConfirm;

    private List<RemotePendingConfirm> pendingConfirms;

    /**
     * 父级观察到的最后一个远程事件序号（用于 SSE 恢复）。
     */
    private long lastEventSeq;

    public TaskRecord() {}

    public TaskRecord(
            String taskId,
            String subAgentId,
            String parentAgentId,
            String parentSessionId,
            String subSessionId) {
        this.taskId = taskId;
        this.subAgentId = subAgentId;
        this.parentAgentId = parentAgentId;
        this.parentSessionId = parentSessionId;
        this.subSessionId = subSessionId;
        this.status = TaskStatus.PENDING;
        this.cancelRequested = false;
        Instant now = Instant.now();
        this.createdAt = now;
        this.lastCheckedAt = now;
        this.lastUpdatedAt = now;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getSubAgentId() {
        return subAgentId;
    }

    public void setSubAgentId(String subAgentId) {
        this.subAgentId = subAgentId;
    }

    public String getParentAgentId() {
        return parentAgentId;
    }

    public void setParentAgentId(String parentAgentId) {
        this.parentAgentId = parentAgentId;
    }

    public String getParentSessionId() {
        return parentSessionId;
    }

    public void setParentSessionId(String parentSessionId) {
        this.parentSessionId = parentSessionId;
    }

    public String getSubSessionId() {
        return subSessionId;
    }

    public void setSubSessionId(String subSessionId) {
        this.subSessionId = subSessionId;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public void setStatus(TaskStatus status) {
        this.status = status;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public boolean isCancelRequested() {
        return cancelRequested;
    }

    public void setCancelRequested(boolean cancelRequested) {
        this.cancelRequested = cancelRequested;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getLastCheckedAt() {
        return lastCheckedAt;
    }

    public void setLastCheckedAt(Instant lastCheckedAt) {
        this.lastCheckedAt = lastCheckedAt;
    }

    public Instant getLastUpdatedAt() {
        return lastUpdatedAt;
    }

    public void setLastUpdatedAt(Instant lastUpdatedAt) {
        this.lastUpdatedAt = lastUpdatedAt;
    }

    /**
     * 便捷方法：将 lastUpdatedAt 更新为当前时间。
     */
    public void touch() {
        this.lastUpdatedAt = Instant.now();
    }

    /**
     * 便捷方法：将 lastCheckedAt 更新为当前时间。
     */
    public void touchChecked() {
        this.lastCheckedAt = Instant.now();
    }

    public Instant getDeliveredAt() {
        return deliveredAt;
    }

    public void setDeliveredAt(Instant deliveredAt) {
        this.deliveredAt = deliveredAt;
    }

    /**
     * 此任务的完成结果是否已经推回父级智能体。
     */
    public boolean isDelivered() {
        return deliveredAt != null;
    }

    public String getTransportType() {
        return transportType;
    }

    public void setTransportType(String transportType) {
        this.transportType = transportType;
    }

    public String getRemoteBaseUrl() {
        return remoteBaseUrl;
    }

    public void setRemoteBaseUrl(String remoteBaseUrl) {
        this.remoteBaseUrl = remoteBaseUrl;
    }

    public Map<String, String> getRemoteHeaders() {
        return remoteHeaders;
    }

    public void setRemoteHeaders(Map<String, String> remoteHeaders) {
        this.remoteHeaders =
                remoteHeaders == null || remoteHeaders.isEmpty()
                        ? null
                        : Collections.unmodifiableMap(Map.copyOf(remoteHeaders));
    }

    /**
     * 此任务是否通过 HTTP 任务协议执行。
     */
    public boolean isAgentProtocolTransport() {
        return transportType != null && "agent-protocol".equalsIgnoreCase(transportType);
    }

    public boolean isAwaitingConfirm() {
        return awaitingConfirm;
    }

    public void setAwaitingConfirm(boolean awaitingConfirm) {
        this.awaitingConfirm = awaitingConfirm;
    }

    public List<RemotePendingConfirm> getPendingConfirms() {
        return pendingConfirms;
    }

    public void setPendingConfirms(List<RemotePendingConfirm> pendingConfirms) {
        this.pendingConfirms =
                pendingConfirms == null || pendingConfirms.isEmpty()
                        ? null
                        : List.copyOf(pendingConfirms);
    }

    public long getLastEventSeq() {
        return lastEventSeq;
    }

    public void setLastEventSeq(long lastEventSeq) {
        this.lastEventSeq = lastEventSeq;
    }
}
