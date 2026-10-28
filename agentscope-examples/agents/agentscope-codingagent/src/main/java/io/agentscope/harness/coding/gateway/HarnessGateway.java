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
package io.agentscope.harness.coding.gateway;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.gateway.ChannelManager;
import io.agentscope.harness.agent.gateway.Gateway;
import io.agentscope.harness.agent.gateway.LocalSessionTurnGate;
import io.agentscope.harness.agent.gateway.MsgContext;
import io.agentscope.harness.agent.gateway.SessionTurnGate;
import io.agentscope.harness.agent.gateway.TurnBusyException;
import io.agentscope.harness.agent.gateway.TurnLease;
import io.agentscope.harness.agent.gateway.channel.OutboundAddress;
import io.agentscope.harness.coding.session.PendingCompletion;
import io.agentscope.harness.coding.session.SessionAgentManager;
import io.agentscope.harness.coding.session.SessionConstants;
import io.agentscope.harness.coding.session.SessionEntry;
import io.agentscope.harness.coding.session.SessionFreshness;
import io.agentscope.harness.coding.session.SessionFreshnessEvaluator;
import io.agentscope.harness.coding.session.SessionKind;
import io.agentscope.harness.coding.session.SessionResetPolicy;
import io.agentscope.harness.coding.session.SessionView;
import io.agentscope.harness.coding.session.SpawnResult;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 网关式入口：按 {@link MsgContext} 路由入站轮次，将子智能体完成通知作为根请求方的新 {@link HarnessAgent} 运行分发，类似 OpenClaw 网关。
 *
 * <p>AgentStateStore 管理委托给 {@link SessionAgentManager}。网关创建时将自身接为 {@link SessionAgentManager.AnnounceDispatcher} 与 {@link SessionAgentManager.SpawnInterceptor}，使子智能体派生和通知经过正确通道门控。
 *
 * <h2>AgentStateStore 路由</h2>
 *
 * 首次对给定 {@link MsgContext#canonicalKey()} 调用 {@link #run} 时，在 {@link SessionAgentManager} 注册 MAIN 会话。相同键后续复用该会话。可通过 {@link #registerAgent} 注册多个 Harness 智能体，按 agentId 路由。
 *
 * <h2>逐会话轮次串行化</h2>
 *
 * 每个 {@link MsgContext#canonicalKey()} 具有公平锁（{@link SessionTurnGate}），保证该键同一时刻最多执行一次 {@code HarnessAgent.call}。
 */
public final class HarnessGateway implements Gateway {

    private static final Logger log = LoggerFactory.getLogger(HarnessGateway.class);

    private final SessionAgentManager sessionAgentManager;
    private final ChannelManager channelManager;

    /**
     * 主智能体，通过 {@link #bindMainAgent} 设置，用作路由回退。
     */
    private final AtomicReference<HarnessAgent> mainAgent = new AtomicReference<>();

    /**
     * agentId → HarnessAgent，由 {@link #bindMainAgent} 和 {@link #registerAgent} 填充。
     */
    private final ConcurrentHashMap<String, HarnessAgent> agentRegistry = new ConcurrentHashMap<>();

    /**
     * 最近一次 {@link #bindMainAgent} 注册的 agentId。
     */
    private volatile String defaultAgentId = null;

    /**
     * gateKey → 主会话键（SessionAgentManager 中）。每个键首次 {@link #run} 时填充，供跨轮次恢复同一 MAIN 会话。
     */
    private final ConcurrentHashMap<String, String> contextKeyToSessionKey =
            new ConcurrentHashMap<>();

    /**
     * 主会话键 → gateKey。创建 MAIN 会话时填充，由 {@link #tryDispatchAnnounce} 用于将完成通知投递到正确通道门控。
     */
    private final ConcurrentHashMap<String, String> sessionKeyToGateKey = new ConcurrentHashMap<>();

    /**
     * 主会话键 → agentId。由 {@link #tryDispatchAnnounce} 用于将完成通知路由到发起运行的同一智能体。
     */
    private final ConcurrentHashMap<String, String> sessionKeyToAgentId = new ConcurrentHashMap<>();

    /**
     * 会话键 → 最近出站地址。每次入站 {@link #run} 更新，使主动出站消息（通知）投递到正确通道/对端。
     */
    private final ConcurrentHashMap<String, OutboundAddress> lastRouteBySessionKey =
            new ConcurrentHashMap<>();

    private final SessionTurnGate sessionTurnGate = new LocalSessionTurnGate();

    private HarnessGateway(SessionAgentManager sessionAgentManager, ChannelManager channelManager) {
        this.sessionAgentManager = Objects.requireNonNull(sessionAgentManager);
        this.channelManager = channelManager;
    }

    /**
     * 创建接入给定 {@link SessionAgentManager} 和 {@link ChannelManager} 的网关。设置会话管理器的通知分发器和派生拦截器，并从会话存储恢复持久化 MAIN 会话路由映射（若可用）。
     *
     * @param sessionAgentManager 会话与智能体生命周期管理器
     * @param channelManager 出站投递的通道注册表；无需出站投递时可为 null
     */
    public static HarnessGateway create(
            SessionAgentManager sessionAgentManager, ChannelManager channelManager) {
        Objects.requireNonNull(sessionAgentManager, "sessionAgentManager");
        HarnessGateway gateway = new HarnessGateway(sessionAgentManager, channelManager);
        sessionAgentManager.setAnnounceDispatcher(gateway::tryDispatchAnnounce);
        sessionAgentManager.setSpawnInterceptor(gateway::onSpawn);
        sessionAgentManager.setMainHistoryAgentResolver(gateway::resolveMainHistoryAgent);
        gateway.restorePersistedMainSessions();
        return gateway;
    }

    /**
     * 创建不带通道管理器的网关，不支持出站投递。
     */
    public static HarnessGateway create(SessionAgentManager sessionAgentManager) {
        return create(sessionAgentManager, null);
    }

    /**
     * 此网关使用的会话智能体管理器。
     */
    public SessionAgentManager sessionAgentManager() {
        return sessionAgentManager;
    }

    /**
     * 出站投递的通道管理器；未配置时为 null。
     */
    public ChannelManager channelManager() {
        return channelManager;
    }

    /**
     * 从会话存储的持久化 MAIN 会话恢复网关路由映射。仅恢复符合配置 {@link SessionResetPolicy} 的新鲜会话；跳过过期会话，在下一次入站轮次创建新会话。
     */
    private void restorePersistedMainSessions() {
        SessionResetPolicy policy = sessionAgentManager.getConfig().sessionResetPolicy();
        long now = System.currentTimeMillis();
        int restored = 0;
        for (SessionEntry entry : sessionAgentManager.allSessions()) {
            if (entry.kind() != SessionKind.MAIN) {
                continue;
            }
            String gateKey = entry.gateKey();
            if (gateKey == null || gateKey.isBlank()) {
                continue;
            }
            if (policy.mode() != SessionResetPolicy.ResetMode.NEVER) {
                SessionFreshness freshness =
                        SessionFreshnessEvaluator.evaluate(entry.lastActivityMs(), now, policy);
                if (!freshness.fresh()) {
                    log.debug(
                            "Skipping stale persisted session: sessionKey={}, gateKey={}",
                            entry.sessionKey(),
                            gateKey);
                    continue;
                }
            }
            contextKeyToSessionKey.putIfAbsent(gateKey, entry.sessionKey());
            sessionKeyToGateKey.putIfAbsent(entry.sessionKey(), gateKey);
            sessionKeyToAgentId.putIfAbsent(entry.sessionKey(), entry.agentId());
            restored++;
        }
        if (restored > 0) {
            log.info("Restored {} persisted MAIN session routing maps", restored);
        }
    }

    /**
     * 绑定主 Harness 智能体，同时按其 {@link HarnessAgent#getAgentId()} 注册以供路由。
     */
    @Override
    public void bindMainAgent(HarnessAgent agent) {
        Objects.requireNonNull(agent, "agent");
        mainAgent.set(agent);
        String id = resolveAgentId(agent);
        agentRegistry.put(id, agent);
        defaultAgentId = id;
    }

    @Override
    public void registerAgent(String agentId, HarnessAgent agent) {
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(agent, "agent");
        agentRegistry.put(agentId, agent);
    }

    /**
     * 直接或通道发起的轮次。按 {@link MsgContext#canonicalKey()} 解析或创建 MAIN 会话，路由到合适智能体，并在逐键 {@link SessionTurnGate} 下运行。
     */
    @Override
    public Mono<Msg> run(MsgContext context, List<Msg> messages) {
        return run(context, messages, null);
    }

    /**
     * 带出站地址跟踪的入站轮次。将 {@code outboundAddress} 记录为会话的“最后路由”，使主动出站消息（子智能体通知）投递到正确通道/对端。
     */
    @Override
    public Mono<Msg> run(MsgContext context, List<Msg> messages, OutboundAddress outboundAddress) {
        MsgContext ctx = context != null ? context : MsgContext.defaultContext();
        String gateKey = ctx.canonicalKey();

        String requestedAgentId = ctx.extra() != null ? (String) ctx.extra().get("agentId") : null;
        HarnessAgent ha = resolveAgent(requestedAgentId);
        if (ha == null) {
            return Mono.error(
                    new IllegalStateException(
                            "HarnessGateway.bindMainAgent must be called before run(...)"));
        }

        String sessionKey = resolveOrCreateMainSession(gateKey, ha, ctx.userId());
        String sessionId =
                sessionAgentManager
                        .viewSession(sessionKey)
                        .map(SessionView::sessionId)
                        .orElse(sessionKey);

        if (outboundAddress != null) {
            lastRouteBySessionKey.put(sessionKey, outboundAddress);
        }

        RuntimeContext.Builder rtcBuilder =
                RuntimeContext.builder()
                        .sessionId(sessionId)
                        .put("msgContext", ctx)
                        .put("sessionKey", sessionKey);
        if (ctx.userId() != null) {
            rtcBuilder.userId(ctx.userId());
        }
        RuntimeContext runtimeContext = rtcBuilder.build();
        return withGatedTurn(gateKey, () -> ha.call(messages, runtimeContext));
    }

    /**
     * 派生拦截器：记录门控键、智能体 ID 和最近路由映射，使子会话通知路由到正确通道门控、智能体和出站目标。
     */
    private void onSpawn(SpawnResult result, String parentSessionKey) {
        if (parentSessionKey != null) {
            String gateKey =
                    sessionKeyToGateKey.getOrDefault(
                            parentSessionKey, MsgContext.defaultContext().canonicalKey());
            sessionKeyToGateKey.put(result.sessionKey(), gateKey);
            String parentAgentId = sessionKeyToAgentId.get(parentSessionKey);
            if (parentAgentId != null) {
                sessionKeyToAgentId.put(result.sessionKey(), parentAgentId);
            }
            OutboundAddress parentRoute = lastRouteBySessionKey.get(parentSessionKey);
            if (parentRoute != null) {
                lastRouteBySessionKey.put(result.sessionKey(), parentRoute);
            }
        }
    }

    /**
     * 尝试在子智能体完成时进行网关式分发：在请求方门控上调度携带 {@link PendingCompletion#announceText()} 的新轮次，再通过 {@link ChannelManager} 经原始通道投递智能体回复。
     */
    boolean tryDispatchAnnounce(PendingCompletion completion) {
        String requesterKey = completion.requesterSessionKey();
        String gateKey = sessionKeyToGateKey.get(requesterKey);
        String requesterAgentId = sessionKeyToAgentId.get(requesterKey);

        boolean isRootRequester = SessionConstants.ROOT_REQUESTER_SESSION_KEY.equals(requesterKey);
        if (gateKey == null && !isRootRequester) {
            return false;
        }
        if (gateKey == null) {
            gateKey = MsgContext.defaultContext().canonicalKey();
        }

        HarnessAgent ha = resolveAgent(requesterAgentId);
        if (ha == null) {
            log.debug(
                    "Announce skipped: no agent found for requesterKey={} agentId={}",
                    requesterKey,
                    requesterAgentId);
            return false;
        }

        Msg m =
                Msg.builder()
                        .role(MsgRole.USER)
                        .name("subagent_announce")
                        .textContent(completion.announceText())
                        .build();

        // 从请求方会话读取保存的 userId，保持命名空间连续性
        String sessionUserId =
                sessionAgentManager.getSession(requesterKey).map(SessionEntry::userId).orElse(null);

        String sessionKey = resolveOrCreateMainSession(gateKey, ha, sessionUserId);
        String sessionId =
                sessionAgentManager
                        .viewSession(sessionKey)
                        .map(SessionView::sessionId)
                        .orElse(sessionKey);

        RuntimeContext.Builder ctxBuilder =
                RuntimeContext.builder()
                        .sessionId(sessionId)
                        .put("announce", Boolean.TRUE)
                        .put("childRunId", completion.runId())
                        .put("childSessionKey", completion.childSessionKey());
        if (sessionUserId != null) {
            ctxBuilder.userId(sessionUserId);
        }
        RuntimeContext ctx = ctxBuilder.build();

        OutboundAddress lastRoute = lastRouteBySessionKey.get(requesterKey);
        if (lastRoute == null) {
            lastRoute = lastRouteBySessionKey.get(sessionKey);
        }
        final OutboundAddress deliveryTarget = lastRoute;

        final String resolvedGateKey = gateKey;
        withGatedTurn(resolvedGateKey, () -> ha.call(List.of(m), ctx))
                .subscribe(
                        reply -> deliverAnnounceReply(deliveryTarget, reply),
                        err -> log.warn("Announce agent run failed: {}", err.toString()));
        return true;
    }

    /**
     * 通过通道管理器投递智能体通知回复。没有通道管理器或最后路由时，仅记录日志而不投递。
     */
    private void deliverAnnounceReply(OutboundAddress target, Msg reply) {
        if (reply == null) {
            return;
        }
        String text = reply.getTextContent();
        if (text != null && text.strip().equalsIgnoreCase("NO_REPLY")) {
            return;
        }
        if (target == null || channelManager == null) {
            log.debug("Announce reply not delivered (no route or no channel manager)");
            return;
        }
        try {
            channelManager.deliver(target, List.of(reply));
        } catch (Exception e) {
            log.warn(
                    "Failed to deliver announce reply: channel={}, to={}",
                    target.channelId(),
                    target.to(),
                    e);
        }
    }

    // -----------------------------------------------------------------
    // 内部辅助方法
    // -----------------------------------------------------------------

    private HarnessAgent resolveAgent(String agentId) {
        if (agentId != null && agentRegistry.containsKey(agentId)) {
            return agentRegistry.get(agentId);
        }
        HarnessAgent def = mainAgent.get();
        if (def != null) {
            return def;
        }
        return defaultAgentId != null ? agentRegistry.get(defaultAgentId) : null;
    }

    private String resolveOrCreateMainSession(String gateKey, HarnessAgent ha, String userId) {
        return contextKeyToSessionKey.compute(
                gateKey,
                (k, existingSessionKey) -> {
                    if (existingSessionKey != null) {
                        if (isSessionFresh(existingSessionKey)) {
                            return existingSessionKey;
                        }
                        log.info(
                                "AgentStateStore stale, rolling over: gateKey={}, oldSessionKey={}",
                                gateKey,
                                existingSessionKey);
                    }
                    String aid = resolveAgentId(ha);
                    SpawnResult r =
                            sessionAgentManager.registerMainSession(aid, null, gateKey, userId);
                    sessionKeyToGateKey.put(r.sessionKey(), gateKey);
                    sessionKeyToAgentId.put(r.sessionKey(), aid);
                    return r.sessionKey();
                });
    }

    private boolean isSessionFresh(String sessionKey) {
        SessionResetPolicy policy = sessionAgentManager.getConfig().sessionResetPolicy();
        if (policy.mode() == SessionResetPolicy.ResetMode.NEVER) {
            return true;
        }
        return sessionAgentManager
                .getSession(sessionKey)
                .map(
                        entry -> {
                            SessionFreshness f =
                                    SessionFreshnessEvaluator.evaluate(
                                            entry.lastActivityMs(),
                                            System.currentTimeMillis(),
                                            policy);
                            return f.fresh();
                        })
                .orElse(false);
    }

    /**
     * 解析持久化 MAIN 元数据，不分发工作也不创建子智能体。
     */
    private HarnessAgent resolveMainHistoryAgent(SessionEntry entry) {
        // 优先使用精确的目录/租户路由 ID。显示名可能在不同工作区重复。
        String gate = entry.gateKey();
        String marker = "|x:agentId=";
        int start = gate == null ? -1 : gate.indexOf(marker);
        if (start >= 0) {
            start += marker.length();
            int end = gate.indexOf('|', start);
            return agentRegistry.get(end < 0 ? gate.substring(start) : gate.substring(start, end));
        }
        HarnessAgent direct = agentRegistry.get(entry.agentId());
        if (direct != null) return direct;
        HarnessAgent match = null;
        for (HarnessAgent candidate : agentRegistry.values()) {
            if (entry.agentId().equals(candidate.getAgentId())
                    || entry.agentId().equals(resolveAgentId(candidate))) {
                if (match != null && match != candidate) return null;
                match = candidate;
            }
        }
        return match;
    }

    private static String resolveAgentId(HarnessAgent ha) {
        String id = ha != null ? ha.sessionKey(RuntimeContext.empty()).agentId() : null;
        return (id != null && !id.isBlank()) ? id : "main";
    }

    private Mono<Msg> withGatedTurn(String gateKey, Supplier<Mono<Msg>> turn) {
        AtomicReference<TurnLease> leaseRef = new AtomicReference<>();
        return Mono.defer(
                        () -> {
                            try {
                                leaseRef.set(sessionTurnGate.acquire(gateKey));
                            } catch (TurnBusyException e) {
                                return Mono.empty();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return Mono.error(new IllegalStateException(e));
                            }
                            return turn.get();
                        })
                .doFinally(
                        sig -> {
                            TurnLease lease = leaseRef.get();
                            if (lease != null) {
                                lease.close();
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }
}
