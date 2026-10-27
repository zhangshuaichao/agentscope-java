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
package io.agentscope.core.shutdown;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.agent.RunControl;
import io.agentscope.core.state.AgentState;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * 管理优雅关闭生命周期与活跃请求跟踪的全局管理器。
 */
public final class GracefulShutdownManager {

    private static final Logger log = LoggerFactory.getLogger(GracefulShutdownManager.class);
    private static final GracefulShutdownManager INSTANCE = new GracefulShutdownManager();

    private final AtomicReference<ShutdownState> state =
            new AtomicReference<>(ShutdownState.RUNNING);
    private final AtomicReference<GracefulShutdownConfig> config =
            new AtomicReference<>(GracefulShutdownConfig.DEFAULT);

    /**
     * 活跃请求按每次调用唯一的 {@code requestId} 索引，而不是按智能体 ID 索引：
     * 单个智能体实例可能服务多个并发调用（不同的 {@code (userId, sessionId)} 会话），
     * 必须分别跟踪、中断和保存。若使用智能体 ID 作为键，并发调用会合并成一个条目
     * （后写覆盖先写），并可能使一个调用完成时注销另一个尚在执行的调用。
     */
    private final ConcurrentHashMap<String, ActiveRequestContext> activeRequestsById =
            new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, ShutdownStateSaver> stateSavers =
            new ConcurrentHashMap<>();
    private final AtomicReference<Instant> shutdownStartedAt = new AtomicReference<>(null);
    private final AtomicBoolean monitorStarted = new AtomicBoolean(false);
    private final ScheduledExecutorService monitor =
            new ScheduledThreadPoolExecutor(
                    1,
                    r -> {
                        Thread t = new Thread(r, "agentscope-shutdown-monitor");
                        t.setDaemon(true);
                        return t;
                    });
    private final Object terminationLock = new Object();
    private final AtomicReference<ScheduledFuture<?>> monitorFuture = new AtomicReference<>();
    private final AtomicReference<Sinks.Empty<Void>> shutdownTimeoutSignal =
            new AtomicReference<>(Sinks.empty());

    private GracefulShutdownManager() {
        AgentScopeJvmShutdownHook.register(this);
    }

    public static GracefulShutdownManager getInstance() {
        return INSTANCE;
    }

    public ShutdownState getState() {
        return state.get();
    }

    public GracefulShutdownConfig getConfig() {
        return config.get();
    }

    public void setConfig(GracefulShutdownConfig config) {
        this.config.set(config);
    }

    /**
     * 返回一个在达到关闭超时时完成的 {@link Mono}。
     * 工具执行器可以让执行过程与此信号竞争，以提前中止。
     */
    public Mono<Void> getShutdownTimeoutSignal() {
        return shutdownTimeoutSignal.get().asMono();
    }

    /**
     * 为指定智能体注册 {@link ShutdownStateSaver}。
     *
     * <p>关闭期间调用保存器，持久化智能体的 {@link AgentState}
     * （{@code shutdownInterrupted} 已设为 {@code true}）。
     */
    public void bindStateSaver(Agent agent, ShutdownStateSaver saver) {
        if (agent == null || saver == null) {
            return;
        }
        stateSavers.put(agent.getAgentId(), saver);
    }

    /**
     * 移除先前为指定智能体注册的 {@link ShutdownStateSaver}。
     *
     * <p>必须由 {@link io.agentscope.core.ReActAgent#close()} 或等效生命周期钩子调用，
     * 避免临时／调用级智能体实例无限积累在 {@link #stateSavers} 中。
     *
     * @param agent 要移除保存器的智能体；为 {@code null} 时不执行操作
     */
    public void unbindStateSaver(Agent agent) {
        if (agent == null) {
            return;
        }
        stateSavers.remove(agent.getAgentId());
    }

    /**
     * 检查智能体的默认状态先前是否因关闭而中断，并清除此标记。
     *
     * <p>此智能体级入口保留给不暴露调用级状态的智能体使用。
     * 具有调用级状态的智能体应改用
     * {@link #checkAndClearShutdownInterruptedForState(AgentState)}。
     *
     * @return 标记原本存在且已清除时为 true
     */
    public boolean checkAndClearShutdownInterrupted(Agent agent) {
        if (!(agent instanceof AgentBase ab)) {
            return false;
        }
        return checkAndClearShutdownInterruptedForState(ab.getAgentState());
    }

    /**
     * 检查调用级状态先前是否因关闭而中断，并清除此标记。
     *
     * <p>提供的状态必须是为当前调用解析出的状态，避免同一智能体实例服务多个会话时，
     * 回退到智能体的默认状态。
     *
     * @param state 为当前调用解析出的状态
     * @return 标记原本存在且已清除时为 true
     */
    public boolean checkAndClearShutdownInterruptedForState(AgentState state) {
        if (state != null && state.isShutdownInterrupted()) {
            state.setShutdownInterrupted(false);
            return true;
        }
        return false;
    }

    public boolean isAcceptingRequests() {
        return getState() == ShutdownState.RUNNING;
    }

    public int getActiveRequestCount() {
        return activeRequestsById.size();
    }

    public void ensureAcceptingRequests() {
        if (!isAcceptingRequests()) {
            throw new AgentShuttingDownException();
        }
    }

    public String registerRequest(Agent agent, RunControl control) {
        if (!(agent instanceof AgentBase)) {
            return "";
        }
        ShutdownStateSaver saver = stateSavers.get(agent.getAgentId());
        String requestId = UUID.randomUUID().toString();
        ActiveRequestContext ctx = new ActiveRequestContext(requestId, saver, control);
        activeRequestsById.put(requestId, ctx);
        return requestId;
    }

