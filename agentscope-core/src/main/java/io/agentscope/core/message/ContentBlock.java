/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.message;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.agentscope.core.state.State;

/**
 * 所有消息内容块的密封基类。
 *
 * <p>内容块表示消息可包含的不同内容类型，如文本、图像、音频、视频或思考内容。
 * 密封继承层次保证类型安全，并支持穷尽式模式匹配。
 *
 * <p><b>支持的内容类型：</b>
 * <ul>
 * <li>{@link TextBlock}：纯文本内容
 * <li>{@link ThinkingBlock}：智能体推理或思考内容
 * <li>{@link ImageBlock}：图像内容（URL 或 Base64）
 * <li>{@link AudioBlock}：音频内容（URL 或 Base64）
 * <li>{@link VideoBlock}：视频内容（URL 或 Base64）
 * <li>{@link ToolUseBlock}：工具执行请求
 * <li>{@link ToolResultBlock}：工具执行结果
 * <li>{@link HintBlock}：用于大语言模型推理的提示信息（例如来自 RAG）
 * <li>{@link DataBlock}：统一图像、音频和视频的通用二进制数据块
 * </ul>
 *
 * <p>使用 Jackson 注解和 "type" 判别字段实现多态 JSON 序列化。
 * sealed 修饰符将子类限制为 permits 列表中的类型，从而支持模式匹配的编译期穷尽性检查。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = TextBlock.class, name = "text"),
    @JsonSubTypes.Type(value = ThinkingBlock.class, name = "thinking"),
    @JsonSubTypes.Type(value = ImageBlock.class, name = "image"),
    @JsonSubTypes.Type(value = AudioBlock.class, name = "audio"),
    @JsonSubTypes.Type(value = VideoBlock.class, name = "video"),
    @JsonSubTypes.Type(value = ToolUseBlock.class, name = "tool_use"),
    @JsonSubTypes.Type(value = ToolResultBlock.class, name = "tool_result"),
    @JsonSubTypes.Type(value = HintBlock.class, name = "hint"),
    @JsonSubTypes.Type(value = DataBlock.class, name = "data")
})
public sealed class ContentBlock implements State
        permits TextBlock,
                ImageBlock,
                AudioBlock,
                VideoBlock,
                ThinkingBlock,
                ToolUseBlock,
                ToolResultBlock,
                HintBlock,
                DataBlock {}
