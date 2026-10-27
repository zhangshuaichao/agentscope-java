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
package io.agentscope.harness.agent.subagent;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;

/**
 * 为单次启动或会话创建新的子智能体实例。在 {@link DefaultAgentManager} 中
 * 以 {@code agent_id} 注册；需要隔离时，每次 {@link #create(RuntimeContext)}
 * 调用都应返回全新智能体。
 *
 * <p>此类型替代子智能体装配中的原始 {@link java.util.function.Supplier}，
 * 使调用点和映射表本身更能表达意图。
 *
 * <p><b>阶段 B-0：</b>实现应将 {@code parentRc.getUserId()} 与
 * {@code parentRc.getSessionId()} 固定到子智能体的持久化 {@code SessionKey} 中，
 * 使 {@code AgentState} 按（用户、父会话）保持隔离，不论配置的是哪种
 * {@link io.agentscope.core.state.AgentStateStore} 后端
 * （Workspace／Redis／InMemory／自定义）。两个字段均可为空；缺失时子级回退到
 * 仅以声明名称为键的旧版单桶形式。
 */
@FunctionalInterface
public interface SubagentFactory {

    /**
     * 为给定父级运行时上下文构建新的子智能体实例。实现负责将 {@code parentRc}
     * 转换为适当的状态隔离信息（例如包含父级身份的 {@code SessionKey}）；
     * 参见类型级 Javadoc。
     *
     * @param parentRc 启动时父级智能体的运行时上下文；调用方没有作用域时
     *     可以为 {@link RuntimeContext#empty()}
     */
    Agent create(RuntimeContext parentRc);
}
