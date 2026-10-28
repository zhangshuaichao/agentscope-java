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
package io.agentscope.builder.web.managed.service;

import io.agentscope.builder.web.managed.SessionEventDto;
import io.agentscope.builder.web.persistence.jpa.SessionEventEntity;
import io.agentscope.builder.web.persistence.jpa.SessionEventEntityRepository;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** 仅追加的会话事件日志。通过冲突重试分配序号，使控制和数据平面可在共享 {@code (session_id, seq)} 唯一约束下并发追加。实时 SSE 扇出由通知驱动，始终从持久化序号游标读取，因此任意平面/副本写入的事件均可见，而不把通知当作数据。<p>【学习提示】通知只是“有新数据”的唤醒信号，数据库日志才是可恢复事实；重放按稳定事件 ID 幂等处理，序号负责排序，两者职责不同。 */
@Service
public class SessionEventLog {

    private static final Logger log = LoggerFactory.getLogger(SessionEventLog.class);
    private static final int MAX_SEQ_RETRIES = 16;

    private final SessionEventEntityRepository repository;
    private final ManagedJsonHelper jsonHelper;
    private final TransactionTemplate transactionTemplate;
    private final DeletedSessionRegistry deletedSessions;
    private final SessionEventNotifier notifier;
    private final List<SessionEventMirror> mirrors;
    private final long recoveryIntervalMs;

    @Autowired
    public SessionEventLog(
            SessionEventEntityRepository repository,
            ManagedJsonHelper jsonHelper,
            TransactionTemplate transactionTemplate,
            DeletedSessionRegistry deletedSessions,
            SessionEventNotifier notifier,
            List<SessionEventMirror> mirrors,
            @Value("${builder.session-event.recovery-interval-ms:30000}") long recoveryIntervalMs) {
        this.repository = repository;
        this.jsonHelper = jsonHelper;
        this.transactionTemplate = transactionTemplate;
        this.deletedSessions = deletedSessions;
        this.notifier = notifier;
        this.mirrors = mirrors != null ? List.copyOf(mirrors) : List.of();
        this.recoveryIntervalMs = Math.max(1_000L, recoveryIntervalMs);
    }

    /** 为聚焦测试和不使用事件镜像的嵌入方保留的构造方法。 */
    public SessionEventLog(
            SessionEventEntityRepository repository,
            ManagedJsonHelper jsonHelper,
            TransactionTemplate transactionTemplate,
            DeletedSessionRegistry deletedSessions,
            SessionEventNotifier notifier,
            long recoveryIntervalMs) {
        this(
                repository,
                jsonHelper,
                transactionTemplate,
                deletedSessions,
                notifier,
                List.of(),
                recoveryIntervalMs);
    }

    /** 使用下一个单调序号追加事件。另一进程并发追加导致唯一约束冲突时重试。每次尝试使用独立事务，避免约束失败使外层事务失效。 */
    public SessionEventDto append(String sessionId, String type, Map<String, Object> payload) {
        return append(sessionId, type, payload, null);
    }

    /** 追加子会话事件，不投递到控制平面。 */
    public SessionEventDto appendLocal(
            String sessionId, String type, Map<String, Object> payload, String eventId) {
        return appendInternal(sessionId, type, payload, eventId, false, null);
    }

    /** 追加事件，可复用预分配的 {@code eventId}，使流式预览能够与持久化行对齐。 */
    public SessionEventDto append(
            String sessionId, String type, Map<String, Object> payload, String eventId) {
        return appendInternal(sessionId, type, payload, eventId, true, null);
    }

    public SessionEventDto appendScoped(
            String sessionId,
            String type,
            Map<String, Object> payload,
            String eventId,
            SessionEventScope scope) {
        return appendInternal(sessionId, type, payload, eventId, true, scope);
    }

