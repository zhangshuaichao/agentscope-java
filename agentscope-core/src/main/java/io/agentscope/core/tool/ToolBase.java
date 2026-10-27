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

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.permission.PermissionRule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import reactor.core.publisher.Mono;

/**
 * 参与权限评估和 ReAct 执行的工具抽象基类。
 *
 * <p>具体子类通过构建器描述名称、描述、输入模式和安全标志，并可覆写 {@link #checkPermissions(Map, PermissionContextState)} 接入自检。默认返回 {@link PermissionDecision#passthrough(String)}，让引擎回退到规则表和基于模式的默认值。
 *
 * <p>{@code ToolBase} 实现 {@link AgentTool}，可直接接入现有 {@code Toolkit} 分派。框架外部产生结果的工具应标记 {@code externalTool=true}；{@code ToolExecutor} 通过 {@link ToolSuspendException} 暴露调用，而不在本地执行。
 *
 * <p>构建器用法：
 *
 * <pre>{@code
 * super(ToolBase.builder()
 *         .name("read")
 *         .description("Read a file")
 *         .inputSchema(schema)
 *         .readOnly(true)
 *         .concurrencySafe(true));
 * }</pre>
 */
public abstract class ToolBase implements AgentTool {

    private final String name;
    private final String description;
    private final String title;
    private final Map<String, Object> inputSchema;
    private final boolean concurrencySafe;
    private final boolean readOnly;
    private final boolean externalTool;
    private final boolean stateInjected;
    private final boolean mcp;
    private final String mcpName;
    private final boolean returnDirect;

    /**
     * 敏感文件；子类可替换列表，扩大或缩小保护范围。
     */
    protected List<String> dangerousFiles = ToolDangerousPathConstants.DEFAULT_DANGEROUS_FILES;

    /**
     * 敏感目录名；绝对路径采用逐片段匹配。
     */
    protected List<String> dangerousDirectories =
            ToolDangerousPathConstants.DEFAULT_DANGEROUS_DIRECTORIES;

    /**
     * 基于构建器的构造器（推荐）。
     */
    protected ToolBase(Builder builder) {
        this(
                builder.name,
                builder.description,
                builder.title,
                builder.inputSchema,
                builder.readOnly,
                builder.concurrencySafe,
                builder.mcp,
                builder.mcpName,
                builder.externalTool,
                builder.stateInjected,
                builder.returnDirect);
        if (builder.dangerousFiles != null) {
            this.dangerousFiles = List.copyOf(builder.dangerousFiles);
        }
        if (builder.dangerousDirectories != null) {
            this.dangerousDirectories = List.copyOf(builder.dangerousDirectories);
        }
    }

    /**
     * 供内置工具和偏好显式参数而非 {@link #builder()} 的子类使用的位置参数构造器。新代码建议使用构建器以提高可读性。
     */
    protected ToolBase(
            String name,
            String description,
            Map<String, Object> inputSchema,
            boolean readOnly,
            boolean concurrencySafe,
            boolean mcp,
            String mcpName,
            boolean externalTool,
            boolean stateInjected) {
        this(
                name,
                description,
                null,
                inputSchema,
                readOnly,
                concurrencySafe,
                mcp,
                mcpName,
                externalTool,
                stateInjected,
                false);
    }

