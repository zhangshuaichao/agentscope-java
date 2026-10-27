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
package io.agentscope.core.state;

import io.agentscope.core.message.Msg;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.state.legacy.ToolkitState;
import java.util.List;
import java.util.Optional;

/**
 * 将 v1 会话数据加载为 2.0 {@link AgentState} 格式的工具。
 *
 * <p>读取旧版会话键（{@code memory_messages}、
 * {@code toolkit_activeGroups}），并构造等价的 {@link AgentState} 对象。
 * 原始会话数据不会被修改，也不会原地迁移。此工具仅提供显式迁移入口；
 * 正常的智能体激活流程不会读取这些键。请显式持久化返回的状态，
 * 或通过 {@code SessionMigration.importBaseline} 导入。
 *
 * <p>旧版键不含 2.0 的 {@link PermissionContextState}。若调用方需要指定的
 * 权限上下文（例如为预置特权会话设置 {@code BYPASS}），必须显式传入；
 * 否则重建的状态会静默回退到默认权限模式。
 */
public final class LegacyStateLoader {

    private LegacyStateLoader() {}

    /**
     * 从 v1 会话加载 {@link AgentState}。
     *
     * <p>读取以下内容：
     * <ul>
     *   <li>{@code memory_messages} — 对话历史</li>
     *   <li>{@code toolkit_activeGroups} — 工具激活状态</li>
     * </ul>
     *
     * @param stateStore 要读取的状态存储
     * @param userId 可为空的用户标识符
     * @param sessionId 会话标识符（必填）
     * @return 使用旧版数据填充的新 AgentState
     */
    public static AgentState loadFromLegacySession(
            AgentStateStore stateStore, String userId, String sessionId) {
        return loadFromLegacySessionWithPresence(stateStore, userId, sessionId, null).state();
    }

    /**
     * 从 v1 会话加载 {@link AgentState}，并应用指定的权限上下文。
     *
     * <p>读取的键见 {@link #loadFromLegacySession(AgentStateStore, String, String)}。
     * 旧版键不携带权限上下文；必须传入 {@code permCtx}，才能在从 v1 会话重建状态时
     * 保留非默认权限模式（例如 {@code BYPASS}）。
     *
     * @param stateStore 要读取的状态存储
     * @param userId 可为空的用户标识符
     * @param sessionId 会话标识符（必填）
     * @param permCtx 要附加的权限上下文；为 {@code null} 时保留默认值
     * @return 使用旧版数据填充的新 AgentState
     */
    public static AgentState loadFromLegacySession(
            AgentStateStore stateStore,
            String userId,
            String sessionId,
            PermissionContextState permCtx) {
        return loadFromLegacySessionWithPresence(stateStore, userId, sessionId, permCtx).state();
    }

    /**
     * 加载旧版状态，同时保留单一工具组键是否存在的信息。
     *
     * <p>必须保留存在标记，因为显式持久化的 {@code
     * toolkit_activeGroups=[]} 与键不存在具有不同语义，尽管两者都会产生
     * 空的 {@link ToolContextState}。
     *
     * <p>接收 {@link PermissionContextState} 的重载会将指定权限上下文应用到
     * 重建状态；旧版键本身从不携带该上下文。不需要指定权限模式的调用方
     * 可以使用 {@code (stateStore, userId, sessionId)} 版本。
     */
    public static LegacyLoadResult loadFromLegacySessionWithPresence(
            AgentStateStore stateStore, String userId, String sessionId) {
        return loadFromLegacySessionWithPresence(stateStore, userId, sessionId, null);
    }

    /**
     * 加载旧版状态，同时保留单一工具组键的存在信息，
     * 并将指定的权限上下文应用到重建状态。
     *
     * <p>存在性的语义见 {@link #loadFromLegacySessionWithPresence(AgentStateStore, String, String)}。
     * 当 {@code permCtx} 非空时，将其附加到重建的 {@link AgentState}；
     * 否则保留默认权限上下文。
     *
     * @param stateStore 要读取的状态存储
     * @param userId 可为空的用户标识符
     * @param sessionId 会话标识符（必填）
     * @param permCtx 要附加的权限上下文；为 {@code null} 时保留默认值
     * @return 重建的旧版状态，以及旧版键是否存在
     */
    public static LegacyLoadResult loadFromLegacySessionWithPresence(
            AgentStateStore stateStore,
            String userId,
            String sessionId,
            PermissionContextState permCtx) {
        List<Msg> msgs = stateStore.getList(userId, sessionId, "memory_messages", Msg.class);
        Optional<ToolkitState> toolkitState =
                stateStore.get(userId, sessionId, "toolkit_activeGroups", ToolkitState.class);

        AgentState.Builder builder = AgentState.builder().context(msgs);
        if (permCtx != null) {
            builder.permissionContext(permCtx);
        }

        toolkitState.ifPresent(
                value -> {
                    ToolContextState.Builder tc = ToolContextState.builder();
                    for (String group : value.activeGroups()) {
                        tc.addActivatedGroup(group);
                    }
                    builder.toolContext(tc.build());
                });

        return new LegacyLoadResult(builder.build(), !msgs.isEmpty() || toolkitState.isPresent());
    }

    /**
     * 读取 v1 会话键的结果。旧版消息非空或工具组键存在时，{@code found} 为 true；
     * 工具组键存在且其列表显式为空的情况也算存在。
     */
    public record LegacyLoadResult(AgentState state, boolean found) {}
}
