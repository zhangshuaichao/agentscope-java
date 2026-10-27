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
package io.agentscope.extensions.model.dashscope.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import io.agentscope.core.util.JsonUtils;
import java.util.List;

/**
 * DashScope 消息 DTO。
 *
 * <p>表示 DashScope API 格式的消息。内容可以是纯文本 String，也可以是多模态 DashScopeContentPart 列表。
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
 *     {"text": "What's in this image?"},
 *     {"image": "https://example.com/image.jpg"}
 *   ]
 * }
 * }</pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class DashScopeMessage {

    /**
     * 消息角色："system"、"user"、"assistant" 或 "tool"。
     */
    @JsonProperty("role")
    private String role;

    /**
     * 消息内容：纯文本为 String，多模态为 List&lt;DashScopeContentPart&gt;。
     */
    @JsonProperty("content")
    private Object content;

    /**
     * 工具名称（role="tool" 时）。
     */
    @JsonProperty("name")
    private String name;

    /**
     * 工具调用 ID（role="tool" 时）。
     */
    @JsonProperty("tool_call_id")
    private String toolCallId;

    /**
     * 助手发起的工具调用。
     */
    @JsonProperty("tool_calls")
    private List<DashScopeToolCall> toolCalls;

    /**
     * 推理/思考内容（启用思考的助手消息）。
     */
    @JsonProperty("reasoning_content")
    @JsonAlias("reasoning")
    private String reasoningContent;

    public DashScopeMessage() {}

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

    /**
     * 将内容作为 String 获取（纯文本消息）。
     *
     * @return 字符串内容；多模态时为 null
     */
    @JsonIgnore
    public String getContentAsString() {
        if (content instanceof String text) {
            return text;
        }
        if (content instanceof List) {
            List<DashScopeContentPart> contentParts =
                    JsonUtils.getJsonCodec()
                            .convertValue(
                                    content, new TypeReference<List<DashScopeContentPart>>() {});
            if (contentParts != null && !contentParts.isEmpty()) {
                return contentParts.get(0).getText();
            }
        }

        return null;
    }

    /**
     * 将内容作为 List 获取（多模态消息）。
     *
     * @return 列表内容；纯文本时为 null
     */
    @JsonIgnore
    @SuppressWarnings("unchecked")
    public List<DashScopeContentPart> getContentAsList() {
        if (content instanceof List) {
            return (List<DashScopeContentPart>) content;
        }
        return null;
    }

    /**
     * 检查消息是否包含多模态内容。
     *
     * @return content 为 List 时为 true
     */
    @JsonIgnore
    public boolean isMultimodal() {
        return content instanceof List;
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

    public List<DashScopeToolCall> getToolCalls() {
        return toolCalls;
    }

    public void setToolCalls(List<DashScopeToolCall> toolCalls) {
        this.toolCalls = toolCalls;
    }

    public String getReasoningContent() {
        return reasoningContent;
    }

    public void setReasoningContent(String reasoningContent) {
        this.reasoningContent = reasoningContent;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final DashScopeMessage message = new DashScopeMessage();

        public Builder role(String role) {
            message.setRole(role);
            return this;
        }

        public Builder content(String content) {
            message.setContent(content);
            return this;
        }

        public Builder content(List<DashScopeContentPart> content) {
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

        public Builder toolCalls(List<DashScopeToolCall> toolCalls) {
            message.setToolCalls(toolCalls);
            return this;
        }

        public Builder reasoningContent(String reasoningContent) {
            message.setReasoningContent(reasoningContent);
            return this;
        }

        public DashScopeMessage build() {
            return message;
        }
    }
}
