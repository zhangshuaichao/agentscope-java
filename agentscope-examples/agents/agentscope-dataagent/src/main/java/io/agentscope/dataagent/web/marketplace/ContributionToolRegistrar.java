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
package io.agentscope.dataagent.web.marketplace;

import io.agentscope.dataagent.runtime.DataAgentBootstrap;
import io.agentscope.dataagent.web.workspace.WorkspaceManagerFactory;
import io.agentscope.harness.agent.HarnessAgent;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 启动时将单例 {@link ContributeWorkspaceTool} 注册到内置 {@code data-agent} 主智能体工具包，使其可通过 {@code contribute_to_workspace} 提交技能/子智能体/记忆片段的共享工作区提案。<p>刻意只为主智能体注册，用户自定义智能体默认不获得此工具；若政策改变，可在此扩展注册范围。<p>在 {@link DataAgentBootstrap} 构建全部智能体后执行，形式类似通过 {@code configureAllAgents} 配置的 {@code ToolNotificationHook}，但延后到引导之后，因为工具依赖 Spring 管理的 {@link MarketContributionService}（JPA 仓库装配），不适合在引导构建器 lambda 中访问。 */
@Component
public class ContributionToolRegistrar {

    private static final Logger log = LoggerFactory.getLogger(ContributionToolRegistrar.class);

    private final DataAgentBootstrap bootstrap;
    private final MarketContributionService service;
    private final WorkspaceManagerFactory workspaceFactory;

    public ContributionToolRegistrar(
            DataAgentBootstrap bootstrap,
            MarketContributionService service,
            WorkspaceManagerFactory workspaceFactory) {
        this.bootstrap = bootstrap;
        this.service = service;
        this.workspaceFactory = workspaceFactory;
    }

    @PostConstruct
    public void registerContributionTool() {
        HarnessAgent main = bootstrap.agents().get(bootstrap.loadedConfig().getMain());
        if (main == null) {
            // 没有显式主智能体时，回退到 agents().values().iterator().next()；
            // DataAgentBootstrap 保证构建时至少存在一个智能体。
            main =
                    bootstrap.agents().values().stream()
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "No agents available to register"
                                                            + " contribute_to_workspace onto"));
        }
        try {
            main.getDelegate()
                    .getToolkit()
                    .registerTool(new ContributeWorkspaceTool(service, workspaceFactory));
            log.info(
                    "Registered contribute_to_workspace tool onto main agent '{}'", main.getName());
        } catch (RuntimeException e) {
            // 降级处理：缺失工具不能阻止应用启动；
            // 即使智能体端便捷工具注册失败，用户 REST 端点
            // /api/me/contributions 仍可正常使用。
            log.warn(
                    "Failed to register contribute_to_workspace tool onto main agent: {}",
                    e.getMessage());
        }
    }
}
