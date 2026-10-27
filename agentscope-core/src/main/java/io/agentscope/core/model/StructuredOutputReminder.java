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
package io.agentscope.core.model;

/**
 * 配置在结构化输出模式下，如何确保模型调用 generate_response 工具。
 *
 * <p>此枚举控制生成结构化输出时，促使模型调用临时 generate_response 工具的机制。
 */
public enum StructuredOutputReminder {
    /**
     * 当模型未主动调用工具时，使用 tool_choice API 参数强制调用。
     * 这是默认且推荐的选项。
     *
     * <p>使用此模式时：
     * <ol>
     *   <li>第一轮：模型可以自由调用任意工具（包括业务工具）
     *   <li>如果模型未调用 generate_response：下一轮通过 tool_choice 参数强制调用
     * </ol>
     *
     * <p>这种方式允许智能体先完成多步骤任务，再生成最终结构化输出。
     */
    TOOL_CHOICE,

    /**
     * 当模型未调用工具时注入提醒提示词。这是可能需要多次 API 调用和重试的旧方案。
     *
     * <p>使用此模式时，如果模型未调用 generate_response 工具，则向对话注入提醒消息，
     * 智能体随后重试推理步骤。这可能需要多轮迭代才能成功生成结构化输出。
     */
    PROMPT
}
