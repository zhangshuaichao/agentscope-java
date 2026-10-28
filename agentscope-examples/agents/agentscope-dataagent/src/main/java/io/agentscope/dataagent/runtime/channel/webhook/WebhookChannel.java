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
package io.agentscope.dataagent.runtime.channel.webhook;

import io.agentscope.core.message.Msg;
import io.agentscope.extensions.channel.common.BotLoopGuard;
import io.agentscope.extensions.channel.common.IdempotencyStore;
import io.agentscope.harness.agent.gateway.Gateway;
import io.agentscope.harness.agent.gateway.channel.Channel;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import io.agentscope.harness.agent.gateway.channel.ChannelRouter;
import io.agentscope.harness.agent.gateway.channel.InboundMessage;
import io.agentscope.harness.agent.gateway.channel.OutboundAddress;
import io.agentscope.harness.agent.gateway.channel.RouteResult;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * 通用 Webhook 通道：HTTP 入站/出站的辅助工具适配器，允许外部系统（IM 机器人、工单、CI）
 * 无需打开主聊天界面即可调用 DataAgent。
 * <p>入站：{@link WebhookCallbackController} 处理 {@code POST /api/webhook/{channelId}
 * /inbound}，验证 HMAC 后调用 {@link #ingest(WebhookInboundRequest)}；后者去重、
 * 检查机器人循环保护、通过 {@link ChannelRouter} 路由并分派到 {@link Gateway}。
 * <p>出站：每个请求有两种模式。{@code callback} 通过 {@link WebhookOutboundClient}
 * 将回复发送到调用方的 {@code callbackUrl}；{@code poll} 将回复暂存在
 * {@link #parkedReplies}，供长轮询端点读取。
 */
public final class WebhookChannel implements Channel {

    private static final Logger log = LoggerFactory.getLogger(WebhookChannel.class);

    /** {@code agentscope.json} 和 {@link
     * io.agentscope.dataagent.runtime.config.ChannelTypeRegistry} 使用的 {@code type} 值。
     */
    public static final String TYPE = "webhook";

    private final String channelId;
    private final ChannelConfig config;
    private final WebhookChannelProperties properties;
    private final WebhookInboundMapper mapper;
    private final WebhookOutboundClient outboundClient;
    private final IdempotencyStore idempotency;
    private final BotLoopGuard botLoopGuard;
    private final ChannelRouter router;

    /** {@code replyMode=poll} 按 inboundId 暂存的槽位，通过 FIFO 淘汰限制容量。 */
    private final ConcurrentHashMap<String, Sinks.One<String>> parkedReplies =
            new ConcurrentHashMap<>();

    private final ConcurrentLinkedDeque<String> parkOrder = new ConcurrentLinkedDeque<>();

    private volatile Gateway gateway;

    private WebhookChannel(
            String channelId,
            ChannelConfig config,
            WebhookChannelProperties properties,
            WebhookInboundMapper mapper,
            WebhookOutboundClient outboundClient,
            IdempotencyStore idempotency,
            BotLoopGuard botLoopGuard,
            ChannelRouter router) {
        this.channelId = Objects.requireNonNull(channelId, "channelId");
        this.config = Objects.requireNonNull(config, "config");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.outboundClient = Objects.requireNonNull(outboundClient, "outboundClient");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
        this.botLoopGuard = Objects.requireNonNull(botLoopGuard, "botLoopGuard");
        this.router = Objects.requireNonNull(router, "router");
    }

    /** 供 {@link io.agentscope.dataagent.runtime.config.ChannelTypeRegistry} 使用的工厂。 */
    public static WebhookChannel fromProperties(
            String channelId, ChannelConfig routing, Map<String, Object> rawProperties) {
        WebhookChannelProperties props = WebhookChannelProperties.from(channelId, rawProperties);
        return new WebhookChannel(
                channelId,
                routing,
                props,
                new WebhookInboundMapper(channelId),
                new WebhookOutboundClient(channelId, props.sharedSecret()),
                new IdempotencyStore(),
                new BotLoopGuard(),
                new ChannelRouter(routing.defaultAgentId()));
    }

    // -----------------------------------------------------------------
    // 渠道生命周期
    // -----------------------------------------------------------------

    @Override
    public String channelId() {
        return channelId;
    }

    @Override
    public ChannelConfig config() {
        return config;
    }

    @Override
    public void init(Gateway gateway) {
        if (this.gateway == null) {
            this.gateway = Objects.requireNonNull(gateway, "gateway");
        }
    }

    @Override
    public void start() {
        log.info(
                "Webhook channel '{}' started: requiresSignature={}, allowedIps={}",
                channelId,
                properties.requiresSignature(),
                properties.allowedIps());
    }

    @Override
    public void stop() {
        parkedReplies.forEach((id, sink) -> sink.tryEmitEmpty());
        parkedReplies.clear();
        parkOrder.clear();
        log.info("Webhook channel '{}' stopped", channelId);
    }

    @Override
    public Mono<Msg> dispatch(InboundMessage message) {
        Objects.requireNonNull(message, "message");
        Gateway g = gateway;
        if (g == null) {
            return Mono.error(
                    new IllegalStateException("WebhookChannel '" + channelId + "' has no gateway"));
        }
        RouteResult route = router.resolveRoute(config, message);
        return g.run(route.context(), message.messages(), route.outboundAddress());
    }

    @Override
    public void deliver(OutboundAddress address, List<Msg> messages) {
        // 子智能体主动公告：Webhook 没有持久对端连接，
        // 因此直接丢弃这类消息。需要接收公告的调用方应使用
        // chatui 或真实 IM 通道。
        if (messages == null || messages.isEmpty()) return;
        log.debug(
                "Webhook channel '{}' dropping {} proactive message(s) (no persistent peer)",
                channelId,
                messages.size());
    }

    // -----------------------------------------------------------------
    // Webhook 入口（由 WebhookCallbackController 调用）
    // -----------------------------------------------------------------

    WebhookChannelProperties properties() {
        return properties;
    }

    /** 接收刚完成认证的入站请求。返回发给调用方的响应载荷（包含分配的 {@code inboundId}，在轮询模式下待回复可用后获取实际结果）。 */
    public Mono<WebhookDispatchResult> ingest(WebhookInboundRequest req) {
        Objects.requireNonNull(req, "req");
        String inboundId =
                (req.inboundId() != null && !req.inboundId().isBlank())
                        ? req.inboundId().strip()
                        : UUID.randomUUID().toString();
        if (!idempotency.firstSeen(channelId + "|" + inboundId)) {
            log.debug(
                    "Webhook channel '{}' dispatch: duplicate inboundId={}", channelId, inboundId);
            return Mono.just(
                    new WebhookDispatchResult(
                            inboundId, "duplicate", null, req.effectiveReplyMode()));
        }
        return mapper.map(req)
                .map(
                        inbound -> {
                            if (!botLoopGuard.allow(inbound.peer().key())) {
                                log.warn(
                                        "Webhook channel '{}' bot-loop guard tripped for peer='{}'",
                                        channelId,
                                        inbound.peer().key());
                                return Mono.just(
                                        new WebhookDispatchResult(
                                                inboundId,
                                                "bot-loop-blocked",
                                                null,
                                                req.effectiveReplyMode()));
                            }
                            return dispatchAndDeliver(req, inbound, inboundId);
                        })
                .orElseGet(
                        () ->
                                Mono.just(
                                        new WebhookDispatchResult(
                                                inboundId,
                                                "invalid-payload",
                                                null,
                                                req.effectiveReplyMode())));
    }

    private Mono<WebhookDispatchResult> dispatchAndDeliver(
            WebhookInboundRequest req, InboundMessage inbound, String inboundId) {
        String mode = req.effectiveReplyMode();
        Mono<Msg> dispatch = dispatch(inbound);

        if (WebhookInboundRequest.REPLY_MODE_CALLBACK.equals(mode)) {
            String cbUrl = req.callbackUrl();
            if (cbUrl == null || cbUrl.isBlank()) {
                return Mono.just(
                        new WebhookDispatchResult(inboundId, "callback-url-required", null, mode));
            }
            dispatch.map(reply -> WebhookOutboundClient.renderReplyText(List.of(reply)))
                    .flatMap(text -> outboundClient.deliver(cbUrl, inboundId, text))
                    .doOnError(
                            err ->
                                    log.warn(
                                            "Webhook channel '{}' dispatch failed (callback): {}",
                                            channelId,
                                            err.getMessage()))
                    .onErrorResume(e -> Mono.empty())
                    .subscribe();
            return Mono.just(new WebhookDispatchResult(inboundId, "accepted", null, mode));
        }

        // 轮询模式：暂存回复 Sink，在后台等待分派。
        Sinks.One<String> sink = Sinks.one();
        parkReply(inboundId, sink);
        dispatch.map(reply -> WebhookOutboundClient.renderReplyText(List.of(reply)))
                .doOnSuccess(text -> sink.tryEmitValue(text == null ? "" : text))
                .doOnError(
                        err -> {
                            log.warn(
                                    "Webhook channel '{}' dispatch failed (poll): {}",
                                    channelId,
                                    err.getMessage());
                            sink.tryEmitValue("");
                        })
                .onErrorResume(e -> Mono.empty())
                .subscribe();
        return Mono.just(new WebhookDispatchResult(inboundId, "parked", null, mode));
    }

    /** 长轮询获取此前暂存的回复。超时仍无回复时返回空，调用方应再次轮询。 */
    public Mono<String> awaitReply(String inboundId) {
        if (inboundId == null) return Mono.empty();
        Sinks.One<String> sink = parkedReplies.get(inboundId);
        if (sink == null) return Mono.empty();
        return sink.asMono()
                .timeout(
                        java.time.Duration.ofMillis(properties.longPollTimeoutMillis()),
                        Mono.empty())
                .doFinally(sig -> parkedReplies.remove(inboundId));
    }

    private void parkReply(String inboundId, Sinks.One<String> sink) {
        parkedReplies.put(inboundId, sink);
        parkOrder.addLast(inboundId);
        while (parkedReplies.size() > properties.outboundParkCapacity()) {
            String evict = parkOrder.pollFirst();
            if (evict == null) break;
            Sinks.One<String> dropped = parkedReplies.remove(evict);
            if (dropped != null) dropped.tryEmitEmpty();
        }
    }

    /** {@link #ingest} 返回的响应信封。 */
    public record WebhookDispatchResult(
            String inboundId, String status, String reply, String replyMode) {}
}
