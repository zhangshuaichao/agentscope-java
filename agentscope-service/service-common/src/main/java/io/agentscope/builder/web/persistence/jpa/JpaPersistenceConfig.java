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
package io.agentscope.builder.web.persistence.jpa;

import io.agentscope.builder.web.auth.UserStore;
import io.agentscope.builder.web.catalog.UserAgentDefinitionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * 装配基于 JPA 的 {@link UserStore} 和 {@link UserAgentDefinitionStore}，这是 Builder 随附的唯一持久化后端。
 * <h2>默认 DataSource</h2>
 * <p>默认 {@code application.yml} 将 {@code spring.datasource.url} 指向
 * {@code ${user.home}/.agentscope-builder/db} 下的嵌入式 H2 文件，刻意与
 * {@code builder.workspace} 分离，避免工作区卷包含目录表。单节点部署无需外部服务或额外设置。
 * <h2>切换至 MySQL / PostgreSQL</h2>
 * <p>启用随附的 {@code jdbc} Spring 配置环境，可将 DataSource 默认值切换为 MySQL 风格：
 *
 * <pre>{@code
 * --spring.profiles.active=jdbc
 *
 * # 或不启用此配置环境，直接覆盖单独的设置：
 * BUILDER_DB_URL=jdbc:postgresql://host:5432/agentscope_builder
 * BUILDER_DB_DRIVER=org.postgresql.Driver
 * BUILDER_DB_USER=...
 * BUILDER_DB_PASSWORD=...
 * BUILDER_JPA_DDL_AUTO=validate          # 由 Flyway / Liquibase 管理模式后使用
 * }</pre>
 *
 * <p>MySQL（{@code com.mysql:mysql-connector-j}）及 PostgreSQL（{@code org.postgresql:postgresql}）
 * JDBC 驱动均以运行时依赖随附；Hibernate 根据 {@code spring.datasource.url} 解析所用方言。
 */
@Configuration
@EnableJpaRepositories(basePackageClasses = JpaPersistenceConfig.class)
@EntityScan(basePackageClasses = JpaPersistenceConfig.class)
@EnableTransactionManagement
public class JpaPersistenceConfig {

    private static final Logger log = LoggerFactory.getLogger(JpaPersistenceConfig.class);

    @Bean
    public org.springframework.transaction.support.TransactionTemplate transactionTemplate(
            org.springframework.transaction.PlatformTransactionManager txManager) {
        return new org.springframework.transaction.support.TransactionTemplate(txManager);
    }

    @Bean
    public UserStore jpaUserStore(
            UserEntityRepository repository,
            @Value("${builder.seed-users:true}") boolean seedUsers) {
        log.info("Persistence: user store backed by JPA");
        return new JpaUserStore(repository, seedUsers);
    }

    @Bean
    public UserAgentDefinitionStore jpaUserAgentDefinitionStore(AgentEntityRepository repository) {
        log.info("Persistence: agent definition store backed by JPA");
        return new JpaUserAgentDefinitionStore(repository);
    }
}
