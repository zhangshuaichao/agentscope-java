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
package io.agentscope.harness.agent.subagent;

import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.subagent.protocol.RemoteStreamDetail;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 声明子智能体的身份、工作区解析策略及可选的能力允许列表。
 *
 * <p>一个声明绑定到以下两种<em>来源模式</em>之一：
 * <ol>
 *   <li><b>定义工作区</b>：{@link #getWorkspacePath()} 指向至少包含 {@code AGENTS.md}
 *       的工作区目录。该文件用作子智能体系统提示正文。当 {@link WorkspaceMode}
 *       为 {@link WorkspaceMode#ISOLATED} 时，可使用定义目录中的技能、知识和 MEMORY。
 *   <li><b>远程 HTTP</b>：{@link #getUrl()} 指向 AgentScope 任务 HTTP 服务器。
 *       没有本地定义工作区或内联正文；子智能体在进程外运行。与定义工作区和内联正文互斥。
 * </ol>
 *
 * <p>三种来源模式互斥：{@link Builder#workspace(Path)}、非空白的
 * {@link Builder#inlineAgentsBody(String)}、非空白的 {@link Builder#url(String)}
 * 最多只能设置一项。
 *
 * <p>工作区解析遵循 {@link WorkspaceMode} 中的五行决策表。
 *
 * <p>{@code tools} 列表非空时，是继承父级工具的<em>允许列表过滤器</em>：
 * 仅保留名称出现在列表中的继承工具。子级构建器仍可添加子级本地工具注册。
 *
 * <p>通过 {@link #builder()} 获取实例。
 *
 * <p>编程式示例：
 *
 * <pre>{@code
 * SubagentDeclaration decl = SubagentDeclaration.builder()
 *     .name("code-reviewer")
 *     .description("Reviews code for security, performance, and readability issues.")
 *     .workspace(Path.of("./defs/code-reviewer"))
 *     .workspaceMode(WorkspaceMode.ISOLATED)
 *     .model("qwen3-max")
 *     .tools(List.of("read_file", "grep_files", "edit_file"))
 *     .build();
 * }</pre>
 */
public final class SubagentDeclaration {

    /**
     * 声明是否可作为顶层主智能体、仅作为受委派子智能体，或两者皆可。
     *
     * <p>{@link io.agentscope.harness.agent.subagent.DefaultAgentManager#createAgentIfPresent}
     * 路径会拒绝仅允许 {@link #PRIMARY} 声明的启动请求，确保它们不会作为工作者调用；
     * 反之，顶层启动器可能需要拒绝将 {@link #SUBAGENT} 声明用作入口
     * （当前 core 不负责此检查：顶层启动直接使用 {@code HarnessAgent.builder()}，
     * 而不是通过声明）。
     */
    public enum Mode {
        PRIMARY,
        SUBAGENT,
        ALL
    }

    private final String name;
    private final String description;
    private final WorkspaceMode workspaceMode;
    private final Path workspacePath;
    private final String inlineAgentsBody;
    private final String model;
    private final Double temperature;
    private final Double topP;
    private final String variant;
    private final int steps;
    private final Mode mode;
    private final boolean hidden;
    private final boolean persistSession;
    private final boolean inheritParentPermissions;
    private final Boolean exposeToUser;
    private final Boolean enablePendingToolRecovery;
    private final List<String> tools;
    private final List<String> skills;
    private final CompactionConfig compactionConfig;
    private final boolean disableCompaction;

    /**
     * 远程任务服务器的基础 URL（例如 {@code http://host:8080}）。
     */
    private final String url;

    private final Map<String, String> headers;

    /**
     * 是否将远程子智能体事件流式回传到父级事件流。
     * {@code null} 默认视为 {@code true}；参见 {@link #isRemoteStreaming()}。
     */
    private final Boolean remoteStreaming;

    /**
     * 请求远程事件流的详细程度。{@code null} 默认视为
     * {@link RemoteStreamDetail#FULL}；参见 {@link #getRemoteStreamDetail()}。
     */
    private final RemoteStreamDetail remoteStreamDetail;

    /**
     * 处理远程 HITL 确认的策略。默认为 {@link RemoteAskPolicy#DENY}。
     */
    private final RemoteAskPolicy remoteAskPolicy;

    /**
     * 每次远程提交时作为 {@code context.attributes} 发送的静态属性。非 null 时绝不为空集合。
     */
    private final Map<String, Object> remoteContextAttributes;

    private SubagentDeclaration(Builder b) {
        this.name = b.name;
        this.description = b.description;
        this.workspaceMode = b.workspaceMode;
        this.workspacePath = b.workspacePath;
        this.inlineAgentsBody = b.inlineAgentsBody;
        this.model = b.model;
        this.temperature = b.temperature;
        this.topP = b.topP;
        this.variant = b.variant;
        this.steps = b.steps;
        this.mode = b.mode != null ? b.mode : Mode.ALL;
        this.hidden = b.hidden;
        this.persistSession = b.persistSession;
        this.inheritParentPermissions = b.inheritParentPermissions;
        this.exposeToUser = b.exposeToUser;
        this.enablePendingToolRecovery = b.enablePendingToolRecovery;
        this.tools = b.tools != null ? List.copyOf(b.tools) : List.of();
        this.skills = b.skills != null ? List.copyOf(b.skills) : List.of();
        this.compactionConfig = b.compactionConfig;
        this.disableCompaction = b.disableCompaction;
        this.url = b.url;
        this.headers = b.headers != null && !b.headers.isEmpty() ? Map.copyOf(b.headers) : null;
        this.remoteStreaming = b.remoteStreaming;
        this.remoteStreamDetail = b.remoteStreamDetail;
        this.remoteAskPolicy = b.remoteAskPolicy != null ? b.remoteAskPolicy : RemoteAskPolicy.DENY;
        this.remoteContextAttributes =
                b.remoteContextAttributes != null && !b.remoteContextAttributes.isEmpty()
                        ? Collections.unmodifiableMap(
                                new LinkedHashMap<>(b.remoteContextAttributes))
                        : null;
    }

    /**
     * 创建新构建器的工厂方法。
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 用于引用此子智能体的唯一名称／智能体 ID。
     */
    public String getName() {
        return name;
    }

    /**
     * 供人阅读的描述；主智能体据此决定何时委派任务。
     */
    public String getDescription() {
        return description;
    }

    /**
     * 工作区解析策略。未指定时默认为 {@link WorkspaceMode#ISOLATED}。
     */
    public WorkspaceMode getWorkspaceMode() {
        return workspaceMode;
    }

    /**
     * 定义工作区目录的路径（至少包含 {@code AGENTS.md}）。为 {@code null}
     * 时此声明使用内联模式，由 {@link #getInlineAgentsBody()} 提供系统提示。
     */
    public Path getWorkspacePath() {
        return workspacePath;
    }

    /**
     * {@link #getWorkspacePath()} 为 {@code null} 时使用的内联系统提示正文。
     * 如果既没有定义工作区，也没有内联正文，则可以为 {@code null} 或空白。
     */
    public String getInlineAgentsBody() {
        return inlineAgentsBody;
    }

    /**
     * 可选模型覆盖值（例如 {@code "qwen3-max"} 或 {@code "openai:gpt-4o-mini"}）。
     * 为 {@code null} 或空白时使用父级模型。
     */
    public String getModel() {
        return model;
    }

    /**
     * 最大推理迭代次数。默认为 10。
     *
     * @deprecated 自阶段 A 起弃用，请使用 {@link #getSteps()}。返回相同值；
     *     为兼容 {@code steps} 字段出现前构建的调用方而保留。
     */
    @Deprecated
    public int getMaxIters() {
        return steps;
    }

    /**
     * 最大推理迭代次数（默认 10）。替代历史上的 {@code maxIters} 字段。
     */
    public int getSteps() {
        return steps;
    }

    /**
     * 可选采样温度覆盖值（例如类似压缩的确定性任务使用 {@code 0.0}，
     * 创意生成使用 {@code 0.7}）。为 {@code null} 时，直接使用父级
     * {@link io.agentscope.core.model.GenerateOptions#getTemperature()}，不作修改。
     */
    public Double getTemperature() {
        return temperature;
    }

    /**
     * 可选核采样覆盖值。为 {@code null} 时，直接使用父级
     * {@link io.agentscope.core.model.GenerateOptions#getTopP()}，不作修改。
     */
    public Double getTopP() {
        return topP;
    }

    /**
     * 可选模型变体标识符（例如 DashScope 思考模式变体使用 {@code "thinking"}）。
     * 为 {@code null} 或空白时不应用变体转换；通过复制构建器继承父级变体（若有）。
     */
    public String getVariant() {
        return variant;
    }

    /**
     * 此声明的 {@link Mode}。未指定时默认为 {@link Mode#ALL}，
     * 即既可作为子智能体启动，也可作为主智能体。
     */
    public Mode getMode() {
        return mode;
    }

    /**
     * 是否在 LLM 可见的可用子智能体列表中隐藏此声明。
     * 用于编排器不应直接委派的内部子智能体（例如压缩、摘要、标题）。
     */
    public boolean isHidden() {
        return hidden;
    }

    /**
     * 子智能体会话状态是否跨父级调用持久化。为 {@code true} 时，
     * 启动键由 (userId, parentSessionId, agentId, label) 确定性派生，
     * 支持进程重启后的状态恢复。为 {@code false}（默认）时使用随机 UUID。
     */
    public boolean isPersistSession() {
        return persistSession;
    }

    /**
     * 子智能体是否继承父级 DENY 权限规则。为 {@code true}（默认）时，
     * 启动时将父级权限上下文中的所有 DENY 规则传播到子级权限引擎，
     * 防止子级绕过父级限制。
     */
    public boolean isInheritParentPermissions() {
        return inheritParentPermissions;
    }

    /**
     * 按子智能体类型配置的策略，决定是否将已启动实例暴露为用户可访问的线程。
     *
     * <p>三态：
     * <ul>
     *   <li>{@code TRUE}：始终暴露，不考虑 LLM 在 {@code agent_spawn} 上的请求
     *   <li>{@code FALSE}：绝不暴露（强制退出），覆盖 LLM 的 {@code expose_to_user=true}
     *   <li>{@code null}（默认）：不指定；先遵循单次调用的 {@code RuntimeContext} 覆盖值，
     *       再遵循 LLM 的 {@code expose_to_user} 参数
     * </ul>
     *
     * <p>运行时可由键为 {@code AgentSpawnTool#CTX_EXPOSE_TO_USER} 的
     * {@code RuntimeContext} 值覆盖。完整解析优先级参见 {@code AgentSpawnTool}。
     */
    public Boolean getExposeToUser() {
        return exposeToUser;
    }

    /**
     * 自动构造的本地子智能体中，孤立工具调用的恢复策略。
     * {@code null}（默认）继承父级设置；{@code true} 或 {@code false}
     * 显式覆盖它。远程子智能体在自己的服务器上配置恢复。
     */
    public Boolean getEnablePendingToolRecovery() {
        return enablePendingToolRecovery;
    }

    /**
     * 可选工具允许列表。非空时，继承工具集中只保留名称在列表中的父级工具。
     * 空列表表示继承所有父级工具。
     */
    public List<String> getTools() {
        return tools;
    }

    public List<String> getSkills() {
        return skills;
    }

    /**
     * 此子智能体的可选压缩配置。
     *
     * <p>为 {@code null} 时，子智能体继承父级配置，除非
     * {@link #isCompactionDisabled()} 为 {@code true}。远程子智能体
     * 在自己的服务器上管理压缩，不使用此设置。
     */
    public CompactionConfig getCompactionConfig() {
        return compactionConfig;
    }

    /**
     * 此声明是否显式禁用本地子智能体的压缩。
     */
    public boolean isCompactionDisabled() {
        return disableCompaction;
    }

    /**
     * 此声明指向远程任务 HTTP 服务器时返回 {@code true}。
     */
    public boolean isRemote() {
        return url != null && !url.isBlank();
    }

    /**
     * 远程任务服务器的基础 URL。仅在 {@linkplain #isRemote() remote} 模式下非空白。
     */
    public String getUrl() {
        return url;
    }

    /**
     * 发送到远程任务服务器的可选 HTTP 请求头（例如认证头）。非 null 时绝不为空集合。
     */
    public Map<String, String> getHeaders() {
        return headers;
    }

    /**
     * 当父级存在实时事件流时，是否将远程子智能体事件（文本增量、工具调用、确认）
     * 流式回传到该流。未设置时默认为 {@code true}。
     */
    public boolean isRemoteStreaming() {
        return remoteStreaming == null || remoteStreaming;
    }

    /**
     * 转发远程事件流的详细程度。默认为 {@link RemoteStreamDetail#FULL}
     * （生命周期、工具调用、文本和思考增量）。使用 {@link RemoteStreamDetail#VERBOSE}
     * 可完整镜像本地子智能体流：块边界、工具输出增量、带 token 用量的模型调用及其他所有事件，
     * 代价是更多流量。仅当 {@link #isRemoteStreaming()} 为 true 时有效。
     */
    public RemoteStreamDetail getRemoteStreamDetail() {
        return remoteStreamDetail != null ? remoteStreamDetail : RemoteStreamDetail.FULL;
    }

    /**
     * 处理远程 HITL（工具确认）请求的策略。默认为
     * {@link RemoteAskPolicy#DENY}，自动拒绝待确认请求，而不是让远程任务无限阻塞。
     */
    public RemoteAskPolicy getRemoteAskPolicy() {
        return remoteAskPolicy;
    }

    /**
     * 每次远程提交时通过 {@code context.attributes} 发送的静态属性，
     * 供远程服务器路由或向其智能体暴露。未设置时为 {@code null}；否则绝不为空集合。
     */
    public Map<String, Object> getRemoteContextAttributes() {
        return remoteContextAttributes;
    }

    /**
     * 此声明指向外部定义工作区时返回 {@code true}。
     */
    public boolean hasDefinitionWorkspace() {
        return workspacePath != null;
    }

    // -------------------------------------------------------------------------
    // 构建器
    // -------------------------------------------------------------------------

    public static final class Builder {

        private String name;
        private String description;
        private WorkspaceMode workspaceMode = WorkspaceMode.ISOLATED;
        private Path workspacePath;
        private String inlineAgentsBody;
        private String model;
        private Double temperature;
        private Double topP;
        private String variant;
        private int steps = 10;
        private Mode mode = Mode.ALL;
        private boolean hidden = false;
        private boolean persistSession = false;
        private boolean inheritParentPermissions = true;
        private Boolean exposeToUser;
        private Boolean enablePendingToolRecovery;
        private List<String> tools;
        private List<String> skills;
        private CompactionConfig compactionConfig;
        private boolean disableCompaction;
        private String url;
        private Map<String, String> headers;
        private Boolean remoteStreaming;
        private RemoteStreamDetail remoteStreamDetail;
        private RemoteAskPolicy remoteAskPolicy = RemoteAskPolicy.DENY;
        private Map<String, Object> remoteContextAttributes;

        private Builder() {}

        /**
         * 设置此子智能体的唯一名称／智能体 ID（必填）。
         */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * 设置供人阅读的描述，供编排器决定何时委派任务（必填）。
         */
        public Builder description(String description) {
            this.description = description;
            return this;
        }

        /**
         * 设置工作区解析模式。默认为 {@link WorkspaceMode#ISOLATED}。
         *
         * @param mode 工作区模式；{@code null} 视为 {@link WorkspaceMode#ISOLATED}
         */
        public Builder workspaceMode(WorkspaceMode mode) {
            this.workspaceMode = mode != null ? mode : WorkspaceMode.ISOLATED;
            return this;
        }

        /**
         * 将此声明指向外部定义工作区。
         *
         * <p>与 {@link #inlineAgentsBody(String)} 互斥：同时传入非 null 路径和
         * 非空白内联正文会使 {@link #build()} 抛出异常。
         *
         * @param workspacePath 绝对路径，或通过 Markdown 前置元数据文件设置时
         *     相对于 {@code mainWorkspace} 的路径
         */
        public Builder workspace(Path workspacePath) {
            this.workspacePath = workspacePath;
            return this;
        }

        /**
         * 为不需要专用定义工作区的轻量级子智能体设置内联系统提示正文。
         *
         * <p>与 {@link #workspace(Path)} 互斥。
         *
         * @param body 系统提示正文（Markdown）；可以为 {@code null} 或空白
         */
        public Builder inlineAgentsBody(String body) {
            this.inlineAgentsBody = body;
            return this;
        }

        /**
         * 通过 {@link io.agentscope.core.model.ModelRegistry} 解析的可选模型覆盖值。
         * 空白或无法解析时回退到父级模型。
         */
        public Builder model(String model) {
            this.model = model;
            return this;
        }

        /**
         * 最大推理迭代次数（默认 10）。
         *
         * @deprecated 自阶段 A 起弃用，请使用 {@link #steps(int)}。行为等价。
         */
        @Deprecated
        public Builder maxIters(int maxIters) {
            this.steps = maxIters;
            return this;
        }

        /**
         * 最大推理迭代次数（默认 10）。
         */
        public Builder steps(int steps) {
            this.steps = steps;
            return this;
        }

        /**
         * 可选采样温度覆盖值。{@code null}（默认）表示继承父级智能体的值。
         * 典型范围为 {@code 0.0 – 2.0}。
         */
        public Builder temperature(Double temperature) {
            this.temperature = temperature;
            return this;
        }

        /**
         * 可选核采样（top-p）覆盖值。{@code null}（默认）表示继承父级。
         * 典型范围为 {@code 0.0 – 1.0}。
         */
        public Builder topP(Double topP) {
            this.topP = topP;
            return this;
        }

        /**
         * 可选模型变体标识符（例如 DashScope 思考模式变体使用 {@code "thinking"}）。
         * 空白或 {@code null} 表示不进行变体转换；通过复制构建器继承父级变体（若有）。
         */
        public Builder variant(String variant) {
            this.variant = variant;
            return this;
        }

        /**
         * 设置 {@link Mode}。{@code null} 视为 {@link Mode#ALL}。
         */
        public Builder mode(Mode mode) {
            this.mode = mode != null ? mode : Mode.ALL;
            return this;
        }

        /**
         * 在 LLM 的可用子智能体列表中隐藏此声明。默认为 {@code false}。
         */
        public Builder hidden(boolean hidden) {
            this.hidden = hidden;
            return this;
        }

        /**
         * 为 {@code true} 时，子智能体启动键由
         * (userId, parentSessionId, agentId, label) 确定性派生，
         * 支持跨父级调用和进程重启的状态恢复。默认为 {@code false}。
         */
        public Builder persistSession(boolean persistSession) {
            this.persistSession = persistSession;
            return this;
        }

        /**
         * 为 {@code true}（默认）时，在启动时将父级 DENY 权限规则传播给子级。
         * 只有子级确实需要父级显式拒绝的权限时才设为 {@code false}（少见）。
         */
        public Builder inheritParentPermissions(boolean inheritParentPermissions) {
            this.inheritParentPermissions = inheritParentPermissions;
            return this;
        }

        /**
         * 按类型配置的策略，决定是否将已启动实例暴露为用户可访问的线程。
         *
         * <p>{@code TRUE} 强制暴露，{@code FALSE} 禁止暴露（覆盖 LLM 请求），
         * {@code null}（默认）先遵循 {@code RuntimeContext} 覆盖值，再遵循
         * LLM 的 {@code expose_to_user} 参数。
         */
        public Builder exposeToUser(Boolean exposeToUser) {
            this.exposeToUser = exposeToUser;
            return this;
        }

        /**
         * 覆盖此本地子智能体的待处理工具恢复设置。传入 {@code null} 继承父级设置
         * （默认行为）。权限确认绝不会自动恢复。
         *
         * @param enable 是否启用恢复，或使用 {@code null} 继承
         * @return 此构建器
         */
        public Builder enablePendingToolRecovery(Boolean enable) {
            this.enablePendingToolRecovery = enable;
            return this;
        }

        /**
         * 工具允许列表：非空时，只保留名称在列表中的继承父级工具。
         * 子级本地工具注册不受影响。
         */
        public Builder tools(List<String> tools) {
            this.tools = tools;
            return this;
        }

        public Builder skills(List<String> skills) {
            this.skills = skills;
            return this;
        }

        /**
         * 为此子智能体设置压缩配置。
         *
         * <p>显式声明级配置优先于父级智能体的压缩配置；
         * 即使父级禁用压缩，也可以为此子智能体启用压缩。
         */
        public Builder compaction(CompactionConfig compactionConfig) {
            this.compactionConfig = compactionConfig;
            this.disableCompaction = false;
            return this;
        }

        /**
         * 禁用此子智能体的压缩，覆盖父级配置。
         */
        public Builder disableCompaction() {
            this.compactionConfig = null;
            this.disableCompaction = true;
            return this;
        }

        /**
         * 远程任务服务器基础 URL。与 {@link #workspace(Path)} 及非空白的
         * {@link #inlineAgentsBody(String)} 互斥。
         */
        public Builder url(String url) {
            this.url = url;
            return this;
        }

        /**
         * 远程任务服务器的可选 HTTP 请求头（例如 {@code Authorization}）。
         * 仅在设置 {@link #url(String)} 时使用。
         */
        public Builder headers(Map<String, String> headers) {
            this.headers = headers;
            return this;
        }

        /**
         * 是否将远程子智能体事件流式回传到父级事件流。
         * {@code null}（默认）视为 {@code true}。仅在设置 {@link #url(String)} 时有效。
         */
        public Builder remoteStreaming(Boolean remoteStreaming) {
            this.remoteStreaming = remoteStreaming;
            return this;
        }

        /**
         * 转发远程事件流的详细程度。{@code null}（默认）视为
         * {@link RemoteStreamDetail#FULL}；{@link RemoteStreamDetail#VERBOSE}
         * 转发远程智能体发出的每个事件，与本地子智能体一致。
         * 仅在启用 {@link #remoteStreaming(Boolean)} 时有效。
         */
        public Builder remoteStreamDetail(RemoteStreamDetail remoteStreamDetail) {
            this.remoteStreamDetail = remoteStreamDetail;
            return this;
        }

        /**
         * 处理远程 HITL 确认的策略。{@code null} 视为
         * {@link RemoteAskPolicy#DENY}。仅在设置 {@link #url(String)} 时有效。
         */
        public Builder remoteAskPolicy(RemoteAskPolicy remoteAskPolicy) {
            this.remoteAskPolicy = remoteAskPolicy != null ? remoteAskPolicy : RemoteAskPolicy.DENY;
            return this;
        }

        /**
         * 每次向此远程子智能体提交时，作为 {@code context.attributes} 发送的静态属性
         * （例如租户或部署标签）。值必须可序列化为 JSON。父级 {@code RuntimeContext}
         * 中设置的单次调用属性在此基础上合并覆盖。仅在设置 {@link #url(String)} 时有效。
         */
        public Builder remoteContextAttributes(Map<String, Object> remoteContextAttributes) {
            this.remoteContextAttributes = remoteContextAttributes;
            return this;
        }

        /**
         * 构建 {@link SubagentDeclaration}。
         *
         * @throws IllegalArgumentException {@code name} 或 {@code description} 为空白，
         *     或组合了互斥字段（工作区、内联正文、远程 URL）时抛出
         */
        public SubagentDeclaration build() {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("SubagentDeclaration requires a non-blank name");
            }
            if (description == null || description.isBlank()) {
                throw new IllegalArgumentException(
                        "SubagentDeclaration requires a non-blank description");
            }
            boolean remote = url != null && !url.isBlank();
            if (remote) {
                if (workspacePath != null) {
                    throw new IllegalArgumentException(
                            "url() and workspace(Path) are mutually exclusive for subagent '"
                                    + name
                                    + "'");
                }
                if (inlineAgentsBody != null && !inlineAgentsBody.isBlank()) {
                    throw new IllegalArgumentException(
                            "url() and inlineAgentsBody() are mutually exclusive for subagent '"
                                    + name
                                    + "'");
                }
            } else if (workspacePath != null
                    && inlineAgentsBody != null
                    && !inlineAgentsBody.isBlank()) {
                throw new IllegalArgumentException(
                        "workspace(Path) and inlineAgentsBody() are mutually exclusive;"
                                + " set at most one for subagent '"
                                + name
                                + "'");
            }
            return new SubagentDeclaration(this);
        }
    }
}
