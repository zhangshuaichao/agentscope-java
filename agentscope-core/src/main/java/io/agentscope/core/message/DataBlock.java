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
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;
import java.util.UUID;

/**
 * 用于任意二进制媒体（图像、音频、视频、文件）的统一数据块。
 *
 * <p>SDK 保留旧的 {@link ImageBlock}、{@link AudioBlock}、{@link VideoBlock} 子类以兼容历史代码；
 * 而 {@code DataBlock} 是面向后续演进、覆盖所有二进制模态的多态容器。
 * 新代码应优先使用 {@code DataBlock}。旧类型仍可作为有效的 {@link MsgRole#USER} 载荷，
 * 保证现有处理流水线继续工作。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class DataBlock extends ContentBlock {

    private final Source source;

    private final String id;

    private final String name;

    /**
     * 创建用于 JSON 反序列化的数据块。
     *
     * @param source 数据来源（URL 或 Base64），必填
     * @param id 稳定标识符；为 null 时生成新的十六进制 UUID
     * @param name 可选的可读名称（例如文件名），可为 null
     * @throws NullPointerException 当 source 为 null 时
     */
    @JsonCreator
    private DataBlock(
            @JsonProperty("source") Source source,
            @JsonProperty("id") String id,
            @JsonProperty("name") String name) {
        this.source = Objects.requireNonNull(source, "source cannot be null");
        this.id =
                (id != null && !id.isEmpty()) ? id : UUID.randomUUID().toString().replace("-", "");
        this.name = name;
    }

    /**
     * 获取数据块的来源。
     *
     * @return 包含 URL 或 Base64 数据的数据来源
     */
    public Source getSource() {
        return source;
    }

    /**
     * 获取数据块的标识符。
     *
     * @return 数据块 ID，永不为 null
     */
    public String getId() {
        return id;
    }

    /**
     * 获取数据块的可选名称。
     *
     * @return 数据块名称；未设置时为 null
     */
    public String getName() {
        return name;
    }

    /**
     * 创建用于构造 DataBlock 实例的构建器。
     *
     * @return 新的构建器实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 用于构造 DataBlock 实例的构建器。
     */
    public static class Builder {

        private Source source;

        private String id;

        private String name;

        /**
         * 设置数据块的来源。
         *
         * @param source 数据来源（URL 或 Base64）
         * @return 当前构建器，支持链式调用
         */
        public Builder source(Source source) {
            this.source = source;
            return this;
        }

        /**
         * 设置数据块的标识符。若未设置，则在 {@link #build()} 时生成新的 UUID。
         *
         * @param id 稳定 ID
         * @return 当前构建器，支持链式调用
         */
        public Builder id(String id) {
            this.id = id;
            return this;
        }

        /**
         * 设置数据块的可选名称。
         *
         * @param name 数据块名称（例如文件名）
         * @return 当前构建器，支持链式调用
         */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * 使用配置的字段构建 DataBlock。
         *
         * @return 新的 DataBlock 实例
         * @throws NullPointerException 当 source 为 null 时
         */
        public DataBlock build() {
            return new DataBlock(source, id, name);
        }
    }
}
