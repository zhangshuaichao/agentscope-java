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
import java.util.Set;

/**
 * 将 AgentScope 事件转换为 AG-UI 协议事件的扩展点。
 *
 * <p>实现声明所处理的 {@link AgentEvent} 类型，并通过提供的 {@link AguiStreamContext} 发送 AG-UI 事件。可通过 {@code AguiAdapterConfig} 注册自定义转换器，扩展或覆盖内置语义映射。
 */
public interface AgentEventConverter {

    /**
     * 返回此转换器处理的 AgentScope 事件类型。
     *
     * @return 支持的 AgentScope 事件类型
     */
    Set<Class<? extends AgentEvent>> eventTypes();

    /**
     * 通过向流上下文发送 AG-UI 事件来转换源 AgentScope 事件。
     *
     * @param event 源 AgentScope 事件
     * @param context 用于发送 AG-UI 事件的流转换上下文
     */
    void convert(AgentEvent event, AguiStreamContext context);
}
