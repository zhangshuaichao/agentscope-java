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

import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.session.SessionTranscriptExport;
import io.agentscope.dataagent.runtime.DataAgentBootstrap;
import io.agentscope.dataagent.runtime.session.HistoryResult;
import io.agentscope.dataagent.runtime.session.SessionAgentManager;
import io.agentscope.dataagent.runtime.session.SessionEntry;
import io.agentscope.dataagent.runtime.session.SessionKind;
import io.agentscope.dataagent.web.catalog.AgentCatalogService;
import io.agentscope.dataagent.web.session.SessionReadStateStore;
import io.agentscope.dataagent.web.session.SessionTurnParser;
import io.agentscope.harness.agent.HarnessAgent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
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
 * 面向指定智能体的 AgentStateStore 管理端点。
 * <ul>
 * <li>{@code GET /api/agents/{agentId}/sessions/inbox} — 分页会话列表，附预览和未读标记。
 * <li>{@code GET /api/agents/{agentId}/sessions/{key}} — 按轮次结构化的记录。
 * <li>{@code POST /api/agents/{agentId}/sessions/{key}/reset} — 清除会话历史。
 * <li>{@code PATCH /api/agents/{agentId}/sessions/{key}/read} — 标记已读。
 * <li>{@code DELETE /api/agents/{agentId}/sessions/{key}} — 删除整个会话。
 * </ul>
 * <p>端点检查认证用户归属，并对带路由键的会话检查 URL 智能体匹配；不匹配返回 403。
 * 没有 gateKey 的子/群组会话只经过用户归属检查，不能把注释理解为所有会话都强制匹配智能体。
 */
@RestController
@RequestMapping("/api/agents/{agentId}/sessions")
public class SessionController {

    private static final Logger log = LoggerFactory.getLogger(SessionController.class);

    private final DataAgentBootstrap bootstrap;
    private final SessionAgentManager sessionAgentManager;
    private final SessionReadStateStore readStateStore;
    private final AgentCatalogService catalogService;

    public SessionController(
            DataAgentBootstrap builderBootstrap,
            SessionReadStateStore readStateStore,
            AgentCatalogService catalogService) {
        this.bootstrap = builderBootstrap;
        this.sessionAgentManager = builderBootstrap.gateway().sessionAgentManager();
        this.readStateStore = readStateStore;
        this.catalogService = catalogService;
    }

