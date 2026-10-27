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

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * 表示 AG-UI 协议中类型化内容片段的密封接口。
 *
 * <p>对应协议的 {@code InputContent} 联合类型，覆盖文本、图像、音频、视频和文档输入。
 *
 * <p>JSON 多态通过 {@code type} 判别字段处理，与 AG-UI 协议规范一致：
 * <ul>
 * <li>{@code "text"} &rarr; {@link TextInputContent}</li>
 * <li>{@code "image"} &rarr; {@link ImageInputContent}</li>
 * <li>{@code "audio"} &rarr; {@link AudioInputContent}</li>
 * <li>{@code "video"} &rarr; {@link VideoInputContent}</li>
 * <li>{@code "document"} &rarr; {@link DocumentInputContent}</li>
 * </ul>
 *
 * @see MessageContent
 * @see io.agentscope.core.agui.converter.AguiMessageConverter
 * @author shanhongyu
 * @since 2026-08-01
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = TextInputContent.class, name = "text"),
    @JsonSubTypes.Type(value = ImageInputContent.class, name = "image"),
    @JsonSubTypes.Type(value = AudioInputContent.class, name = "audio"),
    @JsonSubTypes.Type(value = VideoInputContent.class, name = "video"),
    @JsonSubTypes.Type(value = DocumentInputContent.class, name = "document")
})
public sealed interface InputContent
        permits TextInputContent,
                ImageInputContent,
                AudioInputContent,
                VideoInputContent,
                DocumentInputContent {}
