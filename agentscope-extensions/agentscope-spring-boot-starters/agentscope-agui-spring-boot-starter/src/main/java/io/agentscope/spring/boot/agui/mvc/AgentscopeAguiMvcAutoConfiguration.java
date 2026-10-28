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
package io.agentscope.spring.boot.agui.mvc;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.adapter.AguiAgentAdapterFactory;
import io.agentscope.core.agui.adapter.strategy.AgentEventConverter;
import io.agentscope.core.agui.adapter.strategy.AguiEventEnricher;
import io.agentscope.core.agui.registry.AguiAgentRegistry;
import io.agentscope.core.agui.runtime.AguiRequestBodyParser;
import io.agentscope.core.agui.runtime.AguiRuntimeContextResolver;
import io.agentscope.spring.boot.agui.common.AguiProperties;
import io.agentscope.spring.boot.agui.common.ThreadSessionManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * AG-UI MVC 集成的 Spring Boot 自动配置。
 *
 * <p>提供：
 * <ul>
 *   <li>用于管理智能体的 AguiAgentRegistry bean</li>
 *   <li>用于处理请求的 AguiMvcController bean</li>
 *   <li>用于暴露端点的 AguiRestController bean</li>
 * </ul>
 *
 * <p>添加项目依赖，并将智能体注册到 AguiAgentRegistry 即可使用。
 */
@AutoConfiguration
@ConditionalOnClass({SseEmitter.class, Agent.class})
@ConditionalOnBean(AguiAgentRegistry.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(AguiProperties.class)
public class AgentscopeAguiMvcAutoConfiguration {

    /**
     * 创建线程会话管理器 bean。
     *
     * @param props 配置属性
     * @return 新的 ThreadSessionManager
     */
    @Bean
    @ConditionalOnMissingBean
    public ThreadSessionManager threadSessionManager(AguiProperties props) {
        return new ThreadSessionManager(
                props.getMaxThreadSessions(), props.getSessionTimeoutMinutes());
    }

    /**
     * 创建默认 AG-UI 请求体解析器 bean。
     *
     * <p>通过 AgentScope 的 Jackson 2 编解码器解析请求体，避免 Spring Boot 4 的 Jackson 3 转换器处理带 Jackson 2 注解的 AG-UI 模型。应用可覆盖此 bean 来定制解析。
     *
     * @return 新的 AguiRequestBodyParser
     */
    @Bean
    @ConditionalOnMissingBean
    public AguiRequestBodyParser aguiRequestBodyParser() {
        return new AguiRequestBodyParser();
    }

    /**
     * 创建 AG-UI MVC 控制器 bean。
     *
     * @param registry 智能体注册表
     * @param sessionManager 线程会话管理器
     * @param props 配置属性
     * @return 新的 AguiMvcController
     */
    @Bean
    @ConditionalOnMissingBean
    public AguiMvcController aguiMvcController(
            AguiAgentRegistry registry,
            ThreadSessionManager sessionManager,
            AguiProperties props,
            ObjectProvider<AgentEventConverter> eventConvertersProvider,
            ObjectProvider<AguiEventEnricher> eventEnrichersProvider,
            ObjectProvider<AguiRuntimeContextResolver> runtimeContextResolverProvider,
            ObjectProvider<AguiAgentAdapterFactory> adapterFactoryProvider) {
        AguiAdapterConfig config =
                AguiAdapterConfig.builder()
                        .toolMergeMode(props.getDefaultToolMergeMode())
                        .runTimeout(props.getRunTimeout())
                        .emitStateEvents(props.isEmitStateEvents())
                        .emitToolCallArgs(props.isEmitToolCallArgs())
                        .emitTokenUsage(props.isEmitTokenUsage())
                        .enableReasoning(props.isEnableReasoning())
                        .emitRunFinishedAfterError(props.isEmitRunFinishedAfterError())
                        .defaultAgentId(props.getDefaultAgentId())
                        .eventConverters(eventConvertersProvider.orderedStream().toList())
                        .eventEnrichers(eventEnrichersProvider.orderedStream().toList())
                        .build();

        return AguiMvcController.builder()
                .agentRegistry(registry)
                .sessionManager(sessionManager)
                .serverSideMemory(props.isServerSideMemory())
                .agentIdHeader(props.getAgentIdHeader())
                .sseTimeout(props.getSseTimeout())
                .interruptOnDisconnect(props.isInterruptOnDisconnect())
                .runtimeContextResolver(runtimeContextResolverProvider.getIfAvailable())
                .adapterFactory(adapterFactoryProvider.getIfAvailable())
                .config(config)
                .build();
    }

    /**
     * 创建 AG-UI 端点的 REST 控制器。
     *
     * @param aguiMvcController AG-UI MVC 控制器
     * @param props 配置属性
     * @return 新的 AguiRestController
     */
    @Bean
    @ConditionalOnMissingBean
    public AguiRestController aguiRestController(
            AguiMvcController aguiMvcController,
            AguiProperties props,
            AguiRequestBodyParser requestBodyParser) {
        return new AguiRestController(
                aguiMvcController,
                props.getPathPrefix(),
                props.isEnablePathRouting(),
                requestBodyParser);
    }
}
