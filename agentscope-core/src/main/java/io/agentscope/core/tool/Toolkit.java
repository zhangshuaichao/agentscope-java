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

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.core.tool.subagent.SubAgentConfig;
import io.agentscope.core.tool.subagent.SubAgentProvider;
import io.agentscope.core.tool.subagent.SubAgentTool;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Toolkit 管理智能体工具的注册、检索和执行，作为门面将具体职责委托给专门管理器。
 *
 * <p>【学习提示】按“注册期”和“调用期”分开阅读：注册期把 Java 方法包装为工具并生成参数模式；
 * 调用期则按名称解析工具、验证参数、执行并转换结果。模型看到的是模式，不是 Java 方法体。
 * 注册成功只表示工具进入注册表，并不等于模型每轮都能看到它，工具组和请求配置还会筛选可见范围。
 *
 * <p><b>管理器：</b>
 * <ul>
 * <li>ToolRegistry：工具注册和查找</li>
 * <li>ToolGroupManager：工具组增删改查及激活组管理</li>
 * <li>ToolSchemaProvider：结合工具组筛选生成工具模式</li>
 * <li>McpClientManager：MCP 客户端生命周期和工具注册</li>
 * <li>MetaToolFactory：创建动态控制工具组的元工具</li>
 * </ul>
 *
 * <p><b>核心组件：</b>
 * <ul>
 * <li>ToolSchemaGenerator：生成工具参数 JSON 模式</li>
 * <li>ToolMethodInvoker：方法调用和参数转换</li>
 * <li>ToolResultConverter：将方法结果转换为 ToolResultBlock</li>
 * <li>ToolExecutor：带验证的并行或顺序工具执行</li>
 * </ul>
 *
 * <p><b>特性：</b>
 * <ul>
 * <li>通过工具组动态激活工具</li>
 * <li>通过 StateModule 接口管理状态（持久化 activeGroups）</li>
 * <li>通过 reset_equipped_tools 元工具在运行时控制工具组</li>
 * <li>支持外部工具提供方的 MCP（模型上下文协议）客户端</li>
 * </ul>
 */
public class Toolkit {

    private static final Logger logger = LoggerFactory.getLogger(Toolkit.class);

    private final ToolGroupManager groupManager = new ToolGroupManager();
    private final ToolRegistry toolRegistry = new ToolRegistry();
    private final ToolSchemaProvider schemaProvider;
    private final MetaToolFactory metaToolFactory;
    private AgentTool registeredMetaTool;
    private final McpClientManager mcpClientManager;
    private final ToolSchemaGenerator schemaGenerator = new ToolSchemaGenerator();
    private final ToolMethodInvoker methodInvoker;
    private final ToolkitConfig config;
    private final ToolExecutor executor;

    /**
     * 使用默认配置创建 Toolkit（基于 Reactor 并行执行）。
     */
    public Toolkit() {
        this(ToolkitConfig.defaultConfig());
    }

    /**
     * 使用自定义配置创建 Toolkit。
     *
     * @param config 工具集配置（为 null 时使用 defaultConfig()）
     */
    public Toolkit(ToolkitConfig config) {
        this.config = config != null ? config : ToolkitConfig.defaultConfig();
        this.methodInvoker = new ToolMethodInvoker(new DefaultToolResultConverter());
        this.schemaProvider = new ToolSchemaProvider(toolRegistry, groupManager);
        this.metaToolFactory = new MetaToolFactory(groupManager, toolRegistry);
        this.mcpClientManager =
                new McpClientManager(
                        toolRegistry,
                        groupManager,
                        (tool, groupName, mcpClientName, presetParameters) ->
                                registerAgentTool(
                                        tool, groupName, null, mcpClientName, presetParameters));

        // 按配置创建执行器
        if (config != null && config.hasCustomExecutor()) {
            this.executor =
                    new ToolExecutor(
                            this,
                            toolRegistry,
                            groupManager,
                            this.config,
                            config.getExecutorService());
        } else {
            this.executor = new ToolExecutor(this, toolRegistry, groupManager, this.config);
        }
    }

    /**
     * 创建用于注册工具的链式构建器，支持可选配置。
     *
     * <p>使用示例：
     * <pre>{@code
     * // 注册工具对象
     * toolkit.registration()
     *     .tool(myToolObject)
     *     .group("myGroup")
     *     .presetParameters(Map.of(
     *         "myTool", Map.of("apiKey", "secret")
     *     ))
     *     .apply();
     *
     * // 注册 MCP 客户端
     * toolkit.registration()
     *     .mcpClient(mcpClientWrapper)
     *     .enableTools(List.of("tool1", "tool2"))
     *     .group("mcpGroup")
     *     .presetParameters(Map.of(
     *         "tool1", Map.of("apiKey", "key1")
     *     ))
     *     .apply();
     * }</pre>
     *
     * @return 新的 ToolRegistration 构建器
     */
    public ToolRegistration registration() {
        return new ToolRegistration(this);
    }

    /**
     * 扫描带 @Tool 注解的方法并注册工具对象。
     * @param toolObject 包含工具方法的对象
     */
    public void registerTool(Object toolObject) {
        registerTool(toolObject, null, null, null);
    }

