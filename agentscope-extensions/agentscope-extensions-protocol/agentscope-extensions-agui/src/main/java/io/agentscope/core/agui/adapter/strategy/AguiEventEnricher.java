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
import java.util.List;

/**
 * 在语义转换后增强 AG-UI 事件。
 */
public interface AguiEventEnricher {

    /**
     * 增强已转换的 AG-UI 事件。
     *
     * @param source 源 AgentEvent；框架生命周期事件为 null
     * @param events 已转换的 AG-UI 事件
     * @param context 流上下文
     * @return 增强后的 AG-UI 事件
     */
    List<AguiEvent> enrich(AgentEvent source, List<AguiEvent> events, AguiStreamContext context);
}
