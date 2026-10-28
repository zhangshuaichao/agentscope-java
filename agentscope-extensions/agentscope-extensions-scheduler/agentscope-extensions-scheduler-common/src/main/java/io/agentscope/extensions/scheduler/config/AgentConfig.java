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

import io.agentscope.extensions.scheduler.AgentScheduler;
import io.agentscope.extensions.scheduler.BaseScheduleAgentTask;
import java.util.Objects;

/**
 * 调度任务中智能体实例的可序列化元数据配置。
 * <p>仅包含配置元数据，适合持久化、网络传输及分布式调度；不包含 Toolkit、Hook 等运行时对象。强类型字段提供类型约束，必填值由构建时校验。
 * <p>基础配置与运行时对象配置示例：
 * <pre>{@code
 * // 创建模型配置
 * ModelConfig modelConfig = DashScopeModelConfig.builder()
 *     .apiKey(apiKey)
 *     .modelName("qwen-max")
 *     .stream(true)
 *     .build();
 *
 * // 创建可序列化智能体配置
 * AgentConfig config = AgentConfig.builder()
 *     .name("ScheduledAssistant")
 *     .modelConfig(modelConfig)
 *     .sysPrompt("You are a scheduled assistant.")
 *     .build();
 *
 * // 使用配置调度
 * ScheduleAgentTask task = scheduler.schedule(config, scheduleConfig);
 * }</pre>
 * <pre>{@code
 * // 对于 Toolkit 和 Hook 等运行时对象，使用 RuntimeAgentConfig
 * RuntimeAgentConfig runtimeConfig = RuntimeAgentConfig.builder()
 *     .name("ScheduledAssistant")
 *     .modelConfig(modelConfig)
 *     .sysPrompt("You are a scheduled assistant.")
 *     .toolkit(myToolkit)           // 运行时对象
 *     .hooks(Arrays.asList(hook1))  // 运行时对象
 *     .build();
 *
 * // 使用运行时配置调度
 * ScheduleAgentTask task = scheduler.schedule(runtimeConfig, scheduleConfig);
 * }</pre>
 * <p>字段为 name、modelConfig、sysPrompt；运行时对象使用 {@link RuntimeAgentConfig} 子类。名称与模型配置必填，系统提示词可选；通过构建器构建后不再修改配置字段。
 * @see AgentScheduler
 * @see BaseScheduleAgentTask
 * @see ModelConfig
 * @see RuntimeAgentConfig
 */
public class AgentConfig {

    private final String name;
    private final String sysPrompt;
    private final ModelConfig modelConfig;

    protected AgentConfig(Builder builder) {
        this.name = builder.name;
        this.modelConfig = builder.modelConfig;
        this.sysPrompt = builder.sysPrompt;
        validate();
    }

    /**
     * 校验配置。
     */
    private void validate() {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Agent name must not be null or empty");
        }
        if (modelConfig == null) {
            throw new IllegalArgumentException("ModelConfig must not be null");
        }
    }

    /**
     * 创建构建器。
     * @return 新 AgentConfig.Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 获取智能体名称。作为调度任务标识，应在调度器内唯一；XXL-Job 等实现也将其用作 JobHandler 名称。
     * @return 智能体名称
     */
    public String getName() {
        return name;
    }

    /**
     * 获取模型配置。
     * @return ModelConfig 实例
     */
    public ModelConfig getModelConfig() {
        return modelConfig;
    }

    /**
     * 获取系统提示词。
     * @return 系统提示词，可为 null
     */
    public String getSysPrompt() {
        return sysPrompt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AgentConfig that = (AgentConfig) o;
        return Objects.equals(name, that.name)
                && Objects.equals(modelConfig, that.modelConfig)
                && Objects.equals(sysPrompt, that.sysPrompt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, modelConfig, sysPrompt);
    }

    @Override
    public String toString() {
        return "AgentConfig{"
                + "name='"
                + name
                + '\''
                + ", modelConfig="
                + modelConfig
                + ", sysPrompt='"
                + sysPrompt
                + '\''
                + '}';
    }

    /**
     * 创建 AgentConfig 的构建器。
     */
    public static class Builder {
        private String name;
        private ModelConfig modelConfig;
        private String sysPrompt;

        protected Builder() {}

        /**
         * 设置必填智能体名称；作为调度任务标识，应保持唯一。
         * @param name 智能体名称
         * @return 当前构建器
         */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * 设置必填模型配置。ModelConfig 提供灵活配置方式，DashScope 模型使用 {@link DashScopeModelConfig}。
         * <p>示例：
         * <pre>{@code
         * ModelConfig config = DashScopeModelConfig.builder()
         *     .apiKey(apiKey)
         *     .modelName("qwen-max")
         *     .stream(true)
         *     .build();
         *
         * AgentConfig agentConfig = AgentConfig.builder()
         *     .name("MyAgent")
         *     .modelConfig(config)
         *     .build();
         * }</pre>
         * @param modelConfig 模型配置
         * @return 当前构建器
         */
        public Builder modelConfig(ModelConfig modelConfig) {
            this.modelConfig = modelConfig;
            return this;
        }

        /**
         * 设置可选系统提示词，用于定义智能体角色与行为。
         * @param sysPrompt 系统提示词
         * @return 当前构建器
         */
        public Builder sysPrompt(String sysPrompt) {
            this.sysPrompt = sysPrompt;
            return this;
        }

        /**
         * 构建 AgentConfig。
         * @return 新 AgentConfig 实例
         * @throws IllegalArgumentException 必填字段缺失或无效时
         */
        public AgentConfig build() {
            return new AgentConfig(this);
        }
    }
}
