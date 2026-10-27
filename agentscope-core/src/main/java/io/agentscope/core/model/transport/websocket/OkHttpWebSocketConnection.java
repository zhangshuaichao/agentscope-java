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

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.WebSocket;
import okio.ByteString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * 基于 OkHttp WebSocket 的连接实现。
 *
 * <p>通过泛型参数同时支持文本（String）和二进制（byte[]）消息。
 *
 * @param <T> 消息类型：文本协议为 String，二进制协议为 byte[]
 */
public class OkHttpWebSocketConnection<T> implements WebSocketConnection<T> {

    private static final Logger log = LoggerFactory.getLogger(OkHttpWebSocketConnection.class);

    private final String url;
    private final Class<T> messageType;
    private volatile WebSocket webSocket;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private volatile CloseInfo closeInfo;

    // 消息接收汇（单播，仅允许一个订阅者）
    private final Sinks.Many<T> messageSink = Sinks.many().unicast().onBackpressureBuffer();

    /**
     * 创建新的 OkHttpWebSocketConnection。
     *
     * @param url WebSocket URL
     * @param messageType 消息类型类（String.class 或 byte[].class）
     */
    OkHttpWebSocketConnection(String url, Class<T> messageType) {
        this.url = url;
        this.messageType = messageType;
    }

    /**
     * 设置 WebSocket 实例（内部使用）。
     *
     * @param webSocket WebSocket 实例
     */
    void setWebSocket(WebSocket webSocket) {
        this.webSocket = webSocket;
        this.initialized.set(true);
    }

    /**
     * 检查连接是否已初始化（内部使用）。
     *
     * @return 已初始化时返回 true
     */
    boolean isInitialized() {
        return initialized.get();
    }

    /**
     * 处理收到的消息（内部使用）。
     *
     * @param data 字节数组形式的消息数据
     */
    @SuppressWarnings("unchecked")
    void onMessage(byte[] data) {
        if (messageType == byte[].class) {
            messageSink.tryEmitNext((T) data);
        } else if (messageType == String.class) {
            messageSink.tryEmitNext((T) new String(data, StandardCharsets.UTF_8));
        }
    }

    /**
     * 处理连接关闭（内部使用）。
     *
     * @param code 关闭码
     * @param reason 关闭原因
     */
    void onClosed(int code, String reason) {
        closed.set(true);
        closeInfo = new CloseInfo(code, reason);
        messageSink.tryEmitComplete();
    }

    /**
     * 处理错误（内部使用）。
     *
     * @param error 发生的错误
     */
    void onError(Throwable error) {
        closed.set(true);
        closeInfo = new CloseInfo(CloseInfo.ABNORMAL_CLOSURE, error.getMessage());
        messageSink.tryEmitError(
                new WebSocketTransportException("WebSocket error", error, url, "ERROR"));
    }

    @Override
    public Mono<Void> send(T data) {
        return Mono.<Void>create(
                        sink -> {
                            if (webSocket == null || closed.get()) {
                                sink.error(
                                        new WebSocketTransportException(
                                                "Connection is not open",
                                                null,
                                                url,
                                                closed.get() ? "CLOSED" : "NOT_CONNECTED"));
                                return;
                            }

                            boolean success;
                            if (data instanceof String text) {
                                log.debug("Sending text message, size: {} chars", text.length());
                                success = webSocket.send(text);
                            } else if (data instanceof byte[] bytes) {
                                log.debug("Sending binary message, size: {} bytes", bytes.length);
                                success = webSocket.send(ByteString.of(bytes));
                            } else {
                                sink.error(
                                        new IllegalArgumentException(
                                                "Unsupported message type: "
                                                        + data.getClass().getName()));
                                return;
                            }

                            if (!success) {
                                log.error("Failed to send message: {}", url);
                                sink.error(
                                        new WebSocketTransportException(
                                                "Failed to send message", null, url, "OPEN"));
                            } else {
                                sink.success();
                            }
                        })
                .onErrorMap(
                        e ->
                                e instanceof WebSocketTransportException
                                        ? e
                                        : new WebSocketTransportException(
                                                "Send failed", e, url, "OPEN"));
    }

    @Override
    public Flux<T> receive() {
        return messageSink
                .asFlux()
                .onErrorMap(
                        e ->
                                e instanceof WebSocketTransportException
                                        ? e
                                        : new WebSocketTransportException(
                                                "Receive failed", e, url, "OPEN"));
    }

    @Override
    public Mono<Void> close() {
        return Mono.create(
                sink -> {
                    if (webSocket != null && !closed.getAndSet(true)) {
                        log.info("Closing WebSocket connection: {}", url);
                        webSocket.close(CloseInfo.NORMAL_CLOSURE, "");
                        closeInfo = new CloseInfo(CloseInfo.NORMAL_CLOSURE, "");
                    }
                    sink.success();
                });
    }

    @Override
    public boolean isOpen() {
        return webSocket != null && !closed.get();
    }

    @Override
    public CloseInfo getCloseInfo() {
        return closeInfo;
    }
}
