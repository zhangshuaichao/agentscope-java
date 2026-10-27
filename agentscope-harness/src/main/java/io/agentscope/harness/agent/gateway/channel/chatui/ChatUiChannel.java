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
package io.agentscope.harness.agent.gateway.channel.chatui;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.gateway.Gateway;
import io.agentscope.harness.agent.gateway.MsgContext;
import io.agentscope.harness.agent.gateway.channel.Channel;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import io.agentscope.harness.agent.gateway.channel.ChannelRouter;
import io.agentscope.harness.agent.gateway.channel.DmScope;
import io.agentscope.harness.agent.gateway.channel.InboundMessage;
import io.agentscope.harness.agent.gateway.channel.OutboundAddress;
import io.agentscope.harness.agent.gateway.channel.RouteResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 面向直接聊天 UI 交互的默认 {@link Channel} 实现：没有外部传输、webhook 或 WebSocket；
 * 调用方通过编程方式提交 {@link ChatUiRequest}，以响应式方式接收智能体回复。
 *
 * <p>适用于：
 * <ul>
 *   <li>向 Harness 发送 HTTP 请求的嵌入式网页聊天 UI
 *   <li>需要类型化智能体 API 的 CLI 工具及集成测试
 *   <li>单智能体单会话场景，默认 {@link DmScope#MAIN} 将全部对话合并到一个会话
 * </ul>
 *
 * <h2>获取实例</h2>
 *
 * 推荐通过 {@link
 * io.agentscope.harness.agent.gateway.GatewayBootstrap#chatUiChannel()} 获取：
 *
 * <pre>{@code
 * GatewayBootstrap gw = GatewayBootstrap.builder().model(model)
 *     .agent("main", b -> b.name("assistant").sysPrompt("..."))
 *     .build();
 * ChatUiChannel chat = gw.chatUiChannel();
 * chat.send("Hello!").block();
 * }</pre>
 */
public final class ChatUiChannel implements Channel {

    public static final String CHANNEL_ID = "chatui";

    private volatile Gateway gateway;
    private volatile ChannelConfig config;
    private final ChannelRouter router;
    private final ConcurrentLinkedQueue<OutboundEnvelope> outboundQueue =
            new ConcurrentLinkedQueue<>();

    private ChatUiChannel(Gateway gateway, ChannelConfig config) {
        this.gateway = gateway;
        this.config = Objects.requireNonNull(config, "config");
        this.router = new ChannelRouter(null);
    }

    // -----------------------------------------------------------------
    // 工厂方法
    // -----------------------------------------------------------------

    /**
     * 创建使用默认 {@link DmScope#MAIN} 配置的聊天 UI 渠道。
     */
    public static ChatUiChannel create() {
        return new ChatUiChannel(null, ChannelConfig.of(CHANNEL_ID));
    }

    /**
     * 创建使用明确配置的聊天 UI 渠道。
     */
    public static ChatUiChannel create(ChannelConfig config) {
        return new ChatUiChannel(null, config);
    }

    /**
     * 创建按对端划分的聊天 UI 渠道，每个不同 peerId 获得自己的会话。
     */
    public static ChatUiChannel perPeer() {
        ChannelConfig cfg = ChannelConfig.builder(CHANNEL_ID).dmScope(DmScope.PER_PEER).build();
        return new ChatUiChannel(null, cfg);
    }

    /**
     * 创建预先连接网关的聊天 UI 渠道。
     */
    public static ChatUiChannel create(Gateway gateway) {
        return new ChatUiChannel(
                Objects.requireNonNull(gateway, "gateway"), ChannelConfig.of(CHANNEL_ID));
    }

    /**
     * 创建预先连接网关并使用明确配置的聊天 UI 渠道。
     */
    public static ChatUiChannel create(Gateway gateway, ChannelConfig config) {
        return new ChatUiChannel(Objects.requireNonNull(gateway, "gateway"), config);
    }

    // -----------------------------------------------------------------
    // 渠道生命周期
    // -----------------------------------------------------------------

    @Override
    public void init(Gateway gateway) {
        if (this.gateway == null) {
            this.gateway = Objects.requireNonNull(gateway, "gateway");
        }
    }

    @Override
    public void start() {}

    @Override
    public void stop() {}

    @Override
    public String channelId() {
        return CHANNEL_ID;
    }

    @Override
    public ChannelConfig config() {
        return config;
    }

    @Override
    public boolean applyRoutingConfig(ChannelConfig newConfig) {
        Objects.requireNonNull(newConfig, "newConfig");
        if (!CHANNEL_ID.equals(newConfig.channelId())) {
            return false;
        }
        this.config = newConfig;
        return true;
    }

    @Override
    public Mono<Msg> dispatch(InboundMessage message) {
        Objects.requireNonNull(message, "message");
        RouteResult route = router.resolveRoute(config, message);
        return resolveGateway()
                .run(
                        route.context(),
                        message.messages(),
                        route.outboundAddress(),
                        message.runtimeContext(),
                        message);
    }

    /**
     * 返回本渠道针对 {@code message} 将生成的 {@link RouteResult}，但不实际分发。
     * 适合在发送前预计算会话键。
     */
    public RouteResult previewRoute(InboundMessage message) {
        Objects.requireNonNull(message, "message");
        return router.resolveRoute(config, message);
    }

    @Override
    public void deliver(OutboundAddress address, List<Msg> messages) {
        if (messages != null && !messages.isEmpty()) {
            outboundQueue.add(new OutboundEnvelope(address, messages));
        }
    }

    /**
     * 排空并返回全部已缓冲的主动出站消息；没有待处理消息时返回空列表。
     */
    public List<OutboundEnvelope> pollOutbound() {
        List<OutboundEnvelope> result = new ArrayList<>();
        OutboundEnvelope e;
        while ((e = outboundQueue.poll()) != null) {
            result.add(e);
        }
        return result;
    }

    /**
     * 返回已缓冲主动出站消息的数量。
     */
    public int outboundQueueSize() {
        return outboundQueue.size();
    }

    // -----------------------------------------------------------------
    // 便捷发送 API
    // -----------------------------------------------------------------

    /**
     * 在单会话模式下发送纯文本消息（无对端 ID）。
     */
    public Mono<Msg> send(String text) {
        return send(ChatUiRequest.of(Objects.requireNonNull(text, "text")));
    }

    /**
     * 在单会话模式下发送预先构建的 {@link Msg}（文本或多模态）。
     *
     * <pre>{@code
     * Msg multimodal = Msg.builder()
     *         .role(MsgRole.USER)
     *         .content(
     *                 TextBlock.builder().text("Describe this").build(),
     *                 ImageBlock.builder()
     *                         .source(URLSource.builder().url(imageUrl).build())
     *                         .build())
     *         .build();
     * chat.send(multimodal).block();
     * }</pre>
     */
    public Mono<Msg> send(Msg message) {
        Objects.requireNonNull(message, "message");
        return send(ChatUiRequest.of(List.of(message)));
    }

    /**
     * 在单会话模式下发送一个或多个预先构建的 {@link Msg}。
     * 适用于多模态内容或包含多个部分的用户轮次。
     */
    public Mono<Msg> send(List<Msg> messages) {
        return send(ChatUiRequest.of(requireMessages(messages)));
    }

    /**
     * 发送来自指定对端的纯文本消息。
     */
    public Mono<Msg> send(String peerId, String text) {
        Objects.requireNonNull(peerId, "peerId");
        Objects.requireNonNull(text, "text");
        return send(ChatUiRequest.withPeer(peerId, text));
    }

    /**
     * 发送来自指定对端、预先构建的 {@link Msg}。
     */
    public Mono<Msg> send(String peerId, Msg message) {
        Objects.requireNonNull(peerId, "peerId");
        Objects.requireNonNull(message, "message");
        return send(ChatUiRequest.withPeer(peerId, List.of(message)));
    }

    /**
     * 发送来自指定对端的一个或多个预先构建的 {@link Msg}。
     */
    public Mono<Msg> send(String peerId, List<Msg> messages) {
        Objects.requireNonNull(peerId, "peerId");
        return send(ChatUiRequest.withPeer(peerId, requireMessages(messages)));
    }

    /**
     * 使用明确路由身份发送纯文本消息。{@link SendOptions} 直接决定用户身份与会话键，
     * 无需配置 {@link DmScope}。
     *
     * @see SendOptions#userId(String)
     * @see SendOptions#of(String, String)
     */
    public Mono<Msg> send(SendOptions options, String text) {
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(text, "text");
        Msg msg = Msg.builder().role(MsgRole.USER).name(options.userId()).textContent(text).build();
        return dispatchWithOptions(options, List.of(msg));
    }

    /**
     * 使用明确路由身份发送预先构建的 {@link Msg}。
     * 处理图片、音频等多模态内容且仍需通过 {@link SendOptions} 路由会话时，优先使用此重载。
     */
    public Mono<Msg> send(SendOptions options, Msg message) {
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(message, "message");
        return dispatchWithOptions(options, List.of(message));
    }

    /**
     * 使用明确路由身份发送一个或多个预先构建的 {@link Msg}。
     * 适用于多模态或包含多个部分的用户轮次。
     */
    public Mono<Msg> send(SendOptions options, List<Msg> messages) {
        Objects.requireNonNull(options, "options");
        return dispatchWithOptions(options, requireMessages(messages));
    }

    /**
     * 直接向已公开子智能体发送消息，跳过普通路由。
     */
    public Mono<Msg> sendToSubagent(String subagentId, String text) {
        Objects.requireNonNull(subagentId, "subagentId");
        Objects.requireNonNull(text, "text");
        Msg msg = Msg.builder().role(MsgRole.USER).textContent(text).build();
        return resolveGateway().runSubagent(subagentId, List.of(msg));
    }

    /**
     * 直接向已公开子智能体发送预先构建的 {@link Msg}。
     */
    public Mono<Msg> sendToSubagent(String subagentId, Msg message) {
        Objects.requireNonNull(subagentId, "subagentId");
        Objects.requireNonNull(message, "message");
        return resolveGateway().runSubagent(subagentId, List.of(message));
    }

    /**
     * 直接向已公开子智能体发送一个或多个预先构建的 {@link Msg}。
     */
    public Mono<Msg> sendToSubagent(String subagentId, List<Msg> messages) {
        Objects.requireNonNull(subagentId, "subagentId");
        return resolveGateway().runSubagent(subagentId, requireMessages(messages));
    }

    /**
     * 发送结构化 {@link ChatUiRequest}，以响应式方式返回智能体回复。
     */
    public Mono<Msg> send(ChatUiRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.subagentId() != null) {
            return resolveGateway().runSubagent(request.subagentId(), request.messages());
        }
        return dispatch(buildInbound(request));
    }

    // -----------------------------------------------------------------
    // 流式发送 API
    // -----------------------------------------------------------------

    @Override
    public Flux<AgentEvent> dispatchStream(InboundMessage message) {
        Objects.requireNonNull(message, "message");
        RouteResult route = router.resolveRoute(config, message);
        return resolveGateway()
                .runStream(
                        route.context(),
                        message.messages(),
                        route.outboundAddress(),
                        message.runtimeContext(),
                        message);
    }

    /**
     * {@link #send(String)} 的流式变体，返回细粒度 {@link AgentEvent}。
     */
    public Flux<AgentEvent> sendStream(String text) {
        return sendStream(ChatUiRequest.of(Objects.requireNonNull(text, "text")));
    }

    /**
     * {@link #send(Msg)} 的流式变体。
     */
    public Flux<AgentEvent> sendStream(Msg message) {
        Objects.requireNonNull(message, "message");
        return sendStream(ChatUiRequest.of(List.of(message)));
    }

    /**
     * {@link #send(List)} 的流式变体。
     */
    public Flux<AgentEvent> sendStream(List<Msg> messages) {
        return sendStream(ChatUiRequest.of(requireMessages(messages)));
    }

    /**
     * {@link #send(String, String)} 的流式变体。
     */
    public Flux<AgentEvent> sendStream(String peerId, String text) {
        Objects.requireNonNull(peerId, "peerId");
        Objects.requireNonNull(text, "text");
        return sendStream(ChatUiRequest.withPeer(peerId, text));
    }

    /**
     * {@link #send(String, Msg)} 的流式变体。
     */
    public Flux<AgentEvent> sendStream(String peerId, Msg message) {
        Objects.requireNonNull(peerId, "peerId");
        Objects.requireNonNull(message, "message");
        return sendStream(ChatUiRequest.withPeer(peerId, List.of(message)));
    }

    /**
     * {@link #send(String, List)} 的流式变体。
     */
    public Flux<AgentEvent> sendStream(String peerId, List<Msg> messages) {
        Objects.requireNonNull(peerId, "peerId");
        return sendStream(ChatUiRequest.withPeer(peerId, requireMessages(messages)));
    }

    /**
     * {@link #send(SendOptions, String)} 的流式变体。
     */
    public Flux<AgentEvent> sendStream(SendOptions options, String text) {
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(text, "text");
        Msg msg = Msg.builder().role(MsgRole.USER).name(options.userId()).textContent(text).build();
        return dispatchStreamWithOptions(options, List.of(msg));
    }

    /**
     * {@link #send(SendOptions, Msg)} 的流式变体。
     */
    public Flux<AgentEvent> sendStream(SendOptions options, Msg message) {
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(message, "message");
        return dispatchStreamWithOptions(options, List.of(message));
    }

    /**
     * {@link #send(SendOptions, List)} 的流式变体。
     */
    public Flux<AgentEvent> sendStream(SendOptions options, List<Msg> messages) {
        Objects.requireNonNull(options, "options");
        return dispatchStreamWithOptions(options, requireMessages(messages));
    }

    /**
     * {@link #sendToSubagent(String, String)} 的流式变体。
     */
    public Flux<AgentEvent> sendToSubagentStream(String subagentId, String text) {
        Objects.requireNonNull(subagentId, "subagentId");
        Objects.requireNonNull(text, "text");
        Msg msg = Msg.builder().role(MsgRole.USER).textContent(text).build();
        return resolveGateway().runSubagentStream(subagentId, List.of(msg));
    }

    /**
     * {@link #sendToSubagent(String, Msg)} 的流式变体。
     */
    public Flux<AgentEvent> sendToSubagentStream(String subagentId, Msg message) {
        Objects.requireNonNull(subagentId, "subagentId");
        Objects.requireNonNull(message, "message");
        return resolveGateway().runSubagentStream(subagentId, List.of(message));
    }

    /**
     * {@link #sendToSubagent(String, List)} 的流式变体。
     */
    public Flux<AgentEvent> sendToSubagentStream(String subagentId, List<Msg> messages) {
        Objects.requireNonNull(subagentId, "subagentId");
        return resolveGateway().runSubagentStream(subagentId, requireMessages(messages));
    }

    /**
     * {@link #send(ChatUiRequest)} 的流式变体。
     */
    public Flux<AgentEvent> sendStream(ChatUiRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.subagentId() != null) {
            return resolveGateway().runSubagentStream(request.subagentId(), request.messages());
        }
        return dispatchStream(buildInbound(request));
    }

    // -----------------------------------------------------------------
    // 内部实现
    // -----------------------------------------------------------------

    private static List<Msg> requireMessages(List<Msg> messages) {
        Objects.requireNonNull(messages, "messages");
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("messages must not be empty");
        }
        return messages;
    }

    private Mono<Msg> dispatchWithOptions(SendOptions options, List<Msg> messages) {
        MsgContext ctx = buildContextFromOptions(options);
        OutboundAddress outbound = buildOutboundFromOptions(options);
        return resolveGateway().run(ctx, messages, outbound, options.runtimeContext());
    }

    private Flux<AgentEvent> dispatchStreamWithOptions(SendOptions options, List<Msg> messages) {
        MsgContext ctx = buildContextFromOptions(options);
        OutboundAddress outbound = buildOutboundFromOptions(options);
        return resolveGateway().runStream(ctx, messages, outbound, options.runtimeContext());
    }

    private MsgContext buildContextFromOptions(SendOptions options) {
        String room = options.effectiveSessionKey();
        String agentId = options.agentId();
        if (agentId == null) {
            agentId = config.defaultAgentId();
        }
        Map<String, String> extra = agentId != null ? Map.of("agentId", agentId) : Map.of();
        return new MsgContext(CHANNEL_ID, null, room, null, null, extra)
                .withUserId(options.userId());
    }

    private OutboundAddress buildOutboundFromOptions(SendOptions options) {
        return OutboundAddress.direct(CHANNEL_ID, CHANNEL_ID + ":" + options.effectiveSessionKey());
    }

    private Gateway resolveGateway() {
        Gateway g = gateway;
        if (g == null) {
            throw new IllegalStateException(
                    "ChatUiChannel has no gateway. Use GatewayBootstrap.chatUiChannel(),"
                            + " or construct with ChatUiChannel.create(gateway).");
        }
        return g;
    }

    private InboundMessage buildInbound(ChatUiRequest request) {
        String peerId = request.peerId();
        String agentId = request.agentId();
        if (peerId != null && !peerId.isBlank()) {
            if (agentId != null) {
                return InboundMessage.dmFor(CHANNEL_ID, peerId.trim(), agentId, request.messages());
            }
            return InboundMessage.dm(CHANNEL_ID, peerId.trim(), request.messages());
        }
        if (agentId != null) {
            return InboundMessage.dmFor(CHANNEL_ID, "__anonymous__", agentId, request.messages());
        }
        return InboundMessage.dm(CHANNEL_ID, "__anonymous__", request.messages());
    }
}
