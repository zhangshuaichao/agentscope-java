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
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 表示 AG-UI 协议消息。
 *
 * <p>消息是协议的主要通信单元，包含内容、角色信息以及可选工具调用或工具调用 ID。
 *
 * <p>消息角色：
 * <ul>
 * <li>user - 用户消息</li>
 * <li>assistant - AI 助手消息</li>
 * <li>system - 系统指令</li>
 * <li>tool - 工具执行结果</li>
 * <li>reasoning - 助手推理或思考内容</li>
 * </ul>
 *
 * <p>{@code content} 字段使用类型安全的密封联合 {@link MessageContent}，可表示纯文本或结构化 {@link InputContent} 片段列表（文本、图像、音频、视频、文档）。
 */
public class AguiMessage {

    private final String id;
    private final String role;
    private final MessageContent content;
    private final List<AguiToolCall> toolCalls;
    private final String toolCallId;
    private final String error;

    /**
     * 创建不含错误载荷的 AguiMessage。
     *
     * @param id 唯一消息 ID
     * @param role 消息角色（user、assistant、system、tool）
     * @param content 消息内容（纯文本或结构化块），可为 null
     * @param toolCalls 助手消息的工具调用（可选）
     * @param toolCallId 工具消息的工具调用 ID（可选）
     */
    public AguiMessage(
            String id,
            String role,
            MessageContent content,
            List<AguiToolCall> toolCalls,
            String toolCallId) {
        this(id, role, content, toolCalls, toolCallId, null);
    }

    /**
     * 创建 AguiMessage。
     *
     * @param id 唯一消息 ID
     * @param role 消息角色（user、assistant、system、tool、reasoning）
     * @param content 消息内容（纯文本或结构化块），可为 null
     * @param toolCalls 助手消息的工具调用（可选）
     * @param toolCallId 工具消息的工具调用 ID（可选）
     * @param error 前端工具报告的错误，对应 AG-UI 工具消息的 {@code error} 字段；可为 null
     */
    @JsonCreator
    public AguiMessage(
            @JsonProperty("id") String id,
            @JsonProperty("role") String role,
            @JsonProperty("content") MessageContent content,
            @JsonProperty("toolCalls") List<AguiToolCall> toolCalls,
            @JsonProperty("toolCallId") String toolCallId,
            @JsonProperty("error") String error) {
        this.id = Objects.requireNonNull(id, "id cannot be null");
        this.role = Objects.requireNonNull(role, "role cannot be null");
        this.content = content;
        this.toolCalls =
                toolCalls != null
                        ? Collections.unmodifiableList(toolCalls)
                        : Collections.emptyList();
        this.toolCallId = toolCallId;
        this.error = error;
    }

    /**
     * 创建纯文本用户消息。
     *
     * @param id 消息 ID
     * @param content 纯文本消息内容
     * @return 新用户消息
     */
    public static AguiMessage userMessage(String id, String content) {
        return new AguiMessage(id, "user", wrapText(content), null, null);
    }

    /**
     * 创建结构化内容块用户消息。
     *
     * @param id 消息 ID
     * @param blocks 结构化内容片段
     * @return 新用户消息
     */
    public static AguiMessage userMessage(String id, List<InputContent> blocks) {
        return new AguiMessage(id, "user", new MessageContent.Blocks(blocks), null, null);
    }

    /**
     * 创建简单助手消息。
     *
     * @param id 消息 ID
     * @param content 纯文本消息内容
     * @return 新助手消息
     */
    public static AguiMessage assistantMessage(String id, String content) {
        return new AguiMessage(id, "assistant", wrapText(content), null, null);
    }

    /**
     * 创建系统消息。
     *
     * @param id 消息 ID
     * @param content 纯文本消息内容
     * @return 新系统消息
     */
    public static AguiMessage systemMessage(String id, String content) {
        return new AguiMessage(id, "system", wrapText(content), null, null);
    }

    /**
     * 创建工具结果消息。
     *
     * @param id 消息 ID
     * @param toolCallId 所回应的工具调用 ID
     * @param content 纯文本工具结果内容
     * @return 新工具消息
     */
    public static AguiMessage toolMessage(String id, String toolCallId, String content) {
        return new AguiMessage(id, "tool", wrapText(content), null, toolCallId);
    }

    /**
     * 创建报告前端工具失败的工具结果消息。
     *
     * @param id 消息 ID
     * @param toolCallId 所回应的工具调用 ID
     * @param content 纯文本工具结果内容
     * @param error 前端工具报告的错误
     * @return 新工具消息
     */
    public static AguiMessage toolMessage(
            String id, String toolCallId, String content, String error) {
        return new AguiMessage(id, "tool", wrapText(content), null, toolCallId, error);
    }

    /**
     * 创建推理消息。
     *
     * @param id 消息 ID
     * @param content 纯文本推理内容
     * @return 新推理消息
     */
    public static AguiMessage reasoningMessage(String id, String content) {
        return new AguiMessage(id, "reasoning", wrapText(content), null, null);
    }

