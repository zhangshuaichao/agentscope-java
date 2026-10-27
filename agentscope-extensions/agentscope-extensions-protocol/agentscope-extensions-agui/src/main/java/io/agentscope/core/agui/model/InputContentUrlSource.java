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
 * AG-UI 协议中的 URL 来源。
 *
 * <p>JSON 格式：{@code {"type": "url", "value": "https://...", "mimeType": "image/png"}}
 *
 * <p>转换时映射为 AgentScope 的 {@link io.agentscope.core.message.URLSource}：{@code value} &rarr; {@code url}，{@code mimeType} &rarr; {@code mimeType}。
 *
 * @param value 指向媒体内容的 URL
 * @param mimeType 可选 MIME 类型提示，可为 null
 * @author shanhongyu
 * @since 2026-08-01
 */
public record InputContentUrlSource(
        @JsonProperty("value") String value, @JsonProperty("mimeType") String mimeType)
        implements InputContentSource {

    /**
     * 确保 {@code value} 非 null 的紧凑构造器。
     *
     * @param value URL 地址
     * @param mimeType 可选 MIME 类型
     */
    public InputContentUrlSource {
        Objects.requireNonNull(value, "value cannot be null");
    }

    /**
     * 不带 MIME 类型的便捷构造器。
     *
     * @param value URL 地址
     */
    public InputContentUrlSource(String value) {
        this(value, null);
    }
}
