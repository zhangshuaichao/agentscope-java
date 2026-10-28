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
package io.agentscope.claw2.web.config;

import io.agentscope.claw2.runtime.ClawBootstrap;
import io.agentscope.claw2.runtime.config.ChannelConfigEntry;
import io.agentscope.claw2.web.scaffold.WorkspaceScaffolder;
import io.agentscope.claw2.web.toolbus.ToolEventBus;
import io.agentscope.claw2.web.toolbus.ToolNotificationMiddleware;
import io.agentscope.core.model.Model;
import io.agentscope.core.session.SessionLogStore;
import io.agentscope.extensions.controlplane.adapter.AgentScopeAdapter;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import io.agentscope.harness.agent.gateway.channel.DmScope;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiChannel;
import io.agentscope.harness.agent.session.WorkspaceSessionLogStore;
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
 * 本地单用户助手 agentscope-claw 的 Spring Boot 配置。
 *
 * <p>组装以 {@code ${claw.home}} 为根目录的 {@link ClawBootstrap}（默认 {@code ~/.agentscope/claw}）。{@code agentscope.json} 定义内置智能体；不存在时自动生成最小默认智能体配置。
 *
 * <h2>模型装配优先级</h2>
 *
 * <ol>
 *   <li>若已有其他 {@code @Configuration} 提供的 {@link Model} Spring Bean，则直接使用。
 *   <li>否则，配置 {@code claw.dashscope.api-key} 时自动创建 {@link DashScopeChatModel}。
 *   <li>两者都不可用时，当前实现会在启动阶段抛出配置错误，而非等到聊天调用时失败。
 * </ol>
 */
@Configuration
public class BuilderConfig {

    private static final Logger log = LoggerFactory.getLogger(BuilderConfig.class);

    @Value("${claw.home:#{systemProperties['user.home']}/.agentscope/claw}")
    private String clawHome;

    @Value("${claw.dashscope.api-key:}")
    private String dashscopeApiKey;

    @Value("${claw.dashscope.model-name:qwen-max}")
    private String dashscopeModelName;

    @Value("${claw.dashscope.stream:true}")
    private boolean dashscopeStream;

    @Value("${claw.agent.name:claw}")
    private String agentName;

    @Value(
            "${claw.agent.sys-prompt:You are a helpful local assistant. Answer accurately and"
                    + " concisely.}")
    private String agentSysPrompt;

    /** 可选共享原生会话日志根目录；为空时使用各智能体的工作区后端。 */
    @Value("${claw.session-log.root:}")
    private String sessionLogRoot;

    // -----------------------------------------------------------------
    // 模型 bean：只有配置了 API 密钥，且上下文中
    // 不存在其他 Model bean 时才创建。
    // -----------------------------------------------------------------

    @Bean
    @ConditionalOnMissingBean(Model.class)
    @ConditionalOnExpression("'${claw.dashscope.api-key:}' != ''")
    public Model dashscopeModel() {
        log.info("Building DashScopeChatModel: model={}", dashscopeModelName);
        return DashScopeChatModel.builder()
                .apiKey(dashscopeApiKey)
                .modelName(dashscopeModelName)
                .stream(dashscopeStream)
                .build();
    }

    @Bean
    @ConditionalOnExpression("'${claw.session-log.root:}' != ''")
    public SessionLogStore pawSessionLogStore() {
        return new WorkspaceSessionLogStore(new LocalFilesystem(resolvePath(sessionLogRoot)));
    }

    // -----------------------------------------------------------------
    // 核心启动器：以方法参数注入模型，避免使用字段级
    // @Autowired 所导致的循环依赖。
    // -----------------------------------------------------------------

