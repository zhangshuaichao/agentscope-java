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
package io.agentscope.builder.web.toolbus;

import io.agentscope.builder.control.ControlPlaneClient;
import io.agentscope.builder.control.ControlPlaneClient.ManagedExecutionScope;
import io.agentscope.builder.web.coord.CoordinationStore;
import io.agentscope.builder.web.managed.DataSessionService;
import io.agentscope.builder.web.managed.ManagedSessionDto;
import io.agentscope.builder.web.managed.ManagedTurnContext;
import io.agentscope.builder.web.managed.SessionEventDto;
import io.agentscope.builder.web.managed.SessionEventTypes;
import io.agentscope.builder.web.managed.service.SessionEventLog;
import io.agentscope.core.util.JsonUtils;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 协调人工介入的工具确认。
 * <p>持久化票据是决定权威。Managed AgentTask 票据捕获完整 Attempt 隔离令牌，只能由携带相同令牌的控制平面回调解析。个人聊天确认仍可由用户寻址，但也限定于会话 ID。本地 future 只是续执行句柄，通过轮询共享票据让任意副本都能提交决定。
 */
@Component
public class ToolConfirmationCoordinator {

    private static final Logger log = LoggerFactory.getLogger(ToolConfirmationCoordinator.class);
    private static final UUID UUID_NAMESPACE_URL =
            UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8");

    private final SessionEventLog eventLog;
    private final DataSessionService sessionService;
    private final CoordinationStore coordinationStore;
    private final ControlPlaneClient controlPlaneClient;
    private final long timeoutMs;
    private final ConcurrentHashMap<String, CompletableFuture<Boolean>> localWaiters =
            new ConcurrentHashMap<>();

    public ToolConfirmationCoordinator(
            SessionEventLog eventLog,
            DataSessionService sessionService,
            CoordinationStore coordinationStore,
            ControlPlaneClient controlPlaneClient,
            @Value("${builder.tool-confirmation.timeout-ms:3600000}") long timeoutMs) {
        this.eventLog = eventLog;
        this.sessionService = sessionService;
        this.coordinationStore = coordinationStore;
        this.controlPlaneClient = controlPlaneClient;
        this.timeoutMs = timeoutMs;
    }

    public CompletableFuture<Boolean> requestConfirmation(
            String sessionId, String toolUseId, String toolName, Map<String, Object> input) {
        return requestConfirmation(sessionId, toolUseId, toolName, input, null);
    }

