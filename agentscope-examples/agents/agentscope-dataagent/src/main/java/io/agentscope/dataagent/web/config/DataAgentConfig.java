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
package io.agentscope.dataagent.web.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.dataagent.runtime.DataAgentBootstrap;
import io.agentscope.dataagent.runtime.config.ChannelConfigEntry;
import io.agentscope.dataagent.runtime.marketplace.GitDataAgentMarketplace;
import io.agentscope.dataagent.runtime.marketplace.LocalApprovalMarketplace;
import io.agentscope.dataagent.runtime.marketplace.NacosDataAgentMarketplace;
import io.agentscope.dataagent.runtime.marketplace.UserMarketplaceRegistry.DataAgentMarketplaceFactoryRegistration;
import io.agentscope.dataagent.web.toolbus.ToolEventBus;
import io.agentscope.dataagent.web.toolbus.ToolNotificationMiddleware;
import io.agentscope.dataagent.web.workspace.UserSandboxRegistry;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import io.agentscope.harness.agent.gateway.channel.DmScope;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiChannel;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerFilesystemSpec;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxClientOptions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * agentscope-dataagent Web 模块的 Spring Boot 配置。
 * <p>组装 {@link DataAgentBootstrap} 并加载应用配置；工作目录由 {@code dataagent.workspace} 提供。
 * 旧路径约定为工作目录中的 {@code .agentscope/agentscope.json}，当前默认配置路径见下文。
 * 注册 {@link ChatUiChannel}，默认使用 {@link DmScope#PER_PEER}，使已认证用户拥有隔离会话及命名空间。
 * <h2>属性前缀</h2>
 * <p>配置键使用 {@code dataagent.*}。
 * <h2>文件系统拓扑</h2>
 * <p>每个 {@link
 * io.agentscope.harness.agent.HarnessAgent} 使用 {@link UserSandboxRegistry} 拥有的逐
 * {@code (userId, agentId)} 活跃 Docker 沙箱。{@link
 * io.agentscope.dataagent.runtime.gateway.HarnessGateway} 每次调用将其作为
 * {@link io.agentscope.harness.agent.sandbox.SandboxContext#getExternalSandbox() external sandbox}
 * 附加到 {@link io.agentscope.core.agent.RuntimeContext}，使 Harness 采用优先级 1 获取路径，
 * 与浏览器工作区控制器读写完全相同的容器。
 * <p>多副本部署必须按 {@code userId} 使用粘性负载均衡：注册表仅在内存中，
 * 否则两个 Pod 会为同一用户创建独立容器。
 * <h2>模型装配优先级</h2>
 * <ol>
 *   <li>若其他 {@code @Configuration} 已提供 {@link Model} Spring Bean，直接使用。</li>
 *   <li>否则，设置了 {@code dataagent.dashscope.api-key} 时自动创建 {@link DashScopeChatModel}。</li>
 *   <li>均未提供时，无模型启动；配置模型之前智能体调用会失败。</li>
 * </ol>
 * <p>模型采用 {@code @Bean} 方法的<em>参数注入</em>，而非字段 {@code @Autowired}，
 * 避免与本类定义的 {@code Model} Bean 循环依赖。
 * <h2>智能体配置</h2>
 * <p>{@code ~/.agentscope/dataagent/agentscope.json} 不存在时自动生成最小默认配置，无需手动设置即可启动。
 * <p>【学习提示】粘性路由只是把同一用户请求送往同一副本，不能替代持久化或容器恢复策略；
 * 会话状态、工作区文件、注册表缓存需要分别考虑重启后的恢复。
 */
@Configuration
public class DataAgentConfig {

    private static final Logger log = LoggerFactory.getLogger(DataAgentConfig.class);

    @Value("${dataagent.dashscope.api-key:}")
    private String dashscopeApiKey;

    @Value("${dataagent.dashscope.model-name:qwen-max}")
    private String dashscopeModelName;

    @Value("${dataagent.dashscope.stream:true}")
    private boolean dashscopeStream;

    @Value(
            "${dataagent.agent.sys-prompt:You are a Data Agent built with AgentScope."
                    + " You help users explore, analyse, visualise and report on data.}")
    private String agentSysPrompt;

    @Value("${dataagent.agent.name:data-agent}")
    private String agentName;

    @Value("${dataagent.workspace:}")
    private String workspaceDir;

    // -----------------------------------------------------------------
    // 仅当设置 API 密钥且上下文尚无其他 Model Bean 时
    // 才创建模型 Bean；属性为空白时跳过，
    // 使 Optional<Model> 注入点收到
    // Optional.empty()。
    // -----------------------------------------------------------------

    /** 当配置 {@code dataagent.dashscope.api-key} 且没有其他 {@link Model} Bean 时创建 {@link DashScopeChatModel} Bean。属性为空白时完全跳过，使 {@code Optional<Model>} 注入点得到 {@code Optional.empty()}，而非值为 null 的 Bean。 */
    @Bean
    @ConditionalOnMissingBean(Model.class)
    @ConditionalOnExpression("'${dataagent.dashscope.api-key:}' != ''")
    public Model dashscopeModel() {
        log.info("Building DashScopeChatModel: model={}", dashscopeModelName);
        return DashScopeChatModel.builder()
                .apiKey(dashscopeApiKey)
                .modelName(dashscopeModelName)
                .stream(dashscopeStream)
                .build();
    }

    // -----------------------------------------------------------------
    // Jackson ObjectMapper：供 MarketContributionService 及其他
    // 需要 JSON 序列化的 Web 层组件使用。
    // -----------------------------------------------------------------

    /** 为通过构造方法注入的组件提供共享 Jackson {@link ObjectMapper}，注册类路径上发现的 Jackson 模块（例如 Java Time）。 */
    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    public ObjectMapper objectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    // -----------------------------------------------------------------
    // 核心引导：以方法参数注入模型（不用字段 @Autowired），
    // 避免与上面的 dashscopeModel() 形成循环依赖。
    // -----------------------------------------------------------------

    /**
     * 组装 {@link DataAgentBootstrap}，从 {@code agentscope.json} 加载智能体配置，
     * 并启动 {@link ChatUiChannel}，提供逐用户隔离会话。
     * <p>引导器构建的每个智能体声明 {@link DockerFilesystemSpec}（逐用户隔离范围），
     * 与 {@link UserSandboxRegistry} 共用 {@link SandboxClient}。
     * 每轮实际容器由网关通过 {@link io.agentscope.harness.agent.sandbox.SandboxContext#getExternalSandbox()}
     * 提供，见 {@link io.agentscope.dataagent.runtime.gateway.HarnessGateway#setUserSandboxRegistry}。
     * @param modelOpt 使用的 {@link Model}，未配置时为空
     * @param toolEventBus 共享工具事件总线，供工具调用实时 SSE 流使用
     * @param sandboxClient 各 {@link DockerFilesystemSpec} 使用的客户端，与 {@link UserSandboxRegistry}
     *     使用同一实例，使规范默认值和注册表管理的沙箱共用 Docker 存储
     * @param userSandboxRegistry 引导后接到网关的注册表，为每轮调用提供正确的逐用户沙箱
     */
    @Bean
    public DataAgentBootstrap builderBootstrap(
            Optional<Model> modelOpt,
            ToolEventBus toolEventBus,
            SandboxClient<DockerSandboxClientOptions> sandboxClient,
            UserSandboxRegistry userSandboxRegistry,
            Optional<AgentStateStore> sessionOpt)
            throws IOException {
        Path cwd = resolveCwd();
        ensureAgentscopeConfig();

        DataAgentBootstrap.Builder builder = DataAgentBootstrap.builder().cwd(cwd);

        if (modelOpt.isPresent()) {
            builder.model(modelOpt.get());
        } else {
            log.warn(
                    "No model configured. Set dataagent.dashscope.api-key in application.yml or"
                            + " provide a Model bean. Agent calls will fail until a model is"
                            + " available.");
        }

        // 工作区基于沙箱后，AgentStateStore 后端选择与工作区文件系统
        // 相互独立。
        // 每个用户的沙箱通过内存中的
        // UserSandboxRegistry 和粘性负载均衡访问。生产部署仍应提供
        // 分布式 AgentStateStore Bean，使对话状态能够跨 Pod
        // 重启保留。
        AgentStateStore stateStore = sessionOpt.orElseGet(InMemoryAgentStateStore::new);
        if (sessionOpt.isEmpty()) {
            log.warn(
                    "No distributed AgentStateStore bean configured ({}); using"
                            + " InMemoryAgentStateStore. For multi-replica deployments, provide"
                            + " a DistributedStore or a distributed AgentStateStore bean"
                            + " (e.g. from agentscope-extensions-redis).",
                    AgentStateStore.class.getName());
        }

        builder.configureAllAgents(
                b -> {
                    b.middleware(new ToolNotificationMiddleware(toolEventBus));
                    b.stateStore(stateStore);
                    b.filesystem(
                            new DockerFilesystemSpec()
                                    .client(sandboxClient)
                                    .isolationScope(IsolationScope.USER));
                });

        DataAgentBootstrap bootstrap = builder.build();

        // 向网关传递每用户沙箱注册表，让每个 run(...) 轮次
        // 以 SandboxContext.externalSandbox 注入用户活跃容器（SandboxManager
        // 优先级 1 获取路径）。网关在 DataAgentBootstrap 内构造，该引导层
        // 位于 Web 层之下，不能直接依赖 UserSandboxRegistry。
        bootstrap.gateway().setUserSandboxRegistry(userSandboxRegistry);

        // 根据文件配置中的 bindings 与 dmScope（若有）构建 chatui 通道，
        // 使管理员在 agentscope.json 中修改的绑定生效；没有 chatui 配置项时
        // 回退到 PER_PEER。
        ChannelConfigEntry ce =
                bootstrap.loadedConfig().getChannels() != null
                        ? bootstrap.loadedConfig().getChannels().get(ChatUiChannel.CHANNEL_ID)
                        : null;
        ChannelConfig chatuiCfg =
                ce != null
                        ? ce.toChannelConfig(ChatUiChannel.CHANNEL_ID)
                        : ChannelConfig.builder(ChatUiChannel.CHANNEL_ID)
                                .dmScope(DmScope.PER_PEER)
                                .build();
        ChatUiChannel webChannel = ChatUiChannel.create(chatuiCfg);
        bootstrap.start(webChannel);

        log.info(
                "DataAgentBootstrap initialized: cwd={}, chatui dmScope={}, bindings={}",
                cwd,
                chatuiCfg.dmScope(),
                chatuiCfg.bindings().size());
        return bootstrap;
    }

    /** 以 {@code "local"} 类型注册 {@link LocalApprovalMarketplace} 工厂，使 {@code UserMarketplaceRegistry} 能装载由磁盘上已批准贡献支持的每用户市场。<p>工厂从 {@code ${dataagent.shared-root}/agents/data-agent/skills} 读取，即内置 {@code data-agent} 的逐智能体分区；每个用户的 {@code data-agent} 沙箱将同一目录投影为下层，使已批准技能可供全部租户使用。其他智能体的技能位于各自 {@code shared/agents/<agentId>/skills/} 分区，经各自覆盖层暴露；此本地市场不跨智能体列举。 */
    @Bean
    public DataAgentMarketplaceFactoryRegistration localMarketplaceFactory(
            DataAgentBootstrap bootstrap) {
        Path sharedSkills =
                bootstrap
                        .cwd()
                        .resolve("shared")
                        .resolve("agents")
                        .resolve("data-agent")
                        .resolve("skills");
        return new DataAgentMarketplaceFactoryRegistration(
                LocalApprovalMarketplace.TYPE,
                (userId, id, props, wsf) -> new LocalApprovalMarketplace(id, sharedSkills));
    }

    /** 以 {@code "git"} 类型注册 {@link GitDataAgentMarketplace} 工厂。每个用户市场在 {@code ${dataagent.workspace}/.cache/marketplaces/{userId}/{marketplaceId}} 下拥有独立克隆目标，避免不同用户配置同一上游时竞争共享副本。<p>属性：{@code remoteUrl} 必填，{@code branch} 可选。 */
    @Bean
    public DataAgentMarketplaceFactoryRegistration gitMarketplaceFactory(
            DataAgentBootstrap bootstrap) {
        Path cacheRoot = bootstrap.cwd().resolve(".cache").resolve("marketplaces");
        return new DataAgentMarketplaceFactoryRegistration(
                GitDataAgentMarketplace.TYPE,
                (userId, id, props, wsf) -> {
                    String remoteUrl = stringProp(props, "remoteUrl");
                    if (remoteUrl == null || remoteUrl.isBlank()) {
                        throw new IllegalArgumentException(
                                "git marketplace '" + id + "' requires property 'remoteUrl'");
                    }
                    String branch = stringProp(props, "branch");
                    Path clone = cacheRoot.resolve(userId).resolve(id);
                    return new GitDataAgentMarketplace(id, remoteUrl, branch, clone);
                });
    }

    /**
     * 以 {@code "nacos"} 类型注册 {@link NacosDataAgentMarketplace} 工厂。
     * <p>属性：{@code serverAddr} 必填；{@code namespaceId} 可选，默认为
     * {@code "public"}；还可配置 {@code username} / {@code password}、{@code accessKey} / {@code
     * secretKey}。
     */
    @Bean
    public DataAgentMarketplaceFactoryRegistration nacosMarketplaceFactory() {
        return new DataAgentMarketplaceFactoryRegistration(
                NacosDataAgentMarketplace.TYPE,
                (userId, id, props, wsf) -> {
                    String serverAddr = stringProp(props, "serverAddr");
                    if (serverAddr == null || serverAddr.isBlank()) {
                        throw new IllegalArgumentException(
                                "nacos marketplace '" + id + "' requires property 'serverAddr'");
                    }
                    return new NacosDataAgentMarketplace(
                            id,
                            serverAddr,
                            stringProp(props, "namespaceId"),
                            stringProp(props, "username"),
                            stringProp(props, "password"),
                            stringProp(props, "accessKey"),
                            stringProp(props, "secretKey"));
                });
    }

    private static String stringProp(java.util.Map<String, Object> props, String key) {
        if (props == null) return null;
        Object v = props.get(key);
        return v == null ? null : v.toString();
    }

    @Bean
    public io.agentscope.dataagent.web.identity.IdentityLinkStore identityLinkStore(
            DataAgentBootstrap bootstrap) {
        Path agentscopeDir = bootstrap.cwd().resolve(".agentscope");
        return new io.agentscope.dataagent.web.identity.IdentityLinkStore(agentscopeDir);
    }

    @Bean
    public ChatUiChannel chatUiChannel(DataAgentBootstrap bootstrap) {
        return (ChatUiChannel)
                bootstrap
                        .channelManager()
                        .getChannel(ChatUiChannel.CHANNEL_ID)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "ChatUiChannel not registered in ChannelManager"));
    }

    // -----------------------------------------------------------------
    // 内部辅助方法
    // -----------------------------------------------------------------

    private Path resolveCwd() {
        if (workspaceDir != null && !workspaceDir.isBlank()) {
            return Paths.get(workspaceDir).toAbsolutePath().normalize();
        }
        return Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }

    /**
     * {@code ~/.agentscope/dataagent/agentscope.json} 不存在时自动生成最小配置，
     * 使应用无需手动设置即可启动。生成一个 GLOBAL {@code data-agent}，预先连接
     * {@code chatui} 通道，工作区位置由引导器回退到 {@link DataAgentBootstrap#DEFAULT_WORKSPACE_ROOT}。
     * <p>工作区根是只读共享种子，包含磁盘随附的模板、默认 {@code
     * AGENTS.md} / {@code skills/} / {@code subagents/} / {@code knowledge/}。
     * {@link UserSandboxRegistry} 将其投影到新容器，用户可写文件则位于容器内部。
     */
    private void ensureAgentscopeConfig() throws IOException {
        Path configFile = DataAgentBootstrap.DEFAULT_CONFIG_PATH;
        Path workspaceRoot = DataAgentBootstrap.DEFAULT_WORKSPACE_ROOT;

        if (Files.exists(configFile)) {
            return;
        }

        Files.createDirectories(configFile.getParent());
        Files.createDirectories(workspaceRoot);

        String agentsJson =
                """
                {
                  "main": "data-agent",
                  "agents": {
                    "data-agent": {
                      "name": "Data Agent",
                      "description": "Tenant-isolated data-analysis assistant. Connects to internal SQL sources, drafts queries, validates results, and renders charts.",
                      "maxIters": 20
                    }
                  },
                  "channels": {
                    "chatui": {
                      "defaultAgentId": "data-agent",
                      "dmScope": "MAIN"
                    }
                  }
                }
                """;

        Files.writeString(configFile, agentsJson);
        log.info("Auto-generated DataAgent config at {}", configFile);

        io.agentscope.dataagent.web.scaffold.WorkspaceScaffolder.scaffold(
                workspaceRoot, "Data Agent", agentSysPrompt);
    }
}