    /**
     * 内部方法：注册工具对象，配置工具组、扩展模型和预设参数。
     */
    private void registerTool(
            Object toolObject,
            String groupName,
            ExtendedModel extendedModel,
            Map<String, Map<String, Object>> presetParameters) {
        if (toolObject == null) {
            throw new IllegalArgumentException("Tool object cannot be null");
        }

        // 检查对象是否为 AgentTool 实例
        if (toolObject instanceof AgentTool) {
            AgentTool agentTool = (AgentTool) toolObject;
            String toolName = agentTool.getName();
            Map<String, Object> toolPresets =
                    (presetParameters != null && presetParameters.containsKey(toolName))
                            ? presetParameters.get(toolName)
                            : null;
            registerAgentTool(agentTool, groupName, extendedModel, null, toolPresets);
            return;
        }

        Class<?> clazz = toolObject.getClass();
        Method[] methods = clazz.getDeclaredMethods();

        for (Method method : methods) {
            if (method.isAnnotationPresent(Tool.class)) {
                Tool toolAnnotation = method.getAnnotation(Tool.class);
                String toolName =
                        toolAnnotation.name().isEmpty() ? method.getName() : toolAnnotation.name();
                Map<String, Object> toolPresets =
                        (presetParameters != null && presetParameters.containsKey(toolName))
                                ? presetParameters.get(toolName)
                                : null;
                registerToolMethod(toolObject, method, groupName, extendedModel, toolPresets);
            }
        }
    }

    /**
     * 直接注册 AgentTool 实例。
     * @param tool 要注册的 AgentTool
     */
    public void registerAgentTool(AgentTool tool) {
        registerAgentTool(tool, null, null, null, null);
    }

    /**
     * 内部方法：使用包含预设参数的完整元数据注册 AgentTool。
     */
    private void registerAgentTool(
            AgentTool tool,
            String groupName,
            ExtendedModel extendedModel,
            String mcpClientName,
            Map<String, Object> presetParameters) {
        if (tool == null) {
            throw new IllegalArgumentException("AgentTool cannot be null");
        }

        String toolName = tool.getName();

        // 指定工具组时验证组存在
        if (groupName != null) {
            groupManager.validateGroupExists(groupName);
        }

        // 创建带预设参数的注册包装器
        RegisteredToolFunction registered =
                new RegisteredToolFunction(tool, extendedModel, mcpClientName, presetParameters);

        // 注册到 toolRegistry
        toolRegistry.registerTool(toolName, tool, registered);

        // 指定组时加入该组
        if (groupName != null) {
            groupManager.addToolToGroup(groupName, toolName);
        }

        logger.info(
                "Registered tool '{}' in group '{}'",
                toolName,
                groupName != null ? groupName : "ungrouped");
    }

    /**
     * 按名称检索工具。
     *
     * @param name 要检索的工具名称
     * @return AgentTool 实例；未找到时为 null
     */
    public AgentTool getTool(String name) {
        return toolRegistry.getTool(name);
    }

    /**
     * 按名称解析工具，将逐调用 {@link ToolRequestConfig} 与共享注册表组合，不修改任一对象。请求配置中的外部工具优先于后端注册表；{@link ToolMergeMode#EXTERNAL_ONLY} 模式完全隐藏后端。
     *
     * @param name 工具名称
     * @param requestConfig 逐调用请求配置（{@code null} 视为 {@link ToolRequestConfig#NONE}）
     * @return 解析到的工具；未找到或被隐藏时为 {@code null}
     */
    public AgentTool getTool(String name, ToolRequestConfig requestConfig) {
        return (requestConfig != null ? requestConfig : ToolRequestConfig.NONE)
                .resolveTool(name, this.toolRegistry);
    }

    /**
     * 获取全部已注册工具名称。
     *
     * @return 全部工具名称集合（永不为 null，可以为空）
     */
    public Set<String> getToolNames() {
        return toolRegistry.getToolNames();
    }

    // ==================== 外部工具支持 ====================

    /**
     * 仅使用模式定义注册外部工具。
     *
     * <p>外部工具在框架之外执行。模型返回外部工具调用时，框架不执行它，而通过生成原因为 {@link io.agentscope.core.message.GenerateReason#TOOL_SUSPENDED} 的消息将调用返回给用户。
     *
     * <p>使用示例：
     * <pre>{@code
     * ToolSchema schema = ToolSchema.builder()
     *     .name("query_database")
     *     .description("Query external database")
     *     .parameters(Map.of(
     *         "type", "object",
     *         "properties", Map.of("sql", Map.of("type", "string")),
     *         "required", List.of("sql")
     *     ))
     *     .build();
     *
     * toolkit.registerSchema(schema);
     * }</pre>
     *
     * @param schema 包含名称、描述和参数的工具模式
     * @throws NullPointerException 当 schema 为 null 时
     * @see SchemaOnlyTool
     * @see #isExternalTool(String)
     */
    public void registerSchema(ToolSchema schema) {
        registerAgentTool(new SchemaOnlyTool(schema));
    }

    /**
     * 使用模式定义注册多个外部工具。
     *
     * @param schemas 待注册工具模式列表
     * @throws NullPointerException 当 schemas 为 null 时
     * @see #registerSchema(ToolSchema)
     */
    public void registerSchemas(List<ToolSchema> schemas) {
        if (schemas != null) {
            schemas.forEach(this::registerSchema);
        }
    }

    /**
     * 检查工具是否为需要在框架外执行的外部工具。
     *
     * <p>继承 {@link ToolBase} 且返回 {@code isExternalTool() == true} 的工具视为外部工具，例如 {@link SchemaOnlyTool} 或 {@code @Tool(externalTool=true)} 方法。返回 true 时框架跳过执行，通过 {@code TOOL_SUSPENDED} 向用户暴露调用。
     *
     * @param toolName 待检查工具名称
     * @return 外部工具为 true，否则为 false
     */
    public boolean isExternalTool(String toolName) {
        AgentTool tool = getTool(toolName);
        return tool instanceof ToolBase tb && tb.isExternalTool();
    }

