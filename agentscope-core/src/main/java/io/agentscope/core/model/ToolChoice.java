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

package io.agentscope.core.model;

/**
 * 表示调用大语言模型时的工具选择行为。
 *
 * <p>此密封接口定义模型如何处理工具调用：
 * <ul>
 *   <li>{@link Auto} - 由模型决定是否调用工具以及调用哪个工具（默认）</li>
 *   <li>{@link None} - 禁止模型调用任何工具</li>
 *   <li>{@link Required} - 强制模型至少调用一个工具（由模型选择具体工具）</li>
 *   <li>{@link Specific} - 按名称强制模型调用指定工具</li>
 * </ul>
 *
 * <p><b>使用示例：</b>
 * <pre>{@code
 * // Force model to call a specific tool
 * GenerateOptions options = GenerateOptions.builder()
 *     .toolChoice(new ToolChoice.Specific("generate_response"))
 *     .build();
 *
 * // Let model decide (default)
 * GenerateOptions options = GenerateOptions.builder()
 *     .toolChoice(new ToolChoice.Auto())
 *     .build();
 * }</pre>
 *
 * @see GenerateOptions
 */
public sealed interface ToolChoice
        permits ToolChoice.Auto, ToolChoice.None, ToolChoice.Required, ToolChoice.Specific {

    /**
     * Auto 模式：由模型决定是否调用工具以及调用哪些工具。
     * 未指定 toolChoice 时采用此默认行为。
     */
    record Auto() implements ToolChoice {}

    /**
     * None 模式：禁止模型调用任何工具。
     * 模型仅以文本回复。
     */
    record None() implements ToolChoice {}

    /**
     * Required 模式：强制模型至少调用一个工具。
     * 模型根据上下文选择具体调用哪些工具。
     */
    record Required() implements ToolChoice {}

    /**
     * Specific 模式：按名称强制模型调用指定工具。
     *
     * @param toolName 必须调用的工具名称
     */
    record Specific(String toolName) implements ToolChoice {
        /**
         * 创建 Specific 工具选择。
         *
         * @param toolName 必须调用的工具名称（不可为 null 或空字符串）
         * @throws IllegalArgumentException 当 toolName 为 null 或空字符串时抛出
         */
        public Specific {
            if (toolName == null || toolName.trim().isEmpty()) {
                throw new IllegalArgumentException("Tool name must not be null or empty");
            }
        }
    }
}
