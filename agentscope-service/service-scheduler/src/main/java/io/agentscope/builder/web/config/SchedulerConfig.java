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

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.builder.runtime.outbound.OutboundService;
import io.agentscope.builder.web.auth.InternalTokenAuthFilter;
import io.agentscope.builder.web.auth.UserStore;
import io.agentscope.builder.web.catalog.UserAgentDefinitionStore;
import io.agentscope.builder.web.coord.CoordinationStore;
import io.agentscope.builder.web.coord.JdbcCoordinationStore;
import io.agentscope.builder.web.managed.service.AgentVersionService;
import io.agentscope.builder.web.persistence.jpa.AgentVersionEntityRepository;
import io.agentscope.builder.web.persistence.jpa.CoordHitlTicketEntityRepository;
import io.agentscope.builder.web.persistence.jpa.CoordLeaseEntityRepository;
import io.agentscope.builder.web.persistence.jpa.CoordWorkItemEntityRepository;
import io.agentscope.builder.web.persistence.jpa.CoordWorkerHeartbeatEntityRepository;
import io.agentscope.builder.web.share.AgentAclService;
import io.agentscope.builder.web.share.AgentVisibilityResolver;
import io.agentscope.builder.web.share.JpaAgentVisibilityResolver;
import io.agentscope.extensions.jdbc.dialect.AbstractJdbcDialect;
import io.agentscope.extensions.jdbc.store.JdbcStore;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.gateway.ChannelManager;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Builder 调度平面的 Bean 组装。
 * <ul>
 * <li>{@link ObjectMapper}：忽略未知属性的宽松映射器，由通道运行时和会话桥接器在跨平面通信时共享。
 * <li>{@link ChannelManager}：管理所有活动即时消息适配器的 Harness 通道注册表。
 * <li>{@link OutboundService}：向已注册通道出站投递。
 * <li>{@link AgentVisibilityResolver}：共享数据库的纯 JPA 只读视图，供出站端点的 {@code AgentAccessGuard} 使用。
 * <li>{@code controlPlaneWebClient} / {@code dataPlaneWebClient}：使用共享内部令牌预认证的跨平面 HTTP 客户端。
 * </ul>
 */
@Configuration
public class SchedulerConfig {

    private static final Logger log = LoggerFactory.getLogger(SchedulerConfig.class);

    /**
     * 宽松映射器：容忍新版控制／数据平面新增字段。
     */
    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .findAndRegisterModules()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * Harness 通道注册表；启动时由 {@code SchedulerChannelRuntime} 填充。
     */
    @Bean
    public ChannelManager channelManager() {
        return new ChannelManager();
    }

    /**
     * HTTP 控制器共享的出站投递服务。
     */
    @Bean
    public OutboundService outboundService(ChannelManager channelManager) {
        return new OutboundService(channelManager);
    }

    /**
     * 共享数据库上的纯 JPA {@link AgentVisibilityResolver} 只读视图。调度器只读；控制平面拥有基于权威目录的解析器。
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
     * 由 Spring 管理的 {@link DataSource} 支撑的默认 {@link BaseStore}，与控制／数据平面一致。各平面连接同一 JDBC 数据库，因此定义文件跨平面可见。service-common 组件扫描路径中的 {@code BaseStoreDefinitionStore} 需要此 Bean。运维方可声明自己的 {@link BaseStore} 覆盖默认实现。
     */
    @Bean
    @ConditionalOnMissingBean(BaseStore.class)
    public BaseStore baseStore(DataSource dataSource) {
        // 方言构建器在一次流程中创建并验证数据库模式。
        AbstractJdbcDialect dialect = AbstractJdbcDialect.from(dataSource).build();
        return JdbcStore.builder(dataSource).dialect(dialect).build();
    }

    /**
     * Cron 触发租约的共享协调存储，也用于未来的调度协调。
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
     * 使用内部令牌预认证的控制平面 HTTP 客户端。
     */
    @Bean
    @Qualifier("controlPlaneWebClient")
    public WebClient controlPlaneWebClient(
            @Value("${builder.control-plane-url:http://localhost:8081}") String baseUrl,
            @Value("${builder.internal-token:${BUILDER_INTERNAL_TOKEN:}}") String internalToken) {
        return internalClient(baseUrl, internalToken);
    }

    /**
     * 使用内部令牌预认证的数据平面 HTTP 客户端。
     */
    @Bean
    @Qualifier("dataPlaneWebClient")
    public WebClient dataPlaneWebClient(
            @Value("${builder.data-plane-url:http://localhost:8082}") String baseUrl,
            @Value("${builder.internal-token:${BUILDER_INTERNAL_TOKEN:}}") String internalToken) {
        return internalClient(baseUrl, internalToken);
    }

    private static WebClient internalClient(String baseUrl, String internalToken) {
        WebClient.Builder builder = WebClient.builder().baseUrl(baseUrl);
        if (internalToken != null && !internalToken.isBlank()) {
            builder.defaultHeader(InternalTokenAuthFilter.INTERNAL_TOKEN_HEADER, internalToken);
        }
        return builder.build();
    }
}
