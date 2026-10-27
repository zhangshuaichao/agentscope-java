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
package io.agentscope.core.agui.processor;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agui.AguiException;

/**
 * 从不同来源解析智能体的接口。
 *
 * <p>抽象智能体解析逻辑，支持不同场景的实现，例如简单注册表查找或带记忆管理的会话级解析。
 */
public interface AgentResolver {

    /**
     * 根据智能体 ID 和线程 ID 解析智能体。
     *
     * @param agentId 要解析的智能体 ID
     * @param threadId 用于会话管理的线程 ID
     * @return 解析出的智能体
     * @throws AguiException.AgentNotFoundException 未找到智能体时抛出
     */
    Agent resolveAgent(String agentId, String threadId);

    /**
     * 根据智能体 ID、线程 ID 和用户 ID 解析智能体。
     *
     * <p>默认实现忽略 {@code userId}。会话感知解析器应覆盖此方法，避免使用相同线程 ID 的租户共享智能体实例。
     *
     * @param agentId 要解析的智能体 ID
     * @param threadId 用于会话管理的线程 ID
     * @param userId 用户 ID，匿名时可为 {@code null}
     * @return 解析出的智能体
     * @throws AguiException.AgentNotFoundException 未找到智能体时抛出
     */
    default Agent resolveAgent(String agentId, String threadId, String userId) {
        return resolveAgent(agentId, threadId);
    }

    /**
     * 检查线程是否已有记忆/会话历史。
     *
     * <p>用于决定使用前端提供的历史，还是依赖服务端记忆。
     *
     * @param runtimeContext 标识线程和用户的运行上下文
     * @return 线程已有记忆时为 true
     */
    boolean hasMemory(RuntimeContext runtimeContext);
}
