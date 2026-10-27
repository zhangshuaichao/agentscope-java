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

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.skill.SkillFilter;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.FileSystemSkillRepository;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.coordination.LocalPeriodicGate;
import io.agentscope.harness.agent.coordination.PeriodicGate;
import io.agentscope.harness.agent.coordination.StoreBackedPeriodicGate;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory;
import io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.memory.MemoryConfig;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig;
import io.agentscope.harness.agent.middleware.DynamicSubagentsMiddleware;
import io.agentscope.harness.agent.middleware.SubagentEntry;
import io.agentscope.harness.agent.middleware.SubagentsMiddleware;
import io.agentscope.harness.agent.subagent.AgentSpecLoader;
import io.agentscope.harness.agent.subagent.DefaultAgentManager;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.SubagentFactory;
import io.agentscope.harness.agent.subagent.WorkspaceMode;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.subagent.task.WorkspaceTaskRepository;
import io.agentscope.harness.agent.workspace.WorkspaceIndex;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 支撑 {@link HarnessAgent.Builder} 编排路径的包可见静态辅助方法。
 *
 * <p>每个辅助方法接收 {@link HarnessAgent.Builder}，无需公共访问器即可读取 Harness 字段。
 */
final class HarnessAgentBuilderSupport {

    private static final Logger log = LoggerFactory.getLogger(HarnessAgentBuilderSupport.class);

    private HarnessAgentBuilderSupport() {}

    // -----------------------------------------------------------------
    // 子智能体上下文段
    // -----------------------------------------------------------------

    // @formatter:off
    /**
     * 注入每个子智能体系统提示的上下文段。
     * 定义叶子工作者的身份、规则、输出格式及禁止行为。任务本身作为首条用户消息传递，不在此重复。
     */
    static final String SUBAGENT_CONTEXT_SECTION =
            """
            # Subagent Context

            You are a **subagent** spawned by the main agent for a specific task.

            ## Your Role
            - Complete the assigned task. That's your entire purpose.
            - You are NOT the main agent. Don't try to be.

            ## Rules
            1. **Stay focused** — Do your assigned task, nothing else
            2. **Complete the task** — Your final message will be automatically reported to the main agent
            3. **Don't initiate** — No heartbeats, no proactive actions, no side quests
            4. **Be ephemeral** — You may be terminated after task completion. That's fine.
            5. **Recover from truncated tool output** — If you see `[truncated: output exceeded context limit]`, re-read only what you need using smaller chunks (read with offset/limit, or targeted grep/head/tail) instead of full re-reads
            6. **Land everything in your final message** — It is the ONLY thing the main agent receives. Every conclusion, finding, and answer must be in it, and there must be no tool calls after it: your run ends there
            7. **Cite evidence** — Back each claim with `path:line` references (e.g. `src/Main.java:42`) so the main agent can verify without re-reading
            8. **No report files** — Do not write your results to a file as the deliverable; the main agent reads your final text message only. Write files only when the task itself asks for them

            ## Output Format
            When complete, your final response should include:
            - What you accomplished or found
            - Any relevant details the main agent should know
            - Keep it concise but informative

            ## What You DON'T Do
            - NO user conversations (that's the main agent's job)
            - NO spawning further subagents — you are a leaf worker
            - NO pretending to be the main agent
            - Return plain text results; let the main agent deliver them to the user
            """;

    // @formatter:on

    /**
     * 为通用子智能体前置到 {@link #SUBAGENT_CONTEXT_SECTION} 的身份说明行。
     */
    static final String GENERAL_PURPOSE_BASE_PROMPT =
            "You are a highly capable general-purpose subagent.";

    /**
     * 向给定基础提示追加 {@link #SUBAGENT_CONTEXT_SECTION}，构建子智能体系统提示。
     * 基础提示为空白时仅使用上下文段。
     */
    static String buildSubagentSysPrompt(String basePrompt) {
        String base =
                (basePrompt != null && !basePrompt.isBlank()) ? basePrompt.stripTrailing() : "";
        return base.isEmpty() ? SUBAGENT_CONTEXT_SECTION : base + "\n\n" + SUBAGENT_CONTEXT_SECTION;
    }

    /**
     * 自定义子智能体工厂条目：名称、可选描述、工厂，以及可选的 {@link SubagentDeclaration}；
     * 其启动选项（例如 {@code persistSession}）应用于自定义实例。
     */
    record SubagentFactoryEntry(
            String name,
            String description,
            SubagentFactory factory,
            SubagentDeclaration declaration) {

        /**
         * 向编排器展示的描述，未设置时回退到名称。
         */
        String displayDescription() {
            return description != null && !description.isBlank() ? description : name;
        }
    }

    // -----------------------------------------------------------------
    // 文件系统
    // -----------------------------------------------------------------

