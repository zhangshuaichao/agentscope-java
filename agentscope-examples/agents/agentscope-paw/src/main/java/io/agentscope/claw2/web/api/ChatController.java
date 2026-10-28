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
package io.agentscope.claw2.web.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.claw2.runtime.ClawBootstrap;
import io.agentscope.claw2.runtime.gateway.HarnessGateway;
import io.agentscope.claw2.runtime.session.SessionAgentManager;
import io.agentscope.claw2.runtime.session.SessionEntry;
import io.agentscope.claw2.runtime.session.SessionKind;
import io.agentscope.claw2.web.catalog.AgentCatalogService;
import io.agentscope.claw2.web.toolbus.ToolEventBus;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.gateway.ChannelManager;
import io.agentscope.harness.agent.gateway.MsgContext;
import io.agentscope.harness.agent.gateway.channel.Channel;
import io.agentscope.harness.agent.gateway.channel.ChannelRouter;
import io.agentscope.harness.agent.gateway.channel.InboundMessage;
import io.agentscope.harness.agent.gateway.channel.RouteResult;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiChannel;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * 按智能体划分的聊天端点。
 *
 * <ul>
 *   <li>{@code POST /api/agents/{agentId}/chat/stream}：输出 {@code token | tool_call |
 *       tool_result | done | error} 事件的 SSE 流。
 *   <li>{@code POST /api/agents/{agentId}/chat/send}：同步回复（非流式）。
 *   <li>{@code GET  /api/agents/{agentId}/chat/session}：获取当前会话键，用于恢复界面。
 * </ul>
 *
 * <p>默认本地模式为智能体使用共享会话；实际路由以渠道绑定和默认配置为准。斜杠命令 {@code /new} 与 {@code /reset} 会在调用智能体前清除对话历史。
 */
