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
package io.agentscope.spring.boot.admin.openapi;

import io.agentscope.spring.boot.admin.command.AdminCommand;
import io.agentscope.spring.boot.admin.command.AdminCommandRegistry;
import io.agentscope.spring.boot.admin.command.CommandPlane;
import io.agentscope.spring.boot.admin.properties.AdminProperties;
import io.swagger.v3.oas.models.ExternalDocumentation;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * <em>仅当类路径存在 springdoc 时</em>，将 admin starter 接入 springdoc-openapi。
 *
 * <p>应用添加 {@code springdoc-openapi-starter-webmvc-ui} 依赖即可启用（此处声明为 {@code optional}）。存在时：
 * <ul>
 *   <li>名为 {@code agentscope-admin} 的 {@link GroupedOpenApi} 仅暴露管理 REST 路由，在 Swagger UI 单独显示。
 *   <li>未提供 {@link OpenAPI} bean 时创建默认实例，设置标题、描述和版本，并为每个 {@code AdminCommand} 的 {@code category} 注册一个 OpenAPI {@link Tag}，按 AgentStateStore / Agent / System 分组。
 * </ul>
 *
 * <p>由 {@link io.agentscope.spring.boot.admin.AgentscopeAdminAutoConfiguration} 惰性加载；使用 {@link ConditionalOnClass} 检查 springdoc 类型，避免缺少 springdoc 时加载失败。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass({GroupedOpenApi.class, OpenAPI.class})
public class AdminOpenApiConfiguration {

    /**
 * 将全部管理路由归入独立的 Swagger UI 定义。
 */
    @Bean
    public GroupedOpenApi agentscopeAdminOpenApi(AdminProperties properties) {
        String base = properties.getBasePath();
        return GroupedOpenApi.builder()
                .group("agentscope-admin")
                .displayName("AgentScope Admin")
                .pathsToMatch(base + "/**")
                .pathsToExclude(base + "/internal/**")
                .build();
    }

    /**
     * 应用未声明时提供默认顶层 {@link OpenAPI} bean。标签由 {@link AdminCommandRegistry} 初始化，使 AgentStateStore/Agent/System 各类别在 Swagger UI 拥有独立分组。
     */
    @Bean
    @ConditionalOnMissingBean(OpenAPI.class)
    public OpenAPI agentscopeAdminOpenApiDefinition(
            AdminProperties properties, AdminCommandRegistry registry) {
        Set<String> categories = new LinkedHashSet<>();
        for (AdminCommand cmd : registry.list()) {
            if (cmd.plane() == CommandPlane.DATA) {
                categories.add(cmd.category());
            }
        }
        List<Tag> tags = new ArrayList<>();
        for (String c : categories) {
            tags.add(new Tag().name(c).description("AgentScope admin — " + c));
        }
        return new OpenAPI()
                .info(
                        new Info()
                                .title("AgentScope Admin API")
                                .version("v1")
                                .description(
                                        "Per-session admin / ops actions over agentscope-java"
                                            + " agents. Discover the full command catalog at GET"
                                            + " /actuator/agentscope-commands."))
                .externalDocs(
                        new ExternalDocumentation()
                                .description("Plan & gap analysis")
                                .url(
                                        "https://github.com/agentscope-ai/agentscope-java/blob/main/ADMIN_OPS_API_PLAN.md"))
                .addServersItem(
                        new Server().url(properties.getBasePath()).description("Admin base path"))
                .tags(tags);
    }
}
