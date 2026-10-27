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
package io.agentscope.extensions.channel.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.harness.agent.gateway.channel.InboundMessage;
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
 * 处理 GitHub webhook，端点为 {@code POST /api/channels/github/{channelId}/webhook}。
 * 请求体以原始 byte[] 接收，HMAC-SHA256 按签名对应的精确字节校验。
 * <p>流水线：验签→事件类型→去重→自我回流与对端限流→渠道分发。
 * <p>【学习提示】认证只证明请求来自持有共享秘密的一方，不代表评论正文是可信指令。
 * 去重登记早于分发；智能体执行失败也被 onErrorResume 转为成功 HTTP 确认，不能依赖平台重投自动恢复业务失败。
 */
@RestController
@RequestMapping("/api/channels/github")
public class GitHubWebhookController {

    private static final Logger log = LoggerFactory.getLogger(GitHubWebhookController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final GitHubChannelRegistry registry;

    public GitHubWebhookController() {
        this(GitHubChannelRegistry.instance());
    }

    /**
     * 供测试注入独立注册表。
     */
    GitHubWebhookController(GitHubChannelRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @PostMapping(
            value = "/{channelId}/webhook",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<String>> webhook(
            @PathVariable String channelId,
            @RequestHeader(value = "X-GitHub-Event", required = false) String eventType,
            @RequestHeader(value = "X-GitHub-Delivery", required = false) String deliveryId,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature256,
            @RequestBody byte[] rawBody) {
        GitHubChannel channel = registry.get(channelId);
        if (channel == null) {
            log.warn("GitHub webhook: no channel registered for id='{}'", channelId);
            return Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND).build());
        }

        // 1. 对原始请求体强制验签。
        if (!channel.signatureVerifier().verify(signature256, rawBody)) {
            log.warn(
                    "GitHub webhook: signature mismatch (channelId='{}', delivery='{}')",
                    channelId,
                    deliveryId);
            return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
        }

        // 2. 过滤当前不处理的事件类型，返回 204 确认。
        if (eventType == null
                || !(eventType.equals("issue_comment")
                        || eventType.equals("pull_request_review_comment"))) {
            return Mono.just(ResponseEntity.noContent().build());
        }

        JsonNode payload;
        try {
            payload = MAPPER.readTree(rawBody);
        } catch (Exception e) {
            log.warn(
                    "GitHub webhook: JSON parse failed (channelId='{}'): {}",
                    channelId,
                    e.getMessage());
            return Mono.just(ResponseEntity.badRequest().body(""));
        }

        // 3. 按 comment.id 去重，编辑评论不会改变此 ID。
        Optional<Long> commentId = GitHubInboundMapper.extractCommentId(payload);
        if (commentId.isPresent()
                && !channel.idempotency().firstSeen(channelId + "|" + commentId.get())) {
            log.debug(
                    "GitHub webhook: duplicate comment.id={} (channelId='{}')",
                    commentId.get(),
                    channelId);
            return Mono.just(ResponseEntity.ok("{}"));
        }

        // 4. 作者 ID 与已解析机器人 ID 相同时丢弃，避免自我回流。
        Optional<Long> commenterId = GitHubInboundMapper.extractCommenterId(payload);
        long botId = channel.botIdentity().botUserId();
        if (botId > 0 && commenterId.isPresent() && commenterId.get() == botId) {
            log.debug(
                    "GitHub webhook: skipping self-authored comment (channelId='{}', commenter={})",
                    channelId,
                    botId);
            return Mono.just(ResponseEntity.ok("{}"));
        }

        // 5. 映射为 InboundMessage，不支持的动作静默确认。
        Optional<InboundMessage> inbound = channel.mapper().map(eventType, payload);
        if (inbound.isEmpty()) {
            return Mono.just(ResponseEntity.ok("{}"));
        }
        InboundMessage in = inbound.get();

        // 6. 按对端执行速率限制。
        if (!channel.botLoopGuard().allow(in.peer().key())) {
            log.warn(
                    "GitHub webhook: bot-loop guard tripped for peer='{}' (channelId='{}')",
                    in.peer().key(),
                    channelId);
            return Mono.just(ResponseEntity.ok("{}"));
        }

        return channel.dispatch(in)
                .then(Mono.just(ResponseEntity.ok("{}")))
                .onErrorResume(
                        err -> {
                            log.warn(
                                    "GitHub webhook: agent run failed (channelId='{}'): {}",
                                    channelId,
                                    err.getMessage());
                            return Mono.just(ResponseEntity.ok("{}"));
                        });
    }
}
