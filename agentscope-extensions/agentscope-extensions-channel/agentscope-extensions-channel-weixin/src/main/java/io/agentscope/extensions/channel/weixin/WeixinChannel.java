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
package io.agentscope.extensions.channel.weixin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.extensions.channel.common.BotLoopGuard;
import io.agentscope.harness.agent.gateway.Gateway;
import io.agentscope.harness.agent.gateway.channel.Channel;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import io.agentscope.harness.agent.gateway.channel.ChannelRouter;
import io.agentscope.harness.agent.gateway.channel.InboundMessage;
import io.agentscope.harness.agent.gateway.channel.OutboundAddress;
import io.agentscope.harness.agent.gateway.channel.RouteResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * 基于腾讯官方 iLink 长轮询 API 的个人微信适配器。
 */
public final class WeixinChannel implements Channel {
    public static final String TYPE = "weixin";
    private static final Logger log = LoggerFactory.getLogger(WeixinChannel.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String id;
    private final ChannelConfig config;
    private final WeixinChannelProperties p;
    private final WeixinInboundMapper mapper;
    private final WeixinOutboundClient outbound;
    private final ChannelRouter router;
    private final WeixinStateStore stateStore;
    private final WeixinRuntimeListener runtimeListener;
    private final BotLoopGuard guard = new BotLoopGuard();
    private final ScheduledExecutorService executor;
    private final ScheduledExecutorService heartbeat =
            Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        Thread t = new Thread(r, "weixin-lease-renewal");
                        t.setDaemon(true);
                        return t;
                    });

    /**
     * 同一次租约持有期间每条收件箱消息的分发尝试次数。新持有者获得新预算；同一持有期内，此限制防止持续分发失败的消息无限次重新运行智能体。
     */
    private static final int MAX_DISPATCH_ATTEMPTS = 3;

    private volatile LeaseSession active;
    private volatile Gateway gateway;
    private volatile boolean running;
    private volatile long backoff = 1000;
    private final String leaseHolder = java.util.UUID.randomUUID().toString();

    private WeixinChannel(
            String id,
            ChannelConfig c,
            WeixinChannelProperties p,
            WeixinCredentialProvider credentialProvider,
            WeixinStateStore stateStore,
            WeixinRuntimeListener runtimeListener) {
        this.id = id;
        this.config = c;
        this.p = p;
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.runtimeListener = Objects.requireNonNull(runtimeListener, "runtimeListener");
        // iLink 的 ilink_user_id 标识扫码的人。因此不能
        // 按此值过滤其他联系人的入站消息；防循环使用的身份
        // 是机器人／账户 ID。
        this.mapper = new WeixinInboundMapper(id, p.accountId(), p.accountId());
        this.outbound = new WeixinOutboundClient(p, credentialProvider);
        this.router = new ChannelRouter(c.defaultAgentId());
        this.executor =
                Executors.newSingleThreadScheduledExecutor(
                        r -> {
                            Thread t = new Thread(r, "weixin-" + p.accountId());
                            t.setDaemon(true);
                            return t;
                        });
    }

    public static WeixinChannel fromProperties(
            String id, ChannelConfig c, Map<String, Object> props) {
        Map<String, Object> raw = props == null ? Map.of() : props;
        Object token = raw.get("botToken");
        if (token == null || token.toString().isBlank()) {
            throw new IllegalArgumentException(
                    "weixin.botToken is required by the standalone properties factory");
        }
        log.warn(
                "Weixin channel '{}' uses the standalone properties factory: credentials and"
                        + " runtime state stay in this process and are lost on restart. Production"
                        + " hosts should call WeixinChannel.create(...) with a durable"
                        + " WeixinStateStore.",
                id);
        return create(
                id,
                c,
                WeixinChannelProperties.from(id, raw),
                WeixinCredentialProvider.fixed(token.toString()),
                WeixinStateStore.inMemory(),
                WeixinRuntimeListener.noOp());
    }

    /**
 * 使用宿主提供的凭据、状态和运行时观察器构造渠道。
 */
    public static WeixinChannel create(
            String id,
            ChannelConfig config,
            WeixinChannelProperties properties,
            WeixinCredentialProvider credentialProvider,
            WeixinStateStore stateStore,
            WeixinRuntimeListener runtimeListener) {
        return new WeixinChannel(
                Objects.requireNonNull(id, "id"),
                Objects.requireNonNull(config, "config"),
                Objects.requireNonNull(properties, "properties"),
                Objects.requireNonNull(credentialProvider, "credentialProvider"),
                Objects.requireNonNull(stateStore, "stateStore"),
                Objects.requireNonNull(runtimeListener, "runtimeListener"));
    }

    public String channelId() {
        return id;
    }

    public ChannelConfig config() {
        return config;
    }

    public void init(Gateway g) {
        if (gateway == null) gateway = Objects.requireNonNull(g, "gateway");
    }

    public synchronized void start() {
        if (running) return;
        if (executor.isShutdown())
            throw new WeixinOperationException("Stopped channel cannot restart");
        running = true;
        executor.execute(this::poll);
        log.info("Weixin channel '{}' started: accountId={}", id, p.accountId());
    }

    public void stop() {
        running = false;
        LeaseSession session = active;
        if (session != null) session.cancelled.tryEmitEmpty();
        heartbeat.shutdownNow();
        executor.shutdownNow();
        log.info("Weixin channel '{}' stopped", id);
    }

    public Mono<Msg> dispatch(InboundMessage in) {
        return Mono.defer(() -> dispatch(in, active));
    }

    private Mono<Msg> dispatch(InboundMessage in, LeaseSession session) {
        return Mono.defer(
                () -> {
                    requireLease(session);
                    Gateway g = gateway;
                    if (g == null)
                        return Mono.error(
                                new WeixinOperationException("Weixin channel has no gateway"));
                    RouteResult route = router.resolveRoute(config, in);
                    return g.run(
                                    route.context(),
                                    in.messages(),
                                    route.outboundAddress(),
                                    in.runtimeContext(),
                                    in)
                            .flatMap(reply -> deliverReply(route, in, reply, session))
                            .takeUntilOther(session.cancelled.asMono());
                });
    }

    /**
     * 在内联路径发送智能体回复。由宿主负责持久回复投递时，其网关不返回回复；网关确实返回回复时，渠道负责投递，且必须传播失败，使收件箱仍可重试。与所有至少一次分发一样，此单机路径重试可能再次运行智能体。
     */
    private Mono<Msg> deliverReply(
            RouteResult route, InboundMessage in, Msg reply, LeaseSession session) {
        return outbound.sendWithContext(
                        route.outboundAddress(),
                        List.of(reply),
                        context(in),
                        () -> requireLease(session))
                .doOnError(
                        error -> {
                            if (error instanceof WeixinCredentialRejectedException) return;
                            notifyListener(
                                    "delivery failed",
                                    listener ->
                                            listener.onDeliveryFailed(
                                                    p.accountId(), safeMessage(error)));
                        })
                .thenReturn(reply);
    }

    /**
     * 发送一条宿主已持久保存的回复，并返回服务方回执。宿主投递工作器调用此方法；失败必须可见，使工作器保持通知待处理并反馈错误。因此除了服务方自身的回复文本外，不吞掉任何失败。
     */
    @Override
    public Mono<String> deliverWithReceipt(
            OutboundAddress address, Msg message, String deliveryId) {
        return Mono.defer(
                () -> {
                    LeaseSession session = active;
                    requireLease(session);
                    return outbound.sendWithReceipt(
                            address,
                            message,
                            stateStore.loadContextToken(p.accountId(), peerId(address)),
                            () -> requireLease(session));
                });
    }

    public void deliver(OutboundAddress address, List<Msg> messages) {
        if (messages == null || messages.isEmpty()) return;
        LeaseSession session = active;
        requireLease(session);
        outbound.sendWithContext(
                        address,
                        messages,
                        stateStore.loadContextToken(p.accountId(), peerId(address)),
                        () -> requireLease(session))
                .doOnError(
                        error ->
                                log.warn(
                                        "Weixin delivery failed: {}",
                                        error.getClass().getSimpleName()))
                .subscribe(
                        ignored -> {},
                        error -> {
                            if (active == session
                                    && session.valid
                                    && error
                                            instanceof WeixinCredentialRejectedException rejected) {
                                notifyCredentialRejected(rejected);
                                stop();
                            }
                        });
    }

    private static String peerId(OutboundAddress address) {
        String value = address.to();
        int i = value.lastIndexOf(':');
        return i < 0 ? value : value.substring(i + 1);
    }

    private String context(InboundMessage in) {
        if (in.messages().isEmpty() || in.messages().get(0).getMetadata() == null) return null;
        Object v = in.messages().get(0).getMetadata().get("weixinContextToken");
        return v == null ? null : v.toString();
    }

    private void poll() {
        try {
            while (running && !Thread.currentThread().isInterrupted()) {
                try {
                    Optional<WeixinLease> lease =
                            stateStore.acquireLease(p.accountId(), leaseHolder, p.leaseMs());
                    if (lease.isEmpty()) {
                        sleep(Math.min(1000, Math.max(10, p.leaseMs() / 3)));
                        continue;
                    }
                    consume(new LeaseSession(lease.get()));
                } catch (RuntimeException error) {
                    if (running) {
                        log.warn("Weixin consumer retry: {}", safeMessage(error));
                        sleep(backoff);
                    }
                }
            }
        } finally {
            running = false;
        }
    }

    private void consume(LeaseSession session) {
        active = session;
        java.util.concurrent.ScheduledFuture<?> renewal = null;
        boolean providerStarted = false;
        try {
            renewal =
                    heartbeat.scheduleWithFixedDelay(
                            () -> renew(session),
                            Math.max(1, p.leaseMs() / 3),
                            Math.max(1, p.leaseMs() / 3),
                            java.util.concurrent.TimeUnit.MILLISECONDS);
            notifyListener(
                    "lease acquired",
                    listener -> listener.onLeaseAcquired(p.accountId(), session.lease));
            try {
                requireLease(session);
                outbound.notifyStart(() -> requireLease(session));
                providerStarted = true;
                notifyListener("running", listener -> listener.onRunning(p.accountId()));
            } catch (WeixinCredentialRejectedException error) {
                throw error;
            } catch (Exception error) {
                notifyListener(
                        "transient failure",
                        listener -> listener.onTransientFailure(p.accountId(), safeMessage(error)));
                // 服务方会话从未启动：没有可轮询或停止的对象。
                // 退出当前租约，让外层循环退避、重新获取并重试启动。
                throw new WeixinOperationException("Weixin provider session did not start");
            }
            while (running && session.valid && !Thread.currentThread().isInterrupted()) {
                // 轮询与消费独立失败。服务方停止响应时，不能
                // 冻结消费者已接受的消息；某条消息无法分发时，
                // 也不能阻止游标推进。
                boolean polled = false;
                try {
                    requireLease(session);
                    pollOnce(session);
                    polled = true;
                } catch (WeixinCredentialRejectedException error) {
                    throw error;
                } catch (Exception error) {
                    if (!running || !session.valid) break;
                    notifyTransientFailure(error);
                    sleep(backoff);
                    backoff = Math.min(p.maxBackoffMs(), backoff * 2);
                }
                try {
                    requireLease(session);
                    drainInbox(session);
                    if (polled) {
                        backoff = 1000;
                        notifyListener(
                                "recovered", listener -> listener.onRecovered(p.accountId()));
                    }
                } catch (WeixinCredentialRejectedException error) {
                    throw error;
                } catch (Exception error) {
                    if (!running || !session.valid) break;
                    notifyTransientFailure(error);
                    sleep(backoff);
                    backoff = Math.min(p.maxBackoffMs(), backoff * 2);
                }
            }
        } catch (WeixinCredentialRejectedException error) {
            notifyCredentialRejected(error);
            running = false;
        } finally {
            // 停止也是外部副作用：过期消费者不得停止其后继消费者。
            try {
                if (providerStarted
                        && session.valid
                        && stateStore.isLeaseCurrent(p.accountId(), session.lease))
                    outbound.notifyStop(
                            () -> {
                                if (!stateStore.isLeaseCurrent(p.accountId(), session.lease))
                                    throw new WeixinOperationException("Weixin lease lost");
                            });
            } catch (Exception error) {
                log.debug("Weixin notifyStop failed: {}", safeMessage(error));
            } finally {
                if (renewal != null) renewal.cancel(false);
                loseLease(session);
                try {
                    stateStore.releaseLease(p.accountId(), session.lease);
                } catch (RuntimeException error) {
                    log.warn("Weixin lease release failed: {}", safeMessage(error));
                }
                active = null;
                notifyListener("stopped", listener -> listener.onStopped(p.accountId()));
            }
        }
    }

    private void renew(LeaseSession session) {
        try {
            if (!running
                    || !session.valid
                    || !stateStore.renewLease(p.accountId(), session.lease, p.leaseMs())) {
                loseLease(session);
            }
        } catch (RuntimeException error) {
            loseLease(session);
        }
    }

    private void notifyTransientFailure(Exception error) {
        notifyListener(
                "transient failure",
                listener -> listener.onTransientFailure(p.accountId(), safeMessage(error)));
    }

    private void requireLease(LeaseSession session) {
        if (session == null || !running || !session.valid || active != session)
            throw new WeixinOperationException("Weixin consumer is not active");
        try {
            if (stateStore.isLeaseCurrent(p.accountId(), session.lease)) return;
        } catch (RuntimeException error) {
            loseLease(session);
            throw error;
        }
        loseLease(session);
        throw new WeixinOperationException("Weixin lease lost");
    }

    private void loseLease(LeaseSession session) {
        session.valid = false;
        session.cancelled.tryEmitEmpty();
    }

    /**
 * 一次长轮询及其接受的批次。
 */
    private void pollOnce(LeaseSession session) throws Exception {
        requireLease(session);
        var response = outbound.updates(stateStore.loadCursor(p.accountId()));
        requireLease(session);
        if (response.ret() == -14 || (response.errcode() != null && response.errcode() == -14)) {
            throw new WeixinCredentialRejectedException("getupdates", -14);
        }
        if (response.ret() != 0 || (response.errcode() != null && response.errcode() != 0)) {
            throw new WeixinOperationException("iLink polling failed");
        }
        if (!stateStore.acceptBatch(
                p.accountId(),
                session.lease,
                response.get_updates_buf(),
                inboxMessages(response.msgs()))) {
            loseLease(session);
            throw new WeixinOperationException("Weixin lease lost");
        }
    }

    private void processMessage(JsonNode message, LeaseSession session) {
        mapper.map(message)
                .ifPresent(
                        inbound -> {
                            requireLease(session);
                            if (!guard.allow(inbound.peer().key()))
                                throw new WeixinOperationException("Weixin peer rate limited");
                            String token = context(inbound);
                            if (token != null
                                    && !stateStore.saveContextToken(
                                            p.accountId(),
                                            session.lease,
                                            inbound.peer().id(),
                                            token)) {
                                loseLease(session);
                                throw new WeixinOperationException("Weixin context update fenced");
                            }
                            dispatch(inbound, session)
                                    .block(Duration.ofMillis(p.dispatchTimeoutMs()));
                            requireLease(session);
                        });
    }

    /**
 * 仅由消费者线程调用，该线程拥有会话分发重试计数器。
 */
    private void drainInbox(LeaseSession session) throws Exception {
        while (running && session.valid && !Thread.currentThread().isInterrupted()) {
            requireLease(session);
            List<WeixinInboxClaim> claims =
                    stateStore.claimMessages(
                            p.accountId(),
                            session.lease,
                            1,
                            (long) p.dispatchTimeoutMs() + p.leaseMs());
            if (claims.isEmpty()) return;
            WeixinInboxClaim claim = claims.get(0);
            try {
                processMessage(JSON.readTree(claim.payload()), session);
                if (!stateStore.completeMessage(p.accountId(), session.lease, claim)) {
                    loseLease(session);
                    throw new WeixinOperationException("Weixin inbox completion fenced");
                }
                session.dispatchFailures.remove(claim.messageId());
            } catch (WeixinCredentialRejectedException error) {
                // 重新授权可恢复此消息。账户凭据被拒绝
                // 不代表消息有毒，不应消耗其分发预算。
                // false 表示认领已被隔离令牌拒绝；无论如何都传播凭据拒绝，
                // 让 consume() 停止此消费者并释放租约。
                stateStore.failMessage(p.accountId(), session.lease, claim);
                throw error;
            } catch (Exception error) {
                if (session.dispatchFailures.merge(claim.messageId(), 1, Integer::sum)
                        >= MAX_DISPATCH_ATTEMPTS) {
                    session.dispatchFailures.remove(claim.messageId());
                    if (stateStore.abandonMessage(p.accountId(), session.lease, claim)) {
                        log.warn(
                                "Weixin message abandoned after {} attempts: {}",
                                MAX_DISPATCH_ATTEMPTS,
                                safeMessage(rootCause(error)));
                        notifyListener(
                                "dispatch failed",
                                listener ->
                                        listener.onDispatchFailed(
                                                p.accountId(), safeMessage(error)));
                    }
                } else {
                    stateStore.failMessage(p.accountId(), session.lease, claim);
                }
                throw error;
            }
        }
    }

    private static final class LeaseSession {
        final WeixinLease lease;
        // 只有消费者线程修改此映射。释放会话时，也释放
        // 已被其他持有者完成或停用的消息计数器。
        final Map<String, Integer> dispatchFailures = new HashMap<>();
        final reactor.core.publisher.Sinks.Empty<Void> cancelled =
                reactor.core.publisher.Sinks.empty();
        volatile boolean valid = true;

        LeaseSession(WeixinLease lease) {
            this.lease = lease;
        }
    }

    /**
     * 为每条消息推导一个持久化收件箱 ID。
     *
     * <p>服务方未提供 {@code message_id} 时，使用消息在批次中的位置加上载荷摘要作为键。位置使两条字节完全相同但无 ID 的消息（用户连续输入相同内容）不会折叠为一次认领并丢失其中一条；重复投递同一批次仍产生相同 ID，因而可以去重。
     */
    private List<WeixinInboxMessage> inboxMessages(List<JsonNode> messages) {
        List<JsonNode> batch = messages == null ? List.of() : messages;
        List<WeixinInboxMessage> result = new ArrayList<>(batch.size());
        for (int position = 0; position < batch.size(); position++) {
            JsonNode message = batch.get(position);
            String payload = message.toString();
            int index = position;
            String messageId =
                    WeixinInboundMapper.messageId(message)
                            .orElseGet(() -> "payload-" + index + "-" + digest(payload));
            result.add(new WeixinInboxMessage(messageId, payload));
        }
        return result;
    }

    private static String digest(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private void notifyListener(String event, Consumer<WeixinRuntimeListener> notification) {
        try {
            notification.accept(runtimeListener);
        } catch (RuntimeException e) {
            log.warn(
                    "Weixin runtime listener failed during '{}' for account '{}': {}",
                    event,
                    p.accountId(),
                    safeMessage(e));
        }
    }

    private void notifyCredentialRejected(WeixinCredentialRejectedException error) {
        notifyListener(
                "credential rejected",
                listener -> listener.onCredentialRejected(p.accountId(), error.getMessage()));
    }

    /**
     * 解开 Reactor 添加的操作符包装，使报告失败时呈现实际原因，而非 {@code RetryExhaustedException}。
     */
    private static Throwable rootCause(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        return cause;
    }

    /**
 * 交给宿主的最长诊断长度，限制异常消息体积。
 */
    private static final int MAX_SAFE_MESSAGE_LENGTH = 160;

    /**
     * 报告失败，但不允许任意文本进入宿主。只有本模块自身的失败消息可安全报告：其文本仅由常量和服务方数字码组成，绝不复制服务方的 {@code errmsg}。其他失败（SQL、HTTP、Reactor）仅报告类型，因为消息可能嵌入凭据、请求 URI 或载荷。
     */
    private static String safeMessage(Throwable error) {
        Throwable cause = rootCause(error);
        String type = cause.getClass().getSimpleName();
        if (!(cause instanceof WeixinCredentialRejectedException
                || cause instanceof WeixinOperationException)) {
            return type;
        }
        String message = cause.getMessage();
        if (message == null || message.isBlank()) return type;
        StringBuilder cleaned =
                new StringBuilder(Math.min(message.length(), MAX_SAFE_MESSAGE_LENGTH));
        for (int i = 0; i < message.length() && cleaned.length() < MAX_SAFE_MESSAGE_LENGTH; i++) {
            char c = message.charAt(i);
            if (!Character.isISOControl(c)) cleaned.append(c);
        }
        return cleaned.length() == 0 ? type : type + ": " + cleaned;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
