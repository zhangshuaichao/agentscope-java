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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 将 AgentScope {@link AgentEvent} 实例映射为 AG-UI 协议事件的注册表。
 *
 * <p>先注册内置转换器，再注册自定义转换器，因此自定义转换器可覆盖相同 {@link AgentEvent} 类型的映射。事件增强器在转换后运行，可添加跨事件的 AG-UI 基础属性。
 */
public class AgentEventConverterRegistry {

    private final Map<Class<? extends AgentEvent>, AgentEventConverter> converters;
    private final RawAgentEventConverter rawConverter = new RawAgentEventConverter();
    private final SubagentEventConverter subagentConverter = new SubagentEventConverter();
    private final List<AguiEventEnricher> enrichers;
    private final boolean emitSubagentEventsAsNative;

    public AgentEventConverterRegistry() {
        this(List.of(), List.of(), false);
    }

    /**
     * 使用内置转换器、自定义转换器和事件增强器创建注册表。
     *
     * @param customConverters 在内置转换器之后注册的转换器
     * @param enrichers 每次转换后应用的增强器
     */
    public AgentEventConverterRegistry(
            List<AgentEventConverter> customConverters, List<AguiEventEnricher> enrichers) {
        this(customConverters, enrichers, false);
    }

    /**
     * 使用内置转换器、自定义转换器、增强器和子智能体呈现模式创建注册表。
     *
     * @param customConverters 在内置转换器之后注册的转换器
     * @param enrichers 每次转换后应用的增强器
     * @param emitSubagentEventsAsNative 为 {@code true} 时，子事件与父事件使用相同转换器；为 {@code false}（默认）时，{@code source != null} 的事件变为 {@code subagent.*} CUSTOM / RAW 事件
     */
    public AgentEventConverterRegistry(
            List<AgentEventConverter> customConverters,
            List<AguiEventEnricher> enrichers,
            boolean emitSubagentEventsAsNative) {
        Map<Class<? extends AgentEvent>, AgentEventConverter> map = new LinkedHashMap<>();
        register(map, new AgentLifecycleEventConverter());
        register(map, new PermissionConfirmEventConverter());
        register(map, new ExternalExecutionEventConverter());
        register(map, new TextBlockEventConverter());
        register(map, new ThinkingBlockEventConverter());
        register(map, new ToolCallEventConverter());
        register(map, new ToolResultEventConverter());
        register(map, new ModelCallUsageEventConverter());
        register(map, new CustomAgentEventConverter());
        if (customConverters != null) {
            for (AgentEventConverter converter : customConverters) {
                register(map, Objects.requireNonNull(converter, "converter cannot be null"));
            }
        }
        this.converters = Map.copyOf(map);
        this.enrichers = enrichers != null ? List.copyOf(enrichers) : List.of();
        this.emitSubagentEventsAsNative = emitSubagentEventsAsNative;
    }

    /**
     * 将一个 AgentScope 事件转换为零个或多个 AG-UI 事件。
     *
     * @param event 源 AgentScope 事件
     * @param context 流转换上下文
     * @return 转换并增强后的 AG-UI 事件
     */
    public List<AguiEvent> convert(AgentEvent event, AguiStreamContext context) {
        Objects.requireNonNull(event, "event cannot be null");
        Objects.requireNonNull(context, "context cannot be null");
        context.beginEvent();
        if (!emitSubagentEventsAsNative
                && event.getSource() != null
                && !event.getSource().isBlank()) {
            subagentConverter.convert(event, context);
        } else {
            converters.getOrDefault(event.getClass(), rawConverter).convert(event, context);
        }
        return enrich(event, context.drainEvents(), context);
    }

    /**
     * 按注册顺序将配置的增强器应用于 AG-UI 事件。
     *
     * @param source 源 AgentScope 事件；框架创建的事件为 {@code null}
     * @param events 要增强的 AG-UI 事件
     * @param context 流转换上下文
     * @return 增强后的 AG-UI 事件
     */
    public List<AguiEvent> enrich(
            AgentEvent source, List<AguiEvent> events, AguiStreamContext context) {
        Objects.requireNonNull(events, "events cannot be null");
        Objects.requireNonNull(context, "context cannot be null");
        List<AguiEvent> enriched = List.copyOf(events);
        for (AguiEventEnricher enricher : enrichers) {
            enriched =
                    List.copyOf(
                            Objects.requireNonNull(
                                    enricher.enrich(source, enriched, context),
                                    "enriched events cannot be null"));
        }
        return enriched;
    }

    private static void register(
            Map<Class<? extends AgentEvent>, AgentEventConverter> map,
            AgentEventConverter converter) {
        for (Class<? extends AgentEvent> eventType : converter.eventTypes()) {
            map.put(eventType, converter);
        }
    }
}
