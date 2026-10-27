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
package io.agentscope.core.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.state.AgentState;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 中间件在智能体生命周期的 6 个关键执行点提供拦截机制。
 *
 * <p><b>洋葱模式</b>（4 个钩子，在执行前后包装逻辑）：
 * <ul>
 *   <li>{@link #onAgent}：拦截整个智能体调用</li>
 *   <li>{@link #onReasoning}：拦截推理／模型调用阶段</li>
 *   <li>{@link #onActing}：拦截单个工具调用的执行</li>
 *   <li>{@link #onModelCall}：拦截原始模型 API 调用</li>
 * </ul>
 *
 * <p><b>转换器／流水线模式</b>（1 个钩子，依次转换）：
 * <ul>
 *   <li>{@link #onSystemPrompt}：转换系统提示词字符串</li>
 * </ul>
 *
 * <p><b>通知模式</b>（单向同步通知，不通过 {@code next} 委派）：
 * <ul>
 *   <li>{@link #onAgentStateReady}：本次调用的 AgentState 就绪后收到通知</li>
 * </ul>
 *
 * <p>每个洋葱式／转换式钩子的默认实现都会直接委派给 {@code next}，
 * 因此子类只需重写自己关心的钩子；{@link #onAgentStateReady} 默认不执行操作。
 *
 * <p><b>示例：</b>
 * <pre>{@code
 * MiddlewareBase logging = new MiddlewareBase() {
 *     @Override
 *     public Flux<AgentEvent> onReasoning(
 *             Agent agent, RuntimeContext ctx, ReasoningInput input,
 *             Function<ReasoningInput, Flux<AgentEvent>> next) {
 *         System.out.println("Before reasoning, session=" + ctx.getSessionId());
 *         return next.apply(input)
 *             .doOnComplete(() -> System.out.println("After reasoning"));
 *     }
 * };
 * }</pre>
 */
public interface MiddlewareBase {

    /**
     * 中间件扩展点。新增扩展点时只需在此添加一个常量，
     * 分组机制保持不变。
     */
    enum ExtensionPoint {
        ON_AGENT,
        ON_REASONING,
        ON_ACTING,
        ON_MODEL_CALL,
        ON_SYSTEM_PROMPT,
        ON_AGENT_STATE_READY
    }

    /**
     * 返回此中间件启用的扩展点。这是参与开关，而不是对方法是否被重写
     * 的声明：某个扩展点已启用但对应方法未重写时，只运行默认实现
     * （直接透传或不执行操作）；未列出的扩展点即使被重写也不会调用。
     *
     * <p>默认在所有扩展点上启用，包括未来版本新增的扩展点，
     * 因此未重写此方法的中间件会保持现有行为。重写后，启用范围
     * 就严格等于返回的集合；空集合会在保留注册的同时禁用全部扩展点。
     *
     * <p>启用情况在构建智能体时一次性确定；之后修改返回的集合
     * 或改变后续调用的返回结果，都不会影响已构建的智能体。
     *
     * @return 此中间件参与的扩展点集合；{@code null} 视为完整集合；
     *         不等于某个 {@link ExtensionPoint} 常量的元素不会启用任何扩展点
     */
    default Set<ExtensionPoint> activePoints() {
        return EnumSet.allOf(ExtensionPoint.class);
    }

    /**
     * 返回此中间件的执行顺序。
     *
     * <p>数值越大，优先级越高，在洋葱链中越靠外：例如 {@code 2} 先于
     * {@code 1} 运行，而 {@code 0} 后于 {@code 1} 运行。顺序值相同的中间件
     * 保持在构建器中的注册顺序。默认值为 {@code 1}。
     *
     * @return 执行顺序；数值越大，越先执行，然后再委派给 {@code next}
     */
    default int order() {
        return 1;
    }

    /**
     * 拦截整个智能体调用。
     *
     * @param agent 智能体实例
     * @param ctx   调用级运行上下文（会话、用户、属性）
     * @param input 智能体输入（消息）
     * @param next  调用下一个中间件或智能体核心逻辑
     * @return 智能体调用产生的事件流
     */
    default Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext ctx,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        return next.apply(input);
    }

    /**
     * 拦截推理阶段（LLM 调用与流式输出解析）。
     *
     * @param agent 智能体实例
     * @param ctx   调用级运行上下文（会话、用户、属性）
     * @param input 推理输入（消息、工具、选项）
     * @param next  调用下一个中间件或核心推理逻辑
     * @return 推理产生的事件流
     */
    default Flux<AgentEvent> onReasoning(
            Agent agent,
            RuntimeContext ctx,
            ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        return next.apply(input);
    }

    /**
     * 拦截工具调用执行阶段。
     *
     * @param agent 智能体实例
     * @param ctx   调用级运行上下文（会话、用户、属性）
     * @param input 行动输入（工具调用）
     * @param next  调用下一个中间件或核心行动逻辑
     * @return 行动产生的事件流
     */
    default Flux<AgentEvent> onActing(
            Agent agent,
            RuntimeContext ctx,
            ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        return next.apply(input);
    }

    /**
     * 拦截原始模型 API 调用。
     *
     * <p>对返回流中 {@link io.agentscope.core.event.TextBlockDeltaEvent} 实例的转换
     * 会反映到最终响应消息中。因此，中间件可以在使用方（包括原生结构化输出解析）
     * 使用模型文本之前，先将其规范化。
     *
     * @param agent 智能体实例
     * @param ctx   调用级运行上下文（会话、用户、属性）
     * @param input 模型调用输入（消息、工具、选项、模型）
     * @param next  调用下一个中间件或实际模型
     * @return 模型调用产生的事件流
     */
    default Flux<AgentEvent> onModelCall(
            Agent agent,
            RuntimeContext ctx,
            ModelCallInput input,
            Function<ModelCallInput, Flux<AgentEvent>> next) {
        return next.apply(input);
    }

    /**
     * 转换系统提示词字符串（流水线模式）。
     *
     * <p>多个中间件依次应用；每个中间件接收前一个的输出。
     *
     * @param agent         智能体实例
     * @param ctx           调用级运行上下文（会话、用户、属性）
     * @param currentPrompt 当前系统提示词
     * @return 系统提示词（可能已经转换）
     */
    default Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
        return Mono.just(currentPrompt);
    }

    /**
     * 每次生命周期执行（订阅）通知一次，时机为本次调用的 {@link AgentState}
     * 刚绑定到 {@link RuntimeContext} 之后：状态可能来自
     * {@code (userId, sessionId)} 对应的槽位，也可能为全新会话新建。
     * 此时输入尚未进入处理流水线（记忆、推理）。
     *
     * <p>这是没有 {@code next} 委派的单向同步通知：中间件按 {@link #order()}
     * 顺序运行（数值较大的先运行），且不得阻塞。绝不能再次进入同一会话的智能体，
     * 否则会在调用门控处发生死锁。抛出异常会使本次调用失败：异常原样传播给调用方，
     * 剩余中间件不再调用。
     *
     * <p>生命周期窗口：通知执行时，本次调用已经获准进入并注册了中断处理，
     * 但请求状态绑定、追踪封装与错误事件链尚未就绪。通知期间发出的中断
     * 会得到处理；但抛出的异常会直接到达调用方，不经过 ErrorEvent 钩子
     * 或追踪跨度，因此实现应保持非阻塞并快速失败。
     *
     * @param agent         智能体实例
     * @param ctx           调用级运行上下文（会话、用户、属性）
     * @param state         本次调用刚就绪的状态，与 {@code ctx.getAgentState()}
     *                      是同一实例，绝不为 {@code null}；全新会话的上下文为空
     * @param inputMessages 本次调用的输入（已经经过 {@code onAgent} 中间件，尚未
     *                      与记忆合并），以私有可变副本形式提供；原地修改影响整个调用，
     *                      但不会改变调用方的原始列表
     */
    default void onAgentStateReady(
            Agent agent, RuntimeContext ctx, AgentState state, List<Msg> inputMessages) {}
}
