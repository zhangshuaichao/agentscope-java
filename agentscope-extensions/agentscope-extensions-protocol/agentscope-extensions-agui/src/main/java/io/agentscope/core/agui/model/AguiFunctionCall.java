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
 * 表示 AG-UI 协议工具调用中的函数调用。
 *
 * <p>包含函数名称及 JSON 字符串形式的参数。
 */
public class AguiFunctionCall {

    private final String name;
    private final String arguments;

    /**
     * 创建 AguiFunctionCall。
     *
     * @param name 函数名称
     * @param arguments JSON 字符串形式的函数参数
     */
    @JsonCreator
    public AguiFunctionCall(
            @JsonProperty("name") String name, @JsonProperty("arguments") String arguments) {
        this.name = Objects.requireNonNull(name, "name cannot be null");
        this.arguments = arguments != null ? arguments : "{}";
    }

    /**
     * 获取函数名称。
     *
     * @return 函数名称
     */
    public String getName() {
        return name;
    }

    /**
     * 获取 JSON 字符串形式的函数参数。
     *
     * @return 参数 JSON 字符串
     */
    public String getArguments() {
        return arguments;
    }

    @Override
    public String toString() {
        return "AguiFunctionCall{name='" + name + "', arguments='" + arguments + "'}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AguiFunctionCall that = (AguiFunctionCall) o;
        return Objects.equals(name, that.name) && Objects.equals(arguments, that.arguments);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, arguments);
    }
}
