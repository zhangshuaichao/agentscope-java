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
import java.util.Objects;

/**
 * 智能体完成处理后触发的事件。
 *
 * <p><b>可修改：</b>是，通过 {@link #setFinalMessage(Msg)}
 *
 * <p><b>上下文：</b>
 * <ul>
 *   <li>{@link #getAgent()}：智能体实例</li>
 *   <li>{@link #getMemory()}：智能体记忆（包含最终消息）</li>
 *   <li>{@link #getFinalMessage()}：最终响应消息（可修改）</li>
 * </ul>
 *
 * <p><b>用途：</b>
 * <ul>
 *   <li>对智能体最终响应进行后处理</li>
 *   <li>添加最终元数据或格式</li>
 *   <li>过滤或清理输出</li>
 *   <li>记录发出的响应</li>
 * </ul>
 *
 * @deprecated 自 2.0.0 起弃用。请改用 {@link io.agentscope.core.middleware.MiddlewareBase}。
 */
@Deprecated(forRemoval = true, since = "2.0.0")
public final class PostCallEvent extends HookEvent {

    private Msg finalMessage;

    /**
     * PostCallEvent 的构造器。
     *
     * @param agent 智能体实例（不可为 null）
     * @param finalMessage 最终响应消息（不可为 null）
     * @throws NullPointerException 当 agent 或 finalMessage 为 null 时抛出
     */
    public PostCallEvent(Agent agent, Msg finalMessage) {
        super(HookEventType.POST_CALL, agent);
        this.finalMessage = Objects.requireNonNull(finalMessage, "finalMessage cannot be null");
    }

    /**
     * 获取智能体返回的最终响应消息。
     *
     * @return 最终消息
     */
    public Msg getFinalMessage() {
        return finalMessage;
    }

    /**
     * 修改最终响应消息。
     *
     * @param finalMessage 新的最终消息（不可为 null）
     * @throws NullPointerException 当 finalMessage 为 null 时抛出
     */
    public void setFinalMessage(Msg finalMessage) {
        this.finalMessage = Objects.requireNonNull(finalMessage, "finalMessage cannot be null");
    }
}
