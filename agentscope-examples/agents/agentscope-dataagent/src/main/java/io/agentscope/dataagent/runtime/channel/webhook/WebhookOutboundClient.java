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
package io.agentscope.dataagent.runtime.channel.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 为 {@code replyMode=callback} 的 Webhook 通道向调用方提供的 {@code callbackUrl} 发送智能体回复。
 * <p>每个请求体为 JSON 对象：
 * <pre>{"channelId":"...","inboundId":"...","reply":"..."}</pre>
 * 使用与入站相同的共享密钥签名；{@code X-DataAgent-Sig} 携带对原始请求体计算的 HMAC-SHA256 小写十六进制摘要。
 */
public final class WebhookOutboundClient {

    private static final Logger log = LoggerFactory.getLogger(WebhookOutboundClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String channelId;
    private final String sharedSecret;
    private final HttpClient http;

    public WebhookOutboundClient(String channelId, String sharedSecret) {
        this.channelId = channelId;
        this.sharedSecret = sharedSecret;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    /** 将给定回复文本发送到回调 URL。成功时返回空 Mono；失败时记录并吞掉错误，避免下游回调故障使分派流水线失败。 */
    public Mono<Void> deliver(String callbackUrl, String inboundId, String replyText) {
        if (callbackUrl == null || callbackUrl.isBlank()) {
            return Mono.empty();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("channelId", channelId);
        body.put("inboundId", inboundId);
        body.put("reply", replyText == null ? "" : replyText);

        byte[] payload;
        try {
            payload = MAPPER.writeValueAsBytes(body);
        } catch (Exception e) {
            return Mono.error(new IllegalStateException("Failed to serialise callback body", e));
        }

        HttpRequest.Builder rb =
                HttpRequest.newBuilder(URI.create(callbackUrl))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(payload));
        if (sharedSecret != null && !sharedSecret.isBlank()) {
            rb.header("X-DataAgent-Sig", WebhookSignature.hmacHex(sharedSecret, payload));
        }
        HttpRequest req = rb.build();

        return Mono.fromCallable(() -> http.send(req, HttpResponse.BodyHandlers.discarding()))
                .subscribeOn(Schedulers.boundedElastic())
                .doOnNext(
                        resp -> {
                            if (resp.statusCode() >= 300) {
                                log.warn(
                                        "Webhook channel '{}' callback to {} returned {}",
                                        channelId,
                                        callbackUrl,
                                        resp.statusCode());
                            }
                        })
                .doOnError(
                        err ->
                                log.warn(
                                        "Webhook channel '{}' callback to {} failed: {}",
                                        channelId,
                                        callbackUrl,
                                        err.getMessage()))
                .onErrorResume(e -> Mono.empty())
                .then();
    }

    /** 从智能体回复列表提取用户可见文本，以换行符连接。 */
    public static String renderReplyText(List<Msg> messages) {
        if (messages == null || messages.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Msg m : messages) {
            String t = m.getTextContent();
            if (t == null || t.isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(t);
        }
        return sb.toString();
    }
}
