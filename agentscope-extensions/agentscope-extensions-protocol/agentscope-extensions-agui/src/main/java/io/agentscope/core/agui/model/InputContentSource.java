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
 * 表示 AG-UI 协议内容来源的密封接口。
 *
 * <p>对应协议的 {@code InputContentSource} 联合类型：
 * <ul>
 * <li>{@code "data"} &rarr; {@link InputContentDataSource}（内联 Base64 数据）</li>
 * <li>{@code "url"} &rarr; {@link InputContentUrlSource}（远程 URL 引用）</li>
 * </ul>
 *
 * <p>与 AgentScope 内部的 {@link io.agentscope.core.message.Source} 层次（{@code URLSource} / {@code Base64Source}）不同，由 {@code AguiMessageConverter} 在两层之间转换。
 *
 * @author shanhongyu
 * @since 2026-08-01
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = InputContentDataSource.class, name = "data"),
    @JsonSubTypes.Type(value = InputContentUrlSource.class, name = "url")
})
public sealed interface InputContentSource permits InputContentDataSource, InputContentUrlSource {}
