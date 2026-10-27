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
package io.agentscope.core.tool.mcp;

import static io.agentscope.core.Version.VERSION;

import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.ElicitFormRequest;
import io.modelcontextprotocol.spec.McpSchema.ElicitResult;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import reactor.core.publisher.Mono;

/**
 * 使用链式配置创建 MCP 客户端包装器的构建器。
 *
 * <p>支持三种传输：
 * <ul>
 * <li>StdIO：本地进程通信</li>
 * <li>SSE：HTTP 服务端发送事件（有状态）</li>
 * <li>StreamableHTTP：HTTP 流式传输（无状态）</li>
 * </ul>
 *
 * <p>使用示例：
 * <pre>{@code
 * // StdIO transport
 * McpClientWrapper client = McpClientBuilder.create("git-mcp")
 *         .stdioTransport("python", "-m", "mcp_server_git")
 *         .buildAsync()
 *         .block();
 *
 * // SSE transport with headers and query parameters
 * McpClientWrapper client = McpClientBuilder.create("remote-mcp")
 *         .sseTransport("https://mcp.example.com/sse")
 *         .header("Authorization", "Bearer " + token)
 *         .queryParam("queryKey", "queryValue")
 *         .timeout(Duration.ofSeconds(60))
 *         .buildAsync()
 *         .block();
 *
 * // HTTP transport with multiple query parameters
 * McpClientWrapper client = McpClientBuilder.create("http-mcp")
 *         .streamableHttpTransport("https://mcp.example.com/http")
 *         .queryParams(Map.of("token", "abc123", "env", "prod"))
 *         .buildSync();
 *
 * // StdIO transport with custom protocol versions
 * McpClientWrapper client = McpClientBuilder.create("mcp")
 *         .stdioTransport("python", "server.py")
 *         .protocolVersions("2024-11-05", "2025-03-26")
 *         .buildAsync()
 *         .block();
 * }</pre>
 */
public class McpClientBuilder {

    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration DEFAULT_INIT_TIMEOUT = Duration.ofSeconds(30);

    private final String name;
    private TransportConfig transportConfig;
    private Duration requestTimeout = DEFAULT_REQUEST_TIMEOUT;
    private Duration initializationTimeout = DEFAULT_INIT_TIMEOUT;
    private Function<ElicitFormRequest, Mono<ElicitResult>> asyncElicitationHandler;
    private Function<ElicitFormRequest, ElicitResult> syncElicitationHandler;
    private List<String> protocolVersions;
    private boolean propagateMeta = true;

    private McpClientBuilder(String name) {
        this.name = name;
    }

