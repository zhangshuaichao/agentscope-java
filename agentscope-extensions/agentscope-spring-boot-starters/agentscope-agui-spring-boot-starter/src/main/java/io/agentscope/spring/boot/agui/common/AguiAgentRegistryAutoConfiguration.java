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
package io.agentscope.spring.boot.agui.common;

import io.agentscope.core.agui.registry.AguiAgentRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * 智能体注册表的 Spring Boot 自动配置。
 *
 * <p>提供：
 * <ul>
 *   <li>用于管理智能体的 AguiAgentRegistry bean</li>
 *   <li>将 Agent bean 注册到 AguiAgentRegistry 的 AguiAgentAutoRegistration bean</li>
 * </ul>
 *
 * <p>在项目中添加依赖，并将智能体注册到 AguiAgentRegistry 即可使用。
 */
@AutoConfiguration
@ConditionalOnClass(
        value = {
            AguiAgentRegistry.class,
            AguiAgentRegistryCustomizer.class,
            AguiAgentAutoRegistration.class
        })
public class AguiAgentRegistryAutoConfiguration {

    /**
     * 创建智能体注册表 bean。
     *
     * @param customizerObjectProvider 智能体注册表定制器的对象提供方
     * @return 新的 AguiAgentRegistry
     */
    @Bean
    @ConditionalOnMissingBean
    public AguiAgentRegistry aguiAgentRegistry(
            ObjectProvider<AguiAgentRegistryCustomizer> customizerObjectProvider) {
        AguiAgentRegistry registry = new AguiAgentRegistry();
        customizerObjectProvider.stream().forEach(customizer -> customizer.customize(registry));
        return registry;
    }

    /**
     * 创建智能体自动注册 bean。
     *
     * @param registry 智能体注册表
     * @return 新的 AguiAgentAutoRegistration
     */
    @Bean
    @ConditionalOnMissingBean
    public AguiAgentAutoRegistration aguiAgentAutoRegistration(AguiAgentRegistry registry) {
        return new AguiAgentAutoRegistration(registry);
    }
}
