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
package io.agentscope.builder.web.managed;

import io.agentscope.builder.web.auth.InternalTokenAuthFilter;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 跨平面将即时消息入站轮次（钉钉、飞书、企业微信、GitHub、GitLab 等）桥接到托管会话模型：
 * <ol>
 * <li><b>控制平面</b>的 {@code POST /api/internal/sessions/find-or-create} 解析通道会话，或创建并固定版本。
 * <li><b>数据平面</b>的 {@code POST /api/sessions/{id}/events} 提交用户消息，异步调度 Harness 轮次。
 * <li>轮询<b>数据平面</b>的 {@code GET /api/sessions/{id}/events?after=seq}，直到轮次终结，并将最后的 {@code agent.message} 文本作为通道回复。
 * </ol>
 * <p>所有调用使用共享内部令牌认证；{@code X-Builder-Internal-User} 携带实际所有者，使接收平面将操作归属到通道租户。桥接在 {@code boundedElastic} 上阻塞轮询；每个通道会话一个在途轮次，竞争受入站消息速率约束。
 */
@Component
public class ManagedSessionChannelBridge {

    private static final Logger log = LoggerFactory.getLogger(ManagedSessionChannelBridge.class);

    private static final ParameterizedTypeReference<List<SessionEventDto>> EVENT_LIST =
            new ParameterizedTypeReference<>() {};

    private final WebClient controlPlane;
    private final WebClient dataPlane;
    private final long replyTimeoutMs;
    private final long pollIntervalMs;

    public ManagedSessionChannelBridge(
            @Qualifier("controlPlaneWebClient") WebClient controlPlane,
            @Qualifier("dataPlaneWebClient") WebClient dataPlane,
            @Value("${builder.scheduler.reply-timeout-ms:120000}") long replyTimeoutMs,
            @Value("${builder.scheduler.poll-interval-ms:1000}") long pollIntervalMs) {
        this.controlPlane = controlPlane;
        this.dataPlane = dataPlane;
        this.replyTimeoutMs = replyTimeoutMs;
        this.pollIntervalMs = pollIntervalMs;
    }

    /**
     * 将 {@code text} 作为用户消息提交到查找或创建的托管会话，等待轮次结束并返回助手回复文本。
     * @param ownerId 拥有通道／智能体的 Builder 租户，而非即时消息对端
     * @param agentId 目标托管智能体 ID
     * @param externalKey 稳定会话键，参见 {@link ChannelExternalKeys}
     * @param text 用户消息文本
     */
    public Mono<String> dispatchAndAwaitReply(
            String ownerId, String agentId, String externalKey, String text) {
        // 【学习提示】阻塞的查找、提交和轮询封装到延迟执行的 Mono，并切换线程池；不能直接在 WebFlux 事件循环中等待回复。
        return Mono.fromCallable(() -> doDispatch(ownerId, agentId, externalKey, text))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * @deprecated 请使用 {@link #dispatchAndAwaitReply(String, String, String, String)}
     */
    @Deprecated
    public Mono<String> dispatchAndAwaitReply(String ownerId, String agentId, String text) {
        return dispatchAndAwaitReply(ownerId, agentId, null, text);
    }

    private String doDispatch(String ownerId, String agentId, String externalKey, String text) {
        ManagedSessionDto session = findOrCreateSession(ownerId, agentId, externalKey);
        long after = postUserMessage(ownerId, session.id(), text);
        return awaitReply(ownerId, session.id(), after);
    }

    /**
     * 解析对话的活动会话，首次接触时创建。
     */
    private ManagedSessionDto findOrCreateSession(
            String ownerId, String agentId, String externalKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ownerId", ownerId);
        body.put("agentId", agentId);
        if (externalKey != null && !externalKey.isBlank()) {
            body.put("externalKey", externalKey);
        }
        return controlPlane
                .post()
                .uri("/api/internal/sessions/find-or-create")
                .header(InternalTokenAuthFilter.INTERNAL_USER_HEADER, ownerId)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(ManagedSessionDto.class)
                .block(Duration.ofSeconds(30));
    }

    /**
     * 提交用户消息，返回最高的持久化事件序列，使回复轮询严格从入站事件之后开始。
     */
    private long postUserMessage(String ownerId, String sessionId, String text) {
        Map<String, Object> body =
                Map.of(
                        "events",
                        List.of(
                                Map.of(
                                        "type",
                                        SessionEventTypes.USER_MESSAGE,
                                        "payload",
                                        Map.of("text", text))));
        List<SessionEventDto> persisted =
                dataPlane
                        .post()
                        .uri("/api/sessions/{id}/events", sessionId)
                        .header(InternalTokenAuthFilter.INTERNAL_USER_HEADER, ownerId)
                        .bodyValue(body)
                        .retrieve()
                        .bodyToMono(EVENT_LIST)
                        .block(Duration.ofSeconds(30));
        long max = 0L;
        if (persisted != null) {
            for (SessionEventDto e : persisted) {
                max = Math.max(max, e.seq());
            }
        }
        return max;
    }

    /**
     * 轮询持久化事件日志直到轮次终结。返回最后看到的 {@code agent.message} 文本，轮次结束仍没有该消息时返回回退提示。
     */
    private String awaitReply(String ownerId, String sessionId, long after) {
        long deadline = System.currentTimeMillis() + replyTimeoutMs;
        String reply = null;
        while (System.currentTimeMillis() < deadline) {
            List<SessionEventDto> events;
            final long afterSeq = after;
            try {
                events =
                        dataPlane
                                .get()
                                .uri(
                                        uriBuilder ->
                                                uriBuilder
                                                        .path("/api/sessions/{id}/events")
                                                        .queryParam("after", afterSeq)
                                                        .build(sessionId))
                                .header(InternalTokenAuthFilter.INTERNAL_USER_HEADER, ownerId)
                                .retrieve()
                                .bodyToMono(EVENT_LIST)
                                .block(Duration.ofSeconds(30));
            } catch (Exception ex) {
                log.warn("session event poll failed (session={}): {}", sessionId, ex.getMessage());
                events = List.of();
            }
            if (events != null) {
                for (SessionEventDto e : events) {
                    after = Math.max(after, e.seq());
                    if (SessionEventTypes.AGENT_MESSAGE.equals(e.type())) {
                        String text = payloadText(e.payload());
                        if (text != null && !text.isBlank()) {
                            reply = text;
                        }
                    } else if (isTerminal(e.type())) {
                        return finishReply(e.type(), reply);
                    }
                }
            }
            try {
                Thread.sleep(pollIntervalMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return reply != null
                ? reply
                : "(still processing — the agent will keep running; ask again in a moment)";
    }

    private static String finishReply(String terminalType, String reply) {
        if (reply != null) {
            return reply;
        }
        return switch (terminalType) {
            case SessionEventTypes.SESSION_REQUIRES_ACTION ->
                    "(the agent is waiting for a tool confirmation — confirm it from the console)";
            case SessionEventTypes.SESSION_ERROR -> "(the agent run failed — check session events)";
            default -> "(the agent finished without a text reply)";
        };
    }

    private static boolean isTerminal(String type) {
        return SessionEventTypes.SESSION_STATUS_IDLE.equals(type)
                || SessionEventTypes.SESSION_STATUS_TERMINATED.equals(type)
                || SessionEventTypes.SESSION_REQUIRES_ACTION.equals(type)
                || SessionEventTypes.SESSION_ERROR.equals(type);
    }

    private static String payloadText(Map<String, Object> payload) {
        if (payload == null) {
            return null;
        }
        Object text = payload.get("text");
        return text != null ? String.valueOf(text) : null;
    }
}
