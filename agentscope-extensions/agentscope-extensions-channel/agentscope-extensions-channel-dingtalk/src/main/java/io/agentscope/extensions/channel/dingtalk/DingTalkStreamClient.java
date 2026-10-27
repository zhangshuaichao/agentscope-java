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
package io.agentscope.extensions.channel.dingtalk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 钉钉 Stream 客户端：连接钉钉网关的长期 WebSocket，订阅机器人消息主题，
 * 将入站帧分发给消费方。失败时使用指数退避重连。
 *
 * <h2>Stream 协议概要</h2>
 *
 * <ol>
 *   <li>向 {@code POST {streamRegisterUrl}} 提交 {@code appKey}、{@code appSecret} 和主题订阅列表；
 *       响应包含 {@code endpoint}（wss URL）和 {@code ticket}。
 *   <li>连接 WebSocket 到 {@code endpoint?ticket=ticket}。
 *   <li>每帧是 JSON 信封；{@code type=CALLBACK} 携带字符串化的 {@code data} JSON。
 *       必须确认每次回调，使钉钉从重试缓冲区移除消息。
 * </ol>
 *
 * <p>此客户端有意保持小巧：不依赖外部 SDK，仅做所需的模式验证。
 * 心跳／ping 交给 JDK WebSocket 实现。
 */
public final class DingTalkStreamClient {

