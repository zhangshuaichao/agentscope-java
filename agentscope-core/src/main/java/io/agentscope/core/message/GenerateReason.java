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
package io.agentscope.core.message;

/**
 * 表示智能体生成消息的原因。
 *
 * <p>消息返回时，此枚举帮助用户理解智能体执行的上下文和状态。
 * 不同原因可能意味着需要采取不同的后续操作。
 *
 * <p>使用示例：
 * <pre>{@code
 * Msg response = agent.call(userMsg).block();
 * switch (response.getGenerateReason()) {
 *     case MODEL_STOP -> System.out.println("Task completed");
 *     case TOOL_SUSPENDED -> handleSuspendedTools(response);
 *     case REASONING_STOP_REQUESTED -> handleHumanReview(response);
 *     // ...
 * }
 * }</pre>
 */
public enum GenerateReason {

    /**
     * 模型正常停止，任务已完成。
     */
    MODEL_STOP,

    /**
     * 模型返回工具调用（内部工具，框架将继续执行）。
     */
    TOOL_CALLS,

    /**
     * 结构化输出已完成。
     */
    STRUCTURED_OUTPUT,

    /**
     * 工具执行已挂起，等待用户提供结果。
     */
    TOOL_SUSPENDED,

    /**
     * 推理阶段被 Hook 停止（PostReasoningEvent.stopAgent()）。
     */
    REASONING_STOP_REQUESTED,

    /**
     * 行动阶段被 Hook 停止（PostActingEvent.stopAgent()）。
     */
    ACTING_STOP_REQUESTED,

    /**
     * 权限引擎正在请求用户确认一个或多个工具调用。
     *
     * <p>返回的 Msg 包含 {@code state=ASKING} 的 ToolUseBlock。调用方通过第二次 {@code agent.call(...)} 携带 {@code ConfirmResult} 来恢复执行。
     */
    PERMISSION_ASKING,

    /**
     * 中间件通过 {@code RequestStopEvent} 请求智能体暂停。
     *
     * <p>调用方再次无参调用 {@code agent.call()} 即可恢复；智能体会接续待处理的工具调用，从暂停位置继续。
     */
    MIDDLEWARE_STOP_REQUESTED,

    /**
     * 所有工具调用均被用户拒绝，且某个钩子请求停止智能体。
     *
     * <p>当最近一次推理步骤的全部工具调用均在 HITL 权限确认中被拒绝，且 {@code AllToolsDeniedEvent} 钩子处理器调用 {@code stopAgent()} 时触发。
     */
    ALL_TOOLS_DENIED,

    /**
     * 智能体被中断。
     */
    INTERRUPTED,

    /**
     * 已达到最大迭代次数。
     */
    MAX_ITERATIONS,

    /**
     * 工具结果直接返回给调用方，不再发起后续模型调用。
     */
    TOOL_RETURN_DIRECT
}
