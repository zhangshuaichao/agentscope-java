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
package io.agentscope.spring.boot;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.spring.boot.properties.AgentProperties;
import io.agentscope.spring.boot.properties.AgentscopeProperties;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Scope;

/**
 * 为 AgentScope 提供默认 Memory、Toolkit 和 ReActAgent bean 的 Spring Boot 自动配置。
 *
 * <p>Model bean 由 {@code agentscope-dashscope-spring-boot-starter}、{@code agentscope-openai-spring-boot-starter}、{@code agentscope-gemini-spring-boot-starter}、{@code agentscope-anthropic-spring-boot-starter} 等提供方专用 starter，或用户自定义 {@link Model} bean 提供。
 *
 * <p>基本配置：
 *
 * <pre>{@code
 * agentscope:
 *   agent:
 *     enabled: true
 *     name: "Assistant"
 *     sys-prompt: "You are a helpful AI assistant."
 *     max-iters: 10
 * }</pre>
 */
// 【学习提示】默认智能体还要求已有 Model、显式开启 agentscope.agent.enabled，且不存在用户提供的同类 bean；
// 引入依赖或填写模型属性本身，并不意味着所有默认 bean 都会创建。
@AutoConfiguration
@EnableConfigurationProperties(AgentscopeProperties.class)
@ConditionalOnClass(ReActAgent.class)
public class AgentscopeAutoConfiguration {

    /**
     * 基于 InMemoryMemory 的默认 Memory 实现。
     *
     * <p>Memory 包含可变状态，因此将其声明为 prototype 作用域。在多线程或 Web 环境中，建议通过 {@code ObjectProvider<Memory>} 或方法注入按需获取实例。
     */
    @Bean
    @ConditionalOnProperty(prefix = "agentscope.agent", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    public Memory agentscopeMemory() {
        return new InMemoryMemory();
    }

    /**
     * 初始工具集为空的默认 Toolkit 实现。
     *
     * <p>Toolkit 包含可变状态，因此也声明为 prototype 作用域。应用代码宜通过 {@code ObjectProvider<Toolkit>} 或方法注入按需获取实例。
     */
    @Bean
    @ConditionalOnProperty(prefix = "agentscope.agent", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    public Toolkit agentscopeToolkit() {
        return new Toolkit();
    }

    /**
     * 依据 {@link AgentProperties} 将配置的 Model、Memory 与 Toolkit 组合成默认 ReActAgent。
     *
     * <p>此自动配置提供单例 ReActAgent；其会话状态管理与并发约束应结合 ReActAgent 2.x 实现及自定义工具阅读，不能据单例作用域推断所有依赖都可安全并发访问。
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(Model.class)
    @ConditionalOnProperty(prefix = "agentscope.agent", name = "enabled", havingValue = "true")
    public ReActAgent agentscopeReActAgent(
            Model model, Memory memory, Toolkit toolkit, AgentscopeProperties properties) {
        // 【学习提示】此方法没有 @Scope(prototype)，创建的是默认单例；prototype Toolkit 仅在装配时注入一次。
        // 当前构建链未使用参数 memory，默认 Memory bean 的存在不等于它已接入 ReActAgent 2.x 状态。
        AgentProperties config = properties.getAgent();
        return ReActAgent.builder()
                .name(config.getName())
                .sysPrompt(config.getSysPrompt())
                .model(model)
                .toolkit(toolkit)
                .maxIters(config.getMaxIters())
                .build();
    }
}
