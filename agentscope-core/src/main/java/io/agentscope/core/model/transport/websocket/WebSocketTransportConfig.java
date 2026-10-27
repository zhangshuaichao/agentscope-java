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

import io.agentscope.core.model.transport.ProxyConfig;
import java.time.Duration;

/**
 * WebSocket 客户端配置。
 *
 * <p>保存 WebSocket 客户端行为选项，例如超时、心跳间隔、代理设置和 SSL 选项。
 *
 * <p>用法示例：
 *
 * <pre>{@code
 * WebSocketTransportConfig config = WebSocketTransportConfig.builder()
 *     .connectTimeout(Duration.ofSeconds(30))
 *     .pingInterval(Duration.ofSeconds(30))
 *     .proxy(ProxyConfig.http("proxy.example.com", 8080))
 *     .build();
 *
 * WebSocketTransport client = OkHttpWebSocketTransport.create(config);
 * }</pre>
 */
public class WebSocketTransportConfig {

    /** 默认连接超时：30 秒。 */
    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(30);

    /** 默认读取超时：0（WebSocket 不设超时）。 */
    public static final Duration DEFAULT_READ_TIMEOUT = Duration.ZERO;

    /** 默认写入超时：30 秒。 */
    public static final Duration DEFAULT_WRITE_TIMEOUT = Duration.ofSeconds(30);

    /** 默认 ping 间隔：30 秒。 */
    public static final Duration DEFAULT_PING_INTERVAL = Duration.ofSeconds(30);

    private final Duration connectTimeout;
    private final Duration readTimeout;
    private final Duration writeTimeout;
    private final Duration pingInterval;
    private final ProxyConfig proxyConfig;
    private final boolean ignoreSsl;

    private WebSocketTransportConfig(Builder builder) {
        this.connectTimeout = builder.connectTimeout;
        this.readTimeout = builder.readTimeout;
        this.writeTimeout = builder.writeTimeout;
        this.pingInterval = builder.pingInterval;
        this.proxyConfig = builder.proxyConfig;
        this.ignoreSsl = builder.ignoreSsl;
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
     * 获取读取超时。
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
     * 获取心跳 ping 间隔（仅 OkHttp）。
     *
     * @return ping 间隔时长
     */
    public Duration getPingInterval() {
        return pingInterval;
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
     * 创建新的 WebSocketTransportConfig 建造者。
     *
     * @return 新的 Builder 实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 创建默认配置。
     *
     * @return 默认 WebSocketTransportConfig 实例
     */
    public static WebSocketTransportConfig defaults() {
        return builder().build();
    }

    /** WebSocketTransportConfig 的建造者。 */
    public static class Builder {
        private Duration connectTimeout = DEFAULT_CONNECT_TIMEOUT;
        private Duration readTimeout = DEFAULT_READ_TIMEOUT;
        private Duration writeTimeout = DEFAULT_WRITE_TIMEOUT;
        private Duration pingInterval = DEFAULT_PING_INTERVAL;
        private ProxyConfig proxyConfig = null;
        private boolean ignoreSsl = false;

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
         * 设置读取超时。
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
         * 设置心跳 ping 间隔（仅 OkHttp）。
         *
         * @param pingInterval ping 间隔时长
         * @return 当前建造者
         */
        public Builder pingInterval(Duration pingInterval) {
            this.pingInterval = pingInterval;
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
         * 构建 WebSocketTransportConfig。
         *
         * @return 新的 WebSocketTransportConfig 实例
         */
        public WebSocketTransportConfig build() {
            return new WebSocketTransportConfig(this);
        }
    }
}
