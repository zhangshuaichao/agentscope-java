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

/**
 * 在工具执行期间发送流式响应的接口。
 *
 * <p>工具方法可声明 ToolEmitter 参数，在执行中发送中间进度和消息。数据块通过 {@code onActingChunk()} 事件传给已注册钩子，并以 {@link io.agentscope.core.event.ToolProgressEvent} 传给事件流订阅者，但不发送给大语言模型。只有方法最终返回值作为工具结果发送给模型，由 {@link io.agentscope.core.event.ToolResultTextDeltaEvent} 和 {@link io.agentscope.core.event.ToolResultDataDeltaEvent} 携带。
 *
 * <p><b>关键特性：</b>
 * <ul>
 * <li>框架自动注入 ToolEmitter，无需 {@link ToolParam} 注解</li>
 * <li>发出的数据块传给钩子用于监控或日志，不传给模型</li>
 * <li>适合长时间运行工具提供进度反馈</li>
 * <li>不影响模型可见的工具模式</li>
 * </ul>
 *
 * <p><b>使用示例：</b>
 *
 * <pre>{@code
 * @Tool(name = "long_task", description = "Execute a long running task")
 * public ToolResultBlock execute(
 *     @ToolParam(name = "input") String input,
 *     ToolEmitter emitter  // Automatically injected by framework
 * ) {
 *     emitter.emit(ToolResultBlock.text("Starting task..."));
 *
 *     // Step 1
 *     processStep1(input);
 *     emitter.emit(ToolResultBlock.text("Step 1 completed"));
 *
 *     // Step 2
 *     processStep2(input);
 *     emitter.emit(ToolResultBlock.text("Step 2 completed"));
 *
 *     // Final result (this is what the LLM sees)
 *     return ToolResultBlock.text("Task completed successfully");
 * }
 * }</pre>
 */
public interface ToolEmitter {

    /**
     * 在工具执行期间发送流式响应数据块。
     *
     * <p>通过 {@code onToolChunk()} 向已注册钩子发送中间消息，不影响模型接收的内容；只有工具方法返回值会发送给模型。
     *
     * @param chunk 要发送的数据块
     */
    void emit(ToolResultBlock chunk);

    /**
     * 返回此发射器关联的工具调用 ID。
     *
     * <p>默认返回 {@code null}，使没有工具调用上下文的自定义发射器保持兼容。
     *
     * @return 工具调用 ID；不可用时为 {@code null}
     */
    default String getToolCallId() {
        return null;
    }
}
