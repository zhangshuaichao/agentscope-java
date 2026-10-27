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
package io.agentscope.core.shutdown;

import io.agentscope.core.state.AgentState;

/**
 * 在优雅关闭期间持久化 {@link AgentState} 的策略。
 *
 * <p>实现通过
 * {@link GracefulShutdownManager#bindStateSaver(io.agentscope.core.agent.Agent, ShutdownStateSaver)}
 * 注册，使关闭管理器能够为智能体状态创建检查点，而不依赖旧版
 * {@link io.agentscope.core.state.AgentStateStore} API。
 */
@FunctionalInterface
public interface ShutdownStateSaver {

    /**
     * 持久化给定的智能体状态快照。
     *
     * @param state 当前智能体状态（{@code shutdownInterrupted} 已设置）
     */
    void save(AgentState state);
}
