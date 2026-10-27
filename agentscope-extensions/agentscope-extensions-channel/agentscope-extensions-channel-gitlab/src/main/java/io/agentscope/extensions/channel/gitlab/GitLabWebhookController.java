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
package io.agentscope.extensions.channel.gitlab;

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
 * 处理 GitLab webhook，端点为 {@code POST /api/channels/gitlab/{channelId}/webhook}。
 * <p>流水线：{@code X-Gitlab-Token} 校验→{@code X-Gitlab-Event} 类型→去重→防循环→渠道分发。
 * <p>【学习提示】认证只证明请求来自持有共享秘密的一方，不代表评论正文是可信指令。
 * 去重登记早于分发；智能体执行失败也被 onErrorResume 转为成功 HTTP 确认，不能依赖平台重投自动恢复业务失败。
 */
@RestController
@RequestMapping("/api/channels/gitlab")
public class GitLabWebhookController {

    private static final Logger log = LoggerFactory.getLogger(GitLabWebhookController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final GitLabChannelRegistry registry;

    public GitLabWebhookController() {
        this(GitLabChannelRegistry.instance());
    }

    /**
     * 供测试注入独立注册表。
     */
    GitLabWebhookController(GitLabChannelRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @PostMapping(
            value = "/{channelId}/webhook",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<String>> webhook(
            @PathVariable String channelId,
            @RequestHeader(value = "X-Gitlab-Token", required = false) String token,
            @RequestHeader(value = "X-Gitlab-Event", required = false) String eventType,
            @RequestBody String rawBody) {
        GitLabChannel channel = registry.get(channelId);
        if (channel == null) {
            log.warn("GitLab webhook: no channel registered for id='{}'", channelId);
            return Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND).build());
        }

        // 1. 用常量时间比较校验令牌。
        if (!constantTimeEquals(token, channel.properties().webhookToken())) {
            log.warn("GitLab webhook: X-Gitlab-Token mismatch (channelId='{}')", channelId);
            return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
        }

        // 2. 仅映射 Note Hook，其他事件返回 204。
        if (!"Note Hook".equals(eventType)) {
            return Mono.just(ResponseEntity.noContent().build());
        }

        JsonNode payload;
        try {
            payload = MAPPER.readTree(rawBody);
        } catch (Exception e) {
            log.warn(
                    "GitLab webhook: JSON parse failed (channelId='{}'): {}",
                    channelId,
                    e.getMessage());
            return Mono.just(ResponseEntity.badRequest().body(""));
        }

        // 3. 按笔记 ID 去重。
        Optional<Long> noteId = GitLabInboundMapper.extractNoteId(payload);
        if (noteId.isPresent()
                && !channel.idempotency().firstSeen(channelId + "|" + noteId.get())) {
            log.debug(
                    "GitLab webhook: duplicate note.id={} (channelId='{}')",
                    noteId.get(),
                    channelId);
            return Mono.just(ResponseEntity.ok("{}"));
        }

        // 4. 检测机器人自身回流。
        Optional<Long> authorId = GitLabInboundMapper.extractAuthorId(payload);
        long botId = channel.botIdentity().botUserId();
        if (botId > 0 && authorId.isPresent() && authorId.get() == botId) {
            log.debug(
                    "GitLab webhook: skipping self-authored note (channelId='{}', author={})",
                    channelId,
                    botId);
            return Mono.just(ResponseEntity.ok("{}"));
        }

        // 5. 映射消息；非文本、系统笔记或不支持的 noteable_type 静默确认。
        Optional<InboundMessage> inbound = channel.mapper().map(payload);
        if (inbound.isEmpty()) {
            return Mono.just(ResponseEntity.ok("{}"));
        }
        InboundMessage in = inbound.get();

        // 6. 按对端执行速率限制。
        if (!channel.botLoopGuard().allow(in.peer().key())) {
            log.warn(
                    "GitLab webhook: bot-loop guard tripped for peer='{}' (channelId='{}')",
                    in.peer().key(),
                    channelId);
            return Mono.just(ResponseEntity.ok("{}"));
        }

        return channel.dispatch(in)
                .then(Mono.just(ResponseEntity.ok("{}")))
                .onErrorResume(
                        err -> {
                            log.warn(
                                    "GitLab webhook: agent run failed (channelId='{}'): {}",
                                    channelId,
                                    err.getMessage());
                            return Mono.just(ResponseEntity.ok("{}"));
                        });
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int r = 0;
        for (int i = 0; i < a.length(); i++) {
            r |= a.charAt(i) ^ b.charAt(i);
        }
        return r == 0;
    }
}
