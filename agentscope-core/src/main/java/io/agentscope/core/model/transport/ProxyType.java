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

/**
 * 支持的代理类型枚举。
 *
 * <p>定义 HTTP 和 WebSocket 传输层可配置的代理类型。
 */
public enum ProxyType {

    /**
     * HTTP/HTTPS 代理。
     *
     * <p>支持用户名／密码认证。
     */
    HTTP,

    /**
     * SOCKS 4 代理。
     *
     * <p>不支持认证。
     */
    SOCKS4,

    /**
     * SOCKS 5 代理。
     *
     * <p>仅基于 OkHttp 的传输实现支持用户名／密码认证。
     * JDK HttpClient 不支持 SOCKS5 认证。
     */
    SOCKS5
}
