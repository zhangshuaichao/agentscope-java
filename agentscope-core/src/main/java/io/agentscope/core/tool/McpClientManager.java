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
package io.agentscope.core.tool;

import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.core.tool.mcp.McpTool;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 管理 MCP（模型上下文协议）客户端的注册和生命周期。处理客户端初始化、工具注册和清理。
 */
class McpClientManager {

    private static final Logger logger = LoggerFactory.getLogger(McpClientManager.class);

    private final Map<String, McpClientWrapper> mcpClients = new ConcurrentHashMap<>();
    private final ToolRegistry toolRegistry;
    private final ToolGroupManager groupManager;
    private final ToolRegistrationCallback registrationCallback;

    /**
     * 工具注册回调接口。
     */
    @FunctionalInterface
    interface ToolRegistrationCallback {
        void registerAgentToolWithMcpClient(
                AgentTool tool,
                String groupName,
                String mcpClientName,
                Map<String, Object> presetParameters);
    }

    McpClientManager(
            ToolRegistry toolRegistry,
            ToolGroupManager groupManager,
            ToolRegistrationCallback registrationCallback) {
        this.toolRegistry = toolRegistry;
        this.groupManager = groupManager;
        this.registrationCallback = registrationCallback;
    }

    /**
     * 注册 MCP 客户端及其全部工具。
     *
     * @param mcpClientWrapper MCP 客户端包装器
     * @return 注册完成时完成的 Mono
     */
    Mono<Void> registerMcpClient(McpClientWrapper mcpClientWrapper) {
        return registerMcpClient(mcpClientWrapper, null, null, null);
    }

    /**
     * 注册 MCP 客户端并筛选工具。
     *
     * @param mcpClientWrapper MCP 客户端包装器
     * @param enableTools 要启用的工具名称列表（null 表示全部启用）
     * @return 注册完成时完成的 Mono
     */
    Mono<Void> registerMcpClient(McpClientWrapper mcpClientWrapper, List<String> enableTools) {
        return registerMcpClient(mcpClientWrapper, enableTools, null, null);
    }

    /**
     * 注册 MCP 客户端并筛选工具。
     *
     * @param mcpClientWrapper MCP 客户端包装器
     * @param enableTools 要启用的工具名称列表（null 表示全部启用）
     * @param disableTools 要禁用的工具名称列表（null 表示均不禁用）
     * @return 注册完成时完成的 Mono
     */
    Mono<Void> registerMcpClient(
            McpClientWrapper mcpClientWrapper,
            List<String> enableTools,
            List<String> disableTools) {
        return registerMcpClient(mcpClientWrapper, enableTools, disableTools, null);
    }

    /**
     * 注册 MCP 客户端，筛选工具并分配工具组。
     *
     * @param mcpClientWrapper MCP 客户端包装器
     * @param enableTools 要启用的工具名称列表（null 表示全部启用）
     * @param disableTools 要禁用的工具名称列表（null 表示均不禁用）
     * @param groupName MCP 工具所属的组名
     * @return 注册完成时完成的 Mono
     */
    Mono<Void> registerMcpClient(
            McpClientWrapper mcpClientWrapper,
            List<String> enableTools,
            List<String> disableTools,
            String groupName) {
        return registerMcpClient(mcpClientWrapper, enableTools, disableTools, groupName, null);
    }

    /**
     * 注册 MCP 客户端，筛选工具、分配工具组并设置预设参数。
     *
     * @param mcpClientWrapper MCP 客户端包装器
     * @param enableTools 要启用的工具名称列表（null 表示全部启用）
     * @param disableTools 要禁用的工具名称列表（null 表示均不禁用）
     * @param groupName MCP 工具所属的组名
     * @param presetParametersMapping 工具名称到该工具预设参数的映射
     * @return 注册完成时完成的 Mono
     */
    Mono<Void> registerMcpClient(
            McpClientWrapper mcpClientWrapper,
            List<String> enableTools,
            List<String> disableTools,
            String groupName,
            Map<String, Map<String, Object>> presetParametersMapping) {
        return registerMcpClient(
                mcpClientWrapper,
                enableTools,
                disableTools,
                groupName,
                presetParametersMapping,
                "");
    }

