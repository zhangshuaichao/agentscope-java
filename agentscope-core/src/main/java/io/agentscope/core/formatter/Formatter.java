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
package io.agentscope.core.formatter;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import java.time.Instant;
import java.util.List;

/**
 * 在 AgentScope 格式与服务商专用格式之间转换的格式化器接口。
 * 这是供 Model 实现内部使用的接口。
 *
 * <p>格式化器负责：
 * 1. 将 Msg 对象转换为服务商专用请求格式
 * 2. 将服务商响应转换回 AgentScope ChatResponse
 * 3. 将生成选项应用到服务商请求构建器
 * 4. 将工具模式应用到服务商请求构建器
 *
 * <p>每个格式化器都具有类型安全性，处理服务商 SDK 所要求的精确类型。
 *
 * <p><b>依赖：</b>此接口需要对应服务商的扩展模块。
 * 对于 DashScope，添加以下 Maven 依赖：
 * <pre>{@code
 * <dependency>
 *     <groupId>agentscope</groupId>
 *     <artifactId>agentscope-extensions-model-dashscope</artifactId>
 *     <version>${agentscope.version}</version>
 * </dependency>
 * }</pre>
 *
 * @param <TReq> 服务商专用请求消息类型（例如 DashScope 的 com.alibaba.dashscope.common.Message，
 *                或 OpenAI 的 ChatCompletionMessageParam）
 * @param <TResp> 服务商专用响应类型（例如 DashScope 的 GenerationResult，
 *                 或 OpenAI 的 ChatCompletion/ChatCompletionChunk）
 * @param <TParams> 服务商专用请求参数构建器类型（例如 DashScope 的 GenerationParam，
 *                   或 OpenAI 的 ChatCompletionCreateParams.Builder）
 */
public interface Formatter<TReq, TResp, TParams> {

    /**
     * 将 AgentScope 消息转换为服务商专用请求格式。
     *
     * @param msgs AgentScope 消息列表
     * @return 服务商专用请求消息列表
     */
    List<TReq> format(List<Msg> msgs);

    /**
     * 使用本次请求的生成选项格式化 AgentScope 消息。
     *
     * <p>如果服务商根据消息元数据与生成选项共同生成请求字段，应重写此方法。
     * 默认实现保留旧行为，在消息转换过程中忽略生成选项。
     *
     * @param msgs AgentScope 消息列表
     * @param options 请求级生成选项，可以为 {@code null}
     * @return 服务商专用请求消息列表
     */
    default List<TReq> format(List<Msg> msgs, GenerateOptions options) {
        return format(msgs);
    }

    /**
     * 将服务商专用响应解析为 AgentScope ChatResponse。
     *
     * @param response 服务商专用响应对象
     * @param startTime 请求开始时间，用于计算耗时
     * @return AgentScope ChatResponse
     */
    ChatResponse parseResponse(TResp response, Instant startTime);

    /**
     * 将生成选项应用到服务商专用请求参数。
     *
     * @param paramsBuilder 服务商专用请求参数构建器
     * @param options 要应用的生成选项
     * @param defaultOptions options 参数为 null 时使用的默认选项
     */
    void applyOptions(
            TParams paramsBuilder, GenerateOptions options, GenerateOptions defaultOptions);

    /**
     * 将工具模式应用到服务商专用请求参数。
     *
     * @param paramsBuilder 服务商专用请求参数构建器
     * @param tools 要应用的工具模式列表（可以为 null 或空列表）
     */
    void applyTools(TParams paramsBuilder, List<ToolSchema> tools);

    /**
     * 将工具模式应用到服务商专用请求参数，并处理服务商兼容性。
     *
     * <p>此方法允许格式化器通过 baseUrl/modelName 识别服务商，并调整工具定义以兼容其实现
     * （例如移除不支持的 strict 参数）。
     *
     * <p>默认实现委派给 {@code applyTools(TParams, List)}。
     * 支持服务商专用工具处理的格式化器应重写此方法。
     *
     * @param paramsBuilder 服务商专用请求参数构建器
     * @param tools 要应用的工具模式（可以为 null 或空列表）
     * @param baseUrl 用于服务商识别的 API 基础 URL（null 表示默认）
     * @param modelName 识别服务商时备用的模型名称（null）
     */
    default void applyTools(
            TParams paramsBuilder, List<ToolSchema> tools, String baseUrl, String modelName) {
        // 默认实现：委派给较简单的方法
        applyTools(paramsBuilder, tools);
    }

    /**
     * 将工具选择配置应用到服务商专用请求参数。
     *
     * <p>此方法控制模型如何使用工具。默认实现不执行任何操作，
     * 支持工具选择配置的服务商可由格式化器重写此方法。
     *
     * @param paramsBuilder 服务商专用请求参数构建器
     * @param toolChoice 工具选择配置（null 表示使用服务商默认行为）
     */
    default void applyToolChoice(TParams paramsBuilder, ToolChoice toolChoice) {
        // 默认实现：不执行任何操作
        // 子类可以重写以提供服务商特有行为
    }

    /**
     * 识别服务商，并将工具选择配置应用到对应请求参数。
     *
     * <p>格式化器可根据 baseUrl/modelName 识别服务商，调整 tool_choice 格式，
     * 或在服务商不支持某些选项时平滑降级。
     *
     * <p>默认实现委派给 {@code applyToolChoice(TParams, ToolChoice)}。
     * 支持服务商专用 tool_choice 处理的格式化器应重写此方法。
     *
     * @param paramsBuilder 服务商专用请求参数构建器
     * @param toolChoice 工具选择配置（null 表示使用服务商默认行为）
     * @param baseUrl 用于服务商识别的 API 基础 URL（null 表示默认）
     * @param modelName 识别服务商时备用的模型名称（null）
     */
    default void applyToolChoice(
            TParams paramsBuilder, ToolChoice toolChoice, String baseUrl, String modelName) {
        // 默认实现：委派给较简单的方法
        applyToolChoice(paramsBuilder, toolChoice);
    }
}