    private SessionEventDto appendInternal(
            String sessionId,
            String type,
            Map<String, Object> payload,
            String eventId,
            boolean mirror,
            SessionEventScope scope) {
        if (deletedSessions.isDeleted(sessionId)) {
            log.debug("Dropping {} event for deleted session {}", type, sessionId);
            return droppedEvent(sessionId, type, payload, eventId);
        }
        RuntimeException lastConflict = null;
        for (int attempt = 0; attempt < MAX_SEQ_RETRIES; attempt++) {
            try {
                SessionEventDto appended =
                        transactionTemplate.execute(
                                status -> {
                                    var event = appendOnce(sessionId, type, payload, eventId);
                                    if (mirror) enqueueMirrors(event, scope);
                                    return event;
                                });
                notifier.publish(sessionId);
                return appended;
            } catch (RuntimeException ex) {
                if (!isSeqConflict(ex)) {
                    throw ex;
                }
                // 并发导出器可能已提交此不可变事件。递增序号
                // 无法解决事件 ID 冲突：让 appendIdempotent 立即校验
                // 并复用胜出记录，而不是反复重试同一个 INSERT。
                if (eventId != null && repository.findByEventId(eventId).isPresent()) {
                    throw ex;
                }
                lastConflict = ex;
                log.debug(
                        "Session event seq conflict for {} (attempt {}/{}): {}",
                        sessionId,
                        attempt + 1,
                        MAX_SEQ_RETRIES,
                        ex.getMessage());
            }
        }
        throw new IllegalStateException(
                "Failed to append session event after "
                        + MAX_SEQ_RETRIES
                        + " seq retries: "
                        + sessionId,
                lastConflict);
    }

    /** 使用调用方拥有的稳定 ID 仅追加一次事件。重放时返回已有事件并重新镜像，可可靠地跨平面投递，而不重复本地对话记录。 */
    public SessionEventDto appendIdempotent(
            String sessionId, String type, Map<String, Object> payload, String eventId) {
        return appendIdempotent(sessionId, type, payload, eventId, true, null);
    }

    /** 在本地追加/重放，不使用缺少范围约束的通用镜像。 */
    public SessionEventDto appendIdempotentLocal(
            String sessionId, String type, Map<String, Object> payload, String eventId) {
        return appendIdempotent(sessionId, type, payload, eventId, false, null);
    }

    public SessionEventDto appendIdempotentScoped(
            String sessionId,
            String type,
            Map<String, Object> payload,
            String eventId,
            SessionEventScope scope) {
        return appendIdempotent(sessionId, type, payload, eventId, true, scope);
    }

