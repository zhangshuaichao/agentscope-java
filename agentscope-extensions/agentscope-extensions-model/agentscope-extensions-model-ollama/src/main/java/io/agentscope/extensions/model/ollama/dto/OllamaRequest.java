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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * Ollama 聊天请求 DTO，表示发送给 Ollama 聊天 API 的载荷。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OllamaRequest {

    /**
     * 使用的模型名称。
     */
    @JsonProperty("model")
    private String model;

    /**
     * 对话中的消息列表。
     */
    @JsonProperty("messages")
    private List<OllamaMessage> messages;

    /**
     * 是否流式返回响应。
     */
    @JsonProperty("stream")
    private Boolean stream;

    /**
     * 响应返回格式（例如 "json"）。
     */
    @JsonProperty("format")
    private Object format;

    /**
     * 控制模型在内存中保持加载的时长。
     */
    @JsonProperty("keep_alive")
    private String keepAlive;

    /**
     * 模型可访问的工具列表。
     */
    @JsonProperty("tools")
    private List<OllamaTool> tools;

    /**
     * 工具选择配置，可以是 "auto"、"none" 或指定工具对象。
     */
    @JsonProperty("tool_choice")
    private Object toolChoice;

    /**
     * 控制思考能力（思维链），可以是布尔值（启用／禁用）或字符串（级别）。
     */
    @JsonProperty("think")
    private Object think;

    /**
     * 模型专属选项（temperature 等）。
     */
    @JsonProperty("options")
    private Map<String, Object> options;

    public OllamaRequest() {}

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public List<OllamaMessage> getMessages() {
        return messages;
    }

    public void setMessages(List<OllamaMessage> messages) {
        this.messages = messages;
    }

    public Boolean getStream() {
        return stream;
    }

    public void setStream(Boolean stream) {
        this.stream = stream;
    }

    public Object getFormat() {
        return format;
    }

    public void setFormat(Object format) {
        this.format = format;
    }

    public String getKeepAlive() {
        return keepAlive;
    }

    public void setKeepAlive(String keepAlive) {
        this.keepAlive = keepAlive;
    }

    public List<OllamaTool> getTools() {
        return tools;
    }

    public void setTools(List<OllamaTool> tools) {
        this.tools = tools;
    }

    public Object getToolChoice() {
        return toolChoice;
    }

    public void setToolChoice(Object toolChoice) {
        this.toolChoice = toolChoice;
    }

    public Object getThink() {
        return think;
    }

    public void setThink(Object think) {
        this.think = think;
    }

    public Map<String, Object> getOptions() {
        return options;
    }

    public void setOptions(Map<String, Object> options) {
        this.options = options;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final OllamaRequest request = new OllamaRequest();

        public Builder model(String model) {
            request.setModel(model);
            return this;
        }

        public Builder messages(List<OllamaMessage> messages) {
            request.setMessages(messages);
            return this;
        }

        public Builder stream(Boolean stream) {
            request.setStream(stream);
            return this;
        }

        public Builder format(Object format) {
            request.setFormat(format);
            return this;
        }

        public Builder keepAlive(String keepAlive) {
            request.setKeepAlive(keepAlive);
            return this;
        }

        public Builder tools(List<OllamaTool> tools) {
            request.setTools(tools);
            return this;
        }

        public Builder toolChoice(Object toolChoice) {
            request.setToolChoice(toolChoice);
            return this;
        }

        public Builder think(Object think) {
            request.setThink(think);
            return this;
        }

        public Builder options(Map<String, Object> options) {
            request.setOptions(options);
            return this;
        }

        public OllamaRequest build() {
            return request;
        }
    }
}
