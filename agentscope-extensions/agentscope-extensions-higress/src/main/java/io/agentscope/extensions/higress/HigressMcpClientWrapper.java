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

import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Higress AI 网关的 MCP 客户端包装器。
 * <p>扩展 {@link McpClientWrapper}，连接 Higress 的统一 MCP 服务端点 union-tools-search。
 * Higress 启用语义搜索后，网关提供特殊工具 {@code x_higress_tool_search}，
 * 可按用户请求自动选择语义相关的 MCP 工具。
 * <p>所有 MCP 操作委托给底层 {@link McpClientWrapper} 实例（异步或同步），
 * 为 Higress 专用能力提供一致接口。
 * <p>使用示例：
 * <pre>{@code
 * HigressMcpClientWrapper client = HigressMcpClientBuilder
 *     .create("higress-mcp")
 *     .sseEndpoint("http://higress-gateway/mcp-servers/union-tools-search/sse")
 *     .build();
 *
 * // 初始化并列出工具
 * client.initialize().block();
 * List<McpSchema.Tool> tools = client.listTools().block();
 *
 * // 调用 x_higress_tool_search 获取推荐工具
 * McpSchema.CallToolResult result = client.callTool("x_higress_tool_search",
 *     Map.of("query", "查询北京天气")).block();
 * }</pre>
 * <p>【学习提示】语义搜索缩小的是提供给模型的工具集合，并非安全授权边界。
 * 工具描述、输入模式和输出模式来自远端，业务授权与实际参数验证仍需由调用端及服务端落实。
 * @see HigressMcpClientBuilder
 * @see McpClientWrapper
 */
public class HigressMcpClientWrapper extends McpClientWrapper {

    private static final Logger logger = LoggerFactory.getLogger(HigressMcpClientWrapper.class);

    /** Higress 工具搜索工具的名称。 */
    public static final String TOOL_SEARCH_NAME = "x_higress_tool_search";

    /** 负责实际 MCP 协议通信的底层 MCP 客户端。 */
    private final McpClientWrapper delegateClient;

    /** 是否启用 x_higress_tool_search。 */
    private final boolean enableToolSearch;

    /** x_higress_tool_search 的查询。 */
    private final String toolSearchQuery;

    /** x_higress_tool_search 返回工具数量上限。 */
    private final int toolSearchTopK;

    /**
     * 构造 HigressMcpClientWrapper。
     * @param name 此客户端的唯一名称
     * @param delegateClient 接收委托操作的底层 MCP 客户端包装器
     * @param enableToolSearch 是否启用 x_higress_tool_search 工具
     * @param toolSearchQuery x_higress_tool_search 的查询
     * @param toolSearchTopK 返回工具数量上限
     */
    HigressMcpClientWrapper(
            String name,
            McpClientWrapper delegateClient,
            boolean enableToolSearch,
            String toolSearchQuery,
            int toolSearchTopK) {
        super(name);
        this.delegateClient = delegateClient;
        this.enableToolSearch = enableToolSearch;
        this.toolSearchQuery = toolSearchQuery;
        this.toolSearchTopK = toolSearchTopK;
    }

    /**
     * 初始化 Higress MCP 客户端连接。
     * <p>委托底层 MCP 客户端连接 Higress 网关并发现可用工具。
     * @return 初始化结束时完成的 Mono
     */
    @Override
    public Mono<Void> initialize() {
        if (isInitialized()) {
            logger.debug("Higress MCP client '{}' already initialized", name);
            return Mono.empty();
        }

        logger.info("Initializing Higress MCP client: {}", name);

        return delegateClient
                .initialize()
                .doOnSuccess(
                        unused -> {
                            this.initialized = true;
                            logger.info("Higress MCP client '{}' initialized successfully", name);
                        })
                .doOnError(
                        error ->
                                logger.error(
                                        "Failed to initialize Higress MCP client '{}': {}",
                                        name,
                                        error.getMessage(),
                                        error));
    }

    /**
     * 列出 Higress 网关的可用工具。
     * <p>{@code enableToolSearch} 为 true 时，使用配置查询调用 x_higress_tool_search，
     * 解析推荐工具并转换后返回。
     * <p>{@code enableToolSearch} 为 false 时，返回 MCP 服务器提供的全部工具。
     * @return 发出可用工具列表的 Mono
     */
    @Override
    public Mono<List<McpSchema.Tool>> listTools() {
        if (enableToolSearch) {
            // 调用 x_higress_tool_search，将结果转换为 McpSchema.Tool
            logger.info(
                    "Tool search enabled, calling {} with query: '{}'",
                    TOOL_SEARCH_NAME,
                    toolSearchQuery);

            return searchTools(toolSearchQuery, toolSearchTopK)
                    .map(
                            result -> {
                                if (!result.isSuccess()) {
                                    logger.error(
                                            "Tool search failed: {}", result.getErrorMessage());
                                    throw new RuntimeException(
                                            "Tool search failed: " + result.getErrorMessage());
                                }

                                // 将 ToolInfo 转换为 McpSchema.Tool
                                List<McpSchema.Tool> tools =
                                        result.getTools().stream()
                                                .map(this::convertToMcpTool)
                                                .toList();

                                logger.info(
                                        "Tool search returned {} tools: {}",
                                        tools.size(),
                                        result.getToolNames());
                                return tools;
                            })
                    .doOnNext(
                            tools -> {
                                // 在本地缓存工具
                                tools.forEach(tool -> cachedTools.put(tool.name(), tool));
                            });
        } else {
            // 返回委托客户端提供的全部工具
            return delegateClient
                    .listTools()
                    .doOnNext(
                            tools -> {
                                // 在本地缓存工具
                                tools.forEach(tool -> cachedTools.put(tool.name(), tool));
                                logger.debug(
                                        "Higress MCP client '{}' discovered {} tools",
                                        name,
                                        tools.size());
                            });
        }
    }

