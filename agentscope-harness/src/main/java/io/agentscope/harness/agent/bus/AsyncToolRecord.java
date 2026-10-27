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
package io.agentscope.harness.agent.bus;

import java.time.Instant;

/**
 * 跟踪转入后台的异步工具执行的生命周期。
 * @param id 本次异步执行的唯一标识符
 * @param sessionId 发起工具调用的会话
 * @param toolName 执行的工具名称
 * @param toolCallId 模型原始 tool_call ID
 * @param status 当前状态：RUNNING、COMPLETED、FAILED 或 TIMEOUT
 * @param createdAt 注册异步执行的时间
 */
public record AsyncToolRecord(
        String id,
        String sessionId,
        String toolName,
        String toolCallId,
        String status,
        Instant createdAt) {

    public static final String RUNNING = "RUNNING";
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";
    public static final String TIMEOUT = "TIMEOUT";
}
