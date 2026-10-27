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

import io.agentscope.core.agent.Agent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.Toolkit;

/**
 * 工具执行完成后触发的事件。
 *
 * <p><b>可修改：</b>是，通过 {@link #setToolResult(ToolResultBlock)}
 *
 * <p><b>上下文：</b>
 * <ul>
 *   <li>{@link #getAgent()}：智能体实例</li>
 *   <li>{@link #getMemory()}：智能体记忆</li>
 *   <li>{@link #getToolkit()}：工具集实例</li>
 *   <li>{@link #getToolUse()}：原始工具调用</li>
 *   <li>{@link #getToolResult()}：工具执行结果（可修改）</li>
 * </ul>
 *
 * <p><b>注意：</b>每次工具执行调用一次。
 *
 * <p><b>用途：</b>
 * <ul>
 *   <li>对单个工具结果进行后处理</li>
 *   <li>过滤或清理工具输出</li>
 *   <li>为结果添加元数据</li>
 *   <li>处理工具执行错误</li>
 *   <li>转换结果格式</li>
 *   <li>通过 {@link #stopAgent()} 请求停止智能体，以便人工审核</li>
 * </ul>
 *
 * @deprecated 自 2.0.0 起弃用。请改用 {@link io.agentscope.core.middleware.MiddlewareBase}。
 */
@Deprecated(forRemoval = true, since = "2.0.0")
public final class PostActingEvent extends ActingEvent {

    private ToolResultBlock toolResult;
    private Msg toolResultMsg;
    private boolean stopRequested = false;

    /**
     * PostActingEvent 的构造器。
     *
     * @param agent 智能体实例（不可为 null）
     * @param toolkit 工具集实例（不可为 null）
     * @param toolUse 原始工具调用（空事件时可为 null）
     * @param toolResult 工具执行结果（空事件时可为 null）
     */
    public PostActingEvent(
            Agent agent, Toolkit toolkit, ToolUseBlock toolUse, ToolResultBlock toolResult) {
        super(HookEventType.POST_ACTING, agent, toolkit, toolUse);
        this.toolResult = toolResult;
    }

    /**
     * 获取工具执行结果。
     *
     * @return 工具结果块
     */
    public ToolResultBlock getToolResult() {
        return toolResult;
    }

    /**
     * 修改工具执行结果。
     *
     * @param toolResult 新的工具结果
     */
    public void setToolResult(ToolResultBlock toolResult) {
        this.toolResult = toolResult;
    }

    /**
     * 请求智能体在本次行动阶段后停止。
     *
     * <p>调用后，智能体会返回当前包含 ToolResultBlock 的消息，
     * 而不继续下一轮推理。用户随后可审核
     * 工具执行结果，并通过无参调用 {@code agent.call()} 恢复执行。
     *
     * <p>这支持人在回路场景：智能体继续推理之前，
     * 先由用户审核工具执行结果。
     */
    public void stopAgent() {
        this.stopRequested = true;
    }

    /**
     * 检查是否已请求停止。
     *
     * @return 调用过 {@link #stopAgent()} 时为 true，否则为 false
     */
    public boolean isStopRequested() {
        return stopRequested;
    }

    /**
     * 获取工具结果消息。
     *
     * @return 工具结果消息
     */
    public Msg getToolResultMsg() {
        return toolResultMsg;
    }

    /**
     * 设置工具结果消息。
     *
     * @param toolResultMsg 工具结果消息
     */
    public void setToolResultMsg(Msg toolResultMsg) {
        this.toolResultMsg = toolResultMsg;
    }
}
