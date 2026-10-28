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

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agui.registry.AguiAgentRegistry;
import java.lang.reflect.Method;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.annotation.AnnotationUtils;

/**
 * 将 Agent bean 自动注册到 AguiAgentRegistry 的扫描器。
 *
 * <p>扫描应用上下文中的所有 Agent bean 并注册，支持单例和 prototype 作用域：
 *
 * <ul>
 *   <li><b>单例 bean：</b>直接注册实例
 *   <li><b>Prototype bean：</b>注册工厂，每个请求获取新实例，隔离实例状态
 * </ul>
 *
 * <p><b>智能体 ID 解析：</b>
 *
 * <ol>
 *   <li>bean 方法或类上的 {@link AguiAgentId} 注解
 *   <li>bean 名称（默认）
 * </ol>
 *
 * <p><b>用法：</b>
 *
 * <pre>{@code
 * // 单例 bean（共享实例）
 * @Bean
 * public Agent chatAgent() {
 *     return ReActAgent.builder().name("Chat").model(model).build();
 * }
 *
 * // Prototype bean（每个请求获取新实例，隔离实例状态）
 * @Bean
 * @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
 * public Agent isolatedAgent() {
 *     return ReActAgent.builder().name("Isolated").model(model).build();
 * }
 *
 * // 自定义智能体 ID
 * @Bean
 * @AguiAgentId("custom-id")
 * public Agent myAgent() {
 *     return ReActAgent.builder().name("Custom").model(model).build();
 * }
 * }</pre>
 */
public class AguiAgentAutoRegistration implements BeanFactoryAware, InitializingBean {

    private static final Logger logger = LoggerFactory.getLogger(AguiAgentAutoRegistration.class);

    private ConfigurableListableBeanFactory beanFactory;

    private final AguiAgentRegistry registry;

    /**
     * 创建 AguiAgentAutoRegistration。
     *
     * @param registry 智能体注册表
     */
    public AguiAgentAutoRegistration(AguiAgentRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
        if (beanFactory instanceof ConfigurableListableBeanFactory) {
            this.beanFactory = (ConfigurableListableBeanFactory) beanFactory;
        }
    }

    /**
     * 将所有 Agent bean 注册到 AguiAgentRegistry。
     *
     * <p>在注册表创建后调用，扫描所有 Agent bean。
     */
    protected void aguiAgentAutoRegistrar() {
        if (beanFactory == null) {
            logger.warn("BeanFactory is not available, skipping auto-registration");
            return;
        }

        Map<String, Agent> agentBeans = beanFactory.getBeansOfType(Agent.class);

        for (Map.Entry<String, Agent> entry : agentBeans.entrySet()) {
            String beanName = entry.getKey();
            Agent agent = entry.getValue();

            // 确定智能体 ID
            String agentId = resolveAgentId(beanName);

            // 已注册则跳过，手动注册优先
            if (registry.hasAgent(agentId)) {
                logger.debug("Agent '{}' already registered, skipping auto-registration", agentId);
                continue;
            }

            // 检查 bean 作用域
            boolean isPrototype = isPrototypeBean(beanName);

            if (isPrototype) {
                // 为 prototype bean 注册工厂，每次调用获取新实例
                registry.registerFactory(agentId, () -> beanFactory.getBean(beanName, Agent.class));
                logger.info(
                        "Auto-registered prototype agent '{}' (bean: {}) with factory",
                        agentId,
                        beanName);
            } else {
                // 直接注册单例
                registry.register(agentId, agent);
                logger.info("Auto-registered singleton agent '{}' (bean: {})", agentId, beanName);
            }
        }
    }

    /**
     * 解析 bean 的智能体 ID。
     *
     * @param beanName bean 名称
     * @return 智能体 ID
     */
    private String resolveAgentId(String beanName) {
        // 尝试在 bean 定义上查找 @AguiAgentId 注解
        try {
            BeanDefinition bd = beanFactory.getBeanDefinition(beanName);
            String factoryMethodName = bd.getFactoryMethodName();

            if (factoryMethodName != null && bd.getFactoryBeanName() != null) {
                // 对于 @Bean 方法，检查方法上的注解
                Object factoryBean = beanFactory.getBean(bd.getFactoryBeanName());
                for (Method method : factoryBean.getClass().getMethods()) {
                    if (method.getName().equals(factoryMethodName)) {
                        AguiAgentId annotation =
                                AnnotationUtils.findAnnotation(method, AguiAgentId.class);
                        if (annotation != null) {
                            return annotation.value();
                        }
                        break;
                    }
                }
            }

            // 检查 bean 类上的注解
            Class<?> beanType = beanFactory.getType(beanName);
            if (beanType != null) {
                AguiAgentId annotation =
                        AnnotationUtils.findAnnotation(beanType, AguiAgentId.class);
                if (annotation != null) {
                    return annotation.value();
                }
            }
        } catch (Exception e) {
            logger.debug(
                    "Could not resolve agent ID annotation for bean '{}': {}",
                    beanName,
                    e.getMessage());
        }

        // 默认使用 bean 名称
        return beanName;
    }

    /**
     * 判断 bean 是否为 prototype 作用域。
     *
     * @param beanName bean 名称
     * @return 为 prototype 作用域时返回 true
     */
    private boolean isPrototypeBean(String beanName) {
        try {
            BeanDefinition bd = beanFactory.getBeanDefinition(beanName);
            return bd.isPrototype();
        } catch (Exception e) {
            logger.debug("Could not determine scope for bean '{}': {}", beanName, e.getMessage());
            return false;
        }
    }

    /** 所有属性设置完毕后调用 {@link #aguiAgentAutoRegistrar()}。 */
    @Override
    public void afterPropertiesSet() throws Exception {
        this.aguiAgentAutoRegistrar();
    }
}
