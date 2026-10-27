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
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.io.IOException;

/**
 * DashScope API 响应 DTO。
 *
 * <p>表示文本生成和多模态生成 API 的顶层响应结构。
 *
 * <p>JSON 示例：
 * <pre>{@code
 * {
 *   "request_id": "xxx-xxx-xxx",
 *   "output": {
 *     "choices": [...]
 *   },
 *   "usage": {
 *     "input_tokens": 10,
 *     "output_tokens": 20
 *   }
 * }
 * }</pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class DashScopeResponse {

    /**
     * 唯一请求标识。
     */
    @JsonProperty("request_id")
    private String requestId;

    /**
     * 包含候选项的输出。
     */
    @JsonProperty("output")
    @JsonDeserialize(using = DashScopeOutputDeserializer.class)
    private DashScopeOutput output;

    /**
     * token 使用统计。
     */
    @JsonProperty("usage")
    private DashScopeUsage usage;

    /**
     * 错误代码（请求失败时）。
     */
    @JsonProperty("code")
    private String code;

    /**
     * 错误消息（请求失败时）。
     */
    @JsonProperty("message")
    private String message;

    public DashScopeResponse() {}

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public DashScopeOutput getOutput() {
        return output;
    }

    public void setOutput(DashScopeOutput output) {
        this.output = output;
    }

    public DashScopeUsage getUsage() {
        return usage;
    }

    public void setUsage(DashScopeUsage usage) {
        this.usage = usage;
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

    /**
     * 检查此响应是否表示错误。
     *
     * @return 响应包含错误代码时为 true
     */
    public boolean isError() {
        return code != null && !code.isEmpty();
    }

    /**
     * DashScopeOutput 的自定义反序列化器，同时处理字符串（加密）与对象（解密）形式。
     *
     * <p>output 字段为字符串时返回 null，因为它本应在反序列化之前由 decryptResponse() 解密。此时仍为字符串意味着解密失败或未执行，因此跳过。
     */
    static class DashScopeOutputDeserializer extends JsonDeserializer<DashScopeOutput> {

        @Override
        public DashScopeOutput deserialize(JsonParser p, DeserializationContext ctxt)
                throws IOException {
            JsonToken token = p.currentToken();
            if (token == JsonToken.VALUE_NULL) {
                return null;
            } else if (token == JsonToken.VALUE_STRING) {
                // 字符串值表示尚未解密的加密输出。
                // 读取并丢弃字符串值，返回 null。
                // decryptResponse() 本应处理该值；若解密失败
                // 或未执行，则在此兼容处理。
                p.getValueAsString(); // 读取并跳过字符串值。
                return null;
            } else if (token == JsonToken.START_OBJECT) {
                // 对象值：正常反序列化。
                // 通过 readTree 和 treeToValue 进行标准反序列化。
                // 处理 output 已是解密 JSON 对象的情况。
                JsonNode node = p.getCodec().readTree(p);
                if (node == null || node.isNull() || !node.isObject()) {
                    return null;
                }
                return p.getCodec().treeToValue(node, DashScopeOutput.class);
            }
            // 意外的 token 类型。
            throw new IOException(
                    "Cannot deserialize DashScopeOutput from token: "
                            + token
                            + ". Expected object or string.");
        }
    }
}