    private ToolBase(
            String name,
            String description,
            String title,
            Map<String, Object> inputSchema,
            boolean readOnly,
            boolean concurrencySafe,
            boolean mcp,
            String mcpName,
            boolean externalTool,
            boolean stateInjected,
            boolean returnDirect) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.description = Objects.requireNonNull(description, "description must not be null");
        this.title = title;
        this.inputSchema = Objects.requireNonNull(inputSchema, "inputSchema must not be null");
        this.readOnly = readOnly;
        this.concurrencySafe = concurrencySafe;
        this.mcp = mcp;
        this.mcpName = mcpName;
        this.externalTool = externalTool;
        this.stateInjected = stateInjected;
        this.returnDirect = returnDirect;
        if (mcp && (mcpName == null || mcpName.isBlank())) {
            throw new IllegalArgumentException("mcpName is required when mcp is true");
        }
    }

    @Override
    public final String getName() {
        return name;
    }

    @Override
    public final String getDescription() {
        return description;
    }

    @Override
    public final String getTitle() {
        return title;
    }

    @Override
    public final Map<String, Object> getParameters() {
        return inputSchema;
    }

    public final boolean isConcurrencySafe() {
        return concurrencySafe;
    }

    @Override
    public final boolean isReadOnly() {
        return readOnly;
    }

    /**
     * 工具结果是否应短路 ReAct 循环，直接作为本轮最终答复返回，而不反馈给模型进行下一轮推理。默认为 {@code false}。
     *
     * @return {@code true} 表示直接返回结果，跳过后续模型调用
     */
    @Override
    public final boolean isReturnDirect() {
        return returnDirect;
    }

    public final boolean isExternalTool() {
        return externalTool;
    }

    public final boolean isStateInjected() {
        return stateInjected;
    }

    public final boolean isMcp() {
        return mcp;
    }

    public final String getMcpName() {
        return mcpName;
    }

    /**
     * 默认工具调用。外部工具不得本地执行；非外部工具子类必须覆写此方法。
     */
    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        if (externalTool) {
            return Mono.error(
                    new IllegalStateException(
                            getClass().getSimpleName()
                                    + " is an external tool and must not be invoked locally"));
        }
        return Mono.error(
                new UnsupportedOperationException(
                        getClass().getSimpleName() + " does not implement callAsync"));
    }

    /**
     * 当 {@code allowRules}/{@code askRules} 未直接允许或拒绝调用时，由权限引擎执行工具自检。
     *
     * <p>默认返回 {@link PermissionDecision#passthrough(String)}，将决定交给规则表和模式默认值。具有细粒度语义的工具（Bash、文件写入器、MCP 工具）应覆写此方法，给出自己的 ALLOW / ASK / DENY 策略。
     *
     * @param toolInput 已解析的工具参数
     * @param context 当前权限评估上下文
     * @return 发出决策的 Mono，永不为 {@code null}
     */
    public Mono<PermissionDecision> checkPermissions(
            Map<String, Object> toolInput, PermissionContextState context) {
        return Mono.just(PermissionDecision.passthrough(name));
    }

    /**
     * 默认规则匹配器：{@code ruleContent} 为 {@code null} 时匹配所有调用；任何非 null 模式都拒绝匹配，以便子类叠加自己的语义。
     */
    public boolean matchRule(String ruleContent, Map<String, Object> toolInput) {
        return ruleContent == null;
    }

    /**
     * 默认建议：来源为 {@code "suggested"} 的单条工具名称级 {@link PermissionBehavior#ALLOW} 规则。具有文件路径、命令前缀等细粒度上下文的子类应覆写此方法以生成更精确的模式。
     */
    public List<PermissionRule> generateSuggestions(Map<String, Object> toolInput) {
        return List.of(new PermissionRule(name, null, PermissionBehavior.ALLOW, "suggested"));
    }

    /**
     * @return 当 {@code filePath} 的文件名匹配 {@link #dangerousFiles}（忽略大小写），或任意路径片段匹配 {@link #dangerousDirectories} 时为 {@code true}。同时解析符号链接并重新检查真实路径，防止通过指向危险目标的符号链接绕过保护。
     */
    protected boolean isDangerousPath(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return false;
        }
        Path absolute = Path.of(expandTilde(filePath)).toAbsolutePath().normalize();
        if (isDangerousAbsolute(absolute)) {
            return true;
        }
        try {
            Path resolved = Files.exists(absolute) ? absolute.toRealPath() : absolute;
            if (!resolved.equals(absolute) && isDangerousAbsolute(resolved)) {
                return true;
            }
        } catch (IOException ignored) {
            // 无法解析时，保留逻辑路径检查结果
        }
        return false;
    }

    private boolean isDangerousAbsolute(Path absolute) {
        Path fileNamePath = absolute.getFileName();
        String fileNameLower =
                fileNamePath == null ? "" : fileNamePath.toString().toLowerCase(Locale.ROOT);
        for (String dangerousFile : dangerousFiles) {
            if (fileNameLower.equals(dangerousFile.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        Set<String> segmentsLower = new HashSet<>();
        absolute.forEach(segment -> segmentsLower.add(segment.toString().toLowerCase(Locale.ROOT)));
        for (String dangerousDir : dangerousDirectories) {
            if (segmentsLower.contains(dangerousDir.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static String expandTilde(String path) {
        if (path.startsWith("~")) {
            return System.getProperty("user.home") + path.substring(1);
        }
        return path;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * {@link ToolBase} 子类的链式构建器。
     */
    public static final class Builder {
        private String name;
        private String description;
        private String title;
        private Map<String, Object> inputSchema;
        private boolean readOnly = false;
        private boolean concurrencySafe = true;
        private boolean externalTool = false;
        private boolean stateInjected = false;
        private boolean returnDirect = false;
        private boolean mcp = false;
        private String mcpName;
        private List<String> dangerousFiles;
        private List<String> dangerousDirectories;

        private Builder() {}

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        /**
         * 设置通过 {@link #getTitle()} 公开的可读显示名称。
         */
        public Builder title(String title) {
            this.title = title;
            return this;
        }

        public Builder inputSchema(Map<String, Object> inputSchema) {
            this.inputSchema = inputSchema;
            return this;
        }

        public Builder readOnly(boolean readOnly) {
            this.readOnly = readOnly;
            return this;
        }

        public Builder concurrencySafe(boolean concurrencySafe) {
            this.concurrencySafe = concurrencySafe;
            return this;
        }

        public Builder externalTool(boolean externalTool) {
            this.externalTool = externalTool;
            return this;
        }

        public Builder stateInjected(boolean stateInjected) {
            this.stateInjected = stateInjected;
            return this;
        }

        /**
         * 设置是否将工具结果直接返回给调用方。
         */
        public Builder returnDirect(boolean returnDirect) {
            this.returnDirect = returnDirect;
            return this;
        }

        /**
         * 标记为 MCP 工具并记录 MCP 服务器名称。
         */
        public Builder mcp(String mcpName) {
            this.mcp = true;
            this.mcpName = mcpName;
            return this;
        }

        public Builder dangerousFiles(List<String> dangerousFiles) {
            this.dangerousFiles = dangerousFiles;
            return this;
        }

        public Builder dangerousDirectories(List<String> dangerousDirectories) {
            this.dangerousDirectories = dangerousDirectories;
            return this;
        }
    }
}
