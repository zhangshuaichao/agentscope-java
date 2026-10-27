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
package io.agentscope.extensions.model.gemini.tool;

import com.google.genai.types.Tool;

/**
 * Gemini 专属服务端工具定义。
 *
 * <p>服务端工具由 Gemini 执行，而非本地 AgentScope 工具集。先构建强类型的
 * Google GenAI SDK 工具定义，再使用 {@link #of(Tool)} 包装。
 * 参数名称、类型与验证由 SDK 构建器负责。
 *
 * <p>示例：
 *
 * <pre>{@code
 * GeminiServerTool.of(Tool.builder()
 *         .googleSearch(GoogleSearch.builder()
 *                 .excludeDomains(List.of("example.com"))
 *                 .build())
 *         .build());
 * }</pre>
 */
public final class GeminiServerTool {

    private final Tool tool;

    private GeminiServerTool(Tool tool) {
        this.tool = tool;
    }

    /**
     * 包装由 SDK 构建的 Gemini 服务端工具定义。
     *
     * <p>不接受自定义客户端工具；它们应使用普通工具集的工具 schema。
     *
     * @param tool SDK 工具定义
     * @return 已验证的服务端工具
     * @throws IllegalArgumentException tool 为 null 或包含本地函数工具时抛出
     */
    public static GeminiServerTool of(Tool tool) {
        if (tool == null) {
            throw new IllegalArgumentException("Gemini server tool must not be null");
        }
        if (tool.functionDeclarations().isPresent() || tool.functions().isPresent()) {
            throw new IllegalArgumentException(
                    "GeminiServerTool does not accept local function tools");
        }
        return new GeminiServerTool(tool);
    }

    /**
     * 返回缓存的 SDK 工具定义。
     *
     * @return SDK 工具
     */
    public Tool toTool() {
        return tool;
    }
}
