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

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 确保 Hibernate 验证／更新表之前数据平面 JDBC 模式 {@code dp} 已存在。适用于 Postgres 和 H2；驱动拒绝 {@code CREATE SCHEMA} 时忽略。
 */
@Component
public class DpSchemaInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DpSchemaInitializer.class);

    private final DataSource dataSource;

    public DpSchemaInitializer(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS dp");
            dropLegacyHitlConstraint(statement);
            log.info("Ensured JDBC schema 'dp' exists");
        } catch (SQLException ex) {
            log.warn(
                    "Could not create schema 'dp' (may be unsupported by driver): {}",
                    ex.getMessage());
        }
    }

    private static void dropLegacyHitlConstraint(Statement statement) {
        try {
            statement.execute(
                    "ALTER TABLE dp.builder_coord_hitl DROP CONSTRAINT IF EXISTS"
                            + " uk_builder_coord_hitl_tool");
            return;
        } catch (SQLException ignored) {
            // MySQL 将唯一约束表示为索引。
        }
        try {
            statement.execute(
                    "ALTER TABLE dp.builder_coord_hitl DROP INDEX uk_builder_coord_hitl_tool");
        } catch (SQLException ignored) {
            // 全新安装没有旧约束或表，由 Hibernate 负责创建。
        }
    }
}