    /**
     * 检查 {@code toolName} 在逐调用 {@link ToolRequestConfig} 下是否解析为外部工具。通过 {@link #getTool(String, ToolRequestConfig)} 解析，因此除后端注册的外部工具外，还能识别请求配置中注入的仅模式工具。请求配置为 {@code null} 时只查共享注册表，相当于 {@link #isExternalTool(String)}。
     *
     * @param toolName 待检查工具名称
     * @param requestConfig 逐调用配置（{@code null} 表示仅共享注册表）
     * @return 解析到的工具为外部工具时为 true，否则为 false
     */
    public boolean isExternalTool(String toolName, ToolRequestConfig requestConfig) {
        AgentTool tool = getTool(toolName, requestConfig);
        return tool instanceof ToolBase tb && tb.isExternalTool();
    }

    /**
     * 获取 ToolSchema 工具模式对象，遵循激活工具组的筛选规则。
     *
     * <p><b>旧单会话 API：</b>依据共享激活标志解析。逐调用路径使用 {@link #getToolSchemas(java.util.Collection, ToolRequestConfig)}。
     *
     * @return ToolSchema 对象列表
     */
    public List<ToolSchema> getToolSchemas() {
        return schemaProvider.getToolSchemas();
    }

    /**
     * 根据显式提供的激活组名称筛选工具模式，不依赖工具集共享的逐组激活标志。
     *
     * <p>这是 {@link #getToolSchemas()} 的逐调用、无状态版本。按 {@code (userId, sessionId)} 保存激活组状态的调用方（如 {@code ReActAgent}）使用它从自己的槽位解析模型可见工具，而不是读取并发修改的共享标志。
     *
     * @param activeGroups 本次解析视为激活的组名称
     * @return 对给定组可见的 ToolSchema 列表（包括全部未分组工具）
     */
    public List<ToolSchema> getToolSchemas(Collection<String> activeGroups) {
        return schemaProvider.getToolSchemas(activeGroups);
    }

    /**
     * 无状态组合：先按 {@code activeGroups} 筛选后端注册表，再应用逐调用 {@link ToolRequestConfig}，不修改共享注册表或工具组管理器。
     *
     * <ol>
     * <li>{@link ToolMergeMode#EXTERNAL_ONLY} 模式完全隐藏后端，不删除注册项。
     * <li>删除与外部工具重名的后端模式（外部工具覆盖后端）。
     * <li>追加外部工具模式。
     * </ol>
     *
     * @param activeGroups 本次解析视为激活的组名称
     * @param requestConfig 逐调用请求配置（{@code null} 视为 {@link ToolRequestConfig#NONE}）
     * @return 组合后的工具模式
     */
    public List<ToolSchema> getToolSchemas(
            Collection<String> activeGroups, ToolRequestConfig requestConfig) {
        if (requestConfig == null) {
            requestConfig = ToolRequestConfig.NONE;
        }
        boolean hideBackend = requestConfig.hidesBackend();

        List<ToolSchema> schemas = new ArrayList<>();
        if (!hideBackend) {
            for (ToolSchema schema : schemaProvider.getToolSchemas(activeGroups)) {
                if (!requestConfig.overrides(schema.getName())) {
                    schemas.add(schema);
                }
            }
        }
        for (SchemaOnlyTool tool : requestConfig.externalTools().values()) {
            schemas.add(
                    ToolSchema.builder()
                            .name(tool.getName())
                            .description(tool.getDescription())
                            .parameters(tool.getParameters())
                            .strict(tool.getStrict())
                            .deferLoading(tool.getDeferLoading())
                            .outputSchema(tool.getOutputSchema())
                            .build());
        }
        return schemas;
    }

    /**
     * 注册工具方法，配置工具组、扩展模型和预设参数。
     *
     * <p>构建 {@link ReflectiveFunctionTool}（{@link ToolBase} 子类），使已注册工具与 MCP 和内置工具一样参与权限评估、{@link ToolExecutor} 安全标志机制及智能体待确认流程。
     */
    private void registerToolMethod(
            Object toolObject,
            Method method,
            String groupName,
            ExtendedModel extendedModel,
            Map<String, Object> presetParameters) {
        Tool toolAnnotation = method.getAnnotation(Tool.class);

        String toolName =
                !toolAnnotation.name().isEmpty() ? toolAnnotation.name() : method.getName();
        String description =
                !toolAnnotation.description().isEmpty()
                        ? toolAnnotation.description()
                        : "Tool: " + toolName;

        // 从注解解析自定义转换器
        ToolResultConverter customConverter = parseConverterFromAnnotation(toolAnnotation);

        Set<String> presetParamNames =
                presetParameters != null ? presetParameters.keySet() : Collections.emptySet();

        AgentTool tool =
                ReflectiveFunctionTool.create(
                        toolObject,
                        method,
                        toolAnnotation,
                        toolName,
                        description,
                        schemaGenerator,
                        methodInvoker,
                        customConverter,
                        presetParamNames);

        registerAgentTool(tool, groupName, extendedModel, null, presetParameters);
    }

    /**
     * 从 @Tool 注解解析并实例化转换器。
     *
     * @param toolAnnotation Tool 注解
     * @return ToolResultConverter 实例；null 表示使用默认值
     */
    private ToolResultConverter parseConverterFromAnnotation(Tool toolAnnotation) {
        if (toolAnnotation == null) {
            return null;
        }

        try {
            Class<? extends ToolResultConverter> converterClass = toolAnnotation.converter();
            // 显式设置为 DefaultToolResultConverter 时返回 null，以使用默认实现
            if (converterClass == DefaultToolResultConverter.class) {
                return null;
            }
            return instantiateConverter(converterClass);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create converter from @Tool annotation", e);
        }
    }

