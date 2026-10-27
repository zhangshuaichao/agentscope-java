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
package io.agentscope.core.hook;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Toolkit;
import java.util.Collections;
import java.util.List;
import reactor.core.publisher.Mono;

/**
 * 用于监控和拦截智能体执行的钩子接口。
 *
 * <p>【学习提示】此包属于已弃用的兼容路径，新功能优先学习 MiddlewareBase。
 * 旧 Hook 的 priority 数值越小越先执行，和新 Middleware 的 order 数值越大越先执行不同。
 * 下方保留的历史代码示例不是当前 Java 17 推荐写法；其中直接插入 SYSTEM 消息的方式
 * 也应以 HookEvent 当前的系统消息 API 约束为准，不要直接照搬。
 *
 * <p>所有智能体执行事件都通过单个 {@link #onEvent(HookEvent)} 方法传递。
 * 此统一事件模型以清晰、类型安全的方式拦截和修改智能体行为。
 *
 * <p><b>钩子优先级：</b>钩子按优先级顺序执行（数值越小，优先级越高）。
 * 默认优先级为 100。优先级相同的钩子按注册顺序执行。
 *
 * <p><b>事件可修改性：</b>事件是否可修改，取决于是否存在
 * setter 方法：
 * <ul>
 *   <li>具有 setter 的事件（例如 {@link PreReasoningEvent#setInputMessages}）允许
 *       修改</li>
 *   <li>没有 setter 的事件仅用于通知</li>
 * </ul>
 *
 * <p><b>使用示例：</b>
 *
 * <pre>{@code
 * // 使用默认优先级的基础钩子
 * Hook loggingHook = new Hook() {
 *     @Override
 *     public <T extends HookEvent> Mono<T> onEvent(T event) {
 *         return switch (event) {
 *             case PreReasoningEvent e -> {
 *                 System.out.println("Reasoning with model: " + e.getModelName());
 *                 yield Mono.just(e);
 *             }
 *             case ReasoningChunkEvent e -> {
 *                 // 显示流式输出
 *                 System.out.print(extractText(e.getIncrementalChunk()));
 *                 yield Mono.just(e);
 *             }
 *             default -> Mono.just(event);
 *         };
 *     }
 * };
 *
 * // 高优先级钩子，先执行
 * Hook authHook = new Hook() {
 *     @Override
 *     public int priority() {
 *         return 10;  // 高优先级
 *     }
 *
 *     @Override
 *     public <T extends HookEvent> Mono<T> onEvent(T event) {
 *         return switch (event) {
 *             case PreActingEvent e -> {
 *                 // 在其他钩子之前注入认证令牌
 *                 ToolUseBlock toolUse = e.getToolUse();
 *                 // ……添加认证信息
 *                 e.setToolUse(toolUse);
 *                 yield Mono.just(e);
 *             }
 *             default -> Mono.just(event);
 *         };
 *     }
 * };
 *
 * // 修改事件
 * Hook hintInjector = new Hook() {
 *     @Override
 *     public <T extends HookEvent> Mono<T> onEvent(T event) {
 *         return switch (event) {
 *             case PreReasoningEvent e -> {
 *                 // 在 LLM 推理前修改消息
 *                 List<Msg> msgs = new ArrayList<>(e.getInputMessages());
 *                 msgs.add(0, Msg.builder()
 *                         .role(MsgRole.SYSTEM)
 *                         .content(new TextBlock("Think step by step"))
 *                         .build());
 *                 e.setInputMessages(msgs);
 *                 yield Mono.just(e);
 *             }
 *             case PostActingEvent e -> {
 *                 // 修改工具结果
 *                 ToolResultBlock result = e.getToolResult();
 *                 // ……处理结果
 *                 e.setToolResult(result);
 *                 yield Mono.just(e);
 *             }
 *             default -> Mono.just(event);
 *         };
 *     }
 * };
 * }</pre>
 *
 * @see HookEvent
 * @see HookEventType
 * @deprecated 自 2.0.0 起弃用。请改用 {@link io.agentscope.core.middleware.MiddlewareBase}。
 *     Middleware 暴露 {@code onAgent}、{@code onReasoning}、{@code onActing}、
 *     {@code onModelCall} 和 {@code onSystemPrompt} 拦截点，替代整个
 *     旧钩子系统。
 */
@Deprecated(forRemoval = true, since = "2.0.0")
public interface Hook {

    /**
     * 处理钩子事件。
     *
     * <p>所有智能体执行事件都会调用此方法。使用模式匹配来处理
     * 特定事件类型。
     *
     * <p><b>可修改事件：</b>对于具有 setter 的事件，可修改上下文，
     * 这些改动会影响智能体执行：
     * <ul>
     *   <li>{@link PreReasoningEvent}：LLM 推理前修改消息</li>
     *   <li>{@link PostReasoningEvent}：修改推理结果</li>
     *   <li>{@link PreActingEvent}：执行前修改工具参数</li>
     *   <li>{@link PostActingEvent}：修改工具结果</li>
     *   <li>{@link PreCallEvent}：智能体开始前修改消息</li>
     *   <li>{@link PostCallEvent}：修改智能体最终响应</li>
     * </ul>
     *
     * <p><b>通知事件：</b>没有 setter 的事件为只读：
     * <ul>
     *   <li>{@link ReasoningChunkEvent}：流式推理片段</li>
     *   <li>{@link ActingChunkEvent}：流式工具执行片段</li>
     *   <li>{@link ErrorEvent}：执行期间的错误</li>
     * </ul>
     *
     * @param event 钩子事件
     * @param <T> 具体事件类型
     * @return 包含可能已修改事件的 Mono
     */
    <T extends HookEvent> Mono<T> onEvent(T event);

    /**
     * 与此钩子一同安装的可选工具。
     *
     * <p>在 {@link ReActAgent.Builder#build()} 期间，框架复制构建器的 {@link
     * Toolkit}，再把每个钩子 {@code tools()} 列表中的非 null 元素，
     * 通过 {@link Toolkit#registerTool(Object)} 注册到智能体本地副本。
     *
     * <p>返回 {@link AgentTool} 实例和／或声明了 {@code @Tool} 方法的对象。
     * 默认实现返回空列表，因此现有钩子无须修改。
     *
     * <p>此方法返回 {@code null} 时，按空列表处理。
     *
     * @return 要为此钩子注册的工具实例（可以不可变）
     */
    default List<Object> tools() {
        return Collections.emptyList();
    }

    /**
     * 此钩子的优先级（数值越小，优先级越高）。
     *
     * <p>钩子按优先级数值升序执行。优先级相同的钩子
     * 按注册顺序执行。
     *
     * <p><b>常用优先级范围：</b>
     * <ul>
     *   <li>0-50：关键系统钩子（认证、安全）</li>
     *   <li>51-100：高优先级钩子（验证、预处理）</li>
     *   <li>101-500：普通优先级钩子（业务逻辑）</li>
     *   <li>501-1000：低优先级钩子（日志、指标）</li>
     * </ul>
     *
     * @return 优先级值（默认：100）
     */
    default int priority() {
        return 100;
    }
}
