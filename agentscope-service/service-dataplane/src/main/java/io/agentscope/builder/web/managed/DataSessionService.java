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
package io.agentscope.builder.web.managed;

import io.agentscope.builder.control.ControlPlaneClient;
import io.agentscope.builder.control.ControlPlaneClient.ManagedExecutionScope;
import io.agentscope.builder.control.SessionResolveResult;
import io.agentscope.builder.web.managed.service.ManagedJsonHelper;
import io.agentscope.builder.web.managed.service.SessionEventLog;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 数据平面会话服务：为控制平面创建且固定版本的会话执行状态转换、覆盖配置合并和轮次运行。
 * <p>会话行位于控制平面模式中。本服务通过 {@link ControlPlaneClient} 读取和更新，并保留本地 {@link SessionEventLog} 用于记录及 SSE 扇出。
 */
@Service
public class DataSessionService {

    private static final Logger log = LoggerFactory.getLogger(DataSessionService.class);

    /**
     * @deprecated 请使用 {@link SessionStatuses#CREATED}
     */
    public static final String STATUS_CREATED = SessionStatuses.CREATED;

    /**
     * @deprecated 请使用 {@link SessionStatuses#RUNNING}
     */
    public static final String STATUS_RUNNING = SessionStatuses.RUNNING;

    /**
     * @deprecated 请使用 {@link SessionStatuses#IDLE}
     */
    public static final String STATUS_IDLE = SessionStatuses.IDLE;

    /**
     * @deprecated 请使用 {@link SessionStatuses#REQUIRES_ACTION}
     */
    public static final String STATUS_REQUIRES_ACTION = SessionStatuses.REQUIRES_ACTION;

    /**
     * @deprecated 请使用 {@link SessionStatuses#TERMINATED}
     */
    public static final String STATUS_TERMINATED = SessionStatuses.TERMINATED;

    /**
     * @deprecated 请使用 {@link SessionStatuses#RESCHEDULED}
     */
    public static final String STATUS_RESCHEDULED = SessionStatuses.RESCHEDULED;

    /**
     * @deprecated 请使用 {@link SessionStatuses#ARCHIVED}
     */
    public static final String STATUS_ARCHIVED = SessionStatuses.ARCHIVED;

    private final ControlPlaneClient controlPlaneClient;
    private final SessionEventLog eventLog;
    private final ManagedJsonHelper jsonHelper;
    private final SessionTurnRunner turnRunner;

    public DataSessionService(
            ControlPlaneClient controlPlaneClient,
            SessionEventLog eventLog,
            ManagedJsonHelper jsonHelper,
            SessionTurnRunner turnRunner) {
        this.controlPlaneClient = controlPlaneClient;
        this.eventLog = eventLog;
        this.jsonHelper = jsonHelper;
        this.turnRunner = turnRunner;
    }

    /**
     * 返回调用方拥有的会话。
     */
    public ManagedSessionDto get(String ownerId, String sessionId) {
        ManagedSessionDto session = requireById(sessionId);
        if (!ownerId.equals(session.ownerId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Session access denied");
        }
        return session;
    }

    /**
     * 按 ID 查询会话，不按所有者限制（供 Worker／内部使用）。
     */
    public ManagedSessionDto requireById(String sessionId) {
        return controlPlaneClient.resolveSession(sessionId).session();
    }

    /**
     * 通过控制平面更新会话状态及可选停止原因元数据。
     */
    public ManagedSessionDto updateStatus(
            String ownerId, String sessionId, String status, Map<String, Object> stopReason) {
        return updateStatus(ownerId, sessionId, status, stopReason, null);
    }

    /**
     * 使用产生更新的物理轮次的不可变隔离令牌更新状态。
     */
    public ManagedSessionDto updateStatus(
            String ownerId,
            String sessionId,
            String status,
            Map<String, Object> stopReason,
            ManagedExecutionScope scope) {
        if (SessionStatuses.isLifecycleStatus(status)
                && !SessionStatuses.TERMINATED.equals(status)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Lifecycle status '" + status + "' is owned by the control plane");
        }
        ManagedSessionDto current = get(ownerId, sessionId);
        controlPlaneClient.patchSessionRuntime(sessionId, status, stopReason, ownerId, scope);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", status);
        if (stopReason != null) {
            payload.put("stopReason", stopReason);
        }
        eventLog.appendScoped(
                sessionId,
                "session.status_" + status,
                payload,
                null,
                ControlPlaneClient.eventScope(scope));
        long now = System.currentTimeMillis();
        return new ManagedSessionDto(
                current.id(),
                current.ownerId(),
                current.agentId(),
                current.agentOwnerId(),
                current.agentVersion(),
                current.agentRefType(),
                current.agentOverridesJson(),
                current.environmentId(),
                current.externalKey(),
                current.memoryStoreIds(),
                current.vaultIds(),
                current.resources(),
                status,
                stopReason,
                current.createdAt(),
                now,
                current.archivedAt());
    }

    /**
     * 合并会话级智能体覆盖配置。持久化覆盖配置需要控制平面 API；快速开发实现仅记录警告、追加本地事件，并原样返回当前会话。
     */
    public ManagedSessionDto mergeAgentOverrides(
            String ownerId, String sessionId, Map<String, Object> patch) {
        ManagedSessionDto session = get(ownerId, sessionId);
        log.warn(
                "mergeAgentOverrides is not persisted via control plane yet; session={},"
                        + " patchKeys={}",
                sessionId,
                patch != null ? patch.keySet() : null);
        Map<String, Object> current =
                AgentOverrideMerger.merge(jsonHelper.readMap(session.agentOverridesJson()), patch);
        eventLog.append(sessionId, SessionEventTypes.SESSION_UPDATED, Map.of("overrides", current));
        return session;
    }

    /**
     * 异步运行会话的一个 Harness 轮次。
     */
    public void runTurn(String ownerId, String sessionId, Map<String, Object> messagePayload) {
        runTurn(ownerId, sessionId, messagePayload, () -> {});
    }

    /**
     * 异步运行一个 Harness 轮次，确定会运行时调用 {@code onAdmitted}。
     */
    public void runTurn(
            String ownerId,
            String sessionId,
            Map<String, Object> messagePayload,
            Runnable onAdmitted) {
        ManagedSessionDto session = get(ownerId, sessionId);
        String userMessage = extractUserMessage(messagePayload);
        // runTurnAsync 在当前线程获取轮次租约；CONFLICT 会在
        // 状态变为 running 之前抛出。
        turnRunner.runTurnAsync(session, userMessage, onAdmitted);
    }

    /**
     * 解析完整控制平面会话载荷（快照、挂载、环境）。
     */
    public SessionResolveResult resolve(String sessionId) {
        return controlPlaneClient.resolveSession(sessionId);
    }

    static String extractUserMessage(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return "";
        }
        for (String key : List.of("text", "message", "content")) {
            Object value = payload.get(key);
            if (value != null) {
                String text = String.valueOf(value).trim();
                if (!text.isEmpty()) {
                    return text;
                }
            }
        }
        return String.valueOf(payload);
    }
}
