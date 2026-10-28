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
package io.agentscope.harness.coding;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.channel.dingtalk.DingTalkChannel;
import io.agentscope.extensions.channel.feishu.FeishuChannel;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.gateway.ChannelManager;
import io.agentscope.harness.agent.gateway.Gateway;
import io.agentscope.harness.agent.gateway.channel.Channel;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiChannel;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.middleware.SubagentEntry;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerFilesystemSpec;
import io.agentscope.harness.agent.subagent.DefaultAgentManager;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.subagent.task.WorkspaceTaskRepository;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import io.agentscope.harness.coding.agent.CodingAgentFactory;
import io.agentscope.harness.coding.agent.ReviewerAgentFactory;
import io.agentscope.harness.coding.config.AgentConfigEntry;
import io.agentscope.harness.coding.config.AgentscopeConfig;
import io.agentscope.harness.coding.config.ChannelConfigEntry;
import io.agentscope.harness.coding.config.SkillRepositorySupport;
import io.agentscope.harness.coding.gateway.HarnessGateway;
import io.agentscope.harness.coding.middleware.MessageQueueMiddleware;
import io.agentscope.harness.coding.middleware.ModelCallLimitMiddleware;
import io.agentscope.harness.coding.middleware.ThreadBudgetMiddleware;
import io.agentscope.harness.coding.session.AgentManagerConfig;
import io.agentscope.harness.coding.session.SessionAgentManager;
import io.agentscope.harness.coding.session.SessionStore;
import io.agentscope.harness.coding.session.SubagentRunRegistry;
import io.agentscope.harness.coding.session.tool.SessionsTool;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 组装、配置和运行 AgentScope Harness 的统一 API 类。
 *
 * <h2>构建阶段：{@link #builder()}</h2>
 *
 * 加载 {@code ~/.agentscope/codingagent/agentscope.json}（应用独立主目录，与其他 Harness 应用及 JVM 启动目录隔离），合并文件定义与编程式 {@link Builder} 配置，生成接入 {@link SessionsTool}、共享 {@link SessionAgentManager} 和 {@link HarnessGateway} 的 {@link HarnessAgent} 实例。
 *
 * <h2>运行阶段：两个入口</h2>
 * <ol>
 * <li>{@link #chatUiChannel()}：获取 {@link ChatUiChannel} 以直接编程交互（嵌入式 UI、CLI、测试），立即可用，无需 {@link #start()}</li>
 * <li>{@link #start()}：初始化并启动所有预注册通道适配器</li>
 * </ol>
 */
public final class CodingBootstrap {

    private static final Logger log = LoggerFactory.getLogger(CodingBootstrap.class);
    private static final String DEFAULT_MAIN_ID = "default";

    /**
     * 每个编码智能体实例的默认工作区根目录。位于项目树外，避免技能/记忆/会话污染启动目录，并防止不同 Harness 应用（codingagent、builder、dataagent、claw）争用同一 {@code .agentscope/workspace/} 目录。
     */
    public static final Path DEFAULT_WORKSPACE_ROOT =
            Paths.get(System.getProperty("user.home"), ".agentscope", "codingagent", "workspace");

    /**
     * {@code agentscope.json} 的默认位置，固定在应用独立主目录（{@code ~/.agentscope/codingagent/}），避免编码智能体读到其他 Harness 应用（例如 dataagent）留在启动目录中的旧配置。
     */
    public static final Path DEFAULT_CONFIG_PATH =
            Paths.get(
                    System.getProperty("user.home"),
                    ".agentscope",
                    "codingagent",
                    "agentscope.json");

    // -----------------------------------------------------------------
    // 实例状态：由 Builder.build() 填充
    // -----------------------------------------------------------------

    private final Path cwd;
    private final Path configPath;
    private final String mainAgentId;
    private final Map<String, HarnessAgent> agents;
    private final AbstractFilesystem globalFilesystem;
    private final List<Channel> registeredChannels;
    private final HarnessGateway gateway;
    private final ChannelManager channelManager;

    private CodingBootstrap(
            Path cwd,
            Path configPath,
            String mainAgentId,
            Map<String, HarnessAgent> agents,
            AbstractFilesystem globalFilesystem,
            List<Channel> registeredChannels,
            HarnessGateway gateway,
            ChannelManager channelManager) {
        this.cwd = Objects.requireNonNull(cwd, "cwd");
        this.configPath = Objects.requireNonNull(configPath, "configPath");
        this.mainAgentId = Objects.requireNonNull(mainAgentId, "mainAgentId");
        this.agents = Objects.requireNonNull(agents, "agents");
        this.globalFilesystem = globalFilesystem;
        this.registeredChannels =
                registeredChannels != null ? List.copyOf(registeredChannels) : List.of();
        this.gateway = gateway;
        this.channelManager = channelManager;
    }

    // -----------------------------------------------------------------
    // 静态工厂/工具方法
    // -----------------------------------------------------------------

    public static Builder builder() {
        return new Builder();
    }

    public static AgentscopeConfig loadConfig() throws IOException {
        return loadConfigFile(DEFAULT_CONFIG_PATH);
    }

    public static AgentscopeConfig loadConfigFile(Path configPath) throws IOException {
        if (!Files.isRegularFile(configPath)) {
            return new AgentscopeConfig();
        }
        ObjectMapper mapper =
                new ObjectMapper()
                        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper.readValue(configPath.toFile(), AgentscopeConfig.class);
    }

    // -----------------------------------------------------------------
    // 公开运行时 API
    // -----------------------------------------------------------------

    public ChatUiChannel chatUiChannel() {
        return ChatUiChannel.create(resolveGateway());
    }

    public ChatUiChannel chatUiChannel(ChannelConfig config) {
        return ChatUiChannel.create(resolveGateway(), Objects.requireNonNull(config, "config"));
    }

    public CodingBootstrap start() {
        start(registeredChannels.toArray(new Channel[0]));
        return this;
    }

    public void start(Channel... channels) {
        Objects.requireNonNull(channels, "channels");
        Gateway g = resolveGateway();
        if (channelManager != null) {
            for (Channel channel : channels) {
                if (channel != null) {
                    channelManager.register(channel);
                }
            }
            channelManager.initAll(g);
            channelManager.startAll();
        } else {
            for (Channel channel : channels) {
                if (channel != null) {
                    channel.init(g);
                    channel.start();
                }
            }
        }
    }

    /**
     * 停止通道管理器管理的所有通道。
     */
    public void stop() {
        if (channelManager != null) {
            channelManager.stopAll();
        }
    }

    // -----------------------------------------------------------------
    // 包级私有访问器
    // -----------------------------------------------------------------

    String mainAgentId() {
        return mainAgentId;
    }

    Map<String, HarnessAgent> agents() {
        return agents;
    }

    HarnessAgent mainAgent() {
        HarnessAgent a = agents.get(mainAgentId);
        if (a == null) {
            throw new IllegalStateException("Main agent not registered: " + mainAgentId);
        }
        return a;
    }

    AbstractFilesystem globalFilesystem() {
        return globalFilesystem;
    }

    Path cwd() {
        return cwd;
    }

    Path configPath() {
        return configPath;
    }

    List<Channel> registeredChannels() {
        return registeredChannels;
    }

    public HarnessGateway gateway() {
        return gateway;
    }

    /**
     * 用于通道生命周期和出站投递的通道管理器。
     */
    public ChannelManager channelManager() {
        return channelManager;
    }

    // -----------------------------------------------------------------
    // 内部辅助方法
    // -----------------------------------------------------------------

    private Gateway resolveGateway() {
        if (gateway != null) {
            return gateway;
        }
        throw new IllegalStateException(
                "No gateway available: the main agent has subagents disabled."
                        + " Enable subagents (the default) so a gateway can be created.");
    }

    private static List<Channel> resolveChannels(
            Map<String, Channel> builderChannels, AgentscopeConfig fileConfig) {
        Map<String, ChannelConfigEntry> fileChannels =
                fileConfig.getChannels() != null ? fileConfig.getChannels() : Map.of();

        Map<String, Channel> merged = new LinkedHashMap<>(builderChannels);

        for (Map.Entry<String, ChannelConfigEntry> entry : fileChannels.entrySet()) {
            String channelId = entry.getKey();
            ChannelConfigEntry ce = entry.getValue();
            if (Boolean.TRUE.equals(ce.getDisabled())) {
                merged.remove(channelId);
                continue;
            }
            if (merged.containsKey(channelId)) {
                continue;
            }
            if (ChatUiChannel.CHANNEL_ID.equals(channelId)) {
                merged.put(channelId, ChatUiChannel.create(ce.toChannelConfig(channelId)));
            } else if (DingTalkChannel.TYPE.equals(channelId)) {
                merged.put(
                        channelId,
                        DingTalkChannel.fromProperties(
                                channelId, ce.toChannelConfig(channelId), ce.getProperties()));
            } else if (FeishuChannel.TYPE.equals(channelId)) {
                merged.put(
                        channelId,
                        FeishuChannel.fromProperties(
                                channelId, ce.toChannelConfig(channelId), ce.getProperties()));
            } else {
                log.debug(
                        "Channel '{}' configured in file but no implementation registered via"
                                + " AgentBootstrap.Builder.channel(); skipping auto-creation.",
                        channelId);
            }
        }
        return List.copyOf(merged.values());
    }

    static void applyFileEntry(
            Path cwd, String agentId, AgentConfigEntry e, HarnessAgent.Builder b) {
        String name =
                (e != null && e.getName() != null && !e.getName().isBlank())
                        ? e.getName()
                        : agentId;
        b.name(name);

        if (e != null) {
            if (e.getDescription() != null) {
                b.description(e.getDescription());
            }
            if (e.getSysPrompt() != null) {
                b.sysPrompt(e.getSysPrompt());
            }
            Path workspace =
                    e.getWorkspace() != null && !e.getWorkspace().isBlank()
                            ? cwd.resolve(e.getWorkspace()).normalize()
                            : DEFAULT_WORKSPACE_ROOT;
            b.workspace(workspace);

            if (e.getMaxIters() != null) {
                b.maxIters(e.getMaxIters());
            }
            if (e.getEnvironmentMemory() != null) {
                b.environmentMemory(e.getEnvironmentMemory());
            }
            if (e.getSkillRepository() != null) {
                var repo = SkillRepositorySupport.create(cwd, e.getSkillRepository());
                if (repo != null) {
                    b.skillRepository(repo);
                }
            }
        } else {
            b.workspace(DEFAULT_WORKSPACE_ROOT);
        }
    }

    // -----------------------------------------------------------------
    // 构建器
    // -----------------------------------------------------------------

    public static final class Builder {

        private Path cwd = Paths.get(System.getProperty("user.dir"));
        private Path configPath;
        private boolean skipConfigFile;
        private Model model;
        private AbstractFilesystem globalFileSystem;
        private String mainAgentId;
        private final Map<String, HarnessAgent> prebuilt = new LinkedHashMap<>();
        private final Map<String, Consumer<HarnessAgent.Builder>> configurators =
                new LinkedHashMap<>();
        private final Map<String, Channel> channels = new LinkedHashMap<>();

        private Builder() {}

        public Builder cwd(Path cwd) {
            this.cwd = Objects.requireNonNull(cwd, "cwd").toAbsolutePath().normalize();
            return this;
        }

        public Builder configPath(Path configPath) {
            this.configPath = Objects.requireNonNull(configPath, "configPath");
            return this;
        }

        public Builder skipConfigFile(boolean skip) {
            this.skipConfigFile = skip;
            return this;
        }

        public Builder model(Model model) {
            this.model = model;
            return this;
        }

        public Builder abstractFilesystem(AbstractFilesystem filesystem) {
            this.globalFileSystem = filesystem;
            return this;
        }

        public Builder mainAgent(String agentId) {
            this.mainAgentId = agentId;
            return this;
        }

        public Builder agent(String agentId, HarnessAgent agent) {
            Objects.requireNonNull(agentId, "agentId");
            Objects.requireNonNull(agent, "agent");
            this.prebuilt.put(agentId, agent);
            return this;
        }

        public Builder configureAgent(String agentId, Consumer<HarnessAgent.Builder> customizer) {
            Objects.requireNonNull(agentId, "agentId");
            Objects.requireNonNull(customizer, "customizer");
            this.configurators.put(agentId, customizer);
            return this;
        }

        public Builder channel(Channel... channels) {
            Objects.requireNonNull(channels, "channels");
            for (Channel c : channels) {
                if (c != null) {
                    this.channels.put(c.channelId(), c);
                }
            }
            return this;
        }

        /**
         * 便捷方法：通过各自工厂（{@link CodingAgentFactory} / {@link ReviewerAgentFactory}）注册编码与审查智能体。
         *
         * <p>为两个智能体应用工具集与提示词配置器。文件配置中的 workspace / maxIters 条目仍通过 {@link #applyFileEntry} 优先生效。
         *
         * @param codingToolkit 编码智能体工具集（null 表示空）
         * @param reviewerToolkit 审查智能体工具集（null 表示空）
         */
        public Builder withDualCodingAgents(
                io.agentscope.core.tool.Toolkit codingToolkit,
                io.agentscope.core.tool.Toolkit reviewerToolkit) {
            return withDualCodingAgents(codingToolkit, reviewerToolkit, null);
        }

        /**
         * 与 {@link #withDualCodingAgents(io.agentscope.core.tool.Toolkit, io.agentscope.core.tool.Toolkit)} 相同，但 {@code store} 非 null 时，还为两个智能体注册 open-swe 风格中间件栈（{@link MessageQueueMiddleware}、{@link ThreadBudgetMiddleware}、{@link ModelCallLimitMiddleware}）。无 store 时中间件静默降级，适用于没有线程路由的普通 CLI/测试。
         */
        public Builder withDualCodingAgents(
                io.agentscope.core.tool.Toolkit codingToolkit,
                io.agentscope.core.tool.Toolkit reviewerToolkit,
                BaseStore store) {
            this.configureAgent(
                    "coding",
                    b -> {
                        if (this.model != null) {
                            b.model(this.model);
                        } else {
                            b.model(CodingAgentFactory.buildModel());
                        }
                        if (codingToolkit != null) {
                            b.toolkit(codingToolkit);
                        }
                        b.sysPrompt(
                                io.agentscope.harness.coding.prompt.CodingSystemPrompt.build(
                                        CodingAgentFactory.resolveSandboxWorkingDir(), null));
                        b.maxIters(50);
                        // opencode 风格规划：持久化待办列表在
                        // 每轮推理重新注入（todo_write + TaskReminderMiddleware）。这是
                        // 无需 HITL 确认门的自主规划原语；不同于 Harness
                        // Plan Mode，后者 plan_exit 的 ASK 在此应用中没有确认处理器。
                        b.enableTaskList();
                        // 防止长时间 issue→PR 会话超出上下文窗口。
                        configureCompaction(b);
                        configureSandbox(b);
                        registerOpenSweHooks(b, store);
                    });
            this.configureAgent(
                    "reviewer",
                    b -> {
                        if (this.model != null) {
                            b.model(this.model);
                        } else {
                            b.model(CodingAgentFactory.buildModel());
                        }
                        if (reviewerToolkit != null) {
                            b.toolkit(reviewerToolkit);
                        }
                        b.sysPrompt(
                                io.agentscope.harness.coding.prompt.ReviewerSystemPrompt
                                        .buildTemplate(
                                                CodingAgentFactory.resolveSandboxWorkingDir()));
                        b.maxIters(30);
                        b.disableSubagents();
                        // 大型 PR diff 可能撑爆上下文；长审查也需要压缩。
                        configureCompaction(b);
                        configureSandbox(b);
                        registerOpenSweHooks(b, store);
                    });
            return this;
        }

        /**
         * 为长会话配置对话压缩，对齐 opencode 自动溢出处理。此前仅接入未使用的 {@link CodingAgentFactory}，此处将其接到生产 {@link #withDualCodingAgents} 路径。
         */
        private static void configureCompaction(HarnessAgent.Builder b) {
            b.compaction(
                    CompactionConfig.builder()
                            .triggerMessages(40)
                            .keepMessages(15)
                            .flushBeforeCompact(true)
                            .build());
        }

        /**
         * {@code SANDBOX_TYPE=docker} 时附加 Docker 沙箱文件系统。其他值（例如 {@code none}）使用默认本地主机文件系统，使单元测试和离线运行仍可工作。
         */
        private static void configureSandbox(HarnessAgent.Builder b) {
            String type = CodingAgentFactory.resolveSandboxType();
            if ("docker".equalsIgnoreCase(type)) {
                DockerFilesystemSpec spec = new DockerFilesystemSpec();
                spec.image(CodingAgentFactory.resolveSandboxImage());
                spec.workspaceRoot(CodingAgentFactory.resolveSandboxWorkingDir());
                spec.isolationScope(IsolationScope.SESSION);
                b.filesystem(spec);
            }
        }

        private static void registerOpenSweHooks(HarnessAgent.Builder b, BaseStore store) {
            if (store != null) {
                b.middleware(new MessageQueueMiddleware(store));
            }
            b.middleware(new ThreadBudgetMiddleware(resolveThreadBudget()));
            b.middleware(new ModelCallLimitMiddleware(resolveGlobalModelLimit()));
        }

        private static int resolveThreadBudget() {
            String env = System.getenv("THREAD_MODEL_CALL_BUDGET");
            if (env != null && !env.isBlank()) {
                try {
                    return Integer.parseInt(env.trim());
                } catch (NumberFormatException ignored) {
                    // 继续后续处理
                }
            }
            return 200;
        }

        private static int resolveGlobalModelLimit() {
            String env = System.getenv("GLOBAL_MODEL_CALL_LIMIT");
            if (env != null && !env.isBlank()) {
                try {
                    return Integer.parseInt(env.trim());
                } catch (NumberFormatException ignored) {
                    // 继续后续处理
                }
            }
            return 5000;
        }

        /**
         * 组装全部智能体和通道，连接内部网关，返回完整初始化的 {@link CodingBootstrap}。
         *
         * <h4>装配顺序</h4>
         * <ol>
         * <li>从文件配置和编程注册解析智能体 ID</li>
         * <li>为主智能体提取子智能体条目，构建共享 {@link DefaultAgentManager}、{@link SessionAgentManager} 和 {@link HarnessGateway}</li>
         * <li>根据共享会话管理器创建 {@link SessionsTool}</li>
         * <li>向每个智能体注入共享 {@link SessionsTool} 后构建</li>
         * <li>在网关注册所有智能体以支持多智能体路由</li>
         * </ol>
         */
        public CodingBootstrap build() throws IOException {
            Path resolvedConfig =
                    skipConfigFile ? null : (configPath != null ? configPath : DEFAULT_CONFIG_PATH);
            AgentscopeConfig fileConfig =
                    skipConfigFile
                            ? new AgentscopeConfig()
                            : loadConfigFile(
                                    resolvedConfig != null ? resolvedConfig : DEFAULT_CONFIG_PATH);

            Map<String, AgentConfigEntry> fileAgents =
                    fileConfig.getAgents() != null ? fileConfig.getAgents() : Map.of();

            Set<String> ids = new LinkedHashSet<>();
            ids.addAll(fileAgents.keySet());
            ids.addAll(prebuilt.keySet());
            ids.addAll(configurators.keySet());

            if (ids.isEmpty()) {
                throw new IllegalStateException(
                        "No agents defined: add entries to"
                                + " ~/.agentscope/codingagent/agentscope.json or use"
                                + " AgentBootstrap.builder().agent(id, ...) / configureAgent(...)");
            }

            String main =
                    mainAgentId != null
                            ? mainAgentId
                            : (fileConfig.getMain() != null && !fileConfig.getMain().isBlank()
                                    ? fileConfig.getMain().trim()
                                    : (fileAgents.containsKey(DEFAULT_MAIN_ID)
                                            ? DEFAULT_MAIN_ID
                                            : ids.iterator().next()));

            if (!ids.contains(main)) {
                throw new IllegalStateException(
                        "main agent id '" + main + "' is not among configured agents: " + ids);
            }

            // ---- 阶段 1：构建共享会话基础设施 ----

            // 为主智能体配置临时构建器，以提取子智能体条目。
            Path mainWorkspace = resolveAgentWorkspace(cwd, fileAgents.get(main));
            // 在扫描子智能体条目前铺设内置技能和模板，确保
            // <workspace>/subagents/ 下的 general 子智能体声明可被发现。
            seedWorkspaceTemplates(mainWorkspace);
            HarnessAgent.Builder mainEntryBuilder = HarnessAgent.builder();
            applyFileEntry(cwd, main, fileAgents.get(main), mainEntryBuilder);
            if (model != null) {
                mainEntryBuilder.model(model);
            }
            Consumer<HarnessAgent.Builder> mainCustomizer = configurators.get(main);
            if (mainCustomizer != null) {
                mainCustomizer.accept(mainEntryBuilder);
            }

            List<SubagentEntry> entries = mainEntryBuilder.buildSubagentEntries(mainWorkspace);

            WorkspaceManager wsManager =
                    globalFileSystem != null
                            ? new WorkspaceManager(mainWorkspace, globalFileSystem)
                            : new WorkspaceManager(mainWorkspace);
            DefaultAgentManager dam = new DefaultAgentManager(entries, wsManager);

            Path storeFile = mainWorkspace.resolve("sessions.json");
            SessionStore sessionStore = new SessionStore(storeFile);
            sessionStore.load();

            SessionAgentManager sam =
                    new SessionAgentManager(
                            dam,
                            AgentManagerConfig.defaults(),
                            new SubagentRunRegistry(),
                            sessionStore);

            ChannelManager channelMgr = new ChannelManager();
            HarnessGateway gateway = HarnessGateway.create(sam, channelMgr);
            TaskRepository taskRepo = new WorkspaceTaskRepository(wsManager, main);
            SessionsTool sessionsTool = new SessionsTool(sam, taskRepo, null, 0);

            // ---- 阶段 2：注入 SessionsTool 并构建智能体 ----

            Map<String, HarnessAgent> built = new LinkedHashMap<>();

            for (String id : ids) {
                if (prebuilt.containsKey(id)) {
                    built.put(id, prebuilt.get(id));
                    continue;
                }

                AgentConfigEntry entry = fileAgents.get(id);
                if (entry == null && !configurators.containsKey(id)) {
                    continue;
                }

                HarnessAgent.Builder b = HarnessAgent.builder();
                applyFileEntry(cwd, id, entry, b);
                // 将编码技能铺设到此智能体工作区（幂等，仅在不存在时复制）。
                seedWorkspaceTemplates(resolveAgentWorkspace(cwd, entry));

                if (model != null) {
                    b.model(model);
                }
                if (globalFileSystem != null) {
                    b.abstractFilesystem(globalFileSystem);
                }

                b.externalSubagentTool(sessionsTool);

                Consumer<HarnessAgent.Builder> c = configurators.get(id);
                if (c != null) {
                    c.accept(b);
                }

                built.put(id, b.build());
            }

            if (!built.containsKey(main)) {
                throw new IllegalStateException(
                        "main agent id '" + main + "' was not built. Built ids: " + built.keySet());
            }

            // ---- 阶段 3：将所有智能体连接到网关 ----

            for (Map.Entry<String, HarnessAgent> e : built.entrySet()) {
                gateway.registerAgent(e.getKey(), e.getValue());
            }
            gateway.bindMainAgent(built.get(main));

            List<Channel> resolvedChannels = resolveChannels(channels, fileConfig);

            log.info(
                    "AgentBootstrap: cwd={}, config={}, main={}, agents={}, channels={}",
                    cwd,
                    skipConfigFile ? "(skipConfigFile)" : resolvedConfig,
                    main,
                    built.keySet(),
                    resolvedChannels.stream().map(Channel::channelId).toList());

            return new CodingBootstrap(
                    cwd,
                    resolvedConfig != null ? resolvedConfig : DEFAULT_CONFIG_PATH,
                    main,
                    Map.copyOf(built),
                    globalFileSystem,
                    resolvedChannels,
                    gateway,
                    channelMgr);
        }

        private static Path resolveAgentWorkspace(Path cwd, AgentConfigEntry entry) {
            if (entry != null && entry.getWorkspace() != null && !entry.getWorkspace().isBlank()) {
                return cwd.resolve(entry.getWorkspace()).normalize();
            }
            return DEFAULT_WORKSPACE_ROOT;
        }

        /**
         * 首次运行时铺设到工作区的内置模板：opencode 风格编码技能（从 {@code <workspace>/skills/} 自动加载）与 general 子智能体声明（从 {@code <workspace>/subagents/} 扫描）。路径相对 classpath 的 {@code workspace-templates/} 根目录。
         */
        private static final List<String> WORKSPACE_TEMPLATE_RESOURCES =
                List.of(
                        "skills/verify-changes/SKILL.md",
                        "skills/git-checkpoint/SKILL.md",
                        "skills/apply-patch/SKILL.md",
                        "skills/code-search/SKILL.md",
                        "subagents/general.md");

        /**
         * 将内置工作区模板复制到 {@code workspace}，跳过已存在文件，绝不覆盖用户编辑。采用尽力方式：记录失败，不中止启动。
         */
        private static void seedWorkspaceTemplates(Path workspace) {
            if (workspace == null) {
                return;
            }
            for (String rel : WORKSPACE_TEMPLATE_RESOURCES) {
                Path target = workspace.resolve(rel).normalize();
                if (Files.exists(target)) {
                    continue;
                }
                String resource = "/workspace-templates/" + rel;
                try (InputStream is = CodingBootstrap.class.getResourceAsStream(resource)) {
                    if (is == null) {
                        log.debug("Workspace template not found on classpath: {}", resource);
                        continue;
                    }
                    Path parent = target.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    Files.copy(is, target);
                    log.info("Seeded workspace template {} -> {}", rel, target);
                } catch (IOException e) {
                    log.warn(
                            "Failed to seed workspace template {} -> {}: {}",
                            resource,
                            target,
                            e.getMessage());
                }
            }
        }
    }
}