    static AbstractFilesystem resolveFilesystem(
            HarnessAgent.Builder b,
            Path workspace,
            String agentId,
            WorkspaceIndex workspaceIndex,
            NamespaceFactory nsFactory) {
        if (b.abstractFilesystem != null) {
            return b.abstractFilesystem;
        }
        if (b.remoteFilesystemSpec != null) {
            if (workspaceIndex != null) {
                b.remoteFilesystemSpec.workspaceIndex(workspaceIndex);
            }
            return b.remoteFilesystemSpec.toFilesystem(workspace, agentId, nsFactory);
        }
        if (b.localFilesystemSpec != null) {
            return b.localFilesystemSpec.toFilesystem(workspace, nsFactory);
        }
        // 默认通过 LocalFilesystemSpec 路由，使默认项目（${user.dir}）
        // 覆盖在智能体工作区下层，对应 Claude Code 风格的双层模型。
        return new LocalFilesystemSpec().toFilesystem(workspace, nsFactory);
    }

    /**
     * 构建固定包含指定 {@code userId} 和 {@code sessionId} 的 {@link RuntimeContext}，
     * 用于通过 {@link HarnessAgent#workspaceFor(String, String)} 进行的带外 I/O。
     * 配合 {@code BakedContextFilesystem} 使用，使底层命名空间工厂始终看到此身份，
     * 不受调用方向下游传入内容影响。
     */
    static RuntimeContext buildBakedRuntimeContext(String userId, String sessionId) {
        if ((userId == null || userId.isBlank()) && (sessionId == null || sessionId.isBlank())) {
            return RuntimeContext.empty();
        }
        RuntimeContext.Builder b = RuntimeContext.builder();
        if (userId != null && !userId.isBlank()) {
            b.userId(userId);
        }
        if (sessionId != null && !sessionId.isBlank()) {
            b.sessionId(sessionId);
        }
        return b.build();
    }

    // -----------------------------------------------------------------
    // 子智能体
    // -----------------------------------------------------------------

    /**
     * 从程序化声明、{@code workspace/subagents/*.md} 及自定义工厂构建子智能体条目。
     */
    static List<SubagentEntry> buildSubagentEntries(
            HarnessAgent.Builder b, Path resolvedWorkspace, SandboxBackedFilesystem sandboxFs) {
        List<SubagentDeclaration> allDeclarations = new ArrayList<>(b.subagentDeclarations);

        Path subagentsDir = resolvedWorkspace.resolve("subagents");
        if (Files.isDirectory(subagentsDir)) {
            allDeclarations.addAll(
                    AgentSpecLoader.loadFromDirectory(subagentsDir, resolvedWorkspace));
        }

        List<SubagentEntry> entries = new ArrayList<>();

        entries.add(
                new SubagentEntry(
                        "general-purpose",
                        "General-purpose subagent with same capabilities as the main agent."
                                + " Use for any isolated task that can be fully delegated.",
                        buildGeneralPurposeFactory(b, resolvedWorkspace, sandboxFs),
                        null));

        for (SubagentDeclaration decl : allDeclarations) {
            entries.add(
                    new SubagentEntry(
                            decl.getName(),
                            decl.getDescription(),
                            buildDeclaredFactory(b, decl, resolvedWorkspace, sandboxFs),
                            decl));
        }

        for (SubagentFactoryEntry custom : b.customSubagentFactories) {
            entries.add(
                    new SubagentEntry(
                            custom.name(),
                            custom.displayDescription(),
                            custom.factory(),
                            custom.declaration()));
        }

        return entries;
    }

    /**
     * 类似 {@link #buildSubagentEntries(HarnessAgent.Builder, Path, SandboxBackedFilesystem)}，
     * 但省略本地磁盘 {@code subagents/} 扫描。{@code DynamicSubagentsMiddleware}
     * 会在每次推理步骤自行扫描（第 2 层），在此重复传入相同条目会注册两次。
     */
    static List<SubagentEntry> buildStaticSubagentEntries(
            HarnessAgent.Builder b, Path resolvedWorkspace, SandboxBackedFilesystem sandboxFs) {
        List<SubagentEntry> entries = new ArrayList<>();

        entries.add(
                new SubagentEntry(
                        "general-purpose",
                        "General-purpose subagent with same capabilities as the main agent."
                                + " Use for any isolated task that can be fully delegated.",
                        buildGeneralPurposeFactory(b, resolvedWorkspace, sandboxFs),
                        null));

        for (SubagentDeclaration decl : b.subagentDeclarations) {
            entries.add(
                    new SubagentEntry(
                            decl.getName(),
                            decl.getDescription(),
                            buildDeclaredFactory(b, decl, resolvedWorkspace, sandboxFs),
                            decl));
        }

        for (SubagentFactoryEntry custom : b.customSubagentFactories) {
            entries.add(
                    new SubagentEntry(
                            custom.name(),
                            custom.displayDescription(),
                            custom.factory(),
                            custom.declaration()));
        }

        return entries;
    }

    private static io.agentscope.harness.agent.tools.ToolsConfig childToolsConfig(
            io.agentscope.harness.agent.tools.ToolsConfig parent, List<String> allow) {
        if (allow == null || allow.isEmpty()) return parent;
        var child = new io.agentscope.harness.agent.tools.ToolsConfig();
        child.setStrictAllow(true);
        child.setDefaultToolsEnabled(false);
        child.setAllow(
                allow.stream()
                        .filter(
                                name ->
                                        io.agentscope.harness.agent.tools.ToolFilter.isAllowed(
                                                name, parent))
                        .toList());
        if (parent != null) {
            child.setDeny(parent.getDeny());
            child.setMcpServers(parent.getMcpServers());
        }
        return child;
    }

