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

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

/**
 * AG-UI 协议中的文本输入内容。
 *
 * <p>JSON 格式：{@code {"type": "text", "text": "Hello"}}
 *
 * <p>转换时映射为 {@link io.agentscope.core.message.TextBlock}。
 *
 * @param text 文本内容（不可为 null）
 * @author shanhongyu
 * @since 2026-08-01
 */
public record TextInputContent(@JsonProperty("text") String text) implements InputContent {

    /**
     * 确保 {@code text} 不为 null 的紧凑构造器。
     *
     * @param text 文本内容
     */
    public TextInputContent {
        Objects.requireNonNull(text, "text cannot be null");
    }
}
