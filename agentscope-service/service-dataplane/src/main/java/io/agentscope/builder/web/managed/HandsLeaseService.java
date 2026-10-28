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

import io.agentscope.builder.web.managed.service.HandsMetrics;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 为 {@code self_hosted} 托管会话协调 Hands 工作队列信号。
 * <p>在 Claude 风格事件驱动模式下，Brain <em>不</em>持有活动沙箱。内置 Hands 工具仅含模式定义并挂起；Environment Worker 领取工作项，在本地执行工具，再提交 {@code user.tool_result} 恢复轮次。本服务只入队工作项供 Worker 发现会话（轮询／统计／停止），不阻塞等待本地 {@code Sandbox} 注册。
 */
@Service
public class HandsLeaseService {

    private static final Logger log = LoggerFactory.getLogger(HandsLeaseService.class);

    /**
     * 等待 Worker 接入沙箱的旧版默认时长；当前事件驱动路径不使用此等待。
     */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    private final EnvironmentWorkQueue workQueue;
    private final ExternalSandboxRegistry registry;
    private final HandsMetrics metrics;
    private final ConcurrentHashMap<String, ActiveHandsLease> activeLeasesBySession =
            new ConcurrentHashMap<>();

    public HandsLeaseService(
            EnvironmentWorkQueue workQueue,
            ExternalSandboxRegistry registry,
            HandsMetrics metrics) {
        this.workQueue = workQueue;
        this.registry = registry;
        this.metrics = metrics;
    }

    /**
     * 通知 {@code self_hosted} 会话需要 Worker。将工作项入队供轮询发现，并返回 {@link Optional#empty()}；事件驱动模式下 Brain 不使用优先级 1 的 {@code externalSandbox}。非 {@code self_hosted} 环境不入队，直接返回空。
     */
    public Optional<io.agentscope.harness.agent.sandbox.Sandbox> acquire(
            ManagedSessionDto session, EnvironmentDto environment) {
        return acquire(session, environment, DEFAULT_TIMEOUT, null);
    }

    /**
     * 获取由单个精确物理轮次拥有的 Hands 资源。
     */
    public Optional<io.agentscope.harness.agent.sandbox.Sandbox> acquire(
            ManagedSessionDto session, EnvironmentDto environment, String turnOwnerId) {
        return acquire(session, environment, DEFAULT_TIMEOUT, turnOwnerId);
    }

    /**
     * {@link #acquire(ManagedSessionDto, EnvironmentDto)} 的显式超时重载（为 API 兼容保留；Brain 不再等待本地沙箱，因此超时参数不使用）。
     */
    public Optional<io.agentscope.harness.agent.sandbox.Sandbox> acquire(
            ManagedSessionDto session, EnvironmentDto environment, Duration timeout) {
        return acquire(session, environment, timeout, null);
    }

    private Optional<io.agentscope.harness.agent.sandbox.Sandbox> acquire(
            ManagedSessionDto session,
            EnvironmentDto environment,
            Duration timeout,
            String turnOwnerId) {
        if (environment == null || !EnvironmentTypes.TYPE_SELF_HOSTED.equals(environment.type())) {
            return Optional.empty();
        }
        String sessionId = session.id();

        // 快速路径：旧版进程内沙箱仍已注册时（便于开发）优先使用，
        // 保持旧路径兼容；事件驱动 Worker 不要求此注册。
        io.agentscope.harness.agent.sandbox.Sandbox existing = registry.get(sessionId);
        if (existing != null) {
            metrics.recordAcquire(sessionId);
            return Optional.of(existing);
        }

        activeLeasesBySession.computeIfAbsent(
                sessionId,
                ignored -> {
                    EnvironmentWorkQueue.WorkItem item =
                            workQueue.enqueue(sessionId, environment.id(), session.ownerId());
                    log.debug(
                            "[hands] enqueued work item {} for session={}, environment={}"
                                    + " (event-driven; no local sandbox wait)",
                            item.leaseId(),
                            sessionId,
                            environment.id());
                    return new ActiveHandsLease(turnOwnerId, item.leaseId());
                });
        metrics.recordAcquire(sessionId);
        return Optional.empty();
    }

    /**
     * 释放会话 Hands 租约：完成未结束工作项并移除注册表条目。不调用沙箱的 {@code stop()}/{@code shutdown()}。
     */
    public void release(String sessionId) {
        if (sessionId == null) {
            return;
        }
        ActiveHandsLease lease = activeLeasesBySession.remove(sessionId);
        if (lease != null) {
            workQueue.stop(lease.workLeaseId());
        }
        registry.remove(sessionId);
        metrics.recordRelease(sessionId);
    }

    /**
     * 仅当调用方仍拥有此会话 Hands 工作项时才释放。
     */
    public void release(String sessionId, String expectedTurnOwnerId) {
        if (sessionId == null) {
            return;
        }
        AtomicBoolean removed = new AtomicBoolean(false);
        activeLeasesBySession.computeIfPresent(
                sessionId,
                (ignored, active) -> {
                    if (!Objects.equals(active.turnOwnerId(), expectedTurnOwnerId)) {
                        return active;
                    }
                    workQueue.stop(active.workLeaseId());
                    removed.set(true);
                    return null;
                });
        if (removed.get()) {
            registry.remove(sessionId);
            metrics.recordRelease(sessionId);
        }
    }

    private record ActiveHandsLease(String turnOwnerId, String workLeaseId) {}
}
