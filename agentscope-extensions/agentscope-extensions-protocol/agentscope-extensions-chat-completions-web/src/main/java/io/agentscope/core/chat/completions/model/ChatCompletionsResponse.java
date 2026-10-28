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
 * Chat Completions HTTP API 的响应载荷。
 *
 * <p>响应字段采用 OpenAI Chat Completions API 格式；具体支持范围以本类及响应构造器实现为准。
 *
 * <p><b>标准响应：</b>
 *
 * <pre>{@code
 * {
 *   "id": "chatcmpl-xxx",
 *   "object": "chat.completion",
 *   "created": 1234567890,
 *   "model": "gpt-4",
 *   "choices": [
 *     {
 *       "index": 0,
 *       "message": {"role": "assistant", "content": "Hello!"},
 *       "finish_reason": "stop"
 *     }
 *   ],
 *   "usage": {
 *     "prompt_tokens": 10,
 *     "completion_tokens": 5,
 *     "total_tokens": 15
 *   }
 * }
 * }</pre>
 *
 * <p><b>含工具调用的响应：</b>
 *
 * <p>assistant 调用工具时：
 *
 * <pre>{@code
 * {
 *   "id": "chatcmpl-xxx",
 *   "object": "chat.completion",
 *   "choices": [{
 *     "index": 0,
 *     "message": {
 *       "role": "assistant",
 *       "content": null,
 *       "tool_calls": [{
 *         "id": "call_abc",
 *         "type": "function",
 *         "function": {"name": "get_weather", "arguments": "{\"city\":\"Hangzhou\"}"}
 *       }]
 *     },
 *     "finish_reason": "tool_calls"
 *   }],
 *   "usage": {...}
 * }
 * }</pre>
 *
 * <p><b>无状态客户端的职责：</b>
 *
 * <p>客户端必须：
 *
 * <ol>
 *   <li>将响应中的 assistant 消息追加到对话历史
 *   <li>若存在 tool_calls，执行工具并追加工具结果消息
 *   <li>在下一次请求中发送完整历史
 * </ol>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatCompletionsResponse {

    /** 本次补全的唯一标识。 */
    private String id;

    /** 对象类型；非流式响应始终为 "chat.completion"。 */
    private String object = "chat.completion";

    /** 补全创建时的 Unix 时间戳。 */
    private long created;

    /** 用于生成的模型。 */
    private String model;

    /** 补全候选项列表。 */
    private List<ChatChoice> choices;

    /** Token 用量统计。 */
    private Usage usage;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getObject() {
        return object;
    }

    public void setObject(String object) {
        this.object = object;
    }

    public long getCreated() {
        return created;
    }

    public void setCreated(long created) {
        this.created = created;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public List<ChatChoice> getChoices() {
        return choices;
    }

    public void setChoices(List<ChatChoice> choices) {
        this.choices = choices;
    }

    public Usage getUsage() {
        return usage;
    }

    public void setUsage(Usage usage) {
        this.usage = usage;
    }

    /**
     * 采用 OpenAI 格式的 token 用量统计。
     *
     * <p>全部字段按 OpenAI 规范使用 snake_case。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Usage {

        /** 提示词中的 token 数量。 */
        @JsonProperty("prompt_tokens")
        private Integer promptTokens;

        /** 补全中的 token 数量。 */
        @JsonProperty("completion_tokens")
        private Integer completionTokens;

        /** Token 总数（提示词 + 补全）。 */
        @JsonProperty("total_tokens")
        private Integer totalTokens;

        public Usage() {}

        public Usage(Integer promptTokens, Integer completionTokens, Integer totalTokens) {
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
            this.totalTokens = totalTokens;
        }

        public Integer getPromptTokens() {
            return promptTokens;
        }

        public void setPromptTokens(Integer promptTokens) {
            this.promptTokens = promptTokens;
        }

        public Integer getCompletionTokens() {
            return completionTokens;
        }

        public void setCompletionTokens(Integer completionTokens) {
            this.completionTokens = completionTokens;
        }

        public Integer getTotalTokens() {
            return totalTokens;
        }

        public void setTotalTokens(Integer totalTokens) {
            this.totalTokens = totalTokens;
        }
    }
}
