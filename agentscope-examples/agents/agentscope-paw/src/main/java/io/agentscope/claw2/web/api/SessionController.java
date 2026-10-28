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

import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentscope.claw2.runtime.ClawBootstrap;
import io.agentscope.claw2.runtime.session.HistoryResult;
import io.agentscope.claw2.runtime.session.SessionAgentManager;
import io.agentscope.claw2.runtime.session.SessionEntry;
import io.agentscope.claw2.runtime.session.SessionKind;
import io.agentscope.claw2.web.catalog.AgentCatalogService;
import io.agentscope.claw2.web.session.SessionReadStateStore;
import io.agentscope.claw2.web.session.SessionTurnParser;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.session.SessionTranscriptExport;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.gateway.MsgContext;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiChannel;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * 按智能体划分的会话状态管理端点。
 *
 * <ul>
 *   <li>{@code GET /api/agents/{agentId}/sessions/inbox}：带预览及未读标记的分页会话列表
 *   <li>{@code GET /api/agents/{agentId}/sessions/{key}}：结构化逐轮对话记录
 *   <li>{@code POST /api/agents/{agentId}/sessions/{key}/reset}：清除对话历史
 *   <li>{@code PATCH /api/agents/{agentId}/sessions/{key}/read}：标记已读
 *   <li>{@code DELETE /api/agents/{agentId}/sessions/{key}}：删除整个会话
 * </ul>
 *
 * <p>按网关注册会话使用的 {@code gateKey} 过滤；属于其他智能体的会话返回 403。这是本地单用户应用中的智能体范围检查，不是跨用户认证。
 */
@RestController
@RequestMapping("/api/agents/{agentId}/sessions")
public class SessionController {

    private final ClawBootstrap bootstrap;
    private final SessionAgentManager sessionAgentManager;
    private final SessionReadStateStore readStateStore;
    private final AgentCatalogService catalogService;

    public SessionController(
            ClawBootstrap bootstrap,
            SessionReadStateStore readStateStore,
            AgentCatalogService catalogService) {
        this.bootstrap = bootstrap;
        this.sessionAgentManager = bootstrap.gateway().sessionAgentManager();
        this.readStateStore = readStateStore;
        this.catalogService = catalogService;
    }