    /**
     * 创建纯文本消息，支持自定义角色、工具调用和工具调用 ID。这是文本消息的全参数便捷工厂。
     *
     * @param id 消息 ID
     * @param role 消息角色（user、assistant、system、tool）
     * @param text 纯文本消息内容，可为 null
     * @param toolCalls 助手消息的工具调用（可选）
     * @param toolCallId 工具消息的工具调用 ID（可选）
     * @return 新消息
     */
    public static AguiMessage textMessage(
            String id, String role, String text, List<AguiToolCall> toolCalls, String toolCallId) {
        return new AguiMessage(id, role, wrapText(text), toolCalls, toolCallId);
    }

    /**
     * 创建结构化内容块消息，支持自定义角色、工具调用和工具调用 ID。这是内容块消息的全参数便捷工厂。
     *
     * @param id 消息 ID
     * @param role 消息角色（user、assistant、system、tool）
     * @param blocks 结构化内容片段
     * @param toolCalls 助手消息的工具调用（可选）
     * @param toolCallId 工具消息的工具调用 ID（可选）
     * @return 新消息
     */
    public static AguiMessage blocksMessage(
            String id,
            String role,
            List<InputContent> blocks,
            List<AguiToolCall> toolCalls,
            String toolCallId) {
        return new AguiMessage(id, role, new MessageContent.Blocks(blocks), toolCalls, toolCallId);
    }

    /**
     * 将纯文本字符串包装为 {@link MessageContent.Text}，或返回 null。
     *
     * @param text 要包装的文本，可为 null
     * @return {@code MessageContent.Text}；文本为 null 时返回 null
     */
    private static MessageContent wrapText(String text) {
        return text != null ? new MessageContent.Text(text) : null;
    }

    /**
     * 获取消息 ID。
     *
     * @return 消息 ID
     */
    public String getId() {
        return id;
    }

    /**
     * 获取消息角色。
     *
     * @return 角色（user、assistant、system、tool、reasoning）
     */
    public String getRole() {
        return role;
    }

    /**
     * 获取类型安全的 {@link MessageContent} 消息内容。
     *
     * @return 内容，可为 null
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public MessageContent getContent() {
        return content;
    }

    /**
     * 内容为 {@link MessageContent.Text} 时获取纯文本。
     *
     * <p>针对纯文本这一常见场景的便捷方法。内容为结构化块或 null 时返回 null。
     *
     * @return 文本值；内容非纯文本时返回 null
     */
    @JsonIgnore
    public String getTextContent() {
        if (content instanceof MessageContent.Text text) {
            return text.value();
        }
        return null;
    }

    /**
     * 检查消息是否含结构化内容块。
     *
     * @return 内容为 {@link MessageContent.Blocks} 时为 true
     */
    public boolean hasBlocks() {
        return content instanceof MessageContent.Blocks;
    }

    /**
     * 获取工具调用（用于助手消息）。
     *
     * @return 不可变工具调用列表；无调用时为空
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public List<AguiToolCall> getToolCalls() {
        return toolCalls;
    }

    /**
     * 获取工具调用 ID（用于工具消息）。
     *
     * @return 工具调用 ID；非工具消息时为 null
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getToolCallId() {
        return toolCallId;
    }

    /**
     * 获取前端工具报告的错误，对应 AG-UI 协议的 {@code error} 字段。
     *
     * @return 错误消息；未报告错误时为 null
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getError() {
        return error;
    }

    /**
     * 检查是否为用户消息。
     *
     * @return 角色为 "user" 时为 true
     */
    @JsonIgnore
    public boolean isUserMessage() {
        return "user".equalsIgnoreCase(role);
    }

    /**
     * 检查是否为助手消息。
     *
     * @return 角色为 "assistant" 时为 true
     */
    @JsonIgnore
    public boolean isAssistantMessage() {
        return "assistant".equalsIgnoreCase(role);
    }

    /**
     * 检查是否为系统消息。
     *
     * @return 角色为 "system" 时为 true
     */
    @JsonIgnore
    public boolean isSystemMessage() {
        return "system".equalsIgnoreCase(role);
    }

    /**
     * 检查是否为工具消息。
     *
     * @return 角色为 "tool" 时为 true
     */
    @JsonIgnore
    public boolean isToolMessage() {
        return "tool".equalsIgnoreCase(role);
    }

    /**
     * 检查是否为推理消息。
     *
     * @return 角色为 "reasoning" 时为 true
     */
    @JsonIgnore
    public boolean isReasoningMessage() {
        return "reasoning".equalsIgnoreCase(role);
    }

    /**
     * 检查消息是否含工具调用。
     *
     * @return 存在工具调用时为 true
     */
    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    @Override
    public String toString() {
        return "AguiMessage{id='"
                + id
                + "', role='"
                + role
                + "', content="
                + content
                + ", toolCalls="
                + toolCalls
                + ", toolCallId='"
                + toolCallId
                + "', error='"
                + error
                + "'}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AguiMessage that = (AguiMessage) o;
        return Objects.equals(id, that.id)
                && Objects.equals(role, that.role)
                && Objects.equals(content, that.content)
                && Objects.equals(toolCalls, that.toolCalls)
                && Objects.equals(toolCallId, that.toolCallId)
                && Objects.equals(error, that.error);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, role, content, toolCalls, toolCallId, error);
    }
}
