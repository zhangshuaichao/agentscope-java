/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.model.transport.websocket;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * WebSocket 连接请求配置。
 *
 * <p>用法示例：
 *
 * <pre>{@code
 * WebSocketRequest request = WebSocketRequest.builder("wss://api.openai.com/v1/realtime")
 *     .header("Authorization", "Bearer " + apiKey)
 *     .header("OpenAI-Beta", "realtime=v1")
 *     .connectTimeout(Duration.ofSeconds(30))
 *     .build();
 * }</pre>
 */
public final class WebSocketRequest {

    private final String url;
    private final Map<String, String> headers;
    private final Duration connectTimeout;

    private WebSocketRequest(Builder builder) {
        this.url = builder.url;
        this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(builder.headers));
        this.connectTimeout = builder.connectTimeout;
    }

    /**
     * 获取 WebSocket URL。
     *
     * @return WebSocket URL
     */
    public String getUrl() {
        return url;
    }

    /**
     * 获取请求头。
     *
     * @return 不可变的请求头
     */
    public Map<String, String> getHeaders() {
        return headers;
    }

    /**
     * 获取连接超时。
     *
     * @return 连接超时
     */
    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    /**
     * 使用指定 URL 创建新建造者。
     *
     * @param url WebSocket URL
     * @return Builder 实例
     */
    public static Builder builder(String url) {
        return new Builder(url);
    }

    /** WebSocketRequest 的建造者。 */
    public static class Builder {
        private final String url;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private Duration connectTimeout = Duration.ofSeconds(30);

        private Builder(String url) {
            this.url = Objects.requireNonNull(url, "url is required");
        }

        /**
         * 添加一个请求头。
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
         * 添加多个请求头。
         *
         * @param headers 要添加的请求头
         * @return 当前建造者
         */
        public Builder headers(Map<String, String> headers) {
            this.headers.putAll(headers);
            return this;
        }

        /**
         * 设置连接超时。
         *
         * @param timeout 连接超时
         * @return 当前建造者
         */
        public Builder connectTimeout(Duration timeout) {
            this.connectTimeout = timeout;
            return this;
        }

        /**
         * 构建 WebSocketRequest。
         *
         * @return WebSocketRequest 实例
         */
        public WebSocketRequest build() {
            return new WebSocketRequest(this);
        }
    }
}
