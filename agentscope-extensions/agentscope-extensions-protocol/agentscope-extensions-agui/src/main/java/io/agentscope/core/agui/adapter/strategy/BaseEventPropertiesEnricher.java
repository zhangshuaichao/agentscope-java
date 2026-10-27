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
import io.agentscope.core.agui.event.AguiEvents;
import io.agentscope.core.event.AgentEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * 填充缺失事件时间戳的 AG-UI 基础事件属性增强器。
 *
 * <p>保留现有时间戳和原始事件载荷，不创建基础 {@code rawEvent}；希望暴露原始源数据的应用应提供自定义 {@link AguiEventEnricher}。
 */
public class BaseEventPropertiesEnricher implements AguiEventEnricher {

    /**
     * 为尚无时间戳的每个 AG-UI 事件添加时间戳。
     *
     * @param source 源 AgentScope 事件；框架创建的事件为 {@code null}
     * @param events 要增强的 AG-UI 事件
     * @param context 流转换上下文
     * @return 保留或补全 AG-UI 基础属性的事件
     */
    @Override
    public List<AguiEvent> enrich(
            AgentEvent source, List<AguiEvent> events, AguiStreamContext context) {
        List<AguiEvent> enriched = new ArrayList<>(events.size());
        for (AguiEvent event : events) {
            Long timestamp =
                    event.timestamp() != null ? event.timestamp() : System.currentTimeMillis();
            enriched.add(AguiEvents.withBaseProperties(event, timestamp, event.rawEvent()));
        }
        return List.copyOf(enriched);
    }
}
