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

import java.util.Collections;
import java.util.Map;

/**
 * WebSocket 传输层异常。
 *
 * <p>包装底层传输错误并附带连接上下文，帮助诊断问题。
 * 保留完整异常链，同时添加传输专用上下文。
 *
 * <p>上下文信息包括：
 * <ul>
 *   <li>WebSocket URL
 *   <li>连接状态（OPEN/CLOSED/CONNECTING）
 *   <li>请求头（用于认证／配置调试）
 * </ul>
 *
 * <p>【学习提示】异常携带的请求头可能包含凭据；调试时仍应控制日志输出和脱敏，
 * 不能因为信息来自异常对象就假定可以公开。
 */
public class WebSocketTransportException extends RuntimeException {

    private final String url;
    private final String connectionState;
    private final Map<String, String> headers;

    /**
     * 创建 WebSocketTransportException。
     *
     * @param message 错误消息
     * @param cause 根因异常
     * @param url WebSocket URL
     * @param connectionState 连接状态（OPEN/CLOSED/CONNECTING）
     */
    public WebSocketTransportException(
            String message, Throwable cause, String url, String connectionState) {
        this(message, cause, url, connectionState, Collections.emptyMap());
    }

    /**
     * 创建包含请求头的 WebSocketTransportException。
     *
     * @param message 错误消息
     * @param cause 根因异常
     * @param url WebSocket URL
     * @param connectionState 连接状态
     * @param headers 请求头（用于调试）
     */
    public WebSocketTransportException(
            String message,
            Throwable cause,
            String url,
            String connectionState,
            Map<String, String> headers) {
        super(message, cause);
        this.url = url;
        this.connectionState = connectionState;
        this.headers = headers != null ? Map.copyOf(headers) : Collections.emptyMap();
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
     * 获取连接状态。
     *
     * @return 连接状态
     */
    public String getConnectionState() {
        return connectionState;
    }

    /**
     * 获取请求头。
     *
     * @return 不可变的请求头
     */
    public Map<String, String> getHeaders() {
        return headers;
    }

    @Override
    public String getMessage() {
        return String.format("%s [url=%s, state=%s]", super.getMessage(), url, connectionState);
    }
}
