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
package io.agentscope.spring.boot.admin;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.middleware.SubagentEntry;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.spring.boot.admin.audit.AdminAuditLogger;
import io.agentscope.spring.boot.admin.command.AdminCommandRegistry;
import io.agentscope.spring.boot.admin.command.BuiltinCommandRegistrar;
import io.agentscope.spring.boot.admin.controller.SessionAdminController;
import io.agentscope.spring.boot.admin.controller.SubagentTaskController;
import io.agentscope.spring.boot.admin.endpoint.AgentscopeAgentsEndpoint;
import io.agentscope.spring.boot.admin.endpoint.AgentscopeCommandsEndpoint;
import io.agentscope.spring.boot.admin.endpoint.AgentscopeDoctorEndpoint;
import io.agentscope.spring.boot.admin.endpoint.AgentscopeDrainEndpoint;
import io.agentscope.spring.boot.admin.endpoint.AgentscopeModelsEndpoint;
import io.agentscope.spring.boot.admin.endpoint.AgentscopePermissionsEndpoint;
import io.agentscope.spring.boot.admin.endpoint.AgentscopeShutdownEndpoint;
import io.agentscope.spring.boot.admin.endpoint.AgentscopeStatusEndpoint;
import io.agentscope.spring.boot.admin.endpoint.AgentscopeSubagentsEndpoint;
import io.agentscope.spring.boot.admin.endpoint.AgentscopeToolsEndpoint;
import io.agentscope.spring.boot.admin.endpoint.AgentscopeUsageEndpoint;
import io.agentscope.spring.boot.admin.metrics.MetricsHook;
import io.agentscope.spring.boot.admin.metrics.MetricsRecorder;
import io.agentscope.spring.boot.admin.openapi.AdminOpenApiConfiguration;
import io.agentscope.spring.boot.admin.properties.AdminProperties;
import io.agentscope.spring.boot.admin.registry.AgentRegistry;
import io.agentscope.spring.boot.admin.registry.InMemoryAgentRegistry;
import io.agentscope.spring.boot.admin.service.AgentInventory;
import io.agentscope.spring.boot.admin.service.ModelSummarizationStrategy;
import io.agentscope.spring.boot.admin.service.SessionOperations;
import io.agentscope.spring.boot.admin.service.SubagentTaskOperations;
import io.agentscope.spring.boot.admin.service.SummarizationStrategy;
import io.agentscope.spring.boot.admin.snapshot.SnapshotStore;
import io.agentscope.spring.boot.admin.subagent.SpringSubagentInventory;
import io.agentscope.spring.boot.admin.subagent.SubagentInventory;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AgentScope 管理／运维 starter 的 Spring Boot 自动配置。
 *
 * <p>启用规则：
 * <ul>
 *   <li>{@code agentscope.admin.enabled=true}：总开关，默认 false，避免仅因类路径存在就意外暴露管理端点。
 *   <li>类路径存在 {@link Agent}：防止缺少 agentscope-core 时启用。
 * </ul>
 *
 * <p>仅检测到 Servlet Web 应用时注册 Web 控制器（{@link ConditionalOnWebApplication}）。仅类路径存在 Actuator {@code Endpoint} 类型时注册 Actuator 端点。
 *
 * <p>【学习提示】启用管理功能、允许写操作、暴露 Actuator 端点是不同开关；这些装配条件不替代请求身份认证和网络访问控制。
 */
