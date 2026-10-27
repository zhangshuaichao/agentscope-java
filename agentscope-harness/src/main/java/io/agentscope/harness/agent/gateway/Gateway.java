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

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.gateway.channel.InboundMessage;
import io.agentscope.harness.agent.gateway.channel.OutboundAddress;
import java.util.List;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 网关式入口：按 {@link MsgContext} 路由入站轮次，并绑定主 {@link
 * HarnessAgent} 以投递公告。
 *
 * <p>实现应按逻辑会话串行化轮次（公平等待），防止注入的公告执行与用户／渠道
 * {@link #run} 调用竞争同一 {@code HarnessAgent} 轮次。
 *
 * <p>调用方可提供合并到智能体轮次的 {@link RuntimeContext}。
 * 冲突时，网关拥有的身份字段（{@code sessionId}、{@code userId}、{@code outboundAddress} 等）始终优先。
 */
public interface Gateway {

    /**
     * 构造后绑定主 Harness 智能体。按 {@link HarnessAgent#getAgentId()} 注册它，
     * 供 {@link #run} 路由。
     */
    void bindMainAgent(HarnessAgent agent);

    /**
     * 注册用于路由的命名 Harness 智能体。{@link #run} 根据 {@link MsgContext#extra()}
     * 中提供的 {@code agentId}（键 {@code "agentId"}）解析目标，
     * 找不到时回退到 {@link #bindMainAgent} 注册的智能体。
     */
    default void registerAgent(String agentId, HarnessAgent agent) {}

    /**
     * 入站轮次（直接 API 或渠道适配器）。
     */
    Mono<Msg> run(MsgContext context, List<Msg> messages);

    /**
     * 带出站地址的入站轮次。将 {@code outboundAddress} 记录为会话的“最近路由”，
     * 使主动出站消息（例如子智能体公告）能够返回原始渠道／会话对端。
     *
     * @param context 用于解析会话键的路由上下文
     * @param messages 入站消息
     * @param outboundAddress 主动回复的投递目标；可为 null
     */
    default Mono<Msg> run(MsgContext context, List<Msg> messages, OutboundAddress outboundAddress) {
        return run(context, messages, outboundAddress, null);
    }

    /**
     * 带出站地址及可选调用方 {@link RuntimeContext} 的入站轮次。
     *
     * <p>调用方上下文合并到本轮，网关身份字段优先。
     * 默认实现忽略 {@code callerContext}，委托给 {@link
     * #run(MsgContext, List)}。
     *
     * @param context 用于解析会话键的路由上下文
     * @param messages 入站消息
     * @param outboundAddress 主动回复的投递目标；可为 null
     * @param callerContext 可选的待合并应用上下文；可为 null
     */
    default Mono<Msg> run(
            MsgContext context,
            List<Msg> messages,
            OutboundAddress outboundAddress,
            RuntimeContext callerContext) {
        return run(context, messages, outboundAddress, callerContext, null);
    }

    /**
     * 带完整渠道元数据的入站轮次，供 {@link
     * io.agentscope.harness.agent.gateway.channel.ChannelRuntimeContextResolver} 使用。
     *
     * @param inboundMessage 传给解析器的可选入站信封；可为 null
     */
    default Mono<Msg> run(
            MsgContext context,
            List<Msg> messages,
            OutboundAddress outboundAddress,
            RuntimeContext callerContext,
            InboundMessage inboundMessage) {
        return run(context, messages);
    }

    /**
     * 单消息便捷方法。
     */
    default Mono<Msg> run(MsgContext context, Msg message) {
        return run(context, List.of(message));
    }

    /**
     * {@link #run(MsgContext, List)} 的流式变体，返回细粒度事件。
     */
    default Flux<AgentEvent> runStream(MsgContext context, List<Msg> messages) {
        return runStream(context, messages, null, null);
    }

    /**
     * {@link #run(MsgContext, List, OutboundAddress)} 的流式变体。
     */
    default Flux<AgentEvent> runStream(
            MsgContext context, List<Msg> messages, OutboundAddress outboundAddress) {
        return runStream(context, messages, outboundAddress, null);
    }

    /**
     * {@link #run(MsgContext, List, OutboundAddress, RuntimeContext)} 的流式变体。
     *
     * <p>默认实现发出“不支持流式调用”的错误信号。
     */
    default Flux<AgentEvent> runStream(
            MsgContext context,
            List<Msg> messages,
            OutboundAddress outboundAddress,
            RuntimeContext callerContext) {
        return runStream(context, messages, outboundAddress, callerContext, null);
    }

    /**
     * {@link #run(MsgContext, List, OutboundAddress, RuntimeContext,
     * InboundMessage)} 的流式变体。
     */
    default Flux<AgentEvent> runStream(
            MsgContext context,
            List<Msg> messages,
            OutboundAddress outboundAddress,
            RuntimeContext callerContext,
            InboundMessage inboundMessage) {
        return Flux.error(new UnsupportedOperationException("Streaming not supported"));
    }

    /**
     * 将消息直接路由到 {@code subagentId} 标识的已公开子智能体会话，
     * 跳过常规绑定路由，返回子智能体回复。
     *
     * @param subagentId {@link SubagentGatewayBridge.ExposeResult#subagentId()} 返回的句柄
     * @param messages 要发送给子智能体的消息
     */
    default Mono<Msg> runSubagent(String subagentId, List<Msg> messages) {
        return Mono.error(new UnsupportedOperationException("Subagent routing not supported"));
    }

    /**
     * {@link #runSubagent(String, List)} 的流式变体。
     */
    default Flux<AgentEvent> runSubagentStream(String subagentId, List<Msg> messages) {
        return Flux.error(new UnsupportedOperationException("Subagent streaming not supported"));
    }
}
