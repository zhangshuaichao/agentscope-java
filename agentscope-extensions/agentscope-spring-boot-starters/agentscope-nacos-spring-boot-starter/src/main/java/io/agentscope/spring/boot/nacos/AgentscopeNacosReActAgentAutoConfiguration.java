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

package io.agentscope.spring.boot.nacos;

import com.alibaba.nacos.api.exception.NacosException;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.model.Model;
import io.agentscope.core.nacos.prompt.NacosPromptListener;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.spring.boot.AgentscopeAutoConfiguration;
import io.agentscope.spring.boot.nacos.constants.NacosConstants;
import io.agentscope.spring.boot.nacos.properties.AgentScopeNacosPromptProperties;
import io.agentscope.spring.boot.properties.AgentProperties;
import io.agentscope.spring.boot.properties.AgentscopeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Scope;

/**
 * 组装使用 Nacos 管理提示的 {@link ReActAgent} 实例的自动配置。
 *
 * <p>仅负责用现有模块（模型、工具集、Nacos 提示基础设施）接线智能体；与负责 {@code AiService}、{@code NacosPromptListener} 等提示组件的 {@code AgentscopeNacosPromptAutoConfiguration} 分离。
 *
 * <p>【学习提示】本类在 enabled 缺省时仍满足属性条件，但提示基础设施配置要求显式启用；
 * 因此不能据此推断缺省配置一定能创建智能体，工厂方法仍依赖 NacosPromptListener 等 Bean。
 * Memory 虽是工厂方法参数，当前构建链没有将它传入智能体。
 */
@AutoConfiguration
@AutoConfigureBefore(AgentscopeAutoConfiguration.class)
@EnableConfigurationProperties({AgentscopeProperties.class, AgentScopeNacosPromptProperties.class})
@ConditionalOnClass(ReActAgent.class)
@ConditionalOnProperty(
        prefix = NacosConstants.NACOS_PROMPT_PREFIX,
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class AgentscopeNacosReActAgentAutoConfiguration {

    private static final Logger log =
            LoggerFactory.getLogger(AgentscopeNacosReActAgentAutoConfiguration.class);

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    @ConditionalOnMissingBean(ReActAgent.class)
    public ReActAgent nacosPromptReActAgent(
            Model model,
            Memory memory,
            Toolkit toolkit,
            AgentscopeProperties properties,
            AgentScopeNacosPromptProperties nacosPromptProperties,
            NacosPromptListener nacosPromptListener) {

        AgentProperties agentConfig = properties.getAgent();
        String defaultSysPrompt = agentConfig.getSysPrompt();

        String sysPrompt = defaultSysPrompt;
        String promptKey = nacosPromptProperties.getSysPromptKey();

        if (promptKey != null && !promptKey.isEmpty()) {
            try {
                sysPrompt =
                        nacosPromptListener.getPrompt(
                                promptKey,
                                nacosPromptProperties.getVersion(),
                                nacosPromptProperties.getLabel(),
                                nacosPromptProperties.getVariables(),
                                defaultSysPrompt);
            } catch (NacosException e) {
                log.warn(
                        "Failed to load sys prompt from Nacos for key: {}, fallback to default"
                                + " sys-prompt.",
                        promptKey,
                        e);
            }
        }

        return ReActAgent.builder()
                .name(agentConfig.getName())
                .sysPrompt(sysPrompt)
                .model(model)
                .toolkit(toolkit)
                .maxIters(agentConfig.getMaxIters())
                .build();
    }
}
