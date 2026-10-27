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
import java.util.List;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/**
 * 为指定拦截点构建洋葱式中间件链。
 *
 * <p>构建过程从后向前进行：最后一个中间件包装核心逻辑，
 * 第一个中间件位于最外层。
 */
public final class MiddlewareChain {

    private MiddlewareChain() {}

    /**
     * 构建产生 {@code Flux<AgentEvent>} 的中间件链。
     *
     * @param middlewares 有序的中间件列表（第一项位于最外层）
     * @param agent      传给每个中间件的智能体实例
     * @param ctx        传给每个中间件的调用级运行上下文
     * @param method     中间件钩子方法的引用
     * @param core       所有中间件完成委派后要执行的最内层逻辑
     * @param <I>        此拦截点的输入类型
     * @return 一个函数，将其应用于输入即可运行完整的链
     */
    public static <I> Function<I, Flux<AgentEvent>> build(
            List<? extends MiddlewareBase> middlewares,
            Agent agent,
            RuntimeContext ctx,
            MiddlewareMethod<I> method,
            Function<I, Flux<AgentEvent>> core) {
        if (middlewares == null || middlewares.isEmpty()) {
            return core;
        }
        // 【学习提示】先以核心函数为最内层，再逆序包装。列表为 [A, B] 时，
        // 构造得到 A(B(core))，所以调用时先进入 A，再进入 B，而不是按逆序执行。
        Function<I, Flux<AgentEvent>> chain = core;
        for (int i = middlewares.size() - 1; i >= 0; i--) {
            MiddlewareBase mw = middlewares.get(i);
            // 【学习提示】每轮固定捕获当时的内层链；新的 lambda 包装 next，
            // 不捕获随后会更新的 chain，从而保留正确的嵌套关系。
            Function<I, Flux<AgentEvent>> next = chain;
            chain = input -> method.apply(mw, agent, ctx, input, next);
        }
        // 【学习提示】此处返回的是组装好的函数，不会主动订阅 Flux。
        // 应用 input 会进入函数链；响应式流水线的实际执行仍由下游订阅驱动。
        return chain;
    }

    /**
     * 表示某个洋葱式中间件钩子的函数式接口。
     *
     * @param <I> 输入类型
     */
    @FunctionalInterface
    public interface MiddlewareMethod<I> {
        Flux<AgentEvent> apply(
                MiddlewareBase mw,
                Agent agent,
                RuntimeContext ctx,
                I input,
                Function<I, Flux<AgentEvent>> next);
    }
}
