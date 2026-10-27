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
package io.agentscope.harness.agent.gateway;

import io.agentscope.core.agent.Agent;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.gateway.channel.Channel;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import io.agentscope.harness.agent.gateway.channel.ChannelRuntimeContextResolver;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiChannel;
import io.agentscope.harness.agent.subagent.DefaultAgentManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * 多智能体与渠道路由的启动引导。面向用户的主要入口，用于构建网关管理的智能体系统。
 *
 * <h2>最简用法：单智能体与 ChatUI</h2>
 *
 * <pre>{@code
 * HarnessAgent agent = HarnessAgent.builder()
 *     .model(model).name("assistant").sysPrompt("You are helpful.")
 *     .build();
 *
 * GatewayBootstrap gw = GatewayBootstrap.builder()
 *     .agent("main", agent)
 *     .build();
 *
 * ChatUiChannel chat = gw.chatUiChannel();
 * Msg reply = chat.send("hello").block();
 * }</pre>
 *
 * <h2>多智能体与绑定路由</h2>
 *
 * <pre>{@code
 * GatewayBootstrap gw = GatewayBootstrap.builder()
 *     .agent("sales", salesAgent)
 *     .agent("support", supportAgent)
 *     .mainAgent("sales")
 *     .build();
 *
 * ChannelConfig config = ChannelConfig.builder("chatui")
 *     .dmScope(DmScope.PER_PEER)
 *     .binding(ChannelBinding.forPeer("direct:vip-user-1", "support"))
 *     .build();
 *
 * ChatUiChannel chat = gw.chatUiChannel(config);
 * chat.send("vip-user-1", "help me").block();  // 路由到 support 智能体
 * chat.send("normal-user", "hi").block();      // 路由到 sales（默认智能体）
 * }</pre>
 *
 * <h2>外部渠道</h2>
 *
 * <pre>{@code
 * GatewayBootstrap gw = GatewayBootstrap.builder()
 *     .agent("main", agent)
 *     .channel(mySlackChannel)
 *     .build();
 *
 * gw.start();   // 初始化并启动全部渠道
 * gw.stop();    // 停止全部渠道
 * }</pre>
 */
public final class GatewayBootstrap {

    private final HarnessGateway gateway;
    private final ChannelManager channelManager;
    private final Map<String, HarnessAgent> agents;
    private final String mainAgentId;

    private GatewayBootstrap(
            HarnessGateway gateway,
            ChannelManager channelManager,
            Map<String, HarnessAgent> agents,
            String mainAgentId) {
        this.gateway = gateway;
        this.channelManager = channelManager;
        this.agents = Collections.unmodifiableMap(agents);
        this.mainAgentId = mainAgentId;
    }

    /**
     * 返回新构建器。
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 底层网关（高级用法）。
     */
    public HarnessGateway gateway() {
        return gateway;
    }

    /**
     * 渠道管理器（高级用法）。
     */
    public ChannelManager channelManager() {
        return channelManager;
    }

    /**
     * 按 ID 索引的已注册智能体。
     */
    public Map<String, HarnessAgent> agents() {
        return agents;
    }

    /**
     * 主智能体 ID。
     */
    public String mainAgentId() {
        return mainAgentId;
    }

    /**
     * 返回 {@link SubagentGatewayBridge}，将子智能体作为本网关中用户可寻址的对话线程公开。
     * 将它传给 {@link io.agentscope.harness.agent.middleware.SubagentsMiddleware#setGatewayBridge}，
     * 可启用 {@code agent_spawn} 的 {@code expose_to_user} 参数。
     */
    public SubagentGatewayBridge gatewayBridge() {
        return (agentId, sessionId, agent, replyTo) -> {
            String subagentId = gateway.exposeSubagent(agentId, sessionId, agent, replyTo);
            return new SubagentGatewayBridge.ExposeResult(subagentId);
        };
    }

    /**
     * 返回预先连接本网关、使用默认 DmScope.MAIN 配置的 {@link ChatUiChannel}。
     * 所有对话共享单个会话。
     */
    public ChatUiChannel chatUiChannel() {
        return ChatUiChannel.create(gateway);
    }

    /**
     * 返回预先连接本网关、使用自定义路由配置的 {@link ChatUiChannel}。
     * 可用于设置 DmScope、绑定或默认智能体覆盖。
     */
    public ChatUiChannel chatUiChannel(ChannelConfig config) {
        return ChatUiChannel.create(gateway, config);
    }

    /**
     * 初始化并启动全部预注册渠道（向各渠道注入网关）。
     * 使用 Slack、Telegram 等外部渠道时，在 build() 后调用。
     */
    public GatewayBootstrap start() {
        channelManager.initAll(gateway);
        channelManager.startAll();
        return this;
    }

    /**
     * 停止全部渠道并释放资源。
     */
    public void stop() {
        channelManager.stopAll();
    }

    // =========================================================================
    // 构建器
    // =========================================================================

    public static final class Builder {

        private final LinkedHashMap<String, HarnessAgent> agents = new LinkedHashMap<>();
        private String mainAgentId;
        private final List<Channel> channels = new ArrayList<>();
        private Consumer<HarnessAgent.Builder> agentCustomizer;
        private DistributedStore distributedStore;
        private ChannelRuntimeContextResolver runtimeContextResolver;

