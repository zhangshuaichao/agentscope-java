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

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolUseBlock;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 工具调用参数。
 *
 * <p>封装调用工具所需的全部信息，包括工具使用块（调用 ID、工具名称等元数据）、输入参数和调用方智能体。
 *
 * <p>使用参数对象而非多个方法参数可提升扩展性：新增参数无需破坏现有代码。
 *
 * <p><b>使用示例：</b>
 * <pre>{@code
 * ToolCallParam param = ToolCallParam.builder()
 *     .toolUseBlock(toolUseBlock)
 *     .input(Map.of("key", "value"))
 *     .agent(agent)
 *     .build();
 *
 * ToolResultBlock result = tool.callAsync(param).block();
 * }</pre>
 */
public class ToolCallParam {

    private final ToolUseBlock toolUseBlock;
    private final Map<String, Object> input;
    private final Agent agent;
    private final RuntimeContext runtimeContext;
    private final ToolEmitter emitter;

    private ToolCallParam(Builder builder) {
        this.toolUseBlock = builder.toolUseBlock;
        this.input = builder.input != null ? new HashMap<>(builder.input) : Collections.emptyMap();
        this.agent = builder.agent;
        this.runtimeContext = builder.runtimeContext;
        this.emitter = builder.emitter;
    }

    /**
     * 获取包含调用元数据的工具使用块。
     *
     * @return 工具使用块
     */
    public ToolUseBlock getToolUseBlock() {
        return toolUseBlock;
    }

    /**
     * 获取工具调用输入参数。
     *
     * @return 不可修改的输入参数映射
     */
    public Map<String, Object> getInput() {
        return Collections.unmodifiableMap(input);
    }

    /**
     * 获取发起调用的智能体。
     *
     * @return 智能体；未提供时为 null
     */
    public Agent getAgent() {
        return agent;
    }

    /**
     * 获取工具调用的运行时上下文。
     *
     * @return 运行时上下文；未提供时为 null
     */
    public RuntimeContext getRuntimeContext() {
        return runtimeContext;
    }

    /**
     * 获取工具执行上下文。
     *
     * @return 从 RuntimeContext 派生的执行上下文，或 null
     * @deprecated 请改用 {@link #getRuntimeContext()}。
     */
    @Deprecated
    public ToolExecutionContext getContext() {
        return runtimeContext != null ? runtimeContext.asToolExecutionContext() : null;
    }

    /**
     * 获取用于流式工具输出的 ToolEmitter。
     *
     * <p>工具可在执行中发送中间进度更新。未配置时返回静默丢弃数据块的空操作发射器。
     *
     * @return ToolEmitter，永不为 null
     */
    public ToolEmitter getEmitter() {
        return emitter != null ? emitter : NoOpToolEmitter.INSTANCE;
    }

    /**
     * 创建用于构造 ToolCallParam 实例的构建器。
     *
     * @return 新的构建器
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 创建以现有 ToolCallParam 值初始化的构建器。
     *
     * <p>适用于构造修改后的参数对象副本。复制源对象的全部值，构建新实例前可选择性修改。
     *
     * <p><b>使用示例：</b>
     * <pre>{@code
     * ToolCallParam original = ...;
     * ToolCallParam modified = ToolCallParam.builder(original)
     *     .input(Map.of("newKey", "newValue"))
     *     .build();
     * }</pre>
     *
     * <p>注意：复制输入映射结构，使各条目可独立修改，但嵌套值（Object 类型）仍然共享。不可变字段（toolUseBlock、agent、context、emitter）通过引用共享。
     *
     * @param source 用于复制值的现有 ToolCallParam
     * @return 预填了源对象值的新构建器
     * @throws NullPointerException 当 source 为 null 时
     */
    public static Builder builder(ToolCallParam source) {
        Objects.requireNonNull(source, "source must not be null");
        return new Builder(source);
    }

    /**
     * ToolCallParam 的构建器。
     */
    public static class Builder {
        private ToolUseBlock toolUseBlock;
        private Map<String, Object> input;
        private Agent agent;
        private RuntimeContext runtimeContext;
        private ToolEmitter emitter;

        private Builder() {}

        private Builder(ToolCallParam source) {
            this.toolUseBlock = source.toolUseBlock;
            this.input = source.input.isEmpty() ? null : source.input;
            this.agent = source.agent;
            this.runtimeContext = source.runtimeContext;
            this.emitter = source.emitter;
        }

        /**
         * 设置工具使用块。
         *
         * @param toolUseBlock 工具使用块
         * @return 当前构建器
         */
        public Builder toolUseBlock(ToolUseBlock toolUseBlock) {
            this.toolUseBlock = toolUseBlock;
            return this;
        }

        /**
         * 设置输入参数。
         *
         * @param input 输入参数
         * @return 当前构建器
         */
        public Builder input(Map<String, Object> input) {
            this.input = input;
            return this;
        }

        /**
         * 设置调用方智能体。
         *
         * @param agent 智能体
         * @return 当前构建器
         */
        public Builder agent(Agent agent) {
            this.agent = agent;
            return this;
        }

        /**
         * 设置工具调用的运行时上下文。
         *
         * @param runtimeContext 运行时上下文
         * @return 当前构建器
         */
        public Builder runtimeContext(RuntimeContext runtimeContext) {
            this.runtimeContext = runtimeContext;
            return this;
        }

        /**
         * 设置工具执行上下文。
         *
         * @param context 执行上下文
         * @return 当前构建器
         * @deprecated 请改用 {@link #runtimeContext(RuntimeContext)}。
         */
        @Deprecated
        public Builder context(ToolExecutionContext context) {
            if (context != null) {
                RuntimeContext.Builder rcb = RuntimeContext.builder();
                rcb.toolExecutionContext(context);
                this.runtimeContext = rcb.build();
            }
            return this;
        }

        /**
         * 设置用于流式工具输出的 ToolEmitter。
         *
         * @param emitter ToolEmitter
         * @return 当前构建器
         */
        public Builder emitter(ToolEmitter emitter) {
            this.emitter = emitter;
            return this;
        }

        /**
         * 构建 ToolCallParam 实例。
         *
         * @return 新的 ToolCallParam
         */
        public ToolCallParam build() {
            return new ToolCallParam(this);
        }
    }
}
