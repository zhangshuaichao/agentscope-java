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
package io.agentscope.core.tool.subagent;

import io.agentscope.core.agent.Agent;

/**
 * 创建智能体实例的工厂接口。
 *
 * <p>由于 ReActAgent 不是线程安全的，此提供者模式确保每次工具调用获得新的智能体实例，类似于 Spring 的 ObjectProvider 模式。
 *
 * <p>用法示例：
 * <pre>{@code
 * SubAgentProvider<ReActAgent> provider = () -> ReActAgent.builder()
 *     .name("ResearchAgent")
 *     .model(model)
 *     .sysPrompt("You are a research expert...")
 *     .build();
 *
 * toolkit.registration()
 *     .subAgent(provider)
 *     .apply();
 * }</pre>
 *
 * @param <T> 此提供者创建的智能体类型
 */
@FunctionalInterface
public interface SubAgentProvider<T extends Agent> {

    /**
     * 提供新的智能体实例。
     *
     * <p>每次工具调用都会调用此方法，以确保线程安全。实现应在每次调用时创建新的智能体实例。
     *
     * @return 新的智能体实例
     */
    T provide();
}
