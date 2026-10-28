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
package io.agentscope.spring.boot.admin.audit;

import java.time.Instant;
import java.util.Map;

/**
 * 一次管理操作的不可变审计记录。
 *
 * <p>由 {@code AdminAuditLogger} 输出为 SLF4J 日志，并在配置后作为 Spring {@code ApplicationEvent} 发布，使 Loki、RocketMQ、Nacos 审计等下游接收器可订阅。
 */
public record AdminAuditEvent(
        Instant timestamp,
        String commandId,
        String operator,
        String target,
        boolean writes,
        String outcome,
        Map<String, Object> attributes) {

    public AdminAuditEvent {
        if (commandId == null || commandId.isBlank()) {
            throw new IllegalArgumentException("commandId required");
        }
        timestamp = timestamp == null ? Instant.now() : timestamp;
        operator = operator == null ? "anonymous" : operator;
        target = target == null ? "" : target;
        outcome = outcome == null ? "ok" : outcome;
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
