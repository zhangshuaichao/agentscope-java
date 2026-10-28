/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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
package io.agentscope.core.chat.completions.model;

import java.util.List;

/**
 * Chat Completions HTTP API 的请求载荷。
 *
 * <p>请求字段采用 OpenAI Chat Completions API 风格；具体支持的字段以本类定义为准。
 *
 * <p><b>无状态操作：</b>
 *
 * <p>此 API 按无状态方式工作，客户端负责维护对话历史，并在每次请求中发送完整的 {@code messages} 数组。
 *
 * <p><b>请求示例：</b>
 *
 * <pre>{@code
 * {
 *   "model": "gpt-4",
 *   "messages": [
 *     {"role": "system", "content": "You are a helpful assistant."},
 *     {"role": "user", "content": "What's the weather in Hangzhou?"},
 *     {"role": "assistant", "content": "Let me check...", "tool_calls": [...]},
 *     {"role": "tool", "tool_call_id": "call_xxx", "content": "25°C sunny"},
 *     {"role": "user", "content": "Thanks! And tomorrow?"}
 *   ],
 *   "stream": false
 * }
 * }</pre>
 *
 * <p><b>工具调用流程：</b>
 *
 * <p>assistant 调用工具时，响应包含 {@code tool_calls}。客户端应：
 *
 * <ol>
 *   <li>将含 tool_calls 的 assistant 消息追加到历史
 *   <li>为每次工具调用追加工具结果消息
 *   <li>在下一次请求中发送更新后的历史
 * </ol>
 */
public class ChatCompletionsRequest {

    /** 用于生成的模型 ID，按 OpenAI 规范属于必填字段。 */
    private String model;

    /**
     * 截至当前的对话消息列表。
     *
     * <p>必填字段。为支持无状态操作，客户端必须发送完整对话历史。
     */
    private List<ChatMessage> messages;

    /** 是否通过服务器发送事件（SSE）流式响应；可选，默认为 false。 */
    private Boolean stream;

    /**
     * 模型可以调用的工具列表，目前仅支持函数工具。
     *
     * <p>提供的工具注册为仅含模式的工具。智能体决定调用工具时，执行会挂起，并将工具调用返回客户端，由客户端在外部执行。
     */
    private List<OpenAITool> tools;

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public List<ChatMessage> getMessages() {
        return messages;
    }

    public void setMessages(List<ChatMessage> messages) {
        this.messages = messages;
    }

    public Boolean getStream() {
        return stream;
    }

    public void setStream(Boolean stream) {
        this.stream = stream;
    }

    public List<OpenAITool> getTools() {
        return tools;
    }

    public void setTools(List<OpenAITool> tools) {
        this.tools = tools;
    }
}
