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
import java.util.List;

/**
 * OpenAI Chat Completion API 响应 DTO。
 *
 * <p>表示 OpenAI Chat Completion API 的响应结构，兼容非流式响应和流式分块响应。
 *
 * <p>非流式响应示例：
 * <pre>{@code
 * {
 *   "id": "chatcmpl-123",
 *   "object": "chat.completion",
 *   "created": 1677652280,
 *   "model": "gpt-4o",
 *   "choices": [{
 *     "index": 0,
 *     "message": {
 *       "role": "assistant",
 *       "content": "Hello!"
 *     },
 *     "finish_reason": "stop"
 *   }],
 *   "usage": {
 *     "prompt_tokens": 10,
 *     "completion_tokens": 20,
 *     "total_tokens": 30
 *   }
 * }
 * }</pre>
 *
 * <p>流式分块示例：
 * <pre>{@code
 * {
 *   "id": "chatcmpl-123",
 *   "object": "chat.completion.chunk",
 *   "created": 1677652280,
 *   "model": "gpt-4o",
 *   "choices": [{
 *     "index": 0,
 *     "delta": {
 *       "content": "Hello"
 *     },
 *     "finish_reason": null
 *   }]
 * }
 * }</pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class OpenAIResponse {

    /** 补全结果的唯一标识。 */
    @JsonProperty("id")
    private String id;

    /** 对象类型："chat.completion" 或 "chat.completion.chunk"。 */
    @JsonProperty("object")
    private String object;

    /** 创建补全结果时的 Unix 时间戳。 */
    @JsonProperty("created")
    private Long created;

    /** 用于补全的模型。 */
    @JsonProperty("model")
    private String model;

    /** 补全候选结果列表。 */
    @JsonProperty("choices")
    private List<OpenAIChoice> choices;

    /** token 用量统计。 */
    @JsonProperty("usage")
    private OpenAIUsage usage;

    /** 用于复现分析的系统指纹。 */
    @JsonProperty("system_fingerprint")
    private String systemFingerprint;

    /** 请求失败时的错误信息。 */
    @JsonProperty("error")
    private OpenAIError error;

    /** 非标准错误响应中的错误码。 */
    @JsonProperty("code")
    private String code;

    /** 非标准错误响应中的错误消息。 */
    @JsonProperty("message")
    private String message;

    /** 非标准错误响应中的状态。 */
    @JsonProperty("status")
    private String status;

    public OpenAIResponse() {}

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

    public Long getCreated() {
        return created;
    }

    public void setCreated(Long created) {
        this.created = created;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public List<OpenAIChoice> getChoices() {
        return choices;
    }

    public void setChoices(List<OpenAIChoice> choices) {
        this.choices = choices;
    }

    public OpenAIUsage getUsage() {
        return usage;
    }

    public void setUsage(OpenAIUsage usage) {
        this.usage = usage;
    }

    public String getSystemFingerprint() {
        return systemFingerprint;
    }

    public void setSystemFingerprint(String systemFingerprint) {
        this.systemFingerprint = systemFingerprint;
    }

    public OpenAIError getError() {
        return error;
    }

    public void setError(OpenAIError error) {
        this.error = error;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    /**
     * 检查此响应是否表示错误。
     *
     * 支持检测标准 OpenAI 错误结构，并使用黑名单策略检测非标准 code/status 错误结构。
     *
     * @return 响应包含错误时返回 true
     */
    public boolean isError() {
        // 标准 OpenAI 错误格式。
        if (error != null) {
            return true;
        }

        // 使用 status 字段再次检查。
        if ("error".equalsIgnoreCase(status) || "failed".equalsIgnoreCase(status)) {
            return true;
        }

        // 自定义错误码的黑名单策略。
        if (code != null) {
            try {
                int numericCode = Integer.parseInt(code);
                if (numericCode >= 400 && numericCode <= 599) {
                    return true;
                }
            } catch (NumberFormatException e) {
                // code 不是数字时，例如 "ok"、"success"、"invalid_key"，
                // 依赖前面检查过的 status 或 error 字段，
                // 不要盲目将其判定为错误。
            }
        }

        return false;
    }

    /** 获取实际错误消息，兼容标准和非标准格式。 */
    public String getEffectiveErrorMessage() {
        if (error != null && error.getMessage() != null) {
            return error.getMessage();
        }
        return message != null ? message : "Unknown error";
    }

    /** 获取实际错误码，兼容标准和非标准格式。 */
    public String getEffectiveErrorCode() {
        if (error != null && error.getCode() != null) {
            return error.getCode();
        }
        return code != null ? code : "unknown_error";
    }

    /**
     * 检查是否为流式分块响应。
     *
     * <p>按以下规则识别：
     * <ol>
     * <li>对象类型等于 "chat.completion" 时判为非流式。</li>
     * <li>对象类型等于 "chat.completion.chunk" 时判为流式，这是 OpenAI 标准格式。</li>
     * <li>或者存在 delta 字段而无 message 字段，兼容 GLM 等 OpenAI 兼容 API。</li>
     * </ol>
     *
     * @return 属于流式分块响应时返回 true
     */
    public boolean isChunk() {
        if ("chat.completion".equals(object)) {
            return false;
        }
        return "chat.completion.chunk".equals(object)
                || (choices != null
                        && choices.stream().anyMatch(choice -> choice.getDelta() != null));
    }

    /**
     * 获取首个候选结果（若存在）。
     *
     * @return 首个候选结果；没有候选结果时返回 null
     */
    public OpenAIChoice getFirstChoice() {
        if (choices != null && !choices.isEmpty()) {
            return choices.get(0);
        }
        return null;
    }
}
