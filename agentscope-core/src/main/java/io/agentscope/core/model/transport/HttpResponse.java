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
 * 传输层的 HTTP 响应封装。
 *
 * <p>表示包含状态码、响应头和响应体的 HTTP 响应。
 */
public class HttpResponse {

    private final int statusCode;
    private final Map<String, String> headers;
    private final String body;

    private HttpResponse(Builder builder) {
        this.statusCode = builder.statusCode;
        this.headers = Collections.unmodifiableMap(new HashMap<>(builder.headers));
        this.body = builder.body;
    }

    /**
     * 获取 HTTP 状态码。
     *
     * @return 状态码
     */
    public int getStatusCode() {
        return statusCode;
    }

    /**
     * 获取响应头。
     *
     * @return 响应头的不可修改映射
     */
    public Map<String, String> getHeaders() {
        return headers;
    }

    /**
     * 获取响应体。
     *
     * @return 响应体字符串；无响应体时为 null
     */
    public String getBody() {
        return body;
    }

    /**
     * 检查响应是否表示成功（2xx 状态码）。
     *
     * @return 状态码为 2xx 时返回 true
     */
    public boolean isSuccessful() {
        return statusCode >= 200 && statusCode < 300;
    }

    /**
     * 创建新的 HttpResponse 建造者。
     *
     * @return 新的 Builder 实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /** HttpResponse 的建造者。 */
    public static class Builder {
        private int statusCode;
        private final Map<String, String> headers = new HashMap<>();
        private String body;

        /**
         * 设置 HTTP 状态码。
         *
         * @param statusCode 状态码
         * @return 当前建造者
         */
        public Builder statusCode(int statusCode) {
            this.statusCode = statusCode;
            return this;
        }

        /**
         * 向响应添加一个响应头。
         *
         * @param name 响应头名称
         * @param value 响应头值
         * @return 当前建造者
         */
        public Builder header(String name, String value) {
            this.headers.put(name, value);
            return this;
        }

        /**
         * 向响应添加多个响应头。
         *
         * @param headers 要添加的响应头
         * @return 当前建造者
         */
        public Builder headers(Map<String, String> headers) {
            this.headers.putAll(headers);
            return this;
        }

        /**
         * 设置响应体。
         *
         * @param body 响应体字符串
         * @return 当前建造者
         */
        public Builder body(String body) {
            this.body = body;
            return this;
        }

        /**
         * 构建 HttpResponse。
         *
         * @return 新的 HttpResponse 实例
         */
        public HttpResponse build() {
            return new HttpResponse(this);
        }
    }
}