    /**
     * 根据 RuntimeContext 携带的不可变轮次上下文创建确认。托管请求绝不能从可变的会话全局状态重新发现作用域。
     */
    public CompletableFuture<Boolean> requestConfirmation(
            String sessionId,
            String toolUseId,
            String toolName,
            Map<String, Object> input,
            ManagedTurnContext turnContext) {
        long now = System.currentTimeMillis();
        long expiresAt = now + timeoutMs;
        String ownerId = resolveOwner(sessionId);
        ManagedExecutionScope scope = turnContext != null ? turnContext.scope() : null;
        boolean managed = turnContext != null;
        if (managed && !completeManagedScope(scope)) {
            throw new IllegalStateException(
                    "Managed tool confirmation requires a complete immutable execution scope");
        }
        String continuationLeaseId = managed ? turnContext.continuationLeaseId() : null;
        if (managed) {
            if (continuationLeaseId == null || continuationLeaseId.isBlank()) {
                throw new IllegalStateException(
                        "Managed tool confirmation requires a captured continuation lease");
            }
            String capturedLeaseId = continuationLeaseId;
            boolean exactLeaseActive =
                    coordinationStore
                            .getTurnLease(sessionId)
                            .map(CoordinationStore.LeaseHandle::instanceId)
                            .filter(capturedLeaseId::equals)
                            .isPresent();
            if (!exactLeaseActive) {
                throw new IllegalStateException(
                        "Managed tool confirmation continuation lease is no longer active");
            }
        }
        CoordinationStore.HitlTicket requestedTicket =
                new CoordinationStore.HitlTicket(
                        toolUseId,
                        sessionId,
                        ownerId,
                        managed
                                ? managedApprovalId(scope, sessionId, toolUseId)
                                : UUID.randomUUID().toString(),
                        managed ? scope.agentTaskId() : null,
                        managed ? scope.attemptId() : null,
                        managed ? scope.dispatchGeneration() : 0L,
                        managed ? scope.turnId() : null,
                        continuationLeaseId,
                        toolName,
                        serializeInput(input),
                        null,
                        0L,
                        null,
                        null,
                        now,
                        expiresAt,
                        null,
                        false);
        CoordinationStore.HitlTicket ticket = coordinationStore.putHitlTicket(requestedTicket);

        CompletableFuture<Boolean> future = new CompletableFuture<>();
        localWaiters.put(waiterKey(ticket), future);

        Map<String, Object> payload = ticketPayload(ticket);
        if (input != null) {
            payload.put("inputPreview", redactPreview(input, 0));
        }
        if (ticket.inputJson() != null) {
            payload.put("inputSha256", sha256(ticket.inputJson()));
        }
        eventLog.appendScoped(
                sessionId,
                SessionEventTypes.SESSION_REQUIRES_ACTION,
                payload,
                null,
                ControlPlaneClient.eventScope(scope));

        Thread poller =
                new Thread(() -> pollUntilResolved(ticket, future), "hitl-poll-" + toolUseId);
        poller.setDaemon(true);
        poller.start();

        Thread expiry =
                new Thread(() -> expireAtDeadline(ticket, future), "hitl-expiry-" + toolUseId);
        expiry.setDaemon(true);
        expiry.start();
        if (ownerId != null) {
            try {
                sessionService.updateStatus(
                        ownerId,
                        sessionId,
                        DataSessionService.STATUS_REQUIRES_ACTION,
                        payload,
                        scope);
            } catch (RuntimeException ex) {
                // 持久化请求发件箱／投影器始终是权威。在票据和请求事件
                // 已经存在之后，不能因故障而默认放行。
                log.warn(
                        "Could not project HITL requires_action status: session={}, error={}",
                        sessionId,
                        ex.getMessage());
            }
        }
        return future;
    }

    /**
     * 所属会话删除时，移除全部原始确认输入。
     */
    public void deleteSessionTickets(String sessionId) {
        cancelSession(sessionId, "session_deleted");
    }

    /**
     * 清除已失效／中断轮次拥有的持久化票据，并释放等待者。
     */
    public void cancelSession(String sessionId, String reason) {
        try {
            // 会话至多有一个活动物理轮次。该轮次失效后，其任何票据都
            // 不能接受迟到回调，也不能与复用 toolUseId 的新 Attempt
            // 冲突。持久化会话事件保留作审计记录，原始工具输入则不保留。
            coordinationStore.deleteHitlTicketsBySession(sessionId);
        } catch (RuntimeException ex) {
            // 仍须解除本地轮次阻塞。resolveManaged 还要求共享轮次租约有效，
            // 因此残留行无法在取消后授权执行。
            log.warn(
                    "Could not purge invalidated HITL tickets: session={}, error={}",
                    sessionId,
                    ex.getMessage());
        }
        String prefix = sessionId + "\u0000";
        localWaiters.forEach(
                (key, future) -> {
                    if (key.startsWith(prefix) && localWaiters.remove(key, future)) {
                        future.complete(false);
                    }
                });
        log.debug("Cancelled local HITL waiters for session {}: {}", sessionId, reason);
    }

    /**
     * HTTP 层认证会话所有者后，解析个人聊天确认。
     */
    public DecisionResult resolvePersonal(
            String sessionId, String toolUseId, boolean allow, String denyMessage) {
        Optional<CoordinationStore.HitlTicket> found =
                coordinationStore.getHitlTicket(sessionId, toolUseId);
        if (found.isEmpty()) {
            return DecisionResult.NOT_FOUND;
        }
        CoordinationStore.HitlTicket ticket = found.get();
        if (ticket.managedTask()) {
            return DecisionResult.CONTROL_PLANE_REQUIRED;
        }
        return resolve(
                ticket,
                allow ? "approved" : "rejected",
                0L,
                allow,
                denyMessage,
                "personal_chat",
                System.currentTimeMillis());
    }

