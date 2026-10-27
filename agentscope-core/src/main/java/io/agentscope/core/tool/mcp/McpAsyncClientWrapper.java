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

import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * 使用 Project Reactor 的异步 MCP 客户端包装器，委托给 {@link McpAsyncClient}，提供返回 Mono 的响应式操作。
 *
 * <p>使用示例：
 * <pre>{@code
 * McpAsyncClient client = ... // created via McpClient.async()
 * McpAsyncClientWrapper wrapper = new McpAsyncClientWrapper("my-mcp", client);
 * wrapper.initialize()
 *     .then(wrapper.callTool("tool_name", Map.of("arg1", "value1")))
 *     .subscribe(result -> System.out.println(result));
 * }</pre>
 */
public class McpAsyncClientWrapper extends McpClientWrapper {

    private static final Logger logger = LoggerFactory.getLogger(McpAsyncClientWrapper.class);

    private final McpAsyncClient client;

    /**
     * 构造异步 MCP 客户端包装器。
     *
     * @param name 客户端唯一标识符
     * @param client 底层异步 MCP 客户端
     */
    public McpAsyncClientWrapper(String name, McpAsyncClient client) {
        super(name);
        this.client = client;
    }

    /**
     * 初始化异步 MCP 连接并缓存可用工具。
     *
     * <p>连接服务器、发现工具并缓存供后续使用。已初始化时立即返回，不重复初始化。
     *
     * @return 初始化完成时完成的 Mono
     */
    @Override
    public Mono<Void> initialize() {
        if (initialized) {
            return Mono.empty();
        }

        logger.info("Initializing MCP async client: {}", name);

        return client.initialize()
                .doOnSuccess(
                        result ->
                                logger.debug(
                                        "MCP client '{}' initialized with server: {}",
                                        name,
                                        result.serverInfo().name()))
                .then(client.listTools())
                .doOnNext(
                        result -> {
                            logger.debug(
                                    "MCP client '{}' discovered {} tools",
                                    name,
                                    result.tools().size());
                            // 缓存全部工具
                            result.tools().forEach(tool -> cachedTools.put(tool.name(), tool));
                        })
                .doOnSuccess(v -> initialized = true)
                .doOnError(e -> logger.error("Failed to initialize MCP client: {}", name, e))
                .then();
    }

    /**
     * 列出 MCP 服务器的全部可用工具。
     *
     * <p>查询服务器当前工具列表，调用前必须初始化客户端。
     *
     * @return 发出可用工具列表的 Mono
     * @throws IllegalStateException 当客户端未初始化时
     */
    @Override
    public Mono<List<McpSchema.Tool>> listTools() {
        if (!initialized) {
            return Mono.error(
                    new IllegalStateException("MCP client '" + name + "' not initialized"));
        }

        return client.listTools().map(McpSchema.ListToolsResult::tools);
    }

    /**
     * 异步调用 MCP 服务器工具。
     *
     * <p>发送工具调用请求并异步返回结果，调用前必须初始化客户端。
     *
     * @param toolName 待调用工具名称
     * @param arguments 传给工具的参数
     * @return 发出工具调用结果的 Mono（可包含错误信息）
     * @throws IllegalStateException 当客户端未初始化时
     */
    @Override
    public Mono<McpSchema.CallToolResult> callTool(String toolName, Map<String, Object> arguments) {
        return callTool(toolName, arguments, null);
    }

    /**
     * 携带附加元数据异步调用 MCP 服务器工具。
     *
     * <p>发送请求并异步返回结果，调用前必须初始化客户端。
     *
     * @param toolName 待调用工具名称
     * @param arguments 传给工具的参数
     * @param meta 请求附加元数据
     * @return 发出工具调用结果的 Mono（可包含错误信息）
     * @throws IllegalStateException 当客户端未初始化时
     */
    @Override
    public Mono<McpSchema.CallToolResult> callTool(
            String toolName, Map<String, Object> arguments, Map<String, Object> meta) {
        if (!initialized) {
            return Mono.error(
                    new IllegalStateException("MCP client '" + name + "' not initialized"));
        }

        logger.debug("Calling MCP tool '{}' on client '{}'", toolName, name);

        McpSchema.CallToolRequest request =
                new McpSchema.CallToolRequest(toolName, arguments, meta);

        return client.callTool(request)
                .doOnSuccess(
                        result -> {
                            if (Boolean.TRUE.equals(result.isError())) {
                                logger.warn(
                                        "MCP tool '{}' returned error: {}",
                                        toolName,
                                        result.content());
                            } else {
                                logger.debug("MCP tool '{}' completed successfully", toolName);
                            }
                        })
                .doOnError(
                        e ->
                                logger.error(
                                        "Failed to call MCP tool '{}': {}",
                                        toolName,
                                        e.getMessage()));
    }

    /**
     * 关闭 MCP 客户端连接并释放所有资源。
     *
     * <p>先尝试优雅关闭，失败时强制关闭。方法幂等，可安全地重复调用。
     */
    @Override
    public void close() {
        if (client != null) {
            logger.info("Closing MCP async client: {}", name);
            try {
                client.closeGracefully()
                        .doOnSuccess(v -> logger.debug("MCP client '{}' closed", name))
                        .doOnError(e -> logger.error("Error closing MCP client '{}'", name, e))
                        .block();
            } catch (Exception e) {
                logger.error("Exception during MCP client close", e);
                client.close();
            }
        }
        initialized = false;
        cachedTools.clear();
    }
}
