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

import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import reactor.core.publisher.Mono;

/**
 * MCP（模型上下文协议）客户端抽象包装器，管理连接生命周期，并为同步和异步实现提供统一接口。
 *
 * <p>负责：
 * <ul>
 * <li>客户端初始化和连接管理</li>
 * <li>工具发现与缓存</li>
 * <li>通过 MCP 协议调用工具</li>
 * <li>关闭时清理资源</li>
 * </ul>
 *
 * @see McpAsyncClientWrapper
 * @see McpSyncClientWrapper
 */
public abstract class McpClientWrapper implements AutoCloseable {

    /**
     * MCP 客户端唯一标识符
     */
    protected final String name;

    /**
     * MCP 服务器可用工具缓存
     */
    protected final Map<String, McpSchema.Tool> cachedTools;

    /**
     * 表示客户端是否已初始化的标志
     */
    protected volatile boolean initialized = false;

    /**
     * 是否向此连接对应的 MCP 服务器传播请求元数据，包括用户 {@link McpMeta} 条目和框架工具调用 ID。
     *
     * <p>为兼容旧行为，默认为 {@code true}。这是每次工具调用都会读取的实时开关，修改会立即影响连接中所有工具，包括此前注册的工具。对于不完全可信的服务器应设为 {@code false}，避免通过网络发送追踪 ID、回调 URL 等内部元数据。
     */
    protected volatile boolean propagateMeta = true;

    /**
     * 构造 MCP 客户端包装器。
     *
     * @param name 客户端唯一标识符
     */
    protected McpClientWrapper(String name) {
        this.name = name;
        this.cachedTools = new ConcurrentHashMap<>();
    }

    /**
     * 获取 MCP 客户端唯一名称。
     *
     * @return 客户端名称
     */
    public String getName() {
        return name;
    }

    /**
     * 检查客户端是否已初始化。
     *
     * @return 已初始化为 true，否则为 false
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * 检查是否向 MCP 服务器传播请求元数据。
     *
     * <p>这是连接级决策：每次调用将此值与逐工具 {@code McpTool.isPropagateMeta()} 限制做逻辑与。因此此处为 {@code false} 时始终优先，停止整个连接的元数据传播。
     *
     * @return 允许在连接请求中包含 {@link McpMeta} 条目及调用 ID 时为 true，否则为 false
     */
    public boolean isPropagateMeta() {
        return propagateMeta;
    }

    /**
     * 配置是否向 MCP 服务器传播请求元数据。
     *
     * <p>实时开关，对此客户端注册的所有工具立即生效，包括此前注册的工具。不完全可信的服务器应禁用，以避免泄漏内部元数据。
     *
     * @param propagateMeta true 表示传播（默认），false 表示从请求中完全省略 {@code meta} 字段
     */
    public void setPropagateMeta(boolean propagateMeta) {
        this.propagateMeta = propagateMeta;
    }

    /**
     * 初始化 MCP 连接并缓存可用工具，必须先调用此方法才能执行工具操作。
     *
     * @return 初始化完成时完成的 Mono
     */
    public abstract Mono<Void> initialize();

    /**
     * 列出 MCP 服务器全部可用工具。
     *
     * @return 发出工具列表的 Mono
     */
    public abstract Mono<List<McpSchema.Tool>> listTools();

    /**
     * 调用 MCP 服务器工具。
     *
     * @param toolName 待调用工具名称
     * @param arguments 工具参数
     * @return 发出调用结果的 Mono
     */
    public abstract Mono<McpSchema.CallToolResult> callTool(
            String toolName, Map<String, Object> arguments);

    /**
     * 调用 MCP 服务器工具。
     *
     * @param toolName 待调用工具名称
     * @param arguments 工具参数
     * @param meta 工具元数据
     * @return 发出调用结果的 Mono
     */
    public abstract Mono<McpSchema.CallToolResult> callTool(
            String toolName, Map<String, Object> arguments, Map<String, Object> meta);

    /**
     * 按名称获取缓存的工具定义。
     *
     * @param toolName 工具名称
     * @return 工具定义；未找到时为 null
     */
    public McpSchema.Tool getCachedTool(String toolName) {
        return cachedTools.get(toolName);
    }

    /**
     * 关闭 MCP 客户端并释放所有资源。方法幂等，可安全地重复调用。
     */
    @Override
    public abstract void close();
}
