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
package io.agentscope.spring.boot.admin.command;

import java.util.List;

/**
 * 此 starter 暴露的管理操作的自描述记录。
 *
 * <p>统一注册表用同一套元数据支持 REST 路由、Actuator 端点及机器可读的 {@code /actuator/agentscope-commands} 目录，供 CLI、Studio、Kubernetes operator 使用。
 *
 * @param id 稳定内部 ID，例如 {@code session.compact}
 * @param title 便于人阅读的标签，例如“压缩会话”
 * @param category 粗粒度分组，例如 {@code AgentStateStore}、{@code Agent}、{@code System}
 * @param plane DATA 表示逐会话 REST；CONTROL 表示 Actuator
 * @param httpMethod 访问命令使用的 HTTP 方法，例如 {@code GET}、{@code POST}
 * @param httpPath 从应用根路径起的完整路由，例如 {@code /v1/admin/sessions/{sessionId}:compact}
 * @param aliases 额外显示别名，CLI 风格，例如 {@code summarize}
 * @param writes 命令是否修改状态；未设 {@code agentscope.admin.write-enabled=true} 时禁用
 * @param idempotent 重复调用是否安全
 * @param description 目录显示的简短单行说明
 */
public record AdminCommand(
        String id,
        String title,
        String category,
        CommandPlane plane,
        String httpMethod,
        String httpPath,
        List<String> aliases,
        boolean writes,
        boolean idempotent,
        String description) {

    public AdminCommand {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("AdminCommand.id must not be blank");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("AdminCommand.title must not be blank");
        }
        if (plane == null) {
            throw new IllegalArgumentException("AdminCommand.plane must not be null");
        }
        if (httpMethod == null || httpMethod.isBlank()) {
            throw new IllegalArgumentException("AdminCommand.httpMethod must not be blank");
        }
        if (httpPath == null || httpPath.isBlank()) {
            throw new IllegalArgumentException("AdminCommand.httpPath must not be blank");
        }
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        category = category == null ? "Misc" : category;
        description = description == null ? "" : description;
    }
}