    /**
     * 为 {@code requestId} 标识的在途请求绑定已解析的调用级 {@link AgentState}，
     * 使关闭中断与状态保存精确针对 {@code (userId, sessionId)} 会话，而不使用
     * 智能体无参数的“最近活跃”访问器。由 {@code AgentBase} 在调用激活会话槽位后调用。
     * 未跟踪此 ID 的请求或状态为 {@code null} 时，不执行操作。
     */
    public void bindRequestState(String requestId, AgentState state) {
        if (state == null) {
            return;
        }
        getActiveRequest(requestId).ifPresent(ctx -> ctx.bindState(state));
    }

    public void unregisterRequest(String requestId) {
        if (requestId == null || requestId.isEmpty()) {
            return;
        }
        activeRequestsById.remove(requestId);
        updateTerminatedIfNoRequests();
    }

    private Optional<ActiveRequestContext> getActiveRequest(String requestId) {
        if (requestId == null || requestId.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(activeRequestsById.get(requestId));
    }

    public void interruptIfShuttingDown(String requestId) {
        Optional<ActiveRequestContext> contextOpt = getActiveRequest(requestId);
        if (contextOpt.isEmpty()) {
            return;
        }
        if (getState() == ShutdownState.SHUTTING_DOWN) {
            contextOpt.get().interruptForShutdown();
        }
    }

    /**
     * 智能体的 handleInterrupt 观察到 SYSTEM 中断时调用。
     * 始终保存，因为上次安全点／超时保存之后，记忆可能又发生了更新。
     */
    public void saveOnInterruptObserved(String requestId) {
        getActiveRequest(requestId).ifPresent(ActiveRequestContext::saveState);
    }

    public boolean performGracefulShutdown() {
        ShutdownState previous = state.getAndUpdate(this::transitionToShuttingDown);
        if (previous == ShutdownState.TERMINATED) {
            return false;
        }
        if (previous == ShutdownState.RUNNING) {
            shutdownStartedAt.set(Instant.now());
            Duration timeout = config.get().shutdownTimeout();
            log.info(
                    "Graceful shutdown initiated, {} active request(s), timeout={}",
                    getActiveRequestCount(),
                    timeout != null ? timeout : "infinite");
        }
        startMonitorIfNeeded();
        return true;
    }

    private ShutdownState transitionToShuttingDown(ShutdownState current) {
        if (current == ShutdownState.TERMINATED) {
            return ShutdownState.TERMINATED;
        }
        return ShutdownState.SHUTTING_DOWN;
    }

    private void startMonitorIfNeeded() {
        if (!monitorStarted.compareAndSet(false, true)) {
            return;
        }
        ScheduledFuture<?> future =
                monitor.scheduleAtFixedRate(
                        this::enforceTimeoutAndInterrupt, 0, 1, TimeUnit.SECONDS);
        monitorFuture.set(future);
    }

    private void enforceTimeoutAndInterrupt() {
        if (getState() != ShutdownState.SHUTTING_DOWN) {
            return;
        }
        Instant started = shutdownStartedAt.get();
        if (started == null) {
            return;
        }

        GracefulShutdownConfig cfg = config.get();
        Duration timeout = cfg.shutdownTimeout();

        if (timeout != null) {
            Duration elapsed = Duration.between(started, Instant.now());
            if (elapsed.compareTo(timeout) >= 0) {
                log.info(
                        "Shutdown timeout reached ({}s elapsed, limit={}s), force interrupting {}"
                                + " active request(s)",
                        elapsed.getSeconds(),
                        timeout.getSeconds(),
                        activeRequestsById.size());
                shutdownTimeoutSignal.get().tryEmitEmpty();

                for (ActiveRequestContext ctx : activeRequestsById.values()) {
                    ctx.saveState();
                    if (ctx.interruptForShutdown()) {
                        log.info(
                                "Shutdown force interrupt issued for request {}",
                                ctx.getRequestId());
                    }
                }
            }
        }
    }

    private void updateTerminatedIfNoRequests() {
        if (getState() == ShutdownState.SHUTTING_DOWN && activeRequestsById.isEmpty()) {
            if (state.compareAndSet(ShutdownState.SHUTTING_DOWN, ShutdownState.TERMINATED)) {
                synchronized (terminationLock) {
                    terminationLock.notifyAll();
                }
            }
        }
    }

    /**
     * 阻塞等待，直到关闭状态达到 TERMINATED 或指定超时到期。
     *
     * @param timeout 最长等待时间；{@code null} 表示无限等待
     * @return 达到 TERMINATED 时为 true，超时时为 false
     */
    public boolean awaitTermination(Duration timeout) {
        updateTerminatedIfNoRequests();
        long deadline =
                timeout != null ? System.currentTimeMillis() + timeout.toMillis() : Long.MAX_VALUE;
        synchronized (terminationLock) {
            while (getState() != ShutdownState.TERMINATED) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    return false;
                }
                try {
                    terminationLock.wait(Math.min(remaining, 1000));
                } catch (java.lang.InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 重置管理器状态。仅用于测试和演示。
     */
    public void resetForTesting() {
        ScheduledFuture<?> future = monitorFuture.getAndSet(null);
        if (future != null) {
            future.cancel(false);
        }
        state.set(ShutdownState.RUNNING);
        activeRequestsById.clear();
        stateSavers.clear();
        shutdownStartedAt.set(null);
        monitorStarted.set(false);
        shutdownTimeoutSignal.set(Sinks.empty());
    }
}
