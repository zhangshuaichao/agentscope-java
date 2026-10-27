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

import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.event.AgentEvent;
import java.util.Set;

/**
 * 为未映射的 AgentScope 事件发送官方 AG-UI {@code RAW} 事件的回退转换器。
 *
 * <p>AG-UI 原始事件载荷使用协议的 {@code event} 和 {@code source} 字段。{@code rawEvent} 等基础事件属性交由配置的增强器处理。
 */
final class RawAgentEventConverter implements AgentEventConverter {

    /**
     * 返回空类型集合，因为此转换器作为注册表回退项选择。
     *
     * @return 不显式注册任何事件类型
     */
    @Override
    public Set<Class<? extends AgentEvent>> eventTypes() {
        return Set.of();
    }

    /**
     * 将未映射的 AgentScope 事件转换为 AG-UI 原始事件。
     *
     * @param event 源 AgentScope 事件
     * @param context 流转换上下文
     */
    @Override
    public void convert(AgentEvent event, AguiStreamContext context) {
        context.emit(
                new AguiEvent.Raw(
                        context.getThreadId(), context.getRunId(), event, event.getSource()));
    }
}
