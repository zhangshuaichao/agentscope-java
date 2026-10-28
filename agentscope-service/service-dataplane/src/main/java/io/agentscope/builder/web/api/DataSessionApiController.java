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
package io.agentscope.builder.web.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.builder.web.api.error.ApiException;
import io.agentscope.builder.web.auth.InternalTokenAuthFilter;
import io.agentscope.builder.web.managed.DataSessionService;
import io.agentscope.builder.web.managed.LegacySessionEventAdapter;
import io.agentscope.builder.web.managed.ManagedSessionDto;
import io.agentscope.builder.web.managed.SessionEventDto;
import io.agentscope.builder.web.managed.SessionEventPreviewBus;
import io.agentscope.builder.web.managed.SessionEventTypes;
import io.agentscope.builder.web.managed.SessionTurnRunner;
import io.agentscope.builder.web.managed.service.HandsMetrics;
import io.agentscope.builder.web.managed.service.SessionEventLog;
import io.agentscope.builder.web.toolbus.ToolConfirmationCoordinator;
import io.agentscope.core.message.ToolResultBlock;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * 数据平面会话 API：用户入站事件（消息、中断、工具确认、工具结果）、持久化事件日志、SSE 流及 Hands 指标。
 * <p>会话生命周期（创建、列表、获取、归档、删除）由控制平面管理，共用 {@code /api/sessions} 路径前缀，网关将这些调用路由至控制平面。
 * <p>通过 {@link DataSessionService#get} 按<b>会话所有权</b>授权：控制平面解析结果中的 {@code ownerId} 必须匹配 JWT 用户。产品智能体如今位于 service-controlplane 模式中，因此数据平面 JPA {@code AgentAccessGuard} 查询即使面对有效会话也会得到 404；智能体 RUN/EDIT 权限已在创建会话时检查。
 */
@RestController
@RequestMapping("/api/sessions")
public class DataSessionApiController {

    /**
     * 将阻塞式 CP 解析调度到 Netty 事件循环之外。
     */
    private static final Scheduler BLOCKING = Schedulers.boundedElastic();

    private final DataSessionService sessionService;
    private final SessionEventLog eventLog;
    private final SessionEventPreviewBus previewBus;
    private final ToolConfirmationCoordinator confirmationCoordinator;
    private final SessionTurnRunner turnRunner;
    private final ObjectMapper objectMapper;
    private final HandsMetrics handsMetrics;

    public DataSessionApiController(
            DataSessionService sessionService,
            SessionEventLog eventLog,
            SessionEventPreviewBus previewBus,
            ToolConfirmationCoordinator confirmationCoordinator,
            SessionTurnRunner turnRunner,
            ObjectMapper objectMapper,
            HandsMetrics handsMetrics) {
        this.sessionService = sessionService;
        this.eventLog = eventLog;
        this.previewBus = previewBus;
        this.confirmationCoordinator = confirmationCoordinator;
        this.turnRunner = turnRunner;
        this.objectMapper = objectMapper;
        this.handsMetrics = handsMetrics;
    }

    /**
     * 提交用户入站事件（消息、中断、工具确认）。
     */
    @PostMapping("/{id}/events")
    public Mono<List<SessionEventDto>> postEvents(
            @PathVariable("id") String id,
            @RequestBody PostEventsRequest body,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                        () -> {
                            sessionService.get(userId, id);
                            if (body.events() == null || body.events().isEmpty()) {
                                throw new ResponseStatusException(
                                        HttpStatus.BAD_REQUEST, "events is required");
                            }
                            List<SessionEventDto> recorded = new java.util.ArrayList<>();
                            for (InboundEvent event : body.events()) {
                                recorded.add(handleInboundEvent(userId, id, event));
                            }
                            return recorded;
                        })
                .subscribeOn(BLOCKING);
    }

    /**
     * 列出持久化会话事件，可指定序列游标之后的事件及／或按 {@code types} 过滤（可重复查询参数，等价于 Claude Managed Agents 的 {@code types[]}）。
     */
    @GetMapping("/{id}/events")
    public Mono<List<SessionEventDto>> listEvents(
            @PathVariable("id") String id,
            @RequestParam(value = "after", required = false) Long after,
            @RequestParam(value = "types", required = false) List<String> types,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                        () -> {
                            sessionService.get(userId, id);
                            return eventLog.listAfter(id, after == null ? 0 : after, null).stream()
                                    .map(LegacySessionEventAdapter::adapt)
                                    .filter(
                                            event ->
                                                    types == null
                                                            || types.isEmpty()
                                                            || types.contains(event.type()))
                                    .toList();
                        })
                .subscribeOn(BLOCKING);
    }

    /**
     * 删除会话的全部持久化事件。供控制平面删除产品会话后执行尽力级联。内部令牌调用方可在会话行已不存在时删除；用户 JWT 调用方仍须拥有可解析的会话。
     * <p>只有内部调用才释放会话进程内运行状态，因为只有此时会话本身已删除。用户清空自己的记录后会话仍存活，必须保留团队路由与缓存智能体。
     */
    @DeleteMapping("/{id}/events")
    public Mono<Void> deleteEvents(@PathVariable("id") String id, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        boolean internal =
                auth.getAuthorities().stream()
                        .anyMatch(
                                a ->
                                        InternalTokenAuthFilter.ROLE_INTERNAL.equals(
                                                a.getAuthority()));
        return Mono.fromRunnable(
                        () -> {
                            if (internal) {
                                eventLog.purgeDeletedSession(id);
                                confirmationCoordinator.deleteSessionTickets(id);
                                turnRunner.releaseSession(id, userId);
                            } else {
                                sessionService.get(userId, id);
                                eventLog.deleteBySessionId(id);
                            }
                        })
                .subscribeOn(BLOCKING)
                .then();
    }

    /**
     * 返回该会话 Hands（沙箱租约）的获取、释放和超时计数。
     */
    @GetMapping("/{id}/hands-stats")
    public Mono<HandsMetrics.Snapshot> handsStats(
            @PathVariable("id") String id, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                        () -> {
                            sessionService.get(userId, id);
                            return handsMetrics.snapshot(id);
                        })
                .subscribeOn(BLOCKING);
    }

    /**
     * 仍接受旧预览目标值，但只生成公开文本预览。
     */
    private static final Set<String> ALLOWED_EVENT_DELTAS =
            Set.of(
                    SessionEventTypes.AGENT_MESSAGE,
                    SessionEventTypes.AGENT_THINKING,
                    SessionEventTypes.AGENT_TOOL_USE);

    /**
     * 通过 SSE 流式发送会话事件，可选合并仅用于流的预览增量。
     */
    @GetMapping(value = "/{id}/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> streamEvents(
            @PathVariable("id") String id,
            @RequestParam(value = "after", required = false) Long after,
            @RequestParam(value = "event_deltas", required = false) List<String> eventDeltas,
            @RequestHeader(value = "Last-Event-ID", required = false) Long lastEventId,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        if (eventDeltas != null) {
            if (eventDeltas.size() > 100) {
                return Flux.error(
                        new ResponseStatusException(
                                HttpStatus.BAD_REQUEST, "event_deltas accepts at most 100 values"));
            }
            for (String t : eventDeltas) {
                if (!ALLOWED_EVENT_DELTAS.contains(t)) {
                    return Flux.error(
                            new ResponseStatusException(
                                    HttpStatus.BAD_REQUEST,
                                    "unsupported event_deltas value: "
                                            + t
                                            + " (allowed: agent.message, agent.thinking,"
                                            + " agent.tool_use)"));
                }
            }
        }

        long afterSeq =
                Math.max(after != null ? after : 0L, lastEventId != null ? lastEventId : 0L);
        return Mono.fromCallable(
                        () -> {
                            sessionService.get(userId, id);
                            return Boolean.TRUE;
                        })
                .subscribeOn(BLOCKING)
                .flatMapMany(
                        ignored -> {
                            Flux<SessionEventDto> persisted =
                                    eventLog.subscribe(id, afterSeq)
                                            .map(LegacySessionEventAdapter::adapt);
                            if (eventDeltas == null || eventDeltas.isEmpty()) {
                                return persisted.map(this::toSse);
                            }
                            Set<String> deltaTypes = new HashSet<>(eventDeltas);
                            Flux<SessionEventDto> previews =
                                    previewBus
                                            .subscribe(id)
                                            .map(LegacySessionEventAdapter::adapt)
                                            .filter(
                                                    dto -> {
                                                        if (dto.payload() == null) {
                                                            return false;
                                                        }
                                                        Object targetType =
                                                                dto.payload().get("type");
                                                        return targetType != null
                                                                && deltaTypes.contains(
                                                                        String.valueOf(targetType));
                                                    });
                            return Flux.merge(persisted, previews).map(this::toSse);
                        });
    }

    private SessionEventDto handleInboundEvent(
            String userId, String sessionId, InboundEvent event) {
        String type = event.type();
        if (type == null || type.isBlank()) {
            throw ApiException.invalidRequest(
                    "missing_event_type", "event.type is required", "events[].type");
        }
        Map<String, Object> payload = event.payload() != null ? event.payload() : Map.of();
        return switch (type) {
            case SessionEventTypes.USER_MESSAGE -> {
                // 保留一个小型命令准入标记，用于排序和 Endpoint 关联。
                // 消息文本只存储在已提交的原生输入事实中。
                Map<String, Object> accepted = new LinkedHashMap<>();
                accepted.put("status", "accepted");
                for (String key : List.of("endpointInvocationId", "endpointTurnId"))
                    if (payload.containsKey(key)) accepted.put(key, payload.get(key));
                AtomicReference<SessionEventDto> admitted = new AtomicReference<>();
                sessionService.runTurn(
                        userId,
                        sessionId,
                        payload,
                        () ->
                                admitted.set(
                                        eventLog.append(
                                                sessionId, "session.input_accepted", accepted)));
                yield admitted.get();
            }
            case SessionEventTypes.USER_INTERRUPT -> {
                if (payload.containsKey("run_id")) {
                    Object value = payload.get("run_id");
                    if (!(value instanceof String runId) || runId.isBlank()) {
                        throw ApiException.invalidRequest(
                                "invalid_run_id",
                                "run_id must be a non-blank string",
                                "events[].payload.run_id");
                    }
                    turnRunner.interruptRun(userId, sessionId, runId);
                } else {
                    turnRunner.interrupt(sessionId);
                    sessionService.updateStatus(
                            userId, sessionId, DataSessionService.STATUS_IDLE, payload);
                }
                yield eventLog.append(sessionId, type, payload);
            }
            case SessionEventTypes.USER_TOOL_CONFIRMATION -> {
                String toolUseId = stringValue(payload.get("tool_use_id"));
                if (toolUseId == null) {
                    toolUseId = stringValue(payload.get("toolUseId"));
                }
                if (toolUseId == null) {
                    throw ApiException.invalidRequest(
                            "missing_tool_use_id",
                            "tool_use_id is required for user.tool_confirmation",
                            "events[].payload.tool_use_id");
                }
                boolean allow = Boolean.TRUE.equals(payload.get("allow"));
                String denyMessage = stringValue(payload.get("denyMessage"));
                ToolConfirmationCoordinator.DecisionResult result =
                        confirmationCoordinator.resolvePersonal(
                                sessionId, toolUseId, allow, denyMessage);
                if (result == ToolConfirmationCoordinator.DecisionResult.NOT_FOUND) {
                    throw new ResponseStatusException(
                            HttpStatus.NOT_FOUND, "Tool confirmation ticket not found");
                }
                if (result != ToolConfirmationCoordinator.DecisionResult.RESOLVED
                        && result != ToolConfirmationCoordinator.DecisionResult.IDEMPOTENT) {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            result
                                            == ToolConfirmationCoordinator.DecisionResult
                                                    .CONTROL_PLANE_REQUIRED
                                    ? "Managed AgentTask confirmations must be decided through"
                                            + " control-plane Approvals"
                                    : "Tool confirmation is stale or already decided");
                }
                yield confirmationCoordinator
                        .resolutionEvent(sessionId, toolUseId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.CONFLICT,
                                                "Tool confirmation decision event is unavailable"));
            }
            case SessionEventTypes.USER_CUSTOM_TOOL_RESULT, SessionEventTypes.USER_TOOL_RESULT -> {
                ToolResultBlock block = SessionTurnRunner.toolResultFromPayload(payload);
                ManagedSessionDto session = sessionService.get(userId, sessionId);
                turnRunner.resumeWithToolResults(session, List.of(block));
                yield new SessionEventDto(
                        null,
                        sessionId,
                        -1L,
                        "session.input_accepted",
                        Map.of("status", "accepted", "tool_use_id", block.getId()),
                        null,
                        System.currentTimeMillis());
            }
            case SessionEventTypes.USER_DEFINE_OUTCOME -> eventLog.append(sessionId, type, payload);
            case SessionEventTypes.SYSTEM_MESSAGE -> {
                String text = extractSystemText(payload);
                if (text != null && !text.isBlank()) {
                    sessionService.mergeAgentOverrides(userId, sessionId, Map.of("system", text));
                }
                yield eventLog.append(sessionId, type, payload);
            }
            default ->
                    throw ApiException.invalidRequest(
                            "unknown_event_type",
                            "Unknown inbound event type: " + type,
                            "events[].type");
        };
    }

    private static String extractSystemText(Map<String, Object> payload) {
        for (String key : List.of("text", "message", "content", "system")) {
            String value = stringValue(payload.get(key));
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private ServerSentEvent<String> toSse(SessionEventDto dto) {
        try {
            String json = objectMapper.writeValueAsString(dto);
            return ServerSentEvent.<String>builder()
                    .id(dto.seq() > 0 ? String.valueOf(dto.seq()) : null)
                    .event(dto.type())
                    .data(json)
                    .build();
        } catch (JsonProcessingException ex) {
            return ServerSentEvent.<String>builder().event("error").data("{}").build();
        }
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 批量入站事件封装。
     */
    public record PostEventsRequest(List<InboundEvent> events) {}

    /**
     * 包含类型及自由格式载荷字段的单个入站事件。
     */
    public record InboundEvent(String type, Map<String, Object> payload) {
        /**
         * 将显式载荷与事件对象的其他 JSON 字段合并。
         */
        public InboundEvent {
            payload = payload != null ? payload : new LinkedHashMap<>();
        }
    }
}
