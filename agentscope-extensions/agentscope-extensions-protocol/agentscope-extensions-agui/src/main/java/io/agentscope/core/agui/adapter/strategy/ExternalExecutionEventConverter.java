/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.agui.adapter.strategy;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ExternalExecutionResultEvent;
import io.agentscope.core.event.RequireExternalExecutionEvent;
import java.util.Set;

/**
 * 在默认 AG-UI 流中抑制 ReActAgent 外部执行握手事件。
 *
 * <p>AG-UI 通过从最终 {@code AgentResultEvent} 派生的 {@code RUN_FINISHED.outcome} 呈现外部挂起工具调用。这些类型化 AgentScope 事件对直接消费 AgentEvent 的调用方仍有价值，但发送它们会重复原生中断约定。
 */
final class ExternalExecutionEventConverter implements AgentEventConverter {

    @Override
    public Set<Class<? extends AgentEvent>> eventTypes() {
        return Set.of(RequireExternalExecutionEvent.class, ExternalExecutionResultEvent.class);
    }

    @Override
    public void convert(AgentEvent event, AguiStreamContext context) {
        // 有意不执行任何操作。
    }
}
