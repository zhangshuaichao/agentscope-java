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
package io.agentscope.extensions.channel.wecom;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * 企业微信渠道适配器。
 *
 * <p>入站：依赖 Spring 管理的 {@link WeComCallbackController} 接收 URL 验证握手和加密消息回调，经 {@link WeComCrypto} 解密，按 {@code MsgId} 去重，执行机器人循环保护后分发到此渠道。
 *
 * <p>出站：通过 {@link WeComOutboundClient} 调用 {@code /cgi-bin/message/send} 发送私聊、调用 {@code /cgi-bin/appchat/send} 发送群聊，使用 {@link WeComAccessTokenProvider} 提供的 {@code access_token} 认证。
 */
public final class WeComChannel implements Channel {

    private static final Logger log = LoggerFactory.getLogger(WeComChannel.class);

    /**
 * 在 {@code agentscope.json} 和 {@link io.agentscope.harness.agent.gateway.channel.ChannelFactory} 中使用的 {@code type} 值。
 */
    public static final String TYPE = "wecom";

    private final String channelId;
    private final ChannelConfig config;
    private final WeComChannelProperties properties;
    private final WeComCrypto crypto;
    private final WeComAccessTokenProvider tokenProvider;
    private final WeComOutboundClient outboundClient;
    private final WeComInboundMapper mapper;
    private final InboundEventDeduplicator idempotency;
    private final BotLoopGuard botLoopGuard;
    private final ChannelRouter router;
    private final WeComChannelRegistry registry;

    private volatile Gateway gateway;

    private WeComChannel(
            String channelId,
            ChannelConfig config,
            WeComChannelProperties properties,
            WeComCrypto crypto,
            WeComAccessTokenProvider tokenProvider,
            WeComOutboundClient outboundClient,
            WeComInboundMapper mapper,
            InboundEventDeduplicator idempotency,
            BotLoopGuard botLoopGuard,
            ChannelRouter router,
            WeComChannelRegistry registry) {
        this.channelId = Objects.requireNonNull(channelId, "channelId");
        this.config = Objects.requireNonNull(config, "config");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.crypto = Objects.requireNonNull(crypto, "crypto");
        this.tokenProvider = Objects.requireNonNull(tokenProvider, "tokenProvider");
        this.outboundClient = Objects.requireNonNull(outboundClient, "outboundClient");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
        this.botLoopGuard = Objects.requireNonNull(botLoopGuard, "botLoopGuard");
        this.router = Objects.requireNonNull(router, "router");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /**
     * 供 {@link io.agentscope.harness.agent.gateway.channel.ChannelFactory} 使用的工厂。默认使用进程本地 {@link IdempotencyStore} 和 {@link InMemoryAccessTokenStore}；要提供共享存储实现，使用接受 {@link InboundEventDeduplicator} 和 {@link AccessTokenStore} 的重载。
     *
     * @param channelId 渠道 ID（{@code agentscope.json#channels} 中的键）
     * @param routing 从文件条目 routing 块解析的 {@link ChannelConfig}
     * @param rawProperties 服务方专用属性（corpId、agentId、secret、token、encodingAesKey 等）
     */
    public static WeComChannel fromProperties(
            String channelId, ChannelConfig routing, Map<String, Object> rawProperties) {
        return fromProperties(
                channelId,
                routing,
                rawProperties,
                new IdempotencyStore(),
                new InMemoryAccessTokenStore());
    }

    /**
     * 允许应用提供 {@link InboundEventDeduplicator} 的工厂重载，用于丢弃平台重复投递；例如共享存储实现可跨实例识别重复事件，否则使用进程本地 {@link IdempotencyStore}。
     *
     * @param channelId 渠道 ID（{@code agentscope.json#channels} 中的键）
     * @param routing 从文件条目 routing 块解析的 {@link ChannelConfig}
     * @param rawProperties 服务方专用属性（corpId、agentId、secret、token、encodingAesKey 等）
     * @param idempotency 入站事件去重器，必须线程安全
     */
    public static WeComChannel fromProperties(
            String channelId,
            ChannelConfig routing,
            Map<String, Object> rawProperties,
            InboundEventDeduplicator idempotency) {
        return fromProperties(
                channelId, routing, rawProperties, idempotency, new InMemoryAccessTokenStore());
    }

    /**
     * 额外允许应用提供缓存出站访问令牌的 {@link AccessTokenStore} 的工厂重载；例如使用共享存储，使某实例的刷新或失效作用于整个部署。否则使用进程本地 {@link InMemoryAccessTokenStore}。
     *
     * @param channelId 渠道 ID（{@code agentscope.json#channels} 中的键）
     * @param routing 从文件条目 routing 块解析的 {@link ChannelConfig}
     * @param rawProperties 服务方专用属性（corpId、agentId、secret、token、encodingAesKey 等）
     * @param idempotency 入站事件去重器，必须线程安全
     * @param tokenStore 此渠道访问令牌的缓存，每组凭据一个实例，不得跨不同凭据的渠道共享；必须线程安全
     */
    public static WeComChannel fromProperties(
            String channelId,
            ChannelConfig routing,
            Map<String, Object> rawProperties,
            InboundEventDeduplicator idempotency,
            AccessTokenStore tokenStore) {
        WeComChannelProperties props = WeComChannelProperties.from(channelId, rawProperties);
        WeComCrypto crypto = new WeComCrypto(props.token(), props.encodingAesKey(), props.corpId());
        WeComAccessTokenProvider tokenProvider =
                new WeComAccessTokenProvider(
                        props.apiBase(), props.corpId(), props.secret(), tokenStore);
        WeComOutboundClient outbound =
                new WeComOutboundClient(props.apiBase(), tokenProvider, props.agentId());
        WeComInboundMapper mapper = new WeComInboundMapper(channelId, props.corpId());
        return new WeComChannel(
                channelId,
                routing,
                props,
                crypto,
                tokenProvider,
                outbound,
                mapper,
                idempotency,
                new BotLoopGuard(),
                new ChannelRouter(routing.defaultAgentId()),
                WeComChannelRegistry.instance());
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
        registry.register(this);
        log.info(
                "WeCom channel '{}' started: corpId={}, agentId={}, callbackPath={}",
                channelId,
                properties.corpId(),
                properties.agentId(),
                properties.callbackPath());
    }

    @Override
    public void stop() {
        registry.unregister(channelId);
        log.info("WeCom channel '{}' stopped", channelId);
    }

    @Override
    public Mono<Msg> dispatch(InboundMessage message) {
        Objects.requireNonNull(message, "message");
        Gateway g = gateway;
        if (g == null) {
            return Mono.error(
                    new IllegalStateException("WeComChannel '" + channelId + "' has no gateway"));
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
                                        "WeCom channel '{}' deliver failed: {}",
                                        channelId,
                                        err.getMessage()))
                .subscribe();
    }

    // -----------------------------------------------------------------
    // 供 WeComCallbackController 使用的内部访问器
    // -----------------------------------------------------------------

    WeComCrypto crypto() {
        return crypto;
    }

    WeComInboundMapper mapper() {
        return mapper;
    }

    InboundEventDeduplicator idempotency() {
        return idempotency;
    }

    BotLoopGuard botLoopGuard() {
        return botLoopGuard;
    }

    WeComChannelProperties properties() {
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
                                        "WeCom channel '{}' reply send failed: {}",
                                        channelId,
                                        err.getMessage()));
    }
}
