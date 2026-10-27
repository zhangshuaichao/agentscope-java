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
package io.agentscope.harness.agent.sandbox;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.harness.agent.IsolationScope;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

/**
 * 由通用 AgentScope {@link AgentStateStore} 抽象支撑的沙箱状态存储。
 *
 * <p>此存储将沙箱生命周期状态保存在与 ReActAgent 运行时状态相同的后端中。因此提供分布式 {@link AgentStateStore} 实现（例如 Redis）即可支持分布式沙箱恢复状态。
 */
public final class SessionSandboxStateStore {

    private static final String SANDBOX_STATE_KEY = "_sandbox_state";

    private final AgentStateStore stateStore;
    private final String agentId;

    public SessionSandboxStateStore(AgentStateStore stateStore, String agentId) {
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore must not be null");
        this.agentId = Objects.requireNonNull(agentId, "agentId must not be null");
    }

    public Optional<String> load(SandboxIsolationKey key) throws IOException {
        try {
            String slotSid = slotSessionId(key);
            Optional<SandboxStateSlot> state =
                    stateStore.get(null, slotSid, SANDBOX_STATE_KEY, SandboxStateSlot.class);
            if (state.isEmpty() || state.get().deleted() || state.get().json() == null) {
                return Optional.empty();
            }
            return Optional.of(state.get().json());
        } catch (Exception e) {
            throw asIo("load", key, e);
        }
    }

    public void save(SandboxIsolationKey key, String json) throws IOException {
        try {
            stateStore.save(
                    null, slotSessionId(key), SANDBOX_STATE_KEY, new SandboxStateSlot(json, false));
        } catch (Exception e) {
            throw asIo("save", key, e);
        }
    }

    public void delete(SandboxIsolationKey key) throws IOException {
        try {
            // 并非所有 AgentStateStore 实现都支持按键删除；使用墓碑标记
            // 保持不同存储间行为一致。
            stateStore.save(
                    null, slotSessionId(key), SANDBOX_STATE_KEY, SandboxStateSlot.tombstone());
        } catch (Exception e) {
            throw asIo("delete", key, e);
        }
    }

    /**
     * 将沙箱隔离键打包为单个 sessionId 字符串，适配 {@link AgentStateStore} 的双参数槽位模型。userId 列始终为 {@code null}，因为沙箱状态在概念上以智能体为作用域，而非用户：USER/AGENT/GLOBAL 作用域编码在 sessionId 前缀中，而不是 userId 槽位。
     */
    private String slotSessionId(SandboxIsolationKey key) {
        IsolationScope scope = key.getScope();
        return switch (scope) {
            case SESSION -> "sandbox/session/" + key.getValue();
            case USER -> "sandbox/user/" + agentId + "/" + key.getValue();
            case AGENT -> "sandbox/agent/" + agentId;
            case GLOBAL -> "sandbox/global";
        };
    }

    private static IOException asIo(String op, SandboxIsolationKey key, Exception e) {
        return new IOException("Failed to " + op + " sandbox state for " + key, e);
    }

    private record SandboxStateSlot(String json, boolean deleted) implements State {
        static SandboxStateSlot tombstone() {
            return new SandboxStateSlot("", true);
        }
    }
}
