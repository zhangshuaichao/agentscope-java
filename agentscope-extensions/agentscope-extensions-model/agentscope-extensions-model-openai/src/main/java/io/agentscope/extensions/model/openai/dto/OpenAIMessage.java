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
package io.agentscope.extensions.model.openai.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * OpenAI 消息 DTO。
 *
 * <p>表示 OpenAI Chat Completion API 格式的消息。纯文本内容可用 String，
 * 多模态内容可用 OpenAIContentPart 列表。
 *
 * <p>文本消息示例：
 * <pre>{@code
 * {
 *   "role": "user",
 *   "content": "Hello"
 * }
 * }</pre>
 *
 * <p>多模态消息示例：
 * <pre>{@code
 * {
 *   "role": "user",
 *   "content": [
 *     {"type": "text", "text": "What's in this image?"},
 *     {"type": "image_url", "image_url": {"url": "https://example.com/image.jpg"}}
 *   ]
 * }
 * }</pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class OpenAIMessage {

    /** 消息角色："system"、"user"、"assistant" 或 "tool"。 */
    @JsonProperty("role")
    private String role;

    /**
     * 消息内容。
     * 纯文本使用 String，多模态使用 List&lt;OpenAIContentPart&gt;。
     */
    @JsonProperty("content")
    private Object content;

    /** 此消息作者的名称，可选。 */
    @JsonProperty("name")
    private String name;

    /** 工具调用 ID，供 role="tool" 使用。 */
    @JsonProperty("tool_call_id")
    private String toolCallId;

    /** 助手发起的工具调用。 */
    @JsonProperty("tool_calls")
    private List<OpenAIToolCall> toolCalls;

    /**
     * 模型的推理/思考内容。
     * 反序列化时同时接受 {@code reasoning_content}（DeepSeek、DashScope 等商业 API）和
     * {@code reasoning}（vLLM 部署）。
     */
    @JsonProperty("reasoning_content")
    @JsonAlias("reasoning")
    private String reasoningContent;

    /** 推理详情，供 OpenRouter 上的 Gemini 使用。 */
    @JsonProperty("reasoning_details")
    private List<OpenAIReasoningDetail> reasoningDetails;

    /** 内容策略违规时的拒绝消息。 */
    @JsonProperty("refusal")
    private String refusal;

    public OpenAIMessage() {}

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public Object getContent() {
        return content;
    }

    public void setContent(Object content) {
        this.content = content;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public void setToolCallId(String toolCallId) {
        this.toolCallId = toolCallId;
    }

    public List<OpenAIToolCall> getToolCalls() {
        return toolCalls;
    }

    public void setToolCalls(List<OpenAIToolCall> toolCalls) {
        this.toolCalls = toolCalls;
    }

    public String getReasoningContent() {
        return reasoningContent;
    }

    public void setReasoningContent(String reasoningContent) {
        this.reasoningContent = reasoningContent;
    }

    public List<OpenAIReasoningDetail> getReasoningDetails() {
        return reasoningDetails;
    }

    public void setReasoningDetails(List<OpenAIReasoningDetail> reasoningDetails) {
        this.reasoningDetails = reasoningDetails;
    }

    public String getRefusal() {
        return refusal;
    }

    public void setRefusal(String refusal) {
        this.refusal = refusal;
    }

    /**
     * 以 String 获取纯文本消息内容。
     *
     * @return String 内容；多模态时返回 null
     */
    @JsonIgnore
    public String getContentAsString() {
        if (content instanceof String) {
            return (String) content;
        } else if (content instanceof List) {
            StringBuilder sb = new StringBuilder();
            for (Object item : (List<?>) content) {
                if (item instanceof OpenAIContentPart) {
                    OpenAIContentPart part = (OpenAIContentPart) item;
                    if ("text".equals(part.getType()) && part.getText() != null) {
                        sb.append(part.getText());
                    }
                }
            }
            return sb.toString();
        }
        return null;
    }

    /**
     * 以 List 获取多模态消息内容。
     *
     * @return List 内容；纯文本时返回 null
     */
    @JsonIgnore
    @SuppressWarnings("unchecked")
    public List<OpenAIContentPart> getContentAsList() {
        if (content instanceof List) {
            return (List<OpenAIContentPart>) content;
        }
        return null;
    }

    /**
     * 检查消息是否包含多模态内容。
     *
     * @return 内容为 List 时返回 true
     */
    @JsonIgnore
    public boolean isMultimodal() {
        return content instanceof List;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private OpenAIMessage message = new OpenAIMessage();

        public Builder role(String role) {
            message.setRole(role);
            return this;
        }

        public Builder content(String content) {
            message.setContent(content);
            return this;
        }

        public Builder content(List<OpenAIContentPart> content) {
            message.setContent(content);
            return this;
        }

        public Builder name(String name) {
            message.setName(name);
            return this;
        }

        public Builder toolCallId(String toolCallId) {
            message.setToolCallId(toolCallId);
            return this;
        }

        public Builder toolCalls(List<OpenAIToolCall> toolCalls) {
            message.setToolCalls(toolCalls);
            return this;
        }

        public Builder reasoningContent(String reasoningContent) {
            message.setReasoningContent(reasoningContent);
            return this;
        }

        public Builder reasoningDetails(List<OpenAIReasoningDetail> reasoningDetails) {
            message.setReasoningDetails(reasoningDetails);
            return this;
        }

        public Builder refusal(String refusal) {
            message.setRefusal(refusal);
            return this;
        }

        public OpenAIMessage build() {
            OpenAIMessage result = message;
            message = new OpenAIMessage();
            return result;
        }
    }
}
