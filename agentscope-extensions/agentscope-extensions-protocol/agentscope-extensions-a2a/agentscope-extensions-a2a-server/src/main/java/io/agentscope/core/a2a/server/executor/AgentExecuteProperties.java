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

package io.agentscope.core.a2a.server.executor;

/**
 * 智能体执行相关属性。
 *
 * <p>其中部分属性用于配置 {@link AgentScopeAgentExecutor} 中的智能体执行。
 */
public class AgentExecuteProperties {

    /**
     * 智能体任务执行是否以消息完成。
     *
     * <p>仅用于非阻塞任务请求；阻塞任务请求始终以消息完成。
     * <p>为 {@code true} 时，任务以包含完整执行结果的消息完成。
     */
    private final boolean completeWithMessage;

    /**
     * 智能体任务执行是否需要暴露工具结果、提示等内部消息。
     *
     * <p>为 {@code true} 时，向 A2A 客户端暴露内部
     * {@link io.agentscope.core.event.AgentEvent} 载荷，例如工具结果增量和提示块。
     */
    private final boolean requireInnerMessage;

    private AgentExecuteProperties(boolean completeWithMessage, boolean requireInnerMessage) {
        this.completeWithMessage = completeWithMessage;
        this.requireInnerMessage = requireInnerMessage;
    }

    public boolean isCompleteWithMessage() {
        return completeWithMessage;
    }

    public boolean isRequireInnerMessage() {
        return requireInnerMessage;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private boolean completeWithMessage;
        private boolean requireInnerMessage;

        public Builder completeWithMessage(boolean completeWithMessage) {
            this.completeWithMessage = completeWithMessage;
            return this;
        }

        public Builder requireInnerMessage(boolean requireInnerMessage) {
            this.requireInnerMessage = requireInnerMessage;
            return this;
        }

        public AgentExecuteProperties build() {
            return new AgentExecuteProperties(completeWithMessage, requireInnerMessage);
        }
    }
}
