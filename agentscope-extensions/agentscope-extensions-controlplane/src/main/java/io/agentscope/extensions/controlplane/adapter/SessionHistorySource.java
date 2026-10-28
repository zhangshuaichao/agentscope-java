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
package io.agentscope.extensions.controlplane.adapter;

import io.agentscope.extensions.controlplane.model.MessagePage;
import java.util.List;
import java.util.Optional;

/**
 * {@code GET /agentscope/sessions/{id}/messages} 的可选完整历史读取器。
 *
 * <p>AgentScope Harness 持久保存仅追加的 {@code .log.jsonl}；使用 Harness 的 BYO 应用可接入读取它的数据源，包括通过分布式 {@code AbstractFilesystem} 读取。未提供时，适配器回退到实时 {@code AgentState} 上下文缓冲区。
 */
@FunctionalInterface
public interface SessionHistorySource {

    /**
     * @param sessionId 会话键
     * @param userId 首次观察会话时记录的用户槽位，可为空
     * @return 完整消息列表，尚未分页；无日志时为空
     */
    Optional<List<MessagePage.MessageItem>> loadMessages(String sessionId, String userId);
}
