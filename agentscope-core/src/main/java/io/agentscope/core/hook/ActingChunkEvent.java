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
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.Toolkit;
import java.util.Objects;

/**
 * 工具执行流式输出期间触发的事件。
 *
 * <p><b>可修改：</b>否（仅通知）
 *
 * <p><b>上下文：</b>
 * <ul>
 *   <li>{@link #getAgent()}：智能体实例</li>
 *   <li>{@link #getMemory()}：智能体记忆</li>
 *   <li>{@link #getToolkit()}：工具集实例</li>
 *   <li>{@link #getToolUse()}：正在执行的工具</li>
 *   <li>{@link #getChunk()}：来自 ToolEmitter 的流式片段</li>
 * </ul>
 *
 * <p><b>注意：</b>这些片段由工具通过 {@link
 * io.agentscope.core.tool.ToolEmitter} 发出。它们不会发送给 LLM，只有最终返回值
 * 才会发送。
 *
 * <p><b>用途：</b>
 * <ul>
 *   <li>显示工具执行进度</li>
 *   <li>记录工具中间输出</li>
 *   <li>监控长时间运行的工具操作</li>
 * </ul>
 *
 * @deprecated 自 2.0.0 起弃用。请改用 {@link io.agentscope.core.middleware.MiddlewareBase}。
 */
@Deprecated(forRemoval = true, since = "2.0.0")
public final class ActingChunkEvent extends ActingEvent {

    private final ToolResultBlock chunk;

    /**
     * ActingChunkEvent 的构造器。
     *
     * @param agent 智能体实例（不可为 null）
     * @param toolkit 工具集实例（不可为 null）
     * @param toolUse 正在执行的工具（不可为 null）
     * @param chunk 来自 ToolEmitter 的流式片段（不可为 null）
     * @throws NullPointerException 当 agent、toolkit、toolUse 或 chunk 为 null 时抛出
     */
    public ActingChunkEvent(
            Agent agent, Toolkit toolkit, ToolUseBlock toolUse, ToolResultBlock chunk) {
        super(HookEventType.ACTING_CHUNK, agent, toolkit, toolUse);
        this.chunk = Objects.requireNonNull(chunk, "chunk cannot be null");
    }

    /**
     * 获取来自 ToolEmitter 的流式片段。
     *
     * @return 片段
     */
    public ToolResultBlock getChunk() {
        return chunk;
    }
}
