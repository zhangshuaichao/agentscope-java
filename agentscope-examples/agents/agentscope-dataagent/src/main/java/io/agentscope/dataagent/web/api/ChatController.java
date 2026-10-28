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
package io.agentscope.dataagent.web.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.dataagent.runtime.DataAgentBootstrap;
import io.agentscope.dataagent.runtime.session.SessionAgentManager;
import io.agentscope.dataagent.runtime.session.SessionEntry;
import io.agentscope.dataagent.runtime.session.SessionKind;
import io.agentscope.dataagent.web.audit.ActivityEvent;
import io.agentscope.dataagent.web.audit.AgentActivityStore;
import io.agentscope.dataagent.web.binding.UserBinding;
import io.agentscope.dataagent.web.binding.UserBindingStore;
import io.agentscope.dataagent.web.catalog.AgentCatalogService;
import io.agentscope.dataagent.web.catalog.AgentDefinition;
import io.agentscope.dataagent.web.identity.IdentityLinkStore;
import io.agentscope.dataagent.web.share.AgentAccessGuard;
import io.agentscope.dataagent.web.share.AgentAclService.Tier;
import io.agentscope.dataagent.web.toolbus.ToolEventBus;
import io.agentscope.dataagent.web.usage.UsageStore;
import io.agentscope.harness.agent.gateway.MsgContext;
import io.agentscope.harness.agent.gateway.channel.InboundMessage;
import io.agentscope.harness.agent.gateway.channel.Peer;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiChannel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.Authentication;
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
 * 面向指定智能体的聊天端点。
 * <ul>
 * <li>{@code POST /api/agents/{agentId}/chat/stream} — SSE 流，事件为
 * {@code token | tool_call | tool_result | done | error}。
 * <li>{@code POST /api/agents/{agentId}/chat/send} — 非流式回复。
 * </ul>
 * <p>会话按 {@code (userId, agentId)} 和可选对话 ID 区分；智能体 ID 参与 {@link MsgContext#canonicalKey()}。
 * 调用智能体之前先拦截 {@code /new}、{@code /reset}、{@code /identity}、
 * {@code /dock_<channel> <id>} 斜杠命令。
 */
@RestController
@RequestMapping("/api/agents/{agentId}/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ChatUiChannel chatUiChannel;
    private final SessionAgentManager sessionAgentManager;
    private final AgentCatalogService catalogService;
    private final IdentityLinkStore identityLinks;
    private final UsageStore usageStore;
    private final ToolEventBus toolEventBus;
    private final AgentAccessGuard guard;
    private final AgentActivityStore activity;
    private final UserBindingStore userBindings;

    /**
     * 本进程内已记录 RUN_SESSION 活动的路由键集合，按用户、智能体、对话去重。
     * 同一会话后续轮次不重复记活动；/reset 会移除对应标记，进程重启也会清空集合。
     */
    private final Set<String> startedSessions = ConcurrentHashMap.newKeySet();

    public ChatController(
            ChatUiChannel chatUiChannel,
            DataAgentBootstrap builderBootstrap,
            AgentCatalogService catalogService,
            IdentityLinkStore identityLinks,
            UsageStore usageStore,
            ToolEventBus toolEventBus,
            AgentAccessGuard guard,
            AgentActivityStore activity,
            UserBindingStore userBindings) {
        this.chatUiChannel = chatUiChannel;
        this.sessionAgentManager = builderBootstrap.gateway().sessionAgentManager();
        this.catalogService = catalogService;
        this.identityLinks = identityLinks;
        this.usageStore = usageStore;
        this.toolEventBus = toolEventBus;
        this.guard = guard;
        this.activity = activity;
        this.userBindings = userBindings;
    }

    /**
     * 两个聊天端点共用的请求体。
     * <p>{@code sessionKey} 是调用者提供的对话 ID，可为同一用户和智能体区分多个类似 ChatGPT 的会话。
     * {@code null} 或空白输入经规范化后为空；流式端点会生成新 ID，非流式路径可回退到网关确定性路由键。
     */
    public record ChatRequest(String message, String sessionKey) {}

    /**
     * 非流式端点的响应。
     */
    public record ChatResponse(String reply, String sessionKey) {}

    /**
     * {@link #currentSession} 的响应。已有会话注册时 {@code exists} 为 {@code true}，
     * 前端据此决定页面加载时是否获取历史轮次。
     */
    public record CurrentSessionResponse(String sessionKey, boolean exists) {}

    /**
     * SSE 流式端点发出以下类型的事件，具体交错取决于执行过程：
     * <ul>
     * <li>{@code tool_call} — 智能体调用工具时实时发出。
     * <li>{@code tool_result} — 由工具事件总线提供结果。
     * <li>{@code token} — 完整回复文本，当前成功路径只发出一个文本块，不是逐模型 token 转发。
     * <li>{@code done} — 运行结束，可携带解析后的 {@code sessionKey}。
     * <li>{@code error} — 失败信息。
     * </ul>
     * <p>前端 {@code chat.ts} 将 {@code data:} 载荷解析为 JSON：
     * {@code { type, data?, toolName?, toolInput?, toolResult?, error?, sessionKey? }}。
     * <p>学习提示：SSE 是结果观察通道，不能仅凭浏览器断开就推断后台任务和已执行工具副作用会回滚。
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(
            @PathVariable String agentId, @RequestBody ChatRequest req, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        AgentDefinition def = guard.require(userId, agentId, Tier.RUN);
        // 调用者未指定对话时由服务端生成 ID，确保 gateKey
        // 跨轮次保持稳定，前端可将会话写入 URL。
        String conversationId = normalizedConversationId(req.sessionKey());
        if (conversationId == null) {
            conversationId = UUID.randomUUID().toString();
        }
        final String resolvedConversationId = conversationId;
        recordRunSession(def, agentId, userId, resolvedConversationId);

        // 斜杠命令绕过智能体，返回合成的单块回复。
        CommandResult cmd =
                handleSlashCommand(userId, agentId, req.message(), resolvedConversationId);
        if (cmd != null) {
            Map<String, Object> doneFrame = new LinkedHashMap<>();
            doneFrame.put("type", "done");
            // /new 创建新对话，其他命令保留调用者当前对话。
            doneFrame.put(
                    "sessionKey",
                    cmd.newSessionKey != null ? cmd.newSessionKey : resolvedConversationId);
            return Flux.just(
                    sse("token", Map.of("type", "token", "data", cmd.message)),
                    sse("done", doneFrame));
        }

        // 按真实 sessionKey 订阅工具事件；这里只在开始时查找一次。
        // 【学习提示】原注释描述了逐事件重查的回退，但当前代码没有该逻辑：
        // 若首轮尚无已注册会话，toolEvents 直接为空流，
        // 本次仍会执行智能体并返回最终文本，但不会经此订阅转发工具事件。
        String gateKey = resolveGateKey(userId, agentId, resolvedConversationId);
        String existingSessionKey = findSessionKeyByGate(userId, gateKey);
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
                executeChat(userId, agentId, req.message(), resolvedConversationId)
                        .map(
                                reply -> {
                                    String text =
                                            reply.getTextContent() != null
                                                    ? reply.getTextContent()
                                                    : "";
                                    done.tryEmitValue(true);
                                    Map<String, Object> doneFrame = new LinkedHashMap<>();
                                    doneFrame.put("type", "done");
                                    // 始终返回 conversationId，而不是内部存储键。
                                    // 否则前端会保存内部键并在下一轮当成对话 ID，
                                    // 导致同一次对话被拆成新会话。
                                    doneFrame.put("sessionKey", resolvedConversationId);
                                    return Flux.just(
                                            sse("token", Map.of("type", "token", "data", text)),
                                            sse("done", doneFrame));
                                })
                        .onErrorResume(
                                ex -> {
                                    log.warn(
                                            "Chat stream error: userId={}, agentId={}, error={}",
                                            userId,
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

    /**
     * 查询用户、智能体、对话 ID 三元组是否已注册会话。
     * 返回字段 {@code sessionKey} 是调用者自己的 {@code conversationId}（未提供时为 {@code null}），
     * 绝不回传内部存储键。已注册时 {@code exists} 为 {@code true}，前端据此判断是否获取历史轮次。
     */
    @GetMapping("/session")
    public Mono<CurrentSessionResponse> currentSession(
            @PathVariable String agentId,
            @org.springframework.web.bind.annotation.RequestParam(required = false)
                    String sessionKey,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        guard.require(userId, agentId, Tier.RUN);
        String conversationId = normalizedConversationId(sessionKey);
        return Mono.fromCallable(
                () -> {
                    if (conversationId == null) {
                        return new CurrentSessionResponse(null, false);
                    }
                    String gateKey = resolveGateKey(userId, agentId, conversationId);
                    boolean exists =
                            gateKey != null && findSessionKeyByGate(userId, gateKey) != null;
                    return new CurrentSessionResponse(conversationId, exists);
                });
    }

    /**
     * 非流式聊天：异步 Mono 在智能体回复后才给出完整响应，不等于在此主动调用 block()。
     */
    @PostMapping("/send")
    public Mono<ChatResponse> send(
            @PathVariable String agentId, @RequestBody ChatRequest req, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        AgentDefinition def = guard.require(userId, agentId, Tier.RUN);
        String conversationId = normalizedConversationId(req.sessionKey());
        if (conversationId == null) {
            conversationId = UUID.randomUUID().toString();
        }
        final String resolvedConversationId = conversationId;
        recordRunSession(def, agentId, userId, resolvedConversationId);
        CommandResult cmd =
                handleSlashCommand(userId, agentId, req.message(), resolvedConversationId);
        if (cmd != null) {
            return Mono.just(
                    new ChatResponse(
                            cmd.message,
                            cmd.newSessionKey != null
                                    ? cmd.newSessionKey
                                    : resolvedConversationId));
        }
        return executeChat(userId, agentId, req.message(), resolvedConversationId)
                .map(
                        reply -> {
                            String text =
                                    reply.getTextContent() != null ? reply.getTextContent() : "";
                            // 返回 conversationId 而不是存储键，原因见 stream()。
                            return new ChatResponse(text, resolvedConversationId);
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

    /**
     * 本进程内某个用户、智能体、对话三元组首次聊天时发出一个 {@code RUN_SESSION} 活动事件，
     * 同会话后续轮次不重复记录。{@code /reset} 清除缓存标记，下一次聊天可以重新记录。
     */
    private void recordRunSession(
            AgentDefinition def, String agentId, String userId, String conversationId) {
        if (def == null || def.ownerId() == null) {
            // 全局智能体没有按智能体划分的活动日志。
            return;
        }
        // 使用 gateKey 对用户、智能体、对话进行去重；
        // 首轮开始时真实 sessionKey 可能还不存在。
        String dedupeKey = resolveGateKey(userId, agentId, conversationId);
        if (dedupeKey == null) return;
        if (!startedSessions.add(dedupeKey)) return;
        activity.record(
                def.ownerId(),
                agentId,
                activity.actor(userId),
                ActivityEvent.Action.RUN_SESSION,
                dedupeKey,
                null);
    }

    /**
     * 将请求中的 {@code sessionKey} 规范化为 conversationId，缺失或空白时返回 {@code null}。
     */
    private static String normalizedConversationId(String key) {
        return (key != null && !key.isBlank()) ? key.trim() : null;
    }

    /**
     * 计算用户、智能体、对话 ID 三元组的网关路由键，即网关查找或创建会话时使用的
     * {@link MsgContext#canonicalKey()}，而<em>不是</em>存储层的 {@code SessionEntry.sessionKey()}。
     * 通过 {@link #findSessionKeyByGate} 才能转换到真实存储键。
     * <p>使用 {@link ChatUiChannel#previewRoute}，保证与 {@link #executeChat} 经同一渠道派发时的键一致。
     * 非空 {@code conversationId} 进入 {@code MsgContext.threadId}，为同一智能体的不同对话生成独立路由键及底层会话。
     */
    private String resolveGateKey(String userId, String agentId, String conversationId) {
        if (agentId == null || agentId.isBlank()) return null;
        try {
            String gatewayAgentId = catalogService.resolveGatewayAgentId(userId, agentId);
            InboundMessage probe =
                    InboundMessage.builder(ChatUiChannel.CHANNEL_ID, Peer.direct(userId), List.of())
                            .preferredAgentId(gatewayAgentId)
                            .accountId(conversationId)
                            .build();
            return chatUiChannel.previewRoute(probe).context().canonicalKey();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 扫描已注册的 MAIN 会话，按匹配的 {@code gateKey} 将网关路由键转换为真实的
     * {@code SessionEntry.sessionKey()}。尚未注册（例如首轮前）时返回 {@code null}。
     */
    private String findSessionKeyByGate(String userId, String gateKey) {
        if (gateKey == null) return null;
        for (SessionEntry e : sessionAgentManager.allSessions()) {
            if (e.kind() != SessionKind.MAIN) continue;
            if (!Objects.equals(gateKey, e.gateKey())) continue;
            if (userId != null && !Objects.equals(userId, e.userId())) continue;
            return e.sessionKey();
        }
        return null;
    }

    /**
     * 若 {@code message} 是已知斜杠命令，则执行相应副作用（如重置当前用户/智能体/对话的会话），
     * 返回合成确认消息；普通消息返回 {@code null}。
     * <p>{@code /new} 生成新的 {@code sessionKey}，通过 {@link CommandResult#newSessionKey} 返回，
     * 供前端跳转到新对话；旧会话保留。{@code /reset} 则原地清除<em>当前</em>会话历史。
     */
    private CommandResult handleSlashCommand(
            String userId, String agentId, String message, String conversationId) {
        if (message == null) return null;
        String m = message.trim();
        if (!m.startsWith("/")) return null;
        String[] parts = m.split("\\s+", 2);
        String cmd = parts[0].toLowerCase();
        String arg = parts.length > 1 ? parts[1].trim() : "";

        switch (cmd) {
            case "/new":
                {
                    // 生成全新会话 ID；前端从 SSE done 事件读取后
                    // 跳转到新对话。
                    String fresh = UUID.randomUUID().toString();
                    return new CommandResult(
                            "Started a fresh conversation. Your next message opens a new chat.",
                            fresh);
                }
            case "/reset":
                {
                    String gateKey = resolveGateKey(userId, agentId, conversationId);
                    if (gateKey == null) {
                        return new CommandResult("No active session to reset.", null);
                    }
                    String sessionKey = findSessionKeyByGate(userId, gateKey);
                    if (sessionKey == null) {
                        // 尚无已注册会话，无历史可清除；对用户而言等同于重新开始。
                        // 同时移除去重标记，使下一条真正的消息
                        // 再次记录 RUN_SESSION 活动事件。
                        startedSessions.remove(gateKey);
                        return new CommandResult(
                                "No active session yet — your next message will start a fresh"
                                        + " conversation.",
                                null);
                    }
                    boolean ok = sessionAgentManager.resetSession(sessionKey);
                    startedSessions.remove(gateKey);
                    return new CommandResult(
                            ok
                                    ? "AgentStateStore reset. Conversation history cleared; the"
                                            + " next message starts a fresh turn."
                                    : "No matching session found for reset.",
                            null);
                }
            case "/identity":
                {
                    Map<String, String> links = identityLinks.linksFor(userId);
                    if (links.isEmpty()) {
                        return new CommandResult(
                                "No identity links yet. Use `/dock_<channel> <externalId>` to add"
                                        + " one — e.g. `/dock_slack U7F9LZK1A`.",
                                null);
                    }
                    StringBuilder sb = new StringBuilder("Your identity links:\n");
                    links.forEach(
                            (ch, id) ->
                                    sb.append("  - ")
                                            .append(ch)
                                            .append(" -> ")
                                            .append(id)
                                            .append('\n'));
                    return new CommandResult(sb.toString(), null);
                }
            default:
                if (cmd.startsWith("/dock_")) {
                    String channel = cmd.substring("/dock_".length());
                    if (channel.isBlank() || arg.isBlank()) {
                        return new CommandResult(
                                "Usage: `/dock_<channel> <externalId>` — e.g."
                                        + " `/dock_slack U7F9LZK1A`.",
                                null);
                    }
                    identityLinks.link(userId, channel, arg);
                    return new CommandResult(
                            "Linked your identity on `" + channel + "` to `" + arg + "`.", null);
                }
                return null;
        }
    }

    /**
     * 斜杠命令结果的内部载体。命令创建新会话（例如 {@code /new}）时，
     * {@code newSessionKey} 非空；流式和非流式端点都将其交给前端更新 URL。
     */
    private record CommandResult(String message, String newSessionKey) {}

    /**
     * 为一次聊天构造入站消息列表，应用存储在 {@link UserBinding} 中、能在派发入口生效的偏好。
     * <p>当前仅接入 {@code language}：注入 {@link MsgRole#SYSTEM} 指令请求指定回复语言。
     * 这仍是模型指令，而非保证每个输出字符符合语言要求的强制校验。
     * 其他字段（{@code enabledSkills}、{@code sessionScope}）尚需更深入的架构接入：
     * <ul>
     * <li>{@code enabledSkills} 需要在构建智能体时过滤技能，见 {@code DataAgentBootstrap} / {@code SkillRepositorySupport}，
     * 并非仅改派发入口就能实现。
     * <li>{@code sessionScope} 影响 {@code ChannelRouter} 的会话键推导，不属于此控制器的实现范围。
     * </ul>
     */
    static List<Msg> shapeInboundMessages(
            List<UserBinding> preferences, String channelId, String message) {
        UserBinding pref =
                preferences.stream()
                        .filter(b -> channelId.equals(b.channelId()))
                        .findFirst()
                        .orElse(null);
        List<Msg> msgs = new ArrayList<>();
        if (pref != null && pref.language() != null) {
            msgs.add(
                    Msg.builder()
                            .role(MsgRole.SYSTEM)
                            .textContent("Reply to the user in " + pref.language() + ".")
                            .build());
        }
        msgs.add(Msg.builder().role(MsgRole.USER).textContent(message).build());
        return msgs;
    }

    /**
     * 核心派发逻辑始终经 {@link ChatUiChannel#dispatch}，使
     * {@link io.agentscope.harness.agent.gateway.channel.ChannelRouter} 对 Web UI 路径调用同样执行统一路由。
     * URL 中的 {@code agentId} 作为 {@link InboundMessage#preferredAgentId()} 传入，
     * 表示用户显式选择目标，优先于绑定层级选择；路由器仍可从匹配绑定推导会话范围和出站地址。
     * <p>{@code agentId} 为空时（防御分支，控制器通常总会提供），回退为纯绑定路由，使用匹配绑定或渠道默认智能体。
     */
    private Mono<Msg> executeChat(
            String userId, String agentId, String message, String conversationId) {
        long startMs = System.currentTimeMillis();

        List<Msg> msgs =
                shapeInboundMessages(userBindings.list(userId), ChatUiChannel.CHANNEL_ID, message);

        InboundMessage inbound;
        if (agentId == null || agentId.isBlank()) {
            // 既无智能体覆盖也无对话范围，使用纯绑定路由。
            inbound = InboundMessage.dm(ChatUiChannel.CHANNEL_ID, userId, List.copyOf(msgs));
        } else {
            String gatewayAgentId = catalogService.resolveGatewayAgentId(userId, agentId);
            inbound =
                    InboundMessage.builder(
                                    ChatUiChannel.CHANNEL_ID,
                                    Peer.direct(userId),
                                    List.copyOf(msgs))
                            .preferredAgentId(gatewayAgentId)
                            .accountId(conversationId)
                            .build();
        }
        Mono<Msg> call = chatUiChannel.dispatch(inbound);

        final String recordedAgentId = agentId != null ? agentId : "(default)";
        return call.doOnSuccess(
                reply ->
                        usageStore.record(
                                userId, recordedAgentId, System.currentTimeMillis() - startMs));
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
