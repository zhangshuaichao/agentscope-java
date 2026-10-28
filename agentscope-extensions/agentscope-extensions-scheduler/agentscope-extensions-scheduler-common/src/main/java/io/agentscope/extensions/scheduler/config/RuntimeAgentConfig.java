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

import io.agentscope.core.hook.Hook;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 扩展 AgentConfig，加入不可序列化运行时对象的配置。
 * <p>需要在同一 JVM 中配置 Toolkit、Hook 时使用 RuntimeAgentConfig；持久化配置或分布式调度通常使用 AgentConfig。
 * <p>示例：
 * <pre>{@code
 * // 创建工具包
 * Toolkit toolkit = new Toolkit();
 * toolkit.registerTool(new WeatherTool());
 *
 * // 创建 Hook
 * List<Hook> hooks = Arrays.asList(
 *     new LoggingHook(),
 *     new MetricsHook()
 * );
 *
 * // 创建运行时配置
 * RuntimeAgentConfig config = RuntimeAgentConfig.builder()
 *     .name("MyAgent")
 *     .modelConfig(modelConfig)
 *     .sysPrompt("You are a helpful assistant.")
 *     .toolkit(toolkit)      // 运行时对象
 *     .hooks(hooks)          // 运行时对象
 *     .build();
 *
 * // 使用运行时配置调度
 * ScheduleAgentTask task = scheduler.schedule(config, scheduleConfig);
 * }</pre>
 * <p>继承可序列化配置，将工具包和 Hook 作为仅限运行时属性，不适合直接序列化或跨进程传递；构建后配置字段不可变，不代表引用的运行时对象不可变。
 * @see AgentConfig
 * @see Toolkit
 * @see Hook
 */
public class RuntimeAgentConfig extends AgentConfig {

    private final Model model;
    private final Toolkit toolkit;
    private final List<Hook> hooks;

    private RuntimeAgentConfig(Builder builder) {
        super(builder);
        this.toolkit = builder.toolkit;
        this.hooks = new CopyOnWriteArrayList<>(builder.hooks != null ? builder.hooks : List.of());
        this.model = builder.model;
    }

    /**
     * 创建构建器。
     * @return 新 RuntimeAgentConfig.Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 获取工具包。
     * @return 工具包实例，可为 null
     */
    public Toolkit getToolkit() {
        return toolkit;
    }

    /**
     * 获取 Hook。
     * @return Hook 列表，不为 null，但可为空
     */
    public List<Hook> getHooks() {
        return hooks;
    }

    /**
     * 获取预先创建的 Model。构建时从 ModelConfig 自动创建，避免每次执行智能体都重复创建模型。
     * @return Model 实例，未设置时可为 null
     */
    public Model getModel() {
        return model;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (!super.equals(o)) return false;
        RuntimeAgentConfig that = (RuntimeAgentConfig) o;
        return Objects.equals(model, that.model)
                && Objects.equals(toolkit, that.toolkit)
                && Objects.equals(hooks, that.hooks);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), model, toolkit, hooks);
    }

    @Override
    public String toString() {
        return "RuntimeAgentConfig{"
                + "name='"
                + getName()
                + '\''
                + ", modelConfig="
                + getModelConfig()
                + ", sysPrompt='"
                + getSysPrompt()
                + '\''
                + ", model="
                + model
                + ", toolkit="
                + toolkit
                + ", hooks="
                + hooks
                + '}';
    }

    /**
     * 创建 RuntimeAgentConfig 的构建器。
     */
    public static class Builder extends AgentConfig.Builder {

        private Toolkit toolkit;

        private List<Hook> hooks;

        private Model model;

        private Builder() {}

        /**
         * 设置必填智能体名称。
         * @param name 智能体名称
         * @return 当前构建器
         */
        @Override
        public Builder name(String name) {
            super.name(name);
            return this;
        }

        /**
         * 设置必填模型配置。构建过程中自动根据 ModelConfig 创建 Model，可直接复用而无需重复创建。
         * @param modelConfig 模型配置
         * @return 当前构建器
         */
        @Override
        public Builder modelConfig(ModelConfig modelConfig) {
            super.modelConfig(modelConfig);
            this.model = modelConfig.createModel();
            return this;
        }

        /**
         * 设置可选系统提示词。
         * @param sysPrompt 系统提示词
         * @return 当前构建器
         */
        @Override
        public Builder sysPrompt(String sysPrompt) {
            super.sysPrompt(sysPrompt);
            return this;
        }

        /**
         * 设置可选工具包，提供智能体执行时可用的工具。
         * @param toolkit 工具包实例
         * @return 当前构建器
         */
        public Builder toolkit(Toolkit toolkit) {
            this.toolkit = toolkit;
            return this;
        }

        /**
         * 设置可选 Hook，提供智能体生命周期扩展点。
         * @param hooks Hook 列表
         * @return 当前构建器
         */
        public Builder hooks(List<Hook> hooks) {
            this.hooks = hooks;
            return this;
        }

        /**
         * 构建 RuntimeAgentConfig。
         * @return 新实例
         * @throws IllegalArgumentException 必填字段缺失或无效时
         */
        @Override
        public RuntimeAgentConfig build() {
            return new RuntimeAgentConfig(this);
        }
    }
}
