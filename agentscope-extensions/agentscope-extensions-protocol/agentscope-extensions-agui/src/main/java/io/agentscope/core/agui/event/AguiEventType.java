/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.agui.event;

/**
 * 所有 AG-UI 协议事件类型的枚举。
 */
public enum AguiEventType {
    /**
     * 表示智能体运行开始。
     */
    RUN_STARTED,

    /**
     * 表示智能体运行完成。
     */
    RUN_FINISHED,

    /**
     * 表示文本消息开始。
     */
    TEXT_MESSAGE_START,

    /**
     * 包含消息文本内容。
     */
    TEXT_MESSAGE_CONTENT,

    /**
     * 表示文本消息结束。
     */
    TEXT_MESSAGE_END,

    /**
     * 表示工具调用开始。
     */
    TOOL_CALL_START,

    /**
     * 包含工具调用参数。
     */
    TOOL_CALL_ARGS,

    /**
     * 表示工具调用参数阶段结束。
     */
    TOOL_CALL_END,

    /**
     * 包含工具调用结果。
     */
    TOOL_CALL_RESULT,

    /**
     * 包含当前状态快照。
     */
    STATE_SNAPSHOT,

    /**
     * 包含状态增量更新。
     */
    STATE_DELTA,

    /**
     * 带自定义数据的原始事件。
     */
    RAW,

    /**
     * 带结构化数据的自定义事件。
     */
    CUSTOM,

    RUN_ERROR,

    STEP_STARTED,
    STEP_FINISHED,

    TEXT_MESSAGE_CHUNK,
    TOOL_CALL_CHUNK,

    MESSAGES_SNAPSHOT,
    ACTIVITY_SNAPSHOT,
    ACTIVITY_DELTA,

    /**
     * 表示推理/思考阶段开始。
     */
    REASONING_START,

    /**
     * 表示推理消息开始。
     */
    REASONING_MESSAGE_START,

    /**
     * 包含流式推理消息的内容块。
     */
    REASONING_MESSAGE_CONTENT,

    /**
     * 表示推理消息结束。
     */
    REASONING_MESSAGE_END,

    /**
     * 自动开启/关闭推理消息的便捷事件。
     */
    REASONING_MESSAGE_CHUNK,

    /**
     * 表示推理/思考阶段结束。
     */
    REASONING_END,

    REASONING_ENCRYPTED_VALUE
}