    /**
     * 解析合适构造器并实例化转换器类，依次尝试：1）无参构造器；2）接受 ObjectMapper 的构造器。
     *
     * @param clazz 待实例化的转换器类
     * @return 新的转换器实例
     */
    private ToolResultConverter instantiateConverter(Class<? extends ToolResultConverter> clazz)
            throws Exception {
        // 先尝试无参构造器
        try {
            return clazz.getDeclaredConstructor().newInstance();
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(
                    "Converter " + clazz.getName() + " must have either a no-arg constructor");
        }
    }

    /**
     * 设置流式工具响应的数据块回调。
     *
     * <p>深复制工具集时保留该回调，工具通过 ToolEmitter 发出进度时调用。ReActAgent 使用工具集时，除了框架内部回调，也会调用用户回调。
     *
     * @param callback 工具通过 ToolEmitter 发出数据块时调用的回调
     */
    public void setChunkCallback(BiConsumer<ToolUseBlock, ToolResultBlock> callback) {
        executor.setChunkCallback(callback);
    }

    /**
     * 使用给定参数执行工具。
     *
     * <p>使用示例：
     *
     * <pre>{@code
     * // 简单调用
     * ToolCallParam param = ToolCallParam.builder()
     *     .toolUseBlock(toolCall)
     *     .build();
     * toolkit.callTool(param);
     *
     * // 传入智能体和上下文
     * ToolCallParam param = ToolCallParam.builder()
     *     .toolUseBlock(toolCall)
     *     .agent(agent)
     *     .context(context)
     *     .build();
     * toolkit.callTool(param);
     * }</pre>
     *
     * @param param 包含执行信息的工具调用参数
     * @return 包含执行结果的 Mono
     */
    public Mono<ToolResultBlock> callTool(ToolCallParam param) {
        return executor.executeWithInfrastructure(param, resolveToolExecutionConfig(null));
    }

    /**
     * 使用逐调用执行配置执行工具。
     *
     * <p>配置按 {@code per-call > toolkit-level > system default} 合并。配置为 {@code null} 时等同于 {@link #callTool(ToolCallParam)}。
     *
     * @param param 包含执行信息的工具调用参数
     * @param perCallConfig 逐调用执行配置，可为 {@code null}
     * @return 包含执行结果的 Mono
     */
    public Mono<ToolResultBlock> callTool(ToolCallParam param, ExecutionConfig perCallConfig) {
        return executor.executeWithInfrastructure(param, resolveToolExecutionConfig(perCallConfig));
    }

    private ExecutionConfig resolveToolExecutionConfig(ExecutionConfig perCallConfig) {
        ExecutionConfig base =
                ExecutionConfig.mergeConfigs(
                        config.getExecutionConfig(), ExecutionConfig.TOOL_DEFAULTS);
        return perCallConfig == null ? base : ExecutionConfig.mergeConfigs(perCallConfig, base);
    }

    /**
     * 使用智能体级上下文异步执行多个工具（供 ReActAgent 内部使用）。
     *
     * <p><b>内部 API，不建议外部使用。</b>主要供 {@link io.agentscope.core.ReActAgent} 等框架组件使用。
     *
     * <p>按工具集配置控制并行或顺序执行，应用多层执行配置（超时、重试），并在执行时合并智能体上下文与工具集默认上下文。
     *
     * @param toolCalls 待执行工具调用列表
     * @param agentExecutionConfig 智能体级执行配置（可为 null）
     * @param agent 发起调用的智能体（可为 null）
     * @param agentRuntimeContext 智能体级运行时上下文（可为 null）
     * @return 包含工具响应列表的 Mono
     */
    public Mono<List<ToolResultBlock>> callTools(
            List<ToolUseBlock> toolCalls,
            ExecutionConfig agentExecutionConfig,
            Agent agent,
            RuntimeContext agentRuntimeContext) {
        return callTools(toolCalls, agentExecutionConfig, agent, agentRuntimeContext, null);
    }

    /**
     * 使用运行时上下文中的请求配置及逐调用数据块回调执行多个工具。
     *
     * @param internalChunkCallback 逐调用内部数据块回调（可为 {@code null}）
     */
    public Mono<List<ToolResultBlock>> callTools(
            List<ToolUseBlock> toolCalls,
            ExecutionConfig agentExecutionConfig,
            Agent agent,
            RuntimeContext agentRuntimeContext,
            BiConsumer<ToolUseBlock, ToolResultBlock> internalChunkCallback) {
        return callTools(
                toolCalls,
                agentExecutionConfig,
                agent,
                agentRuntimeContext,
                agentRuntimeContext == null
                        ? ToolRequestConfig.NONE
                        : agentRuntimeContext.getToolRequestConfig(),
                internalChunkCallback);
    }

    /**
     * 使用逐调用 {@link ToolRequestConfig} 和内部数据块回调执行多个工具，并将二者传入每次单工具执行。
     */
    public Mono<List<ToolResultBlock>> callTools(
            List<ToolUseBlock> toolCalls,
            ExecutionConfig agentExecutionConfig,
            Agent agent,
            RuntimeContext agentRuntimeContext,
            ToolRequestConfig requestConfig,
            BiConsumer<ToolUseBlock, ToolResultBlock> internalChunkCallback) {
        ToolExecutionCallback callback =
                internalChunkCallback == null
                        ? null
                        : new ToolExecutionCallback() {
                            @Override
                            public void onProgress(ToolUseBlock call, ToolResultBlock chunk) {
                                internalChunkCallback.accept(call, chunk);
                            }
                        };
        return callToolsWithCallback(
                toolCalls,
                agentExecutionConfig,
                agent,
                agentRuntimeContext,
                requestConfig,
                callback);
    }

