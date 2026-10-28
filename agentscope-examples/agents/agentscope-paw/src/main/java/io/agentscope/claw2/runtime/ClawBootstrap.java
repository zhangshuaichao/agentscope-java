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
package io.agentscope.claw2.runtime;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.claw2.runtime.config.AgentConfigEntry;
import io.agentscope.claw2.runtime.config.AgentscopeConfig;
import io.agentscope.claw2.runtime.config.ChannelConfigEntry;
import io.agentscope.claw2.runtime.config.ChannelTypeRegistry;
import io.agentscope.claw2.runtime.config.SkillRepositorySupport;
import io.agentscope.claw2.runtime.gateway.HarnessGateway;
import io.agentscope.claw2.runtime.outbound.OutboundTool;
import io.agentscope.claw2.runtime.session.AgentManagerConfig;
import io.agentscope.claw2.runtime.session.SessionAgentManager;
import io.agentscope.claw2.runtime.session.SessionStore;
import io.agentscope.claw2.runtime.session.SubagentRunRegistry;
import io.agentscope.claw2.runtime.session.tool.SessionsTool;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.gateway.ChannelManager;
import io.agentscope.harness.agent.gateway.Gateway;
import io.agentscope.harness.agent.gateway.channel.Channel;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import io.agentscope.harness.agent.gateway.channel.ChannelFactory;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiChannel;
import io.agentscope.harness.agent.middleware.SubagentEntry;
import io.agentscope.harness.agent.subagent.DefaultAgentManager;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 用于组装、配置和运行 agentscope Harness 的统一 API 类。
 *
 * <h2>构建阶段 — {@link #builder()}</h2>
 *
 * 加载 {@code ${clawHome}/agentscope.json}，合并文件中的智能体定义与程序化 {@link Builder} 配置，生成接入 {@link SessionsTool} 及共享 {@link SessionAgentManager} + {@link HarnessGateway} 的 {@link HarnessAgent} 实例。
 *
 * <p>{@code clawHome} 是所有磁盘状态的根目录（内置 {@code agentscope.json}、自定义智能体目录、各智能体工作区和会话存储）。默认值为 {@code ~/.agentscope/claw}，使不同 Harness 应用（claw、builder、dataagent、codingagent）可使用各自独立的工作区目录树。
 *
 * <h2>运行阶段 — 两个入口</h2>
 * <ol>
 *   <li>{@link #chatUiChannel()}：获取 {@link ChatUiChannel}，用于直接程序化交互（嵌入式 UI、CLI、测试）；立即可用，无需调用 {@link #start()}。
 *   <li>{@link #start()}：初始化并启动所有预注册的通道适配器。
 * </ol>
 */
public final class ClawBootstrap implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ClawBootstrap.class);
    private static final String DEFAULT_MAIN_ID = "default";

    // -----------------------------------------------------------------
    // 实例状态，由 Builder.build() 填充
    // -----------------------------------------------------------------

    private final Path cwd;
    private final Path configPath;
    private final String mainAgentId;
    private final Map<String, HarnessAgent> agents;
    private final AgentscopeConfig loadedConfig;
    private final List<Channel> registeredChannels;
    private final HarnessGateway gateway;
    private final ChannelManager channelManager;

    private ClawBootstrap(
            Path cwd,
            Path configPath,
            String mainAgentId,
            Map<String, HarnessAgent> agents,
            AgentscopeConfig loadedConfig,
            List<Channel> registeredChannels,
            HarnessGateway gateway,
            ChannelManager channelManager) {
        this.cwd = Objects.requireNonNull(cwd, "cwd");
        this.configPath = Objects.requireNonNull(configPath, "configPath");
        this.mainAgentId = Objects.requireNonNull(mainAgentId, "mainAgentId");
        this.agents = Objects.requireNonNull(agents, "agents");
        this.loadedConfig = loadedConfig != null ? loadedConfig : new AgentscopeConfig();
        this.registeredChannels =
                registeredChannels != null ? List.copyOf(registeredChannels) : List.of();
        this.gateway = gateway;
        this.channelManager = channelManager;
    }

    // -----------------------------------------------------------------
    // 静态工厂／工具方法
    // -----------------------------------------------------------------

    public static Builder builder() {
        return new Builder();
    }

    public static AgentscopeConfig loadConfig(Path clawHome) throws IOException {
        return loadConfigFile(defaultConfigPath(clawHome));
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

    /**
     * {@code clawHome} 下 {@code agentscope.json} 的磁盘路径。
     */
    public static Path defaultConfigPath(Path clawHome) {
        return clawHome.resolve("agentscope.json");
    }

    /**
     * 返回 {@code clawHome} 下 {@code agentId} 的默认工作区目录。
     */
    public static Path defaultAgentWorkspace(Path clawHome, String agentId) {
        return clawHome.resolve("agents").resolve(agentId).resolve("workspace").normalize();
    }

    /**
     * 返回 {@code clawHome} 下各智能体的会话存储文件。
     */
    public static Path defaultSessionsStore(Path clawHome, String agentId) {
        return clawHome.resolve("agents").resolve(agentId).resolve("sessions.json").normalize();
    }

    // -----------------------------------------------------------------
    // 公共运行时 API
    // -----------------------------------------------------------------

    public ChatUiChannel chatUiChannel() {
        return ChatUiChannel.create(resolveGateway());
    }

    public ChatUiChannel chatUiChannel(ChannelConfig config) {
        return ChatUiChannel.create(resolveGateway(), Objects.requireNonNull(config, "config"));
    }

    public ClawBootstrap start() {
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

    /**
     * 停止受管通道，并关闭此引导器创建的所有智能体。
     *
     * <p>关闭智能体会释放与 claw 主目录关联的后台任务仓库和工作区索引。
     */
    @Override
    public void close() {
        stop();
        RuntimeException failure = null;
        for (HarnessAgent agent : new LinkedHashSet<>(agents.values())) {
            try {
                agent.close();
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    // -----------------------------------------------------------------
    // 公共／包私有访问器
    // -----------------------------------------------------------------

    /**
     * claw 主目录，用作相对配置路径的基准，以及 {@code agentscope.json}、各智能体工作区和会话存储的默认位置。
     */
    public Path cwd() {
        return cwd;
    }

    /**
     * {@link #cwd()} 的别名，明确其表示 claw 主目录根路径。
     */
    public Path clawHome() {
        return cwd;
    }

    /**
     * 从 {@code agentscope.json} 加载的 {@link AgentscopeConfig}；文件不存在时为空配置。
     */
    public AgentscopeConfig loadedConfig() {
        return loadedConfig;
    }

    /**
     * 所有已注册智能体实例，以 agentId 为键。
     */
    public Map<String, HarnessAgent> agents() {
        return agents;
    }

    String mainAgentId() {
        return mainAgentId;
    }

    /**
     * 配置的主智能体实例。
     */
    public HarnessAgent mainAgent() {
        HarnessAgent a = agents.get(mainAgentId);
        if (a == null) {
            throw new IllegalStateException("Main agent not registered: " + mainAgentId);
        }
        return a;
    }

    public Path configPath() {
        return configPath;
    }

    /**
     * 通过 {@link ChannelTypeRegistry} 从 {@code agentscope.json} 解析的通道，以及通过 {@link Builder#channel(Channel...)} 程序化添加的通道。它们尚未启动；调用方可传入 {@link #start(Channel...)}，或先注册到 {@link #channelManager()} 再调用 {@link #start()}。
     */
    public List<Channel> registeredChannels() {
        return registeredChannels;
    }

    public HarnessGateway gateway() {
        return gateway;
    }

    /**
     * 负责通道生命周期和出站投递的通道管理器。
     */
    public ChannelManager channelManager() {
        return channelManager;
    }

    /**
     * 使用与构建阶段相同的逻辑，解析给定智能体 ID 的工作区 {@link Path}。
     *
     * <p>若加载的配置中不存在 {@code agentId}（或没有显式工作区），返回默认的逐智能体工作区路径 {@code ${clawHome}/agents/{agentId}/workspace}。
     */
    public Path resolveWorkspace(String agentId) {
        AgentscopeConfig cfg = loadedConfig;
        AgentConfigEntry entry = cfg.getAgents() != null ? cfg.getAgents().get(agentId) : null;
        if (entry != null && entry.getWorkspace() != null && !entry.getWorkspace().isBlank()) {
            return cwd.resolve(entry.getWorkspace()).normalize();
        }
        return defaultAgentWorkspace(cwd, agentId);
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
            // 按 `type` 查找工厂；找不到类型字段时回退到 channelId，以兼容
            // `chatui` 等单实例内置通道。
            String typeId =
                    (ce.getType() != null && !ce.getType().isBlank())
                            ? ce.getType().trim()
                            : channelId;
            Optional<ChannelFactory> factory = ChannelTypeRegistry.get(typeId);
            if (factory.isEmpty()) {
                log.debug(
                        "Channel '{}' (type='{}') configured in file but no factory registered in"
                                + " ChannelTypeRegistry and no programmatic channel supplied;"
                                + " skipping.",
                        channelId,
                        typeId);
                continue;
            }
            try {
                Map<String, Object> props =
                        ce.getProperties() != null ? Map.copyOf(ce.getProperties()) : Map.of();
                Channel created =
                        factory.get().create(channelId, ce.toChannelConfig(channelId), props);
                if (created != null) {
                    merged.put(channelId, created);
                }
            } catch (Exception e) {
                log.error(
                        "Failed to instantiate channel '{}' (type='{}'): {}",
                        channelId,
                        typeId,
                        e.getMessage(),
                        e);
            }
        }
        return List.copyOf(merged.values());
    }

    /**
     * 将 {@code agentscope.json} 中可选的 {@code session.maintenance} 配置块转换为运行时 {@link AgentManagerConfig}；未配置时回退到 {@link AgentManagerConfig#defaults()}。
     */
    private static AgentManagerConfig resolveAgentManagerConfig(AgentscopeConfig fileConfig) {
        var sessionCfg = fileConfig != null ? fileConfig.getSession() : null;
        if (sessionCfg == null || sessionCfg.getMaintenance() == null) {
            return AgentManagerConfig.defaults();
        }
        var m = sessionCfg.getMaintenance();
        String mode = m.getMode();
        if (mode == null || mode.isBlank() || "off".equalsIgnoreCase(mode)) {
            return AgentManagerConfig.defaults();
        }
        long pruneAfterMs = m.pruneAfterMs();
        int maxEntries = m.getMaxEntries() != null ? m.getMaxEntries() : 0;
        io.agentscope.claw2.runtime.session.SessionMaintenanceConfig sm =
                io.agentscope.claw2.runtime.session.SessionMaintenanceConfig.enabled(
                        pruneAfterMs, maxEntries);
        return new AgentManagerConfig(
                AgentManagerConfig.DEFAULT_MAX_SUBAGENT_CONCURRENT,
                AgentManagerConfig.DEFAULT_MAX_NESTED_CONCURRENT,
                true,
                AgentManagerConfig.DEFAULT_MAX_PENDING_ANNOUNCE,
                io.agentscope.claw2.runtime.session.SessionResetPolicy.never(),
                sm);
    }

    static void applyFileEntry(
            Path clawHome, String agentId, AgentConfigEntry e, HarnessAgent.Builder b) {
        String name =
                (e != null && e.getName() != null && !e.getName().isBlank())
                        ? e.getName()
                        : agentId;
        b.name(name);
        // 稳定目录 ID 让原生会话身份在进程重启后保持一致。
        b.agentId(agentId);

        if (e != null) {
            if (e.getDescription() != null) {
                b.description(e.getDescription());
            }
            if (e.getSysPrompt() != null) {
                b.sysPrompt(e.getSysPrompt());
            }
            Path workspace =
                    e.getWorkspace() != null && !e.getWorkspace().isBlank()
                            ? clawHome.resolve(e.getWorkspace()).normalize()
                            : defaultAgentWorkspace(clawHome, agentId);
            b.workspace(workspace);

            if (e.getMaxIters() != null) {
                b.maxIters(e.getMaxIters());
            }
            if (e.getEnvironmentMemory() != null) {
                b.environmentMemory(e.getEnvironmentMemory());
            }
            if (e.getModel() != null && !e.getModel().isBlank()) {
                b.model(e.getModel());
            }
            for (var entry : e.getEffectiveSkillRepositories()) {
                var repo = SkillRepositorySupport.create(clawHome, entry);
                if (repo != null) {
                    b.skillRepository(repo);
                }
            }
            // 若设置 identity.name，则覆盖显示名称。
            if (e.getIdentity() != null && e.getIdentity().getName() != null) {
                b.name(e.getIdentity().getName());
            }
        } else {
            b.workspace(defaultAgentWorkspace(clawHome, agentId));
        }
    }

    // -----------------------------------------------------------------
    // 构建器
    // -----------------------------------------------------------------

    public static final class Builder {

        private Path cwd =
                Paths.get(System.getProperty("user.home"), ".agentscope", "claw").toAbsolutePath();
        private Path configPath;
        private boolean skipConfigFile;
        private Model model;
        private String mainAgentId;
        private final Map<String, HarnessAgent> prebuilt = new LinkedHashMap<>();
        private final Map<String, Consumer<HarnessAgent.Builder>> configurators =
                new LinkedHashMap<>();
        private final List<Consumer<HarnessAgent.Builder>> globalConfigurators =
                new java.util.ArrayList<>();
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

        /**
         * 注册应用于<em>每个</em>智能体构建器的定制器，叠加在各智能体专属定制器之上，适合注入钩子等横切功能。
         */
        public Builder configureAllAgents(Consumer<HarnessAgent.Builder> customizer) {
            Objects.requireNonNull(customizer, "customizer");
            this.globalConfigurators.add(customizer);
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
         * 组装全部智能体与通道，连接内部网关，返回完整初始化的 {@link ClawBootstrap}。
         *
         * <h4>装配顺序</h4>
         * <ol>
         *   <li>从文件配置与程序化注册解析智能体 ID</li>
         *   <li>对主智能体提取子智能体条目，构建共享 {@link DefaultAgentManager} + {@link SessionAgentManager} + {@link HarnessGateway}</li>
         *   <li>从共享会话管理器创建 {@link SessionsTool}</li>
         *   <li>构建各智能体并注入共享 {@link SessionsTool}</li>
         *   <li>向网关注册全部智能体以支持多智能体路由</li>
         * </ol>
         */
        public ClawBootstrap build() throws IOException {
            Path resolvedConfig =
                    skipConfigFile
                            ? null
                            : (configPath != null ? configPath : defaultConfigPath(cwd));
            AgentscopeConfig fileConfig =
                    skipConfigFile
                            ? new AgentscopeConfig()
                            : loadConfigFile(
                                    resolvedConfig != null
                                            ? resolvedConfig
                                            : defaultConfigPath(cwd));

            Map<String, AgentConfigEntry> fileAgents =
                    fileConfig.getAgents() != null ? fileConfig.getAgents() : Map.of();

            Set<String> ids = new LinkedHashSet<>();
            ids.addAll(fileAgents.keySet());
            ids.addAll(prebuilt.keySet());
            ids.addAll(configurators.keySet());

            if (ids.isEmpty()) {
                throw new IllegalStateException(
                        "No agents defined: add entries to .agentscope/agentscope.json or use"
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

            // 配置主智能体的临时构建器，提取子智能体条目。
            Path mainWorkspace = resolveAgentWorkspace(cwd, main, fileAgents.get(main));
            HarnessAgent.Builder mainEntryBuilder = HarnessAgent.builder();
            applyFileEntry(cwd, main, fileAgents.get(main), mainEntryBuilder);
            if (model != null) {
                mainEntryBuilder.model(model);
            }
            Consumer<HarnessAgent.Builder> mainCustomizer = configurators.get(main);
            if (mainCustomizer != null) {
                mainCustomizer.accept(mainEntryBuilder);
            }
            for (Consumer<HarnessAgent.Builder> gc : globalConfigurators) {
                gc.accept(mainEntryBuilder);
            }

            List<SubagentEntry> entries = mainEntryBuilder.buildSubagentEntries(mainWorkspace);

            WorkspaceManager wsManager = new WorkspaceManager(mainWorkspace);
            DefaultAgentManager dam = new DefaultAgentManager(entries, wsManager);

            Path storeFile = defaultSessionsStore(cwd, main);
            SessionStore sessionStore = new SessionStore(storeFile);
            sessionStore.load();

            AgentManagerConfig amCfg = resolveAgentManagerConfig(fileConfig);
            SessionAgentManager sam =
                    new SessionAgentManager(dam, amCfg, new SubagentRunRegistry(), sessionStore);

            ChannelManager channelMgr = new ChannelManager();
            HarnessGateway gateway = HarnessGateway.create(sam, channelMgr);
            OutboundTool outboundTool = new OutboundTool(channelMgr);

            // ---- 阶段 2：构建智能体并注入 SessionsTool ----

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

                if (model != null) {
                    b.model(model);
                }
                java.util.concurrent.atomic.AtomicReference<HarnessAgent> owner =
                        new java.util.concurrent.atomic.AtomicReference<>();
                b.externalSubagentTool(
                        new SessionsTool(sam, () -> owner.get().getTaskRepository()));

                // 预先向智能体工具集填入出站发送工具，使其可以
                // 主动向任意已注册即时通信通道推送消息。此步骤先于
                // 定制器执行，因此调用方仍可按需显式替换工具集。
                Toolkit agentToolkit = new Toolkit();
                agentToolkit.registerTool(outboundTool);
                b.toolkit(agentToolkit);

                Consumer<HarnessAgent.Builder> c = configurators.get(id);
                if (c != null) {
                    c.accept(b);
                }
                for (Consumer<HarnessAgent.Builder> gc : globalConfigurators) {
                    gc.accept(b);
                }

                HarnessAgent builtAgent = b.build();
                owner.set(builtAgent);
                built.put(id, builtAgent);
            }

            if (!built.containsKey(main)) {
                throw new IllegalStateException(
                        "main agent id '" + main + "' was not built. Built ids: " + built.keySet());
            }

            // ---- 阶段 3：将全部智能体接入网关 ----

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

            return new ClawBootstrap(
                    cwd,
                    resolvedConfig != null ? resolvedConfig : defaultConfigPath(cwd),
                    main,
                    Map.copyOf(built),
                    fileConfig,
                    resolvedChannels,
                    gateway,
                    channelMgr);
        }

        private static Path resolveAgentWorkspace(
                Path clawHome, String agentId, AgentConfigEntry entry) {
            if (entry != null && entry.getWorkspace() != null && !entry.getWorkspace().isBlank()) {
                return clawHome.resolve(entry.getWorkspace()).normalize();
            }
            return defaultAgentWorkspace(clawHome, agentId);
        }
    }
}
