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

package io.agentscope.extensions.higress;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.agentscope.core.util.JsonException;
import io.agentscope.core.util.JsonUtils;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 调用 x_higress_tool_search 工具的结果。
 * <p>该工具按查询的语义相似度返回推荐工具列表，此类提供解析与访问结果的方法。
 * <p>Higress 响应结构示例：
 * <pre>{@code
 * {
 *   "content": [
 *     {
 *       "type": "text",
 *       "text": "{\"tools\":[...]}"
 *     }
 *   ],
 *   "structuredContent": {
 *     "tools": [
 *       {
 *         "name": "map___maps_weather",
 *         "description": "根据城市名称或者标准adcode查询指定城市的天气",
 *         "title": "maps_weather",
 *         "inputSchema": {...},
 *         "outputSchema": {}
 *       }
 *     ]
 *   }
 * }
 * }</pre>
 */
public class HigressToolSearchResult {

    private static final Logger logger = LoggerFactory.getLogger(HigressToolSearchResult.class);

    private final List<ToolInfo> tools;
    private final boolean success;
    private final String errorMessage;

    private HigressToolSearchResult(List<ToolInfo> tools, boolean success, String errorMessage) {
        this.tools = tools != null ? tools : Collections.emptyList();
        this.success = success;
        this.errorMessage = errorMessage;
    }

    /**
     * 从 MCP CallToolResult 解析工具搜索结果。
     * @param callToolResult 调用 x_higress_tool_search 的结果
     * @return 解析后的 HigressToolSearchResult
     */
    public static HigressToolSearchResult parse(McpSchema.CallToolResult callToolResult) {
        if (callToolResult == null) {
            return error("CallToolResult is null");
        }

        if (Boolean.TRUE.equals(callToolResult.isError())) {
            return error("Tool call returned error");
        }

        // 优先从 structuredContent 解析
        Object structuredContent = callToolResult.structuredContent();
        if (structuredContent instanceof Map<?, ?> structuredMap) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> typedMap = (Map<String, Object>) structuredMap;
                return parseFromStructuredContent(typedMap);
            } catch (Exception e) {
                logger.debug(
                        "Failed to parse structuredContent, trying content: {}", e.getMessage());
            }
        }

        // 回退为从 content 解析
        if (callToolResult.content() != null && !callToolResult.content().isEmpty()) {
            try {
                return parseFromContent(callToolResult.content());
            } catch (Exception e) {
                logger.error("Failed to parse content: {}", e.getMessage(), e);
                return error("Failed to parse tool search result: " + e.getMessage());
            }
        }

        return error("No content in tool search result");
    }

    /** 从 structuredContent 映射解析。 */
    @SuppressWarnings("unchecked")
    private static HigressToolSearchResult parseFromStructuredContent(
            Map<String, Object> structuredContent) {
        Object toolsObj = structuredContent.get("tools");
        if (toolsObj instanceof List<?> toolsList) {
            List<ToolInfo> tools =
                    toolsList.stream()
                            .filter(item -> item instanceof Map)
                            .map(item -> parseToolInfo((Map<String, Object>) item))
                            .toList();
            return success(tools);
        }
        throw new IllegalArgumentException("Invalid structuredContent format");
    }

    /** 从 content 列表（文本内容）解析。 */
    private static HigressToolSearchResult parseFromContent(List<McpSchema.Content> contentList)
            throws JsonException {
        for (McpSchema.Content content : contentList) {
            if (content instanceof McpSchema.TextContent textContent) {
                String text = textContent.text();
                if (text != null && !text.isEmpty()) {
                    ToolSearchResponse response =
                            JsonUtils.getJsonCodec().fromJson(text, ToolSearchResponse.class);
                    if (response.tools != null) {
                        return success(response.tools);
                    }
                }
            }
        }
        throw new IllegalArgumentException("No valid text content found");
    }

    /** 从映射解析单个工具信息。 */
    @SuppressWarnings("unchecked")
    private static ToolInfo parseToolInfo(Map<String, Object> map) {
        return new ToolInfo(
                (String) map.get("name"),
                (String) map.get("description"),
                (String) map.get("title"),
                (Map<String, Object>) map.get("inputSchema"),
                (Map<String, Object>) map.get("outputSchema"));
    }

    private static HigressToolSearchResult success(List<ToolInfo> tools) {
        return new HigressToolSearchResult(tools, true, null);
    }

    private static HigressToolSearchResult error(String message) {
        return new HigressToolSearchResult(Collections.emptyList(), false, message);
    }

    /**
     * 返回搜索是否成功。
     * @return 成功时为 true
     */
    public boolean isSuccess() {
        return success;
    }

    /**
     * 返回搜索失败的错误消息。
     * @return 错误消息；成功时为 null
     */
    public String getErrorMessage() {
        return errorMessage;
    }

    /**
     * 返回推荐工具列表。
     * @return 工具列表，不会为 null，但可为空
     */
    public List<ToolInfo> getTools() {
        return tools;
    }

    /**
     * 返回全部已找到工具的名称。
     * @return 工具名列表
     */
    public List<String> getToolNames() {
        return tools.stream().map(ToolInfo::name).toList();
    }

    @Override
    public String toString() {
        if (!success) {
            return "HigressToolSearchResult{error='" + errorMessage + "'}";
        }
        return "HigressToolSearchResult{tools=" + getToolNames() + "}";
    }

    /**
     * 搜索返回的单个工具。
     * @param name 工具名称（例如 "map___maps_weather"）
     * @param description 工具描述
     * @param title 工具标题（例如 "maps_weather"）
     * @param inputSchema 输入参数 JSON 模式
     * @param outputSchema 输出参数 JSON 模式
     */
    public record ToolInfo(
            String name,
            String description,
            String title,
            Map<String, Object> inputSchema,
            Map<String, Object> outputSchema) {}

    /** 用于 JSON 反序列化的内部类。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class ToolSearchResponse {
        @JsonProperty("tools")
        List<ToolInfo> tools;
    }
}
