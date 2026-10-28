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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Chat Completions API 的聊天消息表示。
 *
 * <p>此 DTO 兼容 OpenAI 聊天消息格式，支持：
 *
 * <ul>
 *   <li>基本文本消息（user、assistant、system）
 *   <li>工具调用请求（包含 tool_calls 的 assistant 消息）
 *   <li>工具结果（包含 tool_call_id 的 tool 消息）
 * </ul>
 *
 * <p>格式示例：
 *
 * <pre>{@code
 * // 用户消息
 * {"role": "user", "content": "Hello"}
 *
 * // 含工具调用的 assistant 消息
 * {
 *   "role": "assistant",
 *   "content": "Let me check the weather",
 *   "tool_calls": [{
 *     "id": "call_abc",
 *     "type": "function",
 *     "function": {"name": "get_weather", "arguments": "{\"city\":\"Hangzhou\"}"}
 *   }]
 * }
 *
 * // 工具结果消息
 * {"role": "tool", "tool_call_id": "call_abc", "content": "{\"temp\": 25}"}
 * }</pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatMessage {

    private String role;

    private String content;

    /** assistant 发起的工具调用（仅用于 assistant 角色）。 */
    @JsonProperty("tool_calls")
    private List<ToolCall> toolCalls;

    /** 本消息回应的工具调用 ID（仅用于 tool 角色）。 */
    @JsonProperty("tool_call_id")
    private String toolCallId;

    /** 工具名称（仅用于 tool 角色）。 */
    private String name;

    /** 供反序列化使用的默认构造器。 */
    public ChatMessage() {}

    /**
     * 创建简单文本消息。
     *
     * @param role 消息角色（user、assistant、system、tool）
     * @param content 消息内容
     */
    public ChatMessage(String role, String content) {
        this.role = role;
        this.content = content;
    }

    /**
     * 创建含工具调用的 assistant 消息。
     *
     * @param content 可选文本内容
     * @param toolCalls 工具调用列表
     * @return 新的 ChatMessage 实例
     */
    public static ChatMessage assistantWithToolCalls(String content, List<ToolCall> toolCalls) {
        ChatMessage msg = new ChatMessage("assistant", content);
        msg.setToolCalls(toolCalls);
        return msg;
    }

    /**
     * 创建工具结果消息。
     *
     * @param toolCallId 所回应的工具调用 ID
     * @param name 工具名称
     * @param content 工具执行结果
     * @return 新的 ChatMessage 实例
     */
    public static ChatMessage toolResult(String toolCallId, String name, String content) {
        ChatMessage msg = new ChatMessage("tool", content);
        msg.setToolCallId(toolCallId);
        msg.setName(name);
        return msg;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public List<ToolCall> getToolCalls() {
        return toolCalls;
    }

    public void setToolCalls(List<ToolCall> toolCalls) {
        this.toolCalls = toolCalls;
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public void setToolCallId(String toolCallId) {
        this.toolCallId = toolCallId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