    /**
     * 执行批次，在按序聚合前通知开始、进度和最终结果。返回列表保留声明顺序。{@link ToolExecutionCallback#onStart} 在执行器开始每次调用时触发（顺序批次中即轮到该调用时），结果通知可按完成顺序到达。
     */
    public Mono<List<ToolResultBlock>> callToolsWithCallback(
            List<ToolUseBlock> toolCalls,
            ExecutionConfig agentExecutionConfig,
            Agent agent,
            RuntimeContext agentRuntimeContext,
            ToolRequestConfig requestConfig,
            ToolExecutionCallback executionCallback) {
        ExecutionConfig effectiveConfig = resolveToolExecutionConfig(agentExecutionConfig);

        return executor.executeAll(
                toolCalls,
                config.isParallel(),
                effectiveConfig,
                agent,
                agentRuntimeContext,
                requestConfig != null
                        ? requestConfig
                        : agentRuntimeContext == null
                                ? ToolRequestConfig.NONE
                                : agentRuntimeContext.getToolRequestConfig(),
                executionCallback);
    }

    // ==================== MCP 客户端注册（委托） ====================

    /**
     * 注册 MCP 客户端及其全部工具。
     *
     * <p>需要筛选、工具组或预设参数等复杂注册配置时，使用构建器 API：{@code toolkit.registration().mcpClient(...).apply()}。
     *
     * @param mcpClientWrapper MCP 客户端包装器
     * @return 注册完成时完成的 Mono
     */
    public Mono<Void> registerMcpClient(McpClientWrapper mcpClientWrapper) {
        return mcpClientManager.registerMcpClient(mcpClientWrapper);
    }

    /**
     * 移除 MCP 客户端及其全部工具。
     *
     * @param mcpClientName 待移除的 MCP 客户端名称
     * @return 移除完成时完成的 Mono
     */
    public Mono<Void> removeMcpClient(String mcpClientName) {
        return mcpClientManager.removeMcpClient(mcpClientName);
    }

    /**
     * 释放此工具集注册的客户端；副本不拥有源工具集的客户端。
     */
    public void closeMcpClients() {
        for (String name : mcpClientManager.getMcpClientNames()) {
            try {
                removeMcpClient(name).block();
            } catch (RuntimeException e) {
                logger.warn(
                        "MCP client '{}' cleanup failed ({})", name, e.getClass().getSimpleName());
            }
        }
    }

    // ==================== 工具组管理（委托） ====================

    /**
     * 使用指定激活状态和默认 META 作用域创建工具组。
     *
     * @param groupName 工具组名称
     * @param description 工具组描述
     * @param active 是否默认激活
     * @throws IllegalArgumentException 当组已存在时
     */
    public void createToolGroup(String groupName, String description, boolean active) {
        groupManager.createToolGroup(groupName, description, active);
    }

    /**
     * 使用指定激活状态和作用域创建工具组。
     *
     * @param groupName 工具组名称
     * @param description 工具组描述
     * @param active 是否默认激活
     * @param scope 由元工具管理（{@link ToolGroupScope#META}）或开发者代码管理（{@link ToolGroupScope#EXTERNAL}）
     * @throws IllegalArgumentException 当组已存在时
     */
    public void createToolGroup(
            String groupName, String description, boolean active, ToolGroupScope scope) {
        groupManager.createToolGroup(groupName, description, active, scope);
    }

    /**
     * 创建默认激活、META 作用域的工具组。
     *
     * @param groupName 工具组名称
     * @param description 工具组描述
     * @throws IllegalArgumentException 当组已存在时
     */
    public void createToolGroup(String groupName, String description) {
        groupManager.createToolGroup(groupName, description);
    }

    /**
     * 创建绑定特定技能的 {@link SkillToolGroup}。
     *
     * <p>默认作用域为 {@link ToolGroupScope#META}，智能体可通过 {@code reset_equipped_tools} 管理。面向模型的描述会提醒：使用绑定技能时必须激活此组。
     *
     * @param groupName 工具组名称
     * @param description 工具组描述
     * @param active 是否默认激活
     * @param activateOnSkill 此组绑定的技能名称
     * @throws IllegalArgumentException 当组已存在时
     */
    public void createSkillToolGroup(
            String groupName, String description, boolean active, String activateOnSkill) {
        groupManager.createSkillToolGroup(groupName, description, active, activateOnSkill);
    }

    /**
     * 查找 {@code activateOnSkill} 匹配给定技能名的全部 {@link SkillToolGroup}。
     *
     * @param skillName 待匹配技能名称
     * @return 匹配的组名列表（永不为 null，可以为空）
     */
    public List<String> findSkillToolGroupsByActivateOnSkill(String skillName) {
        return groupManager.findSkillToolGroupsByActivateOnSkill(skillName);
    }

    /**
     * 注册预先构建的 {@link ToolGroup} 实例（包括子类）。
     *
     * <p>需要完全控制 ToolGroup 构造时使用，例如自定义 {@link SkillToolGroup} 等子类。
     *
     * @param group 待注册工具组
     * @throws IllegalArgumentException 当同名组已存在时
     */
    public void registerToolGroup(ToolGroup group) {
        groupManager.registerToolGroup(group);
    }

    /**
     * 将已注册工具加入现有工具组。
     *
     * <p>一个工具可属于多个组，重复将同一工具加入同一组不会产生额外效果。
     *
     * @param groupName 现有工具组名称
     * @param toolName 已注册工具名称
     * @throws IllegalArgumentException 当组或工具不存在时
     */
    public void addToolToGroup(String groupName, String toolName) {
        groupManager.validateGroupExists(groupName);
        if (toolRegistry.getTool(toolName) == null) {
            throw new IllegalArgumentException("Tool not found: " + toolName);
        }
        groupManager.addToolToGroup(groupName, toolName);
    }

