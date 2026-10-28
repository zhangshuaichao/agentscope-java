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
package io.agentscope.spring.boot.admin.registry;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.harness.agent.HarnessAgent;

/**
 * 小型解包辅助器，向管理调用点隐藏 {@code HarnessAgent} 组合结构。
 *
 * <p>{@link HarnessAgent} 通过组合包装 {@link ReActAgent}，因此对 Harness 构建的对象，{@code agent instanceof ReActAgent} 为 {@code false}。管理接口需要的只读访问器（模型、AgentState、maxIters、权限引擎、会话、sessionKey、中间件）在委托对象上定义，或由 {@code HarnessAgent} 转发；在边界解包一次最直接。
 */
public final class AgentResolver {

    private AgentResolver() {}

    /**
     * 对裸 {@code ReActAgent} 和 {@link HarnessAgent} 包装实例（通过 {@link HarnessAgent#getDelegate()}）返回底层 {@link ReActAgent}。其他 {@link Agent} 实现返回 {@code null}，让调用方温和退出。
     */
    public static ReActAgent unwrapReActAgent(Agent agent) {
        if (agent instanceof ReActAgent r) {
            return r;
        }
        if (agent instanceof HarnessAgent h) {
            return h.getDelegate();
        }
        return null;
    }
}