@AutoConfiguration
@ConditionalOnClass(Agent.class)
@ConditionalOnProperty(prefix = "agentscope.admin", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(AdminProperties.class)
@org.springframework.context.annotation.Import(AdminOpenApiConfiguration.class)
public class AgentscopeAdminAutoConfiguration {

    private static final Logger log =
            LoggerFactory.getLogger(AgentscopeAdminAutoConfiguration.class);

    /**
     * 默认 {@link AgentRegistry}，自动加入注入映射中发现的 {@link Agent} bean。原型作用域智能体随后新建的实例不会由这次初始化自动持续追踪；应用应在创建时自行调用 {@link AgentRegistry#register(Agent)}。
     */
    @Bean
    @ConditionalOnMissingBean
    public AgentRegistry agentscopeAgentRegistry(ObjectProvider<Map<String, Agent>> agentBeans) {
        InMemoryAgentRegistry registry = new InMemoryAgentRegistry();
        Map<String, Agent> beans = agentBeans.getIfAvailable();
        if (beans != null) {
            for (Map.Entry<String, Agent> entry : beans.entrySet()) {
                try {
                    registry.register(entry.getValue());
                } catch (RuntimeException e) {
                    log.warn(
                            "Failed to seed AgentRegistry from bean {}: {}",
                            entry.getKey(),
                            e.toString());
                }
            }
            log.info("Seeded AgentRegistry with {} singleton agent bean(s)", beans.size());
        }
        return registry;
    }

    @Bean
    @ConditionalOnMissingBean
    public AdminCommandRegistry agentscopeAdminCommandRegistry() {
        return new AdminCommandRegistry();
    }

    @Bean
    public BuiltinCommandRegistrar agentscopeBuiltinCommandRegistrar(
            AdminCommandRegistry registry, AdminProperties properties) {
        return new BuiltinCommandRegistrar(registry, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public AdminAuditLogger agentscopeAdminAuditLogger(
            AdminProperties properties, ApplicationEventPublisher publisher) {
        return new AdminAuditLogger(properties, publisher);
    }

    @Bean
    @ConditionalOnMissingBean
    public SummarizationStrategy agentscopeSummarizationStrategy() {
        return new ModelSummarizationStrategy();
    }

    @Bean
    @ConditionalOnMissingBean
    public SnapshotStore agentscopeSnapshotStore() {
        return new SnapshotStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public SessionOperations agentscopeSessionOperations(
            AgentRegistry registry,
            SummarizationStrategy summarizer,
            AdminProperties properties,
            SnapshotStore snapshots) {
        return new SessionOperations(registry, summarizer, properties, snapshots);
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentInventory agentscopeAgentInventory(
            AgentRegistry registry,
            ObjectProvider<Map<String, Toolkit>> toolkits,
            ObjectProvider<Map<String, Model>> models) {
        return new AgentInventory(registry, toolkits, models);
    }

    @Bean
    @ConditionalOnMissingBean
    public SubagentInventory agentscopeSubagentInventory(
            ObjectProvider<Map<String, SubagentEntry>> entryBeans,
            ObjectProvider<Map<String, SubagentDeclaration>> declarationBeans) {
        return new SpringSubagentInventory(entryBeans, declarationBeans);
    }

    @Bean
    @ConditionalOnMissingBean
    public SubagentTaskOperations agentscopeSubagentTaskOperations(
            ObjectProvider<TaskRepository> repoProvider) {
        return new SubagentTaskOperations(repoProvider);
    }

    @Bean
    @ConditionalOnMissingBean
    public MetricsRecorder agentscopeMetricsRecorder() {
        return new MetricsRecorder();
    }

    /**
     * 启动时通过 {@link AgentBase#addSystemHook(Hook)} 注册 {@link MetricsHook}，使之后构造的智能体贡献词元使用计数。
     *
     * <p>上下文关闭时注销钩子，避免重新加载 starter 后重复计数。
     */
    @Bean
    @ConditionalOnMissingBean
    public MetricsHookLifecycle agentscopeMetricsHookLifecycle(MetricsRecorder recorder) {
        return new MetricsHookLifecycle(new MetricsHook(recorder));
    }

    /**
 * 小型生命周期 bean，随应用上下文挂接／解除指标钩子。
 */
    public static final class MetricsHookLifecycle {

        private static final Logger logger = LoggerFactory.getLogger(MetricsHookLifecycle.class);

        private final MetricsHook hook;

        public MetricsHookLifecycle(MetricsHook hook) {
            this.hook = hook;
        }

        @PostConstruct
        public void register() {
            AgentBase.addSystemHook(hook);
            logger.info(
                    "Registered MetricsHook as AgentBase system hook (applies to agents constructed"
                            + " after this point)");
        }

        @PreDestroy
        public void unregister() {
            AgentBase.removeSystemHook(hook);
        }

        public MetricsHook hook() {
            return hook;
        }
    }

    /**
     * 数据平面 REST 接口，仅在 Servlet Web 应用中加载。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(name = "org.springframework.web.bind.annotation.RestController")
    public static class WebMvcConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public SessionAdminController agentscopeSessionAdminController(
                SessionOperations ops,
                ObjectProvider<AgentStateStore> sessions,
                AdminAuditLogger audit,
                AdminProperties properties) {
            return new SessionAdminController(ops, sessions, audit, properties);
        }

        @Bean
        @ConditionalOnMissingBean
        public SubagentTaskController agentscopeSubagentTaskController(
                SubagentTaskOperations ops, AdminAuditLogger audit, AdminProperties properties) {
            return new SubagentTaskController(ops, audit, properties);
        }
    }

    /**
     * 控制平面 Actuator 端点，仅类路径存在 Spring Boot Actuator {@code @Endpoint} 类型时加载。每个端点仍遵循标准 {@code management.endpoint.<id>.enabled} / {@code management.endpoints.web.exposure} 约定。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.boot.actuate.endpoint.annotation.Endpoint")
    public static class ActuatorEndpointsConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public AgentscopeStatusEndpoint agentscopeStatusEndpoint(
                AgentInventory inventory, AdminProperties properties) {
            return new AgentscopeStatusEndpoint(inventory, properties);
        }

        @Bean
        @ConditionalOnMissingBean
        public AgentscopeAgentsEndpoint agentscopeAgentsEndpoint(AgentInventory inventory) {
            return new AgentscopeAgentsEndpoint(inventory);
        }

        @Bean
        @ConditionalOnMissingBean
        public AgentscopeToolsEndpoint agentscopeToolsEndpoint(AgentInventory inventory) {
            return new AgentscopeToolsEndpoint(inventory);
        }

        @Bean
        @ConditionalOnMissingBean
        public AgentscopeModelsEndpoint agentscopeModelsEndpoint(AgentInventory inventory) {
            return new AgentscopeModelsEndpoint(inventory);
        }

        @Bean
        @ConditionalOnMissingBean
        public AgentscopeCommandsEndpoint agentscopeCommandsEndpoint(
                AdminCommandRegistry registry) {
            return new AgentscopeCommandsEndpoint(registry);
        }

        @Bean
        @ConditionalOnMissingBean
        public AgentscopeDoctorEndpoint agentscopeDoctorEndpoint(
                AdminProperties properties,
                AgentInventory inventory,
                ObjectProvider<AgentStateStore> sessions) {
            return new AgentscopeDoctorEndpoint(properties, inventory, sessions);
        }

        @Bean
        @ConditionalOnMissingBean
        public AgentscopeUsageEndpoint agentscopeUsageEndpoint(MetricsRecorder recorder) {
            return new AgentscopeUsageEndpoint(recorder);
        }

        @Bean
        @ConditionalOnMissingBean
        public AgentscopePermissionsEndpoint agentscopePermissionsEndpoint(AgentRegistry registry) {
            return new AgentscopePermissionsEndpoint(registry);
        }

        @Bean
        @ConditionalOnMissingBean
        public AgentscopeDrainEndpoint agentscopeDrainEndpoint(
                AdminProperties properties, AdminAuditLogger audit) {
            return new AgentscopeDrainEndpoint(properties, audit);
        }

        @Bean
        @ConditionalOnMissingBean
        public AgentscopeShutdownEndpoint agentscopeShutdownEndpoint(
                AdminProperties properties, AdminAuditLogger audit, ApplicationContext context) {
            return new AgentscopeShutdownEndpoint(properties, audit, context);
        }

        @Bean
        @ConditionalOnMissingBean
        public AgentscopeSubagentsEndpoint agentscopeSubagentsEndpoint(
                SubagentInventory inventory) {
            return new AgentscopeSubagentsEndpoint(inventory);
        }
    }
}
