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
package io.agentscope.extensions.agentprotocol;

import io.agentscope.harness.agent.HarnessAgent;
import java.nio.file.Path;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 启用时注册任务协议 REST 端点。
 *
 * <p>将 {@link ConditionalOnBean} 放在 {@code @Bean} 方法级别而非类级别，以避免 Spring Boot 自动配置的顺序问题：在 {@code @AutoConfiguration} 类上的类级 {@code @ConditionalOnBean} 可能早于用户自定义 bean 的注册进行求值。
 *
 * <p>通过 {@link AgentFactory} 选择智能体。未提供用户自定义 bean 时，默认工厂为每个任务解析单个 {@link HarnessAgent} bean；可定义 {@link AgentFactory} bean，按 {@code agent_id} 或提交上下文路由。
 *
 * <p>协议任务元数据通过 {@link ProtocolTaskRepository} 持久化，默认根目录为 {@link AgentProtocolProperties#getTaskStorePath()}，而不是执行智能体的工作区。
 *
 * <p>所有 {@link RuntimeContextCustomizer} bean 按 {@code @Order} 顺序应用到每轮任务运行的上下文。
 *
 * <p>并发执行任务时，可将 {@link HarnessAgent} bean 注册为 {@code @Scope("prototype")}，使各任务获得独立实例；也可在已确保其他并发访问安全的前提下，为单例配置 {@code checkRunning(false)}。
 */
@AutoConfiguration
@EnableConfigurationProperties(AgentProtocolProperties.class)
@ConditionalOnProperty(prefix = "agentscope.agent-protocol", name = "enabled", havingValue = "true")
public class AgentProtocolAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(AgentProtocolEventBus.class)
    public AgentProtocolEventBus agentProtocolEventBus(AgentProtocolProperties properties) {
        return new AgentProtocolTaskEventBus(properties.getSseReplayBufferSize());
    }

    @Bean
    @ConditionalOnBean(HarnessAgent.class)
    @ConditionalOnMissingBean(AgentFactory.class)
    public AgentFactory agentProtocolAgentFactory(ObjectProvider<HarnessAgent> agentProvider) {
        return request -> agentProvider.getObject();
    }

    @Bean
    @ConditionalOnMissingBean(ProtocolTaskRepository.class)
    public ProtocolTaskRepository agentProtocolTaskRepository(AgentProtocolProperties properties) {
        return new WorkspaceProtocolTaskRepository(Path.of(properties.getTaskStorePath()));
    }

    @Bean
    @ConditionalOnBean({AgentFactory.class, ProtocolTaskRepository.class})
    public AgentProtocolTaskStore agentProtocolTaskStore(
            AgentFactory agentFactory,
            ProtocolTaskRepository taskRepository,
            AgentProtocolEventBus eventBus,
            AgentProtocolProperties properties,
            ObjectProvider<RuntimeContextCustomizer> runtimeContextCustomizers) {
        return new AgentProtocolTaskStore(
                agentFactory,
                taskRepository,
                eventBus,
                properties,
                runtimeContextCustomizers.orderedStream().toList());
    }

    @Bean
    @ConditionalOnBean(AgentProtocolTaskStore.class)
    public AgentProtocolController agentProtocolController(
            AgentProtocolTaskStore store, AgentProtocolProperties properties) {
        return new AgentProtocolController(store, properties);
    }
}
