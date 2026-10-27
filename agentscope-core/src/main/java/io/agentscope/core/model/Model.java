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

import io.agentscope.core.message.Msg;
import java.util.List;
import reactor.core.publisher.Flux;

// 【学习提示】这里是模型供应商的适配边界：输入是框架消息与工具模式，输出是响应流。
// ReAct 循环、权限判定与状态恢复由上层组织，不应由某个模型适配器各自实现。
public interface Model {

    /**
     * 以流式方式生成聊天补全响应。
     * 模型在内部使用配置的格式化器完成消息格式转换。
     *
     * @param messages 要发送给模型的 AgentScope 消息
     * @param tools 可选的工具模式列表（无工具时为 null 或空列表）
     * @param options 可选的生成配置（为 null 时使用默认值）
     * @return 由聊天响应组成的 Flux 流
     */
    Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options);

    /**
     * 获取用于日志记录和身份识别的模型名称。
     *
     * @return 模型名称
     */
    String getModelName();

    /**
     * 此模型是否支持原生结构化输出（{@code response_format} 配合
     * {@code json_schema}）。为 {@code true} 时，智能体可通过
     * {@link GenerateOptions#getResponseFormat()} 将输出模式直接传给模型，
     * 而不必注入合成的 {@code generate_response} 工具。
     *
     * @return 模型支持原生结构化输出时返回 {@code true}
     * @see #supportsNativeStructuredOutputWithTools()
     */
    default boolean supportsNativeStructuredOutput() {
        return false;
    }

    /**
     * 当请求同时包含工具时，此模型能否正确处理原生结构化输出
     * （{@code response_format} 配合 {@code json_schema}）。
     *
     * <p>部分兼容 OpenAI 的服务商会优先满足 {@code response_format} 约束，
     * 导致模型直接输出结构化 JSON，完全跳过工具调用。如果此方法返回
     * {@code false} 且请求包含工具，智能体会回退到合成的
     * {@code generate_response} 工具方案，该方案与 ReAct 工具调用循环完全兼容。
     *
     * <p>默认使用 {@link #supportsNativeStructuredOutput()} 的返回值。
     *
     * @return 模型能正确处理工具与 {@code response_format} 并存的情况时返回 {@code true}
     */
    // 【学习提示】“支持结构化输出”与“结构化输出能和工具同时使用”是两个能力。
    // 默认沿用前者；存在兼容性差异的实现需重写此方法，供上层选择回退路径。
    default boolean supportsNativeStructuredOutputWithTools() {
        return supportsNativeStructuredOutput();
    }

    /**
     * 此模型是否支持通过 {@code tool_choice} 强制调用指定工具
     * （即 {@link ToolChoice.Specific}）。
     *
     * <p>智能体的结构化输出回退流程使用此能力标记：当模型没有主动调用
     * {@code generate_response} 工具时，对支持 {@code ToolChoice.Specific} 的
     * 服务商施加硬性的 {@code tool_choice} 约束；对于不支持的服务商，
     * 则回退为注入提示消息，提醒模型调用该工具。
     *
     * <p>默认为 {@code false}：未知模型和自定义模型采用提示提醒策略，
     * 避免发送它们可能不支持的服务商专用约束。支持指定工具选择的模型
     * 必须重写此方法并返回 {@code true}。
     *
     * @return 支持 {@code ToolChoice.Specific} 时返回 {@code true}
     */
    default boolean supportsToolChoiceSpecific() {
        return false;
    }

    /**
     * 返回模型的上下文窗口大小（单位为词元），未知时返回 {@code 0}。
     *
     * <p>Harness 层的上下文压缩中间件使用此值，动态判断何时触发对话摘要。
     * 实现应返回所配置模型的完整上下文窗口大小（输入与输出之和）。
     *
     * @return 以词元为单位的上下文窗口大小；无法获取时为 {@code 0}
     */
    default int getContextWindowSize() {
        return 0;
    }
}
