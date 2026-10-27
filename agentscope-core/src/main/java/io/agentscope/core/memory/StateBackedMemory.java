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
import java.util.ArrayList;
import java.util.List;

/**
 * 将所有读写操作委派给
 * {@link AgentState#contextMutable()} 的 {@link Memory} 实现。
 *
 * <p>此适配器仅用于兼容通过
 * {@code agent.getMemory().getMessages()} 访问对话历史的旧代码。
 * 2.0 中对话上下文的规范存放位置是
 * {@link AgentState#getContext()}。
 *
 * @deprecated 自 2.0.0 起弃用。请直接使用 {@link AgentState#getContext()}。
 */
@Deprecated(forRemoval = true, since = "2.0.0")
public class StateBackedMemory implements Memory {

    private final AgentState state;

    public StateBackedMemory(AgentState state) {
        this.state = state;
    }

    @Override
    public void addMessage(Msg message) {
        state.contextMutable().add(message);
    }

    @Override
    public List<Msg> getMessages() {
        return new ArrayList<>(state.contextMutable());
    }

    @Override
    public void deleteMessage(int index) {
        List<Msg> ctx = state.contextMutable();
        if (index >= 0 && index < ctx.size()) {
            ctx.remove(index);
        }
    }

    @Override
    public void clear() {
        state.contextMutable().clear();
    }

    @Override
    public void saveTo(AgentStateStore stateStore, String userId, String sessionId) {
        stateStore.save(
                userId, sessionId, "memory_messages", new ArrayList<>(state.contextMutable()));
    }

    @Override
    public void loadFrom(AgentStateStore stateStore, String userId, String sessionId) {
        List<Msg> loaded = stateStore.getList(userId, sessionId, "memory_messages", Msg.class);
        state.contextMutable().clear();
        state.contextMutable().addAll(loaded);
    }
}
