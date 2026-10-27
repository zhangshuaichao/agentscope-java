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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * DashScope 工具函数定义 DTO。
 *
 * <p>表示工具的函数模式。
 *
 * <p>示例：
 * <pre>{@code
 * {
 *   "name": "get_weather",
 *   "description": "Get the current weather in a location",
 *   "parameters": {
 *     "type": "object",
 *     "properties": {
 *       "location": {
 *         "type": "string",
 *         "description": "The city name"
 *       }
 *     },
 *     "required": ["location"]
 *   }
 * }
 * }</pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DashScopeToolFunction {

    /**
     * 函数名称。
     */
    @JsonProperty("name")
    private String name;

    /**
     * 函数功能描述。
     */
    @JsonProperty("description")
    private String description;

    /**
     * 参数模式（JSON Schema 格式）。
     */
    @JsonProperty("parameters")
    private Map<String, Object> parameters;

    public DashScopeToolFunction() {}

    public DashScopeToolFunction(String name, String description, Map<String, Object> parameters) {
        this.name = name;
        this.description = description;
        this.parameters = parameters;
    }

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

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String name;
        private String description;
        private Map<String, Object> parameters;

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder parameters(Map<String, Object> parameters) {
            this.parameters = parameters;
            return this;
        }

        public DashScopeToolFunction build() {
            return new DashScopeToolFunction(name, description, parameters);
        }
    }
}
