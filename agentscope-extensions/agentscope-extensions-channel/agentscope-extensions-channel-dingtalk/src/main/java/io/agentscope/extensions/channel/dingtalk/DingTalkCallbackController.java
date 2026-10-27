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
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 处理以 HTTP 回调模式运行的钉钉渠道的入站机器人消息投递（POST）。
 *
 * <p>各 {@link DingTalkChannel} 在启动时注册；控制器按 URL 路径中的 {@code channelId}
 * 分发请求，避免 Spring 动态映射注册，并使装配易于测试。
 */
@RestController
@RequestMapping("/api/channels/dingtalk")
public class DingTalkCallbackController {

    private static final Logger log = LoggerFactory.getLogger(DingTalkCallbackController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 允许解析的回调体大小上限；普通机器人消息通常仅数 KB。
     */
    private static final int MAX_BODY_LENGTH = 64 * 1024;

    /**
     * 写入日志的异常消息片段上限；Jackson 消息可能嵌入被解析的原文。
     */
    private static final int MAX_SNIPPET_LENGTH = 200;

    private final DingTalkChannelRegistry registry;

    public DingTalkCallbackController() {
        this(DingTalkChannelRegistry.instance());
    }

    /**
     * 对测试可见，允许注入新的注册表。
     */
    DingTalkCallbackController(DingTalkChannelRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /**
     * 入站机器人消息。钉钉通过 POST 向此端点发送机器人消息 JSON，并带上
     * {@code timestamp}/{@code sign} 请求头；配置 {@code aesKey} 时，请求体改为
     * {@code {"encrypt": ...}} 信封。消息经验证、按需解密后交给渠道共用接收流程
     * （msgId 去重、映射、机器人循环防护、分发）。除签名无效、请求体不可用或渠道未知等错误外，
     * 返回 {@code 200}，避免平台重试。
     */
    @PostMapping(
            value = "/{channelId}/callback",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_PLAIN_VALUE)
    public Mono<ResponseEntity<String>> dispatch(
            @PathVariable String channelId,
            @RequestHeader(value = "timestamp", required = false) String timestamp,
            @RequestHeader(value = "sign", required = false) String sign,
            @RequestBody String body) {
        DingTalkChannel channel = registry.get(channelId);
        if (channel == null) {
            // 未认证探测不应区分未知渠道 ID 和错误签名，
            // 因此两者都返回 401；详情不进入 warn 日志流。
            log.debug("DingTalk dispatch: no channel registered for id='{}'", channelId);
            return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
        }
        if (isBlank(timestamp) || isBlank(sign) || !channel.crypto().verify(timestamp, sign)) {
            log.warn(
                    "DingTalk dispatch: signature verification failed (channelId='{}')", channelId);
            return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
        }
        JsonNode payload;
        try {
            payload = extractPayload(channel, body);
        } catch (RuntimeException e) {
            log.warn(
                    "DingTalk dispatch: failed to extract payload (channelId='{}'): {}",
                    channelId,
                    truncate(e.getMessage()));
            return Mono.just(ResponseEntity.badRequest().body(""));
        }
        // 接收流程内部确认正常忽略情况（重复、不支持的载荷、循环
        // 防护）；实际回复通过出站 API 投递，不在此响应中返回。
        channel.onInboundPayload(payload);
        return Mono.just(ResponseEntity.ok(""));
    }

    private static JsonNode extractPayload(DingTalkChannel channel, String body) {
        if (body == null || body.isBlank()) {
            throw new IllegalStateException("Request body is empty");
        }
        if (body.length() > MAX_BODY_LENGTH) {
            throw new IllegalStateException(
                    "Request body exceeds " + MAX_BODY_LENGTH + " characters");
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("Request body is not valid JSON: " + e.getMessage(), e);
        }
        JsonNode encrypt = root.get("encrypt");
        boolean hasEnvelope = encrypt != null && encrypt.isTextual() && !encrypt.asText().isBlank();
        // 失败时拒绝：配置 aesKey 的渠道只接受钉钉加密信封，
        // 明文请求体表示配置不匹配或伪造请求，而不是
        // 可分发消息。反向不匹配（无 aesKey 却有信封）在 decrypt() 内失败。
        if (channel.crypto().hasAesKey() && !hasEnvelope) {
            throw new IllegalStateException(
                    "Channel expects an encrypted callback body but none was present");
        }
        if (!hasEnvelope) {
            return root;
        }
        String plain = channel.crypto().decrypt(encrypt.asText());
        try {
            return MAPPER.readTree(plain);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Decrypted payload is not valid JSON: " + e.getMessage(), e);
        }
    }

    private static String truncate(String message) {
        if (message == null) {
            return "";
        }
        return message.length() <= MAX_SNIPPET_LENGTH
                ? message
                : message.substring(0, MAX_SNIPPET_LENGTH) + "...(" + message.length() + " chars)";
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
