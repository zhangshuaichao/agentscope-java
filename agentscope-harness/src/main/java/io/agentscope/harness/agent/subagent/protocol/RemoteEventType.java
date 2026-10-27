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
package io.agentscope.harness.agent.subagent.protocol;

/**
 * 通过 Agent Protocol 流式传输远程子智能体事件的稳定传输事件类型。
 *
 * <p>这些值必须跨版本保持向后兼容；旧客户端应忽略未知类型。
 */
public enum RemoteEventType {
    RUN_STARTED,
    RUN_FINISHED,
    RUN_ERROR,
    TEXT_DELTA,
    THINKING_DELTA,
    TOOL_CALL_START,
    TOOL_CALL_END,
    TOOL_RESULT,
    REQUIRE_CONFIRM,
    STATUS,

    /**
     * 携带没有专属传输类型的 {@link io.agentscope.core.event.AgentEvent}，
     * 完整序列化到 {@link RemoteAgentEvent#getPayload()}。仅在 {@code detail=verbose}
     * 时发出；早于此类型的客户端会跳过它。
     */
    AGENT_EVENT
}
