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
package io.agentscope.builder;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Builder <b>网关平面</b>入口。
 * <p>轻量 Spring Cloud Gateway（Server WebFlux）边缘层：纯路径路由，无业务逻辑、无数据库，也不自行执行安全认证；认证由目标平面强制实施（用户 API 使用 JWT，跨平面 API 使用内部令牌）。
 * <ul>
 * <li>会话<b>轮次</b>流量（事件、SSE 流、Hands 统计）及 Worker HTTP API 路由到<b>数据平面</b>。
 * <li>{@code /api/**} 下其余接口（目录、环境、记忆存储、Vault、部署、会话生命周期）及控制台 SPA 路由到<b>控制平面</b>。
 * <li>跨平面流量（{@code /api/internal/**}、{@code /api/outbound/**}）由平面直接互调；若到达网关，会落到控制平面，后者仍要求内部令牌或返回 404，不因网关转发而公开这些操作。
 * </ul>
 */
@SpringBootApplication
public class GatewayApp {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApp.class, args);
    }
}
