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
package io.agentscope.core.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import java.util.Map;

/**
 * 运行中的工具通过 {@link
 * io.agentscope.core.tool.ToolEmitter} 发布的中间进度更新。
 */
public class ToolProgressEvent extends AgentEvent {

    private final String replyId;
    private final String toolCallId;
    private final String toolCallName;
    private final ContentBlock content;

    @JsonCreator
    public ToolProgressEvent(
            @JsonProperty("id") String id,
            @JsonProperty("createdAt") String createdAt,
            @JsonProperty("replyId") String replyId,
            @JsonProperty("toolCallId") String toolCallId,
            @JsonProperty("toolCallName") String toolCallName,
            @JsonProperty("content") ContentBlock content,
            @JsonProperty("metadata") Map<String, Object> metadata) {
        super(id, createdAt);
        this.replyId = replyId;
        this.toolCallId = toolCallId;
        this.toolCallName = toolCallName;
        this.content = content;
        this.withMetadata(metadata);
    }

    /**
     * 供未提供元数据的调用方使用的向后兼容构造器。
     */
    public ToolProgressEvent(
            String id,
            String createdAt,
            String replyId,
            String toolCallId,
            String toolCallName,
            ContentBlock content) {
        this(id, createdAt, replyId, toolCallId, toolCallName, content, null);
    }

    public ToolProgressEvent(
            String replyId, String toolCallId, String toolCallName, ContentBlock content) {
        this.replyId = replyId;
        this.toolCallId = toolCallId;
        this.toolCallName = toolCallName;
        this.content = content;
    }

    @Override
    public AgentEventType getType() {
        return AgentEventType.TOOL_PROGRESS;
    }

    public String getReplyId() {
        return replyId;
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public String getToolCallName() {
        return toolCallName;
    }

    /**
     * 工具发布的进度片段。
     *
     * @return 发出的内容块，可以是文本或其他类型
     */
    public ContentBlock getContent() {
        return content;
    }

    /**
     * 进度片段为文本时，返回其文本内容。
     *
     * <p>多数消费方只渲染进度，因此直接暴露常用的文本情形；非文本片段
     * 在此读取为 {@code null}，但仍可通过 {@link #getContent()} 获取。
     *
     * @return 片段的文本；片段不是文本块时为 {@code null}
     */
    public String getText() {
        return content instanceof TextBlock textBlock ? textBlock.getText() : null;
    }
}
