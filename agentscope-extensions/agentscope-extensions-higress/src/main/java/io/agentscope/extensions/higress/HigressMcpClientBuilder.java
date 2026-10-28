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

import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import reactor.core.publisher.Mono;

/**
 * 创建 {@link HigressMcpClientWrapper} 实例的构建器。
 * <p>采用与 agentscope-core 中 {@link McpClientBuilder} 相同的模式，
 * 提供用于配置和构建 Higress MCP 客户端的流式 API。
 * <p>支持两种传输：
 * <ul>
 *   <li><b>SSE（服务器发送事件）</b>：支持服务器推送的连接。</li>
 *   <li><b>StreamableHTTP</b>：可流式传输的 HTTP。</li>
 * </ul>
 * <p>以下依次演示 SSE、StreamableHTTP，以及带认证配置的用法：
 * <pre>{@code
 * HigressMcpClientWrapper client = HigressMcpClientBuilder
 *     .create("higress-mcp")
 *     .sseEndpoint("http://higress-gateway/mcp-servers/union-tools-search/sse")
 *     .build();
 * }</pre>
<pre>{@code
 * HigressMcpClientWrapper client = HigressMcpClientBuilder
 *     .create("higress-mcp")
 *     .streamableHttpEndpoint("http://higress-gateway/mcp-servers/union-tools-search")
 *     .build();
 * }</pre>
<pre>{@code
 * HigressMcpClientWrapper client = HigressMcpClientBuilder
 *     .create("higress-mcp")
 *     .sseEndpoint("http://higress-gateway/mcp-servers/union-tools-search/sse")
 *     .header("Authorization", "Bearer " + token)
 *     .header("X-Api-Key", apiKey)
 *     .timeout(Duration.ofSeconds(60))
 *     .buildAsync()
 *     .block();
 * }</pre>
 * <p>【学习提示】请求头和 URL 查询参数只是传递配置，服务端决定认证/授权。
 * 不要把工具搜索结果当作访问许可；敏感凭据放在 URL 中还可能进入网关访问日志。
 * @see HigressMcpClientWrapper
 * @see McpClientBuilder
 */
public class HigressMcpClientBuilder {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration DEFAULT_INIT_TIMEOUT = Duration.ofSeconds(30);

    private final String clientName;
    private String endpoint;
    private TransportType transportType;
    private final Map<String, String> headers = new HashMap<>();
    private final Map<String, String> queryParams = new HashMap<>();
    private Duration timeout = DEFAULT_TIMEOUT;
    private Duration initializationTimeout = DEFAULT_INIT_TIMEOUT;
    private boolean enableToolSearch = false;
    private String toolSearchQuery;
    private int toolSearchTopK = 10;

    /**
     * 私有构造方法。通过静态工厂方法创建实例。
     * @param clientName MCP 客户端的唯一名称
     */
    private HigressMcpClientBuilder(String clientName) {
        this.clientName = clientName;
    }

    /**
     * 使用指定客户端名创建构建器。
     * @param clientName MCP 客户端的唯一标识符
     * @return 新构建器实例
     * @throws IllegalArgumentException 当 clientName 为 null 或空时
     */
    public static HigressMcpClientBuilder create(String clientName) {
        if (clientName == null || clientName.trim().isEmpty()) {
            throw new IllegalArgumentException("Client name cannot be null or empty");
        }
        return new HigressMcpClientBuilder(clientName);
    }

    /**
     * 配置 SSE（服务器发送事件）传输端点。
     * <p>适用于需要实时服务器推送的连接场景。
     * @param endpoint SSE 端点 URL（例如 "http://higress-gateway/mcp-servers/union-tools-search/sse"）
     * @return 用于链式调用的当前构建器
     */
    public HigressMcpClientBuilder sseEndpoint(String endpoint) {
        this.endpoint = endpoint;
        this.transportType = TransportType.SSE;
        return this;
    }

    /**
     * 配置 StreamableHTTP 传输端点。
     * <p>用于 HTTP 流式传输；具体会话状态由底层实现和服务端决定。
     * @param endpoint StreamableHTTP 端点 URL（例如 "http://higress-gateway/mcp-servers/union-tools-search"）
     * @return 用于链式调用的当前构建器
     */
    public HigressMcpClientBuilder streamableHttpEndpoint(String endpoint) {
        this.endpoint = endpoint;
        this.transportType = TransportType.STREAMABLE_HTTP;
        return this;
    }

