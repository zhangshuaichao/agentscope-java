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
package io.agentscope.dataagent.web.workspace;

import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxClient;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxClientOptions;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 各逐智能体 {@code WorkspaceManager} 使用的逐租户工作区文件系统 Spring 配置。
 * <p>浏览器工作区控制器及智能体运行时，均通过 {@link UserSandboxRegistry} 拥有的
 * 逐 {@code (userId, agentId)} 活跃 Docker {@link
 * io.agentscope.harness.agent.sandbox.Sandbox} 读写。以每用户容器实现工作区隔离，
 * 避免旧 {@code CompositeFilesystem} 的其他路由回落到共享 {@code LocalFilesystem} 而跨租户泄漏内容。
 * <p>共享只读种子（AGENTS.md / skills/ / subagents/ / knowledge/）位于宿主
 * {@code ${cwd}/shared/}，通过注册表的 {@code __workspace_projection__} 项投影到新容器。
 * <p>多副本部署必须按 {@code userId} 使用粘性负载均衡，让用户流量落到同一 Pod。
 * 注册表仅在内存中，否则不同 Pod 会为同一用户创建独立容器。
 */
@Configuration
public class DataAgentWorkspaceConfig {

    private static final Logger log = LoggerFactory.getLogger(DataAgentWorkspaceConfig.class);

    @Value("${dataagent.sandbox.idle-ttl-min:15}")
    private long idleTtlMinutes;

    @Value("${dataagent.sandbox.eviction-poll-sec:60}")
    private long evictionPollSeconds;

    /** 独立解析与 {@code DataAgentConfig} 的 {@code cwd} 相同的属性，避免 {@link #userSandboxRegistry} 注入 {@code DataAgentBootstrap}；引导器本身依赖此注册表，反向注入会形成循环。 */
    @Value("${dataagent.workspace:}")
    private String workspaceDir;

    /** 默认 {@link SandboxClient} Bean，使用无参 {@link DockerSandboxClient}。运维可声明自身的 {@code SandboxClient<DockerSandboxClientOptions>} Bean 覆盖。 */
    @Bean
    @ConditionalOnMissingBean(SandboxClient.class)
    public SandboxClient<DockerSandboxClientOptions> sandboxClient() {
        log.info("Wiring default DockerSandboxClient for per-user workspace sandboxes");
        return new DockerSandboxClient();
    }

    @Bean
    public UserSandboxRegistry userSandboxRegistry(
            SandboxClient<DockerSandboxClientOptions> sandboxClient) {
        Path sharedRoot = resolveCwd().resolve("shared");
        Duration idleTtl = Duration.ofMinutes(idleTtlMinutes);
        Duration evictionPoll = Duration.ofSeconds(evictionPollSeconds);
        log.info(
                "DataAgent sandbox registry: hostWorkspaceRoot={}, idleTtl={}, evictionPoll={}",
                sharedRoot,
                idleTtl,
                evictionPoll);
        return new UserSandboxRegistry(sandboxClient, sharedRoot, idleTtl, evictionPoll);
    }

    private Path resolveCwd() {
        if (workspaceDir != null && !workspaceDir.isBlank()) {
            return Paths.get(workspaceDir).toAbsolutePath().normalize();
        }
        return Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }

    @Bean
    public WorkspaceManagerFactory workspaceManagerFactory(UserSandboxRegistry registry) {
        return new WorkspaceManagerFactory(registry);
    }
}