    /**
     * 以完整隔离令牌解析来自控制平面的 Managed AgentTask 确认。
     */
    public DecisionResult resolveManaged(ManagedDecision decision) {
        Optional<CoordinationStore.HitlTicket> found =
                coordinationStore.getHitlTicket(fence(decision));
        if (found.isEmpty()) {
            found = coordinationStore.getHitlTicket(decision.sessionId(), decision.toolUseId());
            if (found.isEmpty()) {
                return DecisionResult.NOT_FOUND;
            }
        }
        CoordinationStore.HitlTicket ticket = found.get();
        if (!ticket.managedTask() || !matches(ticket, decision)) {
            return DecisionResult.FENCE_MISMATCH;
        }
        // 持久化票据可能比创建它的 JVM 续执行句柄存活更久。在接受
        // 首次投递或重试尚未到达 continuationReady 的副作用前，
        // 必须要求共享轮次租约仍有效。已经完全释放的决定，在轮次
        // 结束且租约被移除后仍保持安全幂等。
        // 【学习提示】持久化审批决定与 JVM 中继续执行是两步；租约检查防止旧票据唤醒已被替代的执行。
        if (!ticket.continuationReady()) {
            Optional<CoordinationStore.LeaseHandle> currentLease =
                    coordinationStore.getTurnLease(ticket.sessionId());
            if (currentLease.isEmpty()
                    || !same(ticket.continuationLeaseId(), currentLease.get().instanceId())) {
                return DecisionResult.CONTINUATION_LOST;
            }
        }
        Optional<CoordinationStore.HitlResolution> resolved =
                coordinationStore.resolveHitlTicket(
                        fence(ticket),
                        decision.status(),
                        decision.decisionVersion(),
                        decision.allow(),
                        decision.reason(),
                        System.currentTimeMillis());
        if (resolved.isEmpty()) {
            Optional<CoordinationStore.HitlTicket> current =
                    coordinationStore.getHitlTicket(fence(ticket));
            return current.isPresent() && current.get().resolvedAllow() != null
                    ? DecisionResult.CONFLICT
                    : DecisionResult.FENCE_MISMATCH;
        }
        CoordinationStore.HitlResolution resolution = resolved.get();
        if (resolution.ticket().continuationReady()) {
            completeLocal(
                    resolution.ticket(), Boolean.TRUE.equals(resolution.ticket().resolvedAllow()));
            return resolution.changed() ? DecisionResult.RESOLVED : DecisionResult.IDEMPOTENT;
        }
        CompletableFuture<Boolean> ownerWaiter = localWaiters.get(waiterKey(resolution.ticket()));
        if (ownerWaiter == null) {
            // 回调可能落到任意副本。此处持久化决定，但只有持有实际续执行
            // 句柄的 JVM 才能确认／释放它。该 JVM 的轮询器完成
            // 副作用；CP 发件箱持续重试，直到 continuationReady 可见。
            return DecisionResult.OWNER_UNAVAILABLE;
        }
        DecisionResult ownerResult = finishManagedOnOwner(resolution.ticket(), ownerWaiter);
        if (ownerResult != null) {
            return ownerResult;
        }
        return resolution.changed() ? DecisionResult.RESOLVED : DecisionResult.IDEMPOTENT;
    }

    /**
     * 成功解析后返回稳定的持久化决定事件。
     */
    public Optional<SessionEventDto> resolutionEvent(String sessionId, String toolUseId) {
        return coordinationStore
                .getHitlTicket(sessionId, toolUseId)
                .filter(ticket -> ticket.resolvedAllow() != null)
                .map(
                        ticket ->
                                eventLog.appendIdempotentScoped(
                                        sessionId,
                                        SessionEventTypes.USER_TOOL_CONFIRMATION,
                                        resolutionPayload(
                                                ticket,
                                                ticket.managedTask()
                                                        ? "control_plane"
                                                        : "personal_chat"),
                                        resolutionEventId(ticket),
                                        ControlPlaneClient.eventScope(managedScope(ticket))));
    }

