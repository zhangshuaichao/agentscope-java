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
package io.agentscope.core.agent;

/**
 * 智能体执行期间发出的事件类型。
 *
 * <p>每种事件类型表示智能体推理—行动循环中的一个阶段，以清晰的职责划分支持行为监测。
 *
 * @deprecated 自 2.0.0 起弃用。这是 v1 的粗粒度事件判别类型。新代码应使用
 * {@code ReActAgent#streamEvents(...)} 暴露的 {@code io.agentscope.core.event.AgentEventType}
 * 及类型化的 {@code AgentEvent} 子类。
 */
@Deprecated(since = "2.0.0")
public enum EventType {
    /**
     * 推理事件：智能体进行思考和规划。
     *
     * <p>特点：
     * <ul>
     * <li>消息角色：{@link io.agentscope.core.message.MsgRole#ASSISTANT}</li>
     * <li>内容：TextBlock、ThinkingBlock 和／或工具调用请求</li>
     * <li>流式处理：支持（多条事件使用相同的消息 ID）</li>
     * </ul>
     */
    REASONING,

    /**
     * 工具执行结果事件。
     *
     * <p>特点：
     * <ul>
     * <li>消息角色：{@link io.agentscope.core.message.MsgRole#TOOL}</li>
     * <li>内容：携带执行输出的 ToolResultBlock</li>
     * <li>流式处理：长时间运行的工具可支持</li>
     * </ul>
     */
    TOOL_RESULT,

    /**
     * 提示事件：来自 RAG、记忆或规划系统的信息。
     *
     * <p>特点：
     * <ul>
     * <li>消息角色：{@link io.agentscope.core.message.MsgRole#USER} 或 SYSTEM</li>
     * <li>内容：携带检索信息或上下文信息的 TextBlock</li>
     * <li>流式处理：不适用（仅完整消息）</li>
     * </ul>
     */
    HINT,

    /**
     * 最终结果事件：智能体的完整回复。
     *
     * <p>这是 {@link Agent#call(io.agentscope.core.message.Msg)} 返回的消息。
     * 默认不将此事件包含在流中，因为它已作为返回值提供，避免重复。
     *
     * <p>特点：
     * <ul>
     * <li>消息角色：{@link io.agentscope.core.message.MsgRole#ASSISTANT}</li>
     * <li>内容：最终回复文本</li>
     * <li>流式处理：不适用</li>
     * </ul>
     */
    AGENT_RESULT,

    /**
     * 摘要事件：达到最大迭代次数时生成。
     *
     * <p>特点：
     * <ul>
     * <li>消息角色：{@link io.agentscope.core.message.MsgRole#ASSISTANT}</li>
     * <li>内容：对已完成工作的总结</li>
     * <li>流式处理：可能支持</li>
     * </ul>
     */
    SUMMARY,

    /**
     * 表示流式发送所有事件类型（{@link #AGENT_RESULT} 除外）的特殊值。
     *
     * <p>在 {@link StreamOptions} 中使用此值，可以不经过滤地接收所有事件。
     */
    ALL
}
