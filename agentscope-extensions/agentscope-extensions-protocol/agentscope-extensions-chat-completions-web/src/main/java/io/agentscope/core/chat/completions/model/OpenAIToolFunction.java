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
import java.util.Map;

/**
 * Chat Completions API 请求中的 OpenAI 工具函数定义。
 *
 * <p>采用 OpenAI 格式表示工具内的函数定义。
 *
 * <p>示例：
 * <pre>{@code
 * {
 *   "name": "get_weather",
 *   "description": "Get the current weather",
 *   "parameters": {
 *     "type": "object",
 *     "properties": {
 *       "location": {"type": "string"}
 *     },
 *     "required": ["location"]
 *   }
 * }
 * }</pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OpenAIToolFunction {

    /** 函数名称。 */
    @JsonProperty("name")
    private String name;

    /** 函数描述。 */
    @JsonProperty("description")
    private String description;

    /** 函数参数的 JSON Schema。 */
    @JsonProperty("parameters")
    private Map<String, Object> parameters;

    /** 是否启用模式校验的严格模式。 */
    @JsonProperty("strict")
    private Boolean strict;

    /** 供反序列化使用的默认构造器。 */
    public OpenAIToolFunction() {}

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Map<String, Object> getParameters() {
        return parameters;
    }

    public void setParameters(Map<String, Object> parameters) {
        this.parameters = parameters;
    }

    public Boolean getStrict() {
        return strict;
    }

    public void setStrict(Boolean strict) {
        this.strict = strict;
    }
}
