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
package io.agentscope.builder.web.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.builder.web.auth.UserStore;
import io.agentscope.builder.web.catalog.UserAgentDefinitionStore;
import io.agentscope.builder.web.coord.CoordinationStore;
import io.agentscope.builder.web.coord.JdbcCoordinationStore;
import io.agentscope.builder.web.managed.service.AgentVersionService;
import io.agentscope.builder.web.managed.service.DeletedSessionRegistry;
import io.agentscope.builder.web.persistence.jpa.AgentStateEntityRepository;
import io.agentscope.builder.web.persistence.jpa.AgentVersionEntityRepository;
import io.agentscope.builder.web.persistence.jpa.CoordHitlTicketEntityRepository;
import io.agentscope.builder.web.persistence.jpa.CoordLeaseEntityRepository;
import io.agentscope.builder.web.persistence.jpa.CoordWorkItemEntityRepository;
import io.agentscope.builder.web.persistence.jpa.CoordWorkerHeartbeatEntityRepository;
import io.agentscope.builder.web.persistence.jpa.JpaAgentStateStore;
import io.agentscope.builder.web.share.AgentAclService;
import io.agentscope.builder.web.share.AgentVisibilityResolver;
import io.agentscope.builder.web.share.JpaAgentVisibilityResolver;
import io.agentscope.builder.web.workspace.SharedWorkspacePaths;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.extensions.jdbc.dialect.AbstractJdbcDialect;
import io.agentscope.extensions.jdbc.store.JdbcStore;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Builder 数据平面的 Bean 组装。
 * <ul>
 * <li>{@link ObjectMapper}：Jackson 自动配置未提供实例时的共享 JSON 辅助组件（WebFlux / Boot 4）。
 * <li>{@link Model}：快照未固定模型名称时使用的可选默认 DashScope 模型；未配置 API 密钥时跳过，使 {@code Optional<Model>} 注入点收到 {@code Optional.empty()}。
 * <li>{@link BaseStore}：基于 JDBC 的共享键值存储。控制平面通过它写入智能体定义文件（技能、子智能体、镜像），数据平面物化智能体时读取共享数据库中的相同命名空间。
 * <li>{@link AgentStateStore}：基于 JPA 的智能体状态持久化，供所有构建的 {@code HarnessAgent} 使用。
 * <li>{@link CoordinationStore}：与调度器共享的 JDBC 协调表（轮次租约、HITL 票据、Hands 工作队列）。
 * <li>{@link SharedWorkspacePaths}：解析平台共享工作区根目录，与控制平面布局一致。
 * </ul>
 */
@Configuration
public class DataPlaneConfig {

    private static final Logger log = LoggerFactory.getLogger(DataPlaneConfig.class);

    @Value("${builder.dashscope.api-key:${claw.dashscope.api-key:}}")
    private String dashscopeApiKey;

    @Value("${builder.dashscope.model-name:${claw.dashscope.model-name:qwen-max}}")
    private String dashscopeModelName;

    @Value("${builder.dashscope.stream:${claw.dashscope.stream:true}}")
    private boolean dashscopeStream;

    /**
     * 当 Jackson 自动配置未提供实例（WebFlux / Boot 4）时，确保 {@code ManagedJsonHelper} 及 API 层有共享 {@link ObjectMapper} 可用。
     */
    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    public ObjectMapper objectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    /**
     * 配置了 {@code builder.dashscope.api-key}（或旧版 {@code claw.dashscope.api-key}）且不存在其他 {@link Model} Bean 时，创建 {@link DashScopeChatModel} Bean。
     */
    @Bean
    @ConditionalOnMissingBean(Model.class)
    @ConditionalOnExpression("'${builder.dashscope.api-key:${claw.dashscope.api-key:}}' != ''")
    public Model dashscopeModel() {
        log.info("Building DashScopeChatModel: model={}", dashscopeModelName);
        return DashScopeChatModel.builder()
                .apiKey(dashscopeApiKey)
                .modelName(dashscopeModelName)
                .stream(dashscopeStream)
                .build();
    }

