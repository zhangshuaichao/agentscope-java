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

import io.agentscope.core.state.AgentState;
import reactor.core.publisher.Mono;

/**
 * 会话按需上下文压缩，在控制平面发出 {@code compress} 时调用。
 *
 * <p>有意不内置压缩：它需要 LLM 和摘要策略，两者属于应用而非可观测性 SDK。仅在希望控制平面能压缩此智能体时提供实现；没有实现时，{@code compress} 明确报告不支持，而不是悄然空操作。
 *
 * <p>典型实现委托给 {@code agentscope-harness} 中的 {@code ConversationCompactor}：
 * <pre>{@code
 * SessionCompactor compactor = (sessionId, state) ->
 *         conversationCompactor
 *                 .compactIfNeeded(RuntimeContext.builder().sessionId(sessionId).build(),
 *                         state.getContext().stream()
 *                                 .filter(m -> m.getRole() != MsgRole.SYSTEM)
 *                                 .toList(),
 *                         compactionConfig, agentId, sessionId)
 *                 .doOnNext(replacement -> replacement.ifPresent(msgs -> {
 *                     state.contextMutable().clear();
 *                     state.contextMutable().addAll(msgs);
 *                 }))
 *                 .then();
 * }</pre>
 */
@FunctionalInterface
public interface SessionCompactor {

    /**
     * 原地压缩会话对话。
     *
     * @param sessionId 要压缩的会话
     * @param state 此会话的实时智能体状态；修改 {@code state.contextMutable()} 以应用压缩后历史
     */
    Mono<Void> compact(String sessionId, AgentState state);
}
