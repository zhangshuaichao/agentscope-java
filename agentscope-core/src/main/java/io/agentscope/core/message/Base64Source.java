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
import java.util.Objects;

/**
 * 表示带有媒体类型和数据的 Base64 编码媒体内容。
 *
 * <p>将二进制数据编码为 Base64 字符串，以便直接嵌入消息。
 * 格式遵循标准 MIME 类型约定，并采用 Base64 编码。
 *
 * <p>常见媒体类型包括：
 * <ul>
 * <li>image/jpeg, image/png, image/gif：图像</li>
 * <li>audio/mpeg, audio/wav, audio/ogg：音频</li>
 * <li>video/mp4, video/avi, video/mov：视频</li>
 * </ul>
 *
 * <p>适用于需要将媒体文件直接包含在消息中，而非通过 URL 引用的场景。
 */
public class Base64Source extends Source {

    @JsonProperty("media_type")
    private final String mediaType;

    private final String data;

    /**
     * 创建用于 JSON 反序列化的 Base64 来源。
     *
     * @param mediaType 媒体内容的 MIME 类型（例如 "image/jpeg"）
     * @param data Base64 编码的媒体数据
     * @throws NullPointerException 当 mediaType 或 data 为 null 时
     */
    @JsonCreator
    public Base64Source(
            @JsonProperty("media_type") String mediaType, @JsonProperty("data") String data) {
        this.mediaType = Objects.requireNonNull(mediaType, "mediaType cannot be null");
        this.data = Objects.requireNonNull(data, "data cannot be null");
    }

    /**
     * 获取媒体内容的 MIME 类型。
     *
     * @return 媒体类型字符串（例如 "image/jpeg"）
     */
    public String getMediaType() {
        return mediaType;
    }

    /**
     * 获取 Base64 编码的媒体数据。
     *
     * @return Base64 数据字符串
     */
    public String getData() {
        return data;
    }

    /**
     * 创建用于构造 Base64Source 实例的构建器。
     *
     * @return 新的构建器实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 用于构造 Base64Source 实例的构建器。
     */
    public static class Builder {

        private String mediaType;

        private String data;

        /**
         * 设置媒体内容的 MIME 类型。
         *
         * @param mediaType MIME 类型（例如 "image/jpeg"）
         * @return 当前构建器，支持链式调用
         */
        public Builder mediaType(String mediaType) {
            this.mediaType = mediaType;
            return this;
        }

        /**
         * 设置 Base64 编码的媒体数据。
         *
         * @param data Base64 数据字符串
         * @return 当前构建器，支持链式调用
         */
        public Builder data(String data) {
            this.data = data;
            return this;
        }

        /**
         * 使用配置的属性构建 Base64Source。
         *
         * @return 新的 Base64Source 实例
         * @throws NullPointerException 当 mediaType 或 data 为 null 时
         */
        public Base64Source build() {
            return new Base64Source(mediaType, data);
        }
    }
}
