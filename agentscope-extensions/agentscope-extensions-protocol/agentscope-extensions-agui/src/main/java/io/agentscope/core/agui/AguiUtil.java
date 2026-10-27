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
package io.agentscope.core.agui;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import java.lang.reflect.Method;

/**
 * 用于检查 AG-UI 智能体实例的辅助方法，不对 {@code agentscope-harness} 引入编译期依赖。
 *
 * <p>{@link ReActAgent} 实现了 {@link AutoCloseable}。{@link #asReActAgent(Agent)} 返回的是会话正在使用的智能体，调用方不得关闭它。
 */
public final class AguiUtil {

    private static final String HARNESS_AGENT_CLASS_NAME =
            "io.agentscope.harness.agent.HarnessAgent";

    private AguiUtil() {}

    /**
     * 当 {@code agent} 为 {@code HarnessAgent}（或其子类）时返回 {@code true}。
     *
     * <p>使用类名匹配，使 AG-UI 模块无需依赖 Harness 构件即可流式处理 Harness 智能体。
     *
     * @param agent 要检查的智能体，可以为 {@code null}
     * @return 运行时类型为 Harness 智能体时返回 {@code true}
     */
    public static boolean isHarnessAgent(Agent agent) {
        if (agent == null) {
            return false;
        }
        Class<?> type = agent.getClass();
        while (type != null) {
            if (HARNESS_AGENT_CLASS_NAME.equals(type.getName())) {
                return true;
            }
            type = type.getSuperclass();
        }
        return false;
    }

    /**
     * 尽可能从 {@code agent} 解包出 {@link ReActAgent}。
     *
     * <p>Harness 智能体通过公开的 {@code getDelegate()} 方法暴露内部 ReAct 委托。返回实例是会话正在使用的智能体；调用方不得关闭它。
     *
     * @param agent 要解包的智能体，可以为 {@code null}
     * @return ReAct 智能体；无法解析时返回 {@code null}
     */
    public static ReActAgent asReActAgent(Agent agent) {
        if (agent instanceof ReActAgent reactAgent) {
            return reactAgent;
        }
        if (agent == null) {
            return null;
        }
        try {
            Method getDelegate = agent.getClass().getMethod("getDelegate");
            Object delegate = getDelegate.invoke(agent);
            if (delegate instanceof ReActAgent reactAgent) {
                return reactAgent;
            }
        } catch (ReflectiveOperationException ignored) {
            // 不是 Harness 风格包装器；继续后续处理。
        }
        return null;
    }
}
