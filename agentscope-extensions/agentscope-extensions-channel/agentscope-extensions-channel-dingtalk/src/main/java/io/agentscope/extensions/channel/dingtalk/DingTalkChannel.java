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
package io.agentscope.extensions.channel.dingtalk;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.message.Msg;
import io.agentscope.extensions.channel.common.AccessTokenStore;
import io.agentscope.extensions.channel.common.BotLoopGuard;
import io.agentscope.extensions.channel.common.IdempotencyStore;
import io.agentscope.extensions.channel.common.InMemoryAccessTokenStore;
import io.agentscope.extensions.channel.common.InboundEventDeduplicator;
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
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * 钉钉渠道适配器。
 *
 * <p>接收模式由 {@link DingTalkChannelProperties#mode()} 选择：
 * {@code stream}（默认）模式下，{@link DingTalkStreamClient} 保持长连接 WebSocket，
 * 将各机器人消息载荷分发到此处；{@code http} 模式下，{@link DingTalkCallbackController}
 * 接收签名 HTTP 回调，把已验证载荷交给同一接收流程。
 * 两种模式均通过 {@link DingTalkInboundMapper} 映射，按 {@code msgId} 去重，
 * 应用机器人循环防护，再通过 {@link ChannelRouter} 路由并由 {@link Gateway} 执行。
 *
 * <p>出站：{@link DingTalkOutboundClient} 通过 OpenAPI batchSend 等端点发送回复。
 */
public final class DingTalkChannel implements Channel {

    private static final Logger log = LoggerFactory.getLogger(DingTalkChannel.class);

    /**
     * 在 {@code agentscope.json} 及 {@link io.agentscope.harness.agent.gateway.channel.ChannelFactory} 中使用的 {@code type} 值。
     */
    public static final String TYPE = "dingtalk";

    private final String channelId;
    private final ChannelConfig config;
    private final DingTalkChannelProperties properties;
    private final DingTalkAccessTokenProvider tokenProvider;
    private final DingTalkOutboundClient outboundClient;
    private final DingTalkInboundMapper mapper;
    private final InboundEventDeduplicator idempotency;
    private final BotLoopGuard botLoopGuard;
    private final ChannelRouter router;

    /**
     * 仅 {@code stream} 模式存在；{@code http} 模式为 {@code null}。
     */
    private final DingTalkStreamClient streamClient;

    /**
     * 仅 {@code http} 模式存在；{@code stream} 模式为 {@code null}。
     */
    private final DingTalkCallbackCrypto crypto;

    private final DingTalkChannelRegistry registry;

    private volatile Gateway gateway;

    private DingTalkChannel(
            String channelId,
            ChannelConfig config,
            DingTalkChannelProperties properties,
            DingTalkAccessTokenProvider tokenProvider,
            DingTalkOutboundClient outboundClient,
            DingTalkInboundMapper mapper,
            InboundEventDeduplicator idempotency,
            BotLoopGuard botLoopGuard,
            ChannelRouter router,
            DingTalkChannelRegistry registry) {
        this.channelId = Objects.requireNonNull(channelId, "channelId");
        this.config = Objects.requireNonNull(config, "config");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.tokenProvider = Objects.requireNonNull(tokenProvider, "tokenProvider");
        this.outboundClient = Objects.requireNonNull(outboundClient, "outboundClient");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
        this.botLoopGuard = Objects.requireNonNull(botLoopGuard, "botLoopGuard");
        this.router = Objects.requireNonNull(router, "router");
        this.registry = Objects.requireNonNull(registry, "registry");
        if (DingTalkChannelProperties.MODE_HTTP.equals(properties.mode())) {
            this.streamClient = null;
            this.crypto = new DingTalkCallbackCrypto(properties.appSecret(), properties.aesKey());
        } else {
            this.streamClient = new DingTalkStreamClient(properties, this::onInboundPayload);
            this.crypto = null;
        }
    }

    /**
     * 供 {@link io.agentscope.harness.agent.gateway.channel.ChannelFactory} 使用的工厂。
     * 默认使用进程内 {@link IdempotencyStore} 和 {@link InMemoryAccessTokenStore}；
     * 需共享存储时，使用接收 {@link InboundEventDeduplicator} 和 {@link AccessTokenStore} 的重载。
     */
    public static DingTalkChannel fromProperties(
            String channelId, ChannelConfig routing, Map<String, Object> rawProperties) {
        return fromProperties(
                channelId,
                routing,
                rawProperties,
                new IdempotencyStore(),
                new InMemoryAccessTokenStore());
    }

    /**
     * 允许应用提供 {@link InboundEventDeduplicator} 以丢弃平台重投的工厂变体，
     * 例如使用共享存储跨实例识别重复；否则使用进程内 {@link IdempotencyStore}。
     *
     * @param idempotency 入站事件去重器；必须线程安全
     */
    public static DingTalkChannel fromProperties(
            String channelId,
            ChannelConfig routing,
            Map<String, Object> rawProperties,
            InboundEventDeduplicator idempotency) {
        return fromProperties(
                channelId, routing, rawProperties, idempotency, new InMemoryAccessTokenStore());
    }

    /**
     * 进一步允许应用提供缓存出站令牌的 {@link AccessTokenStore} 的工厂变体，
     * 例如使用共享存储，使一个实例的刷新或失效作用于整个部署。
     * 否则使用进程内 {@link InMemoryAccessTokenStore}。
     *
     * @param channelId 渠道 ID（{@code agentscope.json#channels} 中的键）
     * @param routing 从配置条目的路由块解析出的 {@link ChannelConfig}
     * @param rawProperties 服务商专用属性（appKey、appSecret、robotCode、mode、aesKey 等）
     * @param idempotency 入站事件去重器；必须线程安全
     * @param tokenStore 此渠道的令牌缓存；每组凭据一个实例，不得由不同凭据的渠道共享；必须线程安全
     */
    public static DingTalkChannel fromProperties(
            String channelId,
            ChannelConfig routing,
            Map<String, Object> rawProperties,
            InboundEventDeduplicator idempotency,
            AccessTokenStore tokenStore) {
        DingTalkChannelProperties props = DingTalkChannelProperties.from(channelId, rawProperties);
        DingTalkAccessTokenProvider tokenProvider =
                new DingTalkAccessTokenProvider(
                        props.apiBase(), props.appKey(), props.appSecret(), tokenStore);
        DingTalkOutboundClient outbound =
                new DingTalkOutboundClient(props.apiBase(), tokenProvider, props.robotCode());
        DingTalkInboundMapper mapper = new DingTalkInboundMapper(channelId, props.appKey());
        return new DingTalkChannel(
                channelId,
                routing,
                props,
                tokenProvider,
                outbound,
                mapper,
                idempotency,
                new BotLoopGuard(),
                new ChannelRouter(routing.defaultAgentId()),
                DingTalkChannelRegistry.instance());
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
        if (crypto != null) {
            registry.register(this);
        } else {
            streamClient.start();
        }
        log.info(
                "DingTalk channel '{}' started in {} mode: appKey={}, robotCode={}",
                channelId,
                properties.mode(),
                properties.appKey(),
                properties.robotCode());
    }

    @Override
    public void stop() {
        if (crypto != null) {
            registry.unregister(channelId, this);
        } else {
            streamClient.stop();
        }
        log.info("DingTalk channel '{}' stopped", channelId);
    }

    @Override
    public Mono<Msg> dispatch(InboundMessage message) {
        Objects.requireNonNull(message, "message");
        Gateway g = gateway;
        if (g == null) {
            return Mono.error(
                    new IllegalStateException(
                            "DingTalkChannel '" + channelId + "' has no gateway"));
        }
        RouteResult route = router.resolveRoute(config, message);
        return g.run(
                        route.context(),
                        message.messages(),
                        route.outboundAddress(),
                        message.runtimeContext(),
                        message)
                .flatMap(reply -> sendReply(route.outboundAddress(), reply).thenReturn(reply));
    }

    @Override
    public void deliver(OutboundAddress address, List<Msg> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        outboundClient
                .send(address, messages)
                .doOnError(
                        err ->
                                log.warn(
                                        "DingTalk channel '{}' deliver failed: {}",
                                        channelId,
                                        err.getMessage()))
                .subscribe();
    }

    // -----------------------------------------------------------------
    // 入站接收（两种接收模式共用）
    // -----------------------------------------------------------------

    /**
     * 处理两种接收模式投递的机器人消息载荷：{@link DingTalkStreamClient} 对应 stream 模式，
     * {@link DingTalkCallbackController} 对应 http 模式。
     * 按 {@code msgId} 去重、映射、应用机器人循环防护，然后分发。
     */
    void onInboundPayload(JsonNode payload) {
        // 【学习提示】firstSeen 位于执行之前，后续失败不会在此撤销去重标记。
        // 该机制抑制重投，不提供“恰好一次成功执行”保证；可靠补偿需另行设计。
        Optional<String> msgId = DingTalkInboundMapper.extractMsgId(payload);
        if (msgId.isPresent() && !idempotency.firstSeen(channelId + "|" + msgId.get())) {
            log.debug(
                    "DingTalk dispatch: duplicate msgId={} (channelId='{}')",
                    msgId.get(),
                    channelId);
            return;
        }
        Optional<InboundMessage> inbound = mapper.map(payload);
        if (inbound.isEmpty()) {
            return;
        }
        InboundMessage in = inbound.get();
        if (!botLoopGuard.allow(in.peer().key())) {
            log.warn(
                    "DingTalk dispatch: bot-loop guard tripped for peer='{}' (channelId='{}')",
                    in.peer().key(),
                    channelId);
            return;
        }
        dispatch(in)
                .doOnError(
                        err ->
                                log.warn(
                                        "DingTalk channel '{}' dispatch failed: {}",
                                        channelId,
                                        err.getMessage()))
                .subscribe();
    }

    // -----------------------------------------------------------------
    // 内部访问器与辅助方法
    // -----------------------------------------------------------------

    DingTalkInboundMapper mapper() {
        return mapper;
    }

    /**
     * 回调验证／解密辅助器；非 http 模式时为 {@code null}。
     */
    DingTalkCallbackCrypto crypto() {
        return crypto;
    }

    InboundEventDeduplicator idempotency() {
        return idempotency;
    }

    BotLoopGuard botLoopGuard() {
        return botLoopGuard;
    }

    DingTalkAccessTokenProvider tokenProvider() {
        return tokenProvider;
    }

    DingTalkChannelProperties properties() {
        return properties;
    }

    private Mono<Void> sendReply(OutboundAddress address, Msg reply) {
        if (reply == null) {
            return Mono.empty();
        }
        return outboundClient
                .send(address, List.of(reply))
                .doOnError(
                        err ->
                                log.warn(
                                        "DingTalk channel '{}' reply send failed: {}",
                                        channelId,
                                        err.getMessage()));
    }
}