    /**
     * 使单个待处理票据过期，并将活动续执行恢复为拒绝工具调用。
     */
    public DecisionResult expire(String sessionId, String toolUseId, long now) {
        Optional<CoordinationStore.HitlTicket> found =
                coordinationStore.getHitlTicket(sessionId, toolUseId);
        if (found.isEmpty()) {
            return DecisionResult.NOT_FOUND;
        }
        // Managed AgentTask 的超时／取消是由 CP 管理的 Approval 状态转换。
        // 本地时钟绝不能与仍待处理的人工决定竞争，产生不同审计事实。
        if (found.get().managedTask()) {
            return DecisionResult.CONTROL_PLANE_REQUIRED;
        }
        Optional<CoordinationStore.HitlResolution> resolved =
                coordinationStore.expireHitlTicket(sessionId, toolUseId, now);
        if (resolved.isEmpty()) {
            return coordinationStore.getHitlTicket(sessionId, toolUseId).isPresent()
                    ? DecisionResult.NOT_YET_EXPIRED
                    : DecisionResult.NOT_FOUND;
        }
        CoordinationStore.HitlResolution resolution = resolved.get();
        finishResolution(resolution.ticket(), "timeout");
        return resolution.changed() ? DecisionResult.RESOLVED : DecisionResult.IDEMPOTENT;
    }

    public boolean awaitConfirmation(
            String sessionId, String toolUseId, String toolName, Map<String, Object> input) {
        return awaitConfirmation(sessionId, toolUseId, toolName, input, null);
    }

    public boolean awaitConfirmation(
            String sessionId,
            String toolUseId,
            String toolName,
            Map<String, Object> input,
            ManagedTurnContext turnContext) {
        CompletableFuture<Boolean> future = null;
        try {
            future = requestConfirmation(sessionId, toolUseId, toolName, input, turnContext);
            return future.get();
        } catch (Exception ex) {
            if (future != null) {
                future.cancel(true);
            }
            CompletableFuture<Boolean> failedFuture = future;
            localWaiters.entrySet().removeIf(entry -> entry.getValue() == failedFuture);
            log.debug("Tool confirmation failed for {}: {}", toolUseId, ex.getMessage());
            return false;
        }
    }

    /**
     * 等待决定，并在智能体上下文中保留人工拒绝原因。
     */
    public ConfirmationDecision awaitDecision(
            String sessionId, String toolUseId, String toolName, Map<String, Object> input) {
        return awaitDecision(sessionId, toolUseId, toolName, input, null);
    }

    public ConfirmationDecision awaitDecision(
            String sessionId,
            String toolUseId,
            String toolName,
            Map<String, Object> input,
            ManagedTurnContext turnContext) {
        boolean allow = awaitConfirmation(sessionId, toolUseId, toolName, input, turnContext);
        String reason =
                allow
                        ? null
                        : findTicket(sessionId, toolUseId, turnContext)
                                .map(CoordinationStore.HitlTicket::denyMessage)
                                .filter(value -> !value.isBlank())
                                .map(ToolConfirmationCoordinator::safeReason)
                                .orElse(null);
        return new ConfirmationDecision(allow, reason);
    }

    private DecisionResult resolve(
            CoordinationStore.HitlTicket ticket,
            String resolutionStatus,
            long decisionVersion,
            boolean allow,
            String denyMessage,
            String source,
            long now) {
        Optional<CoordinationStore.HitlResolution> resolved =
                coordinationStore.resolveHitlTicket(
                        fence(ticket), resolutionStatus, decisionVersion, allow, denyMessage, now);
        if (resolved.isEmpty()) {
            Optional<CoordinationStore.HitlTicket> current =
                    ticket.managedTask()
                            ? coordinationStore.getHitlTicket(fence(ticket))
                            : coordinationStore.getHitlTicket(
                                    ticket.sessionId(), ticket.toolUseId());
            if (current.isEmpty()) {
                return DecisionResult.NOT_FOUND;
            }
            if (current.get().resolvedAllow() != null) {
                return DecisionResult.CONFLICT;
            }
            return current.get().expiresAt() <= now
                    ? DecisionResult.EXPIRED
                    : DecisionResult.FENCE_MISMATCH;
        }
        CoordinationStore.HitlResolution resolution = resolved.get();
        finishResolution(resolution.ticket(), source);
        return resolution.changed() ? DecisionResult.RESOLVED : DecisionResult.IDEMPOTENT;
    }