    /**
     * 更新工具组激活状态。
     *
     * <p>禁用 {@code allowToolDeletion} 且 {@code active} 为 false 时，忽略停用操作并记录警告。
     *
     * <p><b>旧单会话 API：</b>修改共享激活标志，逐调用路径不使用它。
     *
     * @param groupNames 要更新的工具组名称列表
     * @param active true 表示激活，false 表示停用
     * @throws IllegalArgumentException 当任意组不存在时
     */
    public void updateToolGroups(List<String> groupNames, boolean active) {
        if (!active && !config.isAllowToolDeletion()) {
            logger.warn(
                    "Tool deletion is disabled - ignoring deactivation of tool groups: {}",
                    groupNames);
            return;
        }
        groupManager.updateToolGroups(groupNames, active);
    }

    /**
     * 从工具集按名称移除工具。
     *
     * @param toolName 待移除工具名称
     */
    public void removeTool(String toolName) {
        if (!config.isAllowToolDeletion()) {
            logger.warn("Tool deletion is disabled - ignoring removal of tool: {}", toolName);
            return;
        }
        toolRegistry.removeTool(toolName);
    }

    /**
     * 仅当注册实例与期望实例一致时原子性地移除工具。
     *
     * @param toolName 待移除工具名称
     * @param expected 期望 AgentTool 实例（按身份比较）
     * @return 已移除为 true；已被替换或不存在时为 false
     */
    public boolean removeToolIfSame(String toolName, AgentTool expected) {
        if (!config.isAllowToolDeletion()) {
            logger.warn("Tool deletion is disabled - ignoring removal of tool: {}", toolName);
            return false;
        }
        return toolRegistry.removeToolIfSame(toolName, expected);
    }

    /**
     * 移除工具组及其中全部工具。
     *
     * <p>禁用 {@code allowToolDeletion} 时忽略移除操作并记录警告。
     *
     * @param groupNames 待移除工具组名称列表
     */
    public void removeToolGroups(List<String> groupNames) {
        if (!config.isAllowToolDeletion()) {
            logger.warn(
                    "Tool deletion is disabled - ignoring removal of tool groups: {}", groupNames);
            return;
        }
        Set<String> toolsToRemove = groupManager.removeToolGroups(groupNames);
        // 从注册表移除工具
        toolRegistry.removeTools(toolsToRemove);
    }

    /**
     * 获取激活工具组名称。
     *
     * <p>返回当前全部激活组名称。智能体只能调用激活组中的工具；此方法可用于调试工具可用性并验证激活状态。
     *
     * <p><b>旧单会话 API：</b>读取共享的构建期激活标志。逐调用路径改从 {@code AgentState#getToolContext()} 解析激活状态。
     *
     * @return 激活组名称列表，永不为 null，可以为空
     */
    public List<String> getActiveGroups() {
        return groupManager.getActiveGroups();
    }

    /**
     * 设置激活工具组。
     *
     * <p>通常由 ReActAgent 在从会话恢复状态时调用。
     *
     * <p><b>旧单会话 API：</b>修改共享激活标志。逐调用路径从不调用它，激活状态位于 {@code AgentState#getToolContext()}。
     *
     * @param groups 要设为激活的组名列表
     */
    public void setActiveGroups(List<String> groups) {
        groupManager.setActiveGroups(groups);
    }

    /**
     * 按名称获取工具组。
     *
     * @param groupName 工具组名称
     * @return ToolGroup；未找到时为 null
     */
    public ToolGroup getToolGroup(String groupName) {
        return groupManager.getToolGroup(groupName);
    }

    // ==================== 元工具注册 ====================

    /**
     * 注册允许智能体动态管理工具组的元工具。
     *
     * 创建包装工具集 resetEquippedTools 方法的工具，使智能体能在执行期间激活工具组。
     */
    public void registerMetaTool() {
        AgentTool metaTool = metaToolFactory.createResetEquippedToolsAgentTool();
        registeredMetaTool = metaTool;

        // 不指定组进行注册，元工具始终可用
        registerAgentTool(metaTool, null, null, null, null);

        logger.info("Registered meta tool: reset_equipped_tools");
    }

    /**
     * 在运行时更新已注册工具的预设参数。
     *
     * <p>无需重新注册工具即可动态修改预设参数，适合更新会话 ID、时间戳等会话上下文或刷新凭据。
     *
     * @param toolName 要更新的工具名称
     * @param newPresetParameters 新预设参数（null 视为空映射）
     * @throws IllegalArgumentException 当工具不存在时
     */
    public void updateToolPresetParameters(
            String toolName, Map<String, Object> newPresetParameters) {
        RegisteredToolFunction registered = toolRegistry.getRegisteredTool(toolName);
        if (registered == null) {
            throw new IllegalArgumentException("Tool not found: " + toolName);
        }
        registered.updatePresetParameters(newPresetParameters);
        logger.debug("Updated preset parameters for tool '{}'", toolName);
    }

    // ==================== 构建期隔离复制 ====================

    /**
     * 创建工具集的构建期隔离副本。
     *
     * <p><b>仅用于构建期，不得逐调用使用。</b>用户工具按引用共享，必须支持并发；框架元工具重新绑定到复制的注册表。深复制工具组及激活标志，提供独立工具组管理器，并保留用户数据块回调。
     *
     * <p>逐调用工具范围变化应通过 {@link ToolRequestConfig} 组合表达（{@link #getTool(String, ToolRequestConfig)} / {@link #getToolSchemas(java.util.Collection, ToolRequestConfig)}），而非复制工具集。本方法服务于智能体构造隔离（如 Harness 子智能体工厂），用于获得新注册表以注册绑定工作区的工具，而不污染源工具集。
     *
     * @return 注册表共享相同工具实例的新 Toolkit
     */
    public Toolkit copy() {
        Toolkit copy = new Toolkit(this.config);

        // 共享无状态且线程安全的工具实例，将注册元数据复制到目标。
        this.toolRegistry.copyTo(copy.toolRegistry);

        // 深复制工具组及激活标志，隔离组状态。
        this.groupManager.copyTo(copy.groupManager);

        // 框架元工具捕获所属工具组管理器，需要重新绑定到副本。
        if (registeredMetaTool != null
                && toolRegistry.getTool(registeredMetaTool.getName()) == registeredMetaTool) {
            copy.registerMetaTool();
            RegisteredToolFunction metadata =
                    toolRegistry.getRegisteredTool(registeredMetaTool.getName());
            if (metadata != null) {
                copy.updateToolPresetParameters(
                        registeredMetaTool.getName(), metadata.getPresetParameters());
            }
        }

        // 构建期复制时保留用户定义的数据块回调。
        copy.executor.setChunkCallback(this.executor.getChunkCallback());

        return copy;
    }

