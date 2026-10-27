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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.agentscope.core.model.ChatUsage;
import java.util.List;
import java.util.Map;

/**
 * 构造时将角色固定为 {@link MsgRole#ASSISTANT} 的 {@link Msg}。
 *
 * <p>提供便捷构造器，可直接传入 {@code String}（包装为 {@link TextBlock}），
 * 或任意组合的 {@link ContentBlock}，包括助手轮次常用的 {@link ThinkingBlock} 和 {@link ToolUseBlock}。
 *
 * <p>还提供独立的 {@link Builder}，用于设置 {@code metadata}、{@code timestamp}、
 * {@code usage} 或 {@code generateReason}。
 */
public final class AssistantMessage extends Msg {

    public AssistantMessage(String text) {
        this(null, text);
    }

    public AssistantMessage(String name, String text) {
        super(
                generateId(),
                name,
                MsgRole.ASSISTANT,
                List.of(TextBlock.builder().text(text).build()),
                null,
                currentTimestamp(),
                null);
    }

    public AssistantMessage(ContentBlock... blocks) {
        this(null, blocks);
    }

    public AssistantMessage(String name, ContentBlock... blocks) {
        super(
                generateId(),
                name,
                MsgRole.ASSISTANT,
                blocks == null ? List.of() : List.of(blocks),
                null,
                currentTimestamp(),
                null);
    }

    public AssistantMessage(List<ContentBlock> blocks) {
        super(generateId(), null, MsgRole.ASSISTANT, blocks, null, currentTimestamp(), null);
    }

    @JsonCreator
    private AssistantMessage(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("content") List<ContentBlock> content,
            @JsonProperty("metadata") Map<String, Object> metadata,
            @JsonProperty("timestamp") String timestamp,
            @JsonProperty("usage") ChatUsage usage) {
        super(id, name, MsgRole.ASSISTANT, content, metadata, timestamp, usage);
    }

    /**
     * 创建 {@link AssistantMessage} 的构建器，并预填随机生成的 ID 和当前时间戳。
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * {@link AssistantMessage} 的链式构建器。角色固定为 {@link MsgRole#ASSISTANT}，不支持 {@link #role(MsgRole)}。
     */
    public static final class Builder extends Msg.Builder {

        public Builder() {
            super.role = MsgRole.ASSISTANT;
        }

        /**
         * {@link AssistantMessage.Builder} 不支持此操作：角色固定为 ASSISTANT。
         */
        @Override
        public Builder role(MsgRole role) {
            throw new UnsupportedOperationException(
                    "AssistantMessage role is fixed to ASSISTANT; use Msg.builder() to set role.");
        }

        @Override
        public Builder id(String id) {
            super.id(id);
            return this;
        }

        @Override
        public Builder name(String name) {
            super.name(name);
            return this;
        }

        @Override
        public Builder content(List<ContentBlock> content) {
            super.content(content);
            return this;
        }

        @Override
        public Builder content(ContentBlock block) {
            super.content(block);
            return this;
        }

        @Override
        public Builder content(ContentBlock... blocks) {
            super.content(blocks);
            return this;
        }

        @Override
        public Builder textContent(String text) {
            super.textContent(text);
            return this;
        }

        @Override
        public Builder metadata(Map<String, Object> metadata) {
            super.metadata(metadata);
            return this;
        }

        @Override
        public Builder timestamp(String timestamp) {
            super.timestamp(timestamp);
            return this;
        }

        @Override
        public Builder usage(ChatUsage usage) {
            super.usage(usage);
            return this;
        }

        @Override
        public Builder generateReason(GenerateReason reason) {
            super.generateReason(reason);
            return this;
        }

        @Override
        public AssistantMessage build() {
            return new AssistantMessage(id, name, content, metadata, timestamp, usage);
        }
    }
}
