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

/**
 * DashScope 函数调用 DTO。
 *
 * <p>表示工具调用中的函数详情。
 *
 * <p>示例：
 * <pre>{@code
 * {
 *   "name": "get_weather",
 *   "arguments": "{\"location\": \"Beijing\"}"
 * }
 * }</pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DashScopeFunction {

    /**
     * 待调用函数的名称。
     */
    @JsonProperty("name")
    private String name;

    /**
     * 传给函数的参数（JSON 字符串）。
     */
    @JsonProperty("arguments")
    private String arguments;

    public DashScopeFunction() {}

    public DashScopeFunction(String name, String arguments) {
        this.name = name;
        this.arguments = arguments;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getArguments() {
        return arguments;
    }

    public void setArguments(String arguments) {
        this.arguments = arguments;
    }

    public static DashScopeFunction of(String name, String arguments) {
        return new DashScopeFunction(name, arguments);
    }
}
