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
package io.agentscope.core.model.transport;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 传输层的 HTTP 请求封装。
 *
 * <p>表示包含 URL、方法、请求头和请求体的 HTTP 请求。
 * 使用建造者模式创建请求。
 */
public class HttpRequest {

    private final String url;
    private final String method;
    private final Map<String, String> headers;
    private final String body;

    private HttpRequest(Builder builder) {
        this.url = builder.url;
        this.method = builder.method;
        this.headers = Collections.unmodifiableMap(new HashMap<>(builder.headers));
        this.body = builder.body;
    }

    /**
     * 获取请求 URL。
     *
     * @return URL
     */
    public String getUrl() {
        return url;
    }

    /**
     * 获取 HTTP 方法。
     *
     * @return 方法（GET、POST 等）
     */
    public String getMethod() {
        return method;
    }

    /**
     * 获取请求头。
     *
     * @return 请求头的不可修改映射
     */
    public Map<String, String> getHeaders() {
        return headers;
    }

    /**
     * 获取请求体。
     *
     * @return 请求体字符串；无请求体时为 null
     */
    public String getBody() {
        return body;
    }

    /**
     * 创建新的 HttpRequest 建造者。
     *
     * @return 新的 Builder 实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /** HttpRequest 的建造者。 */
    public static class Builder {
        private String url;
        private String method = "GET";
        private final Map<String, String> headers = new HashMap<>();
        private String body;

        /**
         * 设置请求 URL。
         *
         * @param url URL
         * @return 当前建造者
         */
        public Builder url(String url) {
            this.url = url;
            return this;
        }

        /**
         * 设置 HTTP 方法。
         *
         * @param method 方法（GET、POST 等）
         * @return 当前建造者
         */
        public Builder method(String method) {
            this.method = method;
            return this;
        }

        /**
         * 向请求添加一个请求头。
         *
         * @param name 请求头名称
         * @param value 请求头值
         * @return 当前建造者
         */
        public Builder header(String name, String value) {
            this.headers.put(name, value);
            return this;
        }

        /**
         * 向请求添加多个请求头。
         *
         * @param headers 要添加的请求头
         * @return 当前建造者
         */
        public Builder headers(Map<String, String> headers) {
            this.headers.putAll(headers);
            return this;
        }

        /**
         * 设置请求体。
         *
         * @param body 请求体字符串
         * @return 当前建造者
         */
        public Builder body(String body) {
            this.body = body;
            return this;
        }

        /**
         * 构建 HttpRequest。
         *
         * @return 新的 HttpRequest 实例
         */
        public HttpRequest build() {
            if (url == null || url.isEmpty()) {
                throw new IllegalArgumentException("URL is required");
            }
            return new HttpRequest(this);
        }
    }
}
