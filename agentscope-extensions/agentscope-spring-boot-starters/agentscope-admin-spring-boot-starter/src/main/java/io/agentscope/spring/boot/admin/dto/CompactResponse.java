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
package io.agentscope.spring.boot.admin.dto;

/**
 * {@code POST /v1/admin/sessions/{id}:compact} 的响应体。
 *
 * @param sessionId 目标会话
 * @param messagesBefore 调用前 {@code AgentState.context} 大小
 * @param messagesAfter 截断后大小
 * @param summaryLengthBefore 旧滚动摘要字符数
 * @param summaryLengthAfter 新滚动摘要字符数
 */
public record CompactResponse(
        String sessionId,
        int messagesBefore,
        int messagesAfter,
        int summaryLengthBefore,
        int summaryLengthAfter) {}