    private void finishResolution(CoordinationStore.HitlTicket ticket, String source) {
        Map<String, Object> payload = resolutionPayload(ticket, source);
        eventLog.appendIdempotentScoped(
                ticket.sessionId(),
                SessionEventTypes.USER_TOOL_CONFIRMATION,
                payload,
                resolutionEventId(ticket),
                ControlPlaneClient.eventScope(managedScope(ticket)));
        if (ticket.ownerId() != null) {
            sessionService.updateStatus(
                    ticket.ownerId(),
                    ticket.sessionId(),
                    DataSessionService.STATUS_RUNNING,
                    null,
                    ticket.managedTask() ? managedScope(ticket) : null);
        }
        if (!coordinationStore.markHitlContinuationReady(fence(ticket))) {
            throw new IllegalStateException("HITL ticket changed before continuation release");
        }
        completeLocal(ticket, Boolean.TRUE.equals(ticket.resolvedAllow()));
    }

    private void pollUntilResolved(
            CoordinationStore.HitlTicket expected, CompletableFuture<Boolean> future) {
        while (!future.isDone()) {
            Optional<CoordinationStore.HitlTicket> ticket =
                    expected.managedTask()
                            ? coordinationStore.getHitlTicket(fence(expected))
                            : coordinationStore.getHitlTicket(
                                    expected.sessionId(), expected.toolUseId());
            if (ticket.isPresent() && ticket.get().resolvedAllow() != null) {
                if (ticket.get().continuationReady()) {
                    future.complete(Boolean.TRUE.equals(ticket.get().resolvedAllow()));
                    return;
                }
                if (ticket.get().managedTask()) {
                    try {
                        finishManagedOnOwner(ticket.get(), future);
                    } catch (RuntimeException ex) {
                        log.warn(
                                "Managed HITL owner could not finish decision; retrying:"
                                        + " session={}, approvalId={}, error={}",
                                ticket.get().sessionId(),
                                ticket.get().approvalId(),
                                ex.getMessage());
                    }
                }
            }
            try {
                Thread.sleep(200L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * @return 重试／过期结果；精确续执行释放后返回 {@code null}
     */
    private DecisionResult finishManagedOnOwner(
            CoordinationStore.HitlTicket ticket, CompletableFuture<Boolean> ownerWaiter) {
        synchronized (ownerWaiter) {
            if (ownerWaiter.isDone() || localWaiters.get(waiterKey(ticket)) != ownerWaiter) {
                return DecisionResult.OWNER_UNAVAILABLE;
            }
            Optional<CoordinationStore.LeaseHandle> lease =
                    coordinationStore.getTurnLease(ticket.sessionId());
            if (lease.isEmpty() || !same(ticket.continuationLeaseId(), lease.get().instanceId())) {
                return DecisionResult.CONTINUATION_LOST;
            }
            Optional<CoordinationStore.HitlTicket> current =
                    coordinationStore.getHitlTicket(fence(ticket));
            if (current.isEmpty() || current.get().resolvedAllow() == null) {
                return DecisionResult.OWNER_UNAVAILABLE;
            }
            if (!current.get().continuationReady()) {
                finishResolution(current.get(), "control_plane");
            } else {
                completeLocal(current.get(), Boolean.TRUE.equals(current.get().resolvedAllow()));
            }
            return null;
        }
    }

    private void expireAtDeadline(
            CoordinationStore.HitlTicket ticket, CompletableFuture<Boolean> future) {
        long remaining = ticket.expiresAt() - System.currentTimeMillis();
        if (remaining > 0) {
            try {
                Thread.sleep(remaining);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (!future.isDone()) {
            try {
                if (!ticket.managedTask()) {
                    expire(ticket.sessionId(), ticket.toolUseId(), System.currentTimeMillis());
                }
            } catch (RuntimeException ex) {
                // 持久化协调修复器会重试此路径。在带隔离令牌的决定持久化入队
                // 且运行时更新成功之前，绝不释放续执行。
                log.warn(
                        "HITL expiry delivery failed: session={}, toolUseId={}, error={}",
                        ticket.sessionId(),
                        ticket.toolUseId(),
                        ex.getMessage());
            }
        }
    }

    private String resolveOwner(String sessionId) {
        try {
            ManagedSessionDto session = sessionService.requireById(sessionId);
            return session.ownerId();
        } catch (Exception ex) {
            log.debug(
                    "Could not resolve session owner for HITL ticket {}: {}",
                    sessionId,
                    ex.getMessage());
            return null;
        }
    }

    private static String serializeInput(Map<String, Object> input) {
        if (input == null) {
            return null;
        }
        try {
            return JsonUtils.getJsonCodec().toJson(input);
        } catch (Exception ex) {
            return String.valueOf(input);
        }
    }

    private static Map<String, Object> ticketPayload(CoordinationStore.HitlTicket ticket) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", "tool_confirmation");
        payload.put("schemaVersion", 1);
        payload.put("sessionId", ticket.sessionId());
        payload.put("toolUseId", ticket.toolUseId());
        payload.put("toolName", ticket.toolName());
        payload.put("approvalId", ticket.approvalId());
        payload.put("requestedAt", ticket.createdAt());
        payload.put("expiresAt", ticket.expiresAt());
        if (ticket.managedTask()) {
            payload.put("agentTaskId", ticket.agentTaskId());
            payload.put("attemptId", ticket.attemptId());
            payload.put("dispatchGeneration", ticket.dispatchGeneration());
            payload.put("turnId", ticket.turnId());
        }
        return payload;
    }

    private static Map<String, Object> resolutionPayload(
            CoordinationStore.HitlTicket ticket, String source) {
        Map<String, Object> payload = ticketPayload(ticket);
        payload.put("allow", Boolean.TRUE.equals(ticket.resolvedAllow()));
        payload.put("status", ticket.resolutionStatus());
        payload.put("decisionVersion", ticket.decisionVersion());
        payload.put("source", source);
        if (ticket.denyMessage() != null && !ticket.denyMessage().isBlank()) {
            payload.put("reason", ticket.denyMessage());
            payload.put("denyMessage", ticket.denyMessage());
        }
        return payload;
    }

    private static String resolutionEventId(CoordinationStore.HitlTicket ticket) {
        return "evt_hitl_decision_" + ticket.approvalId();
    }

    private static CoordinationStore.HitlDecisionFence fence(CoordinationStore.HitlTicket ticket) {
        return new CoordinationStore.HitlDecisionFence(
                ticket.sessionId(),
                ticket.toolUseId(),
                ticket.approvalId(),
                ticket.agentTaskId(),
                ticket.attemptId(),
                ticket.dispatchGeneration(),
                ticket.turnId());
    }

    private static CoordinationStore.HitlDecisionFence fence(ManagedDecision decision) {
        return new CoordinationStore.HitlDecisionFence(
                decision.sessionId(),
                decision.toolUseId(),
                decision.approvalId(),
                decision.agentTaskId(),
                decision.attemptId(),
                decision.dispatchGeneration(),
                decision.turnId());
    }

    private static ManagedExecutionScope managedScope(CoordinationStore.HitlTicket ticket) {
        return ticket.managedTask()
                ? new ManagedExecutionScope(
                        null,
                        ticket.agentTaskId(),
                        ticket.attemptId(),
                        ticket.dispatchGeneration(),
                        ticket.turnId())
                : null;
    }

    private static String managedApprovalId(
            ManagedExecutionScope scope, String sessionId, String toolUseId) {
        // 与控制平面共享的固定 UUID 输入；保留已有审批身份。
        String name =
                "aistio:managed-hitl:v1:"
                        + scope.tenant()
                        + ":"
                        + sessionId
                        + ":"
                        + scope.attemptId()
                        + ":"
                        + scope.dispatchGeneration()
                        + ":"
                        + scope.turnId()
                        + ":"
                        + toolUseId;
        return uuidV5(UUID_NAMESPACE_URL, name).toString();
    }

    private static UUID uuidV5(UUID namespace, String name) {
        try {
            ByteBuffer namespaceBytes = ByteBuffer.allocate(16);
            namespaceBytes.putLong(namespace.getMostSignificantBits());
            namespaceBytes.putLong(namespace.getLeastSignificantBits());
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(namespaceBytes.array());
            byte[] hash = sha1.digest(name.getBytes(StandardCharsets.UTF_8));
            hash[6] = (byte) ((hash[6] & 0x0f) | 0x50);
            hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
            ByteBuffer uuidBytes = ByteBuffer.wrap(hash);
            return new UUID(uuidBytes.getLong(), uuidBytes.getLong());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-1 is unavailable", ex);
        }
    }

    private static boolean matches(CoordinationStore.HitlTicket ticket, ManagedDecision decision) {
        return same(ticket.approvalId(), decision.approvalId())
                && same(ticket.agentTaskId(), decision.agentTaskId())
                && same(ticket.attemptId(), decision.attemptId())
                && ticket.dispatchGeneration() == decision.dispatchGeneration()
                && same(ticket.turnId(), decision.turnId());
    }

    private static boolean same(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean completeManagedScope(ManagedExecutionScope scope) {
        return hasText(scope.tenant())
                && hasText(scope.agentTaskId())
                && hasText(scope.attemptId())
                && scope.dispatchGeneration() > 0
                && hasText(scope.turnId());
    }

    private static Object redactPreview(Object value, int depth) {
        if (value == null) {
            return value;
        }
        if (depth >= 5) {
            return "[TRUNCATED]";
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            int count = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (count++ >= 50) {
                    out.put("_truncated", true);
                    break;
                }
                String key = String.valueOf(entry.getKey());
                out.put(
                        key,
                        sensitiveKey(key)
                                ? "[REDACTED]"
                                : redactPreview(entry.getValue(), depth + 1));
            }
            return out;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> out = new ArrayList<>();
            for (Object item : iterable) {
                if (out.size() >= 50) {
                    out.add("[TRUNCATED]");
                    break;
                }
                out.add(redactPreview(item, depth + 1));
            }
            return out;
        }
        if (value instanceof String text && text.length() > 512) {
            return text.substring(0, 512) + "…";
        }
        return value;
    }

    private static boolean sensitiveKey(String key) {
        String normalized = key.toLowerCase(java.util.Locale.ROOT).replace("-", "_");
        return normalized.contains("password")
                || normalized.contains("secret")
                || normalized.contains("token")
                || normalized.contains("authorization")
                || normalized.contains("credential")
                || normalized.contains("api_key")
                || normalized.contains("apikey")
                || normalized.contains("private_key");
    }

    private static String sha256(String value) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private void completeLocal(CoordinationStore.HitlTicket ticket, boolean allow) {
        CompletableFuture<Boolean> local = localWaiters.remove(waiterKey(ticket));
        if (local != null) {
            local.complete(allow);
        }
    }

    private Optional<CoordinationStore.HitlTicket> findTicket(
            String sessionId, String toolUseId, ManagedTurnContext turnContext) {
        if (turnContext == null || turnContext.scope() == null) {
            return coordinationStore.getHitlTicket(sessionId, toolUseId);
        }
        ManagedExecutionScope scope = turnContext.scope();
        return coordinationStore.getHitlTicket(
                new CoordinationStore.HitlDecisionFence(
                        sessionId,
                        toolUseId,
                        managedApprovalId(scope, sessionId, toolUseId),
                        scope.agentTaskId(),
                        scope.attemptId(),
                        scope.dispatchGeneration(),
                        scope.turnId()));
    }

    private static String waiterKey(CoordinationStore.HitlTicket ticket) {
        return ticket.sessionId()
                + "\u0000"
                + (ticket.managedTask() ? ticket.approvalId() : ticket.toolUseId());
    }

    private static String safeReason(String reason) {
        return reason.length() <= 512 ? reason : reason.substring(0, 512) + "…";
    }

    public record ConfirmationDecision(boolean allow, String reason) {}

    /**
     * 带完整隔离令牌的控制平面决定回调。
     */
    public record ManagedDecision(
            String sessionId,
            String toolUseId,
            String approvalId,
            String agentTaskId,
            String attemptId,
            long dispatchGeneration,
            String turnId,
            String status,
            long decisionVersion,
            boolean allow,
            String reason) {}

    /**
     * 两个 HTTP API 共用的稳定结果，用于区分过期决定与重放决定。
     */
    public enum DecisionResult {
        RESOLVED,
        IDEMPOTENT,
        NOT_FOUND,
        FENCE_MISMATCH,
        EXPIRED,
        CONFLICT,
        CONTINUATION_LOST,
        OWNER_UNAVAILABLE,
        CONTROL_PLANE_REQUIRED,
        NOT_YET_EXPIRED
    }
}