    @Bean
    public ClawBootstrap builderBootstrap(
            Optional<Model> modelOpt,
            ToolEventBus toolEventBus,
            Optional<AgentScopeAdapter> controlPlaneAdapter,
            Optional<SessionLogStore> sessionLogStore)
            throws IOException {
        Path home = resolveClawHome();
        ensureAgentscopeConfig(home);

        ClawBootstrap.Builder builder = ClawBootstrap.builder().cwd(home);

        if (modelOpt.isPresent()) {
            builder.model(modelOpt.get());
        } else {
            // 启动时尽早给出可操作的错误信息；若没有模型，每次聊天
            // 都会在 ReActAgent 深处的 Model.getModelName() 调用出现空指针，
            // 难以诊断，因此在此报告配置问题。
            throw new IllegalStateException(
                    "agentscope-claw cannot start without a model.\n"
                            + "  Configure one of the following before launching:\n"
                            + "    * set the DASHSCOPE_API_KEY environment variable, or\n"
                            + "    * set claw.dashscope.api-key in application.yml, or\n"
                            + "    * register your own io.agentscope.core.model.Model"
                            + " Spring @Bean.\n"
                            + "  See claw.dashscope.model-name (default: qwen-max) to override the"
                            + " model id.");
        }

        builder.configureAllAgents(b -> b.middleware(new ToolNotificationMiddleware(toolEventBus)));

        sessionLogStore.ifPresent(
                store -> builder.configureAllAgents(b -> b.sessionLogStore(store)));

        // ReActAgent 的中间件列表在构建时确定，因此必须在这里接入控制平面，
        // 使构建出的智能体包含观测中间件，
        // 而不是等桥接器附加时再接入，否则没有可观测会话。
        controlPlaneAdapter.ifPresent(
                adapter -> builder.configureAllAgents(b -> b.middleware(adapter.middleware())));

        ClawBootstrap bootstrap = builder.build();

        // 构建 chatui 渠道：存在文件配置时采用其 bindings 和 dmScope，否则
        // 使用单一共享会话（DmScope.MAIN），因为这是本地单用户应用。
        ChannelConfigEntry ce =
                bootstrap.loadedConfig().getChannels() != null
                        ? bootstrap.loadedConfig().getChannels().get(ChatUiChannel.CHANNEL_ID)
                        : null;
        ChannelConfig chatuiCfg =
                ce != null
                        ? ce.toChannelConfig(ChatUiChannel.CHANNEL_ID)
                        : ChannelConfig.builder(ChatUiChannel.CHANNEL_ID)
                                .dmScope(DmScope.MAIN)
                                .build();
        ChatUiChannel webChannel = ChatUiChannel.create(chatuiCfg);

        // 将文件解析出的渠道（dingtalk、wecom 等）预注册到渠道管理器，
        // 使 bootstrap.start(webChannel) 与始终启用的 chatui 渠道一起启动它们。
        for (io.agentscope.harness.agent.gateway.channel.Channel ch :
                bootstrap.registeredChannels()) {
            if (ch != null && !ChatUiChannel.CHANNEL_ID.equals(ch.channelId())) {
                bootstrap.channelManager().register(ch);
            }
        }
        bootstrap.start(webChannel);

        log.info(
                "ClawBootstrap initialized: home={}, chatui dmScope={}, bindings={}, channels={}",
                home,
                chatuiCfg.dmScope(),
                chatuiCfg.bindings().size(),
                bootstrap.channelManager().channelIds());
        return bootstrap;
    }

    @Bean
    public ChatUiChannel chatUiChannel(ClawBootstrap bootstrap) {
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

    private Path resolveClawHome() {
        String raw = clawHome != null && !clawHome.isBlank() ? clawHome : "~/.agentscope/claw";
        return resolvePath(raw);
    }

    private static Path resolvePath(String raw) {
        String expanded = raw;
        if (expanded.startsWith("~")) {
            expanded = System.getProperty("user.home") + expanded.substring(1);
        }
        return Paths.get(expanded).toAbsolutePath().normalize();
    }

    /** 不存在时自动生成最小 {@code agentscope.json} 并搭建默认智能体工作区。生成配置包含一个 {@code default} 内置智能体，显式 {@code workspace} 为 {@code "workspace"}，解析到 {@code ${clawHome}/workspace}（默认 {@code ~/.agentscope/claw/workspace}），使主 HarnessAgent 的文件树直接位于 {@code clawHome} 下，而非藏在 {@code agents/default/workspace} 中。 */
    private void ensureAgentscopeConfig(Path home) throws IOException {
        Path configFile = home.resolve("agentscope.json");
        Path defaultWorkspace = home.resolve("workspace").normalize();

        if (Files.exists(configFile)) {
            return;
        }
        Files.createDirectories(home);
        Files.createDirectories(defaultWorkspace);

        String agentsJson =
                """
                {
                  "main": "default",
                  "agents": {
                    "default": {
                      "name": "%s",
                      "workspace": "workspace",
                      "sysPrompt": "%s"
                    }
                  }
                }
                """
                        .formatted(
                                agentName.replace("\"", "\\\""),
                                agentSysPrompt.replace("\"", "\\\"").replace("\n", "\\n"));

        Files.writeString(configFile, agentsJson);
        log.info("Auto-generated agent config at {}", configFile);

        WorkspaceScaffolder.scaffold(defaultWorkspace, agentName, agentSysPrompt);
    }
}
