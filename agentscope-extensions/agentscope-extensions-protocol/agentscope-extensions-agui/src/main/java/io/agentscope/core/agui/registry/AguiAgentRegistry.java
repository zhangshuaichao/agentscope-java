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
package io.agentscope.core.agui.registry;

import io.agentscope.core.agent.Agent;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 管理通过 AG-UI 协议访问的智能体的注册表。
 *
 * <p>支持两种注册方式：
 * <ul>
 * <li>单例智能体：单个共享实例</li>
 * <li>工厂智能体：每次请求创建新实例</li>
 * </ul>
 *
 * <p><b>用法：</b>
 * <pre>{@code
 * AguiAgentRegistry registry = new AguiAgentRegistry();
 *
 * // 注册单例智能体
 * registry.register("assistant", myAgent);
 *
 * // 注册逐请求创建智能体的工厂
 * registry.registerFactory("chat", () -> createNewAgent());
 *
 * // 获取智能体
 * Optional<Agent> agent = registry.getAgent("assistant");
 * }</pre>
 */
public class AguiAgentRegistry {

    private final Map<String, Agent> singletonAgents = new ConcurrentHashMap<>();
    private final Map<String, Supplier<Agent>> agentFactories = new ConcurrentHashMap<>();

    /**
     * 使用给定 ID 注册单例智能体。
     *
     * <p>所有携带此 ID 的请求返回同一个智能体实例。
     *
     * @param agentId 智能体 ID
     * @param agent 智能体实例
     */
    public void register(String agentId, Agent agent) {
        if (agentId == null || agentId.isEmpty()) {
            throw new IllegalArgumentException("Agent ID cannot be null or empty");
        }
        if (agent == null) {
            throw new IllegalArgumentException("Agent cannot be null");
        }
        singletonAgents.put(agentId, agent);
    }

    /**
     * 使用给定 ID 注册智能体工厂。
     *
     * <p>每个携带此 ID 的请求创建新智能体实例，适合不应共享的有状态智能体。
     *
     * @param agentId 智能体 ID
     * @param factory 创建新智能体实例的工厂
     */
    public void registerFactory(String agentId, Supplier<Agent> factory) {
        if (agentId == null || agentId.isEmpty()) {
            throw new IllegalArgumentException("Agent ID cannot be null or empty");
        }
        if (factory == null) {
            throw new IllegalArgumentException("Factory cannot be null");
        }
        agentFactories.put(agentId, factory);
    }

    /**
     * 根据 ID 获取智能体。
     *
     * <p>按以下顺序解析：
     * <ol>
     * <li>检查已注册工厂并创建新实例</li>
     * <li>若注册了单例则返回单例</li>
     * </ol>
     *
     * @param agentId 智能体 ID
     * @return 包含智能体的 Optional；未找到时为空
     */
    public Optional<Agent> getAgent(String agentId) {
        // 先检查工厂
        Supplier<Agent> factory = agentFactories.get(agentId);
        if (factory != null) {
            return Optional.of(factory.get());
        }

        // 回退到单例
        return Optional.ofNullable(singletonAgents.get(agentId));
    }

    /**
     * 检查是否注册了给定 ID 的智能体。
     *
     * @param agentId 智能体 ID
     * @return 已注册时为 true
     */
    public boolean hasAgent(String agentId) {
        return agentFactories.containsKey(agentId) || singletonAgents.containsKey(agentId);
    }

    /**
     * 根据 ID 注销智能体。
     *
     * @param agentId 智能体 ID
     * @return 成功注销时为 true
     */
    public boolean unregister(String agentId) {
        boolean removedFactory = agentFactories.remove(agentId) != null;
        boolean removedSingleton = singletonAgents.remove(agentId) != null;
        return removedFactory || removedSingleton;
    }

    /**
     * 清除所有已注册智能体。
     */
    public void clear() {
        agentFactories.clear();
        singletonAgents.clear();
    }

    /**
     * 获取注册智能体数量（工厂和单例合计）。
     *
     * @return 注册智能体总数
     */
    public int size() {
        return agentFactories.size() + singletonAgents.size();
    }
}
