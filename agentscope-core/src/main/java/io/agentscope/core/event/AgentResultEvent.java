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
package io.agentscope.core.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.agentscope.core.message.Msg;

/**
 * 智能体成功完成一次调用的处理时发出，携带最终结果消息。
 *
 * <p>此事件是智能体事件流的一部分（包括 {@code call()} 和
 * {@code streamEvents()} 路径），紧接在 {@link AgentEndEvent} 之前发出。
 * {@code streamEvents()} 的调用方可以筛选此事件，直接从事件流取得最终
 * {@link Msg}，无须另外订阅返回值 {@code Mono<Msg>}。
 *
 * <p>{@code call()} 在内部使用此事件从共享的 {@code buildAgentStream()}
 * 核心中提取结果，确保两条路径都经过同一条 {@code onAgent} 中间件链。
 */
public class AgentResultEvent extends AgentEvent {

    private final Msg result;

    public AgentResultEvent(Msg result) {
        this.result = result;
    }

    @JsonCreator
    public AgentResultEvent(
            @JsonProperty("id") String id,
            @JsonProperty("createdAt") String createdAt,
            @JsonProperty("result") Msg result) {
        super(id, createdAt);
        this.result = result;
    }

    @Override
    public AgentEventType getType() {
        return AgentEventType.AGENT_RESULT;
    }

    public Msg getResult() {
        return result;
    }
}