    /**
     * 构建内置通用子智能体的工厂。
     */
    static SubagentFactory buildGeneralPurposeFactory(
            HarnessAgent.Builder b, Path workspace, SandboxBackedFilesystem sandboxFs) {
        final Model capturedModel = b.model;
        final var capturedToolsConfig = b.toolsConfigOverride;
        final var capturedSkillFilter = b.skillFilter;
        final var capturedPermissions = b.permissionContextOverride;
        final var capturedDisableDefaultSkills = b.disableDefaultWorkspaceSkills;
        final var capturedSandboxSpec = b.sandboxFilesystemSpec;
        final var capturedRemoteSpec = b.remoteFilesystemSpec;
        final var capturedRoutes = Map.copyOf(b.filesystemRoutes);

        final boolean capturedPendingToolRecovery = b.enablePendingToolRecovery;
        final Toolkit capturedParentToolkit =
                b.toolkit != null ? b.toolkit.copy() : HarnessAgent.Builder.newDefaultToolkit();
        final AbstractFilesystem capturedBackend =
                sandboxFs != null ? sandboxFs : b.abstractFilesystem;
        final int capturedMaxIters = b.maxIters;
        final ExecutionConfig capturedModelExec = b.modelExecutionConfig;
        final ExecutionConfig capturedToolExec = b.toolExecutionConfig;
        final GenerateOptions capturedGenOpts = b.generateOptions;
        final String capturedEnvMemory = b.environmentMemory;
        final MemoryConfig capturedMemoryConfig = b.memoryConfig;
        final var capturedContextPolicy = b.contextPolicy;
        final List<Hook> capturedHooks = List.copyOf(b.hooks);
        final List<MiddlewareBase> capturedMiddlewares = List.copyOf(b.middlewares);
        final List<AgentSkillRepository> capturedSkillRepos = List.copyOf(b.skillRepositories);
        final Path capturedProjectGlobalSkillsDir = b.projectGlobalSkillsDir;
        final boolean capturedDisableFilesystemTools = b.disableFilesystemTools;
        final boolean capturedDisableShellTool = b.disableShellTool;
        final boolean capturedDisableMemoryTools = b.disableMemoryTools;
        final boolean capturedDisableMemoryHooks = b.disableMemoryHooks;
        final var capturedWebHttpClient = b.webHttpClient;
        final boolean capturedDisableSessionPersistence = b.disableSessionPersistence;
        final boolean capturedDisableWorkspaceContext = b.disableWorkspaceContext;
        final boolean capturedPlanModeEnabled = b.planModeEnabled;
        final boolean capturedPlanModeAllowShell = b.planModeAllowShell;
        final String capturedPlanFileDir = b.planFileDir;
        final CompactionConfig capturedCompactionConfig = b.compactionConfig;
        final boolean capturedDisableCompaction = b.disableCompaction;
        final ToolResultEvictionConfig capturedToolResultEvictionConfig =
                b.toolResultEvictionConfig;
        final boolean capturedDisableToolResultEviction = b.disableToolResultEviction;
        final boolean capturedAgentTracingLogEnabled = b.agentTracingLogEnabled;
        final List<String> capturedAdditionalContextFiles = List.copyOf(b.additionalContextFiles);
        final int capturedMaxContextTokens = b.maxContextTokens;
        // 传播父级（分布式）状态存储，使已暴露子智能体可在
        // 另一节点或重启后重新实体化，并仍按 sessionId 加载
        // 会话历史。纯本地默认部署中为 null，此时子级保持
        // 自己的本地存储，维持旧行为。
        final io.agentscope.core.state.AgentStateStore capturedStateStore = b.stateStoreOverride;
        final var capturedSessionHistory = b.sessionHistoryConfigurer();

        return (RuntimeContext parentRc) -> {
            // 通用子智能体共享父级工作区，每次启动短期存在；
            // 这里无需按父级身份分桶。接收 parentRc 是为接口
            // 兼容及未来使用预留。
            HarnessAgent.Builder sub =
                    HarnessAgent.builder()
                            .name("general-purpose-subagent")
                            .enablePendingToolRecovery(capturedPendingToolRecovery)
                            .description("General-purpose subagent for isolated task execution")
                            .sysPrompt(buildSubagentSysPrompt(GENERAL_PURPOSE_BASE_PROMPT))
                            .model(capturedModel)
                            .toolkit(capturedParentToolkit.copy())
                            .workspace(workspace)
                            .asLeafSubagent()
                            .maxIters(capturedMaxIters)
                            .environmentMemory(capturedEnvMemory)
                            .memory(capturedMemoryConfig)
                            .contextPolicy(capturedContextPolicy)
                            .enableAgentTracingLog(capturedAgentTracingLogEnabled)
                            .maxContextTokens(capturedMaxContextTokens);

            if (capturedPermissions != null) sub.permissionContext(capturedPermissions);
            if (capturedDisableDefaultSkills) sub.disableDefaultWorkspaceSkills();
            if (capturedSkillFilter != null) sub.skillFilter(capturedSkillFilter);
            capturedAdditionalContextFiles.forEach(sub::additionalContextFile);
            if (capturedToolsConfig != null) sub.toolsConfig(capturedToolsConfig);
            capturedRoutes.forEach(sub::filesystemRoute);
            if (capturedBackend == null && capturedRemoteSpec != null)
                sub.filesystem(capturedRemoteSpec);
            if (capturedBackend == null && capturedSandboxSpec != null)
                sub.filesystem(capturedSandboxSpec);

            if (capturedDisableFilesystemTools) sub.disableFilesystemTools();
            if (capturedDisableShellTool) sub.disableShellTool();
            if (capturedDisableMemoryTools) sub.disableMemoryTools();
            if (capturedDisableMemoryHooks) sub.disableMemoryHooks();
            if (capturedWebHttpClient != null) sub.webHttpClient(capturedWebHttpClient);
            if (capturedDisableSessionPersistence) sub.disableSessionPersistence();
            if (capturedDisableWorkspaceContext) sub.disableWorkspaceContext();
            configurePlanMode(
                    sub, capturedPlanModeEnabled, capturedPlanModeAllowShell, capturedPlanFileDir);

            if (!capturedSkillRepos.isEmpty()) sub.skillRepositories(capturedSkillRepos);
            if (capturedProjectGlobalSkillsDir != null) {
                sub.projectGlobalSkillsDir(capturedProjectGlobalSkillsDir);
            }
            if (capturedBackend != null) sub.abstractFilesystem(capturedBackend);
            capturedSessionHistory.accept(sub);
            if (capturedStateStore != null) sub.stateStore(capturedStateStore);
            if (capturedModelExec != null) sub.modelExecutionConfig(capturedModelExec);
            if (capturedToolExec != null) sub.toolExecutionConfig(capturedToolExec);
            if (capturedGenOpts != null) sub.generateOptions(capturedGenOpts);
            if (capturedDisableCompaction) {
                sub.disableCompaction();
            } else if (capturedCompactionConfig != null) {
                sub.compaction(capturedCompactionConfig);
            }
            if (capturedDisableToolResultEviction) {
                sub.disableToolResultEviction();
            } else if (capturedToolResultEvictionConfig != null) {
                sub.toolResultEviction(capturedToolResultEvictionConfig);
            }

            sub.middlewares(capturedMiddlewares);
            sub.hooks(capturedHooks);

            return sub.build();
        };
    }

