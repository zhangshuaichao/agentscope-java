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
 * 定义智能体对话中消息参与者的角色。
 *
 * <p>此枚举指定对话流中每条消息的来源或用途，以支持正确的消息路由、处理和上下文管理。
 *
 * <p>消息角色：
 * <ul>
 * <li>{@link #USER}：来自人类用户或外部输入的消息</li>
 * <li>{@link #ASSISTANT}：由 AI 智能体或助手生成的消息</li>
 * <li>{@link #SYSTEM}：系统指令、提示或配置消息</li>
 * <li>{@link #TOOL}：包含工具执行结果或响应的消息</li>
 * </ul>
 *
 * <p>与不同大语言模型 API 和工具系统交互时，角色决定消息应如何处理和格式化。
 */
public enum MsgRole {
    /**
     * 用户角色，用于人类用户或外部输入的消息。
     *
     * <p>通常包含智能体需要处理或回应的用户问题、命令或输入数据。
     */
    USER,

    /**
     * 助手角色，用于 AI 智能体或助手生成的消息。
     *
     * <p>包含智能体针对用户消息或系统提示产生的响应、推理或行动。
     */
    ASSISTANT,

    /**
     * 系统角色，用于系统指令、提示或配置消息。
     *
     * <p>通常包含指导智能体行为的初始化指令、上下文信息或系统级指令。
     */
    SYSTEM,

    /**
     * 工具角色，用于包含工具执行结果或响应的消息。
     *
     * <p>包含工具执行的输出，如外部工具的返回值、错误消息或状态信息。
     */
    TOOL
}
