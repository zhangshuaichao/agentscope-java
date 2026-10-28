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
package io.agentscope.examples.documentation2.copilotkit.service;

import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.adapter.strategy.AgentEventConverter;
import io.agentscope.core.agui.adapter.strategy.AgentEventConverterRegistry;
import io.agentscope.core.agui.adapter.strategy.AguiEventEnricher;
import io.agentscope.core.agui.adapter.strategy.AguiStreamContext;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.examples.documentation2.copilotkit.service.InMemoryAgentEventStore.StoredAgentEvent;
import io.agentscope.examples.documentation2.copilotkit.workbench.WorkbenchAguiEventConverter;
import io.agentscope.spring.boot.agui.common.AguiProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 将保存的 {@link io.agentscope.core.event.AgentEvent} 重放为 AG-UI 帧。
 * <p>使用与实时适配器相同的转换器／增强器（排除保存增强器），按历史运行重置工作台快照基线，使 {@code STATE_SNAPSHOT} / {@code STATE_DELTA} 与全新转换一致。
 */
@Component
public final class AgentEventAguiReplayer {

    private final InMemoryAgentEventStore eventStore;
    private final AguiProperties properties;
    private final List<AgentEventConverter> eventConverters;
    private final List<AguiEventEnricher> replayEnrichers;
    private final WorkbenchAguiEventConverter workbenchConverter;

    public AgentEventAguiReplayer(
            InMemoryAgentEventStore eventStore,
            AguiProperties properties,
            ObjectProvider<AgentEventConverter> eventConvertersProvider,
            ObjectProvider<AguiEventEnricher> eventEnrichersProvider,
            ObjectProvider<WorkbenchAguiEventConverter> workbenchConverterProvider) {
        this.eventStore = eventStore;
        this.properties = properties;
        this.eventConverters = eventConvertersProvider.orderedStream().toList();
        this.replayEnrichers =
                eventEnrichersProvider
                        .orderedStream()
                        .filter(enricher -> !(enricher instanceof PersistingAgentEventEnricher))
                        .toList();
        this.workbenchConverter = workbenchConverterProvider.getIfAvailable();
    }

    /**
     * 将线程已保存 AgentEvent 投影为 AG-UI 事件。
     * @param threadId 待重放线程
     * @param connectInput 可选连接载荷，提供转换器使用的工具／状态
     * @return 按转换顺序排列的事件；无历史时为空
     */
    public List<AguiEvent> replay(String threadId, RunAgentInput connectInput) {
        List<StoredAgentEvent> history = eventStore.snapshot(threadId);
        if (history.isEmpty()) {
            return List.of();
        }

        AguiAdapterConfig config = replayConfig();
        AgentEventConverterRegistry registry =
                new AgentEventConverterRegistry(
                        config.getEventConverters(), config.getEventEnrichers());

        List<AguiEvent> projected = new ArrayList<>();
        String currentRunId = null;
        AguiStreamContext context = null;

        for (StoredAgentEvent stored : history) {
            String runId = stored.runId();
            if (!Objects.equals(runId, currentRunId)) {
                if (context != null) {
                    projected.addAll(registry.enrich(null, context.finishPendingEvents(), context));
                }
                currentRunId = runId;
                if (workbenchConverter != null) {
                    workbenchConverter.forgetRun(threadId, currentRunId);
                }
                // 优先采用已保存运行输入，使重连时 RUN_STARTED 带有用户／工具消息。
                context =
                        new AguiStreamContext(
                                threadId,
                                currentRunId,
                                config,
                                runInputForReplay(threadId, currentRunId, connectInput));
            }
            projected.addAll(registry.convert(stored.event(), context));
        }

        if (context != null) {
            projected.addAll(registry.enrich(null, context.finishPendingEvents(), context));
        }
        return List.copyOf(projected);
    }

    private AguiAdapterConfig replayConfig() {
        return AguiAdapterConfig.builder()
                .toolMergeMode(properties.getDefaultToolMergeMode())
                .runTimeout(properties.getRunTimeout())
                .emitStateEvents(properties.isEmitStateEvents())
                .emitToolCallArgs(properties.isEmitToolCallArgs())
                .emitTokenUsage(properties.isEmitTokenUsage())
                .enableReasoning(properties.isEnableReasoning())
                .defaultAgentId(properties.getDefaultAgentId())
                .eventConverters(eventConverters)
                .eventEnrichers(replayEnrichers)
                .build();
    }

    /**
     * 构建重放 {@code RUN_STARTED} 附带的 {@link RunAgentInput}。
     * <p>逐运行保存的消息是用户／工具轮次的权威来源；保留连接载荷中的工具／状态供转换器读取。
     */
    private RunAgentInput runInputForReplay(
            String threadId, String runId, RunAgentInput connectInput) {
        List<AguiMessage> storedMessages = eventStore.getRunInputMessages(threadId, runId);
        if (storedMessages.isEmpty() && connectInput == null) {
            return null;
        }
        RunAgentInput.Builder builder = RunAgentInput.builder().threadId(threadId).runId(runId);
        if (!storedMessages.isEmpty()) {
            builder.messages(storedMessages);
        } else if (connectInput != null) {
            builder.messages(connectInput.getMessages());
        }
        if (connectInput != null) {
            builder.tools(connectInput.getTools())
                    .context(connectInput.getContext())
                    .state(connectInput.getState())
                    .forwardedProps(connectInput.getForwardedProps())
                    .resume(connectInput.getResume());
        }
        return builder.build();
    }
}
