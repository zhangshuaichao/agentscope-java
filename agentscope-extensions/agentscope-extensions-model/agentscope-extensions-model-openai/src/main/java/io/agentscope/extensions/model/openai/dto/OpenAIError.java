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
 * OpenAI 错误 DTO。
 *
 * <p>表示 OpenAI API 响应中的错误信息。
 *
 * <p>JSON 示例：
 * <pre>{@code
 * {
 *   "message": "Invalid API key",
 *   "type": "invalid_request_error",
 *   "param": null,
 *   "code": "invalid_api_key"
 * }
 * }</pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class OpenAIError {

    /** 错误消息。 */
    @JsonProperty("message")
    private String message;

    /** 错误类型。 */
    @JsonProperty("type")
    private String type;

    /** 导致错误的参数（若适用）。 */
    @JsonProperty("param")
    private String param;

    /** 错误码。 */
    @JsonProperty("code")
    private String code;

    public OpenAIError() {}

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getParam() {
        return param;
    }

    public void setParam(String param) {
        this.param = param;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    @Override
    public String toString() {
        return "OpenAIError{"
                + "message='"
                + message
                + '\''
                + ", type='"
                + type
                + '\''
                + ", param='"
                + param
                + '\''
                + ", code='"
                + code
                + '\''
                + '}';
    }
}
