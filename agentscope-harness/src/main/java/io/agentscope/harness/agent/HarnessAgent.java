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
package io.agentscope.harness.agent;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.agent.AgentRun;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.agent.config.FailoverListener;
import io.agentscope.core.agent.config.ModelConfig;
import io.agentscope.core.agent.config.ReactConfig;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.TaskReminderMiddleware;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.session.SessionHistoryMode;
import io.agentscope.core.session.SessionKey;
import io.agentscope.core.session.SessionLog;
import io.agentscope.core.session.SessionLogStore;
import io.agentscope.core.session.SessionProjection;
import io.agentscope.core.session.SessionRecovery;
import io.agentscope.core.session.SessionViews;
import io.agentscope.core.shutdown.GracefulShutdownMiddleware;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.ConflictPolicy;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolExecutionContext;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.artifact.ArtifactDeliveryTarget;
import io.agentscope.harness.agent.context.ContextPolicy;
import io.agentscope.harness.agent.context.ContextSelectionPolicy;
import io.agentscope.harness.agent.context.ContextSource;
import io.agentscope.harness.agent.context.ContextSourceOptions;
import io.agentscope.harness.agent.context.ContextSources;
import io.agentscope.harness.agent.context.HarnessContextBuilder;
import io.agentscope.harness.agent.context.TaskContextOptions;
import io.agentscope.harness.agent.coordination.LocalPeriodicGate;
import io.agentscope.harness.agent.coordination.PeriodicGate;
import io.agentscope.harness.agent.coordination.StoreBackedPeriodicGate;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.CompositeFilesystem;
import io.agentscope.harness.agent.filesystem.OverlayFilesystem;
import io.agentscope.harness.agent.filesystem.RoutedSandboxFilesystem;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystemWithShell;
import io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;
import io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;
import io.agentscope.harness.agent.filesystem.spec.SandboxFilesystemSpec;
import io.agentscope.harness.agent.gateway.HarnessGateway;
import io.agentscope.harness.agent.gateway.SubagentGatewayBridge;
import io.agentscope.harness.agent.gateway.channel.Channel;
import io.agentscope.harness.agent.memory.MemoryConfig;
import io.agentscope.harness.agent.memory.MemoryConsolidator;
import io.agentscope.harness.agent.memory.MemoryFlushManager;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ConversationCompactor;
import io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig;
import io.agentscope.harness.agent.middleware.AgentTraceMiddleware;
import io.agentscope.harness.agent.middleware.AsyncToolMiddleware;
import io.agentscope.harness.agent.middleware.AtPathExpansionMiddleware;
import io.agentscope.harness.agent.middleware.CompactionMiddleware;
import io.agentscope.harness.agent.middleware.ContextConventionsMiddleware;
import io.agentscope.harness.agent.middleware.DynamicSubagentsMiddleware;
import io.agentscope.harness.agent.middleware.HarnessRuntimeMiddleware;
import io.agentscope.harness.agent.middleware.HarnessSkillMiddleware;
import io.agentscope.harness.agent.middleware.InboxMiddleware;
import io.agentscope.harness.agent.middleware.MemoryFlushMiddleware;
import io.agentscope.harness.agent.middleware.MemoryMaintenanceMiddleware;
import io.agentscope.harness.agent.middleware.SandboxLifecycleMiddleware;
import io.agentscope.harness.agent.middleware.SubagentEntry;
import io.agentscope.harness.agent.middleware.SubagentsMiddleware;
import io.agentscope.harness.agent.middleware.TeamsMiddleware;
import io.agentscope.harness.agent.middleware.ToolResultEvictionMiddleware;
import io.agentscope.harness.agent.middleware.WorkspaceContextMiddleware;
import io.agentscope.harness.agent.observation.StateStoreActionObserver;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;
import io.agentscope.harness.agent.sandbox.SandboxManager;
import io.agentscope.harness.agent.sandbox.SessionSandboxStateStore;
import io.agentscope.harness.agent.session.AgentSession;
import io.agentscope.harness.agent.session.WorkspaceSessionLogStore;
import io.agentscope.harness.agent.skill.WorkspaceSkillRepository;
import io.agentscope.harness.agent.skill.curator.RejectAllGate;
import io.agentscope.harness.agent.skill.curator.SkillAuditLog;
import io.agentscope.harness.agent.skill.curator.SkillCurator;
import io.agentscope.harness.agent.skill.curator.SkillCuratorConfig;
import io.agentscope.harness.agent.skill.curator.SkillPromoter;
import io.agentscope.harness.agent.skill.curator.SkillPromotionGate;
import io.agentscope.harness.agent.skill.curator.SkillUsageStore;
import io.agentscope.harness.agent.skill.curator.SkillVisibilityFilter;
import io.agentscope.harness.agent.skill.runtime.ShellPathPolicy;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.SubagentFactory;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.tool.ArtifactDeliveryTool;
import io.agentscope.harness.agent.tool.FilesystemTool;
import io.agentscope.harness.agent.tool.MemoryGetTool;
import io.agentscope.harness.agent.tool.MemorySaveTool;
import io.agentscope.harness.agent.tool.MemorySearchTool;
import io.agentscope.harness.agent.tool.PlanModeTools;
import io.agentscope.harness.agent.tool.ProposeSkillTool;
import io.agentscope.harness.agent.tool.SessionSearchTool;
import io.agentscope.harness.agent.tool.ShellExecuteTool;
import io.agentscope.harness.agent.tool.SkillManageConfig;
import io.agentscope.harness.agent.tool.SkillManageTool;
import io.agentscope.harness.agent.tool.WebTools;
import io.agentscope.harness.agent.tools.McpServerRegistrar;
import io.agentscope.harness.agent.tools.McpServerRegistrationListener;
import io.agentscope.harness.agent.tools.ToolFilter;
import io.agentscope.harness.agent.tools.ToolsConfig;
import io.agentscope.harness.agent.tools.ToolsConfigLoader;
import io.agentscope.harness.agent.workspace.WorkspaceIndex;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import io.agentscope.harness.agent.workspace.WorkspacePathNormalizer;
import io.agentscope.harness.agent.workspace.plan.PlanModeManager;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * HarnessAgent 是面向用户的 Harness API，在 {@link ReActAgent} 外组合工作区、文件系统、
 * 沙箱、子智能体、技能、计划模式及 MCP 编排。
 *
 * <p>通过 {@link #builder()} 配置。若只需普通 ReAct，不需要上述能力，直接使用 {@link ReActAgent#builder()}。
 *
 * <p>在内部 {@link ReActAgent} 之上增加的能力：
 * <ul>
 * <li>从工作区加载上下文（AGENTS.md、MEMORY.md、KNOWLEDGE.md）</li>
 * <li>可插拔文件系统后端（本地、沙箱、远程／组合）</li>
 * <li>通过 {@code task}／{@code task_output} 工具编排子智能体（同步及后台）</li>
 * <li>通过 {@link AgentSkillRepository} 加载技能，包括自学习循环</li>
 * <li>上下文压缩前刷新记忆、卸载消息</li>
 * <li>工作区管理的 {@code tools.json}（MCP 服务及允许／拒绝过滤）</li>
 * <li>计划模式（只读设计阶段），提供 {@code plan_enter}／{@code plan_write}／{@code plan_exit} 工具</li>
 * <li>通过 {@link CompactionMiddleware} 在上下文溢出时紧急压缩</li>
 * </ul>
 *
 * <p><b>线程安全：</b>{@code HarnessAgent} 不在调用之间保存请求状态，可作为单例并发服务多个用户／会话。
 * 每次 {@code call()} 使用 {@link io.agentscope.core.agent.RuntimeContext} 的 {@code (userId, sessionId)} 隔离状态。
 * 同会话调用自动串行，不同会话并行。
 */
public class HarnessAgent implements Agent, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HarnessAgent.class);

    private final ReActAgent delegate;
    private final WorkspaceManager workspaceManager;
    private final BiFunction<String, String, WorkspaceManager> workspaceFactory;
    private final WorkspaceIndex ownedWorkspaceIndex;
    private final SandboxContext defaultSandboxContext;
    private final CompactionMiddleware compactionHook;
    private final SandboxLifecycleMiddleware sandboxLifecycleMw;
    private final List<AgentSkillRepository> skillRepositories;
    private final PlanModeManager planModeManager;
    // 技能自学习：未启用 enableSkillManageTool／enableSkillCurator 时为 null。
    private final SkillPromoter skillPromoter;
    private final SkillUsageStore skillUsageStore;
    private final SkillCurator skillCurator;
    private final SkillAuditLog skillAuditLog;
    private final MemoryConfig memoryConfig;
    private final Toolkit ownedMcpToolkit;

    /**
     * 子智能体中间件（SubagentsMiddleware 或 DynamicSubagentsMiddleware）。
     */
    private final Object subagentMiddleware;

    /**
     * 保留分布式存储，以便惰性创建的网关构建持久化 {@link io.agentscope.harness.agent.gateway.SubagentRegistry}，
     * 支持跨节点恢复已暴露子智能体。纯本地部署时为 {@code null}。
     */
    private final DistributedStore distributedStore;

    private final WorkspacePathNormalizer pathNormalizer;

    /**
     * 为 {@link #channel} 惰性创建的内部网关。
     */
    private volatile HarnessGateway internalGateway;

    private HarnessAgent(
            ReActAgent delegate,
            WorkspaceManager workspaceManager,
            BiFunction<String, String, WorkspaceManager> workspaceFactory,
            WorkspaceIndex ownedWorkspaceIndex,
            SandboxContext defaultSandboxContext,
            CompactionMiddleware compactionHook,
            SandboxLifecycleMiddleware sandboxLifecycleMw,
            List<AgentSkillRepository> skillRepositories,
            PlanModeManager planModeManager,
            SkillPromoter skillPromoter,
            SkillUsageStore skillUsageStore,
            SkillCurator skillCurator,
            SkillAuditLog skillAuditLog,
            MemoryConfig memoryConfig,
            Object subagentMiddleware,
            DistributedStore distributedStore,
            WorkspacePathNormalizer pathNormalizer,
            Toolkit ownedMcpToolkit) {
        this.delegate = delegate;
        this.ownedMcpToolkit = ownedMcpToolkit;
        this.workspaceManager = workspaceManager;
        this.workspaceFactory = workspaceFactory;
        this.ownedWorkspaceIndex = ownedWorkspaceIndex;
        this.defaultSandboxContext = defaultSandboxContext;
        this.compactionHook = compactionHook;
        this.sandboxLifecycleMw = sandboxLifecycleMw;
        this.skillRepositories =
                skillRepositories != null ? List.copyOf(skillRepositories) : List.of();
        this.planModeManager = planModeManager;
        this.skillPromoter = skillPromoter;
        this.skillUsageStore = skillUsageStore;
        this.skillCurator = skillCurator;
        this.skillAuditLog = skillAuditLog;
        this.memoryConfig = memoryConfig != null ? memoryConfig : MemoryConfig.defaults();
        this.subagentMiddleware = subagentMiddleware;
        this.distributedStore = distributedStore;
        this.pathNormalizer = pathNormalizer;
    }

    private final Map<SessionKey, AgentSession> sessions = new ConcurrentHashMap<>();

    /**
     * 绑定会话命令及读取视图。获取此句柄不会启动推理。
     */
    public AgentSession session(RuntimeContext context) {
        RuntimeContext effective =
                ensureSessionDefaults(context == null ? RuntimeContext.empty() : context);
        return sessions.computeIfAbsent(
                sessionKey(effective), ignored -> new AgentSession(this, effective));
    }

    public SessionKey sessionKey(RuntimeContext context) {
        return delegate.sessionKey(ensureSessionDefaults(context));
    }

    public SessionLog sessionLog(RuntimeContext context) {
        return delegate.sessionLog(ensureSessionDefaults(context));
    }

    public SessionProjection inspectSession(RuntimeContext context) {
        return SessionProjection.read(sessionLog(context));
    }

    public SessionViews.Transcript sessionTranscript(RuntimeContext context) {
        return SessionViews.transcript(sessionLog(context));
    }

    public void reconcileToolOutcomes(
            RuntimeContext context, Map<String, ToolResultBlock> outcomes, String reason) {
        SessionRecovery.reconcile(sessionLog(context), outcomes, reason);
    }

    /**
     * 返回绑定到此智能体的工作区管理器；未配置时为 {@code null}。
     */
    public WorkspaceManager getWorkspaceManager() {
        return workspaceManager;
    }

    /**
     * 返回 {@link WorkspaceManager} 视图，其文件系统及命名空间在该视图 I/O 期间绑定到指定 {@code (userId, sessionId)}。
     * 不同于 {@link #getWorkspaceManager()}，此操作不修改智能体共享状态，
     * 因此每请求控制器可并发调用，不会与活动聊天发生竞争。
     */
    public WorkspaceManager workspaceFor(String userId, String sessionId) {
        if (workspaceFactory == null) {
            return workspaceManager;
        }
        return workspaceFactory.apply(userId, sessionId);
    }

    /**
     * 返回已配置的 {@link CompactionMiddleware} 实例；未配置时为 {@code null}。
     */
    public CompactionMiddleware getCompactionHook() {
        return compactionHook;
    }

    /**
     * 返回此智能体绑定的 {@link AgentSkillRepository} 有序列表（优先级从低到高）。
     */
    public List<AgentSkillRepository> getSkillRepositories() {
        return skillRepositories;
    }

    /**
     * 访问旁路遥测存储。未配置 {@code enableSkillManageTool} 时为 null。
     */
    public SkillUsageStore getSkillUsageStore() {
        return skillUsageStore;
    }

    /**
     * 查询指定 UTC 日期的审计日志；传入 {@code null} 表示今天。
     * 未配置审计日志（未调用 {@code enableSkillManageTool}）时返回空列表。
     */
    public List<SkillAuditLog.Entry> queryAudit(
            String dayUtc, java.util.function.Predicate<SkillAuditLog.Entry> filter) {
        if (skillAuditLog == null) {
            return List.of();
        }
        return skillAuditLog.query(dayUtc, filter);
    }

    /**
     * 绕过空闲及间隔门控，立即强制运行技能整理器。
     * 未配置整理器时返回发出 {@code null} 的 {@code Mono}。
     */
    public Mono<SkillCurator.CuratorRunReport> runCuratorOnce() {
        if (skillCurator == null) {
            return Mono.empty();
        }
        return Mono.fromCallable(() -> skillCurator.runOnce(null));
    }

    /**
     * 根据显式提供的请求上下文晋升草稿技能。
     *
     * <p>在活动 {@code call(...)} 之外晋升技能，且工作区按用户或会话隔离时，
     * 必须使用此重载，确保草稿在正确命名空间内解析和移动。
     */
    public Mono<SkillPromoter.PromotionResult> promoteSkill(
            String name, String reviewerId, RuntimeContext ctx) {
        if (skillPromoter == null) {
            return Mono.just(
                    SkillPromoter.PromotionResult.invalid(
                            "skill promoter not configured; call"
                                    + " enableSkillManageTool(...) on the builder"));
        }
        return skillPromoter.promote(name, reviewerId, ctx);
    }

    /**
     * 为给定 {@link RuntimeContext} 所标识会话进入计划模式。
     * 变更会持久化，使该会话下一次 {@code call} 可见。
     */
    public void enterPlanMode(RuntimeContext ctx) {
        enterPlanMode(ctx.getUserId(), ctx.getSessionId());
    }

    /**
     * 为给定 {@link RuntimeContext} 所标识会话退出计划模式。
     */
    public void exitPlanMode(RuntimeContext ctx) {
        exitPlanMode(ctx.getUserId(), ctx.getSessionId());
    }

    /**
     * @return 给定 {@link RuntimeContext} 所标识会话是否处于计划模式
     */
    public boolean isPlanModeActive(RuntimeContext ctx) {
        return isPlanModeActive(ctx.getUserId(), ctx.getSessionId());
    }

    /**
     * 清空 {@code ctx} 所标识会话中模型可见的对话上下文。
     *
     * <p>保留会话身份及非对话状态，下一次调用从空对话上下文开始。此方法不会取消进行中的调用。
     *
     * @param ctx 标识会话的运行时上下文
     */
    public void clearContext(RuntimeContext ctx) {
        delegate.clearContext(ctx);
    }

    /**
     * 清空一个 {@code (userId, sessionId)} 会话中模型可见的对话上下文。
     *
     * <p>保留会话身份及非对话状态，下一次调用从空对话上下文开始。此方法不会取消进行中的调用。
     *
     * @param userId 槽位用户身份（{@code null} 表示匿名／单租户）
     * @param sessionId 会话身份；{@code null} 或空白时使用默认会话 ID
     */
    public void clearContext(String userId, String sessionId) {
        delegate.clearContext(userId, sessionId);
    }

    /**
     * 清空被包装 {@link ReActAgent} 持有的所有本地会话状态缓存及权限引擎。
     * 保留配置的 {@link AgentStateStore} 中的持久化状态。
     */
    public void clearStateCache() {
        delegate.clearStateCache();
    }

    /**
     * 清空 {@code ctx} 所标识会话的本地状态缓存及权限引擎。
     * 保留配置的 {@link AgentStateStore} 中的持久化状态。
     *
     * @param ctx 标识会话的运行时上下文
     */
    public void clearStateCache(RuntimeContext ctx) {
        delegate.clearStateCache(ctx);
    }

    /**
     * 清空一个 {@code (userId, sessionId)} 槽位的本地状态缓存及权限引擎。
     * 保留配置的 {@link AgentStateStore} 中的持久化状态。
     *
     * @param userId 槽位用户身份（{@code null} 表示匿名／单租户）
     * @param sessionId 会话身份；{@code null} 或空白时使用默认会话 ID
     */
    public void clearStateCache(String userId, String sessionId) {
        delegate.clearStateCache(userId, sessionId);
    }

    /**
     * 为指定 {@code (userId, sessionId)} 会话进入计划模式，与当前活动槽位无关。
     * 变更会持久化，使该会话下一次 {@code call} 可见。
     */
    public void enterPlanMode(String userId, String sessionId) {
        AgentState s = delegate.getAgentState(userId, sessionId);
        if (planModeManager != null) {
            planModeManager.enter(s);
        } else {
            s.getPlanModeContext().setPlanActive(true);
        }
        delegate.saveAgentState(userId, sessionId);
    }

    /**
     * 为指定 {@code (userId, sessionId)} 会话退出计划模式并持久化变更。
     */
    public void exitPlanMode(String userId, String sessionId) {
        AgentState s = delegate.getAgentState(userId, sessionId);
        if (planModeManager != null) {
            planModeManager.exit(s);
        } else {
            s.getPlanModeContext().setPlanActive(false);
        }
        delegate.saveAgentState(userId, sessionId);
    }

    /**
     * @return 指定 {@code (userId, sessionId)} 会话是否处于计划模式
     */
    public boolean isPlanModeActive(String userId, String sessionId) {
        AgentState s = delegate.getAgentState(userId, sessionId);
        return s.getPlanModeContext().isPlanActive();
    }

    /**
     * 运行时切换给定 {@link RuntimeContext} 所标识会话的 {@link io.agentscope.core.permission.PermissionMode}。
     * 参见 {@link ReActAgent#setPermissionMode(String, String, io.agentscope.core.permission.PermissionMode)}。
     */
    public void setPermissionMode(
            RuntimeContext ctx, io.agentscope.core.permission.PermissionMode mode) {
        delegate.setPermissionMode(ctx, mode);
    }

    /**
     * 运行时切换指定 {@code (userId, sessionId)} 会话的 {@link io.agentscope.core.permission.PermissionMode}，
     * 保留已配置规则并重建缓存权限引擎。
     */
    public void setPermissionMode(
            String userId, String sessionId, io.agentscope.core.permission.PermissionMode mode) {
        delegate.setPermissionMode(userId, sessionId, mode);
    }

    /**
     * @return 指定 {@code (userId, sessionId)} 会话当前权限模式
     */
    public io.agentscope.core.permission.PermissionMode getPermissionMode(
            String userId, String sessionId) {
        return delegate.getPermissionMode(userId, sessionId);
    }

    @Override
    public void close() {
        try {
            sessions.values().forEach(AgentSession::close);
            // 等待无需返回值的记忆刷新／维护任务完成，避免异步 memory/*.md 写入
            // 与资源清理竞争（例如测试中删除临时工作区）。
            io.agentscope.harness.agent.memory.MemoryBackgroundTasks.awaitQuiescence(
                    5, java.util.concurrent.TimeUnit.SECONDS);
            shutdownTaskRepository();
        } finally {
            try {
                if (ownedWorkspaceIndex != null) {
                    ownedWorkspaceIndex.close();
                }
            } finally {
                ownedMcpToolkit.closeMcpClients();
                delegate.close();
            }
        }
    }

    private void shutdownTaskRepository() {
        TaskRepository taskRepo = null;
        if (subagentMiddleware instanceof SubagentsMiddleware sm) {
            taskRepo = sm.getTaskRepository();
        } else if (subagentMiddleware instanceof DynamicSubagentsMiddleware dsm) {
            taskRepo = dsm.getTaskRepository();
        }
        if (taskRepo != null) {
            taskRepo.shutdown();
        }
    }

    // ==================== Agent 接口委托 ====================

    /**
     * 返回被包装的 {@link ReActAgent}。
     */
    public ReActAgent getDelegate() {
        return delegate;
    }

    public Model getModel() {
        return delegate.getModel();
    }

    public int getMaxIters() {
        return delegate.getMaxIters();
    }

    public AgentStateStore getStateStore() {
        return delegate.getStateStore();
    }

    public ConflictPolicy getConflictPolicy() {
        return delegate.getConflictPolicy();
    }

    /**
     * 此智能体配置的分布式存储；本地部署时为 {@code null}。
     * 暴露此对象，使 {@link io.agentscope.harness.agent.gateway.GatewayBootstrap} 能构建
     * 用于暴露子智能体恢复的持久化 {@link io.agentscope.harness.agent.gateway.SubagentRegistry}。
     */
    public DistributedStore getDistributedStore() {
        return distributedStore;
    }

    /**
     * 能够重新实体化此智能体子智能体的内部 {@link io.agentscope.harness.agent.subagent.DefaultAgentManager}；
     * 不拥有管理器时（例如会话模式外部子智能体工具）为 {@code null}。用于接入网关实体化器以支持跨节点恢复。
     */
    public io.agentscope.harness.agent.subagent.DefaultAgentManager getSubagentAgentManager() {
        if (subagentMiddleware instanceof SubagentsMiddleware sm) {
            return sm.getAgentManager();
        }
        if (subagentMiddleware instanceof DynamicSubagentsMiddleware dm) {
            return dm.getAgentManager();
        }
        return null;
    }

    /**
     * 子智能体中间件拥有的后台子智能体 {@link TaskRepository}；未配置子智能体时为 {@code null}。
     */
    public TaskRepository getTaskRepository() {
        if (subagentMiddleware instanceof SubagentsMiddleware sm) {
            return sm.getTaskRepository();
        }
        if (subagentMiddleware instanceof DynamicSubagentsMiddleware dsm) {
            return dsm.getTaskRepository();
        }
        return null;
    }

    /** @see ReActAgent#getDefaultSessionId() */
    public String getDefaultSessionId() {
        return delegate.getDefaultSessionId();
    }

    /**
     * @deprecated 请显式提供会话身份，使用 {@link #getDelegate()}{@code .getAgentState(RuntimeContext)}
     * 或 {@code .getAgentState(String, String)}。
     */
    @Deprecated
    @Override
    public AgentState getAgentState() {
        return delegate.getAgentState();
    }

    @Override
    public String getName() {
        return delegate.getName();
    }

    @Override
    public String getAgentId() {
        return delegate.getAgentId();
    }

    @Override
    public String getDescription() {
        return delegate.getDescription();
    }

    @Override
    public void interrupt() {
        delegate.interrupt();
    }

    @Override
    public void interrupt(Msg msg) {
        delegate.interrupt(msg);
    }

    /**
     * 中断 {@code ctx} 所标识会话的进行中调用。
     *
     * @param ctx 标识待中断会话的运行时上下文
     */
    public void interrupt(RuntimeContext ctx) {
        delegate.interrupt(ctx);
    }

    /**
     * 中断 {@code ctx} 所标识会话的进行中调用，并附带用户消息。
     *
     * @param ctx 标识待中断会话的运行时上下文
     * @param msg 可选的附加到中断信号的用户消息
     */
    public void interrupt(RuntimeContext ctx, Msg msg) {
        delegate.interrupt(ctx, msg);
    }

    /**
     * 中断指定 {@code (userId, sessionId)} 会话的进行中调用。
     *
     * @param userId 槽位用户身份（{@code null} 表示匿名／单租户）
     * @param sessionId 会话身份；{@code null} 或空白时使用默认会话 ID
     */
    public void interrupt(String userId, String sessionId) {
        delegate.interrupt(userId, sessionId);
    }

    /**
     * 中断指定 {@code (userId, sessionId)} 会话的进行中调用，并附带用户消息。
     */
    public void interrupt(String userId, String sessionId, Msg msg) {
        delegate.interrupt(userId, sessionId, msg);
    }

    // -----------------------------------------------------------------
    // 通道／网关
    // -----------------------------------------------------------------

    /**
     * 返回绑定到此智能体内部网关的通道。网关在首次调用时惰性创建，
     * 并将此智能体注册为唯一的主智能体。
     *
     * <p>Spring 控制器中的典型用法：
     * <pre>{@code
     * HarnessAgent agent = HarnessAgent.builder()...build();
     * ChatUiChannel chat = agent.channel(ChatUiChannel.perPeer());
     *
     * // in controller
     * chat.send("user-123", "hello");
     * }</pre>
     *
     * @param channel 要绑定的通道实例（例如 {@code ChatUiChannel.perPeer()}）
     * @param <T> 通道类型
     * @return 同一通道实例，此时已接入智能体网关
     */
    public <T extends Channel> T channel(T channel) {
        ensureGateway();
        channel.init(internalGateway);
        return channel;
    }

    /**
     * 返回支撑 {@link #channel} 的内部网关；未初始化时惰性创建。
     * 供高级用途使用，例如直接调用 {@code runSubagent}。
     */
    public HarnessGateway gateway() {
        ensureGateway();
        return internalGateway;
    }

    private synchronized void ensureGateway() {
        if (internalGateway != null) {
            return;
        }
        HarnessGateway gw = HarnessGateway.create();
        gw.bindMainAgent(this);

        SubagentGatewayBridge bridge =
                (agentId, sessionId, agent, replyTo) -> {
                    String subagentId = gw.exposeSubagent(agentId, sessionId, agent, replyTo);
                    return new SubagentGatewayBridge.ExposeResult(subagentId);
                };
        io.agentscope.harness.agent.subagent.DefaultAgentManager agentManager = null;
        if (subagentMiddleware instanceof SubagentsMiddleware sm) {
            sm.setGatewayBridge(bridge);
            agentManager = sm.getAgentManager();
        } else if (subagentMiddleware instanceof DynamicSubagentsMiddleware dm) {
            dm.setGatewayBridge(bridge);
            agentManager = dm.getAgentManager();
        }

        // 接入已暴露子智能体恢复：实体化器可在任意节点重建智能体，
        // 分布式存储存在时，持久化注册表使 subagentId 能在
        // 进程之外解析。没有这些组件时，暴露仅限当前进程（旧行为）。
        if (agentManager != null) {
            final io.agentscope.harness.agent.subagent.DefaultAgentManager am = agentManager;
            gw.setSubagentMaterializer(am::createAgentIfPresent);
        }
        if (distributedStore != null) {
            gw.setSubagentRegistry(
                    new io.agentscope.harness.agent.gateway.StoreBackedSubagentRegistry(
                            distributedStore.baseStore()));
            gw.setBaseStore(distributedStore.baseStore());
            io.agentscope.harness.agent.gateway.SessionTurnGate turnGate =
                    distributedStore.sessionTurnGate();
            if (turnGate != null) {
                gw.setSessionTurnGate(turnGate);
            }
        }

        this.internalGateway = gw;
    }

    /**
     * @deprecated 请显式提供运行时上下文，使用 {@link #call(List, RuntimeContext)}。
     */
    @Deprecated(since = "2.2.0")
    @Override
    public Mono<Msg> call(List<Msg> msgs) {
        return wrappedCall(msgs, RuntimeContext.empty(), () -> delegate.call(msgs));
    }

    /**
     * @deprecated 请显式提供运行时上下文，使用 {@link #call(List, Class, RuntimeContext)}。
     */
    @Deprecated(since = "2.2.0")
    @Override
    public Mono<Msg> call(List<Msg> msgs, Class<?> structuredModel) {
        return wrappedCall(
                msgs, RuntimeContext.empty(), () -> delegate.call(msgs, structuredModel));
    }

    /**
     * @deprecated 请显式提供运行时上下文，使用 {@link #call(List, JsonNode, RuntimeContext)}。
     */
    @Deprecated(since = "2.2.0")
    @Override
    public Mono<Msg> call(List<Msg> msgs, JsonNode schema) {
        return wrappedCall(msgs, RuntimeContext.empty(), () -> delegate.call(msgs, schema));
    }

    public Mono<Msg> call(Msg msg, RuntimeContext ctx) {
        return call(List.of(msg), ctx);
    }

    /**
     * 使用纯文本输入及单次调用 {@link RuntimeContext} 调用智能体。
     *
     * @param text 输入文本（包装为 {@link UserMessage}）
     * @param ctx 单次调用运行时上下文
     * @return 回复消息
     */
    public Mono<Msg> call(String text, RuntimeContext ctx) {
        return call(new UserMessage(text), ctx);
    }

    public Mono<Msg> call(List<Msg> msgs, RuntimeContext ctx) {
        RuntimeContext effective =
                ensureSessionDefaults(ctx != null ? ctx : RuntimeContext.empty());
        return wrappedCall(msgs, effective, () -> delegate.call(msgs, effective));
    }

    public Mono<Msg> call(List<Msg> msgs, Class<?> structuredModel, RuntimeContext ctx) {
        RuntimeContext effective =
                ensureSessionDefaults(ctx != null ? ctx : RuntimeContext.empty());
        return wrappedCall(msgs, effective, () -> delegate.call(msgs, structuredModel, effective));
    }

    public Mono<Msg> call(List<Msg> msgs, JsonNode schema, RuntimeContext ctx) {
        RuntimeContext effective =
                ensureSessionDefaults(ctx != null ? ctx : RuntimeContext.empty());
        return wrappedCall(msgs, effective, () -> delegate.call(msgs, schema, effective));
    }

    /**
     * @deprecated 自 2.0.0 起弃用，计划移除。请通过 {@link #streamEvents(List)} 获取细粒度
     * {@code AgentEvent} 流，与 Python 2.0 的 {@code agent.reply_stream()} 对齐。
     */
    @Deprecated(since = "2.0.0", forRemoval = true)
    @Override
    public Flux<Event> stream(List<Msg> msgs, StreamOptions options) {
        return wrappedStream(RuntimeContext.empty(), () -> delegate.stream(msgs, options));
    }

    /**
     * @deprecated 自 2.0.0 起弃用，计划移除。请通过 {@link #streamEvents(List)} 获取细粒度 {@code AgentEvent} 流。
     */
    @Deprecated(since = "2.0.0", forRemoval = true)
    @Override
    public Flux<Event> stream(List<Msg> msgs, StreamOptions options, Class<?> structuredModel) {
        return wrappedStream(
                RuntimeContext.empty(), () -> delegate.stream(msgs, options, structuredModel));
    }

    /**
     * @deprecated 自 2.0.0 起弃用，计划移除。请通过 {@link #streamEvents(List)} 获取细粒度 {@code AgentEvent} 流。
     */
    @Deprecated(since = "2.0.0", forRemoval = true)
    @Override
    public Flux<Event> stream(List<Msg> msgs, StreamOptions options, JsonNode schema) {
        return wrappedStream(RuntimeContext.empty(), () -> delegate.stream(msgs, options, schema));
    }

    /**
     * @deprecated 自 2.0.0 起弃用，计划移除。请使用 {@link #streamEvents(Msg, RuntimeContext)}。
     */
    @Deprecated(since = "2.0.0", forRemoval = true)
    public Flux<Event> stream(Msg msg, RuntimeContext ctx) {
        return stream(List.of(msg), StreamOptions.defaults(), ctx);
    }

    /**
     * @deprecated 自 2.0.0 起弃用，计划移除。请使用 {@link #streamEvents(List, RuntimeContext)}。
     */
    @Deprecated(since = "2.0.0", forRemoval = true)
    public Flux<Event> stream(List<Msg> msgs, RuntimeContext ctx) {
        return stream(msgs, StreamOptions.defaults(), ctx);
    }

    /**
     * @deprecated 自 2.0.0 起弃用，计划移除。请使用 {@link #streamEvents(List, RuntimeContext)}。
     */
    @Deprecated(since = "2.0.0", forRemoval = true)
    public Flux<Event> stream(List<Msg> msgs, StreamOptions options, RuntimeContext ctx) {
        RuntimeContext effective =
                ensureSessionDefaults(ctx != null ? ctx : RuntimeContext.empty());
        return wrappedStream(effective, () -> delegate.stream(msgs, options, effective));
    }

    /**
     * @deprecated 自 2.0.0 起弃用，计划移除。请使用 {@link #streamEvents(List, RuntimeContext)}。
     */
    @Deprecated(since = "2.0.0", forRemoval = true)
    public Flux<Event> stream(
            List<Msg> msgs, StreamOptions options, Class<?> structuredModel, RuntimeContext ctx) {
        RuntimeContext effective =
                ensureSessionDefaults(ctx != null ? ctx : RuntimeContext.empty());
        return wrappedStream(
                effective, () -> delegate.stream(msgs, options, structuredModel, effective));
    }

    /**
     * @deprecated 自 2.0.0 起弃用，计划移除。请使用 {@link #streamEvents(List, RuntimeContext)}。
     */
    @Deprecated(since = "2.0.0", forRemoval = true)
    public Flux<Event> stream(
            List<Msg> msgs, StreamOptions options, JsonNode schema, RuntimeContext ctx) {
        RuntimeContext effective =
                ensureSessionDefaults(ctx != null ? ctx : RuntimeContext.empty());
        return wrappedStream(effective, () -> delegate.stream(msgs, options, schema, effective));
    }

    /**
     * 准备覆盖完整 Harness／沙箱生命周期的可取消执行。采用上下文 runId
     * （{@code run.runId() == ctx.getRunId()}）；{@code ensureSessionDefaults} 仍在订阅时运行且不改变它。
     * null 上下文使用新 {@link RuntimeContext#empty()}，使派生默认值继承该 runId。
     */
    public AgentRun<AgentEvent> prepareRun(List<Msg> msgs, RuntimeContext ctx) {
        RuntimeContext source = ctx != null ? ctx : RuntimeContext.empty();
        return AgentRun.create(getAgentId(), source.getRunId(), () -> streamEvents(msgs, source));
    }

    /**
     * 准备覆盖完整 Harness／沙箱生命周期的可取消回复执行。采用上下文 runId
     * （{@code run.runId() == ctx.getRunId()}）；{@code ensureSessionDefaults} 仍在订阅时运行且不改变它。
     * null 上下文使用新 {@link RuntimeContext#empty()}，使派生默认值继承该 runId。
     */
    public AgentRun<Msg> prepareCall(List<Msg> msgs, RuntimeContext ctx) {
        RuntimeContext source = ctx != null ? ctx : RuntimeContext.empty();
        return AgentRun.create(getAgentId(), source.getRunId(), () -> call(msgs, source));
    }

    // ==================== streamEvents（AgentEvent，与 v2 对齐） ====================

    /**
     * @deprecated 请显式提供运行时上下文，使用 {@link #streamEvents(Msg, RuntimeContext)}。
     */
    @Deprecated(since = "2.2.0")
    public Flux<AgentEvent> streamEvents(Msg msg) {
        return streamEvents(List.of(msg), RuntimeContext.empty());
    }

    /**
     * @deprecated 请显式提供运行时上下文，使用 {@link #streamEvents(List, RuntimeContext)}。
     */
    @Deprecated(since = "2.2.0")
    public Flux<AgentEvent> streamEvents(List<Msg> msgs) {
        return streamEvents(msgs, RuntimeContext.empty());
    }

    /**
     * 使用调用方提供的 {@link RuntimeContext}，为单条消息流式发送细粒度 {@link AgentEvent}。
     *
     * @param msg 输入消息
     * @param ctx 要传播到调用中的运行时上下文
     * @return 覆盖完整智能体调用生命周期的事件流
     */
    public Flux<AgentEvent> streamEvents(Msg msg, RuntimeContext ctx) {
        return streamEvents(List.of(msg), ctx);
    }

    /**
     * @deprecated 请显式提供运行时上下文，使用 {@link #streamEvents(String, RuntimeContext)}。
     */
    @Deprecated(since = "2.2.0")
    public Flux<AgentEvent> streamEvents(String text) {
        return streamEvents(new UserMessage(text), RuntimeContext.empty());
    }

    /**
     * 使用调用方提供的 {@link RuntimeContext}，为纯文本输入流式发送细粒度 {@link AgentEvent}。
     *
     * @param text 输入文本（包装为 {@link UserMessage}）
     * @param ctx 要传播到调用中的运行时上下文
     * @return 覆盖完整智能体调用生命周期的事件流
     */
    public Flux<AgentEvent> streamEvents(String text, RuntimeContext ctx) {
        return streamEvents(new UserMessage(text), ctx);
    }

    /**
     * 使用调用方提供的 {@link RuntimeContext}，为消息列表流式发送细粒度 {@link AgentEvent}。
     * Harness 包装内部 {@code ReActAgent#streamEvents(List, RuntimeContext)}，
     * 采用与 {@code call(...)} 系列相同的沙箱获取／释放语义，使流式及阻塞调用方在沙箱预热方面行为一致。
     *
     * <p>通过 {@code agent_spawn}／{@code agent_send} 启动的同步子智能体事件
     * 会实时转发到此流，携带标识来源子智能体的 {@link AgentEvent#getSource() source} 标签。
     *
     * @param msgs 输入消息列表
     * @param ctx 要传播到调用中的运行时上下文
     * @return 覆盖完整智能体调用生命周期的事件流
     */
    public Flux<AgentEvent> streamEvents(List<Msg> msgs, RuntimeContext ctx) {
        RuntimeContext effective =
                ensureSessionDefaults(ctx != null ? ctx : RuntimeContext.empty());
        return wrappedStreamEvents(effective, () -> delegate.streamEvents(msgs, effective));
    }

    @Override
    public Mono<Void> observe(Msg msg) {
        return delegate.observe(msg);
    }

    @Override
    public Mono<Void> observe(List<Msg> msgs) {
        return delegate.observe(msgs);
    }

    public Toolkit getToolkit() {
        return delegate.getToolkit();
    }

    // ==================== 调用／流式包装 ====================

    private Mono<Msg> wrappedCall(
            List<Msg> msgs, RuntimeContext effective, Supplier<Mono<Msg>> inner) {
        // 【学习提示】using 把沙箱占用绑定到订阅生命周期，而非方法调用时刻；
        // 完成、错误或取消都会进入资源清理路径，避免提前释放仍在使用的沙箱。
        Mono<Msg> base =
                Mono.using(
                        () -> {
                            if (sandboxLifecycleMw != null) {
                                sandboxLifecycleMw.acquireForCall(effective);
                            }
                            return effective;
                        },
                        eff -> inner.get(),
                        eff -> {
                            if (sandboxLifecycleMw != null) {
                                sandboxLifecycleMw.releaseForCall(eff);
                            }
                        });
        if (compactionHook != null && !delegate.sessionLogEnabled()) {
            return base.onErrorResume(
                    e -> {
                        if (CompactionMiddleware.isContextOverflowError(e)) {
                            return recoverFromOverflow(msgs, effective);
                        }
                        return Mono.error(e);
                    });
        }
        return base;
    }

    /**
     * @deprecated 自 2.0.0 起弃用，将与 {@link #stream(List, StreamOptions)} 系列一同移除。
     * 由 {@link #wrappedStreamEvents(RuntimeContext, Supplier)} 替代。
     */
    @Deprecated(since = "2.0.0", forRemoval = true)
    private Flux<Event> wrappedStream(RuntimeContext effective, Supplier<Flux<Event>> inner) {
        return Flux.using(
                () -> {
                    if (sandboxLifecycleMw != null) {
                        sandboxLifecycleMw.acquireForCall(effective);
                    }
                    return effective;
                },
                eff -> inner.get(),
                eff -> {
                    if (sandboxLifecycleMw != null) {
                        sandboxLifecycleMw.releaseForCall(eff);
                    }
                });
    }

    private Flux<AgentEvent> wrappedStreamEvents(
            RuntimeContext effective, Supplier<Flux<AgentEvent>> inner) {
        // 【学习提示】流式接口同样围绕整条事件流管理沙箱；消费方取消订阅也是释放边界，
        // 不能在返回 Flux 时释放，因为此时内部执行可能尚未开始。
        return Flux.using(
                () -> {
                    if (sandboxLifecycleMw != null) {
                        sandboxLifecycleMw.acquireForCall(effective);
                    }
                    return effective;
                },
                eff -> inner.get(),
                eff -> {
                    if (sandboxLifecycleMw != null) {
                        sandboxLifecycleMw.releaseForCall(eff);
                    }
                });
    }

    /**
     * 调用方未提供 {@code sessionId} 时填入默认值，并注入默认沙箱上下文。
     * 智能体持久化后端在构建时通过 {@code .stateStore(...)} 绑定；
     * 单次调用通过 RuntimeContext 的 {@code (userId, sessionId)} 路由，
     * 由 {@code ReActAgent.activateSlotForContext} 消费。
     */
    private RuntimeContext ensureSessionDefaults(RuntimeContext ctx) {
        RuntimeContext source = ctx != null ? ctx : RuntimeContext.empty();
        String ctxSessionId = source.getSessionId();
        if (ctxSessionId == null || ctxSessionId.isBlank()) {
            ctxSessionId = getName();
        }
        AbstractFilesystem sourceFs = source.get(AbstractFilesystem.class);
        SandboxContext sandboxCtx =
                source.get(SandboxContext.class) != null
                        ? source.get(SandboxContext.class)
                        : defaultSandboxContext;
        AbstractFilesystem fs = workspaceManager != null ? workspaceManager.getFilesystem() : null;

        if (ctxSessionId.equals(source.getSessionId())
                && sandboxCtx == source.get(SandboxContext.class)
                && (fs == null || sourceFs != null)) {
            return source;
        }
        RuntimeContext.Builder b = RuntimeContext.builder(source).sessionId(ctxSessionId);
        b.put(SandboxContext.class, sandboxCtx);
        if (sourceFs == null && fs != null) {
            b.put(AbstractFilesystem.class, fs);
        }
        if (workspaceManager != null) {
            b.put(WorkspaceManager.class, workspaceManager);
        }
        if (pathNormalizer != null) {
            b.put(WorkspacePathNormalizer.class, pathNormalizer);
        }
        return b.build();
    }

    private Mono<Msg> recoverFromOverflow(List<Msg> msgs, RuntimeContext effective) {
        if (compactionHook != null) {
            log.warn(
                    "Context overflow detected, triggering emergency compaction via"
                            + " CompactionMiddleware");
            return forceCompactAndRetry(msgs, effective);
        }
        return Mono.error(
                new RuntimeException(
                        "Context overflow: no compaction configured, unable to recover"));
    }

    /**
     * 压缩失败调用的上下文、持久化结果，再重试一次。
     *
     * <p>失败调用会持久化已累积状态，包括本轮输入；重试从状态存储重新加载会话。
     * 因此必须先持久化压缩后上下文再重试，否则重新加载会恢复原先超大的上下文。
     * 输入已在上下文中时，重试使用空输入从现有状态继续，避免重复追加。
     */
    private Mono<Msg> forceCompactAndRetry(List<Msg> msgs, RuntimeContext effective) {
        RuntimeContext ctx = effective != null ? effective : RuntimeContext.empty();
        AgentState state = RuntimeContext.resolveAgentState(effective, delegate);
        List<Msg> allMsgs = state.contextMutable();
        if (allMsgs.isEmpty()) {
            return Mono.error(
                    new RuntimeException("Context overflow: context is empty, cannot compact"));
        }
        // 压缩可能将输入折叠进摘要，因此先作判断。
        List<Msg> retryInput = isInContext(msgs, allMsgs) ? List.of() : msgs;
        String agentId = getName();
        String sessionId =
                effective != null && effective.getSessionId() != null
                        ? effective.getSessionId()
                        : "default";

        CompactionConfig forceConfig = CompactionConfig.builder().triggerMessages(1).build();
        String effectiveFlushPrompt =
                memoryConfig.flushPrompt() != null
                        ? memoryConfig.flushPrompt()
                        : MemoryFlushManager.DEFAULT_FLUSH_PROMPT;
        MemoryFlushManager fm =
                new MemoryFlushManager(workspaceManager, getModel(), effectiveFlushPrompt);
        ConversationCompactor compactor = new ConversationCompactor(getModel(), fm);

        return compactor
                .compactIfNeeded(ctx, allMsgs, forceConfig, agentId, sessionId)
                .flatMap(
                        opt -> {
                            if (opt.isEmpty()) {
                                return Mono.error(
                                        new RuntimeException(
                                                "Context overflow: emergency compaction yielded no"
                                                        + " result"));
                            }
                            List<Msg> compacted = opt.get();
                            return Mono.fromRunnable(
                                            () ->
                                                    delegate.updateAgentState(
                                                            ctx,
                                                            "overflow_compaction",
                                                            s -> {
                                                                s.contextMutable().clear();
                                                                s.contextMutable()
                                                                        .addAll(compacted);
                                                            }))
                                    .subscribeOn(Schedulers.boundedElastic())
                                    .then(Mono.defer(() -> delegate.call(retryInput, ctx)));
                        });
    }

    /**
     * 按 ID 判断 {@code input} 中每条消息是否已存在于 {@code context}。
     */
    private static boolean isInContext(List<Msg> input, List<Msg> context) {
        if (input == null || input.isEmpty()) {
            return false;
        }
        Set<String> contextIds = new HashSet<>();
        for (Msg msg : context) {
            if (msg.getId() != null) {
                contextIds.add(msg.getId());
            }
        }
        return input.stream().allMatch(m -> m.getId() != null && contextIds.contains(m.getId()));
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * 内置 {@link JsonFileAgentStateStore} 后端的默认状态目录：{@code ~/.agentscope/state/<agentId>/}。
     * 位于所有工作区之外，避免智能体状态与工作区数据耦合；智能体状态本身又是通过
     * {@link io.agentscope.harness.agent.sandbox.SandboxState#getWorkspaceSpec()} 恢复工作区的前提。
     *
     * <p>基础目录 {@code ~/.agentscope/state} 可通过 {@code agentscope.state.home} 系统属性覆盖。
     * 主要供测试／CI 将状态重定向到构建临时目录，避免污染开发者主目录；生产部署通常不需要。
     */
    static Path defaultStateDir(String agentId) {
        String override = System.getProperty("agentscope.state.home");
        Path root =
                override != null && !override.isBlank()
                        ? Paths.get(override)
                        : Paths.get(System.getProperty("user.home"), ".agentscope", "state");
        return root.resolve(agentId);
    }

    /**
     * 覆盖默认工作区目录的系统属性。
     */
    static final String WORKSPACE_PROPERTY = "agentscope.workspace";

    /**
     * 覆盖默认工作区目录的环境变量。
     */
    static final String WORKSPACE_ENV = "AGENTSCOPE_WORKSPACE";

    /**
     * 未显式设置 {@link Builder#workspace(Path)}／{@link Builder#workspace(String)} 时，解析要使用的工作区目录。
     *
     * <p>解析优先级从高到低：
     * <ol>
     * <li>{@code agentscope.workspace} 系统属性</li>
     * <li>{@code AGENTSCOPE_WORKSPACE} 环境变量</li>
     * <li>{@code ${user.dir}/.agentscope/workspace}（内置默认值）</li>
     * </ol>
     *
     * <p>系统属性／环境变量主要适用于容器镜像部署，在运行时注入工作区位置，避免硬编码进应用。
     *
     * @return 解析出的默认工作区目录，绝不为 {@code null}
     */
    static Path resolveDefaultWorkspace() {
        String property = System.getProperty(WORKSPACE_PROPERTY);
        if (property != null && !property.isBlank()) {
            return Paths.get(property.strip());
        }
        String env = System.getenv(WORKSPACE_ENV);
        if (env != null && !env.isBlank()) {
            return Paths.get(env.strip());
        }
        return Paths.get(System.getProperty("user.dir")).resolve(".agentscope/workspace");
    }

    /**
     * 给定会话为无法跨节点共享状态的本地进程内实现时返回 true。
     * 用于沙箱／远程文件系统快速失败检查，拒绝会在分布式部署中悄悄产生节点独立状态的配置。
     */
    static boolean isLocalSession(AgentStateStore stateStore) {
        return stateStore instanceof JsonFileAgentStateStore
                || stateStore instanceof InMemoryAgentStateStore;
    }

    // ==================== 构建器 ====================

    /**
     * {@link HarnessAgent} 构建器，负责 Harness 编排：工作区、文件系统、沙箱、钩子／中间件、
     * 工具、技能、子智能体、tools.json 及计划模式。
     */
    public static class Builder {
        private SessionLogStore sessionLogStore;
        private boolean legacySessionHistory;
        private SessionHistoryMode historyMode = SessionHistoryMode.EVENT_LOG;

        java.util.function.Consumer<Builder> sessionHistoryConfigurer() {
            var mode = historyMode;
            var store = sessionLogStore;
            return child -> {
                child.sessionHistoryMode(mode);
                if (store != null) child.sessionLogStore(store);
            };
        }

        public Builder sessionHistoryMode(SessionHistoryMode mode) {
            this.legacySessionHistory = mode == SessionHistoryMode.LEGACY;
            this.historyMode = mode;
            inner.sessionHistoryMode(mode);
            return this;
        }

        /**
         * 覆盖原生历史存储，同时保留运行时提交语义。
         */
        public Builder sessionLogStore(SessionLogStore store) {
            this.sessionLogStore = Objects.requireNonNull(store);
            return this;
        }

        /**
         * 为没有原子存储的既有应用／后端显式启用兼容模式。
         */
        public Builder legacySessionHistory(boolean legacy) {
            this.legacySessionHistory = legacy;
            this.historyMode = legacy ? SessionHistoryMode.LEGACY : SessionHistoryMode.EVENT_LOG;
            inner.sessionHistoryMode(
                    legacy ? SessionHistoryMode.LEGACY : SessionHistoryMode.EVENT_LOG);
            return this;
        }

        private final ReActAgent.Builder inner = ReActAgent.builder();

        // ---- HarnessAgentBuilderSupport 可读取的镜像字段 ----
        // 镜像转发给 `inner` 的值，使辅助方法及子智能体工厂
        // 无需跨越 ReActAgent.Builder 的包可见边界即可读取。

        String name;
        String description;
        String sysPrompt;
        boolean checkRunning = true;
        boolean enablePendingToolRecovery = false;
        Model model;
        Toolkit toolkit = newDefaultToolkit();
        int maxIters = 10;
        ExecutionConfig modelExecutionConfig;
        ExecutionConfig toolExecutionConfig;
        GenerateOptions generateOptions;
        final Set<Hook> hooks = new LinkedHashSet<>();
        final List<MiddlewareBase> middlewares = new ArrayList<>();

        // ---- Harness 编排字段 ----

        String agentId;
        final List<AgentSkillRepository> skillRepositories = new ArrayList<>();
        Path projectGlobalSkillsDir;

        Path workspace;
        String environmentMemory;
        AbstractFilesystem abstractFilesystem;
        boolean leafSubagent = false;
        boolean agentTracingLogEnabled = true;
        CompactionConfig compactionConfig = CompactionConfig.builder().build();
        MemoryConfig memoryConfig = MemoryConfig.defaults();
        ToolResultEvictionConfig toolResultEvictionConfig = ToolResultEvictionConfig.defaults();
        boolean disableCompaction = false;
        ContextPolicy contextPolicy = ContextPolicy.defaults();
        ContextSources contextSources = ContextSources.empty();
        ContextSelectionPolicy contextSelectionPolicy = ContextSelectionPolicy.defaults();

        /**
         * 在每次推理请求之前加载只读业务数据，不在注册时加载。
         */
        public Builder contextSource(String id, ContextSource source) {
            contextSources = contextSources.withSource(id, source, ContextSourceOptions.defaults());
            return this;
        }

        /**
         * 来源失败策略与预算驱逐策略相互独立。
         */
        public Builder contextSource(
                String id, ContextSource source, Consumer<ContextSourceOptions.Builder> configure) {
            var options = ContextSourceOptions.builder();
            Objects.requireNonNull(configure).accept(options);
            contextSources = contextSources.withSource(id, source, options.build());
            return this;
        }

        /**
         * 从可信应用配置添加具名稳定指令。
         */
        public Builder instruction(String id, String content) {
            contextSources = contextSources.withInstruction(id, content);
            return this;
        }

        /**
         * 选择任务投影及可选候选工具，不运行验证。
         */
        public Builder taskContext(TaskContextOptions options) {
            contextSources = contextSources.withTaskContext(options);
            return this;
        }

        public Builder contextSelectionPolicy(ContextSelectionPolicy policy) {
            contextSelectionPolicy = Objects.requireNonNull(policy);
            return this;
        }

        /**
         * 配置最终模型输入预算及仅含元数据的上下文观测。
         */
        public Builder contextPolicy(ContextPolicy policy) {
            this.contextPolicy = Objects.requireNonNull(policy);
            return this;
        }

        boolean disableToolResultEviction = false;

        final List<SubagentDeclaration> subagentDeclarations = new ArrayList<>();
        final List<HarnessAgentBuilderSupport.SubagentFactoryEntry> customSubagentFactories =
                new ArrayList<>();
        TaskRepository taskRepository;
        Object externalSubagentTool;
        Function<String, Model> modelResolver;
        final List<String> additionalContextFiles = new ArrayList<>();
        int maxContextTokens = 8000;

        ArtifactDeliveryTarget artifactDeliveryTarget;
        boolean disableFilesystemTools = false;
        boolean disableShellTool = false;
        boolean disableWebTools = false;

        /**
         * 供内置网页工具使用的可选自定义客户端；{@code null} 表示默认值。
         */
        HttpClient webHttpClient;

        boolean disableMemoryTools = false;
        boolean disableMemoryHooks = false;
        boolean disableSessionPersistence = false;
        boolean disableWorkspaceContext = false;
        boolean disableAtPathExpansion = false;
        boolean disableSubagents = false;
        boolean disableDynamicSkills = false;
        boolean disableDefaultWorkspaceSkills = false;
        boolean disableDynamicSubagents = false;
        boolean disableToolsConfig = false;

        boolean skillManageToolEnabled = false;
        SkillManageConfig skillManageConfig;
        SkillPromotionGate promotionGate;
        SkillVisibilityFilter visibilityFilter;
        String environment = "prod";
        boolean skillCuratorEnabled = false;
        SkillCuratorConfig skillCuratorConfig;
        io.agentscope.core.skill.SkillFilter skillFilter;
        PermissionContextState permissionContextOverride;

        boolean planModeEnabled = false;
        boolean planModeAllowShell = false;
        String planFileDir = PlanModeManager.DEFAULT_PLAN_DIR;

        ToolsConfig toolsConfigOverride;
        McpServerRegistrationListener mcpServerRegistrationListener;

        SandboxFilesystemSpec sandboxFilesystemSpec;
        RemoteFilesystemSpec remoteFilesystemSpec;
        LocalFilesystemSpec localFilesystemSpec;
        final Map<String, AbstractFilesystem> filesystemRoutes = new LinkedHashMap<>();

        // 旧版状态持久化及沙箱基础设施槽位。原生对话
        // 恢复始终使用 SessionLogStore，与此可选后端无关。
        AgentStateStore stateStoreOverride;

        DistributedStore distributedStore;

        io.agentscope.harness.agent.bus.MessageBus messageBus;
        java.time.Duration asyncToolTimeout;
        io.agentscope.harness.agent.bus.AsyncToolRegistry asyncToolRegistry;

        io.agentscope.harness.agent.team.TeamClient teamsModeClient;
        io.agentscope.harness.agent.team.TeamContext teamsModeContext;
        String teamsModeSessionId;

        private Builder() {}

        /**
         * 启用 AgentTeams 模式：附加 {@link io.agentscope.harness.agent.middleware.TeamsMiddleware}，
         * 并注册按角色裁剪能力的 {@code team} 工具。用于托管 resolve({@code teamContext})，
         * 以及声明成员模板的 Entry-B 主导会话。
         */
        public Builder teamsMode(
                io.agentscope.harness.agent.team.TeamClient teamClient,
                io.agentscope.harness.agent.team.TeamContext teamContext) {
            return teamsMode(teamClient, teamContext, null);
        }

        /**
         * 与 {@link #teamsMode(io.agentscope.harness.agent.team.TeamClient, io.agentscope.harness.agent.team.TeamContext)}
         * 相同，但还将中间件绑定到 {@code sessionId}，使发给该会话的控制平面 TeamEvent 到达此智能体。
         */
        public Builder teamsMode(
                io.agentscope.harness.agent.team.TeamClient teamClient,
                io.agentscope.harness.agent.team.TeamContext teamContext,
                String sessionId) {
            this.teamsModeClient = teamClient;
            this.teamsModeContext = teamContext;
            this.teamsModeSessionId = sessionId;
            return this;
        }

        /**
         * 返回新的 {@link Builder}，尽可能预填可通过公共访问方法读出的 {@link ReActAgent} 可观测配置。
         *
         * <p>这是部分迁移辅助方法。调用方仍须显式配置工作区、文件系统、沙箱、子智能体、技能、计划模式等
         * Harness 专属能力；普通 {@link ReActAgent} 没有对应项，不能从 {@code agent} 推导。
         *
         * <h4>本方法复制的内容</h4>
         * <table border="1">
         *   <caption>从源 ReActAgent 复制的字段</caption>
         *   <tr><th>分组</th><th>字段</th><th>来源</th></tr>
         *   <tr><td rowspan="7">可观测配置</td>
         *       <td>{@code name}</td><td>{@code agent.getName()}</td></tr>
         *   <tr><td>{@code description}</td><td>{@code agent.getDescription()}</td></tr>
         *   <tr><td>{@code sysPrompt}</td><td>{@code agent.getSysPrompt()}</td></tr>
         *   <tr><td>{@code model}</td><td>{@code agent.getModel()}</td></tr>
         *   <tr><td>{@code maxIters}</td><td>{@code agent.getMaxIters()}</td></tr>
         *   <tr><td>{@code generateOptions}</td><td>{@code agent.getGenerateOptions()}</td></tr>
         *   <tr><td>{@code toolkit}</td><td>防御性复制，使用 {@code agent.getToolkit().copy()}</td></tr>
         *   <tr><td rowspan="3">持久化</td>
         *       <td>{@code session}</td><td>{@code agent.getStateStore()} 非 null 时</td></tr>
         *   <tr><td>{@code conflictPolicy}</td><td>{@code agent.getConflictPolicy()}</td></tr>
         *   <tr><td>{@code defaultSessionId}</td><td>{@code agent.getDefaultSessionId()} 非 null 时</td></tr>
         *   <tr><td rowspan="3">模型韧性（来自 {@code agent.getModelConfig()})</td>
         *       <td>{@code maxRetries}</td><td>{@link ModelConfig#maxRetries()}</td></tr>
         *   <tr><td>{@code fallbackModel}</td><td>{@link ModelConfig#fallbackModel()} 非 null 时</td></tr>
         *   <tr><td>{@code failoverListener}</td><td>{@link ModelConfig#failoverListener()} 非 null 时</td></tr>
         *   <tr><td>推理循环（来自 {@code agent.getReactConfig()})</td>
         *       <td>{@code stopOnReject}</td><td>{@link ReactConfig#stopOnReject()}</td></tr>
         *   <tr><td rowspan="2">执行</td>
         *       <td>{@code modelExecutionConfig}</td><td>{@code agent.getModelExecutionConfig()} 非 null 时</td></tr>
         *   <tr><td>{@code toolExecutionConfig}</td><td>{@code agent.getToolExecutionConfig()} 非 null 时</td></tr>
         *   <tr><td rowspan="3">行为</td>
         *       <td>{@code toolExecutionContext}</td><td>{@code agent.getToolExecutionContext()} 非 null 时</td></tr>
         *   <tr><td>{@code enablePendingToolRecovery}</td><td>{@code agent.isPendingToolRecoveryEnabled()}</td></tr>
         *   <tr><td>{@code checkRunning}</td><td>{@code agent.isCheckRunning()}</td></tr>
         *   <tr><td>权限</td>
         *       <td>{@code permissionContext}</td><td>{@code agent.getPermissionContext()} 非 null 时
         *           (复用同一 {@link PermissionContextState}，它携带已注册
         *           到源引擎上的规则)</td></tr>
         *   <tr><td>扩展接口</td>
         *       <td>{@code middlewares}</td><td>{@code agent.getMiddlewares()} 复制，但排除
         *           Harness 运行时中间件</td></tr>
         *   <tr><td>旧版扩展</td>
         *       <td>{@code hooks}</td><td>{@code agent.getHooks()} 原样追加（{@link Hook}
         *           本身已标记 {@code @Deprecated(forRemoval=true)}，新代码优先使用中间件，
         *           不建议继续使用钩子）</td></tr>
         * </table>
         *
         * <p>注意：{@code enableMetaTool} 和 {@code enableTaskList} 是构建时修改工具集的标志。
         * 不会以标志形式往返复制，但工具集副本已携带其注册工具，因此结果智能体拥有相同工具集合。
         *
         * <h4>本方法不复制的内容</h4>
         * <p><b>跳过 Harness 专属内容，因为 {@code ReActAgent} 没有来源。</b>
         * 若需要 HarnessAgent 语义，必须在返回的构建器上配置：
         * <ul>
         *   <li>工作区及文件系统: {@link #workspace(Path)}, {@link #filesystem(SandboxFilesystemSpec)},
         *       {@link #filesystem(LocalFilesystemSpec)}, {@link #filesystem(RemoteFilesystemSpec)},
         *       {@link #abstractFilesystem(AbstractFilesystem)},
         *       {@link #environmentMemory(String)}</li>
         *   <li>子智能体: {@link #subagent(SubagentDeclaration)}, {@link #subagents(List)},
         *       {@link #subagentFactory(String, Function)},
         *       {@link #subagentFactory(SubagentDeclaration, SubagentFactory)},
         *       {@link #externalSubagentTool(Object)},
         *       {@link #taskRepository(TaskRepository)}, {@link #modelResolver(Function)}</li>
         *   <li>技能治理: {@link #skillRepository(AgentSkillRepository)},
         *       {@link #projectGlobalSkillsDir(Path)},
         *       {@link #enableSkillManageTool(SkillManageConfig)},
         *       {@link #enableSkillCurator(SkillCuratorConfig)},
         *       {@link #enableSkillPromotionGate(SkillPromotionGate, SkillVisibilityFilter)},
         *       {@link #skillFilter(io.agentscope.core.skill.SkillFilter)},
         *       {@link #environment(String)}</li>
         *   <li>计划模式: {@link #enablePlanMode()}, {@link #planFileDirectory(String)}</li>
         *   <li>上下文工程: {@link #additionalContextFile(String)},
         *       {@link #maxContextTokens(int)}, {@link #compaction(CompactionConfig)},
         *       {@link #toolResultEviction(ToolResultEvictionConfig)},
         *       {@link #toolsConfig(ToolsConfig)},
         *       {@link #mcpServerRegistrationListener(McpServerRegistrationListener)}</li>
         *   <li>所有 {@code disableXxx()} 开关以及 {@link #enableAgentTracingLog(boolean)}</li>
         * </ul>
         *
         * <h4>行为注意事项</h4>
         * <p>即使使用此方法，构建的 {@code HarnessAgent} 也不与源 {@code ReActAgent} 行为等价。
         * HarnessAgent 会安装源对象没有的额外编排（工作区投影、智能体追踪中间件、默认技能／子智能体中间件）。
         * 原生会话历史默认使用工作区后端；显式旧版模式使用
         * {@code ~/.agentscope/state/<agentId>/} 下的 JsonFileAgentStateStore。已有快照需要显式迁移。
         *
         * @param agent 要继承可观测配置的源 {@link ReActAgent}
         * @return 预填可继承子集的新 {@link Builder}
         */
        public static Builder fromAgent(ReActAgent agent) {
            Builder b = new Builder();
            if (agent.getModelRequestPreparer() instanceof HarnessContextBuilder contextBuilder) {
                b.contextPolicy(contextBuilder.policy());
                b.contextSources = contextBuilder.sources();
                b.contextSelectionPolicy(contextBuilder.selectionPolicy());
            }

            // 可观测配置。
            b.name(agent.getName());
            b.description(agent.getDescription());
            b.sysPrompt(agent.getSysPrompt());
            b.model(agent.getModel());
            b.maxIters(agent.getMaxIters());
            b.generateOptions(agent.getGenerateOptions());
            b.toolkit(agent.getToolkit().copy());
            if (b.contextSources.taskContext().requirementProposals()) {
                // 构建时根据复制的任务选项重新注册，使
                // 调用方能够在副本上禁用提议而不残留原工具。
                b.toolkit.removeTool("task_requirement_propose");
            }

            // 持久化。
            AgentStateStore srcSession = agent.getStateStore();
            if (srcSession != null) {
                b.stateStore(srcSession);
            }
            b.conflictPolicy(agent.getConflictPolicy());
            String srcDefaultSessionId = agent.getDefaultSessionId();
            if (srcDefaultSessionId != null) {
                b.defaultSessionId(srcDefaultSessionId);
            }

            // 模型韧性。
            ModelConfig mc = agent.getModelConfig();
            if (mc != null) {
                b.maxRetries(mc.maxRetries());
                if (mc.fallbackModel() != null) {
                    b.fallbackModel(mc.fallbackModel());
                }
                if (mc.failoverListener() != null) {
                    b.failoverListener(mc.failoverListener());
                }
            }

            // 推理循环：maxIters 已在上面处理，只剩 stopOnReject。
            ReactConfig rc = agent.getReactConfig();
            if (rc != null) {
                b.stopOnReject(rc.stopOnReject());
            }

            // 执行配置。
            ExecutionConfig srcModelExec = agent.getModelExecutionConfig();
            if (srcModelExec != null) {
                b.modelExecutionConfig(srcModelExec);
            }
            ExecutionConfig srcToolExec = agent.getToolExecutionConfig();
            if (srcToolExec != null) {
                b.toolExecutionConfig(srcToolExec);
            }

            // 行为标志及工具执行上下文。
            ToolExecutionContext srcToolCtx = agent.getToolExecutionContext();
            if (srcToolCtx != null) {
                b.toolExecutionContext(srcToolCtx);
            }
            b.enablePendingToolRecovery(agent.isPendingToolRecoveryEnabled());
            b.checkRunning(agent.isCheckRunning());

            // 权限上下文（同一实例，携带源对象注册的规则）。
            PermissionContextState srcPerm = agent.getPermissionContext();
            if (srcPerm != null) {
                b.permissionContext(srcPerm);
            }

            // 扩展链：中间件是 v2 接口，钩子保留用于 v1 兼容。
            List<MiddlewareBase> srcMiddlewares = agent.getMiddlewares();
            if (srcMiddlewares != null && !srcMiddlewares.isEmpty()) {
                List<MiddlewareBase> copyable = filterCopyableMiddlewares(srcMiddlewares);
                if (b.contextSources.taskContext().requirementProposals()) {
                    // 任务配置会重建提醒。保留复制的待办能力，
                    // 但不添加第二个需求依据块。
                    for (MiddlewareBase middleware : copyable) {
                        if (middleware.getClass() == TaskReminderMiddleware.class
                                && ((TaskReminderMiddleware) middleware).todoEnabled()) {
                            b.enableTaskList();
                        }
                    }
                    copyable =
                            copyable.stream()
                                    .filter(
                                            middleware ->
                                                    middleware.getClass()
                                                            != TaskReminderMiddleware.class)
                                    .toList();
                }
                b.middlewares(copyable);
            }
            List<Hook> srcHooks = agent.getHooks();
            if (srcHooks != null && !srcHooks.isEmpty()) {
                b.hooks(srcHooks);
            }

            return b;
        }

        // ---- 转发设置方法（代理给 inner 并镜像） ----

        public Builder name(String name) {
            this.name = name;
            inner.name(name);
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            inner.description(description);
            return this;
        }

        public Builder sysPrompt(String sysPrompt) {
            this.sysPrompt = sysPrompt;
            inner.sysPrompt(sysPrompt);
            return this;
        }

        public Builder checkRunning(boolean checkRunning) {
            this.checkRunning = checkRunning;
            inner.checkRunning(checkRunning);
            return this;
        }

        public Builder model(Model model) {
            this.model = model;
            inner.model(model);
            return this;
        }

        public Builder model(String modelId) {
            Model resolved = io.agentscope.core.model.ModelRegistry.resolve(modelId);
            this.model = resolved;
            inner.model(resolved);
            return this;
        }

        public Builder toolkit(Toolkit toolkit) {
            this.toolkit = toolkit != null ? toolkit : newDefaultToolkit();
            // 暂不传给 inner，编排阶段将向此工具集注册 Harness 工具，
            // 随后在 build() 时通过 inner.toolkit(...) 传递最终结果。
            return this;
        }

        /**
         * Harness 智能体默认工具集。使用 {@link Toolkit} 默认配置（启用并行工具执行）。
         * 如需关闭，可传入配置了 {@code ToolkitConfig.parallel(false)} 的自定义 {@link Toolkit}。
         */
        static Toolkit newDefaultToolkit() {
            return new Toolkit();
        }

        public Builder maxIters(int maxIters) {
            this.maxIters = maxIters;
            inner.maxIters(maxIters);
            return this;
        }

        public Builder modelExecutionConfig(ExecutionConfig config) {
            this.modelExecutionConfig = config;
            inner.modelExecutionConfig(config);
            return this;
        }

        public Builder toolExecutionConfig(ExecutionConfig config) {
            this.toolExecutionConfig = config;
            inner.toolExecutionConfig(config);
            return this;
        }

        public Builder generateOptions(GenerateOptions options) {
            this.generateOptions = options;
            inner.generateOptions(options);
            return this;
        }

        public Builder hook(Hook hook) {
            if (hook != null) {
                hooks.add(hook);
                inner.hook(hook);
            }
            return this;
        }

        public Builder hooks(List<Hook> hooks) {
            if (hooks != null) {
                for (Hook h : hooks) {
                    hook(h);
                }
            }
            return this;
        }

        public Builder middleware(MiddlewareBase middleware) {
            if (middleware != null) {
                middlewares.add(middleware);
                inner.middleware(middleware);
            }
            return this;
        }

        public Builder middlewares(List<? extends MiddlewareBase> middlewareList) {
            if (middlewareList != null) {
                for (MiddlewareBase middleware : middlewareList) {
                    middleware(middleware);
                }
            }
            return this;
        }

        private static List<MiddlewareBase> filterCopyableMiddlewares(
                List<MiddlewareBase> middlewares) {
            // 仅保留源智能体可观测的注册项。
            List<MiddlewareBase> copyable = new ArrayList<>(middlewares.size());
            for (MiddlewareBase middleware : middlewares) {
                if (middleware != null
                        && !(middleware instanceof HarnessRuntimeMiddleware)
                        && !(middleware instanceof GracefulShutdownMiddleware)) {
                    copyable.add(middleware);
                }
            }
            return copyable;
        }

        /**
         * 旧版快照后端及沙箱元数据存储。原生对话恢复使用 {@link #sessionLogStore(SessionLogStore)}，
         * 绝不隐式导入此后端。
         */
        public Builder stateStore(AgentStateStore stateStore) {
            this.stateStoreOverride = stateStore;
            inner.stateStore(stateStore);
            return this;
        }

        /**
         * {@code agent_state} 保存与其他写入者更新冲突时应用的策略。
         * 默认为 {@link ConflictPolicy#OVERWRITE}，即后写覆盖且不报错。
         *
         * @param conflictPolicy 冲突时应用的策略
         * @return 当前构建器
         */
        public Builder conflictPolicy(ConflictPolicy conflictPolicy) {
            inner.conflictPolicy(conflictPolicy);
            return this;
        }

        /**
         * 配置一次提供全部存储组件的分布式存储：{@link AgentStateStore}、
         * {@link io.agentscope.harness.agent.filesystem.remote.store.BaseStore}、
         * {@link io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec} 及
         * {@link io.agentscope.harness.agent.sandbox.SandboxExecutionGuard}。
         *
         * <p>显式构建器方法（{@code stateStore()}、{@code filesystem()}）对所配置组件的优先级高于分布式存储。
         *
         * @param store 要使用的分布式存储
         * @return 当前构建器
         */
        public Builder distributedStore(DistributedStore store) {
            this.distributedStore = store;
            return this;
        }

        public Builder defaultSessionId(String defaultSessionId) {
            inner.defaultSessionId(defaultSessionId);
            return this;
        }

        public Builder toolExecutionContext(ToolExecutionContext ctx) {
            inner.toolExecutionContext(ctx);
            return this;
        }

        public Builder enableMetaTool(boolean enableMetaTool) {
            inner.enableMetaTool(enableMetaTool);
            return this;
        }

        /**
         * 启用新消息到达时对孤立工具调用的恢复。自动构建的本地子智能体继承此设置，除非声明显式覆盖。
         * 待处理权限确认仍需要显式确认结果。
         *
         * @param enable 是否为孤立工具调用合成错误结果
         * @return 当前构建器
         */
        public Builder enablePendingToolRecovery(boolean enable) {
            this.enablePendingToolRecovery = enable;
            inner.enablePendingToolRecovery(enable);
            return this;
        }

        public Builder enableTaskList() {
            inner.enableTaskList();
            return this;
        }

        public Builder enableTaskList(boolean enabled) {
            inner.enableTaskList(enabled);
            return this;
        }

        public Builder maxRetries(int maxRetries) {
            inner.maxRetries(maxRetries);
            return this;
        }

        public Builder fallbackModel(Model fallbackModel) {
            inner.fallbackModel(fallbackModel);
            return this;
        }

        public Builder fallbackModel(String modelId) {
            inner.fallbackModel(modelId);
            return this;
        }

        /**
         * 设置失败主模型被回退模型接管时通知的监听器，委托给内部 {@link io.agentscope.core.ReActAgent.Builder}。
         *
         * @see FailoverListener 线程及失败契约
         */
        public Builder failoverListener(FailoverListener failoverListener) {
            inner.failoverListener(failoverListener);
            return this;
        }

        public Builder stopOnReject(boolean stopOnReject) {
            inner.stopOnReject(stopOnReject);
            return this;
        }

        public Builder permissionContext(PermissionContextState permissionContext) {
            this.permissionContextOverride = permissionContext;
            inner.permissionContext(permissionContext);
            return this;
        }

        // ---- Harness 专有设置方法 ----

        /**
         * 设置用作组合文件系统中智能体命名空间键的稳定标识符
         * （例如 {@code [agents, <agentId>, users, <userId>, ...]}），并通过内部智能体作为 {@link #getAgentId()} 返回。
         * 未设置时，{@link #build()} 使用 {@link #name(String)} 作为命名空间键，内部智能体仍保留生成的 UUID。
         * 格式契约见 {@link AgentBase#normalizeAgentId(String)}，在 {@code build()} 时验证。
         *
         * <p>通常无需设置，生成的 UUID 默认唯一。仅在外部管理 ID（例如重启后稳定命名空间路径）时设置。
         * 唯一性由你负责，框架不强制检查；须保证活动智能体 ID 始终唯一，
         * 否则可能在文件系统命名空间、状态存储及消息路由中冲突。
         */
        public Builder agentId(String agentId) {
            this.agentId = agentId;
            inner.agentId(agentId);
            return this;
        }

        /**
         * 添加市场／外部技能仓库（例如 {@code GitSkillRepository}）。仓库与工作区技能叠加组合。
         */
        public Builder skillRepository(AgentSkillRepository skillRepository) {
            if (skillRepository != null) {
                this.skillRepositories.add(skillRepository);
            }
            return this;
        }

        /**
         * 用给定集合替换当前市场仓库列表。
         */
        public Builder skillRepositories(List<AgentSkillRepository> repositories) {
            this.skillRepositories.clear();
            if (repositories != null) {
                for (AgentSkillRepository repo : repositories) {
                    if (repo != null) {
                        this.skillRepositories.add(repo);
                    }
                }
            }
            return this;
        }

        /**
         * 配置项目全局技能目录，位于市场及工作区技能之下，优先级最低。
         */
        public Builder projectGlobalSkillsDir(Path projectGlobalSkillsDir) {
            this.projectGlobalSkillsDir = projectGlobalSkillsDir;
            return this;
        }

        /**
         * 设置工作区目录。传入 {@code null} 使用默认 {@code ${cwd}/.agentscope/workspace}。
         */
        /**
         * 设置工作区目录。
         *
         * <p>未设置时，在 {@link #build()} 阶段通过 {@link HarnessAgent#resolveDefaultWorkspace()} 解析：
         * 先看 {@code agentscope.workspace} 系统属性，再看 {@code AGENTSCOPE_WORKSPACE} 环境变量，
         * 最后回退到 {@code ${user.dir}/.agentscope/workspace}。在此显式设置优先于系统属性和环境变量。
         *
         * @param workspace 工作区目录；{@code null} 表示使用解析出的默认值
         * @return 当前构建器
         */
        public Builder workspace(Path workspace) {
            this.workspace = workspace;
            return this;
        }

        /**
         * 通过文件系统路径字符串设置工作区目录。
         *
         * <p>未设置时的回退行为参见 {@link #workspace(Path)}。
         *
         * @param path 工作区目录路径；{@code null} 表示使用解析出的默认值
         * @return 当前构建器
         */
        public Builder workspace(String path) {
            if (path == null) {
                this.workspace = null;
            } else {
                String trimmed = path.strip();
                if (trimmed.isEmpty()) {
                    throw new IllegalArgumentException("workspace path must not be blank");
                }
                this.workspace = Path.of(trimmed);
            }
            return this;
        }

        public Builder environmentMemory(String environmentMemory) {
            this.environmentMemory = environmentMemory;
            return this;
        }

        /**
         * 高级扩展入口：直接设置自定义 {@link AbstractFilesystem} 实现。
         */
        public Builder abstractFilesystem(AbstractFilesystem store) {
            this.abstractFilesystem = store;
            return this;
        }

        /**
         * 配置模式 2：沙箱文件系统。
         */
        public Builder filesystem(SandboxFilesystemSpec spec) {
            this.sandboxFilesystemSpec = spec;
            return this;
        }

        /**
         * 配置模式 1：组合式（非沙箱）文件系统。
         */
        public Builder filesystem(RemoteFilesystemSpec spec) {
            this.remoteFilesystemSpec = spec;
            return this;
        }

        /**
         * 配置模式 3：带 shell 的本地文件系统。
         */
        public Builder filesystem(LocalFilesystemSpec spec) {
            this.localFilesystemSpec = spec;
            return this;
        }

        /**
         * 在路径前缀下挂载额外 {@link AbstractFilesystem}，与通过 {@link #filesystem(SandboxFilesystemSpec)}、
         * {@link #filesystem(RemoteFilesystemSpec)} 或 {@link #filesystem(LocalFilesystemSpec)} 配置的主文件系统并存。
         *
         * <p>主文件系统由沙箱支撑时，经 {@link RoutedSandboxFilesystem} 应用路由，
         * 使 {@code shell_execute} 仍指向沙箱；否则经 {@link CompositeFilesystem} 应用。
         *
         * @param prefix 路由路径前缀（例如 {@code "memory-stores/notes/"}）
         * @param filesystem 为 {@code prefix} 下路径提供服务的后端
         */
        public Builder filesystemRoute(String prefix, AbstractFilesystem filesystem) {
            this.filesystemRoutes.put(prefix, filesystem);
            return this;
        }

        /**
         * 覆盖默认 {@link CompactionMiddleware} 配置。
         * 压缩默认启用，采用 {@link CompactionConfig#builder()}.build() 默认值
         * （按模型上下文窗口动态触发，动态保留尾部）。使用 {@link #disableCompaction()} 完全关闭。
         */
        public Builder compaction(CompactionConfig config) {
            this.compactionConfig = config;
            this.disableCompaction = (config == null);
            return this;
        }

        /**
         * 完全禁用 {@link CompactionMiddleware}。
         */
        public Builder disableCompaction() {
            this.disableCompaction = true;
            return this;
        }

        /**
         * 覆盖长期记忆管道配置（刷新、整合及维护）。未调用时使用 {@link MemoryConfig#defaults()}，
         * 行为与 Harness 历史默认设置一致。
         *
         * <p>上下文内摘要压缩管道参见 {@link #compaction(CompactionConfig)}。
         */
        public Builder memory(MemoryConfig config) {
            this.memoryConfig = config != null ? config : MemoryConfig.defaults();
            return this;
        }

        /**
         * 覆盖默认 {@link ToolResultEvictionMiddleware} 配置。
         * 工具结果卸载默认启用，采用 {@link ToolResultEvictionConfig#defaults()}（80k 字符时触发）。
         * 使用 {@link #disableToolResultEviction()} 完全关闭。
         */
        public Builder toolResultEviction(ToolResultEvictionConfig config) {
            this.toolResultEvictionConfig = config;
            this.disableToolResultEviction = (config == null);
            return this;
        }

        /**
         * 完全禁用 {@link ToolResultEvictionMiddleware}。
         */
        public Builder disableToolResultEviction() {
            this.disableToolResultEviction = true;
            return this;
        }

        /**
         * 以程序方式覆盖 {@code workspace/tools.json}。
         */
        public Builder toolsConfig(ToolsConfig toolsConfig) {
            this.toolsConfigOverride = toolsConfig;
            return this;
        }

        /**
         * 设置构建此智能体时产生的 MCP 服务注册终态结果监听器。
         * 监听器不传播到动态创建的子智能体。传入 {@code null} 禁用结果投递。
         */
        public Builder mcpServerRegistrationListener(
                McpServerRegistrationListener mcpServerRegistrationListener) {
            this.mcpServerRegistrationListener = mcpServerRegistrationListener;
            return this;
        }

        /**
         * 添加子智能体声明。
         */
        public Builder subagent(SubagentDeclaration declaration) {
            this.subagentDeclarations.add(declaration);
            return this;
        }

        public Builder subagents(List<SubagentDeclaration> declarations) {
            this.subagentDeclarations.addAll(declarations);
            return this;
        }

        /**
         * 为指定智能体 ID 添加完全自定义的子智能体工厂。
         */
        public Builder subagentFactory(String name, Function<String, Agent> factory) {
            return subagentFactory(name, null, factory);
        }

        /**
         * 为指定智能体 ID 添加完全自定义的子智能体工厂，并提供向编排器展示的描述。
         * {@code description} 为 null 或空白时使用名称。
         *
         * <p>工厂需要父级 {@link RuntimeContext} 或 {@code persistSession} 等声明选项时，
         * 使用 {@link #subagentFactory(SubagentDeclaration, SubagentFactory)}。
         */
        public Builder subagentFactory(
                String name, String description, Function<String, Agent> factory) {
            this.customSubagentFactories.add(
                    new HarnessAgentBuilderSupport.SubagentFactoryEntry(
                            name, description, rc -> factory.apply(name), null));
            return this;
        }

        /**
         * 添加由 {@code factory} 构建实例、由 {@code declaration} 指定启动行为的子智能体。
         * 适用于需要完全自定义实例（例如有独立中间件、工具或模型的 {@link HarnessAgent}），
         * 同时使用 {@code persistSession} 等声明选项的场景。
         *
         * <p>按声明名称和描述注册子智能体。仅遵循启动时选项：{@code persistSession}、{@code mode}、
         * {@code hidden}、{@code exposeToUser} 及 {@code inheritParentPermissions}。
         * 描述实例构建方式的选项（{@code model}、{@code tools}、{@code skills}、{@code workspace}、{@code steps} 等）
         * 会忽略，需在工厂中配置；需要新实例时工厂接收父级 {@link RuntimeContext}。持久复用不会再次调用工厂。
         *
         * @throws IllegalArgumentException {@code declaration} 为远程子智能体（设置 {@code url}）时抛出，
         * 因为远程智能体经 HTTP 调用，不能使用本地工厂
         */
        public Builder subagentFactory(SubagentDeclaration declaration, SubagentFactory factory) {
            java.util.Objects.requireNonNull(declaration, "declaration");
            java.util.Objects.requireNonNull(factory, "factory");
            if (declaration.isRemote()) {
                throw new IllegalArgumentException(
                        "Remote subagent '"
                                + declaration.getName()
                                + "' cannot be combined with a custom factory");
            }
            this.customSubagentFactories.add(
                    new HarnessAgentBuilderSupport.SubagentFactoryEntry(
                            declaration.getName(),
                            declaration.getDescription(),
                            factory,
                            declaration));
            return this;
        }

        /**
         * 为后台子智能体执行设置自定义 {@link TaskRepository}。
         */
        public Builder taskRepository(TaskRepository taskRepository) {
            this.taskRepository = taskRepository;
            return this;
        }

        /**
         * 设置基于收件箱投递消息的 {@link io.agentscope.harness.agent.bus.MessageBus}。
         * 设置后自动注册 {@link InboxMiddleware}，在每次推理步骤前消费会话收件箱。
         */
        public Builder messageBus(io.agentscope.harness.agent.bus.MessageBus messageBus) {
            this.messageBus = messageBus;
            return this;
        }

        /**
         * 使用给定超时启用 {@link AsyncToolMiddleware}。必须设置
         * {@link #messageBus(io.agentscope.harness.agent.bus.MessageBus)}。
         * 超过超时的工具执行转入后台，结果通过收件箱投递。
         */
        public Builder asyncToolTimeout(java.time.Duration timeout) {
            this.asyncToolTimeout = timeout;
            return this;
        }

        /**
         * 设置跟踪异步工具执行的 {@link io.agentscope.harness.agent.bus.AsyncToolRegistry}。
         * 在 {@link InboxMiddleware} 中启用过期异步工具检测及清理。未设置时跳过异步工具生命周期跟踪。
         */
        public Builder asyncToolRegistry(
                io.agentscope.harness.agent.bus.AsyncToolRegistry registry) {
            this.asyncToolRegistry = registry;
            return this;
        }

        /**
         * 注入外部子智能体工具（通常为 {@code SessionsTool}）。
         */
        public Builder externalSubagentTool(Object tool) {
            this.externalSubagentTool = tool;
            return this;
        }

        /**
         * 设置将子智能体模型名称字符串解析为 {@link Model} 实例的解析器。
         */
        public Builder modelResolver(Function<String, Model> resolver) {
            this.modelResolver = resolver;
            return this;
        }

        /**
         * 添加相对于工作区的自定义上下文文件，与 AGENTS.md、MEMORY.md、KNOWLEDGE.md 一起加载到系统提示。
         */
        public Builder additionalContextFile(String relativePath) {
            if (relativePath != null && !relativePath.isBlank()) {
                this.additionalContextFiles.add(relativePath);
            }
            return this;
        }

        /**
         * 设置工作区上下文的最大 token 预算。
         */
        public Builder maxContextTokens(int maxTokens) {
            this.maxContextTokens = maxTokens;
            return this;
        }

        /**
         * 旧版工作区上下文 XML 渲染已移除，当前格式是唯一模式。
         * @deprecated 无需配置，旧版 XML 渲染已不存在。
         */
        @Deprecated
        public Builder useLegacyXmlWorkspaceContext(boolean enabled) {
            return this;
        }

        /**
         * 通过 {@link AgentTraceMiddleware} 启用或禁用智能体执行追踪日志，默认为 {@code true}。
         */
        public Builder enableAgentTracingLog(boolean enabled) {
            this.agentTracingLogEnabled = enabled;
            return this;
        }

        /**
         * 配置 {@code deliver_artifact} 工具使用的 {@link ArtifactDeliveryTarget}。
         *
         * <p>设置后，在主智能体注册通用 {@link ArtifactDeliveryTool}，沙箱工作区提示告诉模型用它
         * 将产物交付到沙箱之外的目标。未设置（默认）时不暴露交付工具。
         *
         * <p>此工具仅向主智能体暴露，不传播给自动构建的子智能体；子智能体返回纯文本结果，由主智能体交付。
         *
         * <p>注意：工具从智能体文件系统读取文件，因此使用 {@link #disableFilesystemTools()} 时也会抑制它，
         * 同时配置两者会使工具不被注册。
         */
        public Builder artifactDeliveryTarget(ArtifactDeliveryTarget target) {
            this.artifactDeliveryTarget = target;
            return this;
        }

        /**
         * 跳过 {@link FilesystemTool} 注册。
         */
        public Builder disableFilesystemTools() {
            this.disableFilesystemTools = true;
            return this;
        }

        /**
         * 跳过 {@link ShellExecuteTool} 注册。
         */
        public Builder disableShellTool() {
            this.disableShellTool = true;
            return this;
        }

        /**
         * 跳过可选 Tavily 支持的 {@code web_search} 及 {@code web_fetch} 工具注册。
         */
        public Builder disableWebTools() {
            this.disableWebTools = true;
            return this;
        }

        /**
         * 为内置 {@code web_fetch} 及 {@code web_search} 工具提供自定义 {@link java.net.http.HttpClient}
         * （例如自定义代理、TLS 或 HTTP 版本设置）。未设置时使用默认客户端及 JDK 版本协商
         * （优先 HTTP/2，自动回退 HTTP/1.1）；若目标服务在 HTTP/2 协商下失败，
         * 可在此注入仅 HTTP/1.1 的客户端（见 issue #3101）。
         */
        public Builder webHttpClient(HttpClient client) {
            this.webHttpClient = client;
            return this;
        }

        /**
         * 在构建器工具集注册仅含 schema 的外部工具（在 {@link #build()} 时合并到最终智能体工具集）。
         * 用于 {@code self_hosted} 环境暴露暂停并交给工作端执行的 hands 工具。
         */
        public Builder registerExternalSchemas(
                java.util.List<io.agentscope.core.model.ToolSchema> schemas) {
            if (schemas != null) {
                this.toolkit.registerSchemas(schemas);
            }
            return this;
        }

        /**
         * 禁用按调用从工作区文件系统动态加载技能。
         */
        public Builder disableDynamicSkills() {
            this.disableDynamicSkills = true;
            return this;
        }

        /**
         * 跳过默认第 4 层 {@link io.agentscope.harness.agent.skill.WorkspaceSkillRepository} 注册。
         * 用户提供的仓库及工作区技能目录（第 3 层）仍组合使用；仅省略基于命名空间 AbstractFilesystem 的来源。
         */
        public Builder disableDefaultWorkspaceSkills() {
            this.disableDefaultWorkspaceSkills = true;
            return this;
        }

        /**
         * 启用智能体可调用的 {@code skill_manage} 工具，让智能体在工作区创建、编辑、补丁修改、归档自身技能，
         * 并将工作区技能仓库升级为可写变体。
         */
        public Builder enableSkillManageTool(SkillManageConfig config) {
            this.skillManageToolEnabled = true;
            this.skillManageConfig = config != null ? config : SkillManageConfig.defaults();
            return this;
        }

        /**
         * 使用默认配置调用 {@link #enableSkillManageTool(SkillManageConfig)} 的简写。
         */
        public Builder enableSkillManageTool(boolean autoPromote) {
            return enableSkillManageTool(
                    SkillManageConfig.builder().autoPromote(autoPromote).build());
        }

        /**
         * 配置运行时晋升门控及可见性过滤器链。
         */
        public Builder enableSkillPromotionGate(
                SkillPromotionGate gate, SkillVisibilityFilter visibilityFilter) {
            this.promotionGate = gate;
            this.visibilityFilter = visibilityFilter;
            return this;
        }

        /**
         * 设置 {@code EnvironmentFilter} 使用的部署环境标签。
         */
        public Builder environment(String env) {
            this.environment = env != null ? env : "prod";
            return this;
        }

        /**
         * 启用后台技能整理器，需要 {@link #enableSkillManageTool}。
         */
        public Builder enableSkillCurator(SkillCuratorConfig config) {
            this.skillCuratorEnabled = true;
            this.skillCuratorConfig = config != null ? config : SkillCuratorConfig.defaults();
            return this;
        }

        /**
         * 启用计划模式（只读设计阶段），提供 {@code plan_enter}／{@code plan_write}／{@code plan_exit} 工具，
         * 并通过 {@code PlanModeMiddleware} 在计划模式活动时强制仅使用只读工具。
         */
        public Builder enablePlanMode() {
            return enablePlanMode(true);
        }

        public Builder enablePlanMode(boolean enabled) {
            this.planModeEnabled = enabled;
            return this;
        }

        public Builder planFileDirectory(String dir) {
            if (dir != null && !dir.isBlank()) {
                this.planFileDir = dir;
            }
            return this;
        }

        /**
         * 允许计划模式活动时运行 shell 工具（{@code execute}）。默认计划模式严格只读，拒绝 shell，
         * 因为它兼具读写用途，不能仅凭名称判断只读。需要基于 shell 调查（例如 {@code cat}／{@code grep}／{@code git log}）
         * 才能制定现实计划时可显式启用；计划提示要求模型保持 shell 操作只读。
         * 写入仍经被拒绝的文件编辑工具处理。建议配合沙箱文件系统使用。
         */
        public Builder allowShellInPlanMode() {
            return allowShellInPlanMode(true);
        }

        public Builder allowShellInPlanMode(boolean allowed) {
            this.planModeAllowShell = allowed;
            return this;
        }

        public Builder skillFilter(io.agentscope.core.skill.SkillFilter filter) {
            this.skillFilter = filter;
            return this;
        }

        public Builder skillsEnabled(boolean enabled) {
            this.skillFilter =
                    enabled
                            ? io.agentscope.core.skill.SkillFilter.all()
                            : io.agentscope.core.skill.SkillFilter.none();
            return this;
        }

        public Builder enableSkills(String... skillNames) {
            this.skillFilter = io.agentscope.core.skill.SkillFilter.only(skillNames);
            return this;
        }

        public Builder disableSkills(String... skillNames) {
            this.skillFilter = io.agentscope.core.skill.SkillFilter.except(skillNames);
            return this;
        }

        public Builder disableDynamicSubagents() {
            this.disableDynamicSubagents = true;
            return this;
        }

        /**
         * 跳过 {@code memory_search}／{@code memory_get}／{@code memory_save}／{@code session_search} 注册，
         * 并从工作区系统提示中省略相应记忆召回及工具式持久化指导。
         */
        public Builder disableMemoryTools() {
            this.disableMemoryTools = true;
            return this;
        }

        /**
         * 禁用记忆刷新及后台整合，并移除工作区系统提示中“自动提取”的持久化说明。
         * 结合 {@link #disableMemoryTools()} 使用时，还会跳过将 {@code MEMORY.md} 注入
         * {@code HARNESS_CONTEXT} 参考资料。
         */
        public Builder disableMemoryHooks() {
            this.disableMemoryHooks = true;
            return this;
        }

        /**
         * 重复的对话记录写入器已移除，原生会话日志仍启用。
         * @deprecated 无需配置即可禁用重复对话记录文件。
         */
        @Deprecated
        public Builder disableTranscript() {
            return this;
        }

        /**
         * 自 2.0 起为空操作；会话持久化由 ReActAgent 自身负责。
         */
        public Builder disableSessionPersistence() {
            this.disableSessionPersistence = true;
            return this;
        }

        public Builder disableWorkspaceContext() {
            this.disableWorkspaceContext = true;
            return this;
        }

        public Builder disableAtPathExpansion() {
            this.disableAtPathExpansion = true;
            return this;
        }

        public Builder disableSubagents() {
            this.disableSubagents = true;
            return this;
        }

        public Builder disableToolsConfig() {
            this.disableToolsConfig = true;
            return this;
        }

        /**
         * 将此次构建标记为叶子子智能体（不再嵌套编排子智能体）。
         * 采用包可见性，因为仅 {@link HarnessAgentBuilderSupport} 子智能体工厂应将智能体标为叶子。
         */
        Builder asLeafSubagent() {
            this.leafSubagent = true;
            return this;
        }

        /**
         * 仅构建子智能体条目（通用、声明式及自定义工厂），不构建完整智能体。
         * 适用于需要预先提取子智能体工厂的调用方，例如将它们挂载到会话路由器。
         */
        public List<SubagentEntry> buildSubagentEntries(Path resolvedWorkspace) {
            return HarnessAgentBuilderSupport.buildSubagentEntries(this, resolvedWorkspace, null);
        }

        public List<SubagentEntry> buildSubagentEntries(
                Path resolvedWorkspace, SandboxBackedFilesystem sandboxFs) {
            return HarnessAgentBuilderSupport.buildSubagentEntries(
                    this, resolvedWorkspace, sandboxFs);
        }

        private static void wireTaskRepositoryMessageBus(
                io.agentscope.harness.agent.subagent.task.TaskRepository repo,
                io.agentscope.harness.agent.bus.MessageBus bus,
                String agentId) {
            repo.setCompletionCallback(
                    (rc, taskId, subAgentId, sessionId, result) -> {
                        String userId = rc != null ? rc.getUserId() : null;
                        String hintContent =
                                String.format(
                                        "<system-reminder>Background subagent task '%s'"
                                                + " (agent=%s) has completed.\n\nResult:\n\n%s"
                                                + "</system-reminder>",
                                        taskId,
                                        subAgentId,
                                        result != null ? result : "(no output)");
                        String hintId = java.util.UUID.randomUUID().toString().replace("-", "");
                        bus.inboxPush(
                                        sessionId,
                                        java.util.Map.of(
                                                "type",
                                                "hint",
                                                "id",
                                                hintId,
                                                "hint",
                                                hintContent,
                                                "source",
                                                "subagent_task"))
                                .subscribe();
                        bus.enqueueWakeup(
                                        userId != null ? userId : "",
                                        sessionId,
                                        agentId != null ? agentId : "")
                                .subscribe();
                    });
        }

        /**
         * 从可复用配置构建全新运行时。共享构建器前应完成配置；构建操作串行，产出的智能体可并发执行。
         */
        public synchronized HarnessAgent build() {
            // 【学习提示】构建器保存配置，运行时按次构建：复制内部构建器，并在 finally 中
            // 还原可能自动派生的协作组件，防止下一次 build 沿用上一实例的工作区依赖。
            var configuredMessageBus = messageBus;
            var configuredAsyncToolRegistry = asyncToolRegistry;
            try {
                return buildInstance(inner.copy());
            } finally {
                // 由工作区派生的协作组件属于本次构建的文件系统，尤其
                // 当沙箱绑定或工作区索引按智能体管理生命周期时。
                messageBus = configuredMessageBus;
                asyncToolRegistry = configuredAsyncToolRegistry;
            }
        }

        private HarnessAgent buildInstance(ReActAgent.Builder inner) {
            // 深拷贝工具集：每个智能体拥有自己的工具集，避免 Harness 注册工具及
            // 用户注册工具跨构建串用。
            Toolkit agentToolkit = this.toolkit.copy();

            // ---- 验证 ----
            int specCount = 0;
            if (sandboxFilesystemSpec != null) specCount++;
            if (remoteFilesystemSpec != null) specCount++;
            if (localFilesystemSpec != null) specCount++;
            if (specCount > 1) {
                throw new IllegalStateException(
                        "At most one of sandboxFilesystemSpec, remoteFilesystemSpec,"
                                + " localFilesystemSpec may be configured");
            }
            if (abstractFilesystem != null && specCount > 0) {
                throw new IllegalStateException(
                        "abstractFilesystem() is an escape hatch and is mutually exclusive with"
                                + " filesystem(...) specs");
            }
            Path resolvedWorkspace = workspace != null ? workspace : resolveDefaultWorkspace();
            // 任何组件消费 ID（状态目录、文件系统命名空间、
            // 消息总线唤醒键）之前先验证。注意：不验证名称回退值，因为名称
            // 过去从未受此约束，在此验证会破坏既有用户。
            String normalizedAgentId = AgentBase.normalizeAgentId(agentId);
            String resolvedAgentId =
                    normalizedAgentId != null
                            ? normalizedAgentId
                            : (name != null && !name.isBlank() ? name : "ReActAgent");
            // ---- DistributedStore 自动装配 ----
            // distributedStore 提供存储组件，文件系统模式由用户选择。
            // 优先级：显式构建器方法 > distributedStore > 工作区默认值
            if (distributedStore != null) {
                if (stateStoreOverride == null
                        && (legacySessionHistory || sandboxFilesystemSpec != null)) {
                    stateStoreOverride = distributedStore.agentStateStore();
                }
                if (remoteFilesystemSpec != null) {
                    remoteFilesystemSpec.injectStoreIfAbsent(distributedStore.baseStore());
                }
                if (sandboxFilesystemSpec != null) {
                    if (sandboxFilesystemSpec.getSnapshotSpecOverride() == null) {
                        sandboxFilesystemSpec.snapshotSpec(distributedStore.sandboxSnapshotSpec());
                    }
                    if (sandboxFilesystemSpec.getExecutionGuard() == null) {
                        sandboxFilesystemSpec.executionGuard(
                                distributedStore.sandboxExecutionGuard());
                    }
                }
                if (messageBus == null) {
                    messageBus = distributedStore.messageBus();
                }
                if (asyncToolRegistry == null) {
                    asyncToolRegistry = distributedStore.asyncToolRegistry();
                }
            }

            PeriodicGate periodicGate =
                    distributedStore != null
                            ? new StoreBackedPeriodicGate(distributedStore.baseStore())
                            : new LocalPeriodicGate();

            AgentStateStore effectiveSession = stateStoreOverride;
            IsolationScope fsIsolationScope = IsolationScope.USER;
            if (remoteFilesystemSpec != null && remoteFilesystemSpec.getIsolationScope() != null) {
                fsIsolationScope = remoteFilesystemSpec.getIsolationScope();
            } else if (sandboxFilesystemSpec != null
                    && sandboxFilesystemSpec.getIsolationScope() != null) {
                fsIsolationScope = sandboxFilesystemSpec.getIsolationScope();
            } else if (localFilesystemSpec != null
                    && localFilesystemSpec.getIsolationScope() != null) {
                fsIsolationScope = localFilesystemSpec.getIsolationScope();
            }
            NamespaceFactory nsFactory = fsIsolationScope.toNamespaceFactory();
            // 原生会话仅从自身日志恢复。仅显式旧版模式或
            // 沙箱基础设施元数据才需要独立状态存储。
            if (effectiveSession == null
                    && (legacySessionHistory || sandboxFilesystemSpec != null)) {
                effectiveSession = new JsonFileAgentStateStore(defaultStateDir(resolvedAgentId));
            }
            if (legacySessionHistory) inner.stateStore(effectiveSession);

            if (legacySessionHistory
                    && remoteFilesystemSpec != null
                    && isLocalSession(effectiveSession)) {
                throw new IllegalStateException(
                        "filesystem(RemoteFilesystemSpec) is designed for distributed /"
                            + " multi-replica deployments, but the effective AgentStateStore is a"
                            + " local in-process implementation (JsonFileAgentStateStore /"
                            + " InMemoryAgentStateStore). Configure a distributed AgentStateStore"
                            + " (for example RedisAgentStateStore) via .stateStore(...) or use"
                            + " .distributedStore(...).");
            }
            WorkspaceIndex workspaceIndex =
                    remoteFilesystemSpec != null ? WorkspaceIndex.open(resolvedWorkspace) : null;
            if (legacySessionHistory)
                inner.actionObserver(new StateStoreActionObserver(effectiveSession));
            AbstractFilesystem filesystem =
                    HarnessAgentBuilderSupport.resolveFilesystem(
                            this, resolvedWorkspace, resolvedAgentId, workspaceIndex, nsFactory);

            // ---- 沙箱集成 ----
            SandboxLifecycleMiddleware sandboxLifecycleMw = null;
            SandboxContext defaultSandboxContext = null;
            SandboxBackedFilesystem capturedSandboxFs = null;
            if (sandboxFilesystemSpec != null) {
                capturedSandboxFs = new SandboxBackedFilesystem();
                filesystem =
                        filesystemRoutes.isEmpty()
                                ? capturedSandboxFs
                                : new RoutedSandboxFilesystem(capturedSandboxFs, filesystemRoutes);

                defaultSandboxContext = sandboxFilesystemSpec.toSandboxContext(resolvedWorkspace);

                if (isLocalSession(effectiveSession)) {
                    log.warn(
                            "[harness] Sandbox mode is using a local AgentStateStore ({})."
                                    + " Sandbox state will not survive JVM restarts and cannot be"
                                    + " shared across instances. For production, configure a"
                                    + " distributed AgentStateStore via .stateStore(...).",
                            effectiveSession.getClass().getSimpleName());
                }

                SessionSandboxStateStore stateStore =
                        new SessionSandboxStateStore(effectiveSession, resolvedAgentId);
                SandboxExecutionGuard executionGuard =
                        sandboxFilesystemSpec.getExecutionGuard() != null
                                ? sandboxFilesystemSpec.getExecutionGuard()
                                : SandboxExecutionGuard.noop();
                SandboxManager sandboxManager =
                        new SandboxManager(
                                defaultSandboxContext.getClient(),
                                stateStore,
                                resolvedAgentId,
                                executionGuard);
                sandboxLifecycleMw =
                        new SandboxLifecycleMiddleware(sandboxManager, capturedSandboxFs);
            } else if (!filesystemRoutes.isEmpty()) {
                filesystem = new CompositeFilesystem(filesystem, filesystemRoutes);
            }
            WorkspaceManager wsManager =
                    new WorkspaceManager(resolvedWorkspace, filesystem, workspaceIndex, nsFactory);
            wsManager.validate();

            // 【学习提示】workspaceFor 的带外访问通过包装器固定用户／会话上下文，
            // 而不是修改共享文件系统上的当前身份；不同调用取得各自的 WorkspaceManager。
            final AbstractFilesystem sharedFilesystemRef = filesystem;
            final Path capturedWorkspace = resolvedWorkspace;
            final WorkspaceIndex capturedIndex = workspaceIndex;
            BiFunction<String, String, WorkspaceManager> workspaceFactoryFn =
                    (uid, sid) -> {
                        RuntimeContext bakedRc =
                                HarnessAgentBuilderSupport.buildBakedRuntimeContext(uid, sid);
                        NamespaceFactory ctxNs =
                                rc -> (uid == null || uid.isBlank()) ? List.of() : List.of(uid);
                        AbstractFilesystem ctxFs =
                                new io.agentscope.harness.agent.filesystem.BakedContextFilesystem(
                                        sharedFilesystemRef, bakedRc);
                        return new WorkspaceManager(capturedWorkspace, ctxFs, capturedIndex, ctxNs);
                    };

            // ---- MessageBus／AsyncToolRegistry：工作区默认值 ----
            // 未显式设置且 DistributedStore 未提供时，回退到
            // 使用与智能体其余部分相同 AbstractFilesystem 的工作区实现。
            if (messageBus == null && filesystem != null) {
                messageBus =
                        new io.agentscope.harness.agent.bus.WorkspaceMessageBus(
                                filesystem, ".agentscope/bus");
            }
            if (asyncToolRegistry == null && filesystem != null) {
                asyncToolRegistry =
                        new io.agentscope.harness.agent.bus.WorkspaceAsyncToolRegistry(
                                filesystem, ".agentscope/bus/async-tools");
            }

            // ---- 中间件 ----
            // 【学习提示】用户 middleware 已注册到 inner；这里按配置追加 Harness 中间件。
            // 各功能的默认开关在构建阶段决定组件是否安装，不是每次推理重新构造整套能力。
            inner.middleware(new ContextConventionsMiddleware());
            if (sandboxLifecycleMw != null) {
                inner.middleware(sandboxLifecycleMw);
            }
            if (agentTracingLogEnabled) {
                inner.middleware(new AgentTraceMiddleware());
            }
            boolean artifactDeliveryEnabled =
                    artifactDeliveryTarget != null && !disableFilesystemTools;
            if (!disableWorkspaceContext) {
                WorkspaceContextMiddleware markdownMw =
                        new WorkspaceContextMiddleware(
                                wsManager,
                                name != null ? name : "ReActAgent",
                                environmentMemory,
                                maxContextTokens,
                                disableMemoryTools,
                                disableMemoryHooks);
                markdownMw.setAdditionalContextFiles(additionalContextFiles);
                markdownMw.setArtifactDeliveryEnabled(artifactDeliveryEnabled);
                inner.middleware(markdownMw);
            }
            if (!disableAtPathExpansion) {
                inner.middleware(new AtPathExpansionMiddleware(wsManager));
            }
            if (!legacySessionHistory) {
                inner.sessionLogAgentId(resolvedAgentId);
                inner.sessionLogStore(
                        sessionLogStore != null
                                ? sessionLogStore
                                : new WorkspaceSessionLogStore(wsManager));
            }
            Model memoryModel = memoryConfig.model() != null ? memoryConfig.model() : model;
            if (memoryModel != null && !disableMemoryHooks) {
                IsolationScope effectiveIsolationScope = fsIsolationScope;

                String effectiveFlushPrompt =
                        memoryConfig.flushPrompt() != null
                                ? memoryConfig.flushPrompt()
                                : MemoryFlushManager.DEFAULT_FLUSH_PROMPT;
                inner.middleware(
                        new MemoryFlushMiddleware(
                                wsManager,
                                memoryModel,
                                effectiveFlushPrompt,
                                memoryConfig.flushTrigger(),
                                effectiveIsolationScope,
                                periodicGate));

                String effectiveConsolidationPrompt =
                        memoryConfig.consolidationPrompt() != null
                                ? memoryConfig.consolidationPrompt()
                                : MemoryConsolidator.DEFAULT_CONSOLIDATION_PROMPT;
                MemoryConsolidator consolidator =
                        new MemoryConsolidator(
                                wsManager,
                                memoryModel,
                                effectiveConsolidationPrompt,
                                memoryConfig.consolidationMaxTokens(),
                                distributedStore != null ? distributedStore.baseStore() : null);
                inner.middleware(
                        new MemoryMaintenanceMiddleware(
                                wsManager,
                                consolidator,
                                memoryConfig.dailyFileRetentionDays(),
                                memoryConfig.consolidationMinGap(),
                                effectiveIsolationScope,
                                periodicGate));
            }
            CompactionMiddleware compactionHook = null;
            if (!disableCompaction && compactionConfig != null) {
                Model compactionModel =
                        compactionConfig.getModel() != null ? compactionConfig.getModel() : model;
                if (compactionModel != null) {
                    compactionHook =
                            new CompactionMiddleware(wsManager, compactionModel, compactionConfig);
                    inner.middleware(compactionHook.overflowRecovery());
                }
            }
            ToolResultEvictionMiddleware contextEviction =
                    !disableToolResultEviction && toolResultEvictionConfig != null
                            ? new ToolResultEvictionMiddleware(filesystem, toolResultEvictionConfig)
                            : null;
            inner.enableTaskRequirements(contextSources.taskContext().requirementProposals());
            inner.modelRequestPreparer(
                    new HarnessContextBuilder(
                            contextPolicy,
                            compactionHook,
                            contextEviction,
                            contextSources,
                            contextSelectionPolicy));
            if (messageBus != null) {
                inner.middleware(new InboxMiddleware(messageBus, 100, asyncToolRegistry, null));
            }

            TeamsMiddleware capturedTeamsMw = null;
            if (teamsModeClient != null && teamsModeContext != null) {
                TeamsMiddleware teamsMw = new TeamsMiddleware(teamsModeClient, teamsModeContext);
                if (messageBus != null) {
                    teamsMw.wireMessageBus(messageBus, resolvedAgentId);
                }
                teamsMw.bindSession(teamsModeSessionId);
                inner.middleware(teamsMw);
                for (Object t : teamsMw.getTools()) {
                    agentToolkit.registerTool(t);
                }
                capturedTeamsMw = teamsMw;
            }

            Object capturedSubagentMw = null;
            if (!leafSubagent && !disableSubagents && model != null) {
                if (filesystem != null && !disableDynamicSubagents) {
                    DynamicSubagentsMiddleware dynMw =
                            HarnessAgentBuilderSupport.buildDynamicSubagentsMiddleware(
                                    this, wsManager, resolvedWorkspace, capturedSandboxFs);
                    if (dynMw != null) {
                        if (messageBus != null) {
                            wireTaskRepositoryMessageBus(
                                    dynMw.getTaskRepository(), messageBus, resolvedAgentId);
                        }
                        inner.middleware(dynMw);
                        for (Object t : dynMw.getTools()) {
                            agentToolkit.registerTool(t);
                        }
                        capturedSubagentMw = dynMw;
                    }
                } else {
                    SubagentsMiddleware subagentsMw =
                            HarnessAgentBuilderSupport.buildSubagentsMiddleware(
                                    this, wsManager, resolvedWorkspace, capturedSandboxFs);
                    if (subagentsMw != null) {
                        if (messageBus != null) {
                            subagentsMw.wireMessageBus(messageBus, resolvedAgentId);
                        }
                        inner.middleware(subagentsMw);
                        for (Object t : subagentsMw.getTools()) {
                            agentToolkit.registerTool(t);
                        }
                        capturedSubagentMw = subagentsMw;
                    }
                }
            }

            if (messageBus != null && asyncToolTimeout != null) {
                inner.middleware(
                        new AsyncToolMiddleware(messageBus, asyncToolTimeout, asyncToolRegistry));
            }
            if (messageBus != null) {
                TaskRepository waitTaskRepo = null;
                if (capturedSubagentMw instanceof SubagentsMiddleware sm) {
                    waitTaskRepo = sm.getTaskRepository();
                } else if (capturedSubagentMw instanceof DynamicSubagentsMiddleware dsm) {
                    waitTaskRepo = dsm.getTaskRepository();
                }
                io.agentscope.harness.agent.tool.WaitAsyncResultsTool waitTool =
                        new io.agentscope.harness.agent.tool.WaitAsyncResultsTool(
                                messageBus, waitTaskRepo);
                if (capturedTeamsMw != null) {
                    TeamsMiddleware teamsForWait = capturedTeamsMw;
                    waitTool.setExternalWorkProbe(teamsForWait::hasOutstandingTeamWork);
                }
                agentToolkit.registerTool(waitTool);
            }

            // ---- 工具集（记忆／文件系统／shell 工具） ----
            if (!disableMemoryTools) {
                agentToolkit.registerTool(new MemorySearchTool(wsManager));
                agentToolkit.registerTool(new MemoryGetTool(wsManager));
                agentToolkit.registerTool(new MemorySaveTool(wsManager));
                if (!legacySessionHistory) {
                    agentToolkit.registerTool(
                            new SessionSearchTool(
                                    sessionLogStore != null
                                            ? sessionLogStore
                                            : new WorkspaceSessionLogStore(wsManager)));
                }
            }
            WorkspacePathNormalizer pathNormalizer;
            if (filesystem instanceof OverlayFilesystem ov
                    && ov.getUpper() instanceof LocalFilesystemWithShell) {
                // 本地覆盖层模式。ShellAwareOverlay 也是 AbstractSandboxFilesystem 实例，
                // 所以此分支必须在下面的沙箱检查之前，避免
                // 将沙箱 "/workspace" 前缀用于真实宿主路径。
                // 只去掉工作区前缀，不去掉项目路径前缀。项目绝对
                // 路径由 ROOTED pathPolicy 正确处理；若去掉前缀，
                // 会得到相对路径，使下层虚拟条目（/src/...）
                // 在上层 ROOTED 检查中失败。
                pathNormalizer =
                        WorkspacePathNormalizer.of(
                                resolvedWorkspace.toAbsolutePath().toString(), nsFactory);
            } else if (filesystem instanceof AbstractSandboxFilesystem) {
                pathNormalizer =
                        WorkspacePathNormalizer.of(ShellPathPolicy.SANDBOX_WORKSPACE_PREFIX);
            } else {
                pathNormalizer =
                        WorkspacePathNormalizer.of(resolvedWorkspace.toAbsolutePath().toString());
            }
            if (!disableFilesystemTools) {
                agentToolkit.registerTool(new FilesystemTool(filesystem, pathNormalizer));
            }
            if (artifactDeliveryEnabled) {
                agentToolkit.registerTool(
                        new ArtifactDeliveryTool(
                                filesystem, pathNormalizer, artifactDeliveryTarget));
            }
            if (!disableShellTool && filesystem instanceof AbstractSandboxFilesystem sandbox) {
                agentToolkit.registerTool(new ShellExecuteTool(sandbox));
            }
            if (!disableWebTools) {
                if (webHttpClient != null) {
                    agentToolkit.registerTool(new WebTools.WebFetchTool(webHttpClient));
                    agentToolkit.registerTool(new WebTools.WebSearchTool(webHttpClient));
                } else {
                    agentToolkit.registerTool(new WebTools.WebFetchTool());
                    agentToolkit.registerTool(new WebTools.WebSearchTool());
                }
            }

            // ---- 计划模式（只读设计阶段） ----
            PlanModeManager planModeManager = null;
            if (planModeEnabled) {
                planModeManager = new PlanModeManager(wsManager, planFileDir);
                agentToolkit.registerTool(new PlanModeTools.PlanEnterTool(planModeManager));
                agentToolkit.registerTool(new PlanModeTools.PlanWriteTool(planModeManager));
                agentToolkit.registerTool(new PlanModeTools.PlanExitTool(planModeManager));
                final Toolkit roToolkit = agentToolkit;
                java.util.Set<String> planExtraAllowed =
                        planModeAllowShell
                                ? java.util.Set.of(ShellExecuteTool.NAME)
                                : java.util.Set.of();
                inner.middleware(
                        new io.agentscope.harness.agent.middleware.PlanModeMiddleware(
                                planModeManager,
                                toolName -> {
                                    AgentTool t = roToolkit.getTool(toolName);
                                    return t != null && t.isReadOnly();
                                },
                                planExtraAllowed));
            }

            // ---- workspace/tools.json：MCP 服务及允许／拒绝过滤 ----
            ToolsConfig resolvedToolsConfig = null;
            if (!disableToolsConfig) {
                if (toolsConfigOverride != null) {
                    resolvedToolsConfig = toolsConfigOverride;
                } else if (wsManager != null) {
                    resolvedToolsConfig = ToolsConfigLoader.load(wsManager).orElse(null);
                }
            }
            if (resolvedToolsConfig != null) {
                McpServerRegistrar.register(
                        agentToolkit,
                        resolvedToolsConfig.getMcpServers(),
                        mcpServerRegistrationListener);
            }

            // ---- 技能 ----
            List<AgentSkillRepository> orderedSkillRepos =
                    HarnessAgentBuilderSupport.composeSkillRepositories(
                            this, wsManager, filesystem);

            // ---- 技能自学习：可写工作区技能及 skill_manage 工具 ----
            SkillPromoter pendingSkillPromoter = null;
            SkillUsageStore pendingSkillUsageStore = null;
            SkillCurator pendingSkillCurator = null;
            SkillAuditLog pendingSkillAuditLog = null;
            if (skillManageToolEnabled && filesystem != null) {
                SkillManageConfig smConfig =
                        skillManageConfig != null
                                ? skillManageConfig
                                : SkillManageConfig.defaults();
                WorkspaceSkillRepository mainWritableRepo = null;
                for (int i = orderedSkillRepos.size() - 1; i >= 0; i--) {
                    AgentSkillRepository r = orderedSkillRepos.get(i);
                    // 默认第 4 层仓库（composeSkillRepositories）是指向 "skills" 的只读
                    // WorkspaceSkillRepository。将它替换为
                    // 指向配置主目录的可写仓库，使 skill_manage 能
                    // 持久化。
                    if (r instanceof WorkspaceSkillRepository wsr && !wsr.isWriteable()) {
                        mainWritableRepo =
                                new WorkspaceSkillRepository(
                                        filesystem, smConfig.mainDir(), "workspace-writable");
                        orderedSkillRepos.set(i, mainWritableRepo);
                        break;
                    }
                }
                if (mainWritableRepo == null) {
                    mainWritableRepo =
                            new WorkspaceSkillRepository(
                                    filesystem, smConfig.mainDir(), "workspace-writable");
                    orderedSkillRepos.add(mainWritableRepo);
                }
                WorkspaceSkillRepository draftsWritableRepo =
                        new WorkspaceSkillRepository(
                                filesystem, smConfig.draftsDir(), "workspace-drafts");
                SkillUsageStore usageStore =
                        distributedStore != null
                                ? SkillUsageStore.baseStore(distributedStore.baseStore())
                                : new SkillUsageStore(filesystem);
                SkillAuditLog auditLog = new SkillAuditLog(filesystem, wsManager);
                SkillManageTool skillManageTool =
                        new SkillManageTool(
                                mainWritableRepo,
                                draftsWritableRepo,
                                smConfig,
                                usageStore,
                                auditLog);
                pendingSkillAuditLog = auditLog;
                agentToolkit.registerAgentTool(skillManageTool);
                agentToolkit.registerAgentTool(new ProposeSkillTool(skillManageTool));
                inner.middleware(
                        new io.agentscope.harness.agent.middleware.SkillUsageMiddleware(
                                usageStore));

                pendingSkillPromoter =
                        new SkillPromoter(
                                draftsWritableRepo,
                                mainWritableRepo,
                                wsManager,
                                usageStore,
                                promotionGate != null ? promotionGate : new RejectAllGate(),
                                smConfig.draftsDir(),
                                smConfig.mainDir(),
                                auditLog);
                pendingSkillUsageStore = usageStore;

                if (skillCuratorEnabled) {
                    SkillCurator curator =
                            new SkillCurator(
                                    filesystem,
                                    usageStore,
                                    mainWritableRepo,
                                    skillCuratorConfig != null
                                            ? skillCuratorConfig
                                            : SkillCuratorConfig.defaults(),
                                    periodicGate);
                    pendingSkillCurator = curator;
                    inner.middleware(
                            new io.agentscope.harness.agent.middleware.SkillCuratorMiddleware(
                                    curator));
                }
            }

            if (!orderedSkillRepos.isEmpty()) {
                io.agentscope.harness.agent.skill.runtime.MarketplaceStager stager =
                        resolvedWorkspace != null
                                ? new io.agentscope.harness.agent.skill.runtime.MarketplaceStager(
                                        resolvedWorkspace)
                                : null;

                io.agentscope.harness.agent.skill.runtime.ShellPathPolicy shellPolicy;
                boolean shellToolAvailable =
                        !disableShellTool
                                && ToolFilter.isAllowed(ShellExecuteTool.NAME, resolvedToolsConfig);
                if (!shellToolAvailable) {
                    shellPolicy =
                            io.agentscope.harness.agent.skill.runtime.ShellPathPolicy.noShell();
                } else if (filesystem instanceof LocalFilesystemWithShell) {
                    shellPolicy =
                            io.agentscope.harness.agent.skill.runtime.ShellPathPolicy
                                    .localWithShell(resolvedWorkspace);
                } else if (filesystem instanceof OverlayFilesystem ov
                        && ov.getUpper() instanceof LocalFilesystemWithShell) {
                    shellPolicy =
                            io.agentscope.harness.agent.skill.runtime.ShellPathPolicy
                                    .localWithShell(resolvedWorkspace);
                } else if (filesystem instanceof SandboxBackedFilesystem
                        || (filesystem instanceof RoutedSandboxFilesystem routed
                                && routed.primary() instanceof SandboxBackedFilesystem)) {
                    String wsPrefix =
                            defaultSandboxContext != null
                                            && defaultSandboxContext.getClientOptions() != null
                                    ? defaultSandboxContext.getClientOptions().getWorkspaceRoot()
                                    : io.agentscope.harness.agent.skill.runtime.ShellPathPolicy
                                            .SANDBOX_WORKSPACE_PREFIX;
                    shellPolicy =
                            io.agentscope.harness.agent.skill.runtime.ShellPathPolicy.sandbox(
                                    wsPrefix);
                } else {
                    shellPolicy =
                            io.agentscope.harness.agent.skill.runtime.ShellPathPolicy.noShell();
                }

                HarnessSkillMiddleware skillMiddleware =
                        disableDynamicSkills
                                ? HarnessSkillMiddleware.frozen(
                                        orderedSkillRepos,
                                        agentToolkit,
                                        skillFilter,
                                        visibilityFilter,
                                        stager,
                                        shellPolicy)
                                : new HarnessSkillMiddleware(
                                        orderedSkillRepos,
                                        agentToolkit,
                                        skillFilter,
                                        visibilityFilter,
                                        stager,
                                        shellPolicy);
                skillMiddleware.isolationScope(fsIsolationScope);
                inner.middleware(skillMiddleware);

                // Harness 同时拥有实时及冻结仓库路径。
                inner.dynamicSkillsEnabled(false);

                // 接入启动前暂存，使沙箱投影能获取 .skills-cache 内容，
                // 这些内容由 MarketplaceStager 从数据库支持的仓库实体化。
                if (sandboxLifecycleMw != null && stager != null) {
                    sandboxLifecycleMw.setBeforeStartCallback(
                            skillMiddleware::prestageMarketplaceSkills);
                }
            } else if (disableDynamicSkills) {
                // 没有组合仓库，但仍保留核心的显式退出选项。
                inner.dynamicSkillsEnabled(false);
            }

            // ---- 应用 tools.json 允许／拒绝过滤 ----
            // 平台工具（子智能体／团队／任务等）不会被 allow 列表去掉，参见 ToolFilter。
            if (resolvedToolsConfig != null) {
                ToolFilter.apply(agentToolkit, resolvedToolsConfig);
            }

            log.info(
                    "HarnessAgent '{}' built [workspace={}, filesystem={}, subagents={}]",
                    name,
                    resolvedWorkspace,
                    filesystem.getClass().getSimpleName(),
                    !leafSubagent && !disableSubagents && model != null);

            // ---- 构建内部 ReActAgent ----
            // 【学习提示】Harness 采用组合：先装配工具、工作区和中间件，再构建 ReActAgent。
            // 推理／行动循环仍由 delegate 执行，外层负责工作区服务及调用生命周期。
            inner.toolkit(agentToolkit);
            ReActAgent delegate = inner.build();

            return new HarnessAgent(
                    delegate,
                    wsManager,
                    workspaceFactoryFn,
                    workspaceIndex,
                    defaultSandboxContext,
                    compactionHook,
                    sandboxLifecycleMw,
                    orderedSkillRepos,
                    planModeManager,
                    pendingSkillPromoter,
                    pendingSkillUsageStore,
                    pendingSkillCurator,
                    pendingSkillAuditLog,
                    memoryConfig,
                    capturedSubagentMw,
                    distributedStore,
                    pathNormalizer,
                    agentToolkit);
        }
    }
}
