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

/**
 * 表示工具执行应挂起并交由外部处理的异常。
 *
 * <p>用于需要外部执行的工具（例如仅通过模式注册的工具）。抛出后框架将：
 * <ol>
 * <li>将异常转换为待处理的 {@code ToolResultBlock}</li>
 * <li>向用户返回生成原因为 {@code GenerateReason.TOOL_SUSPENDED} 的挂起消息</li>
 * <li>等待用户提供工具执行结果</li>
 * </ol>
 *
 * <p>自定义工具用法示例：
 * <pre>{@code
 * @Tool(name = "external_api", description = "Call external API")
 * public ToolResultBlock callExternalApi(@ToolParam(name = "url") String url) {
 *     // 表明此工具需要外部执行
 *     throw new ToolSuspendException("Requires external API call to: " + url);
 * }
 * }</pre>
 *
 * @see SchemaOnlyTool
 */
public class ToolSuspendException extends RuntimeException {

    private final String reason;

    /**
     * 使用默认消息创建 ToolSuspendException。
     */
    public ToolSuspendException() {
        this(null);
    }

    /**
     * 使用自定义原因创建 ToolSuspendException。
     *
     * @param reason 挂起原因，将包含在待处理的 ToolResultBlock 中
     */
    public ToolSuspendException(String reason) {
        super(reason != null ? reason : "Tool execution suspended");
        this.reason = reason;
    }

    /**
     * 获取用户定义的挂起原因。
     *
     * @return 原因；未指定时为 null
     */
    public String getReason() {
        return reason;
    }
}