    /**
     * 由 Spring 管理的 {@link DataSource} 支撑的默认 {@link BaseStore}。所有平面连接相同 JDBC 数据库，因此可读取控制平面写入的定义文件。运维方可声明自己的 {@link BaseStore} Bean 覆盖默认实现。
     */
    @Bean
    @ConditionalOnMissingBean(BaseStore.class)
    public BaseStore baseStore(DataSource dataSource) {
        log.info("Wiring default JdbcStore-backed BaseStore on the Spring DataSource");
        // 方言构建器在一次流程中创建并验证数据库模式。
        AbstractJdbcDialect dialect = AbstractJdbcDialect.from(dataSource).build();
        return JdbcStore.builder(dataSource).dialect(dialect).build();
    }

    /**
     * 日志数据使用现有分布式工作区存储，位于临时沙箱之外。
     */
    @Bean
    @ConditionalOnMissingBean(io.agentscope.core.session.SessionLogStore.class)
    public io.agentscope.core.session.SessionLogStore sessionLogStore(BaseStore store) {
        return new io.agentscope.harness.agent.session.WorkspaceSessionLogStore(
                new io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem(
                        store, java.util.List.of("runtime", "sessions")));
    }

    /**
     * 此节点构建的所有智能体共用基于 JPA 的 {@link AgentStateStore}。
     */
    @Bean
    @ConditionalOnMissingBean(AgentStateStore.class)
    public AgentStateStore agentStateStore(
            AgentStateEntityRepository repository, DeletedSessionRegistry deletedSessions) {
        log.info("Wiring JpaAgentStateStore on the Spring DataSource / JPA schema");
        return new JpaAgentStateStore(repository, deletedSessions);
    }

    /**
     * 持久化于共享 Builder 数据库的默认多副本协调存储（轮次租约、HITL 票据、Hands 工作队列）。数据平面写入轮次租约与工作项，调度器轮询相同表。
     */
    @Bean
    @ConditionalOnMissingBean(CoordinationStore.class)
    public CoordinationStore coordinationStore(
            CoordLeaseEntityRepository leaseRepository,
            CoordHitlTicketEntityRepository hitlRepository,
            CoordWorkItemEntityRepository workRepository,
            CoordWorkerHeartbeatEntityRepository workerRepository,
            TransactionTemplate transactionTemplate) {
        log.info("Wiring default JdbcCoordinationStore on the Spring DataSource / JPA schema");
        return new JdbcCoordinationStore(
                leaseRepository,
                hitlRepository,
                workRepository,
                workerRepository,
                transactionTemplate);
    }

    /**
     * 共享数据库上的纯 JPA {@link AgentVisibilityResolver} 只读视图：全局智能体来自物化版本行，自有／共享用户智能体来自定义存储与 ACL 授权。控制平面改用其基于目录的解析器。
     */
    @Bean
    @ConditionalOnMissingBean(AgentVisibilityResolver.class)
    public AgentVisibilityResolver agentVisibilityResolver(
            AgentVersionEntityRepository versionRepository,
            AgentVersionService versionService,
            UserAgentDefinitionStore userAgentDefinitionStore,
            UserStore userStore,
            AgentAclService aclService) {
        return new JpaAgentVisibilityResolver(
                versionRepository, versionService, userAgentDefinitionStore, userStore, aclService);
    }

    /**
     * 共享工作区路径解析器。使用与控制平面相同的平台根目录（默认 {@code ~/.agentscope/builder/workspace}），使各平面按智能体组织的数据路径一致。
     */
    @Bean
    @ConditionalOnMissingBean(SharedWorkspacePaths.class)
    public SharedWorkspacePaths sharedWorkspacePaths(
            @Value("${builder.workspace-root:${user.home}/.agentscope/builder/workspace}")
                    String workspaceRoot) {
        Path root = Paths.get(workspaceRoot).toAbsolutePath().normalize();
        log.info("Builder shared workspace root: {}", root);
        return new SharedWorkspacePaths(root);
    }
}