    Mono<Void> registerMcpClient(
            McpClientWrapper mcpClientWrapper,
            List<String> enableTools,
            List<String> disableTools,
            String groupName,
            Map<String, Map<String, Object>> presetParametersMapping,
            String toolNamePrefix) {
        return registerMcpClient(
                mcpClientWrapper,
                enableTools,
                disableTools,
                groupName,
                presetParametersMapping,
                toolNamePrefix,
                null);
    }

    /**
     * 注册 MCP 客户端，全面控制工具筛选、分组、预设参数、命名和元数据传播。
     *
     * @param mcpClientWrapper MCP 客户端包装器
     * @param enableTools 要启用的工具名称列表（null 表示全部启用）
     * @param disableTools 要禁用的工具名称列表（null 表示均不禁用）
     * @param groupName MCP 工具所属的组名
     * @param presetParametersMapping 工具名称到该工具预设参数的映射
     * @param toolNamePrefix 面向模型的工具名称的可选命名空间前缀
     * @param propagateMetaOverride 已注册工具元数据传播的注册级默认值；{@code null} 表示 {@code true}（调用时仍实时应用包装器的连接级开关）
     * @return 注册完成时完成的 Mono
     */
    Mono<Void> registerMcpClient(
            McpClientWrapper mcpClientWrapper,
            List<String> enableTools,
            List<String> disableTools,
            String groupName,
            Map<String, Map<String, Object>> presetParametersMapping,
            String toolNamePrefix,
            Boolean propagateMetaOverride) {
        return registerMcpClient(
                mcpClientWrapper,
                enableTools,
                disableTools,
                groupName,
                presetParametersMapping,
                toolNamePrefix,
                propagateMetaOverride,
                null);
    }

