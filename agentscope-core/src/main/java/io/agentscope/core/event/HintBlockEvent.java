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

/**
 * 一次性提示块事件。
 *
 * <p>与文本块／思考块不同，提示块不采用流式传输：创建时即已有完整内容
 * （例如团队消息、后台工具结果、用户中断等）。一个事件承载完整提示。
 */
public class HintBlockEvent extends AgentEvent {

    private final String replyId;
    private final String blockId;
    private final String hintSource;
    private final String hint;

    @JsonCreator
    public HintBlockEvent(
            @JsonProperty("id") String id,
            @JsonProperty("createdAt") String createdAt,
            @JsonProperty("replyId") String replyId,
            @JsonProperty("blockId") String blockId,
            @JsonProperty("hintSource") String hintSource,
            @JsonProperty("hint") String hint) {
        super(id, createdAt);
        this.replyId = replyId;
        this.blockId = blockId;
        this.hintSource = hintSource;
        this.hint = hint;
    }

    public HintBlockEvent(String replyId, String blockId, String hintSource, String hint) {
        this.replyId = replyId;
        this.blockId = blockId;
        this.hintSource = hintSource;
        this.hint = hint;
    }

    @Override
    public AgentEventType getType() {
        return AgentEventType.HINT_BLOCK;
    }

    public String getReplyId() {
        return replyId;
    }

    public String getBlockId() {
        return blockId;
    }

    /**
     * 返回此提示的发送方或来源。对于团队消息，这是发送方的显示名称
     * （例如 {@code "alice"}）；对于系统通知，可能是 {@code "system"} 或
     * {@code null}。
     *
     * <p>命名为 {@code hintSource}，以避免与承载子智能体转发路径的
     * {@link AgentEvent#getSource()} 冲突。
     */
    public String getHintSource() {
        return hintSource;
    }

    public String getHint() {
        return hint;
    }
}
