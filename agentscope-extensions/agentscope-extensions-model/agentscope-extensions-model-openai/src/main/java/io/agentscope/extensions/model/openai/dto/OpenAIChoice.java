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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * OpenAI 候选结果 DTO。
 *
 * <p>表示 OpenAI API 响应中的单个候选结果。非流式响应使用 message 字段，流式响应使用 delta 字段。
 *
 * <p>非流式示例：
 * <pre>{@code
 * {
 *   "index": 0,
 *   "message": {
 *     "role": "assistant",
 *     "content": "Hello!"
 *   },
 *   "finish_reason": "stop"
 * }
 * }</pre>
 *
 * <p>流式示例：
 * <pre>{@code
 * {
 *   "index": 0,
 *   "delta": {
 *     "content": "Hello"
 *   },
 *   "finish_reason": null
 * }
 * }</pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class OpenAIChoice {

    /** 此候选结果的索引，用于多候选响应。 */
    @JsonProperty("index")
    private Integer index;

    /** 模型生成的完整消息，非流式使用。 */
    @JsonProperty("message")
    private OpenAIMessage message;

    /** 增量消息，流式使用。 */
    @JsonProperty("delta")
    private OpenAIMessage delta;

    /**
     * 生成停止的原因。
     * 取值包括 "stop"、"length"、"tool_calls"、"content_filter"，或 null（仍在生成）。
     */
    @JsonProperty("finish_reason")
    private String finishReason;

    /** 对数概率信息。 */
    @JsonProperty("logprobs")
    private Object logprobs;

    public OpenAIChoice() {}

    public Integer getIndex() {
        return index;
    }

    public void setIndex(Integer index) {
        this.index = index;
    }

    public OpenAIMessage getMessage() {
        return message;
    }

    public void setMessage(OpenAIMessage message) {
        this.message = message;
    }

    public OpenAIMessage getDelta() {
        return delta;
    }

    public void setDelta(OpenAIMessage delta) {
        this.delta = delta;
    }

    public String getFinishReason() {
        return finishReason;
    }

    public void setFinishReason(String finishReason) {
        this.finishReason = finishReason;
    }

    public Object getLogprobs() {
        return logprobs;
    }

    public void setLogprobs(Object logprobs) {
        this.logprobs = logprobs;
    }

    /**
     * 获取实际消息，可能是 message 或 delta。
     *
     * @return 非流式返回 message，流式返回 delta
     */
    public OpenAIMessage getEffectiveMessage() {
        return message != null ? message : delta;
    }
}
