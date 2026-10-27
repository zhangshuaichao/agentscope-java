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
package io.agentscope.core.memory;

import io.agentscope.core.message.Msg;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 只读 {@link Memory} 适配器，将 {@link #getMessages()} 代理到调用时解析出的
 * 实时 {@link AgentState#getContext()}。
 *
 * <p>此适配器仅用于使已弃用的 1.x 钩子和工具（尤其是
 * {@link StaticLongTermMemoryHook}）在 2.0 构建器移除智能体自有
 * {@code Memory} 后继续工作。所有修改操作都抛出 {@link UnsupportedOperationException}；
 * 应改为通过 {@code AgentState.context} 修改上下文。
 *
 * <p>{@link Supplier} 的契约是“尽力而为”：返回 {@code null} 时，解释为
 * “智能体尚未绑定”，表现为空消息列表而非异常，因此
 * 调用方可以在所属 {@link io.agentscope.core.ReActAgent}
 * 构建之前实例化此视图。
 *
 * @deprecated 自 2.0.0 起弃用。新代码应直接从
 *     {@link AgentState#getContext()} 读取上下文。
 */
@Deprecated(forRemoval = true, since = "2.0.0")
public final class AgentStateMemoryView implements Memory {

    private final Supplier<AgentState> stateSupplier;

    public AgentStateMemoryView(Supplier<AgentState> stateSupplier) {
        this.stateSupplier = Objects.requireNonNull(stateSupplier, "stateSupplier");
    }

    @Override
    public List<Msg> getMessages() {
        // 【学习提示】读取时才解析当前状态，而不是在构造视图时缓存某个会话。
        // 视图只负责适配旧 API，不拥有持久化责任，也不允许修改状态。
        AgentState state = stateSupplier.get();
        return state == null ? List.of() : List.copyOf(state.getContext());
    }

    @Override
    public void addMessage(Msg message) {
        throw readOnly();
    }

    @Override
    public void deleteMessage(int index) {
        throw readOnly();
    }

    @Override
    public void clear() {
        throw readOnly();
    }

    @Override
    public void saveTo(AgentStateStore stateStore, String userId, String sessionId) {
        // 不执行操作：持久化由 AgentState 与 AgentStateStore 绑定负责，而非此视图。
    }

    @Override
    public void loadFrom(AgentStateStore stateStore, String userId, String sessionId) {
        throw readOnly();
    }

    private static UnsupportedOperationException readOnly() {
        return new UnsupportedOperationException(
                "AgentStateMemoryView is read-only; mutate AgentState.context directly.");
    }
}
