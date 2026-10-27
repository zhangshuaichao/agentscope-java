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

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * WebSocket 连接接口。
 *
 * <p>表示具有发送／接收能力的活动 WebSocket 连接，通过泛型参数支持不同消息格式：
 * <ul>
 *   <li>{@code WebSocketConnection<String>} — 文本协议（通过 WebSocket 传递 JSON）
 *   <li>{@code WebSocketConnection<byte[]>} — 二进制协议
 * </ul>
 *
 * <p>错误处理：所有方法返回 Mono/Flux，通过 Reactor 错误通道传播错误。
 * 错误包装为带连接上下文的 {@link WebSocketTransportException}。
 *
 * <p>日志：实现应使用合适的日志级别：
 * <ul>
 *   <li>INFO：连接建立／关闭
 *   <li>DEBUG：消息发送／接收操作
 *   <li>TRACE：详细消息内容（大小、预览）
 *   <li>ERROR：连接错误及发送／接收失败
 * </ul>
 *
 * <p>文本协议用法示例：
 *
 * <pre>{@code
 * WebSocketTransport client = JdkWebSocketTransport.create();
 * client.connect(request, String.class)
 *     .flatMapMany(connection -> {
 *         // 发送 JSON 消息
 *         connection.send("{\"type\":\"config\"}").subscribe();
 *
 *         // 接收 JSON 消息
 *         return connection.receive();
 *     })
 *     .subscribe(
 *         json -> handleMessage(json),
 *         error -> handleError(error)  // 包含上下文的 WebSocketTransportException
 *     );
 * }</pre>
 *
 * <p>二进制协议用法示例：
 *
 * <pre>{@code
 * client.connect(request, byte[].class)
 *     .flatMapMany(connection -> {
 *         // 发送二进制消息
 *         connection.send(binaryData).subscribe();
 *
 *         // 接收二进制消息
 *         return connection.receive();
 *     })
 *     .subscribe(
 *         data -> handleBinaryMessage(data),
 *         error -> handleError(error)
 *     );
 * }</pre>
 *
 * @param <T> 消息类型：文本协议为 String，二进制协议为 byte[]
 */
public interface WebSocketConnection<T> {

    /**
     * 发送消息。
     *
     * <p>实现应：
     * <ul>
     *   <li>发送前记录 DEBUG 日志
     *   <li>在 TRACE 日志中记录消息大小
     *   <li>使用 onErrorMap 将错误包装为 WebSocketTransportException
     *   <li>以 ERROR 级别记录含完整上下文的错误
     * </ul>
     *
     * @param data 消息数据（String 或 byte[]）
     * @return 发送结束时完成的 Mono；发生错误时发出 WebSocketTransportException
     */
    Mono<Void> send(T data);

    /**
     * 接收消息流。
     *
     * <p>返回的 Flux：
     * <ul>
     *   <li>连接正常关闭时完成
     *   <li>连接失败时发出错误（WebSocketTransportException）
     *   <li>以 TRACE 级别记录每条收到的消息
     * </ul>
     *
     * <p>实现应：
     * <ul>
     *   <li>以 TRACE 级别记录收到的消息及大小
     *   <li>使用 onErrorMap 将错误包装为 WebSocketTransportException
     *   <li>以 ERROR 级别记录带连接上下文的错误
     * </ul>
     *
     * @return 消息流（String 或 byte[]）
     */
    Flux<T> receive();

    /**
     * 关闭连接。
     *
     * <p>实现应：
     * <ul>
     *   <li>以 INFO 级别记录关闭码和原因
     *   <li>发送关闭码为 1000（正常关闭）的 WebSocket 关闭帧
     *   <li>清理资源
     * </ul>
     *
     * @return 连接关闭时完成的 Mono
     */
    Mono<Void> close();

    /**
     * 检查连接是否打开。
     *
     * @return 连接打开时返回 true
     */
    boolean isOpen();

    /**
     * 获取关闭信息（如果已关闭）。
     *
     * @return 关闭信息；尚未关闭时为 null
     */
    CloseInfo getCloseInfo();
}
