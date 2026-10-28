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
package io.agentscope.builder.runtime.outbound;

import io.agentscope.builder.web.share.AgentAccessGuard;
import io.agentscope.builder.web.share.AgentAclService.Tier;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * 智能体主动／外部出站消息的 HTTP 入口。
 * <pre>
 * POST /api/outbound/send
 * { "channelId": "dingtalk-prod", "peerKind": "DIRECT", "peerId": "staff_xyz",
 *   "agentId": "support", "markdown": "..." }
 * </pre>
 * <p>此基础接口仅允许内部服务身份调用。控制台用户通过已授权工作订阅发布；202 响应仅确认本地提交。
 * 载荷设置 {@code agentId} 时，调用方必须至少具有该智能体的 {@link Tier#RUN} 权限，否则在进入通道前返回 403。
 * {@link OutboundService} 还进行基于通道路由的第二层检查，防止一个智能体通过属于另一智能体的绑定发消息。
 */
@RestController
@RequestMapping("/api/outbound")
public class OutboundController {

    private final OutboundService outboundService;
    private final AgentAccessGuard guard;

    public OutboundController(OutboundService outboundService, AgentAccessGuard guard) {
        this.outboundService = outboundService;
        this.guard = guard;
    }

    @PostMapping("/send")
    public Mono<ResponseEntity<Map<String, Object>>> send(
            @RequestBody OutboundRequest req, Authentication auth) {
        return Mono.fromRunnable(
                        () -> {
                            if (auth == null
                                    || auth.getAuthorities().stream()
                                            .noneMatch(
                                                    a ->
                                                            a.getAuthority()
                                                                    .equals("ROLE_INTERNAL"))) {
                                throw new ResponseStatusException(
                                        HttpStatus.FORBIDDEN,
                                        "Use authorized Channel work subscriptions for outbound"
                                                + " delivery");
                            }
                            // 【学习提示】内部服务身份、智能体 RUN 权限与通道路由归属是不同检查层；不能用其中一项代替其余授权边界。
                            String agentId = req != null ? req.agentId() : null;
                            if (agentId != null && !agentId.isBlank()) {
                                String userId = (String) auth.getPrincipal();
                                guard.require(userId, agentId, Tier.RUN);
                            }
                            outboundService.send(req);
                        })
                .thenReturn(
                        ResponseEntity.accepted()
                                .body(Map.<String, Object>of("status", "submitted")))
                .onErrorResume(
                        ResponseStatusException.class,
                        e ->
                                Mono.just(
                                        ResponseEntity.status(e.getStatusCode())
                                                .body(
                                                        Map.of(
                                                                "status",
                                                                "error",
                                                                "error",
                                                                e.getReason() == null
                                                                        ? e.getMessage()
                                                                        : e.getReason()))))
                .onErrorResume(
                        IllegalArgumentException.class,
                        e ->
                                Mono.just(
                                        ResponseEntity.status(HttpStatus.BAD_REQUEST)
                                                .body(
                                                        Map.of(
                                                                "status",
                                                                "error",
                                                                "error",
                                                                e.getMessage()))))
                .onErrorResume(
                        IllegalStateException.class,
                        e ->
                                Mono.just(
                                        ResponseEntity.status(HttpStatus.NOT_FOUND)
                                                .body(
                                                        Map.of(
                                                                "status",
                                                                "error",
                                                                "error",
                                                                e.getMessage()))));
    }
}
