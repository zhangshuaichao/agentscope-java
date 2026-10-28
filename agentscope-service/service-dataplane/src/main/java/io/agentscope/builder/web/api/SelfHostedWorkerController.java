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
package io.agentscope.builder.web.api;

import io.agentscope.builder.web.api.error.ApiException;
import io.agentscope.builder.web.managed.DataSessionService;
import io.agentscope.builder.web.managed.ManagedSessionDto;
import io.agentscope.builder.web.managed.SessionEventDto;
import io.agentscope.builder.web.managed.SessionTurnRunner;
import io.agentscope.builder.web.managed.selfhosted.PendingHandsToolService;
import io.agentscope.builder.web.managed.selfhosted.SkillsBundleService;
import io.agentscope.core.message.ToolResultBlock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * {@code self_hosted} 的出站 Worker 数据平面：待处理 tool_use 列表、tool_result 恢复及技能下载。通过 {@code X-Builder-Environment-Key} 认证。
 */
@RestController
@RequestMapping("/api/environments/{id}/sessions/{sessionId}")
public class SelfHostedWorkerController {

    private final DataSessionService sessionService;
    private final PendingHandsToolService pendingHandsToolService;
    private final SkillsBundleService skillsBundleService;
    private final SessionTurnRunner turnRunner;

    public SelfHostedWorkerController(
            DataSessionService sessionService,
            PendingHandsToolService pendingHandsToolService,
            SkillsBundleService skillsBundleService,
            SessionTurnRunner turnRunner) {
        this.sessionService = sessionService;
        this.pendingHandsToolService = pendingHandsToolService;
        this.skillsBundleService = skillsBundleService;
        this.turnRunner = turnRunner;
    }

    /**
     * 列出等待 Worker 结果的 {@code agent.tool_use} 事件。
     */
    @GetMapping("/pending-tools")
    public Mono<List<Map<String, Object>>> pendingTools(
            @PathVariable("id") String environmentId,
            @PathVariable("sessionId") String sessionId,
            Authentication auth) {
        return Mono.fromCallable(
                () -> {
                    requireEnvironmentWorker(auth, environmentId, sessionId);
                    return pendingHandsToolService.listPending(sessionId);
                });
    }

    /**
     * 提交一个或多个工具结果并恢复挂起轮次。
     */
    @PostMapping("/tool-results")
    public Mono<List<SessionEventDto>> toolResults(
            @PathVariable("id") String environmentId,
            @PathVariable("sessionId") String sessionId,
            @RequestBody ToolResultsRequest body,
            Authentication auth) {
        return Mono.fromCallable(
                () -> {
                    ManagedSessionDto session =
                            requireEnvironmentWorker(auth, environmentId, sessionId);
                    if (body == null || body.results() == null || body.results().isEmpty()) {
                        throw ApiException.invalidRequest(
                                "missing_results", "results is required", "results");
                    }
                    List<ToolResultBlock> blocks = new ArrayList<>();
                    List<SessionEventDto> recorded = new ArrayList<>();
                    for (Map<String, Object> payload : body.results()) {
                        ToolResultBlock block = SessionTurnRunner.toolResultFromPayload(payload);
                        blocks.add(block);
                        recorded.add(
                                new SessionEventDto(
                                        null,
                                        sessionId,
                                        -1L,
                                        "session.input_accepted",
                                        Map.of("status", "accepted", "tool_use_id", block.getId()),
                                        null,
                                        System.currentTimeMillis()));
                    }
                    turnRunner.resumeWithToolResults(session, blocks);
                    return recorded;
                });
    }

    /**
     * 下载会话智能体技能包，供 Worker 本地暂存。
     */
    @GetMapping("/skills")
    public Mono<Map<String, Object>> skills(
            @PathVariable("id") String environmentId,
            @PathVariable("sessionId") String sessionId,
            Authentication auth) {
        return Mono.fromCallable(
                () -> {
                    requireEnvironmentWorker(auth, environmentId, sessionId);
                    return skillsBundleService.bundleForSession(sessionId);
                });
    }

    private ManagedSessionDto requireEnvironmentWorker(
            Authentication auth, String environmentId, String sessionId) {
        if (auth == null || auth.getPrincipal() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        String principal = String.valueOf(auth.getPrincipal());
        if (!principal.equals("env:" + environmentId)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Environment worker credentials required");
        }
        ManagedSessionDto session = sessionService.requireById(sessionId);
        if (session.environmentId() == null || !session.environmentId().equals(environmentId)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Session does not belong to this environment");
        }
        return session;
    }

    /**
     * 提交工具结果的请求体。
     */
    public record ToolResultsRequest(List<Map<String, Object>> results) {}
}