    /**
     * 注册 MCP 客户端，全面控制工具筛选、分组、预设参数、命名和逐工具元数据传播。
     *
     * @param mcpClientWrapper MCP 客户端包装器
     * @param enableTools 要启用的工具名称列表（null 表示全部启用）
     * @param disableTools 要禁用的工具名称列表（null 表示均不禁用）
     * @param groupName MCP 工具所属的组名
     * @param presetParametersMapping 工具名称到该工具预设参数的映射
     * @param toolNamePrefix 面向模型的工具名称的可选命名空间前缀
     * @param propagateMetaOverride 已注册工具元数据传播的注册级默认值；{@code null} 表示 {@code true}
     * @param toolPropagateMetaOverrides 以远端 MCP 工具名称（添加 {@code toolNamePrefix} 之前）为键的逐工具元数据传播覆盖值，优先于 {@code propagateMetaOverride}。若键不匹配此客户端实际注册的工具（未知名称、远端重命名或被 {@code enableTools}/{@code disableTools} 筛除），注册将抛出 {@link IllegalArgumentException}，避免抑制传播的覆盖配置被静默丢失
     *
     * <p>任何注册失败（包括上述未知工具检查）都会尽力关闭客户端：它已经初始化，但尚未纳入本管理器，其他代码无法释放底层连接（stdio 传输还拥有启动的子进程）。注册失败后不可复用此包装器。
     *
     * @return 注册完成时完成的 Mono
     */
    Mono<Void> registerMcpClient(
            McpClientWrapper mcpClientWrapper,
            List<String> enableTools,
            List<String> disableTools,
            String groupName,
            Map<String, Map<String, Object>> presetParametersMapping,
            String toolNamePrefix,
            Boolean propagateMetaOverride,
            Map<String, Boolean> toolPropagateMetaOverrides) {
        if (mcpClientWrapper == null) {
            return Mono.error(new IllegalArgumentException("MCP client wrapper cannot be null"));
        }

        // 指定工具组时，验证该组存在
        if (groupName != null) {
            try {
                groupManager.validateGroupExists(groupName);
            } catch (IllegalArgumentException e) {
                return Mono.error(e);
            }
        }

        logger.info("Registering MCP client: {}", mcpClientWrapper.getName());

        return mcpClientWrapper
                .initialize()
                .then(Mono.defer(mcpClientWrapper::listTools))
                .flatMapMany(
                        tools -> {
                            // 逐工具 propagateMeta 覆盖配置未匹配此客户端实际注册的工具时明确报错
                            // （可能是未知名称、远端重命名，
                            // 或被启用/禁用列表筛除）。
                            // 此功能用于避免向不可信服务器发送元数据，
                            // 因此抑制传播的覆盖配置绝不能被静默丢失。
                            if (toolPropagateMetaOverrides != null
                                    && !toolPropagateMetaOverrides.isEmpty()) {
                                Set<String> registeredNames =
                                        tools.stream()
                                                .map(tool -> tool.name())
                                                .filter(
                                                        toolName ->
                                                                shouldRegisterTool(
                                                                        toolName,
                                                                        enableTools,
                                                                        disableTools))
                                                .collect(Collectors.toSet());
                                // 使用 TreeSet，使面向用户的错误消息中
                                // 被拒绝的名称顺序确定，且便于复制。
                                Set<String> unknown =
                                        new TreeSet<>(toolPropagateMetaOverrides.keySet());
                                unknown.removeAll(registeredNames);
                                if (!unknown.isEmpty()) {
                                    return Flux.error(
                                            new IllegalArgumentException(
                                                    "Unknown MCP tool(s) in propagateMeta"
                                                            + " override for client '"
                                                            + mcpClientWrapper.getName()
                                                            + "': "
                                                            + unknown));
                                }
                            }
                            return Flux.fromIterable(tools);
                        })
                .filter(tool -> shouldRegisterTool(tool.name(), enableTools, disableTools))
                .doOnNext(
                        mcpTool -> {
                            logger.debug(
                                    "Registering MCP tool: {} from client {} into group {}",
                                    mcpTool.name(),
                                    mcpClientWrapper.getName(),
                                    groupName);

                            // 获取当前工具的预设参数
                            Map<String, Object> toolPresetParams =
                                    presetParametersMapping != null
                                            ? presetParametersMapping.get(mcpTool.name())
                                            : null;

                            boolean readOnly =
                                    mcpTool.annotations() != null
                                            && Boolean.TRUE.equals(
                                                    mcpTool.annotations().readOnlyHint());

                            McpTool agentTool =
                                    new McpTool(
                                            toolNamePrefix + mcpTool.name(),
                                            mcpTool.name(),
                                            mcpTool.description() != null
                                                    ? mcpTool.description()
                                                    : "",
                                            McpTool.convertMcpSchemaToParameters(
                                                    mcpTool.inputSchema(),
                                                    toolPresetParams != null
                                                            ? toolPresetParams.keySet()
                                                            : Collections.emptySet()),
                                            mcpTool.outputSchema() != null
                                                    ? new ConcurrentHashMap<>(
                                                            mcpTool.outputSchema())
                                                    : null,
                                            mcpClientWrapper,
                                            /*
                                             * presetArguments 已由上游 RegisteredToolFunction 处理
                                             */ null,
                                            mcpClientWrapper.getName(),
                                            readOnly,
                                            McpTool.resolveDisplayTitle(mcpTool));

                            // 逐工具元数据传播限制在注册时解析，
                            // 优先级为：逐工具覆盖值 > 注册默认值 >
                            // true。此处不捕获连接级包装器开关；
                            // McpTool 每次调用都实时读取该开关（逻辑与），因此之后禁用
                            // 连接时仍能立即停止元数据传播。
                            Boolean perToolOverride =
                                    toolPropagateMetaOverrides != null
                                            ? toolPropagateMetaOverrides.get(mcpTool.name())
                                            : null;
                            boolean propagateMeta =
                                    perToolOverride != null
                                            ? perToolOverride
                                            : propagateMetaOverride != null
                                                    ? propagateMetaOverride
                                                    : true;
                            agentTool.setPropagateMeta(propagateMeta);

                            // 通过回调注册工具组、MCP 客户端名称和预设参数
                            // 并完成工具注册
                            registrationCallback.registerAgentToolWithMcpClient(
                                    agentTool,
                                    groupName,
                                    mcpClientWrapper.getName(),
                                    toolPresetParams);
                        })
                .then()
                .doOnSuccess(
                        v -> {
                            mcpClients.put(mcpClientWrapper.getName(), mcpClientWrapper);
                            logger.info(
                                    "MCP client '{}' registered successfully",
                                    mcpClientWrapper.getName());
                        })
                .doOnError(
                        e ->
                                logger.error(
                                        "Failed to register MCP client: {}",
                                        mcpClientWrapper.getName(),
                                        e))
                .doOnError(
                        e -> {
                            // 客户端已在上方初始化，但仅在成功后才加入 mcpClients，
                            // 因此失败时其他代码无法访问或关闭它
                            // （stdio 传输拥有启动的子进程）。尽力执行
                            // 清理，且不得掩盖原始错误。
                            try {
                                mcpClientWrapper.close();
                            } catch (RuntimeException cleanupEx) {
                                logger.debug(
                                        "Failed to close MCP client '{}' after a failed"
                                                + " registration",
                                        mcpClientWrapper.getName(),
                                        cleanupEx);
                            }
                        });
    }

