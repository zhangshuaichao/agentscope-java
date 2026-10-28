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
 * Chat Completions API 请求中的 OpenAI 工具定义。
 *
 * <p>采用 OpenAI 格式表示模型可调用的工具。
 *
 * <p>示例：
 * <pre>{@code
 * {
 *   "type": "function",
 *   "function": {
 *     "name": "get_weather",
 *     "description": "Get the current weather",
 *     "parameters": {
 *       "type": "object",
 *       "properties": {
 *         "location": {"type": "string"}
 *       },
 *       "required": ["location"]
 *     }
 *   }
 * }
 * }</pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OpenAITool {

    /** 工具类型，目前始终为 "function"。 */
    @JsonProperty("type")
    private String type = "function";

    /** 函数定义。 */
    @JsonProperty("function")
    private OpenAIToolFunction function;

    /** 供反序列化使用的默认构造器。 */
    public OpenAITool() {}

    /**
     * 使用指定函数创建 OpenAITool。
     *
     * @param function 函数定义
     */
    public OpenAITool(OpenAIToolFunction function) {
        this.function = function;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public OpenAIToolFunction getFunction() {
        return function;
    }

    public void setFunction(OpenAIToolFunction function) {
        this.function = function;
    }
}
