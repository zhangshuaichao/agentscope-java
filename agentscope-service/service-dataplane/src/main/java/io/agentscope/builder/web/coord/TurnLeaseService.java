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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 获取托管会话的在途轮次租约并发送心跳。不进行会话粘性路由，只互斥跨 Brain 副本的并发轮次。心跳也消费 {@link CoordinationStore} 中的跨副本中断票据。
 */
@Service
public class TurnLeaseService {

    private static final Logger log = LoggerFactory.getLogger(TurnLeaseService.class);

    private final CoordinationStore coordinationStore;
    private final BuilderInstanceId instanceId;
    private final Duration ttl;
    private final ScheduledExecutorService heartbeatScheduler =
            Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        Thread t = new Thread(r, "turn-lease-heartbeat");
                        t.setDaemon(true);
                        return t;
                    });

    @Autowired
    public TurnLeaseService(
            CoordinationStore coordinationStore,
            BuilderInstanceId instanceId,
            @Value("${builder.coord.turn-lease-ttl-seconds:90}") long ttlSeconds) {
        this(coordinationStore, instanceId, Duration.ofSeconds(Math.max(15, ttlSeconds)));
    }

    TurnLeaseService(
            CoordinationStore coordinationStore, BuilderInstanceId instanceId, Duration ttl) {
        this.coordinationStore = coordinationStore;
        this.instanceId = instanceId;
        this.ttl = ttl;
    }

    /**
     * 尝试获取轮次租约。其他实例持有有效租约时抛出 409。
     * @param onRemoteInterrupt 其他平面通过协调存储请求中断时调用（可能在心跳线程上执行）
     * @return 必须调用 {@link TurnLease#close()} 的句柄，关闭时释放租约并停止心跳
     */
    public TurnLease acquireOrConflict(
            String sessionId, String ownerId, Runnable onRemoteInterrupt) {
        return acquireOrConflictFenced(
                sessionId,
                ownerId,
                ignored -> {
                    if (onRemoteInterrupt != null) {
                        onRemoteInterrupt.run();
                    }
                });
    }

    /**
     * 获取轮次租约，其中断回调接收持久化请求隔离令牌。
     */
    public TurnLease acquireOrConflictFenced(
            String sessionId,
            String ownerId,
            Consumer<CoordinationStore.TurnInterruptRequest> onRemoteInterrupt) {
        // 上一轮仍在发布最终事件时，同一 JVM 可能收到下一条消息。
        // 若使用进程级所有者 ID，第二轮会被误认为
        // 租约续期，导致两轮重叠。因此每轮使用独立
        // 所有者 ID 进行隔离，使旧轮次清理永远不能删除
        // 或续租较新轮次的租约。
        // 【学习提示】租约标识属于物理轮次而不是 JVM；这是阻止迟到心跳和旧清理动作影响下一轮的 fencing 边界。
        String leaseOwnerId = instanceId.get() + "/" + UUID.randomUUID();
        Optional<CoordinationStore.LeaseHandle> acquired =
                coordinationStore.tryAcquireTurnLease(sessionId, ownerId, leaseOwnerId, ttl);
        if (acquired.isEmpty()) {
            Optional<CoordinationStore.LeaseHandle> holder =
                    coordinationStore.getTurnLease(sessionId);
            String owner =
                    holder.map(CoordinationStore.LeaseHandle::instanceId)
                            .orElse("another-instance");
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Session turn already in progress on instance " + owner);
        }
        AtomicReference<ScheduledFuture<?>> futureRef = new AtomicReference<>();
        AtomicReference<Consumer<CoordinationStore.TurnInterruptRequest>> interruptRef =
                new AtomicReference<>(
                        onRemoteInterrupt != null ? onRemoteInterrupt : ignored -> {});
        AtomicLong validUntil = new AtomicLong(acquired.get().expiresAt());
        ScheduledFuture<?> future =
                heartbeatScheduler.scheduleAtFixedRate(
                        () -> {
                            try {
                                boolean stillOwner =
                                        coordinationStore.heartbeatTurnLease(
                                                sessionId, leaseOwnerId, ttl);
                                if (!stillOwner) {
                                    // 成功的回调会关闭此 TurnLease 并取消
                                    // 调度器。若尚未安装准入记录，
                                    // 则在下一次时钟触发时重试，避免丢失租约
                                    // 失效信号。
                                    Consumer<CoordinationStore.TurnInterruptRequest> cb =
                                            interruptRef.get();
                                    if (cb != null) {
                                        cb.accept(
                                                new CoordinationStore.TurnInterruptRequest(
                                                        "turn_lease_lost", null));
                                    }
                                    return;
                                }
                                validUntil.set(System.currentTimeMillis() + ttl.toMillis());
                                Optional<CoordinationStore.TurnInterruptRequest> request =
                                        coordinationStore.consumeTurnInterruptRequest(sessionId);
                                if (request.isPresent()) {
                                    log.info(
                                            "Consumed remote interrupt for session {}: {}",
                                            sessionId,
                                            request.get().reason());
                                    Consumer<CoordinationStore.TurnInterruptRequest> cb =
                                            interruptRef.get();
                                    if (cb != null) {
                                        cb.accept(request.get());
                                    }
                                }
                            } catch (Exception ex) {
                                log.warn(
                                        "Turn lease heartbeat failed for {}: {}",
                                        sessionId,
                                        ex.getMessage());
                                if (System.currentTimeMillis() >= validUntil.get()) {
                                    Consumer<CoordinationStore.TurnInterruptRequest> cb =
                                            interruptRef.get();
                                    if (cb != null) {
                                        cb.accept(
                                                new CoordinationStore.TurnInterruptRequest(
                                                        "turn_lease_lost", null));
                                    }
                                }
                            }
                        },
                        ttl.toMillis() / 3,
                        ttl.toMillis() / 3,
                        TimeUnit.MILLISECONDS);
        futureRef.set(future);
        return new TurnLease(sessionId, leaseOwnerId, futureRef, interruptRef);
    }

    /**
     * @deprecated 请使用 {@link #acquireOrConflict(String, String, Runnable)}
     */
    public TurnLease acquireOrConflict(String sessionId, String ownerId) {
        return acquireOrConflict(sessionId, ownerId, () -> {});
    }

    public Optional<CoordinationStore.LeaseHandle> currentLease(String sessionId) {
        return coordinationStore.getTurnLease(sessionId);
    }

    public String localInstanceId() {
        return instanceId.get();
    }

    public final class TurnLease implements AutoCloseable {
        private final String sessionId;
        private final String leaseOwnerId;
        private final AtomicReference<ScheduledFuture<?>> heartbeat;
        private final AtomicReference<Consumer<CoordinationStore.TurnInterruptRequest>>
                onRemoteInterrupt;

        private TurnLease(
                String sessionId,
                String leaseOwnerId,
                AtomicReference<ScheduledFuture<?>> heartbeat,
                AtomicReference<Consumer<CoordinationStore.TurnInterruptRequest>>
                        onRemoteInterrupt) {
            this.sessionId = sessionId;
            this.leaseOwnerId = leaseOwnerId;
            this.heartbeat = heartbeat;
            this.onRemoteInterrupt = onRemoteInterrupt;
        }

        public String sessionId() {
            return sessionId;
        }

        public String instanceId() {
            return TurnLeaseService.this.instanceId.get();
        }

        /**
         * 此物理轮次在共享存储中的唯一所有者令牌。
         */
        public String coordinationId() {
            return leaseOwnerId;
        }

        @Override
        public void close() {
            onRemoteInterrupt.set(null);
            ScheduledFuture<?> f = heartbeat.getAndSet(null);
            if (f != null) {
                f.cancel(false);
            }
            coordinationStore.releaseTurnLease(sessionId, leaseOwnerId);
        }
    }
}
