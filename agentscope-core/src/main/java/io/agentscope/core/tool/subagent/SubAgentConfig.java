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

import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.state.JsonFileAgentStateStore;

/**
 * 子智能体注册配置。
 *
 * <p>配置子智能体如何作为工具暴露，支持从智能体自身推导工具名称和描述的智能默认值。
 *
 * <p>子智能体以会话模式运行，通过会话管理支持多轮对话。工具暴露两个参数：
 * <ul>
 * <li>{@code message}：必填，发送给智能体的消息。
 * <li>{@code conversation_id}：可选，省略时开启新会话，提供时继续已有会话。
 * </ul>
 *
 * <p><b>默认行为：</b>
 * <ul>
 * <li>工具名称：从智能体名称推导（例如 "ResearchAgent" → "call_researchagent"）。
 * <li>描述：使用智能体描述，或生成默认描述。
 * <li>AgentStateStore：使用 {@link InMemoryAgentStateStore} 管理会话状态。
 * <li>事件转发：默认启用。
 * </ul>
 *
 * <p>用法示例：
 * <pre>{@code
 * // Minimal configuration - uses all defaults
 * SubAgentConfig config = SubAgentConfig.defaults();
 *
 * // Custom configuration with persistent session
 * SubAgentConfig config = SubAgentConfig.builder()
 *     .toolName("ask_expert")
 *     .description("Ask the expert a question")
 *     .stateStore(new JsonFileAgentStateStore(Path.of("sessions")))
 *     .build();
 * }</pre>
 */
public class SubAgentConfig {

    private final String toolName;
    private final String description;
    private final boolean forwardEvents;
    private final StreamOptions streamOptions;
    private final AgentStateStore stateStore;

    private SubAgentConfig(Builder builder) {
        this.toolName = builder.toolName;
        this.description = builder.description;
        this.forwardEvents = builder.forwardEvents;
        this.streamOptions = builder.streamOptions;
        this.stateStore =
                builder.stateStore != null ? builder.stateStore : new InMemoryAgentStateStore();
    }

    /**
     * 创建默认配置。
     *
     * <p>注册时从智能体推导工具名称和描述。
     *
     * @return 默认配置
     */
    public static SubAgentConfig defaults() {
        return builder().build();
    }

    /**
     * 创建构建器。
     *
     * @return 新的构建器实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 获取覆盖的工具名称。
     *
     * @return 工具名称；为 null 时从智能体推导
     */
    public String getToolName() {
        return toolName;
    }

    /**
     * 获取覆盖的描述。
     *
     * @return 描述；为 null 时从智能体推导
     */
    public String getDescription() {
        return description;
    }

    /**
     * 获取是否向父智能体转发子智能体事件。
     *
     * <p>启用时，通过 ToolEmitter 将子智能体事件（推理分片、工具执行分片）转发到父智能体的钩子。
     *
     * @return 是否转发事件（默认 true）
     */
    public boolean isForwardEvents() {
        return forwardEvents;
    }

    /**
     * 获取事件转发的流选项。
     *
     * <p>控制转发的事件类型及流模式（增量或累计）。仅在 {@link #isForwardEvents()} 返回 true 时适用。
     *
     * @return 流选项；为 null 时使用默认值
     */
    public StreamOptions getStreamOptions() {
        return streamOptions;
    }

    /**
     * 获取用于会话状态管理的会话对象。
     *
     * <p>用于跨对话轮次持久化和恢复子智能体状态。默认使用 {@link InMemoryAgentStateStore}。
     *
     * @return 会话实例（不会为 null）
     */
    public AgentStateStore getStateStore() {
        return stateStore;
    }

    /**
     * SubAgentConfig 的构建器。
     */
    public static class Builder {
        private String toolName;
        private String description;
        private boolean forwardEvents = true;
        private StreamOptions streamOptions;
        private AgentStateStore stateStore;

        private Builder() {}

        /**
         * 设置工具名称。
         *
         * <p>未设置时从智能体名称推导。
         *
         * @param toolName 工具名称
         * @return 当前构建器
         */
        public Builder toolName(String toolName) {
            this.toolName = toolName;
            return this;
        }

        /**
         * 设置工具描述。
         *
         * <p>未设置时从智能体描述推导。
         *
         * @param description 描述
         * @return 当前构建器
         */
        public Builder description(String description) {
            this.description = description;
            return this;
        }

        /**
         * 设置是否向父智能体转发子智能体事件。
         *
         * <p>启用时，通过 ToolEmitter 将子智能体事件（推理分片、工具执行分片）转发到父智能体的钩子。
         *
         * @param forwardEvents 是否转发事件（默认 true）
         * @return 当前构建器
         */
        public Builder forwardEvents(boolean forwardEvents) {
            this.forwardEvents = forwardEvents;
            return this;
        }

        /**
         * 设置事件转发的流选项。
         *
         * <p>控制转发的事件类型和流模式。仅在 {@link #forwardEvents(boolean)} 为 true 时适用。
         *
         * @param streamOptions 流选项（null 表示默认值）
         * @return 当前构建器
         */
        public Builder streamOptions(StreamOptions streamOptions) {
            this.streamOptions = streamOptions;
            return this;
        }

        /**
         * 设置用于会话状态管理的会话对象。
         *
         * <p>用于跨对话轮次持久化和恢复子智能体状态。未设置时默认使用 {@link InMemoryAgentStateStore}。
         *
         * <p>若需要跨进程重启保留对话，请使用 {@link JsonFileAgentStateStore} 等持久化会话实现。
         *
         * @param stateStore 状态存储实例
         * @return 当前构建器
         */
        public Builder stateStore(AgentStateStore stateStore) {
            this.stateStore = stateStore;
            return this;
        }

        /**
         * 构建配置。
         *
         * @return 新的 SubAgentConfig 实例
         */
        public SubAgentConfig build() {
            return new SubAgentConfig(this);
        }
    }
}
