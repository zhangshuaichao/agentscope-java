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
package io.agentscope.claw2;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 本地 agentscope-claw 助手的入口。
 *
 * <p>启动 Spring Boot WebFlux 服务，提供：
 *
 * <ul>
 *   <li>{@code /api/agents/**}：智能体目录、逐智能体聊天（SSE）、工作区增删改查、会话收件箱和渠道绑定
 *   <li>{@code /api/channels}：已注册渠道目录
 *   <li>{@code /api/templates/**}：内置起始模板
 *   <li>{@code /**}：React SPA 静态资源，并将 SPA 路由回退到 {@code index.html}
 * </ul>
 *
 * <p>面向本地单用户部署，没有身份认证和多用户隔离。持久状态保存在 {@code ${claw.home}} 下（默认 {@code ~/.agentscope/claw}）。
 */
@SpringBootApplication
public class Claw2App {
    public static void main(String[] args) {
        SpringApplication.run(Claw2App.class, args);
    }
}
