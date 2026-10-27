/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
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
package io.agentscope.core.agui.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

/**
 * 表示 AG-UI 协议中的工具调用。
 *
 * <p>助手消息中的工具调用表示智能体希望调用工具，包含 ID、类型和函数详情。
 */
public class AguiToolCall {

    private final String id;
    private final String type;
    private final AguiFunctionCall function;

    /**
     * 创建 AguiToolCall。
     *
     * @param id 唯一工具调用 ID
     * @param type 工具调用类型（通常为 "function"）
     * @param function 函数调用详情
     */
    @JsonCreator
    public AguiToolCall(
            @JsonProperty("id") String id,
            @JsonProperty("type") String type,
            @JsonProperty("function") AguiFunctionCall function) {
        this.id = Objects.requireNonNull(id, "id cannot be null");
        this.type = type != null ? type : "function";
        this.function = Objects.requireNonNull(function, "function cannot be null");
    }

    /**
     * 创建类型为 "function" 的 AguiToolCall。
     *
     * @param id 唯一工具调用 ID
     * @param function 函数调用详情
     */
    public AguiToolCall(String id, AguiFunctionCall function) {
        this(id, "function", function);
    }

    /**
     * 获取工具调用 ID。
     *
     * @return 工具调用 ID
     */
    public String getId() {
        return id;
    }

    /**
     * 获取工具调用类型。
     *
     * @return 类型（通常为 "function"）
     */
    public String getType() {
        return type;
    }

    /**
     * 获取函数调用详情。
     *
     * @return 函数调用
     */
    public AguiFunctionCall getFunction() {
        return function;
    }

    @Override
    public String toString() {
        return "AguiToolCall{id='" + id + "', type='" + type + "', function=" + function + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AguiToolCall that = (AguiToolCall) o;
        return Objects.equals(id, that.id)
                && Objects.equals(type, that.type)
                && Objects.equals(function, that.function);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, type, function);
    }
}
