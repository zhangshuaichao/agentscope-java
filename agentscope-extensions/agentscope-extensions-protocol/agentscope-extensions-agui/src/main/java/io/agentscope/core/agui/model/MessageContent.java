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

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.util.List;

/**
 * 表示 AG-UI 消息 {@code content} 字段的类型安全联合。
 *
 * <p>协议中消息内容可以是纯字符串，也可以是结构化 {@link InputContent} 片段数组。此密封接口无需 {@code Object} 即可建模该联合，提供编译期类型安全和穷尽模式匹配的基础。
 *
 * <p>JSON 格式：
 * <ul>
 * <li>{@link Text} &rarr; JSON 字符串：{@code "Hello"}</li>
 * <li>{@link Blocks} &rarr; JSON 数组：{@code [{"type":"text","text":"Hi"},{"type":"image",...}]}</li>
 * </ul>
 *
 * <p>自定义 Jackson 序列化/反序列化通过 {@link MessageContentDeserializer} 和 {@link MessageContentSerializer} 处理非对象 JSON 形态（标量或数组）。
 *
 * @see InputContent
 * @see AguiMessage
 * @author shanhongyu
 * @since 2026-08-01
 */
@JsonDeserialize(using = MessageContentDeserializer.class)
@JsonSerialize(using = MessageContentSerializer.class)
public sealed interface MessageContent permits MessageContent.Text, MessageContent.Blocks {

    /**
     * 纯文本内容，序列化为 JSON 字符串。
     *
     * @param value 文本内容
     */
    record Text(String value) implements MessageContent {}

    /**
     * 结构化内容块，序列化为 {@link InputContent} 的 JSON 数组。
     *
     * @param parts 内容片段列表；通过防御性复制确保列表不可变
     */
    record Blocks(List<InputContent> parts) implements MessageContent {

        /**
         * 创建片段列表不可变副本的紧凑构造器。
         *
         * @param parts 内容片段
         */
        public Blocks {
            parts = List.copyOf(parts);
        }
    }
}
