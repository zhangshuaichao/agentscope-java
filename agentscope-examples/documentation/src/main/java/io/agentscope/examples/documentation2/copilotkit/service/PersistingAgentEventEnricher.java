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

import io.agentscope.core.agui.adapter.strategy.AguiEventEnricher;
import io.agentscope.core.agui.adapter.strategy.AguiStreamContext;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.examples.documentation2.copilotkit.workbench.WorkbenchAguiEventConverter;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 在 AG-UI 适配器投影源 {@link AgentEvent} 时保存它。
 * <p>不保存 AG-UI 帧本身；重连重放通过转换器重新投影。
 * <p>{@link AgentStartEvent} 时也保存运行输入，以重建 {@code RUN_STARTED.input.messages}，供 CopilotKit 恢复用户／工具轮次。
 */
public final class PersistingAgentEventEnricher implements AguiEventEnricher {

    private final InMemoryAgentEventStore eventStore;
    private final WorkbenchAguiEventConverter workbenchConverter;

    public PersistingAgentEventEnricher(
            InMemoryAgentEventStore eventStore,
            ObjectProvider<WorkbenchAguiEventConverter> workbenchConverterProvider) {
        this.eventStore = eventStore;
        this.workbenchConverter = workbenchConverterProvider.getIfAvailable();
    }

    @Override
    public List<AguiEvent> enrich(
            AgentEvent source, List<AguiEvent> events, AguiStreamContext context) {
        if (source != null) {
            if (source instanceof AgentStartEvent) {
                persistRunInputMessages(context);
            }
            eventStore.append(context.getThreadId(), context.getRunId(), source);
            if (source instanceof AgentEndEvent && workbenchConverter != null) {
                workbenchConverter.forgetRun(context.getThreadId(), context.getRunId());
            }
        }
        return events;
    }

    private void persistRunInputMessages(AguiStreamContext context) {
        RunAgentInput runInput = context.getRunInput();
        if (runInput == null
                || runInput.getMessages() == null
                || runInput.getMessages().isEmpty()) {
            return;
        }
        eventStore.putRunInputMessages(
                context.getThreadId(), context.getRunId(), runInput.getMessages());
    }
}
