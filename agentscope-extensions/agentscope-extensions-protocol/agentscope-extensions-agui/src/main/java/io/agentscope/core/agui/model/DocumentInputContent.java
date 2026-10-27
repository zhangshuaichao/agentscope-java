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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;
import java.util.Objects;

/**
 * AG-UI 协议中的文档输入内容。
 *
 * <p>JSON 格式：{@code {"type": "document", "source": {"type": "url", "value": "..."}, "metadata": {...}}}
 *
 * <p>与 {@link ImageInputContent}、{@link AudioInputContent}、{@link VideoInputContent} 结构相同（来源 + 元数据）。AG-UI 消息转换器当前拒绝文档输入；此类型仅表示协议载荷。
 *
 * @param source 文档来源（URL 或数据），不可为 null
 * @param metadata 可选元数据，可为 null
 * @author shanhongyu
 * @since 2026-08-01
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record DocumentInputContent(
        @JsonProperty("source") InputContentSource source,
        @JsonProperty("metadata") Map<String, Object> metadata)
        implements InputContent {

    /**
     * 确保 {@code source} 非 null 的紧凑构造器。
     *
     * @param source 文档来源
     * @param metadata 可选元数据
     */
    public DocumentInputContent {
        Objects.requireNonNull(source, "source cannot be null");
    }
}