    // ==================== 链式注册 ====================

    /**
     * 注册工具并提供可选配置的链式构建器。
     *
     * <p>以清晰且类型安全的方式支持多种注册选项，避免方法数量膨胀。
     */
    public static class ToolRegistration {
        private final Toolkit toolkit;
        private Object toolObject;
        private AgentTool agentTool;
        private McpClientWrapper mcpClientWrapper;
        private SubAgentProvider<?> subAgentProvider;
        private SubAgentConfig subAgentConfig;
        private String groupName;
        private Map<String, Map<String, Object>> presetParameters;
        private ExtendedModel extendedModel;
        private List<String> enableTools;
        private String mcpToolNamePrefix = "";
        private List<String> disableTools;
        private Boolean propagateMeta;
        private final Map<String, Boolean> toolPropagateMeta = new LinkedHashMap<>();

        private ToolRegistration(Toolkit toolkit) {
            this.toolkit = toolkit;
        }

        /**
         * 设置待注册的工具对象（扫描 @Tool 方法）。
         *
         * @param toolObject 包含 @Tool 注解方法的对象
         * @return 当前构建器，支持链式调用
         */
        public ToolRegistration tool(Object toolObject) {
            this.toolObject = toolObject;
            return this;
        }

        /**
         * 设置待注册的 AgentTool 实例。
         *
         * @param agentTool AgentTool 实例
         * @return 当前构建器，支持链式调用
         */
        public ToolRegistration agentTool(AgentTool agentTool) {
            this.agentTool = agentTool;
            return this;
        }

        /**
         * 设置待注册 MCP 客户端。
         *
         * @param mcpClientWrapper MCP 客户端包装器
         * @return 当前构建器，支持链式调用
         */
        public ToolRegistration mcpClient(McpClientWrapper mcpClientWrapper) {
            this.mcpClientWrapper = mcpClientWrapper;
            return this;
        }

        /**
         * 使用默认配置将子智能体注册为工具。
         *
         * <p>工具名称和描述从智能体属性派生，默认使用单个 "task" 字符串参数。
         *
         * <p>示例：
         *
         * <pre>{@code
         * toolkit.registration()
         *     .subAgent(() -> ReActAgent.builder()
         *         .name("ResearchAgent")
         *         .model(model)
         *         .build())
         *     .apply();
         * }</pre>
         *
         * @param provider 创建智能体实例的工厂（每次调用时调用）
         * @return 当前构建器，支持链式调用
         */
        public ToolRegistration subAgent(SubAgentProvider<?> provider) {
            return subAgent(provider, null);
        }

        /**
         * 使用自定义配置将子智能体注册为工具。
         *
         * <p>子智能体支持基于会话状态管理的多轮对话。工具公开两个参数：必填 {@code message} 和可选 {@code session_id}（用于继续已有对话）。
         *
         * <p>自定义工具名称和描述示例：
         *
         * <pre>{@code
         * toolkit.registration()
         *     .subAgent(
         *         () -> ReActAgent.builder().name("Expert").model(model).build(),
         *         SubAgentConfig.builder()
         *             .toolName("ask_expert")
         *             .description("Ask the domain expert a question")
         *             .build())
         *     .apply();
         * }</pre>
         *
         * <p>通过持久化会话实现跨进程对话的示例：
         *
         * <pre>{@code
         * toolkit.registration()
         *     .subAgent(
         *         () -> ReActAgent.builder().name("Assistant").model(model).build(),
         *         SubAgentConfig.builder()
         *             .stateStore(new JsonFileAgentStateStore(Path.of("sessions")))
         *             .forwardEvents(true)
         *             .build())
         *     .apply();
         * }</pre>
         *
         * @param provider 创建智能体实例的工厂（每个会话调用）
         * @param config 子智能体工具配置；null 表示使用默认值（从智能体名称派生工具名、使用 InMemoryAgentStateStore 存储状态、转发事件）
         * @return 当前构建器，支持链式调用
         * @see SubAgentConfig
         * @see SubAgentConfig#defaults()
         */
        public ToolRegistration subAgent(SubAgentProvider<?> provider, SubAgentConfig config) {
            this.subAgentProvider = provider;
            this.subAgentConfig = config;
            return this;
        }

        /**
         * 面向模型的 MCP 工具名称的可选命名空间；传输名称保持不变。
         */
        public ToolRegistration mcpToolNamePrefix(String prefix) {
            this.mcpToolNamePrefix = java.util.Objects.requireNonNull(prefix);
            return this;
        }

        /**
         * 选择远端工具名称；列表缺失或为空时启用全部工具。
         */
        public ToolRegistration enableTools(List<String> enableTools) {
            this.enableTools = enableTools;
            return this;
        }

        /**
         * 设置 MCP 客户端中要禁用的工具列表。
         *
         * <p>仅使用 mcpClient() 时适用。
         *
         * @param disableTools 要禁用的工具名称列表
         * @return 当前构建器，支持链式调用
         */
        public ToolRegistration disableTools(List<String> disableTools) {
            this.disableTools = disableTools;
            return this;
        }