    @GetMapping("/inbox")
    public Mono<List<InboxEntry>> inbox(
            @PathVariable String agentId,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "false") boolean unreadOnly) {
        return Mono.fromCallable(
                () -> {
                    String expectedGateKey = expectedChatGateKey(agentId);
                    List<SessionEntry> matched =
                            sessionAgentManager.allSessions().stream()
                                    .filter(e -> sessionMatchesAgent(e, expectedGateKey))
                                    .sorted(
                                            Comparator.comparingLong(SessionEntry::lastActivityMs)
                                                    .reversed())
                                    .limit(limit)
                                    .toList();

                    List<InboxEntry> out = new ArrayList<>(matched.size());
                    for (SessionEntry e : matched) {
                        boolean unread =
                                readStateStore.isUnread(e.sessionKey(), e.lastActivityMs());
                        if (unreadOnly && !unread) continue;
                        String preview = lastMessagePreview(agentId, e);
                        out.add(
                                new InboxEntry(
                                        e.sessionKey(),
                                        e.sessionId(),
                                        e.agentId(),
                                        e.label(),
                                        e.lastActivityMs(),
                                        preview,
                                        unread));
                    }
                    return out;
                });
    }

    @GetMapping("/{key}")
    public Mono<List<SessionTurnParser.TurnEntry>> turns(
            @PathVariable String agentId, @PathVariable String key) {
        return Mono.fromCallable(
                () -> {
                    SessionEntry entry = requireSession(agentId, key);
                    String content = readSessionLogContent(agentId, entry);
                    return SessionTurnParser.parse(content != null ? content : "");
                });
    }

    @PostMapping("/{key}/reset")
    public Mono<ResetResult> reset(@PathVariable String agentId, @PathVariable String key) {
        return Mono.fromCallable(
                () -> {
                    requireSession(agentId, key);
                    boolean ok = sessionAgentManager.resetSession(key);
                    return new ResetResult(key, ok);
                });
    }

    @PatchMapping("/{key}/read")
    public Mono<ReadStateResult> markRead(@PathVariable String agentId, @PathVariable String key) {
        return Mono.fromCallable(
                () -> {
                    requireSession(agentId, key);
                    long readAtMs = readStateStore.markRead(key);
                    return new ReadStateResult(key, readAtMs, false);
                });
    }

    @DeleteMapping("/{key}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable String agentId, @PathVariable String key) {
        return Mono.fromRunnable(
                () -> {
                    requireSession(agentId, key);
                    sessionAgentManager.removeSession(key);
                });
    }

    // -----------------------------------------------------------------
    // 内部辅助方法
    // -----------------------------------------------------------------

    private SessionEntry requireSession(String agentId, String key) {
        SessionEntry entry =
                sessionAgentManager
                        .getSession(key)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "AgentStateStore not found: " + key));
        String expectedGateKey = expectedChatGateKey(agentId);
        if (!sessionMatchesAgent(entry, expectedGateKey)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }
        return entry;
    }

    /** 计算 chat-ui 渠道对 {@code agentId} 使用的网关路由键，与 {@code ChatController#resolveGateKey} 对应，使此控制器按网关注册会话所用的同一键检查范围。 */
    private String expectedChatGateKey(String agentId) {
        if (agentId == null) return null;
        String gatewayAgentId;
        try {
            gatewayAgentId = catalogService.resolveGatewayAgentId(agentId);
        } catch (ResponseStatusException ex) {
            return null;
        }
        if (gatewayAgentId == null) return null;
        MsgContext ctx =
                new MsgContext(
                        ChatUiChannel.CHANNEL_ID,
                        null,
                        null,
                        null,
                        null,
                        Map.of("agentId", gatewayAgentId));
        return ctx.canonicalKey();
    }

    /** 检查会话是否属于 URL 指定的智能体。{@link SessionEntry#agentId()} 保存原生稳定身份（旧元数据使用实例 UUID），因此不直接匹配它，而是比较会话 {@code gateKey} 与 URL 智能体期望的键；该键由 {@code gatewayAgentId} 确定性推导。缺少 {@code gateKey} 的子会话／群组会话会直接放行，它们本来也不显示在收件箱。 */
    private static boolean sessionMatchesAgent(SessionEntry e, String expectedGateKey) {
        if (expectedGateKey == null) return false;
        if (e.kind() == SessionKind.MAIN) {
            return Objects.equals(e.gateKey(), expectedGateKey);
        }
        return e.gateKey() == null || Objects.equals(e.gateKey(), expectedGateKey);
    }

    private String lastMessagePreview(String agentId, SessionEntry entry) {
        try {
            String content = readSessionLogContent(agentId, entry);
            if (content == null || content.isEmpty()) {
                return null;
            }
            List<SessionTurnParser.TurnEntry> turns = SessionTurnParser.parse(content);
            for (int i = turns.size() - 1; i >= 0; i--) {
                SessionTurnParser.TurnEntry t = turns.get(i);
                if (t.content() != null && !t.content().isBlank()) {
                    String trimmed = t.content().trim();
                    return trimmed.length() > 200 ? trimmed.substring(0, 200) + "…" : trimmed;
                }
            }
        } catch (Exception ignored) {
            // 继续后续处理
        }
        return null;
    }

    /** 根据已提交的原生会话日志构建旧版 UI 历史结构。 */
    private String readSessionLogContent(String urlAgentId, SessionEntry entry) {
        HarnessAgent ha = bootstrap.gateway().getAgent(urlAgentId);
        if (ha != null && entry.kind() == SessionKind.MAIN) {
            RuntimeContext rc =
                    RuntimeContext.builder().userId(null).sessionId(entry.sessionId()).build();
            return SessionTranscriptExport.jsonl(ha.sessionTranscript(rc));
        }
        HistoryResult raw = sessionAgentManager.history(entry.sessionKey(), 0);
        return raw != null && raw.error() == null && raw.content() != null ? raw.content() : "";
    }

    // -----------------------------------------------------------------
    // 数据传输对象
    // -----------------------------------------------------------------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record InboxEntry(
            String sessionKey,
            String sessionId,
            String agentId,
            String label,
            long lastActivityMs,
            String lastMessage,
            boolean unread) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ResetResult(String sessionKey, boolean reset) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ReadStateResult(String sessionKey, long readAtMs, boolean unread) {}
}
