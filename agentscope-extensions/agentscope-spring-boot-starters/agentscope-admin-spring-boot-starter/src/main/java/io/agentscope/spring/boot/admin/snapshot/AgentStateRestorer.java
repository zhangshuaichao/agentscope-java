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

import io.agentscope.core.state.AgentState;

/**
 * 将快照中的 {@link AgentState} 可变字段复制回实时状态。
 *
 * <p>此辅助器采用逐字段覆盖而非交换实例；上游以 {@code ReActAgent} 的 {@code private final} 状态字段解释该设计，当前 {@code AgentState} 状态所有权仍应按 core 实现理解。
 *
 * <p><b>覆盖范围：</b>
 * <ul>
 *   <li>{@code summary}：通过 setter 覆盖
 *   <li>{@code replyId}：通过 setter 覆盖
 *   <li>{@code curIter}：通过 setter 覆盖
 *   <li>{@code shutdownInterrupted}：通过 setter 覆盖
 *   <li>{@code context}：原地清空并重新填充
 * </ul>
 *
 * <p>不恢复子上下文（{@code permissionContext}、{@code toolContext}、{@code tasksContext}、{@code planModeContext}）。当前范围对应只修改 {@code summary} 和 {@code context} 的 {@code session:compact}；更广泛撤销覆盖记录在 {@code project_java-agentstate-refactor.md} 的 {@code AgentState} 重构中。
 *
 * <p>【学习提示】保存完整 JSON 不等于恢复完整对象图。计划状态等不在本方法恢复范围内，不能把这套 undo 当作任意管理操作的通用事务回滚。
 */
public final class AgentStateRestorer {

    private AgentStateRestorer() {}

    /**
     * 从快照恢复实时 {@code AgentState}。
     *
     * @param live 智能体当前状态，原地修改
     * @param snapshotJson 由 {@link AgentState#toJson()} 生成的 JSON
     * @throws IllegalArgumentException JSON 无效或对应其他会话时抛出
     */
    public static void restore(AgentState live, String snapshotJson) {
        if (live == null) {
            throw new IllegalArgumentException("live state must not be null");
        }
        if (snapshotJson == null || snapshotJson.isBlank()) {
            throw new IllegalArgumentException("snapshot JSON must not be blank");
        }
        AgentState snap;
        try {
            snap = AgentState.fromJsonString(snapshotJson);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    "invalid AgentState snapshot JSON: " + e.getMessage(), e);
        }
        if (!live.getSessionId().equals(snap.getSessionId())) {
            throw new IllegalArgumentException(
                    "snapshot sessionId="
                            + snap.getSessionId()
                            + " does not match live sessionId="
                            + live.getSessionId());
        }
        live.setSummary(snap.getSummary());
        live.setReplyId(snap.getReplyId());
        live.setCurIter(snap.getCurIter());
        live.setShutdownInterrupted(snap.isShutdownInterrupted());
        live.contextMutable().clear();
        live.contextMutable().addAll(snap.getContext());
    }
}
