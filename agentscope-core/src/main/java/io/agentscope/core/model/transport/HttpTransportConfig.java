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

import java.time.Duration;

/**
 * HTTP 传输层配置。
 *
 * <p>保存 HTTP 客户端行为选项，例如超时、连接池设置与重试策略。
 *
 * <p>【学习提示】连接超时、首块响应超时、流空闲超时不是同一概念。
 * 同一个配置对象在 JDK 和 OkHttp 实现中的适用字段也不同，应沿具体实现追踪。
 */
public class HttpTransportConfig {

    /** 默认连接超时：30 秒。 */
    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(30);

    /** 默认读取超时：5 分钟。 */
    public static final Duration DEFAULT_READ_TIMEOUT = Duration.ofMinutes(5);

    /** 默认流式响应超时：5 分钟（从请求开始到发出首个流式数据块）。 */
    public static final Duration DEFAULT_RESPONSE_TIMEOUT = Duration.ofMinutes(5);

    /** 默认流空闲超时：5 分钟（数据块之间的最长等待时间）。 */
    public static final Duration DEFAULT_STREAM_IDLE_TIMEOUT = DEFAULT_READ_TIMEOUT;

    /** 默认写入超时：30 秒。 */
    public static final Duration DEFAULT_WRITE_TIMEOUT = Duration.ofSeconds(30);

    private final Duration connectTimeout;
    private final Duration responseTimeout;
    private final Duration streamIdleTimeout;
    private final Duration readTimeout;
    private final Duration writeTimeout;
    private final int maxIdleConnections;
    private final Duration keepAliveDuration;
    private final boolean ignoreSsl;
    private final ProxyConfig proxyConfig;
    private final HttpVersion httpVersion;

    private HttpTransportConfig(Builder builder) {
        this.connectTimeout = builder.connectTimeout;
        this.responseTimeout = builder.responseTimeout;
        this.streamIdleTimeout = builder.streamIdleTimeout;
        this.readTimeout = builder.readTimeout;
        this.writeTimeout = builder.writeTimeout;
        this.maxIdleConnections = builder.maxIdleConnections;
        this.keepAliveDuration = builder.keepAliveDuration;
        this.ignoreSsl = builder.ignoreSsl;
        this.proxyConfig = builder.proxyConfig;
        this.httpVersion = builder.httpVersion;
    }

    /**
     * 获取连接超时。
     *
     * @return 连接超时时长
     */
    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    /**
     * 获取流式请求的响应超时。
     *
     * <p>对于 {@link JdkHttpTransport} 的流式请求，此值限制从请求开始到发出首个 SSE/NDJSON 数据块的时间。
     * {@link OkHttpTransport} 不使用此选项，而是依赖 OkHttp 的 {@link #getReadTimeout()} 进行流读取。
     *
     * @return 响应超时时长
     */
    public Duration getResponseTimeout() {
        return responseTimeout;
    }

    /**
     * 获取流空闲超时。
     *
     * <p>对于 {@link JdkHttpTransport} 的流式请求，此值限制连续两个已发出 SSE/NDJSON 数据块之间的最长等待时间。
     * {@link OkHttpTransport} 不使用此选项，而是依赖 OkHttp 的 {@link #getReadTimeout()} 进行流读取。
     *
     * @return 流空闲超时时长
     */
    public Duration getStreamIdleTimeout() {
        return streamIdleTimeout;
    }

    /**
     * 获取读取超时。
     *
     * <p>{@link OkHttpTransport} 将此值用作普通请求和流式请求的 OkHttp 读取超时。
     * {@link JdkHttpTransport} 仅对非流式请求应用此值；JDK 流式请求改用
     * {@link #getResponseTimeout()} 和 {@link #getStreamIdleTimeout()}。
     *
     * @return 读取超时时长
     */
    public Duration getReadTimeout() {
        return readTimeout;
    }

    /**
     * 获取写入超时。
     *
     * @return 写入超时时长
     */
    public Duration getWriteTimeout() {
        return writeTimeout;
    }

    /**
     * 获取连接池中的最大空闲连接数。
     *
     * @return 最大空闲连接数
     */
    public int getMaxIdleConnections() {
        return maxIdleConnections;
    }

    /**
     * 获取空闲连接的保活时长。
     *
     * @return 保活时长
     */
    public Duration getKeepAliveDuration() {
        return keepAliveDuration;
    }

    /**
     * 获取是否忽略 SSL 证书验证。
     *
     * <p><b>警告：</b>设为 true 会禁用 SSL 证书验证，使连接面临中间人攻击风险。
     * 仅应在测试或使用可信自签名证书的场景中考虑此选项。
     *
     * @return true 表示忽略 SSL 证书验证，否则为 false
     */
    public boolean isIgnoreSsl() {
        return ignoreSsl;
    }

    /**
     * 获取代理配置。
     *
     * @return 代理配置；未配置代理时为 null
     */
    public ProxyConfig getProxyConfig() {
        return proxyConfig;
    }

    /**
     * 获取 HTTP 版本。
     *
     * @return HTTP 版本；未配置时为 null（自动：明文使用 HTTP/1.1，https 使用 HTTP/2）
     */
    public HttpVersion getHttpVersion() {
        return httpVersion;
    }

