/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.agentscope.core.a2a.server;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.a2a.server.card.AgentScopeAgentCardConverter;
import io.agentscope.core.a2a.server.card.ConfigurableAgentCard;
import io.agentscope.core.a2a.server.executor.AgentExecuteProperties;
import io.agentscope.core.a2a.server.executor.AgentScopeAgentExecutor;
import io.agentscope.core.a2a.server.executor.runner.AgentRunner;
import io.agentscope.core.a2a.server.executor.runner.ReActAgentWithBuilderRunner;
import io.agentscope.core.a2a.server.registry.AgentRegistry;
import io.agentscope.core.a2a.server.registry.AgentRegistryService;
import io.agentscope.core.a2a.server.request.AgentScopeA2aRequestHandler;
import io.agentscope.core.a2a.server.transport.DeploymentProperties;
import io.agentscope.core.a2a.server.transport.TransportProperties;
import io.agentscope.core.a2a.server.transport.TransportWrapper;
import io.agentscope.core.a2a.server.transport.TransportWrapperBuilder;
import io.agentscope.core.agent.Agent;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.a2aproject.sdk.jsonrpc.common.json.JsonProcessingException;
import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.server.events.MainEventBus;
import org.a2aproject.sdk.server.events.QueueManager;
import org.a2aproject.sdk.server.tasks.PushNotificationConfigStore;
import org.a2aproject.sdk.server.tasks.PushNotificationSender;
import org.a2aproject.sdk.server.tasks.TaskStore;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.TransportProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AgentScope A2A 服务器。
 *
 * <p>开发者通过 AgentScope 将智能体暴露为 A2A 服务器的核心入口。构建时可配置：
 * <ul>
 *   <li>必需：自定义 {@link Agent}，例如 {@link ReActAgent}。</li>
 *   <li>可选：自定义 AgentCard。</li>
 *   <li>可选：选择支持并暴露的传输，例如 JSONRPC。</li>
 *   <li>可选：服务器就绪接收请求后使用的 A2A 注册中心。</li>
 *   <li>可选：A2A 服务端功能实现，包括 {@link TaskStore}、{@link QueueManager}、
 *       {@link PushNotificationConfigStore}、{@link PushNotificationSender}。</li>
 * </ul>
 *
 * <p>此服务器本身不监听端口或暴露端点；主要负责构建并组装处理 A2A 请求的组件。
 *
 * <p>例如，要为 A2A 服务器暴露 JSON-RPC 传输，最简单的步骤为：
 * <ol>
 *   <li>构建自定义 {@link Agent}，例如使用 {@link ReActAgent.Builder} 构建 {@link ReActAgent}。</li>
 *   <li>使用必需的 {@link ReActAgent.Builder} 创建 {@link AgentScopeA2aServer} 构建器。</li>
 *   <li>构建并持有 {@link AgentScopeA2aServer} 实例。</li>
 *   <li>启动自定义端口的 Web 服务器及自定义 Controller，接收 JSON-RPC 请求。</li>
 *   <li>Web 服务器就绪时调用 {@link #postEndpointReady()}。</li>
 *   <li>收到客户端请求时，从此 {@link AgentScopeA2aServer} 获取 JSON-RPC 的
 *       {@link TransportWrapper} 实现，实际处理请求。</li>
 * </ol>
 */
@SuppressWarnings("rawtypes")
public class AgentScopeA2aServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AgentScopeA2aServer.class);

    private final Map<String, TransportWrapper> transportWrappers;

    private final Set<TransportProperties> transportProperties;

    private final AgentCard agentCard;

    private final AgentRegistryService agentRegistry;

    private final AgentScopeA2aRequestHandler requestHandler;

    private AgentScopeA2aServer(
            Map<String, TransportWrapper> transportWrappers,
            Set<TransportProperties> transportProperties,
            AgentCard agentCard,
            AgentRegistryService agentRegistry,
            AgentScopeA2aRequestHandler requestHandler) {
        this.transportWrappers = transportWrappers;
        this.transportProperties = transportProperties;
        this.agentCard = agentCard;
        this.agentRegistry = agentRegistry;
        this.requestHandler = requestHandler;
    }

    /**
     * 停止请求处理器的线程，参见 {@link AgentScopeA2aRequestHandler#close()}。
     * Spring Boot starter 在应用上下文关闭时调用此方法。重复调用无效果。
     */
    @Override
    public void close() {
        requestHandler.close();
    }

    /**
     * 按名称获取原始类型的 {@link TransportWrapper} 实例。
     * 开发者应在实际使用前确认类型符合预期。
     *
     * @param transportName 传输名称
     * @return 原始类型的 {@link TransportWrapper} 实例
     */
    public TransportWrapper getTransportWrapper(String transportName) {
        TransportWrapper result = transportWrappers.get(transportName);
        if (null == result) {
            throw new IllegalArgumentException("Transport " + transportName + " not found.");
        }
        return result;
    }

    /**
     * 按名称和预期类型获取具体 {@link TransportWrapper} 实例。
     *
     * @param transportName 传输名称
     * @param expectedType 预期的 {@link TransportWrapper} 类
     * @param <W> {@link TransportWrapper} 子类型
     * @return 预期类型的 {@link TransportWrapper} 实例
     * @throws ClassCastException 找到的包装器类型不符时抛出
     */
    public <W extends TransportWrapper<?, ?>> W getTransportWrapper(
            String transportName, Class<W> expectedType) {
        TransportWrapper rawWrapper = getTransportWrapper(transportName);
        if (expectedType.isInstance(rawWrapper)) {
            return expectedType.cast(rawWrapper);
        } else {
            throw new ClassCastException(
                    "Transport '"
                            + transportName
                            + "' is of type "
                            + rawWrapper.getClass().getName()
                            + ", not the expected type "
                            + expectedType.getName());
        }
    }

    /**
     * 获取此服务器的 {@link AgentCard} 实例。
     *
     * @return 智能体卡片
     */
    public AgentCard getAgentCard() {
        return agentCard;
    }

    /**
     * 以 JSON 获取此服务器的智能体卡片，供 A2A 客户端使用。
     *
     * <p>JSON 由 A2A SDK 写出，不依赖宿主框架的对象序列化方式。
     *
     * @return 智能体卡片 JSON
     */
    public String getAgentCardJson() {
        try {
            return JsonUtil.toJson(agentCard);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize the agent card", e);
        }
    }

    /**
     * 所有端点就绪可访问时调用此方法。
     *
     * <p>默认应在 Web 服务器或其他网络端点就绪后调用，
     * 使 AgentScope 完成注册到 A2A 注册中心等服务端操作。
     */
    public void postEndpointReady() {
        agentRegistry.register(agentCard, transportProperties);
    }

    /**
     * 根据 {@link ReActAgent.Builder} 获取 {@link AgentScopeA2aServer} 构建器。
     *
     * <p>大多数情况下使用默认 {@link ReActAgentWithBuilderRunner} 作为 {@link AgentRunner}。
     *
     * @param agentBuilder {@link ReActAgent} 构建器
     * @return {@link AgentScopeA2aServer} 构建器实例
     * @see #builder(AgentRunner)
     */
    public static Builder builder(ReActAgent.Builder agentBuilder) {
        return builder(ReActAgentWithBuilderRunner.newInstance(agentBuilder));
    }

    /**
     * 根据自定义 {@link AgentRunner} 获取 {@link AgentScopeA2aServer} 构建器。
     *
     * @param agentRunner 自定义智能体运行器
     * @return {@link AgentScopeA2aServer} 构建器实例
     */
    public static Builder builder(AgentRunner agentRunner) {
        return new Builder(agentRunner);
    }

    public static class Builder {

        private final AgentRunner agentRunner;

        private final Set<TransportProperties> supportedTransports;

        private final List<AgentRegistry> agentRegistries;

        private ConfigurableAgentCard agentCard;

        private TaskStore taskStore;

        private QueueManager queueManager;

        private MainEventBus mainEventBus;

        private PushNotificationConfigStore pushConfigStore;

        private PushNotificationSender pushSender;

        private Executor executor;

        private DeploymentProperties deploymentProperties;

        private AgentExecuteProperties agentExecuteProperties;

        private Builder(AgentRunner agentRunner) {
            this.agentRunner = agentRunner;
            this.supportedTransports = new HashSet<>();
            this.agentRegistries = new LinkedList<>();
        }

        /**
         * 设置开发者配置的智能体卡片。
         *
         * @param agentCard 已配置的智能体卡片
         * @return {@link AgentScopeA2aServer} 构建器实例
         */
        public Builder agentCard(ConfigurableAgentCard agentCard) {
            this.agentCard = agentCard;
            return this;
        }

        /**
         * 为此 A2A 服务器添加支持的传输及其属性。
         *
         * @param transportProperties 支持的传输属性
         * @return {@link AgentScopeA2aServer} 构建器实例
         */
        public Builder withTransport(TransportProperties transportProperties) {
            supportedTransports.add(transportProperties);
            return this;
        }

        /**
         * 设置 {@link TaskStore} 实现。
         *
         * <p>默认使用 {@link org.a2aproject.sdk.server.tasks.InMemoryTaskStore}。
         *
         * @param taskStore 任务存储
         * @return {@link AgentScopeA2aServer} 构建器实例
         */
        public Builder taskStore(TaskStore taskStore) {
            this.taskStore = taskStore;
            return this;
        }

        /**
         * 设置 {@link QueueManager} 实现。
         *
         * <p>默认使用 {@link org.a2aproject.sdk.server.events.InMemoryQueueManager}。
         * 自定义队列管理器必须使用事件总线构建，并通过 {@link #mainEventBus(MainEventBus)}
         * 设置同一个总线。
         *
         * @param queueManager 队列管理器
         * @return {@link AgentScopeA2aServer} 构建器实例
         */
        public Builder queueManager(QueueManager queueManager) {
            this.queueManager = queueManager;
            return this;
        }

        /**
         * 设置将队列事件传给持久化及分派处理器的总线。使用自定义
         * {@link #queueManager(QueueManager) queue manager} 时必须传入其构造时使用的总线。
         * 默认创建新总线。
         *
         * @param mainEventBus 事件总线
         * @return {@link AgentScopeA2aServer} 构建器实例
         */
        public Builder mainEventBus(MainEventBus mainEventBus) {
            this.mainEventBus = mainEventBus;
            return this;
        }

        /**
         * 设置 {@link PushNotificationConfigStore} 实现。
         *
         * <p>默认使用 {@link org.a2aproject.sdk.server.tasks.InMemoryPushNotificationConfigStore}。
         * <p>尚不支持推送通知：不接受新推送配置，但自定义存储已有的配置仍会由推送发送器发送。
         *
         * @param pushConfigStore 推送通知配置存储
         * @return {@link AgentScopeA2aServer} 构建器实例
         */
        public Builder pushConfigStore(PushNotificationConfigStore pushConfigStore) {
            this.pushConfigStore = pushConfigStore;
            return this;
        }

        /**
         * 设置 {@link PushNotificationSender} 实现。
         *
         * <p>默认使用 {@link org.a2aproject.sdk.server.tasks.BasePushNotificationSender}。
         * <p>尚不支持推送通知：不接受新推送配置，但自定义存储已有的配置仍会由推送发送器发送。
         *
         * @param pushSender 推送通知发送器
         * @return {@link AgentScopeA2aServer} 构建器实例
         */
        public Builder pushSender(PushNotificationSender pushSender) {
            this.pushSender = pushSender;
            return this;
        }

        /**
         * 设置处理 A2A 请求的执行器。
         *
         * <p>默认使用 {@link Executors#newCachedThreadPool()}。
         *
         * @param executor 执行器
         * @return {@link AgentScopeA2aServer} 构建器实例
         */
        public Builder executor(Executor executor) {
            this.executor = executor;
            return this;
        }

        /**
         * 设置部署端口，用于生成 AgentCard 中默认传输接口信息。
         *
         * @param port 部署端口
         * @return {@link AgentScopeA2aServer} 构建器实例
         */
        public Builder deploymentProperties(Integer port) {
            this.deploymentProperties = new DeploymentProperties.Builder().port(port).build();
            return this;
        }

        /**
         * 设置部署属性，用于生成 AgentCard 中默认传输接口信息。
         *
         * @param deploymentProperties 部署属性
         * @return {@link AgentScopeA2aServer} 构建器实例
         */
        public Builder deploymentProperties(DeploymentProperties deploymentProperties) {
            this.deploymentProperties = deploymentProperties;
            return this;
        }

        /**
         * 添加负责将此智能体注册到注册中心的 {@link AgentRegistry} 实现。
         *
         * @param agentRegistry 智能体注册中心实现
         * @return {@link AgentScopeA2aServer} 构建器实例
         */
        public Builder withAgentRegistry(AgentRegistry agentRegistry) {
            this.agentRegistries.add(agentRegistry);
            return this;
        }

        /**
         * 设置智能体执行属性。
         *
         * @param agentExecuteProperties 智能体执行属性
         * @return {@link AgentScopeA2aServer} 构建器实例
         */
        public Builder agentExecuteProperties(AgentExecuteProperties agentExecuteProperties) {
            this.agentExecuteProperties = agentExecuteProperties;
            return this;
        }

        /**
         * 构建新的 {@link AgentScopeA2aServer} 实例。
         *
         * <p>构建器按以下步骤构建：
         * <ul>
         *   <li>根据输入卡片属性与传输属性构建 {@link AgentCard}。</li>
         *   <li>使用输入 {@link AgentRunner} 构建 AgentExecutor 和 RequestHandler。</li>
         *   <li>根据输入属性与 SPI 加载的传输构建器构建全部传输请求处理包装器。</li>
         *   <li>根据输入 {@link AgentRegistry} 构建 {@link AgentRegistryService}。</li>
         * </ul>
         *
         * @return 新的 {@link AgentScopeA2aServer} 实例
         * @throws IllegalArgumentException 缺少 {@link AgentRunner} 或未找到可用传输时抛出
         */
        public AgentScopeA2aServer build() {
            if (null == agentRunner) {
                throw new IllegalArgumentException("AgentRunner is required.");
            }
            if (null == agentExecuteProperties) {
                agentExecuteProperties = AgentExecuteProperties.builder().build();
            }
            AgentScopeAgentExecutor agentExecutor =
                    new AgentScopeAgentExecutor(agentRunner, agentExecuteProperties);
            AgentScopeA2aRequestHandler requestHandler =
                    AgentScopeA2aRequestHandler.builder()
                            .agentExecutor(agentExecutor)
                            .taskStore(taskStore)
                            .mainEventBus(mainEventBus)
                            .queueManager(queueManager)
                            .pushConfigStore(pushConfigStore)
                            .pushSender(pushSender)
                            .build();
            try {
                return build(requestHandler);
            } catch (RuntimeException e) {
                // 请求处理器已启动线程。
                requestHandler.close();
                throw e;
            }
        }

        private AgentScopeA2aServer build(AgentScopeA2aRequestHandler requestHandler) {
            if (null == executor) {
                executor = Executors.newCachedThreadPool();
            }
            if (null == agentCard) {
                agentCard = new ConfigurableAgentCard.Builder().build();
            }
            if (supportedTransports.isEmpty()) {
                log.warn("Not found input supportedTransports, use default transport: `JSONRPC`");
                if (null == deploymentProperties) {
                    log.error(
                            "Not found any properties for transport and not found default"
                                    + " deployment properties.");
                    throw new IllegalArgumentException("Not found any properties for transport.");
                }
                supportedTransports.add(
                        TransportProperties.builder(TransportProtocol.JSONRPC.asString())
                                .host(deploymentProperties.host())
                                .port(deploymentProperties.port())
                                .path(deploymentProperties.path())
                                .build());
            }
            Map<String, TransportWrapperBuilder> allBuilders = loadTransportBuilders();
            Set<TransportProperties> availableTransports = getAvailableTransports(allBuilders);
            if (availableTransports.isEmpty()) {
                throw new IllegalArgumentException("No one available transport found.");
            }
            AgentCard a2aAgentCard =
                    new AgentScopeAgentCardConverter()
                            .createAgentCard(agentCard, agentRunner, availableTransports);
            Map<String, TransportWrapper> transportWrappers =
                    buildTransportWrappers(
                            requestHandler, availableTransports, allBuilders, a2aAgentCard);
            if (transportWrappers.isEmpty()) {
                log.warn("No one TransportWrapper actually, a2a request will not be handle.");
            }
            AgentRegistryService agentRegistryService = new AgentRegistryService(agentRegistries);
            return new AgentScopeA2aServer(
                    transportWrappers,
                    availableTransports,
                    a2aAgentCard,
                    agentRegistryService,
                    requestHandler);
        }

        private Map<String, TransportWrapperBuilder> loadTransportBuilders() {
            return ServiceLoader.load(TransportWrapperBuilder.class).stream()
                    .map(ServiceLoader.Provider::get)
                    .filter(builder -> null != builder.getTransportType())
                    .collect(
                            Collectors.toMap(
                                    TransportWrapperBuilder::getTransportType,
                                    Function.identity(),
                                    (oldBuilder, newBuilder) -> {
                                        log.warn(
                                                "Load duplicate TransportWrapperBuilder for"
                                                    + " transport `{}`, oldType: {}, newType: {}",
                                                oldBuilder.getTransportType(),
                                                oldBuilder.getClass().getName(),
                                                newBuilder.getClass().getName());
                                        return newBuilder;
                                    }));
        }

        private Set<TransportProperties> getAvailableTransports(
                Map<String, TransportWrapperBuilder> allBuilders) {
            return supportedTransports.stream()
                    .filter(
                            transportProperties -> {
                                String transportType = transportProperties.transportType();
                                if (allBuilders.containsKey(transportType)) {
                                    return true;
                                }
                                log.warn(
                                        "Not found TransportWrapperBuilder for configure supported"
                                                + " transport `{}` from SPI, will ignore this"
                                                + " transport",
                                        transportType);
                                return false;
                            })
                    .collect(Collectors.toSet());
        }

        private Map<String, TransportWrapper> buildTransportWrappers(
                AgentScopeA2aRequestHandler requestHandler,
                Set<TransportProperties> availableTransports,
                Map<String, TransportWrapperBuilder> allBuilders,
                AgentCard a2aAgentCard) {
            Map<String, TransportWrapper> result = new HashMap<>();
            Set<TransportProperties> buildFailedTransport = new HashSet<>();
            for (TransportProperties transportProperties : availableTransports) {
                TransportWrapperBuilder builder =
                        allBuilders.get(transportProperties.transportType());
                try {
                    TransportWrapper transportWrapper =
                            builder.build(a2aAgentCard, requestHandler, executor);
                    result.put(transportProperties.transportType(), transportWrapper);
                } catch (Exception e) {
                    log.warn(
                            "Build TransportWrapper for configure supported transport `{}` failed,"
                                    + " will ignore this transport",
                            transportProperties.transportType(),
                            e);
                    buildFailedTransport.add(transportProperties);
                }
            }
            availableTransports.removeAll(buildFailedTransport);
            return result;
        }
    }
}
