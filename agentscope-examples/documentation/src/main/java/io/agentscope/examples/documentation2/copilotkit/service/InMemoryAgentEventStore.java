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

import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.event.AgentEvent;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 按 threadId 索引的内存 AgentEvent 日志。
 * <p>AgentScope {@link AgentEvent} 是事实来源，AG-UI 帧按需投影（参见 {@link AgentEventAguiReplayer}），供 {@code /agent/{agentId}/connect} 和检查 API 使用。
 * <p>每次运行的用户／工具／恢复输入单独保存，重放时附回 {@code RUN_STARTED.input}，与 CopilotKit 持久化模型一致；此示例内存存储本身不跨进程持久化。
 * <p>【学习提示】这里的重放基于进程内日志，解决同进程重连，不提供崩溃恢复；生产持久化还需要租户授权和存储保留策略。
 */
@Component
public final class InMemoryAgentEventStore {

    private static final int MAX_EVENTS_PER_THREAD = 5_000;

    private final ConcurrentHashMap<String, List<StoredAgentEvent>> eventsByThread =
            new ConcurrentHashMap<>();

    /**
     * threadId →（runId → 本次运行的新输入消息）。
     */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, List<AguiMessage>>>
            runInputsByThread = new ConcurrentHashMap<>();

    /**
     * 追加线程／运行的单个 AgentEvent。
     * @param threadId 线程标识
     * @param runId 产生事件的运行标识
     * @param event 源 AgentEvent
     */
    public void append(String threadId, String runId, AgentEvent event) {
        if (threadId == null || threadId.isBlank() || event == null) {
            return;
        }
        String resolvedRunId = runId == null || runId.isBlank() ? "unknown" : runId;
        StoredAgentEvent stored = new StoredAgentEvent(resolvedRunId, event);
        eventsByThread.compute(
                threadId,
                (id, existing) -> {
                    List<StoredAgentEvent> events = existing != null ? existing : new ArrayList<>();
                    synchronized (events) {
                        events.add(stored);
                        while (events.size() > MAX_EVENTS_PER_THREAD) {
                            events.remove(0);
                        }
                    }
                    return events;
                });
    }

    /**
     * 保存运行的新 AG-UI 输入消息，通常为最新用户消息。CopilotKit 重连通过 {@code RUN_STARTED.input.messages} 恢复对话轮次；已存储 ID 的消息跳过。
     * @param threadId 线程标识
     * @param runId 运行标识
     * @param messages 来自 {@code RunAgentInput} 的候选输入消息
     */
    public void putRunInputMessages(String threadId, String runId, List<AguiMessage> messages) {
        if (threadId == null
                || threadId.isBlank()
                || runId == null
                || runId.isBlank()
                || messages == null
                || messages.isEmpty()) {
            return;
        }
        Set<String> knownIds = knownMessageIds(threadId);
        List<AguiMessage> fresh = new ArrayList<>();
        for (AguiMessage message : messages) {
            if (message == null || message.getId() == null || message.getId().isBlank()) {
                continue;
            }
            if (knownIds.contains(message.getId())) {
                continue;
            }
            fresh.add(message);
            knownIds.add(message.getId());
        }
        if (fresh.isEmpty()) {
            return;
        }
        runInputsByThread
                .computeIfAbsent(threadId, id -> new ConcurrentHashMap<>())
                .put(runId, List.copyOf(fresh));
    }

    /**
     * 获取运行先前保存的输入消息，没有时为空。
     * @param threadId 线程标识
     * @param runId 运行标识
     * @return 不可变列表，永不为 null
     */
    public List<AguiMessage> getRunInputMessages(String threadId, String runId) {
        if (threadId == null || runId == null) {
            return List.of();
        }
        Map<String, List<AguiMessage>> byRun = runInputsByThread.get(threadId);
        if (byRun == null) {
            return List.of();
        }
        List<AguiMessage> messages = byRun.get(runId);
        return messages == null ? List.of() : messages;
    }

    /**
     * 获取线程已保存 AgentEvent 的快照。
     * @param threadId 线程标识
     * @return 不可变副本，永不为 null
     */
    public List<StoredAgentEvent> snapshot(String threadId) {
        List<StoredAgentEvent> events = eventsByThread.get(threadId);
        if (events == null) {
            return List.of();
        }
        synchronized (events) {
            return List.copyOf(events);
        }
    }

    /**
     * 线程是否有已保存 AgentEvent。
     * @param threadId 线程标识
     * @return 非空时为 true
     */
    public boolean hasEvents(String threadId) {
        List<StoredAgentEvent> events = eventsByThread.get(threadId);
        if (events == null) {
            return false;
        }
        synchronized (events) {
            return !events.isEmpty();
        }
    }

    /**
     * 清除线程事件及运行输入。
     * @param threadId 线程标识
     */
    public void clear(String threadId) {
        eventsByThread.remove(threadId);
        runInputsByThread.remove(threadId);
    }

    private Set<String> knownMessageIds(String threadId) {
        Set<String> ids = new LinkedHashSet<>();
        Map<String, List<AguiMessage>> byRun = runInputsByThread.get(threadId);
        if (byRun == null) {
            return ids;
        }
        for (List<AguiMessage> messages : byRun.values()) {
            for (AguiMessage message : messages) {
                if (message != null && message.getId() != null) {
                    ids.add(message.getId());
                }
            }
        }
        return ids;
    }

    /**
     * 特定 AG-UI 运行中捕获的单个 AgentEvent。
     * @param runId 发出事件的运行
     * @param event 源 AgentEvent
     */
    public record StoredAgentEvent(String runId, AgentEvent event) {}
}
