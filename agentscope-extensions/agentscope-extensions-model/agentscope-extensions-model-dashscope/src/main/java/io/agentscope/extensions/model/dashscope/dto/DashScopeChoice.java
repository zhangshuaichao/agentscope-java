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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * DashScope 候选项 DTO。
 *
 * <p>表示 API 响应中的单个候选项。
 *
 * <p>JSON 示例：
 * <pre>{@code
 * {
 *   "message": {
 *     "role": "assistant",
 *     "content": "Hello!"
 *   },
 *   "finish_reason": "stop"
 * }
 * }</pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class DashScopeChoice {

    /**
     * 模型生成的消息。
     */
    @JsonProperty("message")
    private DashScopeMessage message;

    /**
     * 生成停止的原因："stop"、"length"、"tool_calls"，或 null（仍在生成）。
     */
    @JsonProperty("finish_reason")
    private String finishReason;

    /**
     * 此候选项的索引（用于多候选响应）。
     */
    @JsonProperty("index")
    private Integer index;

    public DashScopeChoice() {}

    public DashScopeMessage getMessage() {
        return message;
    }

    public void setMessage(DashScopeMessage message) {
        this.message = message;
    }

    public String getFinishReason() {
        return finishReason;
    }

    public void setFinishReason(String finishReason) {
        this.finishReason = finishReason;
    }

    public Integer getIndex() {
        return index;
    }

    public void setIndex(Integer index) {
        this.index = index;
    }
}