    /**
     * 根据 {@link SubagentDeclaration} 构建用户声明子智能体的工厂。
     */
    static SubagentFactory buildDeclaredFactory(
            HarnessAgent.Builder b,
            SubagentDeclaration decl,
            Path mainWorkspace,
            SandboxBackedFilesystem sandboxFs) {
        final Model capturedModel = b.model;
        final var capturedToolsConfig = b.toolsConfigOverride;
        final var capturedSkillFilter = b.skillFilter;
        final var capturedPermissions = b.permissionContextOverride;
        final var capturedDisableDefaultSkills = b.disableDefaultWorkspaceSkills;
        final var capturedSandboxSpec = b.sandboxFilesystemSpec;
        final var capturedRemoteSpec = b.remoteFilesystemSpec;
        final var capturedRoutes = Map.copyOf(b.filesystemRoutes);

        final boolean capturedPendingToolRecovery =
                decl.getEnablePendingToolRecovery() != null
                        ? decl.getEnablePendingToolRecovery()
                        : b.enablePendingToolRecovery;
        final Toolkit capturedParentToolkit =
                b.toolkit != null ? b.toolkit.copy() : HarnessAgent.Builder.newDefaultToolkit();
        final Function<String, Model> capturedResolver = b.modelResolver;
        final List<MiddlewareBase> capturedMiddlewares = List.copyOf(b.middlewares);
        final AbstractFilesystem capturedSharedBackend =
                sandboxFs != null ? sandboxFs : b.abstractFilesystem;
        final MemoryConfig capturedMemoryConfig = b.memoryConfig;
        final var capturedContextPolicy = b.contextPolicy;
        final boolean capturedDisableFilesystemTools = b.disableFilesystemTools;
        final boolean capturedDisableShellTool = b.disableShellTool;
        final boolean capturedDisableMemoryTools = b.disableMemoryTools;
        final boolean capturedDisableMemoryHooks = b.disableMemoryHooks;
        final var capturedWebHttpClient = b.webHttpClient;
        final boolean capturedDisableSessionPersistence = b.disableSessionPersistence;
        final boolean capturedPlanModeEnabled = b.planModeEnabled;
        final boolean capturedPlanModeAllowShell = b.planModeAllowShell;
        final String capturedPlanFileDir = b.planFileDir;
        final GenerateOptions capturedGenOpts = b.generateOptions;
        // 参见 buildGeneralPurposeFactory：传播父级模型／工具执行配置，
        // 使声明式子智能体遵循父级超时。否则会回退到
        // ExecutionConfig 默认值（例如 5 分钟工具超时），忽略主智能体
        // 配置的更长超时。
        final ExecutionConfig capturedModelExec = b.modelExecutionConfig;
        final ExecutionConfig capturedToolExec = b.toolExecutionConfig;
        // 主智能体本地文件系统配置快照。ISOLATED 子智能体获得新的
        // 配置，携带相同 project／additionalRoots／mode，使 PathPolicy 保持
        // 一致；否则每个隔离子智能体会默认 project=${user.dir}，
        // 丢失主级配置的 --add-dir 风格允许列表。
        final io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec
                capturedLocalFilesystemSpec = b.localFilesystemSpec;
        final List<AgentSkillRepository> capturedSkillRepos = List.copyOf(b.skillRepositories);
        final Path capturedProjectGlobalSkillsDir = b.projectGlobalSkillsDir;
        final CompactionConfig capturedCompactionConfig = b.compactionConfig;
        final boolean capturedDisableCompaction = b.disableCompaction;
        // 参见 buildGeneralPurposeFactory：传播父级（分布式）状态存储，
        // 使子智能体会话可跨节点重新实体化；本地默认值为 null。
        final io.agentscope.core.state.AgentStateStore capturedStateStore = b.stateStoreOverride;
        final var capturedSessionHistory = b.sessionHistoryConfigurer();

        return (RuntimeContext parentRc) -> {
            if (decl.isRemote()) {
                return new io.agentscope.harness.agent.subagent.RemoteSubagentStub(
                        decl.getName(), decl.getDescription());
            }
            // ---- 解析工作区根目录 ----
            Path runtimeWorkspace = resolveDeclaredWorkspace(decl, mainWorkspace);

            // ---- 解析系统提示 ----
            String sysPromptBase = resolveDeclaredSysPromptBase(decl);

            // ---- 解析模型 ----
            Model effectiveModel =
                    resolveModel(decl.getModel(), capturedModel, capturedResolver, decl.getName());

            // ---- 派生子会话 ID：按父级身份对持久化 AgentState 分桶 ----
            // （阶段 B-0）否则每个用户／父会话都会共享同一桶，
            // 能够通过 AgentStateStore.get(...) 读取彼此的子智能体会话。
            String childSessionId = deriveChildSessionId(decl, parentRc);

            // ---- 构建子智能体 ----
            HarnessAgent.Builder sub =
                    HarnessAgent.builder()
                            .name(decl.getName())
                            .enablePendingToolRecovery(capturedPendingToolRecovery)
                            .description(decl.getDescription())
                            .model(effectiveModel)
                            .toolkit(
                                    allowlistedInheritedToolkit(
                                            capturedParentToolkit, decl.getTools()))
                            .workspace(runtimeWorkspace)
                            .defaultSessionId(childSessionId)
                            .maxIters(decl.getSteps())
                            .asLeafSubagent()
                            .memory(capturedMemoryConfig)
                            .contextPolicy(capturedContextPolicy)
                            .sysPrompt(buildSubagentSysPrompt(sysPromptBase));

            // 将声明指定的 temperature/topP 叠加到父级
            // GenerateOptions 之上。
            // 覆盖项中的 null 字段通过 GenerateOptions.mergeOptions 回退到父级。
            // 注意：SubagentDeclaration.variant 会解析并保留在声明中，但
            // 目前不会接入 GenerateOptions，因为核心 Model 层没有 variant
            // 概念；在 variant 支持落地之前，仅将其视为面向未来的 schema 字段存储。
            if (decl.getTemperature() != null || decl.getTopP() != null) {
                GenerateOptions overlay =
                        GenerateOptions.builder()
                                .temperature(decl.getTemperature())
                                .topP(decl.getTopP())
                                .build();
                sub.generateOptions(GenerateOptions.mergeOptions(overlay, capturedGenOpts));
            } else if (capturedGenOpts != null) {
                sub.generateOptions(capturedGenOpts);
            }

            var childTools = childToolsConfig(capturedToolsConfig, decl.getTools());
            if (childTools != null) sub.toolsConfig(childTools);
            capturedRoutes.forEach(sub::filesystemRoute);
            if (decl.getWorkspaceMode() == WorkspaceMode.SHARED && capturedSharedBackend != null) {
                sub.abstractFilesystem(capturedSharedBackend);
            } else if (capturedSandboxSpec != null) {
                sub.filesystem(capturedSandboxSpec);
            } else if (capturedRemoteSpec != null) {
                sub.filesystem(capturedRemoteSpec);
            } else if (decl.getWorkspaceMode() != WorkspaceMode.SHARED
                    && capturedLocalFilesystemSpec != null) {
                sub.filesystem(cloneLocalSpecForSubagent(capturedLocalFilesystemSpec));
            }

            capturedSessionHistory.accept(sub);
            if (capturedStateStore != null) {
                sub.stateStore(capturedStateStore);
            }

            if (capturedModelExec != null) sub.modelExecutionConfig(capturedModelExec);
            if (capturedToolExec != null) sub.toolExecutionConfig(capturedToolExec);

            CompactionConfig declaredCompactionConfig = decl.getCompactionConfig();
            if (decl.isCompactionDisabled()) {
                sub.disableCompaction();
            } else if (declaredCompactionConfig != null) {
                sub.compaction(declaredCompactionConfig);
            } else if (capturedDisableCompaction) {
                sub.disableCompaction();
            } else if (capturedCompactionConfig != null) {
                sub.compaction(capturedCompactionConfig);
            }

            if (capturedDisableFilesystemTools) sub.disableFilesystemTools();
            if (capturedDisableShellTool) sub.disableShellTool();
            if (capturedDisableMemoryTools) sub.disableMemoryTools();
            if (capturedDisableMemoryHooks) sub.disableMemoryHooks();
            if (capturedWebHttpClient != null) sub.webHttpClient(capturedWebHttpClient);
            if (capturedDisableSessionPersistence) sub.disableSessionPersistence();
            configurePlanMode(
                    sub, capturedPlanModeEnabled, capturedPlanModeAllowShell, capturedPlanFileDir);

            if (!capturedSkillRepos.isEmpty()) sub.skillRepositories(capturedSkillRepos);
            if (capturedProjectGlobalSkillsDir != null) {
                sub.projectGlobalSkillsDir(capturedProjectGlobalSkillsDir);
            }

            if (capturedPermissions != null) sub.permissionContext(capturedPermissions);
            if (capturedDisableDefaultSkills) sub.disableDefaultWorkspaceSkills();
            if (capturedSkillFilter != null) sub.skillFilter(capturedSkillFilter);
            List<String> skillAllowlist = decl.getSkills();
            if (!skillAllowlist.isEmpty()) {
                sub.skillFilter(
                        SkillFilter.only(
                                skillAllowlist.stream()
                                        .filter(
                                                name ->
                                                        capturedSkillFilter == null
                                                                || capturedSkillFilter.isAllowed(
                                                                        name))
                                        .toArray(String[]::new)));
            }

            sub.middlewares(capturedMiddlewares);
            return sub.build();
        };
    }

