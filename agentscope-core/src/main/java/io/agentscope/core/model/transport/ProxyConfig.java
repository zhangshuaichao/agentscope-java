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

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * HTTP 与 SOCKS 代理配置。
 *
 * <p>统一配置 HTTP 和 SOCKS 代理，支持可选认证和绕过代理规则。
 *
 * <p>用法示例：
 *
 * <pre>{@code
 * // Simple HTTP proxy
 * ProxyConfig proxy = ProxyConfig.http("proxy.example.com", 8080);
 *
 * // HTTP proxy with authentication
 * ProxyConfig proxy = ProxyConfig.http("proxy.example.com", 8080, "user", "pass");
 *
 * // SOCKS5 proxy with authentication
 * ProxyConfig proxy = ProxyConfig.socks5("socks.example.com", 1080, "user", "pass");
 *
 * // Using builder for advanced configuration
 * ProxyConfig proxy = ProxyConfig.builder()
 *     .type(ProxyType.HTTP)
 *     .host("proxy.example.com")
 *     .port(8080)
 *     .username("user")
 *     .password("pass")
 *     .nonProxyHosts(Set.of("localhost", "*.internal.com"))
 *     .build();
 * }</pre>
 */
public class ProxyConfig {

    private final ProxyType type;
    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final Set<String> nonProxyHosts;

    private ProxyConfig(Builder builder) {
        this.type = builder.type;
        this.host = builder.host;
        this.port = builder.port;
        this.username = builder.username;
        this.password = builder.password;
        this.nonProxyHosts =
                builder.nonProxyHosts != null
                        ? Collections.unmodifiableSet(new HashSet<>(builder.nonProxyHosts))
                        : null;
    }

    /**
     * 创建不含认证的简单 HTTP 代理配置。
     *
     * @param host 代理服务器主机名或 IP 地址
     * @param port 代理服务器端口
     * @return 新的 ProxyConfig 实例
     */
    public static ProxyConfig http(String host, int port) {
        return builder().type(ProxyType.HTTP).host(host).port(port).build();
    }

    /**
     * 创建带认证的 HTTP 代理配置。
     *
     * @param host 代理服务器主机名或 IP 地址
     * @param port 代理服务器端口
     * @param username 认证用户名
     * @param password 认证密码
     * @return 新的 ProxyConfig 实例
     */
    public static ProxyConfig http(String host, int port, String username, String password) {
        return builder()
                .type(ProxyType.HTTP)
                .host(host)
                .port(port)
                .username(username)
                .password(password)
                .build();
    }

    /**
     * 创建简单的 SOCKS4 代理配置。
     *
     * <p>注意：SOCKS4 不支持认证。
     *
     * @param host 代理服务器主机名或 IP 地址
     * @param port 代理服务器端口
     * @return 新的 ProxyConfig 实例
     */
    public static ProxyConfig socks4(String host, int port) {
        return builder().type(ProxyType.SOCKS4).host(host).port(port).build();
    }

    /**
     * 创建不含认证的简单 SOCKS5 代理配置。
     *
     * @param host 代理服务器主机名或 IP 地址
     * @param port 代理服务器端口
     * @return 新的 ProxyConfig 实例
     */
    public static ProxyConfig socks5(String host, int port) {
        return builder().type(ProxyType.SOCKS5).host(host).port(port).build();
    }

    /**
     * 创建带认证的 SOCKS5 代理配置。
     *
     * <p><b>注意：</b>仅基于 OkHttp 的传输实现支持 SOCKS5 认证。
     * JDK HttpClient 不支持 SOCKS5 认证。
     *
     * @param host 代理服务器主机名或 IP 地址
     * @param port 代理服务器端口
     * @param username 认证用户名
     * @param password 认证密码
     * @return 新的 ProxyConfig 实例
     */
    public static ProxyConfig socks5(String host, int port, String username, String password) {
        return builder()
                .type(ProxyType.SOCKS5)
                .host(host)
                .port(port)
                .username(username)
                .password(password)
                .build();
    }

    /**
     * 创建新的 ProxyConfig 建造者。
     *
     * @return 新的 Builder 实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 获取代理类型。
     *
     * @return 代理类型
     */
    public ProxyType getType() {
        return type;
    }

    /**
     * 获取代理服务器主机名或 IP 地址。
     *
     * @return 代理主机
     */
    public String getHost() {
        return host;
    }

    /**
     * 获取代理服务器端口。
     *
     * @return 代理端口
     */
    public int getPort() {
        return port;
    }

    /**
     * 获取认证用户名。
     *
     * @return 用户名；未配置认证时为 null
     */
    public String getUsername() {
        return username;
    }

    /**
     * 获取认证密码。
     *
     * @return 密码；未配置认证时为 null
     */
    public String getPassword() {
        return password;
    }

    /**
     * 获取应绕过代理的主机集合。
     *
     * <p>集合可以包含精确主机名（例如 "localhost"）或通配模式
     * （例如 "*.internal.com"、"192.168.*"）。
     *
     * @return 不使用代理的主机集合；未配置时为 null
     */
    public Set<String> getNonProxyHosts() {
        return nonProxyHosts;
    }

