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
import io.agentscope.core.message.ToolUseBlock;
import java.util.function.BiConsumer;

/**
 * 将数据块交付给回调的 ToolEmitter 默认实现。
 *
 * <p>此类为包私有，当工具方法声明 ToolEmitter 参数时由框架内部创建。每次工具调用拥有独立的 DefaultToolEmitter 实例。
 */
class DefaultToolEmitter implements ToolEmitter {

    private final ToolUseBlock toolUseBlock;
    private final BiConsumer<ToolUseBlock, ToolResultBlock> chunkCallback;

    /**
     * 创建 DefaultToolEmitter。
     *
     * @param toolUseBlock 标识工具调用的工具使用块
     * @param chunkCallback 向钩子交付数据块的回调（可为 null）
     */
    DefaultToolEmitter(
            ToolUseBlock toolUseBlock, BiConsumer<ToolUseBlock, ToolResultBlock> chunkCallback) {
        this.toolUseBlock = toolUseBlock;
        this.chunkCallback = chunkCallback;
    }

    /**
     * 向注册的回调发送工具结果数据块。
     *
     * <p>将数据块传给回调函数，后者通常通过 {@code onActingChunk()} 事件转发给钩子。回调或数据块为 null 时静默不执行任何操作。
     *
     * @param chunk 要发送的工具结果数据块（可为 null）
     */
    @Override
    public void emit(ToolResultBlock chunk) {
        if (chunkCallback != null && chunk != null) {
            chunkCallback.accept(toolUseBlock, chunk);
        }
    }

    /**
     * 返回此发射器关联的工具调用 ID。
     *
     * @return 工具调用 ID；没有可用工具调用时为 {@code null}
     */
    @Override
    public String getToolCallId() {
        return toolUseBlock != null ? toolUseBlock.getId() : null;
    }
}
