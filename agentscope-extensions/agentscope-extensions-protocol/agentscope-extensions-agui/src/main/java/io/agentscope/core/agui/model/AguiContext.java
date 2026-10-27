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
 * 表示 AG-UI 协议中的一项上下文信息。
 *
 * <p>上下文项为智能体提供可能有助于生成回复的额外信息，包含上下文含义的描述及其实际值。
 */
public class AguiContext {

    private final String description;
    private final String value;

    /**
     * 创建 AguiContext。
     *
     * @param description 上下文含义的描述
     * @param value 实际上下文值
     */
    @JsonCreator
    public AguiContext(
            @JsonProperty("description") String description, @JsonProperty("value") String value) {
        this.description = Objects.requireNonNull(description, "description cannot be null");
        this.value = Objects.requireNonNull(value, "value cannot be null");
    }

    /**
     * 获取上下文描述。
     *
     * @return 描述
     */
    public String getDescription() {
        return description;
    }

    /**
     * 获取上下文值。
     *
     * @return 值
     */
    public String getValue() {
        return value;
    }

    @Override
    public String toString() {
        return "AguiContext{description='" + description + "', value='" + value + "'}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AguiContext that = (AguiContext) o;
        return Objects.equals(description, that.description) && Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(description, value);
    }
}
