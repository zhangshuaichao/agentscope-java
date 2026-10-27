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
package io.agentscope.core.tool;

import io.agentscope.core.model.ExecutionConfig;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/**
 * Toolkit 执行行为配置。
 *
 * <p>定义工具执行方式：
 * - 并行或顺序执行
 * - 可选自定义 ExecutorService
 * - 超时和重试配置
 *
 * <p>默认一轮中的多个工具调用在 Reactor 调度器上并行运行。默认超时为 5 分钟，不重试（1 次尝试）。传入 {@code parallel(false)} 可串行执行。
 */
public class ToolkitConfig {

    private final boolean parallel;
    private final ExecutorService executorService;
    private final ExecutionConfig executionConfig;
    private final boolean allowToolDeletion;
    private final ToolExecutionContext defaultContext;
    private final List<ToolCallInterceptor> interceptors;
    private final Consumer<ToolCallParam> beforeToolCall;

    private ToolkitConfig(Builder builder) {
        this.parallel = builder.parallel;
        this.executorService = builder.executorService;
        this.executionConfig = builder.executionConfig;
        this.allowToolDeletion = builder.allowToolDeletion;
        this.defaultContext = builder.defaultContext;
        this.interceptors = List.copyOf(builder.interceptors);
        this.beforeToolCall = builder.beforeToolCall;
    }

    /**
     * 是否并行执行工具。
     *
     * @return 启用并行执行时为 true
     */
    public boolean isParallel() {
        return parallel;
    }

    /**
     * 获取提供的自定义执行器服务。
     *
     * @return ExecutorService；使用默认 Reactor 调度器时为 null
     */
    public ExecutorService getExecutorService() {
        return executorService;
    }

    /**
     * 检查是否提供了自定义执行器服务。
     *
     * @return 已配置自定义执行器时为 true
     */
    public boolean hasCustomExecutor() {
        return executorService != null;
    }

    /**
     * 获取超时和重试执行配置。
     *
     * @return ExecutionConfig；未配置时为 null（将应用默认值）
     */
    public ExecutionConfig getExecutionConfig() {
        return executionConfig;
    }

    /**
     * 检查是否允许删除工具。
     *
     * @return 允许删除为 true（默认），否则为 false
     */
    public boolean isAllowToolDeletion() {
        return allowToolDeletion;
    }

    /**
     * 获取默认工具执行上下文。
     *
     * @return 默认上下文；未配置时为 null
     * @deprecated 请改用 {@link io.agentscope.core.agent.RuntimeContext}。
     */
    @Deprecated
    public ToolExecutionContext getDefaultContext() {
        return defaultContext;
    }

    /**
     * 获取构建期注册的工具调用拦截器。
     *
     * @return 不可变拦截器列表（未注册时为空）
     */
    public List<ToolCallInterceptor> getInterceptors() {
        return interceptors;
    }

    /**
     * 获取可选的最终分派检查。
     *
     * @return 检查器；未配置时为 null
     */
    public Consumer<ToolCallParam> getBeforeToolCall() {
        return beforeToolCall;
    }

    /**
     * 创建 ToolkitConfig 构建器。
     *
     * @return 构建器实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 获取默认配置（基于 Reactor 并行执行）。
     *
     * @return 默认 ToolkitConfig
     */
    public static ToolkitConfig defaultConfig() {
        return builder().build();
    }

    /**
     * ToolkitConfig 的构建器。
     */
    public static class Builder {
        private boolean parallel = true;
        private ExecutorService executorService;
        private ExecutionConfig executionConfig;
        private boolean allowToolDeletion = true;
        private ToolExecutionContext defaultContext;
        private List<ToolCallInterceptor> interceptors = List.of();
        private Consumer<ToolCallParam> beforeToolCall;

        private Builder() {}

        /**
         * 设置在每次本地调用或外部工具挂起前立即运行的同步非阻塞检查，包括重试和重复订阅。本地检查在拦截器与分派持久化之后运行，接收最终参数。抛出普通 RuntimeException 可通过现有工具错误结果路径拒绝分派。不要抛出 ToolSuspendException，因为它表示挂起而非拒绝。不会取消已经开始的调用。工具集副本保留此检查，因此应使用运行级状态或从调用的 RuntimeContext 解析状态。
         *
         * @param beforeToolCall 可选分派检查；null 表示禁用
         * @return 当前构建器
         */
        public Builder beforeToolCall(Consumer<ToolCallParam> beforeToolCall) {
            this.beforeToolCall = beforeToolCall;
            return this;
        }

        /**
         * 设置是否并行执行工具。
         *
         * @param parallel true 表示并行，false 表示顺序
         * @return 当前构建器
         */
        public Builder parallel(boolean parallel) {
            this.parallel = parallel;
            return this;
        }

        /**
         * 设置工具执行使用的自定义执行器服务。未设置时使用 Reactor 的 Schedulers.boundedElastic()。
         *
         * @param executorService 自定义执行器服务
         * @return 当前构建器
         */
        public Builder executorService(ExecutorService executorService) {
            this.executorService = executorService;
            return this;
        }

        /**
         * 设置超时和重试行为。未设置时应用工具执行默认值（超时 5 分钟，不重试）。
         *
         * @param executionConfig 工具执行配置
         * @return 当前构建器
         */
        public Builder executionConfig(ExecutionConfig executionConfig) {
            this.executionConfig = executionConfig;
            return this;
        }

        /**
         * 设置是否允许删除工具。为 false 时，忽略 removeTool()、removeToolGroups() 和 updateToolGroups(groups, false) 调用。
         *
         * @param allowToolDeletion true 表示允许删除（默认），false 表示禁止
         * @return 当前构建器
         */
        public Builder allowToolDeletion(boolean allowToolDeletion) {
            this.allowToolDeletion = allowToolDeletion;
            return this;
        }

        /**
         * 设置所有工具的默认执行上下文。
         *
         * @param defaultContext 默认执行上下文
         * @return 当前构建器
         * @deprecated 请改用 {@link io.agentscope.core.agent.RuntimeContext}。
         */
        @Deprecated
        public Builder defaultContext(ToolExecutionContext defaultContext) {
            this.defaultContext = defaultContext;
            return this;
        }

        /**
         * 追加一个或多个工具调用拦截器，可重复调用。
         *
         * @param interceptors 要追加的拦截器（均不能为 null）
         * @return 当前构建器
         */
        public Builder interceptor(ToolCallInterceptor... interceptors) {
            List<ToolCallInterceptor> appended = new ArrayList<>(this.interceptors);
            appended.addAll(Arrays.asList(interceptors));
            this.interceptors = appended;
            return this;
        }

        /**
         * 设置工具调用拦截器列表，替换之前追加的拦截器。
         *
         * @param interceptors 要设置的拦截器；{@code null} 表示清空
         * @return 当前构建器
         */
        public Builder interceptors(List<ToolCallInterceptor> interceptors) {
            this.interceptors = interceptors != null ? interceptors : List.of();
            return this;
        }

        /**
         * 构建 ToolkitConfig。
         *
         * @return 配置完成的 ToolkitConfig 实例
         */
        public ToolkitConfig build() {
            return new ToolkitConfig(this);
        }
    }
}
