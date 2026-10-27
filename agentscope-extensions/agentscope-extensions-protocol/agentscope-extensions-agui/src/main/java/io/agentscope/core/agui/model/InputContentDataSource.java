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
 * AG-UI 协议中的内联数据来源。
 *
 * <p>JSON 格式：{@code {"type": "data", "value": "<base64>", "mimeType": "image/png"}}
 *
 * <p>转换时映射为 AgentScope 的 {@link io.agentscope.core.message.Base64Source}：{@code value} &rarr; {@code data}，{@code mimeType} &rarr; {@code mediaType}。
 *
 * @param value Base64 编码的数据内容
 * @param mimeType MIME 类型（例如 "image/png"）
 * @author shanhongyu
 * @since 2026-08-01
 */
public record InputContentDataSource(
        @JsonProperty("value") String value, @JsonProperty("mimeType") String mimeType)
        implements InputContentSource {

    /**
     * 确保必填字段非 null 的紧凑构造器。
     *
     * @param value 数据内容
     * @param mimeType MIME 类型
     */
    public InputContentDataSource {
        Objects.requireNonNull(value, "value cannot be null");
        Objects.requireNonNull(mimeType, "mimeType cannot be null");
    }
}
