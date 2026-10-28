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

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.bus.MessageBus;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import io.agentscope.harness.agent.gateway.channel.ChannelRuntimeContextRequest;
import io.agentscope.harness.agent.gateway.channel.ChannelRuntimeContextResolver;
import io.agentscope.harness.agent.gateway.channel.InboundMessage;
import io.agentscope.harness.agent.gateway.channel.OutboundAddress;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 默认 {@link Gateway} 实现。按 {@link MsgContext#canonicalKey()} 路由入站轮次，
 * 通过 {@link SessionTurnGate} 按会话串行化并发轮次，再分发给对应的已注册 {@link HarnessAgent}。
 *
 * <p>此实现提供：
 * <ul>
 *   <li>多智能体注册表，并回退到主智能体
 *   <li>稳定会话映射：相同 {@link MsgContext#canonicalKey()} 始终解析到相同会话 ID，
 *       使每个逻辑对话有自己的记忆
 *   <li>按会话公平互斥，防止并发轮次竞争
 *   <li>跟踪出站地址以主动投递消息（例如子智能体公告）
 * </ul>
 */
public final class HarnessGateway implements Gateway, WakeupDispatcher.WakeupTarget {

    private static final Logger log = LoggerFactory.getLogger(HarnessGateway.class);

    private static final List<String> SESSION_NAMESPACE = List.of("gateway", "sessions");
    private static final List<String> ROUTE_NAMESPACE = List.of("gateway", "routes");

    private final ChannelManager channelManager;
    private final MessageBus messageBus;
    private volatile SessionTurnGate sessionTurnGate = new LocalSessionTurnGate();

    private final AtomicReference<HarnessAgent> mainAgent = new AtomicReference<>();
    private final ConcurrentHashMap<String, HarnessAgent> agentRegistry = new ConcurrentHashMap<>();
    private volatile String defaultAgentId = null;

    /**
     * 反向映射：会话 ID → canonicalKey（供唤醒驱动的执行使用）。
     */
    private final ConcurrentHashMap<String, String> sessionToGateKey = new ConcurrentHashMap<>();

    /**
     * 同节点快速路径的活跃智能体缓存：subagentId → 已公开子智能体会话。
     */
    private final ConcurrentHashMap<String, ExposedSession> exposedSessions =
            new ConcurrentHashMap<>();

    /**
     * 公开信息的持久化注册表。默认 {@link InMemorySubagentRegistry}（旧单进程行为）；
     * 配置分布式存储时注入 {@link StoreBackedSubagentRegistry}，支持跨节点／重启解析。
     */
    private volatile SubagentRegistry subagentRegistry = new InMemorySubagentRegistry();

    /**
     * 本节点没有活跃实例时（跨节点／重启后），按类型 ID 重建子智能体 {@link Agent}。
     * {@code null} 表示禁用恢复，仅缓存中的活跃会话可寻址。
     */
    private volatile SubagentMaterializer subagentMaterializer;

    /**
     * 会话 ID → 最近出站地址（用于主动推送投递）
     */
    private final ConcurrentHashMap<String, OutboundAddress> lastRouteBySession =
            new ConcurrentHashMap<>();

    /**
     * 可选持久化存储，保存会话到 gateKey 及会话到路由的映射。
     * 设置后，更新时同步写入本地缓存与持久存储，缓存未命中时查询存储（跨节点恢复）。
     */
    private volatile BaseStore baseStore;

    /**
     * 可选解析器，在应用网关身份字段前提供或替换调用方 {@link RuntimeContext}。
     */
    private volatile ChannelRuntimeContextResolver runtimeContextResolver;

    private HarnessGateway(ChannelManager channelManager, MessageBus messageBus) {
        this.channelManager = channelManager;
        this.messageBus = messageBus;
    }

    /**
     * 创建带渠道管理器的网关，用于出站投递。
     */
    public static HarnessGateway create(ChannelManager channelManager) {
        return new HarnessGateway(Objects.requireNonNull(channelManager, "channelManager"), null);
    }

    /**
     * 创建带渠道管理器和消息总线的网关。
     */
    public static HarnessGateway create(ChannelManager channelManager, MessageBus messageBus) {
        return new HarnessGateway(
                Objects.requireNonNull(channelManager, "channelManager"), messageBus);
    }

    /**
     * 创建不支持出站投递的网关。
     */
    public static HarnessGateway create() {
        return new HarnessGateway(null, null);
    }

    /**
     * 返回消息总线，未配置时为 null。
     */
    public MessageBus messageBus() {
        return messageBus;
    }

    /**
     * 出站投递的渠道管理器，未配置时为 null。
     */
    public ChannelManager channelManager() {
        return channelManager;
    }

    /**
     * 设置可选的 {@link ChannelRuntimeContextResolver}，用于构建每轮的 {@link
     * RuntimeContext}。传入 {@code null} 会清除之前配置的解析器。
     */
    public void setRuntimeContextResolver(ChannelRuntimeContextResolver runtimeContextResolver) {
        this.runtimeContextResolver = runtimeContextResolver;
    }

    /**
     * 返回已配置的 {@link ChannelRuntimeContextResolver}，没有则为 {@code null}。
     */
    public ChannelRuntimeContextResolver getRuntimeContextResolver() {
        return runtimeContextResolver;
    }

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
     * 返回以 {@code agentId} 注册的智能体；未找到时为 null。
     * 供内省使用，例如枚举智能体的技能仓库。
     */
    public HarnessAgent findAgent(String agentId) {
        if (agentId == null) return null;
        return agentRegistry.get(agentId);
    }

    @Override
    public Mono<Msg> run(MsgContext context, List<Msg> messages) {
        return run(context, messages, null, null);
    }

    @Override
    public Mono<Msg> run(MsgContext context, List<Msg> messages, OutboundAddress outboundAddress) {
        return run(context, messages, outboundAddress, null);
    }

    @Override
    public Mono<Msg> run(
            MsgContext context,
            List<Msg> messages,
            OutboundAddress outboundAddress,
            RuntimeContext callerContext) {
        return run(context, messages, outboundAddress, callerContext, null);
    }

    @Override
    public Mono<Msg> run(
            MsgContext context,
            List<Msg> messages,
            OutboundAddress outboundAddress,
            RuntimeContext callerContext,
            InboundMessage inboundMessage) {
        MsgContext ctx = context != null ? context : MsgContext.defaultContext();
        String gateKey = ctx.canonicalKey();

        String requestedAgentId = ctx.extra() != null ? ctx.extra().get("agentId") : null;
        HarnessAgent ha = resolveAgent(requestedAgentId);
        if (ha == null) {
            return Mono.error(
                    new IllegalStateException(
                            "HarnessGateway.bindMainAgent must be called before run(...)"));
        }

        String sessionId = resolveSessionId(gateKey);

        if (outboundAddress != null) {
            persistRoute(sessionId, outboundAddress);
        }

        RuntimeContext runtimeContext =
                buildRuntimeContext(
                        ctx, outboundAddress, callerContext, inboundMessage, sessionId, gateKey);

        return withGatedTurn(gateKey, () -> ha.call(messages, runtimeContext));
    }

    @Override
    public Flux<AgentEvent> runStream(
            MsgContext context, List<Msg> messages, OutboundAddress outboundAddress) {
        return runStream(context, messages, outboundAddress, null, null);
    }

    @Override
    public Flux<AgentEvent> runStream(
            MsgContext context,
            List<Msg> messages,
            OutboundAddress outboundAddress,
            RuntimeContext callerContext) {
        return runStream(context, messages, outboundAddress, callerContext, null);
    }

    @Override
    public Flux<AgentEvent> runStream(
            MsgContext context,
            List<Msg> messages,
            OutboundAddress outboundAddress,
            RuntimeContext callerContext,
            InboundMessage inboundMessage) {
        MsgContext ctx = context != null ? context : MsgContext.defaultContext();
        String gateKey = ctx.canonicalKey();

        String requestedAgentId = ctx.extra() != null ? ctx.extra().get("agentId") : null;
        HarnessAgent ha = resolveAgent(requestedAgentId);
        if (ha == null) {
            return Flux.error(
                    new IllegalStateException(
                            "HarnessGateway.bindMainAgent must be called before runStream(...)"));
        }

        String sessionId = resolveSessionId(gateKey);

        if (outboundAddress != null) {
            persistRoute(sessionId, outboundAddress);
        }

        RuntimeContext runtimeContext =
                buildRuntimeContext(
                        ctx, outboundAddress, callerContext, inboundMessage, sessionId, gateKey);

        return withGatedStream(gateKey, () -> ha.streamEvents(messages, runtimeContext));
    }

    /**
     * 构建网关轮次实际使用的 {@link RuntimeContext}。
     *
     * <ol>
     *   <li>从 {@code callerContext} 开始（可为 null）
     *   <li>若已配置 {@link ChannelRuntimeContextResolver} 且返回非 null，则用其结果替换调用方基础上下文
     *   <li>应用网关身份字段（{@code sessionId}、{@code userId}、{@code msgContext}、{@code
     *       gateKey}、{@code outboundAddress}），冲突时它们优先
     * </ol>
     *
     * @param inboundMessage 供解析器使用的可选入站信封；可为 null
     */
    RuntimeContext buildRuntimeContext(
            MsgContext ctx,
            OutboundAddress outboundAddress,
            RuntimeContext callerContext,
            InboundMessage inboundMessage,
            String sessionId,
            String gateKey) {
        RuntimeContext base = callerContext;
        ChannelRuntimeContextResolver resolver = this.runtimeContextResolver;
        if (resolver != null) {
            ChannelRuntimeContextRequest request =
                    new ChannelRuntimeContextRequest(
                            ctx != null ? ctx.channel() : null,
                            inboundMessage,
                            ctx,
                            outboundAddress,
                            callerContext);
            RuntimeContext resolved = resolver.resolve(request);
            if (resolved != null) {
                base = resolved;
            }
        }

        // 【学习提示】这里覆盖的是实际提供的网关字段：userId 仅在非空白时写入，
        // outboundAddress 仅在非 null 时写入；缺失字段不会自动清除 base 中的同名值。
        // 这些上下文合并操作不负责认证，身份可信性仍由应用／渠道入口保证。
        RuntimeContext.Builder rtcBuilder = RuntimeContext.builder(base).sessionId(sessionId);
        if (ctx != null) {
            rtcBuilder.put("msgContext", ctx);
        }
        if (gateKey != null) {
            rtcBuilder.put("gateKey", gateKey);
        }
        if (outboundAddress != null) {
            rtcBuilder.put("outboundAddress", outboundAddress);
        }
        if (ctx != null && ctx.userId() != null && !ctx.userId().isBlank()) {
            rtcBuilder.userId(ctx.userId());
        }
        return rtcBuilder.build();
    }

    /**
     * 使用会话最近记录的出站地址，通过渠道管理器投递主动消息。
     * 没有路由或渠道管理器时返回 false。
     */
    public boolean deliverToSession(String sessionId, List<Msg> messages) {
        if (channelManager == null || sessionId == null || messages == null || messages.isEmpty()) {
            return false;
        }
        OutboundAddress target = resolveRoute(sessionId);
        if (target == null) {
            log.debug("Cannot deliver to session {}: no outbound address recorded", sessionId);
            return false;
        }
        channelManager.deliver(target, messages);
        return true;
    }

    // -----------------------------------------------------------------
    // 已公开子智能体的路由
    // -----------------------------------------------------------------

    private record ExposedSession(
            String subagentId,
            String agentId,
            String sessionId,
            Agent agent,
            OutboundAddress replyTo) {}

    /**
     * 将已创建的子智能体公开为用户可寻址入口，返回分配的 subagentId。
     *
     * @param agentId 子智能体类型标识
     * @param sessionId 分配给子智能体的会话 ID
     * @param agent 智能体实例
     * @param replyTo 回复投递的出站地址；可为 null
     * @return 用于直接寻址的 subagentId 句柄
     */
    public String exposeSubagent(
            String agentId, String sessionId, Agent agent, OutboundAddress replyTo) {
        Objects.requireNonNull(agent, "agent");
        String subagentId = "sub-" + UUID.randomUUID().toString().substring(0, 8);
        exposedSessions.put(
                subagentId, new ExposedSession(subagentId, agentId, sessionId, agent, replyTo));
        try {
            subagentRegistry.register(
                    new SubagentRecord(
                            subagentId, agentId, sessionId, null, null, Instant.now(), null));
        } catch (RuntimeException e) {
            log.warn(
                    "Failed to persist exposed-subagent record {}: {}", subagentId, e.getMessage());
        }
        log.debug(
                "Exposed subagent agentId={} sessionId={} as subagentId={}",
                agentId,
                sessionId,
                subagentId);
        return subagentId;
    }

    /**
     * 撤销先前公开的子智能体，从活跃缓存和注册表中移除。
     */
    public void revokeSubagent(String subagentId) {
        if (subagentId != null) {
            exposedSessions.remove(subagentId);
            try {
                subagentRegistry.revoke(subagentId);
            } catch (RuntimeException e) {
                log.debug("Registry revoke failed for {}: {}", subagentId, e.getMessage());
            }
        }
    }

    /**
     * 安装持久化 {@link SubagentRegistry}。在网关装配期间调用；未设置时默认使用内存注册表。
     */
    public void setSubagentRegistry(SubagentRegistry registry) {
        if (registry != null) {
            this.subagentRegistry = registry;
        }
    }

    /**
     * 安装 {@link SubagentMaterializer}，用于重建本地活跃缓存中不存在的子智能体
     * （跨节点／重启后恢复）。
     */
    public void setSubagentMaterializer(SubagentMaterializer materializer) {
        this.subagentMaterializer = materializer;
    }

    /**
     * 安装用于跨节点持久化会话路由元数据的 {@link BaseStore}。
     * 配置分布式存储时，在网关装配期间调用。
     */
    public void setBaseStore(BaseStore store) {
        this.baseStore = store;
    }

    /**
     * 安装按会话串行化并发轮次的 {@link SessionTurnGate}。
     * 未设置时默认使用 {@link LocalSessionTurnGate}。
     *
     * @param gate 轮次门控实现；为 {@code null} 时忽略
     */
    public void setSessionTurnGate(SessionTurnGate gate) {
        if (gate != null) {
            this.sessionTurnGate = gate;
        }
    }

    /**
     * 将已公开子智能体解析为可运行会话：缓存中存在活跃会话时直接返回，
     * 否则通过配置的 {@link SubagentMaterializer} 根据持久化注册表重建。
     * 句柄未知、已过期或无法重建时返回 {@code null}。
     */
    private ExposedSession resolveExposed(String subagentId) {
        ExposedSession live = exposedSessions.get(subagentId);
        if (live != null) {
            return live;
        }
        SubagentMaterializer materializer = this.subagentMaterializer;
        if (materializer == null) {
            return null;
        }
        Optional<SubagentRecord> recordOpt = subagentRegistry.find(subagentId);
        if (recordOpt.isEmpty()) {
            return null;
        }
        SubagentRecord record = recordOpt.get();
        RuntimeContext.Builder rcb = RuntimeContext.builder().sessionId(record.sessionId());
        if (record.userId() != null && !record.userId().isBlank()) {
            rcb.userId(record.userId());
        }
        Optional<Agent> agentOpt = materializer.materialize(record.agentId(), rcb.build());
        if (agentOpt.isEmpty()) {
            log.warn(
                    "Cannot re-materialize exposed subagent {} (agentId={}): unknown type",
                    subagentId,
                    record.agentId());
            return null;
        }
        ExposedSession rebuilt =
                new ExposedSession(
                        record.subagentId(),
                        record.agentId(),
                        record.sessionId(),
                        agentOpt.get(),
                        null);
        exposedSessions.putIfAbsent(subagentId, rebuilt);
        return exposedSessions.get(subagentId);
    }

    @Override
    public Mono<Msg> runSubagent(String subagentId, List<Msg> messages) {
        ExposedSession session = resolveExposed(subagentId);
        if (session == null) {
            return Mono.error(new IllegalArgumentException("Unknown subagentId: " + subagentId));
        }

        RuntimeContext rtc = RuntimeContext.builder().sessionId(session.sessionId()).build();
        String gateKey = "subagent:" + subagentId;

        return withGatedTurn(gateKey, () -> invokeExposedAgent(session.agent(), messages, rtc))
                .doOnNext(
                        reply -> {
                            if (channelManager != null
                                    && session.replyTo() != null
                                    && reply != null) {
                                channelManager.deliver(session.replyTo(), List.of(reply));
                            }
                        });
    }

    private Mono<Msg> invokeExposedAgent(Agent agent, List<Msg> messages, RuntimeContext ctx) {
        if (agent instanceof HarnessAgent ha) {
            return ha.call(messages, ctx);
        }
        if (agent instanceof ReActAgent ra) {
            return ra.call(messages, ctx);
        }
        return agent.call(messages);
    }

    // -----------------------------------------------------------------
    // 内部实现
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

    private static String resolveAgentId(HarnessAgent ha) {
        String id = ha != null ? ha.getAgentId() : null;
        return (id != null && !id.isBlank()) ? id : "main";
    }

    private static String generateSessionId(String gateKey) {
        return "gw-" + SessionIdUtils.deterministicHash(gateKey);
    }

    @Override
    public Flux<AgentEvent> runSubagentStream(String subagentId, List<Msg> messages) {
        ExposedSession session = resolveExposed(subagentId);
        if (session == null) {
            return Flux.error(new IllegalArgumentException("Unknown subagentId: " + subagentId));
        }

        RuntimeContext rtc = RuntimeContext.builder().sessionId(session.sessionId()).build();
        String gateKey = "subagent:" + subagentId;

        return withGatedStream(gateKey, () -> streamExposedAgent(session.agent(), messages, rtc));
    }

    private Flux<AgentEvent> streamExposedAgent(
            Agent agent, List<Msg> messages, RuntimeContext ctx) {
        if (agent instanceof HarnessAgent ha) {
            return ha.streamEvents(messages, ctx);
        }
        if (agent instanceof ReActAgent ra) {
            return ra.streamEvents(messages, ctx);
        }
        return Flux.error(
                new UnsupportedOperationException(
                        "Agent type "
                                + agent.getClass().getName()
                                + " does not support streaming"));
    }

    // ------------------------------------------------------------------
    // 会话辅助方法
    // ------------------------------------------------------------------

    private String resolveSessionId(String gateKey) {
        String sessionId = generateSessionId(gateKey);
        String previous = sessionToGateKey.put(sessionId, gateKey);
        if (previous == null || !previous.equals(gateKey)) {
            persistSessionGateKey(sessionId, gateKey);
        }
        return sessionId;
    }

    /**
     * 接纳控制面分配的会话 ID，使 {@link #runWakeup(String, String)} 能解析它。
     * 由自带基础设施的 {@code team_join} 使用；受管会话已使用受管轮次路径直接接受的产品 ID，无需此映射。
     *
     * @param sessionId 控制面分配的外部会话 ID
     * @param gateKey 稳定门控键（例如 {@code team:{teamName}:{role}}）
     */
    public void registerExternalSession(String sessionId, String gateKey) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId required");
        }
        if (gateKey == null || gateKey.isBlank()) {
            throw new IllegalArgumentException("gateKey required");
        }
        sessionToGateKey.put(sessionId, gateKey);
        persistSessionGateKey(sessionId, gateKey);
    }

    /**
     * 指定会话当前处于受门控轮次中（正在运行）时返回 {@code true}。
     * {@link WakeupDispatcher} 用它跳过正在本轮推理步骤中自然排空收件箱的会话。
     */
    public boolean isSessionRunning(String sessionId) {
        String gateKey = resolveSessionGateKey(sessionId);
        return gateKey != null && sessionTurnGate.isRunning(gateKey);
    }

    /**
     * {@inheritDoc}
     *
     * <p>以 {@code null} 用户 ID 委托给 {@link #runWakeup(String, String)}。
     *
     * @deprecated 请使用 {@link #runWakeup(String, String)}，使唤醒轮次从正确的
     *     {@code (userId, sessionId)} 槽位加载智能体状态。此重载保留旧的匿名唤醒行为。
     */
    @Deprecated
    @Override
    public Mono<Msg> runWakeup(String sessionId) {
        return runWakeup(null, sessionId);
    }

    /**
     * 为闲置会话触发唤醒执行，并在存在所属用户 ID 时向下传递。
     * 后台任务完成或团队消息到达时由 {@link WakeupDispatcher} 调用。
     * 智能体在没有用户输入的情况下开始推理轮次；{@link
     * io.agentscope.harness.agent.middleware.InboxMiddleware} 排空收件箱并将待处理结果注入上下文。
     *
     * <p>{@code userId} 非空白时写入 {@link RuntimeContext}，使智能体从与原用户调用相同的
     * {@code (userId, sessionId)} 槽位加载状态，与 {@code runStream} 一致。
     * 用户 ID 为空白或 null 时保留旧匿名行为。
     *
     * @param userId 唤醒条目携带的所属用户 ID；可为 {@code null} 或空白
     * @param sessionId 要唤醒的会话
     * @return 智能体响应；未知会话时为 {@link Mono#empty()}
     */
    @Override
    public Mono<Msg> runWakeup(String userId, String sessionId) {
        String gateKey = resolveSessionGateKey(sessionId);
        if (gateKey == null) {
            log.debug("runWakeup: unknown sessionId={}, skipping", sessionId);
            return Mono.empty();
        }
        HarnessAgent ha = resolveAgent(null);
        if (ha == null) {
            return Mono.empty();
        }
        RuntimeContext.Builder rtcBuilder =
                RuntimeContext.builder()
                        .sessionId(sessionId)
                        .put("gateKey", gateKey)
                        .put("outboundAddress", resolveRoute(sessionId));
        if (userId != null && !userId.isBlank()) {
            rtcBuilder.userId(userId);
        }
        RuntimeContext rtc = rtcBuilder.build();

        if (messageBus == null) {
            return withGatedTurn(gateKey, () -> ha.call(List.of(), rtc));
        }

        return withGatedTurn(
                gateKey,
                () ->
                        ha.streamEvents(List.of(), rtc)
                                .doOnNext(event -> publishEventToSession(sessionId, event))
                                .filter(e -> e instanceof AgentResultEvent)
                                .cast(AgentResultEvent.class)
                                .map(AgentResultEvent::getResult)
                                .last());
    }

    private void publishEventToSession(String sessionId, AgentEvent event) {
        try {
            Map<String, Object> payload =
                    io.agentscope.core.util.JsonUtils.getJsonCodec()
                            .convertValue(
                                    event,
                                    new com.fasterxml.jackson.core.type.TypeReference<
                                            Map<String, Object>>() {});
            messageBus.sessionPublishEvent(sessionId, payload).subscribe();
        } catch (Exception e) {
            log.debug("Failed to publish event to session {}: {}", sessionId, e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 轮次串行化
    // ------------------------------------------------------------------

    // 【学习提示】acquire 可能阻塞，因此订阅安排到 boundedElastic；defer 决定获取时机，
    // doFinally 终止时尝试清理已记录租约；取消与 acquire/set 交错时不能据此保证租约必定释放。
    // 注意 leaseRef 在 defer 外创建，同一个返回值被重复／并发订阅
    // 时会共享它；不能由“使用 defer”推断所有租约状态都按订阅隔离。
    private Mono<Msg> withGatedTurn(String gateKey, Supplier<Mono<Msg>> turn) {
        AtomicReference<TurnLease> leaseRef = new AtomicReference<>();
        return Mono.defer(
                        () -> {
                            try {
                                leaseRef.set(sessionTurnGate.acquire(gateKey));
                            } catch (TurnBusyException e) {
                                log.debug(
                                        "Skipping turn for {}: gate busy ({})",
                                        gateKey,
                                        e.getMessage());
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

    private Flux<AgentEvent> withGatedStream(String gateKey, Supplier<Flux<AgentEvent>> stream) {
        AtomicReference<TurnLease> leaseRef = new AtomicReference<>();
        return Flux.defer(
                        () -> {
                            try {
                                leaseRef.set(sessionTurnGate.acquire(gateKey));
                            } catch (TurnBusyException e) {
                                log.debug(
                                        "Skipping stream for {}: gate busy ({})",
                                        gateKey,
                                        e.getMessage());
                                return Flux.empty();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return Flux.error(new IllegalStateException(e));
                            }
                            return stream.get();
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

    // ------------------------------------------------------------------
    // 路由持久化（本地缓存及可选 BaseStore）
    // ------------------------------------------------------------------

    private String resolveSessionGateKey(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        String gateKey = sessionToGateKey.get(sessionId);
        if (gateKey != null) {
            return gateKey;
        }
        BaseStore store = this.baseStore;
        if (store == null) {
            return null;
        }
        try {
            StoreItem item = store.get(SESSION_NAMESPACE, sessionId);
            if (item == null || item.value() == null) {
                return null;
            }
            Object value = item.value().get("gateKey");
            if (value == null) {
                return null;
            }
            gateKey = String.valueOf(value);
            sessionToGateKey.put(sessionId, gateKey);
            return gateKey;
        } catch (RuntimeException e) {
            log.debug(
                    "Failed to load session gate key for {} from store: {}",
                    sessionId,
                    e.getMessage());
            return null;
        }
    }

    private OutboundAddress resolveRoute(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        OutboundAddress route = lastRouteBySession.get(sessionId);
        if (route != null) {
            return route;
        }
        BaseStore store = this.baseStore;
        if (store == null) {
            return null;
        }
        try {
            StoreItem item = store.get(ROUTE_NAMESPACE, sessionId);
            if (item == null || item.value() == null) {
                return null;
            }
            route = OutboundAddress.fromMap(item.value());
            if (route != null) {
                lastRouteBySession.put(sessionId, route);
            }
            return route;
        } catch (RuntimeException e) {
            log.debug(
                    "Failed to load outbound route for {} from store: {}",
                    sessionId,
                    e.getMessage());
            return null;
        }
    }

    private void persistSessionGateKey(String sessionId, String gateKey) {
        if (sessionId == null || gateKey == null) {
            return;
        }
        BaseStore store = this.baseStore;
        if (store == null) {
            return;
        }
        try {
            Map<String, Object> value = new HashMap<>();
            value.put("gateKey", gateKey);
            store.put(SESSION_NAMESPACE, sessionId, value);
        } catch (RuntimeException e) {
            log.warn("Failed to persist session gate key for {}: {}", sessionId, e.getMessage());
        }
    }

    private void persistRoute(String sessionId, OutboundAddress outboundAddress) {
        lastRouteBySession.put(sessionId, outboundAddress);
        BaseStore store = this.baseStore;
        if (store == null || outboundAddress == null) {
            return;
        }
        try {
            store.put(ROUTE_NAMESPACE, sessionId, outboundAddress.toMap());
        } catch (RuntimeException e) {
            log.warn("Failed to persist outbound route for {}: {}", sessionId, e.getMessage());
        }
    }
}