    private SessionEventDto appendIdempotent(
            String sessionId,
            String type,
            Map<String, Object> payload,
            String eventId,
            boolean mirror,
            SessionEventScope scope) {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("eventId is required for idempotent append");
        }
        var existing = repository.findByEventId(eventId);
        if (existing.isEmpty()) {
            try {
                return appendInternal(sessionId, type, payload, eventId, mirror, scope);
            } catch (RuntimeException error) {
                if (repository.findByEventId(eventId).isPresent())
                    return appendIdempotent(sessionId, type, payload, eventId, mirror, scope);
                throw error;
            }
        }
        if (!sessionId.equals(existing.get().getSessionId())
                || !type.equals(existing.get().getEventType())) {
            throw new IllegalStateException(
                    "eventId already belongs to another session/event type: " + eventId);
        }
        if (!Objects.equals(
                jsonHelper.readMap(existing.get().getPayloadJson()),
                jsonHelper.readMap(jsonHelper.writeJson(payload)))) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "eventId reused with different payload");
        }
        SessionEventDto replay = toDto(existing.get());
        if (mirror) {
            transactionTemplate.executeWithoutResult(status -> enqueueMirrors(replay, scope));
        }
        return replay;
    }

    /** 命令准入与其公共事实一同提交。仅在提交后发布。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public SessionEventDto appendCommand(
            String sessionId, String type, Map<String, Object> payload, String eventId) {
        var event = appendOnce(sessionId, type, payload, eventId);
        enqueueMirrors(event, null);
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        notifier.publish(sessionId);
                    }
                });
        return event;
    }

    private void enqueueMirrors(SessionEventDto event, SessionEventScope scope) {
        if (event == null || event.seq() <= 0) return;
        for (SessionEventMirror mirror : mirrors) {
            mirror.mirror(event, scope == null ? SessionEventScope.NONE : scope);
        }
    }

    private SessionEventDto appendOnce(
            String sessionId, String type, Map<String, Object> payload, String eventId) {
        long now = System.currentTimeMillis();
        long seq = repository.maxSeq(sessionId) + 1;
        SessionEventEntity entity = new SessionEventEntity();
        entity.setEventId(
                eventId != null && !eventId.isBlank()
                        ? eventId
                        : "evt_" + UUID.randomUUID().toString().replace("-", ""));
        entity.setSessionId(sessionId);
        entity.setSeq(seq);
        entity.setEventType(type);
        entity.setPayloadJson(jsonHelper.writeJson(payload));
        entity.setProcessedAt(now);
        entity.setCreatedAt(now);
        return toDto(repository.saveAndFlush(entity));
    }

    private static boolean isSeqConflict(Throwable ex) {
        Throwable cur = ex;
        while (cur != null) {
            if (cur instanceof DataIntegrityViolationException) {
                return true;
            }
            String name = cur.getClass().getName();
            if (name.contains("ConstraintViolation") || name.contains("SQLIntegrityConstraint")) {
                return true;
            }
            String message = cur.getMessage();
            if (message != null
                    && (message.contains("ux_builder_session_event_seq")
                            || message.contains("Unique index")
                            || message.contains("unique constraint"))) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    /** 按序号顺序列出会话的所有事件。 */
    @Transactional(readOnly = true)
    public List<SessionEventDto> list(String sessionId) {
        return list(sessionId, null);
    }

    /** 列出会话事件，可选地限制为 {@code types}。{@code types} 为 null 或空时返回所有类型。 */
    @Transactional(readOnly = true)
    public List<SessionEventDto> list(String sessionId, Collection<String> types) {
        if (types == null || types.isEmpty()) {
            return repository.findBySessionIdOrderBySeqAsc(sessionId).stream()
                    .map(this::toDto)
                    .toList();
        }
        return repository.findBySessionIdAndEventTypeInOrderBySeqAsc(sessionId, types).stream()
                .map(this::toDto)
                .toList();
    }

    /** 列出序号严格大于 {@code afterSeq} 的事件。 */
    @Transactional(readOnly = true)
    public List<SessionEventDto> listAfter(String sessionId, long afterSeq) {
        return listAfter(sessionId, afterSeq, null);
    }

    /** 列出 {@code afterSeq} 之后的事件，可选地限制为 {@code types}。 */
    @Transactional(readOnly = true)
    public List<SessionEventDto> listAfter(
            String sessionId, long afterSeq, Collection<String> types) {
        if (types == null || types.isEmpty()) {
            return listAfterUnchecked(sessionId, afterSeq);
        }
        return repository
                .findBySessionIdAndEventTypeInAndSeqGreaterThanOrderBySeqAsc(
                        sessionId, types, afterSeq)
                .stream()
                .map(this::toDto)
                .toList();
    }

    /** 在固定的已提交前缀上执行有界数据库读取。 */
    @Transactional(readOnly = true)
    public List<SessionEventDto> page(String sessionId, long after, long through, int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("limit must be 1..1000");
        return repository
                .findBySessionIdAndSeqGreaterThanAndSeqLessThanEqualOrderBySeqAsc(
                        sessionId, after, through, PageRequest.of(0, limit))
                .stream()
                .map(this::toDto)
                .toList();
    }

    /** 通过公共标识符查找单个事件。 */
    @Transactional(readOnly = true)
    public Optional<SessionEventDto> findByEventId(String eventId) {
        return repository.findByEventId(eventId).map(this::toDto);
    }

    /** 通过公共标识符查找单个事件，并要求其存在。 */
    @Transactional(readOnly = true)
    public SessionEventDto getByEventId(String eventId) {
        return findByEventId(eventId)
                .orElseThrow(
                        () ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "Event not found: " + eventId));
    }

    @Transactional(readOnly = true)
    public long highWatermark(String sessionId) {
        return repository.maxSeq(sessionId);
    }

    /** 返回用于 SSE 流的实时事件 Flux，从序号 0 之后开始。 */
    public Flux<SessionEventDto> subscribe(String sessionId) {
        return subscribe(sessionId, 0L);
    }

    /**
     * 立即触发、进程内通知或 PostgreSQL 跨副本通知后，读取序号严格大于 {@code afterSeq} 的事件。
     * 低频恢复定时信号用于应对通知丢失或数据库不支持 LISTEN/NOTIFY 的情况。
     * <p>每次轮询均在 {@link TransactionTemplate} 内运行：PostgreSQL {@code @Lob} CLOB/OID
     * 载荷读取需要事务，而通过 {@code this.} 调用 {@link #listAfter} 会绕过 Spring {@code @Transactional} 代理。
     */
    public Flux<SessionEventDto> subscribe(String sessionId, long afterSeq) {
        AtomicLong cursor = new AtomicLong(Math.max(0L, afterSeq));
        Flux<Long> recovery = Flux.interval(Duration.ofMillis(recoveryIntervalMs));
        Flux<Long> wakeups =
                Flux.merge(Flux.just(0L), recovery, notifier.wakeups(sessionId).map(ignored -> 0L))
                        .onBackpressureLatest();
        return wakeups.concatMap(
                ignored ->
                        Mono.fromCallable(
                                        () ->
                                                transactionTemplate.execute(
                                                        status -> repository.maxSeq(sessionId)))
                                .subscribeOn(Schedulers.boundedElastic())
                                .flatMapMany(
                                        through ->
                                                readCommittedPrefix(sessionId, cursor, through)));
    }

    private Flux<SessionEventDto> readCommittedPrefix(
            String sessionId, AtomicLong cursor, long through) {
        return Flux.defer(
                        () ->
                                Mono.fromCallable(
                                                () ->
                                                        transactionTemplate.execute(
                                                                status ->
                                                                        page(
                                                                                sessionId,
                                                                                cursor.get(),
                                                                                through,
                                                                                256)))
                                        .subscribeOn(Schedulers.boundedElastic())
                                        .flatMapMany(
                                                batch -> {
                                                    if (batch == null || batch.isEmpty()) {
                                                        cursor.set(through);
                                                        return Flux.empty();
                                                    }
                                                    cursor.set(batch.get(batch.size() - 1).seq());
                                                    return Flux.fromIterable(batch);
                                                }))
                .repeat(() -> cursor.get() < through);
    }

    /** 供事务入口及可恢复订阅使用的仓库读取方法。 */
    private List<SessionEventDto> listAfterUnchecked(String sessionId, long afterSeq) {
        return repository
                .findBySessionIdAndSeqGreaterThanOrderBySeqAsc(sessionId, afterSeq)
                .stream()
                .map(this::toDto)
                .toList();
    }

    /** 删除仍继续运行的会话的全部持久化事件（清空对话记录）。 */
    @Transactional
    public void deleteBySessionId(String sessionId) {
        repository.deleteBySessionId(sessionId);
    }

    /**
     * 删除会话事件并拒绝此后为它追加事件。会话行本身已删除时，应使用此方法而非 {@link
     * #deleteBySessionId}，防止正在退出的轮次遗留孤儿行。
     */
    public void purgeDeletedSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        deletedSessions.markDeleted(sessionId);
        transactionTemplate.executeWithoutResult(status -> repository.deleteBySessionId(sessionId));
    }

    /** 代表因会话已消失而未写入的事件。调用方会将结果回显到 HTTP/SSE，因此 {@code seq} 为 {@code -1} 标记它从未持久化。 */
    private SessionEventDto droppedEvent(
            String sessionId, String type, Map<String, Object> payload, String eventId) {
        long now = System.currentTimeMillis();
        String id =
                eventId != null && !eventId.isBlank()
                        ? eventId
                        : "evt_" + UUID.randomUUID().toString().replace("-", "");
        return new SessionEventDto(id, sessionId, -1L, type, payload, now, now);
    }

    private SessionEventDto toDto(SessionEventEntity entity) {
        return new SessionEventDto(
                entity.getEventId(),
                entity.getSessionId(),
                entity.getSeq(),
                entity.getEventType(),
                jsonHelper.readMap(entity.getPayloadJson()),
                entity.getProcessedAt(),
                entity.getCreatedAt());
    }
}
