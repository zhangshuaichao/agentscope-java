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
package io.agentscope.core.shutdown;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * 将优雅关闭集成到智能体生命周期中的系统中间件。
 *
 * <p>每次调用的请求注册与注销由 {@code AgentBase.call()} 处理：每次调用注册独立的
 * {@code requestId}（通过 Reactor Context 中的 {@link
 * io.agentscope.core.agent.AgentBase#SHUTDOWN_REQUEST_ID_KEY} 携带），并通过
 * {@code doFinally} 注销，保证无论成功、出错还是取消，已注册请求始终会被注销；
 * 同一智能体实例上的并发调用也会被独立跟踪。
 *
 * <p>此中间件负责：
 * <ul>
 *   <li>{@code onAgent}：从关闭中断的会话恢复时，对输入去重</li>
 *   <li>{@code onReasoning}（doOnComplete）：推理完成后创建检查点</li>
 *   <li>{@code onActing}（doOnComplete）：行动完成后创建检查点</li>
 * </ul>
 *
 * <p>【学习提示】上方职责列表沿用上游描述；此类当前仅在 activePoints 中启用
 * ON_REASONING 与 ON_ACTING，未实现 onAgent。请按实际注册点追踪恢复输入去重逻辑。
 *
 * <p>关闭检查点：系统处于 SHUTTING_DOWN 状态时，智能体会在这些安全点
 * （当前阶段完全结束之后）中断。因此先允许推理／行动完成，再发出中断，
 * 避免浪费已经生成的输出词元。只有达到全局关闭超时时，才会在阶段中途
 * 强制中断智能体（由 GracefulShutdownManager#enforceTimeoutAndInterrupt 处理）。
 */
public final class GracefulShutdownMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(GracefulShutdownMiddleware.class);

    private final GracefulShutdownManager manager;

    public GracefulShutdownMiddleware(GracefulShutdownManager manager) {
        this.manager = manager;
    }

    /** 仅声明所需的拦截点；子类若重写更多钩子，必须扩展此集合。 */
    @Override
    public Set<ExtensionPoint> activePoints() {
        return EnumSet.of(ExtensionPoint.ON_REASONING, ExtensionPoint.ON_ACTING);
    }

    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent,
            RuntimeContext ctx,
            ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        // 【学习提示】在订阅时读取当前请求的 Reactor Context，而不是保存到共享字段。
        // doOnComplete 只表示本阶段正常完成；取消／错误路径的注销由 AgentBase 的 doFinally 负责。
        return Flux.deferContextual(
                cv ->
                        next.apply(input)
                                .doOnComplete(
                                        () ->
                                                manager.interruptIfShuttingDown(
                                                        currentRequestId(cv))));
    }

    @Override
    public Flux<AgentEvent> onActing(
            Agent agent,
            RuntimeContext ctx,
            ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        return Flux.deferContextual(
                cv ->
                        next.apply(input)
                                .doOnComplete(
                                        () ->
                                                manager.interruptIfShuttingDown(
                                                        currentRequestId(cv))));
    }

    private static String currentRequestId(reactor.util.context.ContextView cv) {
        return (String) cv.getOrDefault(AgentBase.SHUTDOWN_REQUEST_ID_KEY, null);
    }
}
