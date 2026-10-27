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
import java.util.Map;
import reactor.core.publisher.Mono;

/**
 * 可由模型调用的智能体工具接口。
 *
 * <p>工具是 AI 智能体为执行操作或检索信息而调用的函数，连接智能体推理与外部世界。
 *
 * <p><b>实现准则：</b>
 * <ul>
 * <li>工具名称应清晰且具有描述性</li>
 * <li>描述应说明工具的作用和适用时机</li>
 * <li>参数模式必须遵循 JSON Schema 格式</li>
 * <li>所有操作应使用 Reactor Mono 异步实现</li>
 * </ul>
 *
 * @see Tool
 * @see ToolParam
 * @see Toolkit
 */
public interface AgentTool {

    /**
     * 获取工具名称。
     *
     * <p>名称应在工具集内唯一，并遵循 snake_case 约定，以兼容不同大语言模型提供商。
     *
     * @return 工具名称（永不为 null）
     */
    String getName();

    /**
     * 获取工具描述。
     *
     * <p>描述应清楚说明工具的作用、使用时机和返回结果的类型，帮助大语言模型决定何时调用。
     *
     * @return 工具描述（永不为 null）
     */
    String getDescription();

    /**
     * 获取 JSON Schema 格式的工具参数模式。
     *
     * <p>模式定义工具接受的输入参数结构，应包括：
     * <ul>
     * <li>type: "object"</li>
     * <li>properties：参数名称到其模式的映射</li>
     * <li>required：必填参数名称列表</li>
     * </ul>
     *
     * @return 表示工具参数 JSON Schema 的映射（永不为 null）
     */
    Map<String, Object> getParameters();

    /**
     * 获取工具模式的严格模式配置。
     *
     * <p>启用严格模式时，兼容的模型提供商应更严格地遵循工具参数模式。返回 {@code null} 表示未显式指定严格模式偏好。
     *
     * @return 严格模式值（{@code true}/{@code false}）；未指定时为 {@code null}
     */
    default Boolean getStrict() {
        return null;
    }

    /**
     * 获取工具定义延迟加载配置。
     *
     * <p>支持工具搜索的提供商（例如 OpenAI Responses 的 {@code tool_search} 服务端工具）可延迟加载工具参数模式，直到模型搜索到该工具。返回 {@code null} 表示未显式指定偏好，此时工具仍会立即加载。
     *
     * @return 延迟加载值（{@code true}/{@code false}）；未指定时为 {@code null}
     */
    default Boolean getDeferLoading() {
        return null;
    }

    /**
     * 获取 JSON Schema 格式的可选工具输出模式。
     *
     * <p>多数工具不会向模型公开结构化输出模式，因此默认实现返回 {@code null}。MCP 工具可覆写此方法以公开服务器提供的 {@code outputSchema} 定义。
     *
     * @return 表示工具输出 JSON Schema 的映射；不支持时为 null
     */
    default Map<String, Object> getOutputSchema() {
        return null;
    }

    /**
     * 获取面向最终用户显示的可读工具名称。
     *
     * <p>{@link #getName()} 是面向模型的程序标识符，{@link #getDescription()} 是面向模型的使用指导，二者都不适合作为 UI 标签。MCP 服务器可通过协议中的 {@code Tool.title}（回退到 {@code ToolAnnotations.title}）提供显示名称，{@link io.agentscope.core.tool.mcp.McpTool} 在此公开该名称。
     *
     * <p>本地 {@code @Tool} 方法没有显示名称，因此默认实现返回 {@code null}。此值为 {@code null} 时，调用方必须回退到 {@link #getName()}。
     *
     * @return 显示标题；工具未提供时为 {@code null}
     */
    default String getTitle() {
        return null;
    }

    /**
     * 返回工具是否只执行读取操作而从不修改状态。
     *
     * <p>在计划模式等受限执行模式中，只读工具会自动获准，而写操作需要明确批准。
     *
     * @return 只读工具返回 {@code true}；默认为 {@code false}
     */
    default boolean isReadOnly() {
        return false;
    }

    /**
     * 工具结果是否应直接返回给调用方并跳过下一轮推理，默认为 {@code false}。
     *
     * <p>此标记适用于所有成功结果：框架执行的工具，以及 {@code TOOL_SUSPENDED} 后恢复时由调用方提供的结果（例如 {@code externalTool = true} 的工具）。若一个批次跨多次恢复才完成，或混合外部提供结果与框架执行结果，则始终反馈给模型。
     *
     * <p>成功结果会成为本轮最终答复，因此必须产生可展示的内容块，不能返回空输出列表。
     */
    default boolean isReturnDirect() {
        return false;
    }

    /**
     * 使用给定参数异步执行工具。
     *
     * <p>接收包含完整执行上下文的 {@link ToolCallParam}，包括：
     * <ul>
     * <li>toolUseBlock：包含用于追踪的工具调用 ID 和名称</li>
     * <li>input：工具输入参数</li>
     * <li>agent：调用方智能体（可为 null），用于访问智能体上下文</li>
     * </ul>
     *
     * @param param 工具调用参数
     * @return 包含 ToolResultBlock 的 Mono
     */
    Mono<ToolResultBlock> callAsync(ToolCallParam param);
}