    /**
     * 将构建时计划模式能力传播给自动构建的子智能体。
     *
     * <p>仅复制父级显式中间件列表还不够：{@code PlanModeMiddleware} 和 {@code PlanModeManager}
     * 由 {@link HarnessAgent.Builder#build()} 动态安装。因此必须在构建子智能体之前
     * 将构建器配置传给它；之后仅设置 {@code AgentState.planActive} 会暴露状态，却没有安装写操作保护。
     */
    private static void configurePlanMode(
            HarnessAgent.Builder sub,
            boolean planModeEnabled,
            boolean planModeAllowShell,
            String planFileDir) {
        if (!planModeEnabled) {
            return;
        }
        // 【学习提示】运行时的“处于计划模式”状态与构建时的“具备计划模式保护”不同；
        // 子智能体必须先安装相关能力，后续状态传播才能由对应中间件解释和约束。
        sub.enablePlanMode()
                .planFileDirectory(planFileDir)
                .allowShellInPlanMode(planModeAllowShell);
    }

    /**
     * 为 ISOLATED 子智能体构建新的 {@link io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec}，
     * 复制主智能体 project、mode 和 additionalRoots，使子级 {@code PathPolicy} 与父级一致。
     * 子智能体拥有自己的工作区（通过 {@code sub.workspace(...)} 单独传入），保持 MEMORY／会话隔离；
     * 仅共享允许列表输入。
     */
    private static io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec
            cloneLocalSpecForSubagent(
                    io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec parent) {
        io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec spec =
                new io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec();
        if (parent.getProject() != null) {
            spec.project(parent.getProject());
        }
        if (parent.getMode() != null) {
            spec.mode(parent.getMode());
        }
        spec.additionalRoots(parent.getAdditionalRoots());
        return spec;
    }

