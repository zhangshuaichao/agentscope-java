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
 * 构造时将角色固定为 {@link MsgRole#SYSTEM} 的 {@link Msg}。
 *
 * <p>提供直接接收 {@code String} 的便捷构造器（将字符串包装为 {@link TextBlock}）。{@link Msg} 的验证器将系统消息限制为文本块；传入非文本块会抛出 {@link IllegalArgumentException}。
 *
 * <p>还提供独立的 {@link Builder}，用于设置 {@code metadata}、{@code timestamp}、{@code usage} 或 {@code generateReason}。
 */
public final class SystemMessage extends Msg {

    public SystemMessage(String text) {
        this(null, text);
    }

    public SystemMessage(String name, String text) {
        super(
                generateId(),
                name,
                MsgRole.SYSTEM,
                List.of(TextBlock.builder().text(text).build()),
                null,
                currentTimestamp(),
                null);
    }

    public SystemMessage(ContentBlock... blocks) {
        this(null, blocks);
    }

    public SystemMessage(String name, ContentBlock... blocks) {
        super(
                generateId(),
                name,
                MsgRole.SYSTEM,
                blocks == null ? List.of() : List.of(blocks),
                null,
                currentTimestamp(),
                null);
    }

    public SystemMessage(List<ContentBlock> blocks) {
        super(generateId(), null, MsgRole.SYSTEM, blocks, null, currentTimestamp(), null);
    }

    @JsonCreator
    private SystemMessage(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("content") List<ContentBlock> content,
            @JsonProperty("metadata") Map<String, Object> metadata,
            @JsonProperty("timestamp") String timestamp,
            @JsonProperty("usage") ChatUsage usage) {
        super(id, name, MsgRole.SYSTEM, content, metadata, timestamp, usage);
    }

    /**
     * 创建 {@link SystemMessage} 的构建器，并预填随机生成的 ID 和当前时间戳。
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * {@link SystemMessage} 的链式构建器。角色固定为 {@link MsgRole#SYSTEM}，不支持 {@link #role(MsgRole)}。
     */
    public static final class Builder extends Msg.Builder {

        public Builder() {
            super.role = MsgRole.SYSTEM;
        }

        /**
         * {@link SystemMessage.Builder} 不支持此操作：角色固定为 SYSTEM。
         */
        @Override
        public Builder role(MsgRole role) {
            throw new UnsupportedOperationException(
                    "SystemMessage role is fixed to SYSTEM; use Msg.builder() to set role.");
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
        public SystemMessage build() {
            return new SystemMessage(id, name, content, metadata, timestamp, usage);
        }
    }
}