    /** 将 HigressToolSearchResult.ToolInfo 转换为 McpSchema.Tool。 */
    private McpSchema.Tool convertToMcpTool(HigressToolSearchResult.ToolInfo toolInfo) {
        Map<String, Object> inputSchemaMap = toolInfo.inputSchema();
        if (inputSchemaMap == null) {
            inputSchemaMap = new java.util.HashMap<>();
        }

        McpSchema.Tool.Builder builder = McpSchema.Tool.builder(toolInfo.name(), inputSchemaMap);
        if (toolInfo.title() != null) {
            builder.title(toolInfo.title());
        }
        if (toolInfo.description() != null) {
            builder.description(toolInfo.description());
        }
        if (toolInfo.outputSchema() != null) {
            builder.outputSchema(toolInfo.outputSchema());
        }
        return builder.build();
    }

    /**
     * 调用 Higress 网关上的工具。
     * <p>可调用网关上任意可用工具，包括用于智能工具选择的 {@code x_higress_tool_search}。
     * @param toolName 要调用的工具名称
     * @param arguments 传给工具的参数
     * @return 发出工具调用结果的 Mono
     */
    @Override
    public Mono<McpSchema.CallToolResult> callTool(String toolName, Map<String, Object> arguments) {
        return callTool(toolName, arguments, null);
    }

    @Override
    public Mono<McpSchema.CallToolResult> callTool(
            String toolName, Map<String, Object> arguments, Map<String, Object> meta) {
        logger.debug(
                "Calling tool '{}' on Higress MCP client '{}' with arguments: {}",
                toolName,
                name,
                arguments);

        return delegateClient
                .callTool(toolName, arguments, meta)
                .doOnSuccess(
                        result -> {
                            if (Boolean.TRUE.equals(result.isError())) {
                                logger.warn(
                                        "Higress tool '{}' returned error: {}",
                                        toolName,
                                        result.content());
                            } else {
                                logger.debug("Higress tool '{}' completed successfully", toolName);
                            }
                        })
                .doOnError(
                        error ->
                                logger.error(
                                        "Failed to call Higress tool '{}': {}",
                                        toolName,
                                        error.getMessage()));
    }

    /** 关闭 Higress MCP 客户端并释放所有资源。<p>关闭底层委托客户端并清空所有缓存数据。 */
    @Override
    public void close() {
        logger.info("Closing Higress MCP client: {}", name);

        if (delegateClient != null) {
            try {
                delegateClient.close();
                logger.debug("Higress MCP client '{}' closed successfully", name);
            } catch (Exception e) {
                logger.error("Error closing Higress MCP client '{}': {}", name, e.getMessage(), e);
            }
        }

        this.initialized = false;
        this.cachedTools.clear();
    }

    /**
     * 检查此客户端是否启用 x_higress_tool_search 模式。
     * <p>启用时，listTools() 调用 x_higress_tool_search 获取语义相关工具，而非返回所有工具。
     * @return 工具搜索模式启用时为 true
     */
    public boolean isToolSearchEnabled() {
        return enableToolSearch;
    }

    /**
     * 搜索匹配查询的工具，返回解析结果。
     * <p>调用 x_higress_tool_search，并将响应解析为结构化 {@link HigressToolSearchResult}。
     * @param query 用于查找匹配工具的用户查询（必填）
     * @return 发出已解析工具搜索结果的 Mono
     */
    public Mono<HigressToolSearchResult> searchTools(String query) {
        return callTool(TOOL_SEARCH_NAME, Map.of("query", query))
                .map(HigressToolSearchResult::parse);
    }

    /**
     * 按 topK 上限搜索匹配查询的工具。
     * @param query 用于查找匹配工具的用户查询（必填）
     * @param topK 返回工具数量上限
     * @return 发出已解析工具搜索结果的 Mono
     */
    public Mono<HigressToolSearchResult> searchTools(String query, int topK) {
        return callTool(TOOL_SEARCH_NAME, Map.of("query", query, "topK", topK))
                .map(HigressToolSearchResult::parse);
    }
}