    /**
     * 返回继承父级工具的防御性副本，并按可选允许列表过滤。
     */
    static Toolkit allowlistedInheritedToolkit(Toolkit parentToolkit, List<String> allowlist) {
        Toolkit toolkit =
                parentToolkit != null
                        ? parentToolkit.copy()
                        : HarnessAgent.Builder.newDefaultToolkit();
        if (allowlist == null || allowlist.isEmpty()) {
            return toolkit;
        }
        List<String> toRemove =
                toolkit.getToolSchemas().stream()
                        .map(ToolSchema::getName)
                        .filter(name -> !allowlist.contains(name))
                        .toList();
        toRemove.forEach(toolkit::removeTool);
        return toolkit;
    }

    /**
     * 组合子智能体持久化会话 ID，按声明名称及启动时父级身份分桶：
     *
     * <pre>
     * {declarationName}[@{parentSessionId}][#{userId}]
     * </pre>
     *
     * <p>省略空／空白 uid 或 sid 段，保持单租户演示与旧式单桶形式兼容。
     * {@link WorkspaceMode#SHARED} 子智能体有意回退到旧式单桶形式，因为设计上它们共享父级完整状态树。
     *
     * <p>对 {@link io.agentscope.core.state.AgentStateStore} 的 Workspace、Redis、InMemory 及自定义实现
     * 均有效，因为它们都按会话 ID 对 {@code save}／{@code get} 分桶。（阶段 B-0）
     */
    static String deriveChildSessionId(SubagentDeclaration decl, RuntimeContext parentRc) {
        String declName = decl.getName();
        if (decl.getWorkspaceMode() == WorkspaceMode.SHARED || parentRc == null) {
            return declName;
        }
        String sid = sanitizeIdentifier(parentRc.getSessionId());
        String uid = sanitizeIdentifier(parentRc.getUserId());
        if (sid == null && uid == null) {
            return declName;
        }
        StringBuilder sb = new StringBuilder(declName);
        if (sid != null) sb.append('@').append(sid);
        if (uid != null) sb.append('#').append(uid);
        return sb.toString();
    }