        private Builder() {}

        /**
         * 以指定 ID 注册预先构建的智能体。
         */
        public Builder agent(String id, HarnessAgent agent) {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(agent, "agent");
            agents.put(id, agent);
            return this;
        }

        /**
         * 注册通过构建器 lambda 声明的智能体。
         * lambda 接收已应用全局定制器的 {@link HarnessAgent.Builder}。
         */
        public Builder agent(String id, Consumer<HarnessAgent.Builder> configurator) {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(configurator, "configurator");
            HarnessAgent.Builder b = HarnessAgent.builder();
            if (agentCustomizer != null) {
                agentCustomizer.accept(b);
            }
            configurator.accept(b);
            agents.put(id, b.build());
            return this;
        }

        /**
         * 设置主智能体 ID（作为路由回退目标）。
         * 未调用时，第一个注册的智能体成为主智能体。
         */
        public Builder mainAgent(String id) {
            this.mainAgentId = id;
            return this;
        }

        /**
         * 注册一个或多个交由网关管理的外部渠道。
         */
        public Builder channel(Channel... channels) {
            for (Channel ch : channels) {
                this.channels.add(Objects.requireNonNull(ch, "channel"));
            }
            return this;
        }

        /**
         * 向通过 lambda 形式的 {@link #agent(String, Consumer)} 方法创建的每个智能体构建器
         * 应用定制器。适合设置共享模型或工作区。
         */
        public Builder configureAllAgents(Consumer<HarnessAgent.Builder> customizer) {
            this.agentCustomizer = customizer;
            return this;
        }

        /**
         * 设置用于构建持久化 {@link SubagentRegistry} 的分布式存储，
         * 使通过 {@code expose_to_user} 公开的子智能体可跨节点／重启解析和重建。
         * 未设置时，回退使用主智能体自身的 {@code distributedStore}（若有）；否则仅在当前进程内公开。
         */
        public Builder distributedStore(DistributedStore store) {
            this.distributedStore = store;
            return this;
        }

        /**
         * 设置 {@link ChannelRuntimeContextResolver}，在每个网关轮次应用身份字段前，
         * 提供或替换调用方 {@code RuntimeContext}。
         * 适合从周围请求中附加租户、认证或工具依赖。
         */
        public Builder runtimeContextResolver(ChannelRuntimeContextResolver resolver) {
            this.runtimeContextResolver = resolver;
            return this;
        }

        /**
         * 构建网关启动引导对象。至少须注册一个智能体。
         *
         * @throws IllegalStateException 没有已注册智能体时抛出
         */
        public GatewayBootstrap build() {
            if (agents.isEmpty()) {
                throw new IllegalStateException(
                        "At least one agent must be registered via agent(...)");
            }

            String resolvedMainId = mainAgentId;
            if (resolvedMainId == null) {
                resolvedMainId = agents.keySet().iterator().next();
            }
            if (!agents.containsKey(resolvedMainId)) {
                throw new IllegalStateException(
                        "mainAgent('" + resolvedMainId + "') not found in registered agents");
            }

            ChannelManager cm = new ChannelManager();
            for (Channel ch : channels) {
                cm.register(ch);
            }

            HarnessGateway gw = HarnessGateway.create(cm);
            if (runtimeContextResolver != null) {
                gw.setRuntimeContextResolver(runtimeContextResolver);
            }

            // 注册全部智能体并绑定主智能体
            HarnessAgent mainHa = agents.get(resolvedMainId);
            gw.bindMainAgent(mainHa);
            for (Map.Entry<String, HarnessAgent> entry : agents.entrySet()) {
                if (!entry.getKey().equals(resolvedMainId)) {
                    gw.registerAgent(entry.getKey(), entry.getValue());
                }
            }

            // ---- 已公开子智能体的恢复装配（跨节点／重启后）----
            // 组合重建器依次尝试每个已注册智能体的管理器，
            // 使任意节点都能重建子智能体；配置分布式存储时，持久化注册表
            // 让 subagentId 可在当前进程之外解析。没有这些设施时，
            // 公开能力仍局限于当前进程（旧行为）。
            List<DefaultAgentManager> managers =
                    agents.values().stream()
                            .map(HarnessAgent::getSubagentAgentManager)
                            .filter(Objects::nonNull)
                            .toList();
            if (!managers.isEmpty()) {
                gw.setSubagentMaterializer(
                        (agentId, rc) -> {
                            for (DefaultAgentManager m : managers) {
                                Optional<Agent> agent = m.createAgentIfPresent(agentId, rc);
                                if (agent.isPresent()) {
                                    return agent;
                                }
                            }
                            return Optional.empty();
                        });
            }
            DistributedStore store = distributedStore;
            if (store == null) {
                store = mainHa.getDistributedStore();
            }
            if (store != null) {
                gw.setSubagentRegistry(new StoreBackedSubagentRegistry(store.baseStore()));
            }

            return new GatewayBootstrap(gw, cm, new LinkedHashMap<>(agents), resolvedMainId);
        }
    }
}
