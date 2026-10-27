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

/**
 * 表示基于 URL 的媒体内容。
 *
 * <p>引用可通过 URL 访问的媒体文件，同时支持远程 HTTP/HTTPS URL 和本地文件 URL。
 * 媒体文件托管于外部，或文件过大而不适合 Base64 编码时，应优先采用此方式。
 *
 * <p>支持的 URL 格式：
 * <ul>
 * <li>远程 URL：https://example.com/image.jpg</li>
 * <li>本地文件：file:///absolute/path/to/file.jpg</li>
 * </ul>
 *
 * <p>对于大型媒体文件，URL 来源效率更高，可让系统流式处理内容，而不必全部加载到内存。
 *
 * <p>URL 不含文件扩展名时（例如 CDN 签名 URL），应显式设置 {@code mimeType}，
 * 使转换器无需依赖扩展名推断，就能将内容送入正确的媒体位置。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class URLSource extends Source {

    private final String url;

    @JsonProperty("mime_type")
    private final String mimeType;

    /**
     * 创建用于 JSON 反序列化的 URL 来源。
     *
     * @param url 指向媒体内容的 URL
     * @param mimeType 可选 MIME 类型提示（例如 "image/jpeg"），可为 null
     * @throws NullPointerException 当 url 为 null 时
     */
    @JsonCreator
    public URLSource(@JsonProperty("url") String url, @JsonProperty("mime_type") String mimeType) {
        this.url = Objects.requireNonNull(url, "url cannot be null");
        this.mimeType = mimeType;
    }

    /**
     * 创建不带 MIME 类型提示的 URL 来源。
     *
     * @param url 指向媒体内容的 URL
     * @throws NullPointerException 当 url 为 null 时
     */
    public URLSource(String url) {
        this(url, null);
    }

    /**
     * 获取指向媒体内容的 URL。
     *
     * @return URL 字符串
     */
    public String getUrl() {
        return url;
    }

    /**
     * 获取 URL 来源的可选 MIME 类型提示。
     *
     * <p>存在此值时，转换器使用它，而不再根据 URL 的文件扩展名推断类型。适用于没有扩展名的 URL，例如 CDN 签名链接或 API 生成的媒体端点。
     *
     * @return MIME 类型（例如 "image/jpeg"）；未设置时为 null
     */
    public String getMimeType() {
        return mimeType;
    }

    /**
     * 创建用于构造 URLSource 实例的构建器。
     *
     * @return 新的构建器实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 用于构造 URLSource 实例的构建器。
     */
    public static class Builder {

        private String url;

        private String mimeType;

        /**
         * 设置媒体内容的 URL。
         *
         * @param url 指向媒体内容的 URL
         * @return 当前构建器，支持链式调用
         */
        public Builder url(String url) {
            this.url = url;
            return this;
        }

        /**
         * 为无扩展名 URL 设置可选 MIME 类型提示。
         *
         * @param mimeType MIME 类型（例如 "video/mp4"）
         * @return 当前构建器，支持链式调用
         */
        public Builder mimeType(String mimeType) {
            this.mimeType = mimeType;
            return this;
        }

        /**
         * 使用配置的字段构建 URLSource。
         *
         * @return 新的 URLSource 实例
         * @throws NullPointerException 当 url 为 null 时
         */
        public URLSource build() {
            return new URLSource(url, mimeType);
        }
    }
}
