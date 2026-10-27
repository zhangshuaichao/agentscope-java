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
package io.agentscope.core.agui.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 通过 AG-UI 协议运行智能体的输入模型。
 *
 * <p>表示调用智能体所需的完整输入，包括消息、工具、上下文、状态与透传属性。
 */
public class RunAgentInput {

    private final String threadId;
    private final String runId;
    private final List<AguiMessage> messages;
    private final List<AguiTool> tools;
    private final List<AguiContext> context;
    private final Map<String, Object> state;
    private final Map<String, Object> forwardedProps;
    private final List<AguiResume> resume;

    /**
     * 创建 RunAgentInput。
     *
     * @param threadId 本会话的线程 ID
     * @param runId 唯一运行 ID
     * @param messages 会话消息
     * @param tools 前端提供的工具
     * @param context 额外上下文信息
     * @param state 要加载的初始状态
     * @param forwardedProps 额外透传属性
     */
    public RunAgentInput(
            @JsonProperty("threadId") String threadId,
            @JsonProperty("runId") String runId,
            @JsonProperty("messages") List<AguiMessage> messages,
            @JsonProperty("tools") List<AguiTool> tools,
            @JsonProperty("context") List<AguiContext> context,
            @JsonProperty("state") Map<String, Object> state,
            @JsonProperty("forwardedProps") Map<String, Object> forwardedProps) {
        this(threadId, runId, messages, tools, context, state, forwardedProps, null);
    }

    /**
     * 创建 RunAgentInput。
     *
     * @param threadId 本会话的线程 ID
     * @param runId 唯一运行 ID
     * @param messages 会话消息
     * @param tools 前端提供的工具
     * @param context 额外上下文信息
     * @param state 要加载的初始状态
     * @param forwardedProps 额外透传属性
     * @param resume 对先前已发送中断的恢复响应
     */
    @JsonCreator
    public RunAgentInput(
            @JsonProperty("threadId") String threadId,
            @JsonProperty("runId") String runId,
            @JsonProperty("messages") List<AguiMessage> messages,
            @JsonProperty("tools") List<AguiTool> tools,
            @JsonProperty("context") List<AguiContext> context,
            @JsonProperty("state") Map<String, Object> state,
            @JsonProperty("forwardedProps") Map<String, Object> forwardedProps,
            @JsonProperty("resume") List<AguiResume> resume) {
        this.threadId = Objects.requireNonNull(threadId, "threadId cannot be null");
        this.runId = Objects.requireNonNull(runId, "runId cannot be null");
        this.messages =
                messages != null ? Collections.unmodifiableList(messages) : Collections.emptyList();
        this.tools = tools != null ? Collections.unmodifiableList(tools) : Collections.emptyList();
        this.context =
                context != null ? Collections.unmodifiableList(context) : Collections.emptyList();
        this.state =
                state != null
                        ? Collections.unmodifiableMap(new HashMap<>(state))
                        : Collections.emptyMap();
        this.forwardedProps =
                forwardedProps != null
                        ? Collections.unmodifiableMap(new HashMap<>(forwardedProps))
                        : Collections.emptyMap();
        this.resume =
                resume != null ? Collections.unmodifiableList(resume) : Collections.emptyList();
    }

    /**
     * 获取线程 ID。
     *
     * @return 线程 ID
     */
    public String getThreadId() {
        return threadId;
    }

    /**
     * 获取运行 ID。
     *
     * @return 运行 ID
     */
    public String getRunId() {
        return runId;
    }

    /**
     * 获取会话消息。
     *
     * @return 不可变消息列表
     */
    public List<AguiMessage> getMessages() {
        return messages;
    }

    /**
     * 获取前端提供的工具。
     *
     * @return 不可变工具列表
     */
    public List<AguiTool> getTools() {
        return tools;
    }

    /**
     * 获取上下文信息。
     *
     * @return 不可变上下文列表
     */
    public List<AguiContext> getContext() {
        return context;
    }

    /**
     * 获取初始状态。
     *
     * @return 不可变状态映射
     */
    public Map<String, Object> getState() {
        return state;
    }

    /**
     * 获取透传属性。
     *
     * @return 不可变透传属性映射
     */
    public Map<String, Object> getForwardedProps() {
        return forwardedProps;
    }

    /**
     * 获取先前已发送中断的恢复响应。
     *
     * @return 不可变恢复响应列表
     */
    public List<AguiResume> getResume() {
        return resume;
    }

    /**
     * 获取指定透传属性。
     *
     * @param key 属性键
     * @return 属性值；不存在时为 null
     */
    public Object getForwardedProp(String key) {
        return forwardedProps.get(key);
    }

    /**
     * 获取指定透传属性，支持默认值。
     *
     * @param key 属性键
     * @param defaultValue 不存在时的默认值
     * @return 属性值；不存在时为默认值
     */
    public Object getForwardedProp(String key, Object defaultValue) {
        return forwardedProps.getOrDefault(key, defaultValue);
    }

    /**
     * 检查是否存在消息。
     *
     * @return 存在消息时为 true
     */
    public boolean hasMessages() {
        return messages != null && !messages.isEmpty();
    }

    /**
     * 检查是否存在前端工具。
     *
     * @return 存在工具时为 true
     */
    public boolean hasTools() {
        return tools != null && !tools.isEmpty();
    }

    /**
     * 检查是否存在上下文。
     *
     * @return 存在上下文时为 true
     */
    public boolean hasContext() {
        return context != null && !context.isEmpty();
    }

    /**
     * 检查是否存在初始状态。
     *
     * @return 存在状态时为 true
     */
    public boolean hasState() {
        return state != null && !state.isEmpty();
    }

    /**
     * 检查是否存在中断恢复响应。
     *
     * @return 存在恢复响应时为 true
     */
    public boolean hasResume() {
        return resume != null && !resume.isEmpty();
    }

    @Override
    public String toString() {
        return "RunAgentInput{threadId='"
                + threadId
                + "', runId='"
                + runId
                + "', messages="
                + messages.size()
                + ", tools="
                + tools.size()
                + ", context="
                + context.size()
                + ", state="
                + state.size()
                + ", forwardedProps="
                + forwardedProps.size()
                + ", resume="
                + resume.size()
                + "}";
    }

    /**
     * 创建 RunAgentInput 构建器。
     *
     * @return 新构建器实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * RunAgentInput 的构建器。
     */
    public static class Builder {
        private String threadId;
        private String runId;
        private List<AguiMessage> messages;
        private List<AguiTool> tools;
        private List<AguiContext> context;
        private Map<String, Object> state;
        private Map<String, Object> forwardedProps;
        private List<AguiResume> resume;

        public Builder threadId(String threadId) {
            this.threadId = threadId;
            return this;
        }

        public Builder runId(String runId) {
            this.runId = runId;
            return this;
        }

        public Builder messages(List<AguiMessage> messages) {
            this.messages = messages;
            return this;
        }

        public Builder tools(List<AguiTool> tools) {
            this.tools = tools;
            return this;
        }

        public Builder context(List<AguiContext> context) {
            this.context = context;
            return this;
        }

        public Builder state(Map<String, Object> state) {
            this.state = state;
            return this;
        }

        public Builder forwardedProps(Map<String, Object> forwardedProps) {
            this.forwardedProps = forwardedProps;
            return this;
        }

        public Builder resume(List<AguiResume> resume) {
            this.resume = resume;
            return this;
        }

        public RunAgentInput build() {
            return new RunAgentInput(
                    threadId, runId, messages, tools, context, state, forwardedProps, resume);
        }
    }
}
