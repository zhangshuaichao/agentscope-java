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

import io.agentscope.builder.web.coord.CoordinationStore;
import io.agentscope.builder.web.managed.DataSessionService;
import io.agentscope.builder.web.managed.EnvironmentWorkQueue;
import io.agentscope.builder.web.managed.ManagedSessionDto;
import io.agentscope.builder.web.managed.selfhosted.SessionInputStager;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * 供 {@code self_hosted} 环境进程外 Environment Worker 使用的 REST 接口。
 * Worker 通过 {@link #poll} 长轮询、{@link #ack} 确认、心跳以及 {@link #stop} 停止。
 * 工具执行采用事件驱动（待处理工具及工具结果），参见 {@link SelfHostedWorkerController}。
 * <p>Worker 操作通过 {@code X-Builder-Environment-Key} 认证；列表、统计和获取操作要求用户 JWT。
 */
@RestController
@RequestMapping("/api/environments/{id}")
public class WorkerEnvironmentController {

    private final EnvironmentWorkQueue workQueue;
    private final DataSessionService sessionService;

    public WorkerEnvironmentController(
            EnvironmentWorkQueue workQueue, DataSessionService sessionService) {
        this.workQueue = workQueue;
        this.sessionService = sessionService;
    }

    /**
     * 确认请求体：可选 Worker ID 与工作区目录。
     */
    public record AckRequest(String workerId, String workDir) {}

    /**
     * 可选工作项元数据更新的请求体。
     */
    public record UpdateWorkRequest(String workerId, String workDir) {}

    /**
     * 长轮询下一个工作项；在 {@code timeoutMs} 内未领取到工作时返回 204。
     */
    @GetMapping("/work/poll")
    public Mono<ResponseEntity<EnvironmentWorkQueue.WorkItem>> poll(
            @PathVariable("id") String environmentId,
            @RequestParam("workerId") String workerId,
            @RequestParam(name = "timeoutMs", defaultValue = "25000") long timeoutMs) {
        return Mono.fromCallable(
                () -> {
                    try {
                        Optional<EnvironmentWorkQueue.WorkItem> item =
                                workQueue.poll(environmentId, workerId, timeoutMs);
                        return item.map(this::withSessionMetadata)
                                .map(ResponseEntity::ok)
                                .orElseGet(() -> ResponseEntity.noContent().build());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new ResponseStatusException(
                                HttpStatus.SERVICE_UNAVAILABLE, "Poll interrupted");
                    }
                });
    }

    private EnvironmentWorkQueue.WorkItem withSessionMetadata(EnvironmentWorkQueue.WorkItem item) {
        try {
            ManagedSessionDto session = sessionService.requireById(item.sessionId());
            Map<String, Object> metadata =
                    SessionInputStager.metadataFromResources(session.resources());
            return item.withMetadata(metadata);
        } catch (Exception ex) {
            return item;
        }
    }

    /**
     * 列出环境工作项，可按 {@code state} 过滤。
     */
    @GetMapping("/work")
    public Mono<List<EnvironmentWorkQueue.WorkItem>> listWork(
            @PathVariable("id") String environmentId,
            @RequestParam(value = "state", required = false) String state,
            Authentication auth) {
        requireUserAuth(auth);
        return Mono.fromCallable(() -> workQueue.list(environmentId, state));
    }

    /**
     * 返回环境各状态的数量及最早排队项的等待时长。
     */
    @GetMapping("/work/stats")
    public Mono<CoordinationStore.WorkStats> workStats(
            @PathVariable("id") String environmentId, Authentication auth) {
        requireUserAuth(auth);
        return Mono.fromCallable(() -> workQueue.stats(environmentId));
    }

    /**
     * 根据 ID 返回单个工作项。
     */
    @GetMapping("/work/{workId}")
    public Mono<EnvironmentWorkQueue.WorkItem> getWork(
            @PathVariable("id") String environmentId,
            @PathVariable("workId") String workId,
            Authentication auth) {
        requireUserAuth(auth);
        return Mono.fromCallable(
                () ->
                        workQueue
                                .get(workId)
                                .orElseThrow(
                                        () ->
                                                new ResponseStatusException(
                                                        HttpStatus.NOT_FOUND,
                                                        "Unknown work item: " + workId)));
    }

    /**
     * 确认已轮询领取的工作项并将行标记为活动。在事件驱动的自行托管模式下，Brain 不创建本地沙箱；Worker 在进程外执行工具并提交 {@code user.tool_result}。
     */
    @PostMapping("/work/{workId}/ack")
    public Mono<Void> ack(
            @PathVariable("id") String environmentId,
            @PathVariable("workId") String workId,
            @RequestBody(required = false) AckRequest req) {
        return Mono.fromRunnable(
                () -> {
                    EnvironmentWorkQueue.WorkItem item =
                            workQueue
                                    .get(workId)
                                    .orElseThrow(
                                            () ->
                                                    new ResponseStatusException(
                                                            HttpStatus.NOT_FOUND,
                                                            "Unknown work item: " + workId));
                    String workerId =
                            req != null && req.workerId() != null && !req.workerId().isBlank()
                                    ? req.workerId()
                                    : item.claimedBy() != null ? item.claimedBy() : "remote-worker";
                    String workDir =
                            req != null && req.workDir() != null && !req.workDir().isBlank()
                                    ? req.workDir()
                                    : defaultWorkDir(item.sessionId()).toString();
                    workQueue.ack(workId, workerId, workDir);
                });
    }

    /**
     * 更新活动工作项的 {@code updated_at}（租约心跳）。
     */
    @PostMapping("/work/{workId}/heartbeat")
    public Mono<Void> heartbeat(
            @PathVariable("id") String environmentId, @PathVariable("workId") String workId) {
        return Mono.fromRunnable(() -> workQueue.heartbeat(workId));
    }

    /**
     * 会话轮次释放沙箱后，将工作项标记为停止。
     */
    @PostMapping("/work/{workId}/stop")
    public Mono<Void> stop(
            @PathVariable("id") String environmentId, @PathVariable("workId") String workId) {
        return Mono.fromRunnable(() -> workQueue.stop(workId));
    }

    /**
     * 可选地更新现有工作项元数据。
     */
    @PostMapping("/work/{workId}")
    public Mono<Void> updateWork(
            @PathVariable("id") String environmentId,
            @PathVariable("workId") String workId,
            @RequestBody(required = false) UpdateWorkRequest req) {
        return Mono.fromRunnable(
                () -> {
                    EnvironmentWorkQueue.WorkItem item =
                            workQueue
                                    .get(workId)
                                    .orElseThrow(
                                            () ->
                                                    new ResponseStatusException(
                                                            HttpStatus.NOT_FOUND,
                                                            "Unknown work item: " + workId));
                    String workerId =
                            req != null && req.workerId() != null
                                    ? req.workerId()
                                    : item.claimedBy();
                    String workDir =
                            req != null && req.workDir() != null ? req.workDir() : item.workDir();
                    workQueue.ack(workId, workerId, workDir);
                });
    }

    private static void requireUserAuth(Authentication auth) {
        if (auth == null || auth.getPrincipal() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        String principal = String.valueOf(auth.getPrincipal());
        if (principal.startsWith("env:")) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Environment worker credentials cannot list work");
        }
    }

    private static Path defaultWorkDir(String sessionId) {
        return Paths.get(System.getProperty("java.io.tmpdir"), "agentscope-hands", sessionId);
    }
}