    /**
     * 使用指定名称创建 MCP 客户端构建器。
     *
     * @param name MCP 客户端唯一标识符
     * @return 新的构建器实例
     */
    public static McpClientBuilder create(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("MCP client name cannot be null or empty");
        }
        return new McpClientBuilder(name);
    }

    /**
     * 配置本地进程通信的 StdIO 传输。
     *
     * @param command 可执行命令
     * @param args 命令参数
     * @return 当前构建器
     */
    public McpClientBuilder stdioTransport(String command, String... args) {
        this.transportConfig = new StdioTransportConfig(command, Arrays.asList(args));
        return this;
    }

    /**
     * 配置带环境变量的 StdIO 传输。
     *
     * @param command 可执行命令
     * @param args 命令参数列表
     * @param env 环境变量
     * @return 当前构建器
     */
    public McpClientBuilder stdioTransport(
            String command, List<String> args, Map<String, String> env) {
        this.transportConfig = new StdioTransportConfig(command, args, env);
        return this;
    }

    /**
     * 配置用于有状态连接的 HTTP SSE（服务端发送事件）传输。
     *
     * @param url 服务器 URL
     * @return 当前构建器
     */
    public McpClientBuilder sseTransport(String url) {
        this.transportConfig = new SseTransportConfig(url);
        return this;
    }

    /**
     * 自定义 SSE 传输的 HTTP 客户端（仅在调用 sseTransport 后适用），支持 HTTP/2、自定义超时、SSL 设置等高级配置。
     *
     * <p>HTTP/2 用法示例：
     * <pre>{@code
     * McpClientWrapper client = McpClientBuilder.create("mcp")
     *         .sseTransport("https://example.com/sse")
     *     .customizeSseClient(clientBuilder ->
     *         clientBuilder.version(java.net.http.HttpClient.Version.HTTP_2))
     *         .buildAsync()
     *         .block();
     * }</pre>
     *
     * @param customizer 定制 HttpClient.Builder 的消费者
     * @return 当前构建器
     */
    public McpClientBuilder customizeSseClient(Consumer<HttpClient.Builder> customizer) {
        if (transportConfig instanceof SseTransportConfig) {
            ((SseTransportConfig) transportConfig).customizeHttpClient(customizer);
        }
        return this;
    }

    /**
     * 配置用于无状态连接的 HTTP StreamableHTTP 传输。
     *
     * @param url 服务器 URL
     * @return 当前构建器
     */
    public McpClientBuilder streamableHttpTransport(String url) {
        this.transportConfig = new StreamableHttpTransportConfig(url);
        return this;
    }

    /**
     * 自定义 StreamableHTTP 传输的 HTTP 客户端（仅在调用 streamableHttpTransport 后适用），支持 HTTP/2、自定义超时、SSL 设置等高级配置。
     *
     * <p>HTTP/2 用法示例：
     *
     * <pre>{@code
     * McpClientWrapper client = McpClientBuilder.create("mcp")
     *         .streamableHttpTransport("https://example.com/http")
     *         .customizeStreamableHttpClient(
     *                 clientBuilder -> clientBuilder.version(java.net.http.HttpClient.Version.HTTP_2))
     *         .buildAsync()
     *         .block();
     * }</pre>
     *
     * @param customizer 定制 HttpClient.Builder 的消费者
     * @return 当前构建器
     */
    public McpClientBuilder customizeStreamableHttpClient(Consumer<HttpClient.Builder> customizer) {
        if (transportConfig instanceof StreamableHttpTransportConfig) {
            ((StreamableHttpTransportConfig) transportConfig).customizeHttpClient(customizer);
        }
        return this;
    }

    /**
     * 注册逐请求 HTTP 定制器，用于动态修改请求（仅适用于 SSE 和 StreamableHTTP 传输）。
     *
     * <p>与构建时设置静态请求头的 {@link #header(String, String)} 不同，此定制器在<strong>每次</strong>请求时调用，支持 OAuth/STS 凭据过期场景中的动态 token 注入。
     *
     * <p>直接委托给 MCP SDK 的 {@code httpRequestCustomizer(McpSyncHttpClientRequestCustomizer)} API，每次请求提供 HTTP 方法、端点 URI、请求体和传输上下文。
     *
     * <p>动态 OAuth token 示例：
     * <pre>{@code
     * McpClientWrapper client = McpClientBuilder.create("mcp-server")
     *         .streamableHttpTransport("https://mcp.example.com/http")
     *         .httpRequestCustomizer((builder, method, uri, body, ctx) -> {
     *             String token = tokenService.getToken();
     *             builder.header("Authorization", "Bearer " + token);
     *         })
     *         .buildSync();
     * }</pre>
     *
     * <p>SSE 传输示例：
     * <pre>{@code
     * McpClientWrapper client = McpClientBuilder.create("mcp-server")
     *         .sseTransport("https://mcp.example.com/sse")
     *         .httpRequestCustomizer((builder, method, uri, body, ctx) -> {
     *             builder.header("Authorization", "Bearer " + tokenProvider.getToken());
     *             builder.header("X-Request-Id", UUID.randomUUID().toString());
     *         })
     *         .buildAsync()
     *         .block();
     * }</pre>
     *
     * @param customizer 每次 HTTP 请求前调用的定制器
     * @return 当前构建器
     * @since 2.0.0
     */
    public McpClientBuilder httpRequestCustomizer(McpSyncHttpClientRequestCustomizer customizer) {
        if (transportConfig instanceof HttpTransportConfig) {
            ((HttpTransportConfig) transportConfig).setHttpRequestCustomizer(customizer);
        }
        return this;
    }

    /**
     * 添加 HTTP 请求头（仅适用于 HTTP 传输）。
     *
     * @param key 请求头名称
     * @param value 请求头值
     * @return 当前构建器
     */
    public McpClientBuilder header(String key, String value) {
        if (transportConfig instanceof HttpTransportConfig) {
            ((HttpTransportConfig) transportConfig).addHeader(key, value);
        }
        return this;
    }

    /**
     * 设置多个 HTTP 请求头（仅适用于 HTTP 传输）。
     *
     * @param headers 请求头名称和值的映射
     * @return 当前构建器
     */
    public McpClientBuilder headers(Map<String, String> headers) {
        if (transportConfig instanceof HttpTransportConfig) {
            ((HttpTransportConfig) transportConfig).setHeaders(headers);
        }
        return this;
    }

    /**
     * 向 URL 添加查询参数（仅适用于 HTTP 传输）。
     *
     * <p>与 URL 已有查询参数合并，同名时新增参数优先。
     *
     * @param key 查询参数名称
     * @param value 查询参数值
     * @return 当前构建器
     */
    public McpClientBuilder queryParam(String key, String value) {
        if (transportConfig instanceof HttpTransportConfig) {
            ((HttpTransportConfig) transportConfig).addQueryParam(key, value);
        }
        return this;
    }

    /**
     * 设置多个查询参数（仅适用于 HTTP 传输）。
     *
     * <p>替换此前添加的查询参数，但仍保留并合并原 URL 中的参数。
     *
     * @param queryParams 查询参数名称和值的映射
     * @return 当前构建器
     */
    public McpClientBuilder queryParams(Map<String, String> queryParams) {
        if (transportConfig instanceof HttpTransportConfig) {
            ((HttpTransportConfig) transportConfig).setQueryParams(queryParams);
        }
        return this;
    }

    /**
     * 设置请求超时时长。
     *
     * @param timeout 超时时长
     * @return 当前构建器
     */
    public McpClientBuilder timeout(Duration timeout) {
        this.requestTimeout = timeout;
        return this;
    }

    /**
     * 设置初始化超时时长。
     *
     * @param timeout 超时时长
     * @return 当前构建器
     */
    public McpClientBuilder initializationTimeout(Duration timeout) {
        this.initializationTimeout = timeout;
        return this;
    }

    /**
     * 设置客户端支持的 MCP 协议版本。
     *
     * <p>默认仅支持 "2024-11-05"；服务器初始化响应使用其他版本（例如 "2025-03-26"）时，连接会因 "Unsupported protocol version" 失败。
     *
     * <p>通过此方法声明支持额外版本：
     * <pre>{@code
     * McpClientWrapper client = McpClientBuilder.create("server")
     *         .stdioTransport("python", "server.py")
     *         .protocolVersions("2024-11-05", "2025-03-26")
     *         .buildAsync()
     *         .block();
     * }</pre>
     *
     * @param versions 一个或多个协议版本字符串（例如 "2024-11-05"、"2025-03-26"）
     * @return 当前构建器
     * @throws IllegalArgumentException 当未提供版本时
     */
    public McpClientBuilder protocolVersions(String... versions) {
        if (versions == null || versions.length == 0) {
            throw new IllegalArgumentException("At least one protocol version must be specified");
        }
        List<String> filtered =
                Arrays.stream(versions).filter(Objects::nonNull).collect(Collectors.toList());
        if (filtered.isEmpty()) {
            throw new IllegalArgumentException(
                    "At least one non-null protocol version must be specified");
        }
        this.protocolVersions = Collections.unmodifiableList(filtered);
        return this;
    }

    /**
     * 注册异步信息征询处理器，处理服务器的 elicit 请求。
     *
     * <p>注册后自动在 ClientCapabilities 中启用 elicitation 能力。
     *
     * <p>配合 {@link #buildAsync()} 使用，处理器返回 {@link Mono} 进行异步处理。
     *
     * <p>使用示例：
     *
     * <pre>{@code
     * McpClientWrapper client = McpClientBuilder.create("mcp")
     *     .stdioTransport("python", "-m", "mcp_server")
     *     .asyncElicitation(request -> {
     *         // Handle elicitation request asynchronously
     *         return Mono.just(ElicitResult.builder()...build());
     *     })
     *     .buildAsync()
     *     .block();
     * }
     * </pre>
     *
     * @param handler 异步处理 elicit 请求的函数
     * @return 当前构建器
     */
    public McpClientBuilder asyncElicitation(
            Function<ElicitFormRequest, Mono<ElicitResult>> handler) {
        this.asyncElicitationHandler = handler;
        return this;
    }

    /**
     * 注册同步信息征询处理器，处理服务器的 elicit 请求。
     *
     * <p>注册后自动在 ClientCapabilities 中启用 elicitation 能力。
     *
     * <p>配合 {@link #buildSync()} 使用，处理器直接返回 {@link ElicitResult}。
     *
     * <p>使用示例：
     *
     * <pre>{@code
     * McpClientWrapper client = McpClientBuilder.create("mcp")
     *     .stdioTransport("python", "-m", "mcp_server")
     *     .syncElicitation(request -> {
     *         // Handle elicitation request synchronously
     *         return ElicitResult.builder()...build();
     *     })
     *     .buildSync();
     * }
     * </pre>
     *
     * @param handler 同步处理 elicit 请求的函数
     * @return 当前构建器
     */
    public McpClientBuilder syncElicitation(Function<ElicitFormRequest, ElicitResult> handler) {
        this.syncElicitationHandler = handler;
        return this;
    }

    /**
     * 配置是否向 MCP 服务器传播请求元数据。
     *
     * <p>默认将运行时上下文中以 {@link McpMeta} 注册的条目及框架工具调用 ID 作为每次请求的 {@code meta} 字段发送。对于不完全可信的服务器（例如第三方服务），应禁用传播，避免追踪 ID、回调 URL 等内部元数据离开进程。
     *
     * <p>使用示例：
     * <pre>{@code
     * McpClientWrapper external = McpClientBuilder.create("external-mcp")
     *         .propagateMeta(false)
     *         .sseTransport("https://mcp.example.com/sse")
     *         .buildAsync()
     *         .block();
     * }</pre>
     *
     * @param propagateMeta true 表示传播（默认），false 表示从请求中完全省略 {@code meta} 字段
     * @return 当前构建器
     */
    public McpClientBuilder propagateMeta(boolean propagateMeta) {
        this.propagateMeta = propagateMeta;
        return this;
    }

    /**
     * 构建异步 MCP 客户端包装器。
     *
     * @return 发出异步客户端包装器的 Mono
     */
    public Mono<McpClientWrapper> buildAsync() {
        if (transportConfig == null) {
            return Mono.error(new IllegalStateException("Transport must be configured"));
        }

        return Mono.fromCallable(
                () -> {
                    McpClientTransport transport = transportConfig.createTransport();

                    if (protocolVersions != null) {
                        transport =
                                new ProtocolVersionOverrideTransport(transport, protocolVersions);
                    }

                    McpSchema.Implementation clientInfo =
                            McpSchema.Implementation.builder("agentscope-java", VERSION)
                                    .title("AgentScope Java Framework")
                                    .build();

                    McpSchema.ClientCapabilities clientCapabilities =
                            buildCapabilities(asyncElicitationHandler != null);

                    var clientBuilder =
                            McpClient.async(transport)
                                    .requestTimeout(requestTimeout)
                                    .initializationTimeout(initializationTimeout)
                                    .clientInfo(clientInfo)
                                    .capabilities(clientCapabilities);

                    if (asyncElicitationHandler != null) {
                        clientBuilder = clientBuilder.elicitation(asyncElicitationHandler);
                    }

                    McpAsyncClient mcpClient = clientBuilder.build();

                    McpAsyncClientWrapper wrapper = new McpAsyncClientWrapper(name, mcpClient);
                    wrapper.setPropagateMeta(propagateMeta);
                    return wrapper;
                });
    }

    /**
     * 构建同步 MCP 客户端包装器（阻塞操作）。
     *
     * @return 同步客户端包装器
     */
    public McpClientWrapper buildSync() {
        if (transportConfig == null) {
            throw new IllegalStateException("Transport must be configured");
        }

        McpClientTransport transport = transportConfig.createTransport();

        if (protocolVersions != null) {
            transport = new ProtocolVersionOverrideTransport(transport, protocolVersions);
        }

        McpSchema.Implementation clientInfo =
                McpSchema.Implementation.builder("agentscope-java", VERSION)
                        .title("AgentScope Java Framework")
                        .build();

        McpSchema.ClientCapabilities clientCapabilities =
                buildCapabilities(syncElicitationHandler != null);

        var clientBuilder =
                McpClient.sync(transport)
                        .requestTimeout(requestTimeout)
                        .initializationTimeout(initializationTimeout)
                        .clientInfo(clientInfo)
                        .capabilities(clientCapabilities);

        if (syncElicitationHandler != null) {
            clientBuilder = clientBuilder.elicitation(syncElicitationHandler);
        }

        McpSyncClient mcpClient = clientBuilder.build();

        McpSyncClientWrapper wrapper = new McpSyncClientWrapper(name, mcpClient);
        wrapper.setPropagateMeta(propagateMeta);
        return wrapper;
    }

    /**
     * 构建客户端能力。
     * @param withElicitation 是否包含信息征询能力
     * @return 客户端能力
     */
    private McpSchema.ClientCapabilities buildCapabilities(boolean withElicitation) {
        McpSchema.ClientCapabilities.Builder capabilitiesBuilder =
                McpSchema.ClientCapabilities.builder();
        if (withElicitation) {
            capabilitiesBuilder.elicitation();
        }
        return capabilitiesBuilder.build();
    }

    // ==================== 内部传输配置类 ====================

    private interface TransportConfig {
        McpClientTransport createTransport();
    }

    /**
     * 覆盖向 MCP 客户端报告的协议版本的传输装饰器。
     *
     * <p>MCP Java SDK 的 {@code LifecycleInitializer} 严格检查服务器协议版本是否在客户端支持列表中。默认传输仅报告支持 "2024-11-05"，因此遇到响应较新版本（如 "2025-03-26"）的服务器时连接失败。
     *
     * <p>包装任意 {@link McpClientTransport}，覆写 {@link #protocolVersions()} 返回用户指定列表，其他操作均委托到底层传输。
     */
    private static class ProtocolVersionOverrideTransport implements McpClientTransport {

        private final McpClientTransport delegate;
        private final List<String> versions;

        ProtocolVersionOverrideTransport(McpClientTransport delegate, List<String> versions) {
            this.delegate = delegate;
            this.versions = versions;
        }

        @Override
        public List<String> protocolVersions() {
            return versions;
        }

        @Override
        public Mono<Void> connect(Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler) {
            return delegate.connect(handler);
        }

        @Override
        public void setExceptionHandler(Consumer<Throwable> handler) {
            delegate.setExceptionHandler(handler);
        }

        @Override
        public Mono<Void> closeGracefully() {
            return delegate.closeGracefully();
        }

        @Override
        public void close() {
            delegate.close();
        }

        @Override
        public Mono<Void> sendMessage(JSONRPCMessage message) {
            return delegate.sendMessage(message);
        }

        @Override
        public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
            return delegate.unmarshalFrom(data, typeRef);
        }
    }

    private static class StdioTransportConfig implements TransportConfig {
        private final String command;
        private final List<String> args;
        private final Map<String, String> env;

        public StdioTransportConfig(String command, List<String> args) {
            this(command, args, new HashMap<>());
        }

        public StdioTransportConfig(String command, List<String> args, Map<String, String> env) {
            this.command = command;
            this.args = new ArrayList<>(args);
            this.env = new HashMap<>(env);
        }

        @Override
        public McpClientTransport createTransport() {
            ServerParameters.Builder paramsBuilder = ServerParameters.builder(command);

            if (!args.isEmpty()) {
                paramsBuilder.args(args);
            }

            if (!env.isEmpty()) {
                paramsBuilder.env(env);
            }

            ServerParameters params = paramsBuilder.build();
            return new StdioClientTransport(
                    params,
                    new JacksonMcpJsonMapper(new com.fasterxml.jackson.databind.ObjectMapper()));
        }
    }

    private abstract static class HttpTransportConfig implements TransportConfig {
        protected final String url;
        protected Map<String, String> headers = new HashMap<>();
        protected Map<String, String> queryParams = new HashMap<>();
        protected McpSyncHttpClientRequestCustomizer httpRequestCustomizer;

        protected HttpTransportConfig(String url) {
            this.url = url;
        }

        public void addHeader(String key, String value) {
            headers.put(key, value);
        }

        public void setHeaders(Map<String, String> headers) {
            this.headers = new HashMap<>(headers);
        }

        public void addQueryParam(String key, String value) {
            if (key == null) {
                throw new IllegalArgumentException("Query parameter key cannot be null");
            }
            if (value == null) {
                throw new IllegalArgumentException("Query parameter value cannot be null");
            }
            queryParams.put(key, value);
        }

        public void setQueryParams(Map<String, String> queryParams) {
            if (queryParams == null) {
                throw new IllegalArgumentException("Query parameters map cannot be null");
            }
            this.queryParams = new HashMap<>(queryParams);
        }

        public void setHttpRequestCustomizer(McpSyncHttpClientRequestCustomizer customizer) {
            this.httpRequestCustomizer = customizer;
        }

        /**
         * 从 URL 提取端点路径，并合并附加查询参数。原 URL 参数会保留，同名时附加配置优先。
         *
         * @return 带查询参数的端点路径（例如 "/api/sse?token=abc"）
         */
        protected String extractEndpoint() {
            URI uri;
            try {
                uri = URI.create(url);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid URL format: " + url, e);
            }

            String endpoint = uri.getPath();
            if (endpoint == null || endpoint.isEmpty()) {
                endpoint = "/";
            }

            // 解析 URL 已有查询参数
            Map<String, String> mergedParams = new HashMap<>();
            String existingQuery = uri.getQuery();
            if (existingQuery != null && !existingQuery.isEmpty()) {
                for (String param : existingQuery.split("&")) {
                    // 跳过空参数
                    if (param.isEmpty()) {
                        continue;
                    }

                    String[] keyValue = param.split("=", 2);
                    String key = keyValue[0];
                    String value = keyValue.length == 2 ? keyValue[1] : "";

                    // 对键和值进行 URL 解码
                    key = URLDecoder.decode(key, StandardCharsets.UTF_8);
                    value = URLDecoder.decode(value, StandardCharsets.UTF_8);

                    mergedParams.put(key, value);
                }
            }

            // 合并附加查询参数，附加参数优先
            mergedParams.putAll(queryParams);

            // 构建查询字符串
            if (!mergedParams.isEmpty()) {
                String queryString =
                        mergedParams.entrySet().stream()
                                .map(
                                        e ->
                                                URLEncoder.encode(
                                                                e.getKey(), StandardCharsets.UTF_8)
                                                        + "="
                                                        + URLEncoder.encode(
                                                                e.getValue(),
                                                                StandardCharsets.UTF_8))
                                .collect(Collectors.joining("&"));
                endpoint += "?" + queryString;
            }

            return endpoint;
        }
    }

    private static class SseTransportConfig extends HttpTransportConfig {
        private HttpClientSseClientTransport.Builder clientTransportBuilder = null;
        private Consumer<HttpClient.Builder> httpClientCustomizer = null;

        public SseTransportConfig(String url) {
            super(url);
        }

        public void clientTransportBuilder(
                HttpClientSseClientTransport.Builder clientTransportBuilder) {
            this.clientTransportBuilder = clientTransportBuilder;
        }

        public void customizeHttpClient(Consumer<HttpClient.Builder> customizer) {
            this.httpClientCustomizer = customizer;
        }

        @Override
        public McpClientTransport createTransport() {
            if (clientTransportBuilder == null) {
                clientTransportBuilder = HttpClientSseClientTransport.builder(url);
            }

            // 若提供 HTTP 客户端定制器则应用
            if (httpClientCustomizer != null) {
                clientTransportBuilder.customizeClient(httpClientCustomizer);
            }

            clientTransportBuilder.sseEndpoint(extractEndpoint());

            McpSyncHttpClientRequestCustomizer effectiveCustomizer =
                    composeRequestCustomizer(headers, httpRequestCustomizer);
            if (effectiveCustomizer != null) {
                clientTransportBuilder.httpRequestCustomizer(effectiveCustomizer);
            }

            return clientTransportBuilder.build();
        }
    }

    private static class StreamableHttpTransportConfig extends HttpTransportConfig {
        private HttpClientStreamableHttpTransport.Builder clientTransportBuilder = null;
        private Consumer<HttpClient.Builder> httpClientCustomizer = null;

        public StreamableHttpTransportConfig(String url) {
            super(url);
        }

        public void clientTransportBuilder(
                HttpClientStreamableHttpTransport.Builder clientTransportBuilder) {
            this.clientTransportBuilder = clientTransportBuilder;
        }

        public void customizeHttpClient(Consumer<HttpClient.Builder> customizer) {
            this.httpClientCustomizer = customizer;
        }

        @Override
        public McpClientTransport createTransport() {
            if (clientTransportBuilder == null) {
                clientTransportBuilder = HttpClientStreamableHttpTransport.builder(url);
            }

            // 若提供 HTTP 客户端定制器则应用
            if (httpClientCustomizer != null) {
                clientTransportBuilder.customizeClient(httpClientCustomizer);
            }

            clientTransportBuilder.endpoint(extractEndpoint());

            McpSyncHttpClientRequestCustomizer effectiveCustomizer =
                    composeRequestCustomizer(headers, httpRequestCustomizer);
            if (effectiveCustomizer != null) {
                clientTransportBuilder.httpRequestCustomizer(effectiveCustomizer);
            }

            return clientTransportBuilder.build();
        }
    }

    /**
     * 将静态请求头配置与可选逐请求定制器合并为一个定制器。二者均未配置时返回 null。
     */
    private static McpSyncHttpClientRequestCustomizer composeRequestCustomizer(
            Map<String, String> headers, McpSyncHttpClientRequestCustomizer delegate) {
        boolean hasHeaders = headers != null && !headers.isEmpty();
        if (!hasHeaders && delegate == null) {
            return null;
        }
        return (builder, method, uri, body, ctx) -> {
            if (hasHeaders) {
                headers.forEach(builder::header);
            }
            if (delegate != null) {
                delegate.customize(builder, method, uri, body, ctx);
            }
        };
    }
}
