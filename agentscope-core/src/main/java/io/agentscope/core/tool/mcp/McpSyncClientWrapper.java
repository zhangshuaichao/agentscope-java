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

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 将同步 MCP 客户端的阻塞调用转换为响应式 Mono 的包装器。委托给 {@link McpSyncClient}，将阻塞操作放到 Reactor boundedElastic 调度器，避免阻塞事件循环。
 *
 * <p>使用示例：
 * <pre>{@code
 * McpSyncClient client = ... // created via McpClient.sync()
 * McpSyncClientWrapper wrapper = new McpSyncClientWrapper("my-mcp", client);
 * wrapper.initialize()
 *     .then(wrapper.callTool("tool_name", Map.of("arg1", "value1")))
 *     .subscribe(result -> System.out.println(result));
 * }</pre>
 */
public class McpSyncClientWrapper extends McpClientWrapper {

    private static final Logger logger = LoggerFactory.getLogger(McpSyncClientWrapper.class);

    private final McpSyncClient client;

    /**
     * 构造同步 MCP 客户端包装器。
     *
     * @param name 客户端唯一标识符
     * @param client 底层同步 MCP 客户端
     */
    public McpSyncClientWrapper(String name, McpSyncClient client) {
        super(name);
        this.client = client;
    }

    /**
     * 初始化同步 MCP 连接并缓存可用工具。
     *
     * <p>将阻塞操作包装为在 boundedElastic 上运行的 Mono，避免阻塞事件循环。已初始化时立即返回，不重复初始化。
     *
     * @return 初始化完成时完成的 Mono
     */
    @Override
    public Mono<Void> initialize() {
        if (initialized) {
            return Mono.empty();
        }

        logger.info("Initializing MCP sync client: {}", name);

        return Mono.fromCallable(
                        () -> {
                            // 初始化客户端（阻塞）
                            McpSchema.InitializeResult result = client.initialize();
                            logger.debug(
                                    "MCP client '{}' initialized with server: {}",
                                    name,
                                    result.serverInfo().name());

                            // 列出并缓存工具（阻塞）
                            McpSchema.ListToolsResult toolsResult = client.listTools();
                            logger.debug(
                                    "MCP client '{}' discovered {} tools",
                                    name,
                                    toolsResult.tools().size());

                            toolsResult.tools().forEach(tool -> cachedTools.put(tool.name(), tool));

                            initialized = true;
                            return null;
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .doOnError(e -> logger.error("Failed to initialize MCP client: {}", name, e))
                .then();
    }

    /**
     * 列出 MCP 服务器全部可用工具。
     *
     * <p>将阻塞的同步 listTools 调用包装为响应式 Mono，调用前必须初始化客户端。
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

        return Mono.fromCallable(() -> client.listTools().tools())
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 调用 MCP 服务器工具，将阻塞调用包装为响应式 Mono。
     *
     * <p>同步 callTool 操作在 boundedElastic 上运行，调用前必须初始化客户端。
     *
     * @param toolName 待调用工具名称
     * @param arguments 工具参数
     * @return 发出工具调用结果的 Mono（可包含错误信息）
     * @throws IllegalStateException 当客户端未初始化时
     */
    @Override
    public Mono<McpSchema.CallToolResult> callTool(String toolName, Map<String, Object> arguments) {
        return callTool(toolName, arguments, null);
    }

    /**
     * 调用 MCP 服务器工具，将阻塞调用包装为响应式 Mono。
     *
     * <p>同步 callTool 操作在 boundedElastic 上运行，调用前必须初始化客户端。
     *
     * @param toolName 待调用工具名称
     * @param arguments 工具参数
     * @param meta 附加工具元数据
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

        return Mono.fromCallable(
                        () -> {
                            McpSchema.CallToolRequest request =
                                    new McpSchema.CallToolRequest(toolName, arguments, meta);
                            McpSchema.CallToolResult result = client.callTool(request);

                            if (Boolean.TRUE.equals(result.isError())) {
                                logger.warn(
                                        "MCP tool '{}' returned error: {}",
                                        toolName,
                                        result.content());
                            } else {
                                logger.debug("MCP tool '{}' completed successfully", toolName);
                            }

                            return result;
                        })
                .subscribeOn(Schedulers.boundedElastic())
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
            logger.info("Closing MCP sync client: {}", name);
            try {
                client.closeGracefully();
                logger.debug("MCP client '{}' closed", name);
            } catch (Exception e) {
                logger.error("Exception during MCP client close", e);
                client.close();
            }
        }
        initialized = false;
        cachedTools.clear();
    }
}
