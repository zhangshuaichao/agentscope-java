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
package io.agentscope.core.agui.adapter;

import io.agentscope.core.agent.Agent;

/**
 * 为 AG-UI 请求创建 {@link AguiAgentAdapter} 实例的工厂。
 *
 * <p>应用需要替换默认适配器时可提供自定义工厂，例如使用定制运行上下文创建逻辑的 {@link AguiAgentAdapter} 子类。
 */
@FunctionalInterface
public interface AguiAgentAdapterFactory {

    /**
     * 为已解析的智能体创建适配器。
     *
     * @param agent 已解析的智能体
     * @param config AG-UI 适配器配置
     * @return 本次请求使用的适配器
     */
    AguiAgentAdapter create(Agent agent, AguiAdapterConfig config);

    /**
     * 返回默认工厂。
     *
     * @return 创建标准 {@link AguiAgentAdapter} 的工厂
     */
    static AguiAgentAdapterFactory defaultFactory() {
        return AguiAgentAdapter::new;
    }
}