    /**
     * 输入为 null 或空白时返回 {@code null}；否则将会扰乱基于路径的 AgentStateStore 的字符
     * （斜杠、反斜杠、空白、控制字符）替换为下划线。
     * Redis／InMemory／SQL 的键为不透明存储形式，不受影响。
     */
    static String sanitizeIdentifier(String s) {
        if (s == null) return null;
        String trimmed = s.trim();
        if (trimmed.isEmpty()) return null;
        return trimmed.replaceAll("[/\\\\\\s\\p{Cntrl}]", "_");
    }

    /**
     * 解析声明式子智能体的运行时工作区根目录，必要时创建自动生成的隔离目录。
     */
    static Path resolveDeclaredWorkspace(SubagentDeclaration decl, Path mainWorkspace) {
        if (decl.getWorkspacePath() != null) {
            if (decl.getWorkspaceMode() == WorkspaceMode.SHARED) {
                return mainWorkspace;
            }
            return decl.getWorkspacePath();
        }
        if (decl.getWorkspaceMode() == WorkspaceMode.SHARED) {
            return mainWorkspace;
        }
        // ISOLATED 且未指定路径：自动创建 agents/<name>/workspace/
        Path isolated =
                mainWorkspace.resolve("agents").resolve(decl.getName()).resolve("workspace");
        try {
            Files.createDirectories(isolated);
        } catch (Exception e) {
            log.warn(
                    "Failed to create isolated workspace for subagent '{}' at {}: {}",
                    decl.getName(),
                    isolated,
                    e.getMessage());
        }
        return isolated;
    }

    /**
     * 解析声明式子智能体的系统提示基础内容。
     */
    static String resolveDeclaredSysPromptBase(SubagentDeclaration decl) {
        if (decl.getWorkspacePath() != null) {
            Path agentsMd = decl.getWorkspacePath().resolve("AGENTS.md");
            if (Files.isRegularFile(agentsMd)) {
                try {
                    return Files.readString(agentsMd, java.nio.charset.StandardCharsets.UTF_8);
                } catch (Exception e) {
                    log.warn(
                            "Failed to read AGENTS.md for subagent '{}' from {}: {}",
                            decl.getName(),
                            agentsMd,
                            e.getMessage());
                }
            }
            return "";
        }
        String inline = decl.getInlineAgentsBody();
        return (inline != null) ? inline : "";
    }

    /**
     * 解析子智能体实际使用的 {@link Model}，应用可选覆盖。
     */
    static Model resolveModel(
            String modelOverride,
            Model parentModel,
            Function<String, Model> resolver,
            String subagentName) {
        if (modelOverride == null || modelOverride.isBlank()) {
            return parentModel;
        }
        Function<String, Model> effectiveResolver =
                resolver != null ? resolver : ModelRegistry::resolve;
        if (ModelRegistry.canResolve(modelOverride) || resolver != null) {
            try {
                Model resolved = effectiveResolver.apply(modelOverride);
                if (resolved != null) {
                    log.debug(
                            "Subagent '{}' using overridden model: {}",
                            subagentName,
                            modelOverride);
                    return resolved;
                }
            } catch (Exception e) {
                log.warn(
                        "Failed to resolve model '{}' for subagent '{}', falling back to parent"
                                + " model: {}",
                        modelOverride,
                        subagentName,
                        e.getMessage());
            }
        }
        return parentModel;
    }

    // -----------------------------------------------------------------
    // 子智能体中间件
    // -----------------------------------------------------------------

    static SubagentsMiddleware buildSubagentsMiddleware(
            HarnessAgent.Builder b,
            WorkspaceManager wsManager,
            Path workspace,
            SandboxBackedFilesystem sandboxFs) {
        List<SubagentEntry> entries = buildSubagentEntries(b, workspace, sandboxFs);
        TaskRepository repo = resolveTaskRepository(b, wsManager);

        if (b.externalSubagentTool != null) {
            return new SubagentsMiddleware(entries, b.externalSubagentTool, repo);
        }

        AbstractFilesystem fs = wsManager.getFilesystem();
        Function<SubagentDeclaration, SubagentFactory> factoryFn =
                decl -> buildDeclaredFactory(b, decl, workspace, sandboxFs);
        return new SubagentsMiddleware(entries, repo, wsManager, fs, workspace, factoryFn);
    }

