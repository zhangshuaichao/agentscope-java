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

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * 包装 MCP（Model Context Protocol，模型上下文协议）工具的 {@link ToolBase} 子类。
 *
 * <p>将远程 MCP 工具接入 AgentScope 工具体系，使其与内置工具及 {@code @Tool} 工具一样参与权限评估和智能体的待确认流程。
 *
 * <p>【学习提示】工具参数模式、执行权限和请求元数据是三条不同路径：模式供模型生成参数，权限由调用流程评估，元数据是否出进程受工具级与连接级开关共同限制。禁用元数据传播不等于禁止远程工具执行。
 *
 * <p>功能：
 * <ul>
 * <li>将 AgentScope 工具调用转换为 MCP 协议调用。
 * <li>合并调用方参数与注册时配置的预设参数。
 * <li>将 MCP 结果转换为 AgentScope {@link ToolResultBlock}。
 * <li>通过 {@link #checkPermissions(Map, PermissionContextState)} 暴露权限策略：只读 MCP 工具自动放行，其余工具需要用户明确授权（与 Python {@code MCPTool} 的默认行为一致）。
 * </ul>
 */
public class McpTool extends ToolBase {

    private static final Logger logger = LoggerFactory.getLogger(McpTool.class);

    /**
     * 服务端显示标题的长度上限，与 {@code AgentTraceMiddleware} 中工具名称使用的单行标签宽度一致。截断时最后三个位置用于 {@link #ELLIPSIS}，因此清理后的标题不会超过此码点数。
     */
    private static final int MAX_DISPLAY_TITLE_LENGTH = 120;

    private static final String ELLIPSIS = "...";

    private static final int CONTENT_LENGTH_BEFORE_ELLIPSIS =
            MAX_DISPLAY_TITLE_LENGTH - ELLIPSIS.length();

    private final Map<String, Object> outputSchema;
    private final String remoteToolName;
    private final McpClientWrapper clientWrapper;
    private final Map<String, Object> presetArguments;

    /**
     * 针对单个工具的请求元数据传播限制。只有此标志与连接级 {@link McpClientWrapper#isPropagateMeta()} 均为 {@code true} 时才实际传播（逻辑与）：包装器标志在每次调用时实时读取，而此标志在注册时（由 {@code McpClientManager}）确定，只能进一步限制该工具的传播。
     *
     * <p>两个开关保留的状态不同：注册时在此明确记录的 {@code true} 会被记住，因此操作方重新启用以 {@code propagateMeta(false)} 构建的连接后，工具可恢复传播；连接级开关始终是实时上限，为 {@code false} 时不能被覆盖。
     */
    private volatile boolean propagateMeta = true;

    /**
     * 供 {@link io.agentscope.core.tool.McpClientManager} 使用的首选构造方法。
     */
    public McpTool(
            String name,
            String description,
            Map<String, Object> parameters,
            Map<String, Object> outputSchema,
            McpClientWrapper clientWrapper,
            Map<String, Object> presetArguments,
            String mcpName,
            boolean readOnly) {
        this(
                name,
                name,
                description,
                parameters,
                outputSchema,
                clientWrapper,
                presetArguments,
                mcpName,
                readOnly);
    }

    /**
     * 注册带命名空间、面向模型的名称，实际调用仍使用原始 MCP 工具名。
     */
    public McpTool(
            String name,
            String remoteToolName,
            String description,
            Map<String, Object> parameters,
            Map<String, Object> outputSchema,
            McpClientWrapper clientWrapper,
            Map<String, Object> presetArguments,
            String mcpName,
            boolean readOnly) {
        this(
                name,
                remoteToolName,
                description,
                parameters,
                outputSchema,
                clientWrapper,
                presetArguments,
                mcpName,
                readOnly,
                null);
    }

    /**
     * 与 {@link #McpTool(String, String, String, Map, Map, McpClientWrapper, Map, String, boolean)} 相同，但额外携带 MCP 服务端提供的人类可读显示标题，并通过 {@link #getTitle()} 暴露。
     *
     * @param title 面向最终用户的显示名称；服务端未提供时为 {@code null}；该值会经过显示清理，避免服务端向权限提示注入换行或双向文本覆盖字符
     */
    public McpTool(
            String name,
            String remoteToolName,
            String description,
            Map<String, Object> parameters,
            Map<String, Object> outputSchema,
            McpClientWrapper clientWrapper,
            Map<String, Object> presetArguments,
            String mcpName,
            boolean readOnly,
            String title) {
        super(
                ToolBase.builder()
                        .name(Objects.requireNonNull(name, "name cannot be null"))
                        .description(description != null ? description : "")
                        .title(sanitizeDisplayTitle(title))
                        .inputSchema(parameters != null ? parameters : new HashMap<>())
                        .readOnly(readOnly)
                        .concurrencySafe(false)
                        .mcp(Objects.requireNonNull(mcpName, "mcpName cannot be null")));
        this.remoteToolName = Objects.requireNonNull(remoteToolName);
        this.outputSchema = outputSchema != null ? new HashMap<>(outputSchema) : null;
        this.clientWrapper = Objects.requireNonNull(clientWrapper, "clientWrapper cannot be null");
        this.presetArguments = presetArguments != null ? new HashMap<>(presetArguments) : null;
    }

    /**
     * @deprecated 请使用 {@link #McpTool(String, String, Map, Map, McpClientWrapper, Map, String, boolean)} 以传播 MCP 服务端名称和只读提示。保留此方法以兼容 {@link ToolBase} 集成之前的调用方源码。
     */
    @Deprecated(since = "2.0.0")
    public McpTool(
            String name,
            String description,
            Map<String, Object> parameters,
            McpClientWrapper clientWrapper) {
        this(
                name,
                description,
                parameters,
                null,
                clientWrapper,
                null,
                clientWrapper.getName(),
                false);
    }

    /**
     * @deprecated 请使用 {@link #McpTool(String, String, Map, Map, McpClientWrapper, Map, String, boolean)} 以传播 MCP 服务端名称和只读提示。
     */
    @Deprecated(since = "2.0.0")
    public McpTool(
            String name,
            String description,
            Map<String, Object> parameters,
            McpClientWrapper clientWrapper,
            Map<String, Object> presetArguments) {
        this(
                name,
                description,
                parameters,
                null,
                clientWrapper,
                presetArguments,
                clientWrapper.getName(),
                false);
    }

    /**
     * @deprecated 请使用 {@link #McpTool(String, String, Map, Map, McpClientWrapper, Map, String, boolean)} 以传播 MCP 服务端名称和只读提示。
     */
    @Deprecated(since = "2.0.0")
    public McpTool(
            String name,
            String description,
            Map<String, Object> parameters,
            Map<String, Object> outputSchema,
            McpClientWrapper clientWrapper,
            Map<String, Object> presetArguments) {
        this(
                name,
                description,
                parameters,
                outputSchema,
                clientWrapper,
                presetArguments,
                clientWrapper.getName(),
                false);
    }

    @Override
    public Map<String, Object> getOutputSchema() {
        return outputSchema != null ? new HashMap<>(outputSchema) : null;
    }

    @Override
    public Mono<PermissionDecision> checkPermissions(
            Map<String, Object> toolInput, PermissionContextState context) {
        if (isReadOnly()) {
            return Mono.just(
                    PermissionDecision.allow(
                            "Read-only MCP tool '" + getName() + "' allowed without prompt"));
        }
        return Mono.just(
                PermissionDecision.ask(
                        "MCP tool '"
                                + getName()
                                + "' requires explicit authorization before each call"));
    }

    /**
     * 使用给定参数异步执行此 MCP 工具。
     *
     * <p>合并预设参数与输入参数（输入优先），通过客户端包装器调用远程 MCP 工具，并将结果转换为 {@link ToolResultBlock}。发生错误时返回错误结果，而不是以失败信号结束。
     *
     * <p>仅当 {@link #isPropagateMeta()} 和连接级 {@link McpClientWrapper#isPropagateMeta()} 均为 {@code true}（逻辑与）时，才向 MCP 服务端传播请求元数据（用户 {@link McpMeta} 条目及框架工具调用 ID）；否则请求中完全省略 {@code meta} 字段。
     *
     * @param param 工具调用参数，包含 toolUseBlock、input 和 agent
     * @return MCP 调用完成后发出工具结果的 Mono
     */
    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        logger.debug("Calling MCP tool '{}' with input: {}", getName(), param.getInput());

        // 合并预设参数与输入参数。
        Map<String, Object> mergedArgs = mergeArguments(param.getInput());

        // 仅当两个开关均允许时才传播请求元数据；null 会完全省略 MCP
        // `_meta` 字段，使任何元数据（用户 McpMeta 条目及工具调用 ID）
        // 都不会离开进程。包装器开关实时读取，因此禁用连接
        // 会立即生效，即使工具早已注册。
        boolean propagate = propagateMeta && clientWrapper.isPropagateMeta();
        Map<String, Object> metaMap = propagate ? buildMetaMap(param) : null;

        return clientWrapper
                .callTool(remoteToolName, mergedArgs, metaMap)
                .map(McpContentConverter::convertCallToolResult)
                .doOnSuccess(
                        result -> logger.debug("MCP tool '{}' completed successfully", getName()))
                .onErrorResume(
                        e -> {
                            logger.error(
                                    "Error calling MCP tool '{}': {}", getName(), e.getMessage());
                            String errorMsg =
                                    e.getMessage() != null
                                            ? e.getMessage()
                                            : e.getClass().getSimpleName();
                            return Mono.just(ToolResultBlock.error("MCP tool error: " + errorMsg));
                        });
    }

    /**
     * 检查此工具是否允许传播请求元数据。
     *
     * <p>这只是单工具限制：实际决策还要与每次调用实时读取的连接级 {@link McpClientWrapper#isPropagateMeta()} 做逻辑与。
     *
     * @return 若允许工具调用请求包含用户 {@link McpMeta} 条目及工具调用 ID，则为 true，否则为 false
     */
    public boolean isPropagateMeta() {
        return propagateMeta;
    }

    /**
     * 配置此工具是否允许传播请求元数据。
     *
     * <p>初始值在注册时确定（注册级覆盖值，默认 {@code true}）。设为 {@code false} 会持续限制此工具；重新设为 {@code true} 后，也只有 {@link McpClientWrapper} 的连接级开关允许时才恢复传播。
     *
     * @param propagateMeta true 表示允许此工具传播元数据（默认）；false 表示在其请求中完全省略 {@code meta} 字段
     */
    public void setPropagateMeta(boolean propagateMeta) {
        this.propagateMeta = propagateMeta;
    }

    /**
     * 获取提供此工具的 MCP 客户端名称。
     *
     * @return MCP 客户端名称
     */
    public String getClientName() {
        return clientWrapper.getName();
    }

    /**
     * 获取此工具配置的预设参数。
     *
     * @return 预设参数；未配置时为 null
     */
    public Map<String, Object> getPresetArguments() {
        return presetArguments != null ? new HashMap<>(presetArguments) : null;
    }

    /**
     * 合并输入参数与预设参数，输入参数优先。
     *
     * <p>使用包级可见性，以便直接单元测试 {@link ToolCallParam#getInput()} 在生产环境无法到达的防御性 {@code null} 分支。
     *
     * @param input 输入参数
     * @return 合并后的参数
     */
    // 为测试提供可见性，不作为子类扩展点（参见上方 Javadoc）。
    Map<String, Object> mergeArguments(Map<String, Object> input) {
        if (presetArguments == null || presetArguments.isEmpty()) {
            return input != null ? input : new HashMap<>();
        }

        Map<String, Object> merged = new HashMap<>(presetArguments);
        if (input != null) {
            merged.putAll(input);
        }
        return merged;
    }

    /**
     * 构建工具调用的请求元数据。
     *
     * <p>从运行时上下文的 {@link McpMeta} 类型命名空间提取 MCP 元数据，并附加框架工具调用 ID。传输元数据不是模型输入，不应改变工具模式。
     *
     * @param param 工具调用参数
     * @return 请求元数据映射，可以为空但不会为 null
     */
    private Map<String, Object> buildMetaMap(ToolCallParam param) {
        Map<String, Object> meta = new HashMap<>(extractMcpMeta(param));
        if (param.getToolUseBlock() != null && param.getToolUseBlock().getId() != null) {
            meta.put("io.agentscope/toolCallId", param.getToolUseBlock().getId());
        }
        return meta;
    }

    /**
     * 从给定工具调用参数中提取 MCP 元数据。
     *
     * @param param 工具调用参数
     * @return 提取出的 MCP 元数据
     */
    private Map<String, Object> extractMcpMeta(ToolCallParam param) {
        if (param == null || param.getRuntimeContext() == null) {
            return Collections.emptyMap();
        }
        McpMeta mcpMeta = param.getRuntimeContext().get(McpMeta.class);
        if (mcpMeta == null || mcpMeta.isEmpty()) {
            return Collections.emptyMap();
        }
        return mcpMeta.entries();
    }

    /**
     * 解析 MCP 服务端为工具提供的人类可读显示标题。
     *
     * <p>MCP 规范将 {@code Tool.title} 用于显示，也允许在 {@code ToolAnnotations.title} 中提供相同值；两者均可选，且都不是面向模型的 {@code description}。注册时使用此标题，让权限确认界面能为最终用户标注操作，而不只显示程序名称。
     *
     * <p>两个候选值都经过 {@link #sanitizeDisplayTitle(String)}：最终采用的内容来自 MCP 服务端，并展示给即将批准操作的人。
     *
     * @param tool MCP 工具定义，可以为 {@code null}
     * @return 首个非空白且已清理的标题；服务端未提供时为 {@code null}
     */
    public static String resolveDisplayTitle(McpSchema.Tool tool) {
        if (tool == null) {
            return null;
        }
        String title = sanitizeDisplayTitle(tool.title());
        if (title != null) {
            return title;
        }
        return sanitizeDisplayTitle(tool.annotations() != null ? tool.annotations().title() : null);
    }

    /**
     * 在向用户展示之前规范化服务端提供的标签。
     *
     * <p>两类字符直接移除而不是转义，因为仅靠转义无法消除其显示影响：零宽和方向格式字符（{@code Cf}，例如 U+202E RIGHT-TO-LEFT OVERRIDE）会重排读者看到的该行内容；C0/C1 控制字符和空白可能向确认对话框注入额外行。连续空白折叠为一个空格以保持单行，结果限制为 {@link #MAX_DISPLAY_TITLE_LENGTH} 个码点（一个 emoji 计为一个），避免截断代理项对。
     *
     * @param title 服务端原始值，可以为 {@code null}
     * @return 可显示形式；没有剩余可打印内容时为 {@code null}（调用方于是回退到 {@link #getName()}，避免显示空标签）
     */
    private static String sanitizeDisplayTitle(String title) {
        if (title == null) {
            return null;
        }
        String cleaned =
                title.replaceAll("\\p{Cf}", "")
                        .replaceAll("[\\p{Cc}\\p{Zs}\\p{Zl}\\p{Zp}]+", " ")
                        .strip();
        if (cleaned.isEmpty()) {
            return null;
        }
        if (cleaned.codePointCount(0, cleaned.length()) <= MAX_DISPLAY_TITLE_LENGTH) {
            return cleaned;
        }
        logger.debug(
                "MCP display title exceeds {} code points and was truncated for display",
                MAX_DISPLAY_TITLE_LENGTH);
        return cleaned.substring(0, cleaned.offsetByCodePoints(0, CONTENT_LENGTH_BEFORE_ELLIPSIS))
                + ELLIPSIS;
    }

    /**
     * 将 MCP 工具的 inputSchema 映射转换为 AgentScope 参数格式。
     * MCP SDK 2.0 以原始 JSON Schema 映射表示 inputSchema，因此这里保留全部模式关键字，仅规范化预设参数过滤使用的对象关键字。
     *
     * @param inputSchema 原始输入模式映射（JSON Schema）
     * @return AgentScope 格式的参数映射
     */
    public static Map<String, Object> convertMcpSchemaToParameters(
            Map<String, Object> inputSchema, Set<String> excludeParams) {
        Map<String, Object> parameters = new HashMap<>();

        if (inputSchema == null || inputSchema.isEmpty()) {
            parameters.put("type", "object");
            parameters.put("properties", new HashMap<>());
            parameters.put("required", new ArrayList<>());
            return parameters;
        }

        parameters.putAll(inputSchema);

        Object typeNode = parameters.get("type");
        Object propertiesNode = parameters.get("properties");
        Object requiredNode = parameters.get("required");

        @SuppressWarnings("unchecked")
        Map<String, Object> properties =
                propertiesNode instanceof Map
                        ? new HashMap<>((Map<String, Object>) propertiesNode)
                        : new HashMap<>();

        List<String> required = new ArrayList<>();
        if (requiredNode instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof String s) {
                    required.add(s);
                }
            }
        }

        // 从模式中排除预设参数。
        if (excludeParams != null) {
            required.removeAll(excludeParams);
            properties.keySet().removeAll(excludeParams);
        }

        parameters.put("type", typeNode instanceof String s && !s.isBlank() ? s : "object");
        parameters.put("properties", properties);
        parameters.put("required", required);

        return parameters;
    }
}
