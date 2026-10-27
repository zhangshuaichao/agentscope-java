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
import io.agentscope.core.message.ToolUseBlock;
import java.util.List;

/**
 * 最近一轮推理产生的所有工具调用都被拒绝时，在行动阶段发出
 * （拒绝可能来自用户 HITL 确认或权限规则）。
 *
 * <p>观察 {@link io.agentscope.core.middleware.MiddlewareBase#onActing} 的中间件
 * 可以检测此事件，并发出原因设为
 * {@link io.agentscope.core.message.GenerateReason#ALL_TOOLS_DENIED} 的
 * {@link RequestStopEvent}，阻止智能体继续下一轮推理。
 *
 * <p>如果没有中间件发出停止请求，智能体会像以前一样继续推理（向后兼容）。
 */
public class AllToolsDeniedEvent extends AgentEvent {

    private final List<ToolUseBlock> deniedToolCalls;

    @JsonCreator
    public AllToolsDeniedEvent(
            @JsonProperty("id") String id,
            @JsonProperty("createdAt") String createdAt,
            @JsonProperty("deniedToolCalls") List<ToolUseBlock> deniedToolCalls) {
        super(id, createdAt);
        this.deniedToolCalls = deniedToolCalls != null ? List.copyOf(deniedToolCalls) : List.of();
    }

    public AllToolsDeniedEvent(List<ToolUseBlock> deniedToolCalls) {
        this.deniedToolCalls = deniedToolCalls != null ? List.copyOf(deniedToolCalls) : List.of();
    }

    @Override
    public AgentEventType getType() {
        return AgentEventType.ALL_TOOLS_DENIED;
    }

    /** 返回被拒绝的工具调用。 */
    public List<ToolUseBlock> getDeniedToolCalls() {
        return deniedToolCalls;
    }
}
