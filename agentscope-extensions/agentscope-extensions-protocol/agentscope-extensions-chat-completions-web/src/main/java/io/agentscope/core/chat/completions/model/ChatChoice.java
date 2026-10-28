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

/**
 * Chat Completions 风格响应中的单个候选项。
 *
 * <p>本类采用 OpenAI Chat Completions API 格式。
 *
 * <p>示例：
 *
 * <pre>{@code
 * {
 *   "index": 0,
 *   "message": {
 *     "role": "assistant",
 *     "content": "Hello! How can I help you?"
 *   },
 *   "finish_reason": "stop"
 * }
 * }</pre>
 *
 * <p>finish_reason 的可能取值：
 *
 * <ul>
 *   <li>"stop"：消息自然结束
 *   <li>"length"：达到最大 token 数
 *   <li>"tool_calls"：模型希望调用工具
 *   <li>"content_filter"：内容被过滤
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatChoice {

    private int index;

    /** 非流式响应的消息内容。 */
    private ChatMessage message;

    /** 流式响应的增量内容。 */
    private ChatMessage delta;

    /** 模型停止生成的原因，按 OpenAI 规范使用 snake_case。 */
    @JsonProperty("finish_reason")
    private String finishReason;

    /** 对数概率信息（OpenAI 可选字段）。 */
    private Object logprobs;

    public int getIndex() {
        return index;
    }

    public void setIndex(int index) {
        this.index = index;
    }

    public ChatMessage getMessage() {
        return message;
    }

    public void setMessage(ChatMessage message) {
        this.message = message;
    }

    public ChatMessage getDelta() {
        return delta;
    }

    public void setDelta(ChatMessage delta) {
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
}
