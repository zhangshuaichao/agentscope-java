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
package io.agentscope.extensions.scheduler.config;

import io.agentscope.core.model.Model;

/**
 * 调度任务的模型配置接口，封装模型设置，为不同提供方提供统一契约。
 * <p>配置可序列化以便持久化和传输，与模型实现分离；强类型对象支持多种模型提供方。
 * <p>示例：
 * <pre>{@code
 * // 创建模型配置
 * ModelConfig modelConfig = DashScopeModelConfig.builder()
 *     .apiKey(apiKey)
 *     .modelName("qwen-max")
 *     .stream(true)
 *     .build();
 *
 * // 在 AgentConfig 中使用
 * AgentConfig agentConfig = AgentConfig.builder()
 *     .name("MyAgent")
 *     .modelConfig(modelConfig)
 *     .build();
 * }</pre>
 * @see AgentConfig
 * @see DashScopeModelConfig
 */
public interface ModelConfig {

    /**
     * 获取模型名称或标识，用于日志、识别及路由。
     * @return 模型名称／标识
     */
    String getModelName();

    /**
     * 根据配置创建实际 Model，供智能体执行。每次调用返回新实例还是缓存实例，由实现决定。
     * <p>示例：
     * <pre>{@code
     * ModelConfig modelConfig = DashScopeModelConfig.builder()
     *     .apiKey(apiKey)
     *     .modelName("qwen-max")
     *     .build();
     *
     * // 创建实际模型实例
     * Model model = modelConfig.createModel();
     * }</pre>
     * @return 按配置创建的 Model
     * @throws IllegalStateException 无法创建模型时
     */
    Model createModel();
}
