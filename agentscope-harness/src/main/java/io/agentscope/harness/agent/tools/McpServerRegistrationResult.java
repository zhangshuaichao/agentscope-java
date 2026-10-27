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
package io.agentscope.harness.agent.tools;

import java.time.Instant;
import java.util.Objects;

/**
 * 注册单个 MCP 服务器配置项的终态结果。
 *
 * @param serverName 配置的服务器名称；跳过无效配置项时可能为 {@code null}
 * @param transport 配置的传输方式；缺失或不可用时可能为 {@code null}
 * @param status 注册终态
 * @param completedAt 注册进入终态的时间
 * @param cause 失败或跳过的原因；注册成功时为 {@code null}
 */
public record McpServerRegistrationResult(
        String serverName, String transport, Status status, Instant completedAt, Throwable cause) {

    public McpServerRegistrationResult {
        Objects.requireNonNull(status, "status cannot be null");
        Objects.requireNonNull(completedAt, "completedAt cannot be null");
        if (status == Status.SUCCESS && cause != null) {
            throw new IllegalArgumentException("successful registration cannot have a cause");
        }
        if (status != Status.SUCCESS) {
            Objects.requireNonNull(cause, "non-successful registration must have a cause");
        }
    }

    /** MCP 服务器注册尝试的终态。 */
    public enum Status {
        SUCCESS,
        FAILED,
        SKIPPED
    }

    /** 创建注册成功结果。 */
    public static McpServerRegistrationResult success(String serverName, String transport) {
        return new McpServerRegistrationResult(
                serverName, transport, Status.SUCCESS, Instant.now(), null);
    }

    /** 创建注册失败结果。 */
    public static McpServerRegistrationResult failed(
            String serverName, String transport, Throwable cause) {
        return new McpServerRegistrationResult(
                serverName, transport, Status.FAILED, Instant.now(), cause);
    }

    /** 创建跳过注册的结果。 */
    public static McpServerRegistrationResult skipped(
            String serverName, String transport, Throwable cause) {
        return new McpServerRegistrationResult(
                serverName, transport, Status.SKIPPED, Instant.now(), cause);
    }
}