    @GetMapping("/inbox")
    public Mono<List<InboxEntry>> inbox(
            @PathVariable String agentId,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> {
                    String gatewayAgentId = catalogService.peekGatewayAgentId(userId, agentId);
                    List<SessionEntry> matched =
                            sessionAgentManager.allSessions().stream()
                                    .filter(e -> Objects.equals(e.userId(), userId))
                                    .filter(e -> sessionMatchesAgent(e, gatewayAgentId))
                                    .sorted(
                                            Comparator.comparingLong(SessionEntry::lastActivityMs)
                                                    .reversed())
                                    .limit(limit)
                                    .toList();

                    List<InboxEntry> out = new ArrayList<>(matched.size());
                    for (SessionEntry e : matched) {
                        boolean unread =
                                readStateStore.isUnread(userId, e.sessionKey(), e.lastActivityMs());
                        if (unreadOnly && !unread) continue;
                        String preview = lastMessagePreview(agentId, e);
                        out.add(
                                new InboxEntry(
                                        e.sessionKey(),
                                        e.sessionId(),
                                        e.agentId(),
                                        extractConversationId(e.gateKey()),
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
            @PathVariable String agentId, @PathVariable String key, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> {
                    SessionEntry entry = requireOwnedSession(agentId, key, userId);
                    String content = readSessionLogContent(agentId, entry);
                    return SessionTurnParser.parse(content != null ? content : "");
                });
    }

    @PostMapping("/{key}/reset")
    public Mono<ResetResult> reset(
            @PathVariable String agentId, @PathVariable String key, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> {
                    SessionEntry entry = requireOwnedSession(agentId, key, userId);
                    boolean ok = sessionAgentManager.resetSession(entry.sessionKey());
                    return new ResetResult(key, ok);
                });
    }

    @PatchMapping("/{key}/read")
    public Mono<ReadStateResult> markRead(
            @PathVariable String agentId, @PathVariable String key, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> {
                    SessionEntry entry = requireOwnedSession(agentId, key, userId);
                    long readAtMs = readStateStore.markRead(userId, entry.sessionKey());
                    return new ReadStateResult(key, readAtMs, false);
                });
    }

    @DeleteMapping("/{key}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(
            @PathVariable String agentId, @PathVariable String key, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromRunnable(
                () -> {
                    SessionEntry entry = requireOwnedSession(agentId, key, userId);
                    sessionAgentManager.removeSession(entry.sessionKey());
                });
    }

    // -----------------------------------------------------------------
    // 内部辅助方法
    // -----------------------------------------------------------------

    /**
     * 将路径中的 {@code key} 解析成属于 {@code userId}、对应 URL {@code agentId} 的 {@link SessionEntry}。
     * {@code key} 可为旧调用者使用的内部存储键，也可为 {@link InboxEntry#conversationId()} 暴露的对话 ID；
     * 前端多会话导航使用后者。
     */
    private SessionEntry requireOwnedSession(String agentId, String key, String userId) {
        SessionEntry entry =
                sessionAgentManager
                        .getSession(key)
                        .orElseGet(() -> findSessionByConversationId(agentId, key, userId));
        if (entry == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "AgentStateStore not found: " + key);
        }
        String gatewayAgentId = catalogService.peekGatewayAgentId(userId, agentId);
        if (!Objects.equals(entry.userId(), userId)
                || !sessionMatchesAgent(entry, gatewayAgentId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }
        return entry;
    }

    /**
     * 扫描已注册的 MAIN 会话，查找 {@code gateKey} 包含 {@code |t:<key>} 且用户、智能体匹配的项；
     * 不存在时返回 {@code null}。
     */
    private SessionEntry findSessionByConversationId(String agentId, String key, String userId) {
        if (key == null || key.isBlank()) return null;
        String gatewayAgentId = catalogService.peekGatewayAgentId(userId, agentId);
        for (SessionEntry e : sessionAgentManager.allSessions()) {
            if (e.kind() != SessionKind.MAIN) continue;
            if (!Objects.equals(userId, e.userId())) continue;
            if (!sessionMatchesAgent(e, gatewayAgentId)) continue;
            if (key.equals(extractConversationId(e.gateKey()))) {
                return e;
            }
        }
        return null;
    }

    /**
     * 检查会话是否匹配 URL 中的智能体。{@link SessionEntry#agentId()} 存储原生稳定身份，
     * 旧元数据还可能是实例 UUID，因此不直接比较它。
     * 改为检查由 {@code (userId, gatewayAgentId, conversationId)} 推导的 {@code gateKey}，
     * 其中 {@code |x:agentId=...} 段应匹配目标网关智能体，且不受区分同智能体不同会话的
     * {@code |t:<conversationId>} 段影响。
     * <p>缺少 gateKey 的子/群组会话仅依赖上游用户归属检查；入口已按 {@code entry.userId() == auth principal}
     * 筛选，但这并非同一用户内的智能体级绑定校验。
     */
    private static boolean sessionMatchesAgent(SessionEntry e, String gatewayAgentId) {
        if (gatewayAgentId == null) return false;
        String gateKey = e.gateKey();
        if (e.kind() == SessionKind.MAIN) {
            return gateKey != null && extractGatewayAgentId(gateKey).equals(gatewayAgentId);
        }
        // 子/群组会话可能没有 gateKey，此分支只依赖上游 userId 归属检查。
        return gateKey == null || extractGatewayAgentId(gateKey).equals(gatewayAgentId);
    }

    /**
     * 从规范路由键的 {@code |x:agentId=<value>} 段提取 {@code agentId}，不存在时返回空字符串。
     */
    private static String extractGatewayAgentId(String gateKey) {
        String needle = "|x:agentId=";
        int i = gateKey.indexOf(needle);
        if (i < 0) return "";
        int start = i + needle.length();
        int end = gateKey.indexOf('|', start);
        return end < 0 ? gateKey.substring(start) : gateKey.substring(start, end);
    }

    /**
     * 从规范路由键 {@code |t:<value>} 段提取 conversationId，即
     * {@link io.agentscope.harness.agent.gateway.MsgContext} 的 threadId。
     * 路由键缺失或无此段时返回 {@code null}；旧单会话模式的对话 ID 也为 {@code null}，仍可使用存储键访问。
     */
    static String extractConversationId(String gateKey) {
        if (gateKey == null) return null;
        String needle = "|t:";
        int i = gateKey.indexOf(needle);
        if (i < 0) return null;
        int start = i + needle.length();
        int end = gateKey.indexOf('|', start);
        String val = end < 0 ? gateKey.substring(start) : gateKey.substring(start, end);
        return val.isEmpty() ? null : val;
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
            // 继续执行后续分支
        }
        return null;
    }

    /**
     * 从已提交的原生会话日志构造旧版界面历史记录结构。
     */
    private String readSessionLogContent(String urlAgentId, SessionEntry entry) {
        String gatewayAgentId = catalogService.peekGatewayAgentId(entry.userId(), urlAgentId);
        HarnessAgent ha =
                gatewayAgentId == null ? null : bootstrap.gateway().findAgent(gatewayAgentId);
        if (ha != null && entry.kind() == SessionKind.MAIN) {
            RuntimeContext rc =
                    RuntimeContext.builder()
                            .userId(entry.userId())
                            .sessionId(entry.sessionId())
                            .build();
            return SessionTranscriptExport.jsonl(ha.sessionTranscript(rc));
        }
        HistoryResult raw = sessionAgentManager.history(entry.sessionKey(), 0);
        return raw != null && raw.error() == null && raw.content() != null ? raw.content() : "";
    }

    // -----------------------------------------------------------------
    // 数据传输对象（DTO）
    // -----------------------------------------------------------------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record InboxEntry(
            String sessionKey,
            String sessionId,
            String agentId,
            String conversationId,
            String label,
            long lastActivityMs,
            String lastMessage,
            boolean unread) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ResetResult(String sessionKey, boolean reset) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ReadStateResult(String sessionKey, long readAtMs, boolean unread) {}
}