    /**
     * 创建新的 HttpTransportConfig 建造者。
     *
     * @return 新的 Builder 实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 创建默认配置。
     *
     * @return 默认 HttpTransportConfig 实例
     */
    public static HttpTransportConfig defaults() {
        return builder().build();
    }

    /** HttpTransportConfig 的建造者。 */
    public static class Builder {
        private Duration connectTimeout = DEFAULT_CONNECT_TIMEOUT;
        private Duration responseTimeout = DEFAULT_RESPONSE_TIMEOUT;
        private Duration streamIdleTimeout = DEFAULT_STREAM_IDLE_TIMEOUT;
        private Duration readTimeout = DEFAULT_READ_TIMEOUT;
        private Duration writeTimeout = DEFAULT_WRITE_TIMEOUT;
        private int maxIdleConnections = 5;
        private Duration keepAliveDuration = Duration.ofMinutes(5);
        private boolean ignoreSsl = false;
        private ProxyConfig proxyConfig = null;
        private HttpVersion httpVersion = null;

        /**
         * 设置连接超时。
         *
         * @param connectTimeout 连接超时时长
         * @return 当前建造者
         */
        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
            return this;
        }

        /**
         * 设置流式请求的响应超时。
         *
         * <p>对于 {@link JdkHttpTransport} 的流式请求，此值限制从请求开始到发出首个 SSE/NDJSON 数据块的时间。
         * {@link OkHttpTransport} 不使用此选项，而是依赖 OkHttp 的 {@link #getReadTimeout()} 进行流读取。
         *
         * @param responseTimeout 响应超时时长
         * @return 当前建造者
         */
        public Builder responseTimeout(Duration responseTimeout) {
            this.responseTimeout = responseTimeout;
            return this;
        }

        /**
         * 设置流空闲超时。
         *
         * <p>对于 {@link JdkHttpTransport} 的流式请求，此值限制连续两个已发出 SSE/NDJSON 数据块之间的最长等待时间。
         * {@link OkHttpTransport} 不使用此选项，而是依赖 OkHttp 的 {@link #getReadTimeout()} 进行流读取。
         *
         * @param streamIdleTimeout 流空闲超时时长
         * @return 当前建造者
         */
        public Builder streamIdleTimeout(Duration streamIdleTimeout) {
            this.streamIdleTimeout = streamIdleTimeout;
            return this;
        }

        /**
         * 设置读取超时。
         *
         * <p>{@link OkHttpTransport} 将此值用作普通请求和流式请求的 OkHttp 读取超时。
         * {@link JdkHttpTransport} 仅对非流式请求应用此值；JDK 流式请求改用
         * {@link #getResponseTimeout()} 和 {@link #getStreamIdleTimeout()}。
         *
         * @param readTimeout 读取超时时长
         * @return 当前建造者
         */
        public Builder readTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
            return this;
        }

        /**
         * 设置写入超时。
         *
         * @param writeTimeout 写入超时时长
         * @return 当前建造者
         */
        public Builder writeTimeout(Duration writeTimeout) {
            this.writeTimeout = writeTimeout;
            return this;
        }

        /**
         * 设置连接池中的最大空闲连接数。
         *
         * @param maxIdleConnections 最大空闲连接数
         * @return 当前建造者
         */
        public Builder maxIdleConnections(int maxIdleConnections) {
            this.maxIdleConnections = maxIdleConnections;
            return this;
        }

        /**
         * 设置空闲连接的保活时长。
         *
         * @param keepAliveDuration 保活时长
         * @return 当前建造者
         */
        public Builder keepAliveDuration(Duration keepAliveDuration) {
            this.keepAliveDuration = keepAliveDuration;
            return this;
        }

        /**
         * 设置是否忽略 SSL 证书验证。
         *
         * <p><b>警告：</b>设为 true 会禁用 SSL 证书验证，使连接面临中间人攻击风险。
         * 仅应在测试或使用可信自签名证书的场景中考虑此选项。
         *
         * @param ignoreSsl true 表示忽略 SSL 证书验证，否则为 false
         * @return 当前建造者
         */
        public Builder ignoreSsl(boolean ignoreSsl) {
            this.ignoreSsl = ignoreSsl;
            return this;
        }

        /**
         * 设置代理配置。
         *
         * <p>支持 HTTP 和 SOCKS 代理，详情见 {@link ProxyConfig}。
         *
         * @param proxyConfig 代理配置
         * @return 当前建造者
         */
        public Builder proxy(ProxyConfig proxyConfig) {
            this.proxyConfig = proxyConfig;
            return this;
        }

        /**
         * 设置 HTTP 版本；null 表示自动选择（默认）：明文请求使用 HTTP/1.1，
         * https 请求通过 ALPN 使用 HTTP/2。对明文 URL 显式设置 {@link
         * HttpVersion#HTTP_2} 会启用 h2c 升级，某些服务端（例如 vLLM/uvicorn）无法处理。
         * 所有非 https 协议都视为明文。仅 {@link JdkHttpTransport} 使用此选项。
         *
         * @param httpVersion HTTP 版本；null 表示自动选择
         * @return 当前建造者
         */
        public Builder httpVersion(HttpVersion httpVersion) {
            this.httpVersion = httpVersion;
            return this;
        }

        /**
         * 构建 HttpTransportConfig。
         *
         * @return 新的 HttpTransportConfig 实例
         */
        public HttpTransportConfig build() {
            return new HttpTransportConfig(this);
        }
    }
}