    static DynamicSubagentsMiddleware buildDynamicSubagentsMiddleware(
            HarnessAgent.Builder b,
            WorkspaceManager wsManager,
            Path workspace,
            SandboxBackedFilesystem sandboxFs) {
        List<SubagentEntry> staticEntries = buildStaticSubagentEntries(b, workspace, sandboxFs);
        TaskRepository repo = resolveTaskRepository(b, wsManager);

        AbstractFilesystem fs = wsManager.getFilesystem();
        Function<SubagentDeclaration, SubagentFactory> factoryFn =
                decl -> buildDeclaredFactory(b, decl, workspace, sandboxFs);
        DefaultAgentManager manager = new DefaultAgentManager(staticEntries, wsManager);
        return new DynamicSubagentsMiddleware(
                staticEntries, fs, workspace, factoryFn, manager, b.externalSubagentTool, repo);
    }

    private static TaskRepository resolveTaskRepository(
            HarnessAgent.Builder b, WorkspaceManager wsManager) {
        if (b.taskRepository != null) {
            return b.taskRepository;
        }
        if (b.distributedStore != null) {
            TaskRepository distributed = b.distributedStore.taskRepository();
            if (distributed != null) {
                return distributed;
            }
        }
        Objects.requireNonNull(
                wsManager,
                "WorkspaceManager must be non-null when resolving the default TaskRepository;"
                    + " HarnessAgent.build() always constructs one. Pass an explicit"
                    + " .taskRepository(...) if you need a non-workspace-backed implementation.");
        String taskAgentId =
                b.agentId != null && !b.agentId.isBlank()
                        ? b.agentId
                        : (b.name != null && !b.name.isBlank() ? b.name : "ReActAgent");
        return new WorkspaceTaskRepository(wsManager, taskAgentId, resolvePeriodicGate(b));
    }

    /**
     * 为基于工作区的孤立任务清扫解析 {@link PeriodicGate}：优先使用 {@link DistributedStore} 的 {@link BaseStore}，
     * 其次使用附加到 {@code RemoteFilesystemSpec} 的存储，否则使用进程局部门控。
     */
    private static PeriodicGate resolvePeriodicGate(HarnessAgent.Builder b) {
        if (b.distributedStore != null) {
            BaseStore store = b.distributedStore.baseStore();
            if (store != null) {
                return new StoreBackedPeriodicGate(store);
            }
        }
        if (b.remoteFilesystemSpec != null) {
            BaseStore store = b.remoteFilesystemSpec.store();
            if (store != null) {
                return new StoreBackedPeriodicGate(store);
            }
        }
        return new LocalPeriodicGate();
    }

    // -----------------------------------------------------------------
    // 技能
    // -----------------------------------------------------------------

    /**
     * 组装本次构建使用的技能仓库有序列表（优先级从低到高）。
     */
    static List<AgentSkillRepository> composeSkillRepositories(
            HarnessAgent.Builder b, WorkspaceManager wsManager, AbstractFilesystem filesystem) {
        // 【学习提示】这里仅按由低到高的优先级组合仓库，具体技能加载交给技能中间件；
        // 默认工作区层可退出，因此不要把“默认可见”误解为不可覆盖的内置能力。
        List<AgentSkillRepository> ordered = new ArrayList<>();

        // 第 1 层（最低优先级）：项目全局技能目录。
        if (b.projectGlobalSkillsDir != null && Files.isDirectory(b.projectGlobalSkillsDir)) {
            try {
                ordered.add(new FileSystemSkillRepository(b.projectGlobalSkillsDir));
            } catch (Exception e) {
                log.warn(
                        "Failed to register project-global skills dir {}: {}",
                        b.projectGlobalSkillsDir,
                        e.getMessage());
            }
        }

        // 第 2 层：市场仓库（用户提供）。
        ordered.addAll(b.skillRepositories);

        // 第 3 层：工作区智能体共享目录。
        Path workspaceSkillsDir = wsManager.getSkillsDir();
        if (workspaceSkillsDir != null && Files.isDirectory(workspaceSkillsDir)) {
            try {
                ordered.add(new FileSystemSkillRepository(workspaceSkillsDir));
            } catch (Exception e) {
                log.warn(
                        "Failed to load workspace skills from {}: {}",
                        workspaceSkillsDir,
                        e.getMessage());
            }
        }

        // 第 4 层（最高优先级）：通过新的 WorkspaceSkillRepository 提供按用户命名空间的文件系统视图
        // （替代旧 FilesystemBackedSkillRepository）。
        // 用户通过 disableDefaultWorkspaceSkills() 退出时跳过。
        if (filesystem != null && !b.disableDefaultWorkspaceSkills) {
            ordered.add(
                    new io.agentscope.harness.agent.skill.WorkspaceSkillRepository(
                            filesystem, "skills", "workspace-namespaced", false));
        }

        return ordered;
    }
}