    /**
     * 检查是否配置了认证凭据。
     *
     * @return 用户名和密码均已设置时返回 true
     */
    public boolean hasAuthentication() {
        return username != null && !username.isEmpty() && password != null && !password.isEmpty();
    }

    /**
     * 检查指定主机名是否应绕过代理。
     *
     * @param hostname 要检查的主机名
     * @return 该主机名应绕过代理时返回 true
     */
    public boolean shouldBypass(String hostname) {
        if (nonProxyHosts == null || nonProxyHosts.isEmpty() || hostname == null) {
            return false;
        }

        for (String pattern : nonProxyHosts) {
            if (matchesPattern(hostname, pattern)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 将配置转换为 {@link java.net.Proxy} 实例。
     *
     * @return Java Proxy 实例
     */
    public Proxy toJavaProxy() {
        Proxy.Type proxyType;
        switch (type) {
            case HTTP:
                proxyType = Proxy.Type.HTTP;
                break;
            case SOCKS4:
            case SOCKS5:
                proxyType = Proxy.Type.SOCKS;
                break;
            default:
                throw new IllegalStateException("Unknown proxy type: " + type);
        }
        return new Proxy(proxyType, new InetSocketAddress(host, port));
    }

    /**
     * 获取此代理的套接字地址。
     *
     * @return 代理套接字地址
     */
    public InetSocketAddress getSocketAddress() {
        return new InetSocketAddress(host, port);
    }

    private boolean matchesPattern(String hostname, String pattern) {
        if (pattern.equals(hostname)) {
            return true;
        }

        if (pattern.startsWith("*.")) {
            String suffix = pattern.substring(1);
            return hostname.endsWith(suffix);
        }

        if (pattern.endsWith(".*")) {
            String prefix = pattern.substring(0, pattern.length() - 1);
            return hostname.startsWith(prefix);
        }

        if (pattern.contains("*")) {
            String regex = pattern.replace(".", "\\.").replace("*", ".*");
            return Pattern.matches(regex, hostname);
        }

        return false;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("ProxyConfig{type=").append(type);
        sb.append(", host='").append(host).append('\'');
        sb.append(", port=").append(port);
        if (hasAuthentication()) {
            sb.append(", username='").append(username).append('\'');
            sb.append(", password='****'");
        }
        if (nonProxyHosts != null) {
            sb.append(", nonProxyHosts=").append(nonProxyHosts);
        }
        sb.append('}');
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ProxyConfig that = (ProxyConfig) o;
        return port == that.port
                && type == that.type
                && Objects.equals(host, that.host)
                && Objects.equals(username, that.username)
                && Objects.equals(password, that.password)
                && Objects.equals(nonProxyHosts, that.nonProxyHosts);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, host, port, username, password, nonProxyHosts);
    }

    /** ProxyConfig 的建造者。 */
    public static class Builder {
        private ProxyType type;
        private String host;
        private int port;
        private String username;
        private String password;
        private Set<String> nonProxyHosts;

        private Builder() {}

        /**
         * 设置代理类型。
         *
         * @param type 代理类型
         * @return 当前建造者
         */
        public Builder type(ProxyType type) {
            this.type = type;
            return this;
        }

        /**
         * 设置代理服务器主机名或 IP 地址。
         *
         * @param host 代理主机
         * @return 当前建造者
         */
        public Builder host(String host) {
            this.host = host;
            return this;
        }

        /**
         * 设置代理服务器端口。
         *
         * @param port 代理端口
         * @return 当前建造者
         */
        public Builder port(int port) {
            this.port = port;
            return this;
        }

        /**
         * 设置认证用户名。
         *
         * @param username 用户名
         * @return 当前建造者
         */
        public Builder username(String username) {
            this.username = username;
            return this;
        }

        /**
         * 设置认证密码。
         *
         * @param password 密码
         * @return 当前建造者
         */
        public Builder password(String password) {
            this.password = password;
            return this;
        }

        /**
         * 设置应绕过代理的主机。
         *
         * <p>支持精确主机名和通配模式：
         * <ul>
         *   <li>{@code localhost} — 精确匹配
         *   <li>{@code *.internal.com} — 后缀匹配
         *   <li>{@code 192.168.*} — 前缀匹配
         * </ul>
         *
         * @param nonProxyHosts 不使用代理的主机集合
         * @return 当前建造者
         */
        public Builder nonProxyHosts(Set<String> nonProxyHosts) {
            this.nonProxyHosts = nonProxyHosts;
            return this;
        }

        /**
         * 构建 ProxyConfig。
         *
         * @return 新的 ProxyConfig 实例
         * @throws NullPointerException type 或 host 为 null 时抛出
         * @throws IllegalArgumentException port 超出范围时抛出
         */
        public ProxyConfig build() {
            Objects.requireNonNull(type, "type is required");
            Objects.requireNonNull(host, "host is required");
            if (port <= 0 || port > 65535) {
                throw new IllegalArgumentException("port must be between 1 and 65535");
            }
            return new ProxyConfig(this);
        }
    }
}
