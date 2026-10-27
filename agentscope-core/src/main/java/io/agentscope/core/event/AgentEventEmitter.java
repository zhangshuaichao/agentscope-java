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
package io.agentscope.core.event;

import java.util.Optional;
import reactor.util.context.ContextView;

/**
 * 公共接口，供工具执行代码向父智能体的 {@code streamEvents()} 事件流发出
 * {@link AgentEvent}。
 *
 * <p>实例在 {@link io.agentscope.core.ReActAgent#streamEvents} 内创建，并以
 * {@link #CONTEXT_KEY} 为键存入 Reactor Context。当工具方法（例如 {@code agent_spawn}）
 * 在该流式流水线中运行时，可以通过 {@link #fromContext(ContextView)} 取得发射器，
 * 注入自定义事件（例如 {@link SubagentExposedEvent}）。
 *
 * <p>父智能体通过 {@code call()}（非流式）调用时，上下文中没有发射器，
 * {@link #fromContext} 返回 {@link Optional#empty()}，使调用方能够平稳回退。
 *
 * <p>这是 {@link io.agentscope.core.agent.SubagentEventBus} 对应的
 * {@link AgentEvent} 版本；后者服务于已弃用的 {@link io.agentscope.core.agent.Event} 类型。
 */
@FunctionalInterface
public interface AgentEventEmitter {

    /**
     * 在 Reactor Context 中存储发射器实例的键。请使用
     * {@link #fromContext(ContextView)} 获取实例。
     */
    String CONTEXT_KEY = "agentscope.agent.event.emitter";

    /**
     * 父工具（例如 {@code agent_spawn}）向子智能体的 Reactor Context 注入转发发射器时
     * 使用的键。存在此发射器时，子智能体的 {@code publishEvent()} 使用它而非直接
     * 使用 {@code FluxSink}，从而在每个子事件进入父事件流前标记来源路径。
     *
     * <p>此键有意与 {@link #CONTEXT_KEY} 分开：常规键携带父智能体自身的发射器，
     * 工具用它发出 {@link SubagentExposedEvent} 等自定义事件；
     * 此键则携带为子智能体内部事件添加来源标记的包装器。
     */
    String FORWARDING_CONTEXT_KEY = "agentscope.agent.event.forwarding.emitter";

    /**
     * 向父智能体的 {@code streamEvents()} 流发出一个 {@link AgentEvent}。
     *
     * <p>可从任何线程安全调用此方法；底层 {@code FluxSink.next} 是线程安全的。
     *
     * @param event 要发出的事件
     */
    void emit(AgentEvent event);

    /**
     * 从 Reactor Context 获取 {@link AgentEventEmitter}（若存在）。
     *
     * @param ctx 当前 Reactor 订阅方上下文
     * @return 包含发射器的 {@link Optional}；不在流式流水线中运行时为空
     */
    static Optional<AgentEventEmitter> fromContext(ContextView ctx) {
        if (ctx == null || !ctx.hasKey(CONTEXT_KEY)) {
            return Optional.empty();
        }
        return Optional.ofNullable(ctx.getOrDefault(CONTEXT_KEY, null));
    }

    /**
     * 从 Reactor Context 获取用于转发的 {@link AgentEventEmitter}（若存在）。
     *
     * @param ctx 当前 Reactor 订阅方上下文
     * @return 包含转发发射器的 {@link Optional}；不在子智能体转发上下文中时为空
     */
    static Optional<AgentEventEmitter> fromForwardingContext(ContextView ctx) {
        if (ctx == null || !ctx.hasKey(FORWARDING_CONTEXT_KEY)) {
            return Optional.empty();
        }
        return Optional.ofNullable(ctx.getOrDefault(FORWARDING_CONTEXT_KEY, null));
    }
}
