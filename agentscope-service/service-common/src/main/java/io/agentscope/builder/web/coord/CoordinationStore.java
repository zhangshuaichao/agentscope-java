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
package io.agentscope.builder.web.coord;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 多副本 Brain / Hands 工作进程共享的协调层。
 * <p>权威业务状态仍在 JPA 会话/事件表中。此存储保存短期租约、工作队列行、
 * HITL 工单和沙箱就绪元数据。默认实现为 JDBC；运维可将 Bean 替换为 Redis 实现。
 * <p>【学习提示】租约用于协调执行权，不代表工具副作用可回滚。租约、业务状态、
 * 人工确认的执行栅栏分别解决存活性、持久化与过期结果隔离，不能互相替代。
 */
public interface CoordinationStore {

    /** 轮询时回收 starting/active 工作项的过期阈值。 */
    Duration WORK_STALE_THRESHOLD = Duration.ofSeconds(60);

    // ---- 轮次租约（每会话进行中轮次的互斥锁） ----

    /** 尝试获取 {@code sessionId} 的轮次租约。其他所有者持有未过期租约时返回空。 */
    Optional<LeaseHandle> tryAcquireTurnLease(
            String sessionId, String ownerId, String instanceId, Duration ttl);

    /** {@code instanceId} 仍拥有租约时延长 TTL。 */
    boolean heartbeatTurnLease(String sessionId, String instanceId, Duration ttl);

    /** 租约属于 {@code instanceId} 时释放它。 */
    boolean releaseTurnLease(String sessionId, String instanceId);

    Optional<LeaseHandle> getTurnLease(String sessionId);

    /** 列出 {@code expiresAt} 早于 {@code nowMillis} 的轮次租约。 */
    List<LeaseHandle> listExpiredTurnLeases(long nowMillis);

    // ---- 轮次中断（跨副本） ----

    /** 记录 {@code sessionId} 的中断请求。持有轮次租约的实例在下次心跳时消费请求，并取消本地轮次。 */
    void requestTurnInterrupt(String sessionId, String reason);

    /** 记录托管执行尝试的中断请求，只允许匹配的轮次消费。 */
    void requestFencedTurnInterrupt(String sessionId, String reason, String fenceToken);

    /** 原子地消费 {@code sessionId} 的待处理中断请求；存在请求时返回原因。 */
    Optional<String> consumeTurnInterrupt(String sessionId);

    Optional<TurnInterruptRequest> consumeTurnInterruptRequest(String sessionId);

    // ---- 部署 cron 触发租约 ----

    /** 单次触发窗口租约；当前实例赢得窗口时返回 true。 */
    boolean tryAcquireFireLease(
            String deploymentId, String fireWindow, String instanceId, Duration ttl);

    // ---- HITL 人工介入工单 ----

    /** 插入不可变工单，或返回内容完全相同的已有工单。 */
    HitlTicket putHitlTicket(HitlTicket ticket);

    /** 为直接会话路由查找最新工单；托管解析使用带执行栅栏的重载。 */
    Optional<HitlTicket> getHitlTicket(String sessionId, String toolUseId);

    Optional<HitlTicket> getHitlTicket(HitlDecisionFence fence);

    /** 校验完整的不可变执行栅栏后设置允许/拒绝决定。已解决时返回当前工单（包括幂等重放）；工单/栅栏不匹配、已过期或冲突决定已获胜时返回空。 */
    Optional<HitlResolution> resolveHitlTicket(
            HitlDecisionFence fence,
            String resolutionStatus,
            long decisionVersion,
            boolean allow,
            String denyMessage,
            long resolvedAt);

    /** 截止时间已过时，原子地拒绝仍待处理的工单。 */
    Optional<HitlResolution> expireHitlTicket(String sessionId, String toolUseId, long expiredAt);

    /** 标记副作用已完成，使轮询续接可安全恢复。 */
    boolean markHitlContinuationReady(HitlDecisionFence fence);

    void deleteHitlTicket(String sessionId, String toolUseId);

    void deleteHitlTicketsBySession(String sessionId);

    long deleteResolvedHitlTicketsBefore(long expiresAtCutoff);

    List<HitlTicket> listExpiredHitlTickets(long nowMillis);

    // ---- Hands 工作队列 ----

    WorkItemRecord enqueueWork(String sessionId, String environmentId, String ownerId);

    Optional<WorkItemRecord> claimWork(String environmentId, String workerId, long timeoutMs)
            throws InterruptedException;

    void ackWork(String leaseId, String workerId, String workDir);

    void stopWork(String leaseId);

    void heartbeatWork(String leaseId);

    Optional<WorkItemRecord> getWork(String leaseId);

    List<WorkItemRecord> listWork(String environmentId, String statusFilter);

    WorkStats workStats(String environmentId);

    void workerHeartbeat(String workerId, String capabilitiesJson);

    Map<String, Long> workerHeartbeats();

    int pendingWorkCount();

    Optional<SandboxReadyMeta> getSandboxReady(String sessionId);

    void clearSandboxReady(String sessionId);

    /** 不透明的轮次租约快照。 */
    record LeaseHandle(
            String sessionId, String ownerId, String instanceId, long acquiredAt, long expiresAt) {}

    record TurnInterruptRequest(String reason, String fenceToken) {}

    /** Brain 副本之间共享的 HITL 确认工单。 */
    record HitlTicket(
            String toolUseId,
            String sessionId,
            String ownerId,
            String approvalId,
            String agentTaskId,
            String attemptId,
            long dispatchGeneration,
            String turnId,
            String continuationLeaseId,
            String toolName,
            String inputJson,
            String resolutionStatus,
            long decisionVersion,
            Boolean resolvedAllow,
            String denyMessage,
            long createdAt,
            long expiresAt,
            Long resolvedAt,
            boolean continuationReady) {

        public boolean managedTask() {
            return agentTaskId != null && !agentTaskId.isBlank();
        }
    }

    /** 决定托管 AgentTask 确认结果所需的完整关联栅栏。 */
    record HitlDecisionFence(
            String sessionId,
            String toolUseId,
            String approvalId,
            String agentTaskId,
            String attemptId,
            long dispatchGeneration,
            String turnId) {}

    /** 原子决定结果；{@code changed=false} 表示安全的幂等重放。 */
    record HitlResolution(HitlTicket ticket, boolean changed) {}

    /** 持久化的 Hands 工作队列行。 */
    record WorkItemRecord(
            String leaseId,
            String sessionId,
            String environmentId,
            String ownerId,
            String status,
            String claimedBy,
            String workDir,
            long createdAt,
            long updatedAt) {}

    /** 按环境统计的工作队列计数器。 */
    record WorkStats(Map<String, Long> countsByStatus, Long oldestQueuedAgeMs) {}

    /** 工作进程已为会话准备好沙箱的元数据（沙箱对象仍保留在本地）。 */
    record SandboxReadyMeta(
            String sessionId, String workerId, String workDir, String leaseId, long readyAt) {}
}
