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
package io.agentscope.core.tool;

import io.agentscope.core.message.ToolResultBlock;
import java.util.function.Function;
import reactor.core.publisher.Mono;

/**
 * 在 Toolkit 层拦截单次工具调用，位置在模式验证、预设参数合并之后，会话分派日志和真实执行之前。
 *
 * <p>适用于工具级权限无法表达的逐调用、参数级业务检查：按调用方身份限制文件路径、审计、参数规范化和策略拒绝。
 * “此工具是否可调用”的工具级 ALLOW/ASK/DENY 和人工审核仍由权限引擎负责。
 *
 * <p>常见操作：
 * <ul>
 * <li>放行：{@code next.apply(invocation)}
 * <li>策略拒绝：{@code Mono.just(ToolResultBlock.text(msg)
 *  .withState(ToolResultState.DENIED))}，记录为 {@code denied} 决策
 * <li>无效调用：{@code Mono.just(ToolResultBlock.error(msg))}，不记录拒绝决策
 * <li>重写参数：{@code next.apply(invocation.withInput(newInput))}（可覆盖预设值，不再按模式验证）
 * <li>审计：{@code next.apply(invocation).doOnSuccess(...)}
 * <li>挂起以进行 HITL：抛出 {@link ToolSuspendException}
 * </ul>
 *
 * <p>实现不得返回 {@code null} 或空 {@code Mono}（空 Mono 会映射为容易误导的“完成但未返回结果”错误），
 * 且必须无状态或线程安全：注册到 {@link ToolkitConfig} 的拦截器由并发会话共享。
 * 直接 {@link Toolkit#callTool(ToolCallParam)} 路径没有超时或调度包装，禁止阻塞；应将阻塞 I/O 转移到 Reactor 调度器。
 *
 * <p>外部工具（{@code externalTool=true} / {@link SchemaOnlyTool}）在框架之外执行，不会进入拦截器。
 *
 * @author shanhongyu
 */
public interface ToolCallInterceptor {

    /**
     * 拦截单次工具调用。
     *
     * @param invocation 调用快照（工具名称、工具实例、最终输入、运行时上下文）
     * @param next 调用下一拦截器或真实工具执行
     * @return 工具结果（放行结果、拒绝原因或重写后执行结果）；不得为空 {@code Mono}
     */
    Mono<ToolResultBlock> intercept(
            ToolInvocation invocation, Function<ToolInvocation, Mono<ToolResultBlock>> next);

    /**
     * 执行顺序：数值越大越靠近链外侧（先拦截，最后收到结果）。顺序相同则保持注册顺序，与 {@code MiddlewareBase#order()} 约定一致，默认为 {@code 1}。
     *
     * @return 执行顺序；较高值位于最外层
     */
    default int order() {
        return 1;
    }
}
