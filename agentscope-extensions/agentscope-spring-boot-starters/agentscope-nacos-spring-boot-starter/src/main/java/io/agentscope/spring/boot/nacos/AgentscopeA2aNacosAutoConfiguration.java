/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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

package io.agentscope.spring.boot.nacos;

import com.alibaba.nacos.api.ai.AiFactory;
import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.exception.NacosException;
import io.agentscope.core.a2a.agent.card.AgentCardResolver;
import io.agentscope.core.a2a.server.registry.AgentRegistry;
import io.agentscope.core.nacos.a2a.discovery.NacosAgentCardResolver;
import io.agentscope.core.nacos.a2a.registry.NacosA2aRegistryProperties;
import io.agentscope.core.nacos.a2a.registry.NacosAgentRegistry;
import io.agentscope.spring.boot.nacos.constants.NacosConstants;
import io.agentscope.spring.boot.nacos.properties.AgentScopeNacosProperties;
import io.agentscope.spring.boot.nacos.properties.a2a.AgentScopeA2aNacosProperties;
import jakarta.annotation.PreDestroy;
import java.io.Closeable;
import java.io.IOException;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 为 AgentScope 暴露 A2A Nacos bean 的 Spring Boot 自动配置。
 *
 * <p>创建独立的 Nacos {@link AiService} 客户端用于 A2A 发现和注册，避免与 MCP 注册中心的客户端冲突，让 A2A 与 MCP 可分别配置不同 Nacos 集群。
 *
 * <p>【学习提示】顶层 a2a.nacos.enabled 必须显式为 true，此配置才生效；其内部 discovery/registry 的默认开启只是在顶层已激活之后适用。
 */
@AutoConfiguration
@EnableConfigurationProperties({
    AgentScopeNacosProperties.class,
    AgentScopeA2aNacosProperties.class
})
@ConditionalOnProperty(
        prefix = NacosConstants.A2A_NACOS_PREFIX,
        name = "enabled",
        havingValue = "true",
        matchIfMissing = false)
public class AgentscopeA2aNacosAutoConfiguration implements Closeable {

    private static final Logger log =
            LoggerFactory.getLogger(AgentscopeA2aNacosAutoConfiguration.class);

    private final AiService a2aService;

    /**
     * 构造新的 {@link AgentscopeA2aNacosAutoConfiguration}。
     *
     * @param nacosProperties 基础 Nacos 属性
     * @param a2aNacosProperties A2A Nacos 属性
     * @throws NacosException 创建 Nacos A2A 服务出错时抛出
     */
    public AgentscopeA2aNacosAutoConfiguration(
            AgentScopeNacosProperties nacosProperties,
            AgentScopeA2aNacosProperties a2aNacosProperties)
            throws NacosException {
        this.a2aService = a2aService(nacosProperties, a2aNacosProperties);
    }

    private AiService a2aService(
            AgentScopeNacosProperties nacosProperties,
            AgentScopeA2aNacosProperties a2aNacosProperties)
            throws NacosException {
        Properties nacosClientProperties = nacosProperties.getNacosProperties();
        nacosClientProperties.putAll(a2aNacosProperties.getExplicitNacosProperties());
        return AiFactory.createAiService(nacosClientProperties);
    }

    /**
     * 关闭 Nacos A2A 服务。
     *
     * <p>【学习提示】当前实现捕获 NacosException 并记录日志，不向外传播它；关闭方法的 throws 声明不代表所有清理失败都会由调用方收到。
     *
     * @throws IOException 方法签名允许关闭过程中的 I/O 错误
     */
    @Override
    @PreDestroy
    public void close() throws IOException {
        if (null != a2aService) {
            try {
                a2aService.shutdown();
            } catch (NacosException e) {
                log.error("Error shutting down Nacos A2A service", e);
            }
        }
    }

    /**
     * 创建从 Nacos 解析智能体卡片的 bean。
     *
     * @return {@link NacosAgentCardResolver} 实例
     */
    @Bean
    @ConditionalOnProperty(
            prefix = NacosConstants.A2A_NACOS_DISCOVERY_PREFIX,
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    public AgentCardResolver nacosAgentCardResolver() {
        return new NacosAgentCardResolver(a2aService);
    }

    /**
     * 创建向 Nacos 注册智能体的 bean。
     *
     * @param a2aNacosProperties A2A Nacos 属性
     * @return {@link NacosAgentRegistry} 实例
     */
    @Bean
    @ConditionalOnProperty(
            prefix = NacosConstants.A2A_NACOS_REGISTRY_PREFIX,
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    public AgentRegistry nacosAgentRegistry(AgentScopeA2aNacosProperties a2aNacosProperties) {
        return NacosAgentRegistry.builder(a2aService)
                .nacosA2aProperties(buildNacosA2aProperties(a2aNacosProperties))
                .build();
    }

    private NacosA2aRegistryProperties buildNacosA2aProperties(
            AgentScopeA2aNacosProperties a2aProperties) {
        return NacosA2aRegistryProperties.builder()
                .setAsLatest(a2aProperties.getRegistry().isRegisterAsLatest())
                .enabledRegisterEndpoint(a2aProperties.getRegistry().isEnabledRegisterEndpoint())
                .overwritePreferredTransport(
                        a2aProperties.getRegistry().getOverwritePreferredTransport())
                .build();
    }
}
