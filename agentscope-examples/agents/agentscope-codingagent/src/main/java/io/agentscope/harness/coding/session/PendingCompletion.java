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
package io.agentscope.harness.coding.session;

/**
 * 尽力向请求方会话交付完成通知，类似 OpenClaw {@code announce}。{@link #announceText()} 适合作为父智能体下一轮内部上下文输入，不是子智能体的原始工具返回值。
 */
public record PendingCompletion(
        String runId,
        String childSessionKey,
        String requesterSessionKey,
        String status,
        String resultText,
        String error,
        long completedAtMs,
        String announceText) {}
