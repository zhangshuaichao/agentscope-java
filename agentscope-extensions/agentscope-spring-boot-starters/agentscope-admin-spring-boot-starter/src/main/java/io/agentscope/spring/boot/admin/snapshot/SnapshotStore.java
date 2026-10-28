/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.spring.boot.admin.snapshot;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 按会话保存的内存撤销／重做栈。
 *
 * <p>保存 {@link io.agentscope.core.state.AgentState} 的序列化 JSON，恢复时再反序列化。每会话容量有界，默认 20，达到上限时丢弃最旧快照。
 *
 * <p>这是有意保持简单的单节点存储。集群部署可提供自定义 {@code SnapshotStore} bean，替换为 Redis 等后端。
 */
public final class SnapshotStore {

    /**
 * 每会话默认撤销深度。
 */
    public static final int DEFAULT_CAPACITY = 20;

    private final int capacityPerSession;
    private final ConcurrentMap<String, SessionStacks> sessions = new ConcurrentHashMap<>();

    public SnapshotStore() {
        this(DEFAULT_CAPACITY);
    }

    public SnapshotStore(int capacityPerSession) {
        this.capacityPerSession = Math.max(1, capacityPerSession);
    }

    /**
 * 为 {@code sessionId} 压入新快照；这是新编辑，因此清空重做栈。
 */
    public synchronized void push(String sessionId, String json) {
        if (sessionId == null || json == null) return;
        SessionStacks s = sessions.computeIfAbsent(sessionId, k -> new SessionStacks());
        s.undo.push(json);
        s.redo.clear();
        while (s.undo.size() > capacityPerSession) {
            s.undo.removeLast();
        }
    }

    /**
     * 弹出最近撤销快照，并原子地将 {@code currentJson} 压入重做栈，使后续 {@link #redo(String, String)} 可恢复它。
     *
     * @return 待恢复快照 JSON，无可用快照时为空
     */
    public synchronized Optional<String> undo(String sessionId, String currentJson) {
        SessionStacks s = sessions.get(sessionId);
        if (s == null || s.undo.isEmpty()) {
            return Optional.empty();
        }
        String snap = s.undo.pop();
        if (currentJson != null) {
            s.redo.push(currentJson);
            while (s.redo.size() > capacityPerSession) {
                s.redo.removeLast();
            }
        }
        return Optional.of(snap);
    }

    /**
     * 弹出最近重做快照，并将 {@code currentJson} 压入撤销栈，使后续 {@link #undo(String, String)} 可撤回它。
     */
    public synchronized Optional<String> redo(String sessionId, String currentJson) {
        SessionStacks s = sessions.get(sessionId);
        if (s == null || s.redo.isEmpty()) {
            return Optional.empty();
        }
        String snap = s.redo.pop();
        if (currentJson != null) {
            s.undo.push(currentJson);
            while (s.undo.size() > capacityPerSession) {
                s.undo.removeLast();
            }
        }
        return Optional.of(snap);
    }

    public synchronized int undoDepth(String sessionId) {
        SessionStacks s = sessions.get(sessionId);
        return s == null ? 0 : s.undo.size();
    }

    public synchronized int redoDepth(String sessionId) {
        SessionStacks s = sessions.get(sessionId);
        return s == null ? 0 : s.redo.size();
    }

    public synchronized void clear(String sessionId) {
        sessions.remove(sessionId);
    }

    private static final class SessionStacks {
        final Deque<String> undo = new ArrayDeque<>();
        final Deque<String> redo = new ArrayDeque<>();
    }
}
