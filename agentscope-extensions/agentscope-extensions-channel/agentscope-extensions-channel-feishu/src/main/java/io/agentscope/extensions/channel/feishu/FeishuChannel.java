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
package io.agentscope.extensions.channel.feishu;

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
 * 飞书／Lark 渠道适配器。
 *
 * <p>入站依赖 Spring 管理的 {@link FeishuCallbackController}：接收 URL 验证握手和事件回调，
 * 按需通过 {@link FeishuCrypto} 解密，按 {@code header.event_id} 去重，
 * 运行机器人循环防护，再分发到此渠道。
 *
 * <p>出站使用 {@link FeishuOutboundClient} 调用 {@code /open-apis/im/v1/messages}，
 * 并用 {@link FeishuAccessTokenProvider} 提供的 {@code tenant_access_token} 认证。
 */
public final class FeishuChannel implements Channel {

    private static final Logger log = LoggerFactory.getLogger(FeishuChannel.class);

    /**
     * 在 {@code agentscope.json} 及 {@link io.agentscope.harness.agent.gateway.channel.ChannelFactory} 中使用的 {@code type} 值。
     */
    public static final String TYPE = "feishu";

    private final String channelId;
    private final ChannelConfig config;
    private final FeishuChannelProperties properties;
    private final FeishuCrypto crypto;
    private final FeishuAccessTokenProvider tokenProvider;
    private final FeishuOutboundClient outboundClient;
    private final FeishuInboundMapper mapper;
    private final InboundEventDeduplicator idempotency;
    private final BotLoopGuard botLoopGuard;
    private final ChannelRouter router;
    private final FeishuChannelRegistry registry;

    private volatile Gateway gateway;

    private FeishuChannel(
            String channelId,
            ChannelConfig config,
            FeishuChannelProperties properties,
            FeishuCrypto crypto,
            FeishuAccessTokenProvider tokenProvider,
            FeishuOutboundClient outboundClient,
            FeishuInboundMapper mapper,
            InboundEventDeduplicator idempotency,
            BotLoopGuard botLoopGuard,
            ChannelRouter router,
            FeishuChannelRegistry registry) {
        this.channelId = Objects.requireNonNull(channelId, "channelId");
        this.config = Objects.requireNonNull(config, "config");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.crypto = crypto;
        this.tokenProvider = Objects.requireNonNull(tokenProvider, "tokenProvider");
        this.outboundClient = Objects.requireNonNull(outboundClient, "outboundClient");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
        this.botLoopGuard = Objects.requireNonNull(botLoopGuard, "botLoopGuard");
        this.router = Objects.requireNonNull(router, "router");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /**
     * 供 {@link io.agentscope.harness.agent.gateway.channel.ChannelFactory} 使用的工厂。
     * 默认使用进程内 {@link IdempotencyStore} 和 {@link InMemoryAccessTokenStore}；
     * 需共享存储时，使用接收 {@link InboundEventDeduplicator} 和 {@link AccessTokenStore} 的重载。
     */
    public static FeishuChannel fromProperties(
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
     * 注意飞书回调控制器当前将去重委托给持久化接收流程，传入的存储仅保留供会查询它的渠道路径使用。
     *
     * @param idempotency 入站事件去重器；必须线程安全
     */
    public static FeishuChannel fromProperties(
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
     * @param rawProperties 服务商专用属性（appId、appSecret、encryptKey 等）
     * @param idempotency 入站事件去重器；必须线程安全
     * @param tokenStore 此渠道的令牌缓存；每组凭据一个实例，不得由不同凭据的渠道共享；必须线程安全
     */
    public static FeishuChannel fromProperties(
            String channelId,
            ChannelConfig routing,
            Map<String, Object> rawProperties,
            InboundEventDeduplicator idempotency,
            AccessTokenStore tokenStore) {
        FeishuChannelProperties props = FeishuChannelProperties.from(channelId, rawProperties);
        FeishuCrypto crypto = props.isEncrypted() ? new FeishuCrypto(props.encryptKey()) : null;
        FeishuAccessTokenProvider tokenProvider =
                new FeishuAccessTokenProvider(
                        props.apiBase(), props.appId(), props.appSecret(), tokenStore);
        FeishuOutboundClient outbound = new FeishuOutboundClient(props.apiBase(), tokenProvider);
        FeishuInboundMapper mapper = new FeishuInboundMapper(channelId);
        return new FeishuChannel(
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
                FeishuChannelRegistry.instance());
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
                "Feishu channel '{}' started: appId={}, callbackPath={}, encrypted={}",
                channelId,
                properties.appId(),
                properties.callbackPath(),
                properties.isEncrypted());
    }

    @Override
    public void stop() {
        registry.unregister(channelId);
        log.info("Feishu channel '{}' stopped", channelId);
    }

    @Override
    public Mono<Msg> dispatch(InboundMessage message) {
        Objects.requireNonNull(message, "message");
        Gateway g = gateway;
        if (g == null) {
            return Mono.error(
                    new IllegalStateException("FeishuChannel '" + channelId + "' has no gateway"));
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
    public Mono<String> deliverWithReceipt(
            OutboundAddress address, Msg message, String deliveryId) {
        return outboundClient.sendWithReceipt(address, message, deliveryId);
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
                                        "Feishu channel '{}' deliver failed: {}",
                                        channelId,
                                        err.getMessage()))
                .subscribe();
    }

    // -----------------------------------------------------------------
    // 供 FeishuCallbackController 使用的内部访问器
    // -----------------------------------------------------------------

    FeishuCrypto crypto() {
        return crypto;
    }

    FeishuInboundMapper mapper() {
        return mapper;
    }

    InboundEventDeduplicator idempotency() {
        return idempotency;
    }

    BotLoopGuard botLoopGuard() {
        return botLoopGuard;
    }

    FeishuChannelProperties properties() {
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
                                        "Feishu channel '{}' reply send failed: {}",
                                        channelId,
                                        err.getMessage()));
    }
}