    /**
     * 添加每个请求都携带的 HTTP 请求头。
     * <p>常见用途是认证请求头：
     * <ul>
     *   <li>{@code header("Authorization", "Bearer " + token)}</li>
     *   <li>{@code header("X-Api-Key", apiKey)}</li>
     * </ul>
     * @param key 请求头名称
     * @param value 请求头值
     * @return 用于链式调用的当前构建器
     */
    public HigressMcpClientBuilder header(String key, String value) {
        this.headers.put(key, value);
        return this;
    }

    /**
     * 一次设置多个 HTTP 请求头。
     * @param headers 请求头名称与值的映射
     * @return 用于链式调用的当前构建器
     */
    public HigressMcpClientBuilder headers(Map<String, String> headers) {
        if (headers != null) {
            this.headers.putAll(headers);
        }
        return this;
    }

    /**
     * 向 URL 添加查询参数，与 URL 中已有的查询参数合并。
     * <p>示例：
     * <pre>{@code
     * HigressMcpClientWrapper client = HigressMcpClientBuilder
     *     .create("higress")
     *     .streamableHttpEndpoint("http://gateway/mcp")
     *     .queryParam("token", "abc123")
     *     .queryParam("env", "prod")
     *     .build();
     * }</pre>
     * @param key 查询参数名
     * @param value 查询参数值
     * @return 用于链式调用的当前构建器
     */
    public HigressMcpClientBuilder queryParam(String key, String value) {
        this.queryParams.put(key, value);
        return this;
    }

    /**
     * 一次设置多个查询参数。
     * @param queryParams 查询参数名称与值的映射
     * @return 用于链式调用的当前构建器
     */
    public HigressMcpClientBuilder queryParams(Map<String, String> queryParams) {
        if (queryParams != null) {
            this.queryParams.putAll(queryParams);
        }
        return this;
    }

    /**
     * 设置请求超时时长。
     * <p>适用于单个 MCP 请求（工具调用、工具列举等），默认 120 秒。
     * @param timeout 超时时长
     * @return 用于链式调用的当前构建器
     */
    public HigressMcpClientBuilder timeout(Duration timeout) {
        this.timeout = timeout;
        return this;
    }

    /**
     * 设置初始化超时时长。
     * <p>适用于客户端初始化过程，默认 30 秒。
     * @param timeout 超时时长
     * @return 用于链式调用的当前构建器
     */
    public HigressMcpClientBuilder initializationTimeout(Duration timeout) {
        this.initializationTimeout = timeout;
        return this;
    }

    /**
     * 使用指定查询启用工具搜索。
     * <p>启用后 listTools() 会使用该查询调用 x_higress_tool_search，
     * 仅返回语义相关工具（默认前 10 个）。
     * <p>示例：
     * <pre>{@code
     * HigressMcpClientWrapper client = HigressMcpClientBuilder
     *     .create("higress")
     *     .streamableHttpEndpoint(endpoint)
     *     .toolSearch("查询天气")
     *     .build();
     * }</pre>
     * @param query 描述所需工具的搜索查询
     * @return 用于链式调用的当前构建器
     */
    public HigressMcpClientBuilder toolSearch(String query) {
        this.enableToolSearch = true;
        this.toolSearchQuery = query;
        return this;
    }

    /**
     * 使用指定查询及 topK 启用工具搜索。
     * <p>示例：
     * <pre>{@code
     * HigressMcpClientWrapper client = HigressMcpClientBuilder
     *     .create("higress")
     *     .streamableHttpEndpoint(endpoint)
     *     .toolSearch("查询天气", 5)
     *     .build();
     * }</pre>
     * @param query 描述所需工具的搜索查询
     * @param topK 返回工具数量上限（必须为正数）
     * @return 用于链式调用的当前构建器
     * @throws IllegalArgumentException 当 topK 非正数时
     */
    public HigressMcpClientBuilder toolSearch(String query, int topK) {
        if (topK <= 0) {
            throw new IllegalArgumentException("topK must be a positive integer, got: " + topK);
        }
        this.enableToolSearch = true;
        this.toolSearchQuery = query;
        this.toolSearchTopK = topK;
        return this;
    }

