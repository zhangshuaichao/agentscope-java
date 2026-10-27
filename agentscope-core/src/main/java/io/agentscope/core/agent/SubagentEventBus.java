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
package io.agentscope.core.agent;

import java.util.Optional;
import reactor.util.context.ContextView;

/**
 * 轻量级通道，允许同步调用的子智能体将其 {@link Event} 推送到父智能体的 {@code Flux<Event>} 流中。
 *
 * <p>实例在 {@link AgentBase#createEventStream} 内创建，并以 {@link #CONTEXT_KEY} 为键存入 Reactor Context。
 * 在该流管道中执行的工具方法（例如 {@code agent_spawn}）可以通过 {@link #fromContext(ContextView)}
 * 获取总线，并将每个子事件转发到父级 sink。
 *
 * <p>通过总线发出的事件<em>必须</em>已携带调用方附加的 {@link EventSource}，以便下游消费者识别来源子智能体。
 *
 * <p>父智能体通过非流式的 {@code call()} 调用时，上下文中不存在总线，{@link #fromContext}
 * 返回 {@link Optional#empty()}，调用方因而可以平稳回退到阻塞执行路径。
 */
public interface SubagentEventBus {

    /**
     * 在 Reactor Context 中存储总线实例的键。仅供内部使用；请通过 {@link #fromContext(ContextView)} 获取总线。
     */
    String CONTEXT_KEY = "agentscope.subagent.event.bus";

    /**
     * 向父级流发出子智能体事件。事件应携带非空的 {@link EventSource}，以标识来源子智能体。
     *
     * <p>此方法可从任意线程安全调用；底层 {@code FluxSink.next} 是线程安全的。
     *
     * @param event 要转发的事件（必须设置 {@link EventSource}）
     */
    void emit(Event event);

    /**
     * 从 Reactor Context 获取 {@link SubagentEventBus}（如果存在）。
     *
     * @param ctx 当前 Reactor 订阅者上下文
     * @return 包含总线的 {@link Optional}；在流式管道之外运行时为空
     */
    static Optional<SubagentEventBus> fromContext(ContextView ctx) {
        if (ctx == null || !ctx.hasKey(CONTEXT_KEY)) {
            return Optional.empty();
        }
        return Optional.ofNullable(ctx.getOrDefault(CONTEXT_KEY, null));
    }
}
