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
package io.agentscope.harness.agent.gateway.channel;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.gateway.Gateway;
import java.util.List;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 渠道适配器：从一个消息平台（或编程调用方）接收入站消息，
 * 通过 {@link ChannelRouter} 解析目标智能体与会话，再委托 {@link Gateway} 执行。
 *
 * <h2>生命周期</h2>
 *
 * <ol>
 *   <li>{@link #init(Gateway)} — 由网关启动引导在启动前注入自动装配的 {@link Gateway}。
 *       实现应保存网关并完成连接前准备。构造时已接收网关的渠道可使用默认空操作。
 *   <li>{@link #start()} — 连接外部事件源（webhook 端点、长轮询、WebSocket 等），
 *       开始分发入站消息；编程式渠道可不执行操作。
 *   <li>{@link #stop()} — 断开连接并释放资源。
 * </ol>
 *
 * <h2>消息分发</h2>
 *
 * 实现调用 {@link Gateway#run}，传入 {@link ChannelRouter#resolveRoute} 生成的
 * {@link io.agentscope.harness.agent.gateway.MsgContext}、解析出的 {@link OutboundAddress}，
 * 以及 {@link InboundMessage#runtimeContext()} 携带的调用方
 * {@link io.agentscope.core.agent.RuntimeContext}。
 * 返回的 {@link Msg} 回复由渠道适配器送回原平台。
 *
 * @see ChannelConfig
 * @see ChannelRouter
 * @see Gateway
 */
public interface Channel {

    /**
     * 此渠道的逻辑标识（例如 {@code "chatui"}、{@code "slack"}、{@code "discord"}）。
     * 必须匹配 {@link ChannelConfig#channelId()}。
     */
    String channelId();

    /**
     * 返回此渠道的路由配置。
     */
    ChannelConfig config();

    /**
     * 由网关启动引导在 {@link #start()} 前调用，提供自动装配的 {@link Gateway}。
     * 需要网关但未在构造时接收它的实现应覆盖此方法。默认不执行操作。
     */
    default void init(Gateway gateway) {}

    /**
     * 连接外部事件源并开始分发入站消息。编程式渠道可不执行操作。
     */
    default void start() {}

    /**
     * 断开外部事件源并释放资源。编程式渠道可不执行操作。
     */
    default void stop() {}

    /**
     * 将完整构造的 {@link InboundMessage} 经路由分发，返回智能体回复。
     * 渠道实现负责：
     *
     * <ol>
     *   <li>调用 {@link ChannelRouter#resolveRoute} 获得 {@link RouteResult}
     *       （其中包含回复路由的 {@link OutboundAddress}）
     *   <li>将 {@link RouteResult#context()}、消息和 {@link
     *       RouteResult#outboundAddress()} 传给 {@link Gateway#run}
     * </ol>
     */
    Mono<Msg> dispatch(InboundMessage message);

    /**
     * {@link #dispatch(InboundMessage)} 的流式变体。
     * 返回细粒度 {@link AgentEvent}，而不是单个回复。
     *
     * <p>默认根据解析出的路由委托给 {@link Gateway#runStream}。
     */
    default Flux<AgentEvent> dispatchStream(InboundMessage message) {
        return Flux.error(new UnsupportedOperationException("Streaming dispatch not supported"));
    }

    /**
     * 将主动出站消息（例如子智能体完成公告）投递到此渠道的传输层。
     * 智能体产生需要推送回原始渠道／对端、而不是同步返回的回复时，由网关调用。
     *
     * <p>默认实现不执行操作，适用于不支持主动推送的拉取式渠道。
     *
     * @param address 投递目标（对端、线程、账户上下文）
     * @param messages 要投递的消息
     */
    default void deliver(OutboundAddress address, List<Msg> messages) {}

    /**
     * 投递一条持久化通知并返回服务商消息 ID，而非本地确认。
     * 不支持的传输必须显式失败，使调用方能够保持通知待处理状态。
     */
    default Mono<String> deliverWithReceipt(
            OutboundAddress address, Msg message, String deliveryId) {
        return Mono.error(new UnsupportedOperationException("Provider receipts are not supported"));
    }

    /**
     * 应用新的路由 {@link ChannelConfig}（绑定、dmScope、defaultAgentId），而不拆除渠道传输。
     * 将 {@link #config()} 保存在可变／volatile 引用中的实现可覆盖此方法，以支持绑定热加载。
     *
     * <p>默认返回 {@code false} 表示不支持在线替换配置。
     * 返回 {@code true} 表示替换已应用，后续入站消息将使用新配置路由。
     *
     * @param newConfig 要安装的新路由配置（channelId 必须匹配 {@link #channelId()}）
     * @return 在线应用成功为 {@code true}；需要重启渠道才能采用新配置时为 {@code false}
     */
    default boolean applyRoutingConfig(ChannelConfig newConfig) {
        return false;
    }
}