@RestController
@RequestMapping("/api/agents/{agentId}/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HarnessGateway gateway;
    private final SessionAgentManager sessionAgentManager;
    private final AgentCatalogService catalogService;
    private final ToolEventBus toolEventBus;
    private final ChannelManager channelManager;
    private final ChannelRouter router = new ChannelRouter(null);

    public ChatController(
            ClawBootstrap bootstrap,
            AgentCatalogService catalogService,
            ToolEventBus toolEventBus) {
        this.gateway = bootstrap.gateway();
        this.sessionAgentManager = bootstrap.gateway().sessionAgentManager();
        this.catalogService = catalogService;
        this.toolEventBus = toolEventBus;
        this.channelManager = bootstrap.channelManager();
    }

    /** 两个聊天端点的请求体；{@code sessionKey} 为将来的路由用途预留。 */
    public record ChatRequest(String message, String sessionKey) {}

    /** 同步端点的响应。 */
    public record ChatResponse(String reply, String sessionKey) {}

    /** {@link #currentSession} 的响应。已有会话条目（即用户至少发送过一条消息）时，{@code exists} 为 {@code true}；前端据此决定初始化时是否拉取轮次历史。 */
    public record CurrentSessionResponse(String sessionKey, boolean exists) {}

    /** SSE 端点；成功路径将完整回复作为一个 token 帧发送，并非逐模型 token 输出。 */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(
            @PathVariable String agentId, @RequestBody ChatRequest req) {
        CommandResult cmd = handleSlashCommand(agentId, req.message());
        if (cmd != null) {
            return Flux.just(
                    sse("token", Map.of("type", "token", "data", cmd.message)),
                    sse("done", Map.of("type", "done")));
        }

        String gateKey = resolveGateKey(agentId);
        // 【学习提示】只在建立流时解析一次会话键；首轮尚无会话时工具事件流为空，不会随着会话创建自动补订阅。
        String existingSessionKey = findSessionKeyByGate(gateKey);
        Sinks.One<Boolean> done = Sinks.one();
        Flux<ServerSentEvent<String>> toolEvents =
                existingSessionKey != null
                        ? toolEventBus
                                .subscribe(existingSessionKey)
                                .takeUntilOther(done.asMono().timeout(Duration.ofMinutes(10)))
                                .map(this::toToolFrame)
                                .onErrorResume(ex -> Flux.empty())
                        : Flux.empty();

        Mono<Flux<ServerSentEvent<String>>> agentCall =
                executeChat(agentId, req.message())
                        .map(
                                reply -> {
                                    String text =
                                            reply.getTextContent() != null
                                                    ? reply.getTextContent()
                                                    : "";
                                    done.tryEmitValue(true);
                                    Map<String, Object> doneFrame = new LinkedHashMap<>();
                                    doneFrame.put("type", "done");
                                    String resolved = findSessionKeyByGate(gateKey);
                                    if (resolved != null) {
                                        doneFrame.put("sessionKey", resolved);
                                    }
                                    return Flux.just(
                                            sse("token", Map.of("type", "token", "data", text)),
                                            sse("done", doneFrame));
                                })
                        .onErrorResume(
                                ex -> {
                                    log.warn(
                                            "Chat stream error: agentId={}, error={}",
                                            agentId,
                                            ex.getMessage());
                                    done.tryEmitValue(false);
                                    return Mono.just(
                                            Flux.just(
                                                    sse(
                                                            "error",
                                                            Map.of(
                                                                    "type",
                                                                    "error",
                                                                    "error",
                                                                    ex.getMessage()))));
                                });

        return Flux.merge(toolEvents, Flux.from(agentCall.flatMapMany(f -> f)));
    }

    /** 返回 {@code agentId} 的当前会话键，供 UI 初始化使用。 */
    @GetMapping("/session")
    public Mono<CurrentSessionResponse> currentSession(@PathVariable String agentId) {
        return Mono.fromCallable(
                () -> {
                    String gateKey = resolveGateKey(agentId);
                    if (gateKey == null) {
                        return new CurrentSessionResponse(null, false);
                    }
                    String sessionKey = findSessionKeyByGate(gateKey);
                    if (sessionKey == null) {
                        return new CurrentSessionResponse(null, false);
                    }
                    return new CurrentSessionResponse(sessionKey, true);
                });
    }

    /** 同步非流式聊天；阻塞直到智能体生成回复。 */
    @PostMapping("/send")
    public Mono<ChatResponse> send(@PathVariable String agentId, @RequestBody ChatRequest req) {
        CommandResult cmd = handleSlashCommand(agentId, req.message());
        if (cmd != null) {
            return Mono.just(new ChatResponse(cmd.message, null));
        }
        String gateKey = resolveGateKey(agentId);
        return executeChat(agentId, req.message())
                .map(
                        reply -> {
                            String text =
                                    reply.getTextContent() != null ? reply.getTextContent() : "";
                            String sessionKey = findSessionKeyByGate(gateKey);
                            return new ChatResponse(text, sessionKey);
                        });
    }

    // -----------------------------------------------------------------
    // 内部辅助方法
    // -----------------------------------------------------------------

    private ServerSentEvent<String> toToolFrame(ToolEventBus.ToolEvent e) {
        Map<String, Object> data = new LinkedHashMap<>();
        boolean isResult = "TOOL_RESULT".equalsIgnoreCase(e.eventType());
        data.put("type", isResult ? "tool_result" : "tool_call");
        data.put("toolName", e.toolName());
        if (e.data() != null) {
            String payload;
            try {
                payload = MAPPER.writeValueAsString(e.data());
            } catch (JsonProcessingException ex) {
                payload = String.valueOf(e.data());
            }
            data.put(isResult ? "toolResult" : "toolInput", payload);
        }
        return sse(isResult ? "tool_result" : "tool_call", data);
    }

    /** 执行与 {@link ChatUiChannel#dispatch} 相同的 {@link ChannelRouter} 路由，再取得已解析上下文的 {@link MsgContext#canonicalKey()}，计算网关路由键。即使 chatui 绑定覆盖请求中的智能体，也与分派使用的键一致。它<strong>不是</strong>磁盘上的 {@code SessionEntry.sessionKey()}；需通过 {@link #findSessionKeyByGate} 转换。 */
    String resolveGateKey(String agentId) {
        if (agentId == null || agentId.isBlank()) return null;
        try {
            return resolveRoute(agentId, "__probe__").context().canonicalKey();
        } catch (Exception e) {
            return null;
        }
    }

    /** 构建聊天请求的入站消息并经过 chatui 路由器。解析后的 {@link RouteResult} 同时驱动会话键查询与 {@link HarnessGateway#run}，让绑定、渠道默认值和请求智能体提示使用同一路由结果。 */
    private RouteResult resolveRoute(String agentId, String probeText) {
        String gatewayAgentId = catalogService.resolveGatewayAgentId(agentId);
        ChatUiChannel chatui = lookupChatUi();
        InboundMessage inbound =
                InboundMessage.builder(
                                ChatUiChannel.CHANNEL_ID,
                                io.agentscope.harness.agent.gateway.channel.Peer.direct(
                                        "__anonymous__"),
                                List.of(
                                        Msg.builder()
                                                .role(MsgRole.USER)
                                                .textContent(probeText)
                                                .build()))
                        .senderId("__anonymous__")
                        .preferredAgentId(gatewayAgentId)
                        .build();
        return router.resolveRoute(chatui.config(), inbound);
    }

    private ChatUiChannel lookupChatUi() {
        if (channelManager != null) {
            Channel ch = channelManager.getChannel(ChatUiChannel.CHANNEL_ID).orElse(null);
            if (ch instanceof ChatUiChannel chatui) {
                return chatui;
            }
        }
        throw new IllegalStateException(
                "chatui channel not registered with ChannelManager; cannot route chat request");
    }

    /** 扫描已注册 MAIN 会话，将网关路由键转换为实际 {@code SessionEntry.sessionKey()}；尚无已注册会话时返回 {@code null}。 */
    private String findSessionKeyByGate(String gateKey) {
        if (gateKey == null) return null;
        for (SessionEntry e : sessionAgentManager.allSessions()) {
            if (e.kind() != SessionKind.MAIN) continue;
            if (!Objects.equals(gateKey, e.gateKey())) continue;
            return e.sessionKey();
        }
        return null;
    }

    /** 处理 {@code /new} 与 {@code /reset}；普通消息或未知斜杠命令返回 {@code null}。 */
    private CommandResult handleSlashCommand(String agentId, String message) {
        if (message == null) return null;
        String m = message.trim();
        if (!m.startsWith("/")) return null;
        String cmd = m.split("\\s+", 2)[0].toLowerCase();

        return switch (cmd) {
            case "/new", "/reset" -> {
                String gateKey = resolveGateKey(agentId);
                if (gateKey == null) {
                    yield new CommandResult("No active session to reset.");
                }
                String sessionKey = findSessionKeyByGate(gateKey);
                if (sessionKey == null) {
                    yield new CommandResult(
                            "No active session yet — your next message will start a fresh"
                                    + " conversation.");
                }
                boolean ok = sessionAgentManager.resetSession(sessionKey);
                yield new CommandResult(
                        ok
                                ? "AgentStateStore reset. Conversation history cleared; the next"
                                        + " message starts a fresh turn."
                                : "No matching session found for reset.");
            }
            default -> null;
        };
    }

    /** 承载斜杠命令结果的内部类型。 */
    private record CommandResult(String message) {}

    private Mono<Msg> executeChat(String agentId, String message) {
        RouteResult route = resolveRoute(agentId, message);
        return gateway.run(route.context(), List.of(messageOf(message)), route.outboundAddress());
    }

    private static Msg messageOf(String text) {
        return Msg.builder().role(MsgRole.USER).textContent(text).build();
    }

    private ServerSentEvent<String> sse(String eventType, Object data) {
        String json;
        try {
            json = MAPPER.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            json = "{\"type\":\"" + eventType + "\"}";
        }
        return ServerSentEvent.<String>builder().event(eventType).data(json).build();
    }
}
