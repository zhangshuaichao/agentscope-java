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
package io.agentscope.extensions.model.anthropic.tool;

import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.ToolUnion;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Anthropic 专用服务端工具定义。
 *
 * <p>服务端工具在一次模型调用内由 Anthropic 基础设施执行。
 * 先使用 SDK 的强类型构建器创建工具定义，再将得到的 {@link
 * ToolUnion} 通过 {@link #of(ToolUnion)} 包装。
 * SDK 尚未建模的 API 字段可使用构建器的 {@code putAdditionalProperty("field", JsonValue.from(value))}。
 *
 * <p>示例：
 * <pre>{@code
 * AnthropicServerTool.of(ToolUnion.ofWebFetchTool20250910(
 *         WebFetchTool20250910.builder()
 *                 .maxUses(10)
 *                 .allowedDomains(List.of("docs.anthropic.com"))
 *                 .build()));
 * }</pre>
 */
public final class AnthropicServerTool {

    private final ToolUnion toolUnion;
    private final String name;

    private AnthropicServerTool(ToolUnion toolUnion, String name) {
        this.toolUnion = toolUnion;
        this.name = name;
    }

    /**
     * 包装 SDK 构建的 Anthropic 工具定义。
     *
     * <p>不接受自定义客户端工具；此类工具应使用常规工具集模式。
     *
     * @param toolUnion SDK 工具定义
     * @return 已验证的服务端工具
     * @throws IllegalArgumentException 工具为 null、无效或属于自定义客户端工具时抛出
     */
    public static AnthropicServerTool of(ToolUnion toolUnion) {
        if (toolUnion == null) {
            throw new IllegalArgumentException("Anthropic server tool must not be null");
        }
        if (toolUnion.isTool()) {
            throw new IllegalArgumentException(
                    "AnthropicServerTool does not accept custom client tools");
        }

        try {
            toolUnion.validate();
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Invalid Anthropic server tool definition: " + e.getMessage(), e);
        }

        JsonNode json = ObjectMappers.jsonMapper().valueToTree(toolUnion);
        return new AnthropicServerTool(toolUnion, json.path("name").asText());
    }

    /**
     * 获取 Anthropic 工具名称，例如 {@code web_search}。
     *
     * @return 不带版本的工具名称
     */
    public String getName() {
        return name;
    }

    /**
     * 返回缓存的已验证 SDK 工具定义。
     *
     * @return SDK 工具联合类型
     */
    public ToolUnion toToolUnion() {
        return toolUnion;
    }
}
