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
package io.agentscope.core.model.transport;

import io.agentscope.core.model.transport.websocket.WebSocketConnection;
import io.agentscope.core.model.transport.websocket.WebSocketRequest;
import reactor.core.publisher.Mono;

/**
 * WebSocket 客户端接口。
 *
 * <p>设计与 HTTPClient 类似：
 * <ul>
 *   <li>客户端实例无状态，可重复使用
 *   <li>每次 connect() 创建新连接
 *   <li>连接配置通过 WebSocketRequest 传入
 *   <li>使用泛型参数支持不同消息格式（String 或 byte[]）
 * </ul>
 *
 * <p>实现：
 * <ul>
 *   <li>{@code JdkWebSocketTransport} — 基于 JDK 11+ HttpClient
 *   <li>{@code OkHttpWebSocketTransport} — 基于 OkHttp
 * </ul>
 *
 * <p>文本协议用法示例：
 *
 * <pre>{@code
 * // Create client (reusable)
 * WebSocketTransport client = JdkWebSocketTransport.create();
 *
 * // Create connection request
 * WebSocketRequest request = WebSocketRequest.builder("wss://api.openai.com/v1/realtime")
 *     .header("Authorization", "Bearer " + apiKey)
 *     .build();
 *
 * // Establish connection (specify String type)
 * client.connect(request, String.class)
 *     .flatMapMany(connection -> {
 *         // Send JSON configuration
 *         connection.send(sessionConfig).subscribe();
 *         // Receive JSON messages
 *         return connection.receive();
 *     })
 *     .subscribe(json -> handleMessage(json));
 * }</pre>
 *
 * <p>二进制协议用法示例：
 *
 * <pre>{@code
 * // Establish connection (specify byte[] type)
 * client.connect(request, byte[].class)
 *     .flatMapMany(connection -> {
 *         // Send binary data
 *         connection.send(binaryFrame).subscribe();
 *         // Receive binary data
 *         return connection.receive();
 *     })
 *     .subscribe(data -> handleBinaryMessage(data));
 * }</pre>
 */
public interface WebSocketTransport {

    /**
     * 建立 WebSocket 连接。
     *
     * @param <T> 消息类型：文本协议为 String，二进制协议为 byte[]
     * @param request 连接请求配置
     * @param messageType 消息类型的 Class 对象（String.class 或 byte[].class）
     * @return 连接成功时发出 WebSocketConnection 的 Mono
     */
    <T> Mono<WebSocketConnection<T>> connect(WebSocketRequest request, Class<T> messageType);

    /**
     * 关闭客户端并释放资源。
     *
     * <p>关闭后，不应再使用此客户端。
     */
    void shutdown();
}
