/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.extensions.model.ollama.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Ollama 聊天消息 DTO，表示对话历史中的一条消息。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OllamaMessage {

    /**
     * 消息发送方角色（system、user、assistant、tool）。
     */
    @JsonProperty("role")
    private String role;

    /**
     * 消息内容。
     */
    @JsonProperty("content")
    private String content;

    /**
     * 模型的推理内容，在请求启用 {@code think} 选项时由思考模型返回。
     * 原生 Ollama {@code /api/chat} 在 {@code thinking} 字段中返回此内容；
     * 某些推理模型或代理使用 {@code reasoning} 或 {@code reasoning_content} 字段。
     *
     * <p>在 Ollama schema 中，{@code thinking} 是可选消息字段；AgentScope 的
     * {@code OllamaResponseParser} 将入站推理内容提取为 {@code ThinkingBlock}。
     */
    @JsonProperty("thinking")
    @JsonAlias({"reasoning", "reasoning_content"})
    private String thinking;

    /**
     * Base64 编码图像列表（可选）。
     */
    @JsonProperty("images")
    private List<String> images;

    /**
     * 模型生成的工具调用列表（可选）。
     */
    @JsonProperty("tool_calls")
    private List<OllamaToolCall> toolCalls;

    /**
     * 工具调用 ID（工具消息必填）。
     */
    @JsonProperty("tool_call_id")
    private String toolCallId;

    /**
     * 工具／函数名称（可选，工具消息中必填）。
     */
    @JsonProperty("tool_name")
    private String name;

    public OllamaMessage() {}

    public OllamaMessage(String role, String content) {
        this.role = role;
        this.content = content;
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

    public String getThinking() {
        return thinking;
    }

    public void setThinking(String thinking) {
        this.thinking = thinking;
    }

    public List<String> getImages() {
        return images;
    }

    public void setImages(List<String> images) {
        this.images = images;
    }

    public List<OllamaToolCall> getToolCalls() {
        return toolCalls;
    }

    public void setToolCalls(List<OllamaToolCall> toolCalls) {
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
