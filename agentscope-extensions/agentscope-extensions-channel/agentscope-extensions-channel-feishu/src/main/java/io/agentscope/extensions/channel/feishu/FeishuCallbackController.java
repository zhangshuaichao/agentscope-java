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
package io.agentscope.extensions.channel.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.harness.agent.gateway.channel.InboundMessage;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
 * 处理飞书 URL 验证握手和事件订阅回调。
 *
 * <p>单一端点为 {@code POST /api/channels/feishu/{channelId}/callback}。
 * 请求体为 JSON，可以是明文信封或 {@code {"encrypt":"<base64>"}}。控制器会：
 *
 * <ol>
 *   <li>配置 {@code encryptKey} 时解密信封
 *   <li>存在 {@code X-Lark-Signature} 请求头时验证签名
 *   <li>URL 验证握手时回显 {@code challenge}
 *   <li>按 {@code header.event_id} 去重
 *   <li>应用机器人循环防护并分发到渠道
 * </ol>
 */
@RestController
@RequestMapping("/api/channels/feishu")
public class FeishuCallbackController {

    private static final Logger log = LoggerFactory.getLogger(FeishuCallbackController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final FeishuChannelRegistry registry;

    public FeishuCallbackController() {
        this(FeishuChannelRegistry.instance());
    }

    /**
     * 对测试可见，允许注入新的注册表。
     */
    FeishuCallbackController(FeishuChannelRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @PostMapping(
            value = "/{channelId}/callback",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<String>> callback(
            @PathVariable String channelId,
            @RequestHeader(value = "X-Lark-Signature", required = false) String signature,
            @RequestHeader(value = "X-Lark-Request-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Lark-Request-Nonce", required = false) String nonce,
            @RequestBody String rawBody) {
        FeishuChannel channel = registry.get(channelId);
        if (channel == null) {
            log.warn("Feishu callback: no channel registered for id='{}'", channelId);
            return Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND).build());
        }

        // 1. 对原始请求体进行可选签名验证。渠道启用加密且提供
        // 签名时，请求头签名必须匹配。
        if (channel.properties().isEncrypted()
                && signature != null
                && !channel.crypto().verifySignature(signature, timestamp, nonce, rawBody)) {
            log.warn("Feishu callback: signature mismatch (channelId='{}')", channelId);
            return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
        }

        // 2. 解析外层请求体；如果已加密，则解密并重新解析。
        JsonNode envelope;
        try {
            envelope = MAPPER.readTree(rawBody);
            if (envelope.has("encrypt") && channel.crypto() != null) {
                String plaintext = channel.crypto().decrypt(envelope.get("encrypt").asText());
                envelope = MAPPER.readTree(plaintext);
            }
        } catch (RuntimeException e) {
            log.warn(
                    "Feishu callback: decrypt/parse failed (channelId='{}'): {}",
                    channelId,
                    e.getMessage());
            return Mono.just(ResponseEntity.badRequest().body(""));
        } catch (Exception e) {
            log.warn(
                    "Feishu callback: parse failed (channelId='{}'): {}",
                    channelId,
                    e.getMessage());
            return Mono.just(ResponseEntity.badRequest().body(""));
        }

        // 3. URL 验证握手：回显 {"challenge":"…"}。
        Optional<String> challenge = FeishuInboundMapper.extractUrlChallenge(envelope);
        if (challenge.isPresent()) {
            String tokenFromBody = envelope.path("token").asText(null);
            String configured = channel.properties().verificationToken();
            if (configured != null && !configured.isBlank() && !configured.equals(tokenFromBody)) {
                log.warn(
                        "Feishu callback: verification token mismatch (channelId='{}')", channelId);
                return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
            }
            try {
                String body = MAPPER.writeValueAsString(Map.of("challenge", challenge.get()));
                return Mono.just(ResponseEntity.ok(body));
            } catch (Exception e) {
                return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(""));
            }
        }

        // 身份归属必须建立在事件真实性验证之上。上面的 URL 验证
        // 使用挑战令牌；普通事件在头部携带该令牌。
        // 【学习提示】URL 挑战与普通事件的规则不同：普通事件要求配置非空 verificationToken，
        // 而挑战只在已配置时比较。上面的签名验证也有条件，不能把“配置了加密”
        // 理解为所有请求都会强制验证签名头。
        String configuredToken = channel.properties().verificationToken();
        String eventToken = envelope.path("header").path("token").asText("");
        if (configuredToken == null
                || configuredToken.isBlank()
                || !java.security.MessageDigest.isEqual(
                        configuredToken.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        eventToken.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
        }
        // 持久化接收流程负责去重。若在持久化前就标记事件已见，
        // 控制面暂时不可用时会丢失工作。
        // 【学习提示】本控制器没有调用 firstSeen；“持久化接收负责去重”是下游集成要求，
        // 不能仅因注入了 idempotency 对象就推断任意 Gateway 都已实现可靠去重。

        // 5. 映射为 InboundMessage；不支持或格式错误的消息安静确认。
        Optional<InboundMessage> inbound = channel.mapper().map(envelope);
        if (inbound.isEmpty()) {
            return Mono.just(ResponseEntity.ok("{}"));
        }
        InboundMessage in = inbound.get();

        // 6. 按对话对端键应用机器人循环防护。
        if (!channel.botLoopGuard().allow(in.peer().key())) {
            log.warn(
                    "Feishu callback: bot-loop guard tripped for peer='{}' (channelId='{}')",
                    in.peer().key(),
                    channelId);
            return Mono.just(ResponseEntity.ok("{}"));
        }

        // 7. 分发到渠道，由渠道处理出站投递。
        return channel.dispatch(in)
                .then(Mono.just(ResponseEntity.ok("{}")))
                .onErrorResume(
                        err -> {
                            log.warn(
                                    "Feishu callback: agent run failed (channelId='{}'): {}",
                                    channelId,
                                    err.getMessage());
                            return Mono.just(
                                    ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                                            .body("{}"));
                        });
    }
}
