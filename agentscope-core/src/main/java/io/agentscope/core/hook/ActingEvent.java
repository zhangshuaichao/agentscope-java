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
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.Toolkit;

/**
 * 工具执行（行动）相关事件的基类。
 *
 * <p>此密封类为所有行动事件提供公共上下文：
 * <ul>
 *   <li>{@link #getToolkit()}：工具集实例</li>
 *   <li>{@link #getToolUse()}：正在执行的工具</li>
 * </ul>
 *
 * <p>子类表示工具执行的不同阶段：
 * <ul>
 *   <li>{@link PreActingEvent}：工具执行前</li>
 *   <li>{@link PostActingEvent}：工具执行后</li>
 *   <li>{@link ActingChunkEvent}：工具流式输出期间</li>
 * </ul>
 *
 * @see PreActingEvent
 * @see PostActingEvent
 * @see ActingChunkEvent
 * @deprecated 自 2.0.0 起弃用。请改用 {@link io.agentscope.core.middleware.MiddlewareBase}。
 */
@Deprecated(forRemoval = true, since = "2.0.0")
public abstract sealed class ActingEvent extends HookEvent
        permits PreActingEvent, PostActingEvent, ActingChunkEvent {

    private final Toolkit toolkit;
    protected ToolUseBlock toolUse;

    /**
     * ActingEvent 的构造器。
     *
     * @param type 事件类型（不可为 null）
     * @param agent 智能体实例（不可为 null）
     * @param toolkit 工具集实例
     * @param toolUse 正在执行的工具（空事件时可为 null）
     */
    protected ActingEvent(HookEventType type, Agent agent, Toolkit toolkit, ToolUseBlock toolUse) {
        super(type, agent);
        this.toolkit = toolkit;
        this.toolUse = toolUse;
    }

    /**
     * 获取工具集实例。
     *
     * @return 工具集
     */
    public final Toolkit getToolkit() {
        return toolkit;
    }

    /**
     * 获取正在执行的工具。
     *
     * @return 工具调用块
     */
    public final ToolUseBlock getToolUse() {
        return toolUse;
    }
}