    private static final Logger log = LoggerFactory.getLogger(DingTalkStreamClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DingTalkChannelProperties properties;
    private final Consumer<JsonNode> messageConsumer;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        Thread t = new Thread(r, "dingtalk-stream");
                        t.setDaemon(true);
                        return t;
                    });
    private final HttpClient httpClient =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<WebSocket> currentSocket = new AtomicReference<>();
    private final AtomicReference<ScheduledFuture<?>> reconnectTask = new AtomicReference<>();
    private final StringBuilder partialFrame = new StringBuilder();

    private long backoffMs = 1_000L;

    public DingTalkStreamClient(
            DingTalkChannelProperties properties, Consumer<JsonNode> messageConsumer) {
        this.properties = properties;
        this.messageConsumer = messageConsumer;
    }

    /**
     * 启动 Stream 连接，按设计调用一次。
     */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        scheduler.submit(this::connect);
    }

    /**
     * 停止 Stream 连接并释放资源。
     */
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        ScheduledFuture<?> rt = reconnectTask.getAndSet(null);
        if (rt != null) {
            rt.cancel(false);
        }
        WebSocket s = currentSocket.getAndSet(null);
        if (s != null) {
            try {
                s.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown");
            } catch (Exception ignored) {
                // 尽力而为
            }
            s.abort();
        }
        scheduler.shutdownNow();
    }

    private void connect() {
        if (!running.get()) {
            return;
        }
        Endpoint endpoint;
        try {
            endpoint = openConnection();
        } catch (Exception e) {
            log.warn(
                    "DingTalk Stream open failed: {} (reconnect in {}ms)",
                    e.getMessage(),
                    backoffMs);
            scheduleReconnect();
            return;
        }

        URI wsUri =
                URI.create(
                        endpoint.url
                                + (endpoint.url.contains("?") ? "&" : "?")
                                + "ticket="
                                + endpoint.ticket);
        try {
            httpClient
                    .newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .buildAsync(wsUri, new Listener())
                    .whenComplete(
                            (ws, err) -> {
                                if (err != null) {
                                    log.warn(
                                            "DingTalk Stream WebSocket connect failed: {}"
                                                    + " (reconnect in {}ms)",
                                            err.getMessage(),
                                            backoffMs);
                                    scheduleReconnect();
                                    return;
                                }
                                currentSocket.set(ws);
                                backoffMs = 1_000L;
                                log.info("DingTalk Stream connected: {}", wsUri.getHost());
                            });
        } catch (Exception e) {
            log.warn(
                    "DingTalk Stream WebSocket build failed: {} (reconnect in {}ms)",
                    e.getMessage(),
                    backoffMs);
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (!running.get()) {
            return;
        }
        long delay = backoffMs;
        backoffMs = Math.min(backoffMs * 2L, 60_000L);
        reconnectTask.set(scheduler.schedule(this::connect, delay, TimeUnit.MILLISECONDS));
    }

    private Endpoint openConnection() throws Exception {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("clientId", properties.appKey());
        body.put("clientSecret", properties.appSecret());
        body.put("ua", "agentscope-builder/1.0");
        body.set(
                "subscriptions",
                MAPPER.valueToTree(
                        List.of(Map.of("type", "CALLBACK", "topic", "/v1.0/im/bot/messages/get"))));

        HttpRequest req =
                HttpRequest.newBuilder(URI.create(properties.streamRegisterUrl()))
                        .timeout(Duration.ofSeconds(15))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                        .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException(
                    "DingTalk gateway open returned HTTP "
                            + resp.statusCode()
                            + ": "
                            + resp.body());
        }
        JsonNode node = MAPPER.readTree(resp.body());
        String url = node.path("endpoint").asText(null);
        String ticket = node.path("ticket").asText(null);
        if (url == null || url.isBlank() || ticket == null || ticket.isBlank()) {
            throw new IllegalStateException(
                    "DingTalk gateway open missing endpoint/ticket: " + resp.body());
        }
        return new Endpoint(url, ticket);
    }

    private void handleFrame(String frameText) {
        JsonNode envelope;
        try {
            envelope = MAPPER.readTree(frameText);
        } catch (Exception e) {
            log.warn("DingTalk Stream: malformed frame: {}", e.getMessage());
            return;
        }
        String type = envelope.path("type").asText(null);
        if (!"CALLBACK".equalsIgnoreCase(type)) {
            // 最小实现中忽略 SYSTEM／EVENT 帧。
            return;
        }

        // 先发送 ACK 再分发，使网关释放消息；用户可见回复
        // 随后由 DingTalkOutboundClient 单独发送。
        // 【学习提示】ACK 调用发生在内层 data 解析及消费之前，且发送 future 未在此等待。
        // 这不是业务完成回执；后续解析或执行失败不能假定平台仍会重投。
        ackFrame(envelope);

        String dataStr = envelope.path("data").asText(null);
        if (dataStr == null) {
            return;
        }
        try {
            JsonNode payload = MAPPER.readTree(dataStr);
            messageConsumer.accept(payload);
        } catch (Exception e) {
            log.warn("DingTalk Stream: failed to parse payload: {}", e.getMessage());
        }
    }

    private void ackFrame(JsonNode envelope) {
        WebSocket socket = currentSocket.get();
        if (socket == null) {
            return;
        }
        try {
            ObjectNode ack = MAPPER.createObjectNode();
            ack.put("code", 200);
            ObjectNode headers = ack.putObject("headers");
            String messageId = envelope.path("headers").path("messageId").asText(null);
            if (messageId != null) {
                headers.put("messageId", messageId);
            }
            headers.put("contentType", "application/json");
            ack.put("message", "OK");
            ack.put("data", "{}");
            socket.sendText(MAPPER.writeValueAsString(ack), true);
        } catch (Exception e) {
            log.warn("DingTalk Stream: failed to send ack: {}", e.getMessage());
        }
    }

    private final class Listener implements WebSocket.Listener {

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partialFrame.append(data);
            if (last) {
                String text = partialFrame.toString();
                partialFrame.setLength(0);
                try {
                    handleFrame(text);
                } catch (Exception e) {
                    log.warn("DingTalk Stream: handler error: {}", e.getMessage());
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            log.info("DingTalk Stream: closed code={}, reason={}", statusCode, reason);
            currentSocket.compareAndSet(webSocket, null);
            partialFrame.setLength(0);
            scheduleReconnect();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.warn("DingTalk Stream: error: {}", error.getMessage());
            currentSocket.compareAndSet(webSocket, null);
            partialFrame.setLength(0);
            scheduleReconnect();
        }
    }

    private record Endpoint(String url, String ticket) {}
}