    /**
     * 构建异步 {@link HigressMcpClientWrapper} 实例。
     * <p>调用时先校验配置并创建底层 {@link McpClientBuilder}；返回的 {@link Mono}
     * 在订阅构建流程后，将底层客户端包装为 {@link HigressMcpClientWrapper} 并初始化。
     * <p>使用示例：
     * <pre>{@code
     * HigressMcpClientWrapper client = HigressMcpClientBuilder
     *     .create("higress-mcp")
     *     .streamableHttpEndpoint(endpoint)
     *     .buildAsync()
     *     .block();
     * }</pre>
     * @return 发出已配置 HigressMcpClientWrapper 实例的 Mono
     * @throws IllegalArgumentException 未配置端点时
     * @throws IllegalStateException 未配置传输类型时
     */
    public Mono<HigressMcpClientWrapper> buildAsync() {
        // 校验配置
        validateConfiguration();

        // 通过 AgentScope 的 McpClientBuilder 构建底层 MCP 客户端
        McpClientBuilder mcpClientBuilder = createMcpClientBuilder();

        // 构建异步委托客户端，包装并初始化
        return mcpClientBuilder
                .buildAsync()
                .flatMap(
                        delegateClient -> {
                            HigressMcpClientWrapper wrapper =
                                    new HigressMcpClientWrapper(
                                            clientName,
                                            delegateClient,
                                            enableToolSearch,
                                            toolSearchQuery,
                                            toolSearchTopK);
                            return wrapper.initialize().thenReturn(wrapper);
                        });
    }

    /**
     * 构建同步 {@link HigressMcpClientWrapper} 实例（阻塞操作）。
     * <p>先校验配置，再用 {@link McpClientBuilder} 创建底层同步客户端，
     * 包装为 {@link HigressMcpClientWrapper}，最后初始化客户端。
     * <p>使用示例：
     * <pre>{@code
     * HigressMcpClientWrapper client = HigressMcpClientBuilder
     *     .create("higress-mcp")
     *     .streamableHttpEndpoint(endpoint)
     *     .buildSync();
     * }</pre>
     * @return 已配置并初始化的 HigressMcpClientWrapper 实例
     * @throws IllegalArgumentException 未配置端点时
     * @throws IllegalStateException 未配置传输类型时
     */
    public HigressMcpClientWrapper buildSync() {
        // 校验配置
        validateConfiguration();

        // 通过 AgentScope 的 McpClientBuilder 构建底层 MCP 客户端
        McpClientBuilder mcpClientBuilder = createMcpClientBuilder();

        // 构建同步委托客户端
        McpClientWrapper delegateClient = mcpClientBuilder.buildSync();

        // 创建 Higress 包装器并初始化
        HigressMcpClientWrapper wrapper =
                new HigressMcpClientWrapper(
                        clientName,
                        delegateClient,
                        enableToolSearch,
                        toolSearchQuery,
                        toolSearchTopK);

        wrapper.initialize().block();

        return wrapper;
    }

    /**
     * 校验构建器配置。
     * @throws IllegalArgumentException 未配置端点或缺少工具搜索查询时
     * @throws IllegalStateException 未配置传输类型时
     */
    private void validateConfiguration() {
        if (endpoint == null || endpoint.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "Endpoint must be configured via sseEndpoint() or streamableHttpEndpoint()");
        }
        if (transportType == null) {
            throw new IllegalStateException(
                    "Transport type must be configured via sseEndpoint() or"
                            + " streamableHttpEndpoint()");
        }
        if (enableToolSearch && (toolSearchQuery == null || toolSearchQuery.trim().isEmpty())) {
            throw new IllegalArgumentException(
                    "Query is required for tool search. Use toolSearch(query) to configure.");
        }
    }

    /**
     * 创建并配置底层 McpClientBuilder。
     * @return 已配置的 McpClientBuilder 实例
     */
    private McpClientBuilder createMcpClientBuilder() {
        McpClientBuilder mcpClientBuilder = McpClientBuilder.create(clientName);

        // 根据类型配置传输
        switch (transportType) {
            case SSE -> mcpClientBuilder.sseTransport(endpoint);
            case STREAMABLE_HTTP -> mcpClientBuilder.streamableHttpTransport(endpoint);
        }

        // 配置请求头
        if (!headers.isEmpty()) {
            mcpClientBuilder.headers(headers);
        }

        // 配置查询参数
        if (!queryParams.isEmpty()) {
            mcpClientBuilder.queryParams(queryParams);
        }

        // 配置超时
        mcpClientBuilder.timeout(timeout);
        mcpClientBuilder.initializationTimeout(initializationTimeout);

        return mcpClientBuilder;
    }

    /** 传输类型枚举。 */
    private enum TransportType {
        /** 支持服务器推送的 SSE 传输。 */
        SSE,

        /** Streamable HTTP 流式传输。 */
        STREAMABLE_HTTP
    }
}
