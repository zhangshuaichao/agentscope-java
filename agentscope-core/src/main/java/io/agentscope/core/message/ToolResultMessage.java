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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 构造时将角色固定为 {@link MsgRole#TOOL} 的 {@link Msg}。
 *
 * <p>内容由一个或多个 {@link ToolResultBlock} 组成。三参数构造器接收工具调用 ID、工具名称和结果字符串，自动将字符串包装为 {@link ToolResultBlock} 内的 {@link TextBlock}。
 *
 * <p>还提供独立的 {@link Builder}，用于累积多个工具结果、附加元数据或显式设置时间戳和用量。
 */
public final class ToolResultMessage extends Msg {

    public ToolResultMessage(String toolCallId, String toolName, String resultText) {
        super(
                generateId(),
                null,
                MsgRole.TOOL,
                List.of(
                        new ToolResultBlock(
                                toolCallId,
                                toolName,
                                List.of(TextBlock.builder().text(resultText).build()))),
                null,
                currentTimestamp(),
                null);
    }

    public ToolResultMessage(ToolResultBlock... results) {
        super(
                generateId(),
                null,
                MsgRole.TOOL,
                results == null ? List.of() : List.copyOf(Arrays.asList(results)),
                null,
                currentTimestamp(),
                null);
    }

    public ToolResultMessage(List<ToolResultBlock> results) {
        super(
                generateId(),
                null,
                MsgRole.TOOL,
                results == null ? List.of() : List.copyOf(results),
                null,
                currentTimestamp(),
                null);
    }

    @JsonCreator
    private ToolResultMessage(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("content") List<ContentBlock> content,
            @JsonProperty("metadata") Map<String, Object> metadata,
            @JsonProperty("timestamp") String timestamp,
            @JsonProperty("usage") ChatUsage usage) {
        super(id, name, MsgRole.TOOL, content, metadata, timestamp, usage);
    }

    /**
     * 创建 {@link ToolResultMessage} 的构建器，并预填随机生成的 ID 和当前时间戳。
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * {@link ToolResultMessage} 的链式构建器。角色固定为 {@link MsgRole#TOOL}，不支持 {@link #role(MsgRole)}。
     *
     * <p>{@link #result(String, String, String)} 和 {@link #result(ToolResultBlock)} 向结果列表追加内容；{@link #results(ToolResultBlock...)}、{@link #results(List)} 以及继承的 {@code content(...)} 设置方法则整体替换列表。
     */
    public static final class Builder extends Msg.Builder {

        public Builder() {
            super.role = MsgRole.TOOL;
            super.content = new ArrayList<>();
        }

        /**
         * {@link ToolResultMessage.Builder} 不支持此操作：角色固定为 TOOL。
         */
        @Override
        public Builder role(MsgRole role) {
            throw new UnsupportedOperationException(
                    "ToolResultMessage role is fixed to TOOL; use Msg.builder() to set role.");
        }

        /**
         * 追加由工具调用 ID、工具名称和结果文本构造的 {@link ToolResultBlock}。
         */
        public Builder result(String toolCallId, String toolName, String resultText) {
            return result(
                    new ToolResultBlock(
                            toolCallId,
                            toolName,
                            List.of(TextBlock.builder().text(resultText).build())));
        }

        /**
         * 追加单个 {@link ToolResultBlock}。
         */
        public Builder result(ToolResultBlock block) {
            if (block != null) {
                if (!(super.content instanceof ArrayList)) {
                    super.content = new ArrayList<>(super.content);
                }
                ((ArrayList<ContentBlock>) super.content).add(block);
            }
            return this;
        }

        /**
         * 用给定列表替换全部已累积的结果。
         */
        public Builder results(List<ToolResultBlock> results) {
            super.content = results == null ? new ArrayList<>() : new ArrayList<>(results);
            return this;
        }

        /**
         * 用给定内容块替换全部已累积的结果。
         */
        public Builder results(ToolResultBlock... results) {
            super.content =
                    results == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(results));
            return this;
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
        public ToolResultMessage build() {
            return new ToolResultMessage(
                    id, name, List.copyOf(content), metadata, timestamp, usage);
        }
    }
}