    /**
     * 移除 MCP 客户端及其全部工具。
     *
     * @param mcpClientName 要移除的 MCP 客户端名称
     * @return 移除完成时完成的 Mono
     */
    Mono<Void> removeMcpClient(String mcpClientName) {
        McpClientWrapper wrapper = mcpClients.remove(mcpClientName);
        if (wrapper == null) {
            logger.warn("MCP client not found: {}", mcpClientName);
            return Mono.empty();
        }

        logger.info("Removing MCP client: {}", mcpClientName);

        // 移除此 MCP 客户端的全部工具
        List<String> toolsToRemove =
                toolRegistry.getAllRegisteredTools().values().stream()
                        .filter(reg -> mcpClientName.equals(reg.getMcpClientName()))
                        .map(reg -> reg.getTool().getName())
                        .collect(Collectors.toList());

        toolsToRemove.forEach(
                toolName -> {
                    toolRegistry.removeTool(toolName);
                    logger.debug("Removed MCP tool: {}", toolName);
                });

        return Mono.fromRunnable(wrapper::close)
                .then()
                .doOnSuccess(
                        v -> logger.info("MCP client '{}' removed successfully", mcpClientName));
    }

    /**
     * 获取全部已注册 MCP 客户端名称。
     *
     * @return 客户端名称集合，永不为 null，但可以为空
     */
    Set<String> getMcpClientNames() {
        return new HashSet<>(mcpClients.keySet());
    }

    /**
     * 按名称获取 MCP 客户端包装器。
     *
     * @param name MCP 客户端名称
     * @return 客户端包装器；未找到时为 null
     */
    McpClientWrapper getMcpClient(String name) {
        return mcpClients.get(name);
    }

    /**
     * 根据启用/禁用列表判断是否注册工具。
     *
     * @param toolName 工具名称
     * @param enableTools 要启用的工具列表（null 表示全部），优先于 disableTools
     * @param disableTools 要禁用的工具列表（null 表示均不禁用）
     * @return 应注册该工具时为 true
     */
    private boolean shouldRegisterTool(
            String toolName, List<String> enableTools, List<String> disableTools) {
        // 默认注册全部工具
        boolean result = true;

        if (disableTools != null && !disableTools.isEmpty()) {
            result = !disableTools.contains(toolName);
        }

        if (enableTools != null && !enableTools.isEmpty()) {
            result = enableTools.contains(toolName);
        }

        return result;
    }
}