        /**
         * 控制是否向此构建器注册的 MCP 服务器传播请求元数据。
         *
         * <p>默认将运行时上下文中以 {@link io.agentscope.core.tool.mcp.McpMeta} 注册的条目及框架工具调用 ID 作为每次工具请求的 {@code meta} 字段发送。不完全可信的服务器（如外部第三方服务）应设为 {@code false}，避免元数据离开进程。
         *
         * <p>未设置时不记录逐工具限制（工具标志保持 {@code true}），实际决策回退到每次调用实时读取的客户端包装器连接级开关。
         *
         * <p>仅使用 mcpClient() 时适用。
         *
         * @param propagateMeta true 表示传播元数据，false 表示从工具调用请求中完全省略 {@code meta} 字段
         * @return 当前构建器，支持链式调用
         */
        public ToolRegistration propagateMeta(boolean propagateMeta) {
            this.propagateMeta = propagateMeta;
            return this;
        }

        /**
         * 控制此客户端单个 MCP 工具的请求元数据传播。
         *
         * <p>逐工具条目优先于 {@link #propagateMeta(boolean)} 设置的客户端默认值，调用时二者还与包装器连接级开关做逻辑与。形式类似 {@code enableTools}/{@code disableTools}：可仅让可信工具接收回调 URL 等元数据，同时禁止不可信服务器其余工具接收，或反向配置。
         *
         * <p>工具名称使用服务器公开的远端 MCP 名称（添加 {@code mcpToolNamePrefix} 之前）。不同工具的重复调用会累积配置，同一工具则覆盖旧值。若名称未匹配实际注册工具（如拼写错误或被 {@code enableTools}/{@code disableTools} 筛除），注册抛出 {@link IllegalArgumentException}，避免抑制传播的覆盖值被静默丢失。
         *
         * <p>仅使用 mcpClient() 时适用。
         *
         * @param toolName 远端 MCP 工具名称
         * @param propagateMeta true 表示允许该工具传播元数据，false 表示从其请求中省略 {@code meta} 字段
         * @return 当前构建器，支持链式调用
         * @throws IllegalArgumentException 当 {@code toolName} 为 {@code null} 或空白时
         */
        public ToolRegistration propagateMeta(String toolName, boolean propagateMeta) {
            if (toolName == null || toolName.isBlank()) {
                throw new IllegalArgumentException("MCP tool name cannot be null or blank");
            }
            this.toolPropagateMeta.put(toolName, propagateMeta);
            return this;
        }

        /**
         * 设置工具组名称。
         *
         * @param groupName 组名（null 表示不分组）
         * @return 当前构建器，支持链式调用
         */
        public ToolRegistration group(String groupName) {
            this.groupName = groupName;
            return this;
        }

        /**
         * 设置工具执行时自动注入的预设参数。
         *
         * <p>这些参数不暴露在 JSON 模式中。
         *
         * <p>映射以工具名称为键、参数映射为值：
         * <pre>{@code
         * Map.of(
         *     "toolName1", Map.of("param1", "value1", "param2", "value2"),
         *     "toolName2", Map.of("param1", "value3")
         * )
         * }</pre>
         *
         * @param presetParameters 工具名称到预设参数的映射
         * @return 当前构建器，支持链式调用
         */
        public ToolRegistration presetParameters(
                Map<String, Map<String, Object>> presetParameters) {
            this.presetParameters = presetParameters;
            return this;
        }

        /**
         * 设置用于动态模式扩展的扩展模型。
         *
         * @param extendedModel 扩展模型
         * @return 当前构建器，支持链式调用
         */
        public ToolRegistration extendedModel(ExtendedModel extendedModel) {
            this.extendedModel = extendedModel;
            return this;
        }

        /**
         * 应用全部配置选项并执行注册。
         *
         * @throws IllegalStateException 当 tool()、agentTool()、mcpClient()、subAgent() 均未设置时
         * @throws IllegalStateException 当 tool()、agentTool()、mcpClient()、subAgent() 中设置了多个时
         */
        public void apply() {
            int toolCount = 0;
            if (toolObject != null) toolCount++;
            if (agentTool != null) toolCount++;
            if (mcpClientWrapper != null) toolCount++;
            if (subAgentProvider != null) toolCount++;

            if (toolCount == 0) {
                throw new IllegalStateException(
                        "Must call one of: tool(), agentTool(), mcpClient(), or subAgent() before"
                                + " apply()");
            }
            if (toolCount > 1) {
                throw new IllegalStateException(
                        "Cannot set multiple registration types. Use only one of: tool(),"
                                + " agentTool(), mcpClient(), or subAgent().");
            }

            if (toolObject != null) {
                toolkit.registerTool(toolObject, groupName, extendedModel, presetParameters);
            } else if (agentTool != null) {
                String toolName = agentTool.getName();
                Map<String, Object> toolPresets =
                        (presetParameters != null && presetParameters.containsKey(toolName))
                                ? presetParameters.get(toolName)
                                : null;
                toolkit.registerAgentTool(agentTool, groupName, extendedModel, null, toolPresets);
            } else if (mcpClientWrapper != null) {
                toolkit.mcpClientManager
                        .registerMcpClient(
                                mcpClientWrapper,
                                enableTools,
                                disableTools,
                                groupName,
                                presetParameters,
                                mcpToolNamePrefix,
                                propagateMeta,
                                toolPropagateMeta)
                        .block();
            } else if (subAgentProvider != null) {
                SubAgentTool subAgentTool = new SubAgentTool(subAgentProvider, subAgentConfig);
                toolkit.registerAgentTool(subAgentTool, groupName, extendedModel, null, null);
            }
        }
    }
}
