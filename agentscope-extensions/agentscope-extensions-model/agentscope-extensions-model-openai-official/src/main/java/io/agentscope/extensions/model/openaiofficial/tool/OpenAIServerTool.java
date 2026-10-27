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
package io.agentscope.extensions.model.openaiofficial.tool;

import com.openai.models.responses.Tool;
import com.openai.models.responses.ToolSearchTool;

/**
 * OpenAI Responses API 服务端工具定义。
 *
 * <p>先构造 SDK 内置工具，再用 {@link #of(Tool)} 包装。
 * 拒绝自定义函数工具以及需要外部运行时或连接器的工具；
 * 自定义函数应使用普通 AgentScope 工具集。
 */
public final class OpenAIServerTool {

    /**
     * 当前 Web 搜索工具的稳定工具类型。
     */
    public static final String WEB_SEARCH = "web_search";

    /**
     * 代码解释器的稳定工具类型。
     */
    public static final String CODE_INTERPRETER = "code_interpreter";

    /**
     * 图像生成的稳定工具类型。
     */
    public static final String IMAGE_GENERATION = "image_generation";

    /**
     * 工具搜索的稳定工具类型。
     */
    public static final String TOOL_SEARCH = "tool_search";

    private final Tool tool;
    private final String type;

    private OpenAIServerTool(Tool tool, String type) {
        this.tool = tool;
        this.type = type;
    }

    /**
     * 包装由 SDK 构建的 OpenAI 内置工具。
     *
     * @param tool SDK 工具定义
     * @return 已验证的服务端工具
     * @throws IllegalArgumentException tool 为 null 或不受支持时抛出
     */
    public static OpenAIServerTool of(Tool tool) {
        if (tool == null) {
            throw new IllegalArgumentException("OpenAI server tool must not be null");
        }

        String type = resolveType(tool);
        if (type == null) {
            throw new IllegalArgumentException(
                    "OpenAIServerTool supports provider-executed built-in tools only. Custom"
                            + " functions, namespaces, MCP, computer use, shell, apply patch, and"
                            + " local runtime tools are not accepted.");
        }

        if (TOOL_SEARCH.equals(type)) {
            tool.search()
                    .flatMap(ToolSearchTool::execution)
                    .filter(ToolSearchTool.Execution.CLIENT::equals)
                    .ifPresent(
                            execution -> {
                                throw new IllegalArgumentException(
                                        "Client-executed tool_search is not a provider-executed"
                                                + " server tool.");
                            });
        }

        try {
            tool.validate();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    "Invalid OpenAI server tool definition: " + e.getMessage(), e);
        }

        return new OpenAIServerTool(tool, type);
    }

    private static String resolveType(Tool tool) {
        if (tool.webSearch().isPresent()) {
            return WEB_SEARCH;
        }
        if (tool.codeInterpreter().isPresent()) {
            return CODE_INTERPRETER;
        }
        if (tool.imageGeneration().isPresent()) {
            return IMAGE_GENERATION;
        }
        if (tool.search().isPresent()) {
            return TOOL_SEARCH;
        }
        return null;
    }

    /**
     * 获取稳定的 Responses API 工具类型。
     *
     * @return 工具类型
     */
    public String getType() {
        return type;
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
