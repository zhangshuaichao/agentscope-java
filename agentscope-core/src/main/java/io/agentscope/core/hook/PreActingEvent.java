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
import java.util.Objects;

/**
 * 工具执行之前触发的事件。
 *
 * <p><b>可修改：</b>是，通过 {@link #setToolUse(ToolUseBlock)}
 *
 * <p><b>上下文：</b>
 * <ul>
 *   <li>{@link #getAgent()}：智能体实例</li>
 *   <li>{@link #getMemory()}：智能体记忆</li>
 *   <li>{@link #getToolkit()}：工具集实例</li>
 *   <li>{@link #getToolUse()}：待执行的工具调用（可修改）</li>
 * </ul>
 *
 * <p><b>注意：</b>每个工具调用一次。如果推理结果包含
 * 多个工具调用，此事件会触发多次。
 *
 * <p><b>用途：</b>
 * <ul>
 *   <li>验证或修改每个工具调用的参数</li>
 *   <li>向单个工具调用添加认证或上下文</li>
 *   <li>实现逐工具授权检查</li>
 *   <li>记录或监控单个工具调用</li>
 * </ul>
 *
 * @deprecated 自 2.0.0 起弃用。请改用 {@link io.agentscope.core.middleware.MiddlewareBase}。
 */
@Deprecated(forRemoval = true, since = "2.0.0")
public final class PreActingEvent extends ActingEvent {

    /**
     * PreActingEvent 的构造器。
     *
     * @param agent 智能体实例（不可为 null）
     * @param toolkit 工具集实例（不可为 null）
     * @param toolUse 待执行的工具调用（不可为 null）
     * @throws NullPointerException 当 agent、toolkit 或 toolUse 为 null 时抛出
     */
    public PreActingEvent(Agent agent, Toolkit toolkit, ToolUseBlock toolUse) {
        super(HookEventType.PRE_ACTING, agent, toolkit, toolUse);
    }

    /**
     * 修改工具调用（例如改变参数）。
     *
     * @param toolUse 新的工具调用块（不可为 null）
     * @throws NullPointerException 当 toolUse 为 null 时抛出
     */
    public void setToolUse(ToolUseBlock toolUse) {
        this.toolUse = Objects.requireNonNull(toolUse, "toolUse cannot be null");
    }
}
