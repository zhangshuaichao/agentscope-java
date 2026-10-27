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
package io.agentscope.core.interruption;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolUseBlock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 一次中断的上下文信息。
 *
 * <p>记录中断发生时间和原因的元数据，
 * 包括来源、时间戳、用户消息（如有）以及被中断的待处理工具调用。
 *
 * <p>使用示例：
 * <pre>{@code
 * InterruptContext context = InterruptContext.builder()
 *     .source(InterruptSource.USER)
 *     .userMessage(userMsg)
 *     .pendingToolCalls(toolCalls)
 *     .build();
 * }</pre>
 */
public class InterruptContext {

    private final InterruptSource source;
    private final Instant timestamp;
    private final Msg userMessage;
    private final List<ToolUseBlock> pendingToolCalls;

    private InterruptContext(
            InterruptSource source,
            Instant timestamp,
            Msg userMessage,
            List<ToolUseBlock> pendingToolCalls) {
        this.source = source;
        this.timestamp = timestamp;
        this.userMessage = userMessage;
        this.pendingToolCalls =
                pendingToolCalls != null ? List.copyOf(pendingToolCalls) : List.of();
    }

    /**
     * 获取中断来源。
     *
     * @return 中断来源
     */
    public InterruptSource getSource() {
        return source;
    }

    /**
     * 获取中断发生时的时间戳。
     *
     * @return 中断时间戳
     */
    public Instant getTimestamp() {
        return timestamp;
    }

    /**
     * 获取触发中断的用户消息（如有）。
     *
     * @return 用户消息；不适用时为 null
     */
    public Msg getUserMessage() {
        return userMessage;
    }

    /**
     * 获取中断发生时尚待处理的工具调用列表。
     *
     * @return 不可修改的待处理工具调用列表
     */
    public List<ToolUseBlock> getPendingToolCalls() {
        return pendingToolCalls;
    }

    /**
     * 创建新的 InterruptContext 构建器。
     *
     * @return 新的构建器实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * InterruptContext 的构建器。
     */
    public static class Builder {
        private InterruptSource source = InterruptSource.USER;
        private Instant timestamp = Instant.now();
        private Msg userMessage;
        private List<ToolUseBlock> pendingToolCalls = new ArrayList<>();

        private Builder() {}

        /**
         * 设置中断来源。
         *
         * @param source 中断来源
         * @return 此构建器
         */
        public Builder source(InterruptSource source) {
            this.source = source;
            return this;
        }

        /**
         * 设置中断时间戳。
         *
         * @param timestamp 中断时间戳
         * @return 此构建器
         */
        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        /**
         * 设置触发中断的用户消息。
         *
         * @param userMessage 用户消息
         * @return 此构建器
         */
        public Builder userMessage(Msg userMessage) {
            this.userMessage = userMessage;
            return this;
        }

        /**
         * 设置中断发生时尚待处理的工具调用。
         *
         * @param pendingToolCalls 待处理工具调用列表
         * @return 此构建器
         */
        public Builder pendingToolCalls(List<ToolUseBlock> pendingToolCalls) {
            this.pendingToolCalls =
                    pendingToolCalls != null
                            ? new ArrayList<>(pendingToolCalls)
                            : new ArrayList<>();
            return this;
        }

        /**
         * 构建 InterruptContext。
         *
         * @return 新的 InterruptContext 实例
         */
        public InterruptContext build() {
            return new InterruptContext(source, timestamp, userMessage, pendingToolCalls);
        }
    }

    /**
     * 返回中断上下文的字符串表示。
     *
     * <p>该字符串以紧凑格式包含中断来源、时间戳、是否有用户消息，
     * 以及待处理工具调用的数量。
     *
     * @return 中断上下文的格式化字符串表示
     */
    @Override
    public String toString() {
        return String.format(
                "InterruptContext{source=%s, timestamp=%s, userMessage=%s, pendingToolCalls=%d}",
                source,
                timestamp,
                userMessage != null ? "present" : "null",
                pendingToolCalls.size());
    }
}
