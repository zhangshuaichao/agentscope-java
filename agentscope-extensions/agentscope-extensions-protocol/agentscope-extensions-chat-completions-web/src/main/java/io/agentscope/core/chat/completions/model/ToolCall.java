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

/**
 * 以兼容 OpenAI 的格式表示工具调用。
 *
 * <p>此 DTO 用于序列化对话上下文中的工具调用，便于客户端重建包含工具调用的完整对话历史。
 *
 * <p>JSON 示例（非流式）：
 *
 * <pre>{@code
 * {
 *   "id": "call_abc123",
 *   "type": "function",
 *   "function": {
 *     "name": "get_weather",
 *     "arguments": "{\"city\":\"Hangzhou\"}"
 *   }
 * }
 * }</pre>
 *
 * <p>JSON 示例（带索引的流式形式）：
 *
 * <pre>{@code
 * {
 *   "index": 0,
 *   "id": "call_abc123",
 *   "type": "function",
 *   "function": {
 *     "name": "get_weather",
 *     "arguments": "{\"city\":\"Hangzhou\"}"
 *   }
 * }
 * }</pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ToolCall {

    private Integer index;

    private String id;

    private String type = "function";

    private FunctionCall function;

    /** 供反序列化使用的默认构造器。 */
    public ToolCall() {}

    /**
     * 创建工具调用。
     *
     * @param id 本次工具调用的唯一标识
     * @param name 函数名称
     * @param arguments 函数参数的 JSON 字符串
     */
    public ToolCall(String id, String name, String arguments) {
        this.id = id;
        this.type = "function";
        this.function = new FunctionCall(name, arguments);
    }

    /**
     * 创建带索引的工具调用（用于流式输出）。
     *
     * @param index 在 tool_calls 数组中的索引
     * @param id 本次工具调用的唯一标识
     * @param name 函数名称
     * @param arguments 函数参数的 JSON 字符串
     */
    public ToolCall(Integer index, String id, String name, String arguments) {
        this.index = index;
        this.id = id;
        this.type = "function";
        this.function = new FunctionCall(name, arguments);
    }

    public Integer getIndex() {
        return index;
    }

    public void setIndex(Integer index) {
        this.index = index;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public FunctionCall getFunction() {
        return function;
    }

    public void setFunction(FunctionCall function) {
        this.function = function;
    }

    /** 表示工具调用内的函数详情。 */
    public static class FunctionCall {

        private String name;

        private String arguments;

        /** 供反序列化使用的默认构造器。 */
        public FunctionCall() {}

        /**
         * 创建函数调用。
         *
         * @param name 函数名称
         * @param arguments 函数参数的 JSON 字符串
         */
        public FunctionCall(String name, String arguments) {
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
    }
}
