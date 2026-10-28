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

import io.agentscope.builder.web.config.BuilderE2bProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Builder <b>数据平面</b>入口。
 * <p>承载基于实时会话状态执行智能体轮次的功能：
 * <ul>
 * <li>{@code /api/sessions/{id}/events/**}：用户入站事件（消息、中断、工具确认、工具结果）、持久化事件日志、SSE 流。
 * <li>{@code /api/sessions/{id}/hands-stats}：Hands（沙箱租约）指标。
 * <li>{@code /api/environments/{id}/work/**}：环境 Worker 工作队列操作，使用环境密钥认证。
 * <li>{@code /api/environments/{id}/sessions/{sessionId}/pending-tools|tool-results|skills}：自行托管 Worker 的会话操作，使用环境密钥认证。
 * <li>{@code /agentscope/**}：控制平面的数据平面 HTTP 契约（信息、健康、会话、上下文、消息、压缩、终止），以及向 service-controlplane 自注册。
 * </ul>
 * <p>数据平面根据会话行固定的版本快照创建 {@code HarnessAgent}、加载状态并执行推理；不创建会话，也不管理静态目录定义，这些均由控制平面负责。
 */
@SpringBootApplication(
        scanBasePackages = {
            "io.agentscope.builder.control",
            "io.agentscope.builder.web.api",
            "io.agentscope.builder.web.auth",
            "io.agentscope.builder.web.catalog",
            "io.agentscope.builder.web.config",
            "io.agentscope.builder.web.coord",
            "io.agentscope.builder.web.managed",
            "io.agentscope.builder.web.persistence",
            "io.agentscope.builder.web.share",
            "io.agentscope.builder.web.toolbus",
            "io.agentscope.builder.web.workspace"
        })
@EnableConfigurationProperties(BuilderE2bProperties.class)
@EnableScheduling
public class DataApp {
    public static void main(String[] args) {
        SpringApplication.run(DataApp.class, args);
    }
}
