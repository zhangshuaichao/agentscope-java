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
package io.agentscope.examples.documentation2.copilotkit.workbench;

import io.agentscope.core.agui.adapter.strategy.AgentEventConverter;
import io.agentscope.core.agui.adapter.strategy.AguiStreamContext;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.CustomEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 将工作台 {@link CustomEvent} 映射为 CopilotKit 理解的 AG-UI 帧。
 * <ul><li>{@code state_updated}：本轮首帧为 {@code STATE_SNAPSHOT}，之后为 RFC 6902 {@code STATE_DELTA}。
 * <li>{@code plan_step_started} / {@code plan_step_finished} → {@code STEP_STARTED} / {@code STEP_FINISHED}。
 * <li>{@code a2ui_surface} → 类型为 {@code a2ui-surface} 的 {@code ACTIVITY_SNAPSHOT}，传给 A2UI 渲染器。
 * <li>{@code permission_audit} → 审计面板消费的 {@code CUSTOM}。
 * </ul>
 * <p>快照基线按 {@code threadId:runId} 索引，运行结束即移除，避免长期服务积累。
 */
@Component
public class WorkbenchAguiEventConverter implements AgentEventConverter {

    /**
     * 浏览器 {@code useRenderActivity("a2ui-surface")} 对应的 AG-UI 活动类型。
     */
    public static final String ACTIVITY_A2UI_SURFACE = "a2ui-surface";

    private final Map<String, Map<String, Object>> lastSnapshots = new ConcurrentHashMap<>();

    @Override
    public Set<Class<? extends AgentEvent>> eventTypes() {
        return Set.of(CustomEvent.class);
    }

    @Override
    public void convert(AgentEvent event, AguiStreamContext context) {
        CustomEvent custom = (CustomEvent) event;
        String name = custom.getName();
        Object value = custom.getValue();

        switch (name == null ? "" : name) {
            case WorkbenchState.EVENT_STATE_UPDATED -> emitState(context, asMap(value));
            case WorkbenchState.EVENT_STEP_STARTED ->
                    context.emit(
                            new AguiEvent.StepStarted(
                                    context.getThreadId(), context.getRunId(), stepName(value)));
            case WorkbenchState.EVENT_STEP_FINISHED ->
                    context.emit(
                            new AguiEvent.StepFinished(
                                    context.getThreadId(), context.getRunId(), stepName(value)));
            case WorkbenchState.EVENT_A2UI_SURFACE -> emitSurface(context, asMap(value));
            default ->
                    context.emit(
                            new AguiEvent.Custom(
                                    context.getThreadId(), context.getRunId(), name, value));
        }
    }

    /**
     * 删除运行级快照基线，在运行结束时调用。
     */
    public void forgetRun(String threadId, String runId) {
        lastSnapshots.remove(runKey(threadId, runId));
    }

    private void emitState(AguiStreamContext context, Map<String, Object> snapshot) {
        if (snapshot.isEmpty()) {
            return;
        }
        String key = runKey(context.getThreadId(), context.getRunId());
        Map<String, Object> previous = lastSnapshots.put(key, snapshot);

        if (previous == null) {
            context.emit(
                    new AguiEvent.StateSnapshot(
                            context.getThreadId(), context.getRunId(), snapshot));
            return;
        }

        List<AguiEvent.JsonPatchOperation> patch = diff(previous, snapshot);
        if (patch.isEmpty()) {
            return;
        }
        context.emit(new AguiEvent.StateDelta(context.getThreadId(), context.getRunId(), patch));
    }

    private void emitSurface(AguiStreamContext context, Map<String, Object> payload) {
        Object surfaceId = payload.get("surfaceId");
        if (surfaceId == null) {
            return;
        }
        context.emit(
                new AguiEvent.ActivitySnapshot(
                        context.getThreadId(),
                        context.getRunId(),
                        "a2ui-" + surfaceId,
                        ACTIVITY_A2UI_SURFACE,
                        payload));
    }

    /**
     * 构建两个快照之间的顶层 RFC 6902 补丁。嵌套对象整体替换而非递归比较，因为工作台状态较小，浅层补丁便于事件检查器阅读。
     */
    private static List<AguiEvent.JsonPatchOperation> diff(
            Map<String, Object> previous, Map<String, Object> current) {
        List<AguiEvent.JsonPatchOperation> patch = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>(previous.keySet());
        keys.addAll(current.keySet());

        for (String key : keys) {
            boolean existed = previous.containsKey(key);
            boolean exists = current.containsKey(key);
            String path = "/" + key;
            if (existed && !exists) {
                patch.add(new AguiEvent.JsonPatchOperation("remove", path, null, null));
            } else if (!existed) {
                patch.add(new AguiEvent.JsonPatchOperation("add", path, current.get(key), null));
            } else if (!Objects.equals(previous.get(key), current.get(key))) {
                patch.add(
                        new AguiEvent.JsonPatchOperation("replace", path, current.get(key), null));
            }
        }
        return patch;
    }

    private static String stepName(Object value) {
        Map<String, Object> payload = asMap(value);
        Object step = payload.get("step");
        return step == null ? "plan-step" : String.valueOf(step);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map
                ? new LinkedHashMap<>((Map<String, Object>) map)
                : Map.of();
    }

    private static String runKey(String threadId, String runId) {
        return threadId + ":" + runId;
    }
}
