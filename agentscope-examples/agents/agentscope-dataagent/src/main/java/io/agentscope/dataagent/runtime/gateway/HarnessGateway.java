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
package io.agentscope.dataagent.runtime.gateway;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.dataagent.runtime.session.PendingCompletion;
import io.agentscope.dataagent.runtime.session.SessionAgentManager;
import io.agentscope.dataagent.runtime.session.SessionConstants;
import io.agentscope.dataagent.runtime.session.SessionEntry;
import io.agentscope.dataagent.runtime.session.SessionFreshness;
import io.agentscope.dataagent.runtime.session.SessionFreshnessEvaluator;
import io.agentscope.dataagent.runtime.session.SessionKind;
import io.agentscope.dataagent.runtime.session.SessionResetPolicy;
import io.agentscope.dataagent.runtime.session.SessionView;
import io.agentscope.dataagent.runtime.session.SpawnResult;
import io.agentscope.dataagent.web.workspace.UserSandboxRegistry;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.gateway.ChannelManager;
import io.agentscope.harness.agent.gateway.Gateway;
import io.agentscope.harness.agent.gateway.LocalSessionTurnGate;
import io.agentscope.harness.agent.gateway.MsgContext;
import io.agentscope.harness.agent.gateway.SessionTurnGate;
import io.agentscope.harness.agent.gateway.TurnBusyException;
import io.agentscope.harness.agent.gateway.TurnLease;
import io.agentscope.harness.agent.gateway.channel.OutboundAddress;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxContext;
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
 * 网关式路由器：按 {@link MsgContext} 路由入站轮次，并将子智能体完成公告分派为
 * 根请求者上的新 {@link HarnessAgent} 运行（对应 OpenClaw 网关）。
 * <p>状态存储管理委托给 {@link SessionAgentManager}。创建时将自身连接为
 * {@link SessionAgentManager.AnnounceDispatcher} 及 {@link
 * SessionAgentManager.SpawnInterceptor}，确保子智能体派生与公告经过正确通道门控。
 * <h2>状态存储路由</h2>
 * 对给定 {@link MsgContext#canonicalKey()} 首次调用 {@link #run} 时，
 * 在 {@link SessionAgentManager} 中注册 MAIN 会话。相同键的后续调用复用此会话。
 * 可通过 {@link #registerAgent} 注册多个 Harness 智能体，按 agentId 路由。
 * <h2>逐会话轮次串行化</h2>
 * 每个 {@link MsgContext#canonicalKey()} 拥有公平锁（{@link SessionTurnGate}），
 * 保证相同键同时最多执行一个 {@code HarnessAgent.call}。
 */
public final class HarnessGateway implements Gateway {

    private static final Logger log = LoggerFactory.getLogger(HarnessGateway.class);

    private final SessionAgentManager sessionAgentManager;
    private final ChannelManager channelManager;

    /** 可选注册表，为每个 {@code (userId, agentId)} 拥有一个活跃 Docker {@link Sandbox}。非 null 时，每个 {@link #run} / {@link #tryDispatchAnnounce} 轮次都会将该沙箱以 {@link SandboxContext#getExternalSandbox()} 注入 {@link RuntimeContext}，使 Harness 采用优先级 1 获取路径，与浏览器工作区控制器读写同一容器。<p>通过构造方法或 {@link #setUserSandboxRegistry(UserSandboxRegistry)} 一次性设置。 */
    private volatile UserSandboxRegistry userSandboxRegistry;

    /** 通过 {@link #bindMainAgent} 绑定的主智能体，用作路由后备。 */
    private final AtomicReference<HarnessAgent> mainAgent = new AtomicReference<>();

    /** agentId 到 HarnessAgent 的映射，由 {@link #bindMainAgent} 和 {@link #registerAgent} 填充。 */
    private final ConcurrentHashMap<String, HarnessAgent> agentRegistry = new ConcurrentHashMap<>();

    /** 最近一次 {@link #bindMainAgent} 注册的 agentId。 */
    private volatile String defaultAgentId = null;

    /** gateKey 到 SessionAgentManager 中主会话键的映射。每个键首次 {@link #run} 时填充，用于跨轮次恢复同一 MAIN 会话。 */
    private final ConcurrentHashMap<String, String> contextKeyToSessionKey =
            new ConcurrentHashMap<>();

    /**
     * 主会话键到 gateKey 的映射，创建 MAIN 会话时填充。{@link
     * #tryDispatchAnnounce} 据此在正确通道门控中投递完成公告。
     */
    private final ConcurrentHashMap<String, String> sessionKeyToGateKey = new ConcurrentHashMap<>();

    /** 主会话键到 agentId 的映射。{@link #tryDispatchAnnounce} 据此将完成公告路由给发起运行的同一智能体。 */
    private final ConcurrentHashMap<String, String> sessionKeyToAgentId = new ConcurrentHashMap<>();

    /** 会话键到最后出站地址的映射。每次入站 {@link #run} 都更新，使主动出站公告发到正确的通道/对端。 */
    private final ConcurrentHashMap<String, OutboundAddress> lastRouteBySessionKey =
            new ConcurrentHashMap<>();

    private final SessionTurnGate sessionTurnGate = new LocalSessionTurnGate();

    private HarnessGateway(
            SessionAgentManager sessionAgentManager,
            ChannelManager channelManager,
            UserSandboxRegistry userSandboxRegistry) {
        this.sessionAgentManager = Objects.requireNonNull(sessionAgentManager);
        this.channelManager = channelManager;
        this.userSandboxRegistry = userSandboxRegistry;
    }

    /**
     * 创建连接给定 {@link SessionAgentManager}、{@link ChannelManager} 和
     * {@link UserSandboxRegistry} 的网关。在会话管理器上设置公告分派器和派生拦截器，
     * 并从会话存储恢复持久化 MAIN 路由映射（若可用）。
     * @param sessionAgentManager 会话与智能体生命周期管理器
     * @param channelManager 用于出站投递的通道注册表；不需要出站投递时可为 null
     * @param userSandboxRegistry 逐 {@code (userId, agentId)} 沙箱注册表；非 null 时，每轮将
     *     用户活跃沙箱注入 {@link SandboxContext#getExternalSandbox()}，与浏览器路径使用同一容器。
     *     不关注工作区隔离的测试中可为 null。
     */
    public static HarnessGateway create(
            SessionAgentManager sessionAgentManager,
            ChannelManager channelManager,
            UserSandboxRegistry userSandboxRegistry) {
        Objects.requireNonNull(sessionAgentManager, "sessionAgentManager");
        HarnessGateway gateway =
                new HarnessGateway(sessionAgentManager, channelManager, userSandboxRegistry);
        sessionAgentManager.setAnnounceDispatcher(gateway::tryDispatchAnnounce);
        sessionAgentManager.setSpawnInterceptor(gateway::onSpawn);
        sessionAgentManager.setMainHistoryAgentResolver(gateway::resolveMainHistoryAgent);
        gateway.restorePersistedMainSessions();
        return gateway;
    }

    /** 创建不注入沙箱的网关（兼容旧调用方/测试）。 */
    public static HarnessGateway create(
            SessionAgentManager sessionAgentManager, ChannelManager channelManager) {
        return create(sessionAgentManager, channelManager, null);
    }

    /** 创建没有通道管理器及沙箱注册表的网关。 */
    public static HarnessGateway create(SessionAgentManager sessionAgentManager) {
        return create(sessionAgentManager, null, null);
    }

    /** 此网关使用的会话智能体管理器。 */
    public SessionAgentManager sessionAgentManager() {
        return sessionAgentManager;
    }

    /** 用于出站投递的通道管理器，未配置时为 null。 */
    public ChannelManager channelManager() {
        return channelManager;
    }

    /**
     * 连接或替换每用户沙箱注册表，预期用于应用启动时的一次性装配。
     * 通常由 Spring 配置在 {@link
     * io.agentscope.dataagent.runtime.DataAgentBootstrap} 生成网关后调用，避免引导层直接依赖 Web 层。
     */
    public void setUserSandboxRegistry(UserSandboxRegistry registry) {
        this.userSandboxRegistry = registry;
    }

    /** 从会话存储中持久化的 MAIN 会话恢复网关路由映射。仅恢复按 {@link SessionResetPolicy} 仍有效的会话；跳过过期会话，使下一入站轮次创建新会话。 */
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
     * 绑定主 Harness 智能体，并以其 {@link
     * HarnessAgent#sessionKey(RuntimeContext)} 稳定智能体身份注册以供路由。
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

    /** 返回 {@code gatewayId} 下注册的 {@link HarnessAgent}（通常是全局智能体 ID 或 {@code uca-{userId}-{agentId}} 命名空间 ID）；当前无注册实例时为 {@code null}。供平台控制器直接检查已构建注册的智能体（如枚举技能仓库），无需经过路由路径。 */
    public HarnessAgent findAgent(String gatewayId) {
        if (gatewayId == null) return null;
        return agentRegistry.get(gatewayId);
    }

    /**
     * 直接或通道发起的轮次。按 {@link
     * MsgContext#canonicalKey()} 解析或创建 MAIN 会话，路由到适当智能体，并在逐键
     * {@link SessionTurnGate} 保护下执行。
     */
    @Override
    public Mono<Msg> run(MsgContext context, List<Msg> messages) {
        return run(context, messages, null);
    }

    /** 带出站地址跟踪的入站轮次。将 {@code outboundAddress} 记录为会话最后路由，使主动消息（子智能体公告）可投递到正确通道/对端。 */
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

        String sessionKey = resolveOrCreateMainSession(gateKey, ha, ctx.userId(), requestedAgentId);
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
        attachUserSandboxContext(
                rtcBuilder, ctx.userId(), resolveSandboxAgentId(requestedAgentId, ha));
        RuntimeContext runtimeContext = rtcBuilder.build();
        return withGatedTurn(gateKey, () -> ha.call(messages, runtimeContext));
    }

    /** 派生拦截器：记录 gateKey、agentId 及最后路由映射，使子会话公告到达正确的通道门控、智能体和出站目标。 */
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

    /** 子智能体完成时尝试网关式分派：在请求者门控上安排携带 {@link PendingCompletion#announceText()} 的新轮次，再经 {@link ChannelManager} 通过原通道发送智能体回复。 */
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

        // 取请求者会话保存的 userId，维持命名空间连续性
        String sessionUserId =
                sessionAgentManager.getSession(requesterKey).map(SessionEntry::userId).orElse(null);

        String sessionKey =
                resolveOrCreateMainSession(gateKey, ha, sessionUserId, requesterAgentId);
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
        attachUserSandboxContext(
                ctxBuilder, sessionUserId, resolveSandboxAgentId(requesterAgentId, ha));
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

    /** 通过通道管理器投递智能体公告回复。没有通道管理器或最后路由时，仅记录日志，不投递。 */
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

    private String resolveOrCreateMainSession(
            String gateKey, HarnessAgent ha, String userId, String routingAgentId) {
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
                    String harnessAgentId = resolveAgentId(ha);
                    String sandboxAgentId = resolveSandboxAgentId(routingAgentId, ha);
                    SpawnResult r =
                            sessionAgentManager.registerMainSession(
                                    harnessAgentId, null, gateKey, userId);
                    sessionKeyToGateKey.put(r.sessionKey(), gateKey);
                    // 优先使用网关/目录 ID，使公告轮次借用与聊天界面
                    // 相同的沙箱。
                    sessionKeyToAgentId.put(r.sessionKey(), sandboxAgentId);
                    return r.sessionKey();
                });
    }

    private boolean isSessionFresh(String sessionKey) {
        // 已删除会话不能继续保留 gateKey → sessionKey 映射。NEVER 模式
        // 跳过空闲/每日检查，但仍要求会话存在于管理器中。
        return sessionAgentManager
                .getSession(sessionKey)
                .map(
                        entry -> {
                            SessionResetPolicy policy =
                                    sessionAgentManager.getConfig().sessionResetPolicy();
                            if (policy.mode() == SessionResetPolicy.ResetMode.NEVER) {
                                return true;
                            }
                            SessionFreshness f =
                                    SessionFreshnessEvaluator.evaluate(
                                            entry.lastActivityMs(),
                                            System.currentTimeMillis(),
                                            policy);
                            return f.fresh();
                        })
                .orElse(false);
    }

    /** 只解析持久化 MAIN 元数据，不分派工作或创建子智能体。 */
    private HarnessAgent resolveMainHistoryAgent(SessionEntry entry) {
        // 优先使用精确的目录/租户路由 ID；不同工作区可能有相同显示名称。
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

    /** 与工作区/历史 HTTP 读取方共享的沙箱注册键。<p>优先使用路由中的网关/目录 ID（{@code MsgContext} 的 {@code agentId}，例如 {@code data-agent} 或 {@code uca-...}）。若回退到 {@link HarnessAgent#getAgentId()}（内部 UUID），可能打开与界面不同的容器，表现为历史为空。 */
    private String resolveSandboxAgentId(String preferredGatewayId, HarnessAgent ha) {
        if (preferredGatewayId != null && !preferredGatewayId.isBlank()) {
            // 忽略伪装成路由 ID 的内部 UUID（旧 sessionKeyToAgentId）。
            if (ha == null || !preferredGatewayId.equals(ha.getAgentId())) {
                return preferredGatewayId;
            }
        }
        if (ha == null) {
            return "main";
        }
        String uuid = ha.getAgentId();
        String uuidKey = null;
        for (var e : agentRegistry.entrySet()) {
            if (e.getValue() != ha) {
                continue;
            }
            String key = e.getKey();
            if (uuid != null && uuid.equals(key)) {
                uuidKey = key;
                continue;
            }
            return key;
        }
        if (uuidKey != null) {
            return uuidKey;
        }
        return resolveAgentId(ha);
    }

    /**
     * 从注册表借用逐 {@code (userId, agentId)} 的 {@link Sandbox}（若已配置），
     * 以 {@link SandboxContext#getExternalSandbox()
     * external sandbox} 附加到 {@link RuntimeContext}，使 {@code SandboxManager.acquire}
     * 采用优先级 1 路径，与浏览器工作区控制器使用同一容器。
     * 注册表未配置或 {@code userId} 缺失时不做操作。
     * <p>【学习提示】路由身份必须贯穿聊天、历史读取和工作区访问。
     * 内部实例 UUID 与用户可见目录 ID 不可互换；这里的沙箱借用也不转移其生命周期所有权。
     */
    private void attachUserSandboxContext(
            RuntimeContext.Builder builder, String userId, String agentId) {
        if (userSandboxRegistry == null || userId == null || userId.isBlank()) {
            return;
        }
        try {
            Sandbox sb = userSandboxRegistry.borrow(userId, agentId);
            SandboxContext sandboxCtx =
                    SandboxContext.builder()
                            .externalSandbox(sb)
                            .isolationScope(IsolationScope.USER)
                            .build();
            builder.put(SandboxContext.class, sandboxCtx);
        } catch (RuntimeException e) {
            log.warn(
                    "[gateway] Failed to borrow sandbox for user={}, agent={} — agent turn will"
                            + " fall back to the default SandboxContext: {}",
                    userId,
                    agentId,
                    e.getMessage(),
                    e);
        }
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
