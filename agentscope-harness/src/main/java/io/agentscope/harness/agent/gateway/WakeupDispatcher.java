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
package io.agentscope.harness.agent.gateway;

import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.bus.BusEntry;
import io.agentscope.harness.agent.bus.MessageBus;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

/**
 * 每进程调度器，在后台工作完成时唤醒闲置会话。
 *
 * <p>订阅 {@link MessageBus#subscribeWakeup()} 的共享唤醒信号渠道，
 * 每次信号到达时排空持久化唤醒队列。对目标会话闲置的队列条目，
 * 通过 {@link WakeupTarget#runWakeup(String)} 触发新的推理轮次。
 *
 * 它是跨会话通信的统一激活入口：
 *
 * <ul>
 *   <li>后台子智能体任务完成
 *   <li>异步工具结果（{@link io.agentscope.harness.agent.middleware.AsyncToolMiddleware}）
 *   <li>团队消息（后续 Agent Teams 支持）
 *   <li>计划任务触发
 * </ul>
 *
 * <p>生命周期：网关完整配置后调用 {@link #start()}；关闭时调用 {@link #close()}。
 * 通常由应用启动引导或网关工厂管理。
 */
public class WakeupDispatcher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WakeupDispatcher.class);

    private static final int MAX_DRAIN_COUNT = 64;

    private final MessageBus messageBus;
    private final WakeupTarget target;
    private volatile Disposable subscription;

    /**
     * 调度器所需两个网关操作的轻量接口。{@link HarnessGateway} 直接实现它，测试可提供桩对象。
     */
    public interface WakeupTarget {
        boolean isSessionRunning(String sessionId);

        /**
         * 在不提供所属用户 ID 的情况下触发唤醒执行。
         *
         * @param sessionId 要唤醒的会话
         * @return 智能体响应；未知会话时为 {@link Mono#empty()}
         * @deprecated 实现方应改为覆盖 {@link #runWakeup(String, String)}，使调度器能向下传递所属用户 ID。
         *     按 {@code (userId, sessionId)} 隔离状态的运行时必须如此；否则唤醒轮次会从匿名槽位加载，
         *     无法恢复原对话。此重载为向后兼容而保留，默认忽略用户 ID。
         */
        @Deprecated
        Mono<Msg> runWakeup(String sessionId);

        /**
         * 为闲置会话触发唤醒执行，并在存在所属用户 ID 时携带它。
         *
         * <p>后台任务完成或团队消息到达时由 {@link WakeupDispatcher} 调用。
         * 用户 ID 非空白时，实现应把它设置到智能体运行上下文，
         * 使状态从与原用户调用相同的 {@code (userId, sessionId)} 槽位加载。
         *
         * @param userId 唤醒条目携带的所属用户 ID；产生方未提供时为 {@code null} 或空白
         *     （例如单租户运行时）
         * @param sessionId 要唤醒的会话
         * @return 智能体响应；未知会话时为 {@link Mono#empty()}
         */
        default Mono<Msg> runWakeup(String userId, String sessionId) {
            return runWakeup(sessionId);
        }
    }

    public WakeupDispatcher(MessageBus messageBus, WakeupTarget target) {
        this.messageBus = messageBus;
        this.target = target;
    }

    /**
     * 启动调度器：先排空已有唤醒队列（处理本进程停机期间产生的信号），再订阅实时信号渠道。
     */
    public void start() {
        drainAndDispatch();
        subscription =
                messageBus
                        .subscribeWakeup()
                        .subscribe(
                                signal -> drainAndDispatch(),
                                err ->
                                        log.error(
                                                "WakeupDispatcher subscription error; "
                                                        + "dispatcher is dead",
                                                err));
        log.info("WakeupDispatcher started");
    }

    @Override
    public void close() {
        Disposable d = subscription;
        if (d != null && !d.isDisposed()) {
            d.dispose();
        }
        subscription = null;
        log.info("WakeupDispatcher stopped");
    }

    private void drainAndDispatch() {
        try {
            List<BusEntry> entries =
                    messageBus.queueDrain("agentscope:wakeups", MAX_DRAIN_COUNT).block();
            if (entries == null || entries.isEmpty()) {
                return;
            }

            for (BusEntry entry : entries) {
                dispatch(entry.payload());
            }
        } catch (Exception e) {
            log.warn("WakeupDispatcher: drainAndDispatch failed", e);
        }
    }

    private void dispatch(Map<String, Object> payload) {
        String userId = getString(payload, "userId");
        String sessionId = getString(payload, "sessionId");
        String agentId = getString(payload, "agentId");

        if (sessionId == null || sessionId.isBlank()) {
            log.debug("WakeupDispatcher: skipping entry with no sessionId");
            return;
        }

        if (target.isSessionRunning(sessionId)) {
            log.debug(
                    "WakeupDispatcher: session {} is running, skipping (current run will drain"
                            + " inbox)",
                    sessionId);
            return;
        }

        log.info(
                "WakeupDispatcher: waking idle session {}, userId={}, agentId={}",
                sessionId,
                userId,
                agentId);
        target.runWakeup(userId, sessionId)
                .subscribe(
                        msg ->
                                log.debug(
                                        "WakeupDispatcher: wakeup run completed for session {}",
                                        sessionId),
                        err ->
                                log.warn(
                                        "WakeupDispatcher: wakeup run failed for session {}",
                                        sessionId,
                                        err));
    }

    private static String getString(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof String s ? s : null;
    }
}
