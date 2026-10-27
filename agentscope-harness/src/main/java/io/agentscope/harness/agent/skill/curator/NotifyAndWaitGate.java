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
package io.agentscope.harness.agent.skill.curator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 多副本门控。{@code review} 时在草稿技能旁写入 {@code .review_request.json}，使最终接收批准调用的任意副本都能看到该文件，并并发触发全部已配置 {@link NotificationSink}。始终返回 {@link SkillPromotionGate.PromotionDecision.Defer}；外部系统可在任意副本调用 {@code HarnessAgent.promoteSkill(...)} 重新发起流程，但当前门控不会读取批准状态或自动返回批准；要真正晋级需另行接入批准决策。
 *
 * <p>通知接收端尽力而为：失败会记录日志，但不阻止门控或智能体继续。
 */
public class NotifyAndWaitGate implements SkillPromotionGate {

    private static final Logger log = LoggerFactory.getLogger(NotifyAndWaitGate.class);

    private static final ObjectMapper JSON =
            new ObjectMapper()
                    .registerModule(new JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .enable(SerializationFeature.INDENT_OUTPUT);

    private final List<NotificationSink> sinks;
    private final WorkspaceManager workspaceManager;
    private final String draftsDir;
    private final Duration retryAfter;

    public NotifyAndWaitGate(
            List<NotificationSink> sinks, WorkspaceManager workspaceManager, String draftsDir) {
        this(sinks, workspaceManager, draftsDir, Duration.ofHours(24));
    }

    public NotifyAndWaitGate(
            List<NotificationSink> sinks,
            WorkspaceManager workspaceManager,
            String draftsDir,
            Duration retryAfter) {
        this.sinks = sinks != null ? List.copyOf(sinks) : List.of();
        this.workspaceManager = workspaceManager;
        this.draftsDir = draftsDir != null && !draftsDir.isBlank() ? draftsDir : "skills/_drafts";
        this.retryAfter = retryAfter != null ? retryAfter : Duration.ofHours(24);
    }

    @Override
    public Mono<PromotionDecision> review(SkillCandidate candidate, RuntimeContext ctx) {
        return writeReviewRequest(candidate, ctx)
                .then(
                        Flux.fromIterable(sinks)
                                .flatMap(
                                        sink ->
                                                sink.notify(candidate)
                                                        .onErrorResume(
                                                                e -> {
                                                                    log.warn(
                                                                            "Notification sink {}"
                                                                                + " failed for"
                                                                                + " skill {}: {}",
                                                                            sink.getClass()
                                                                                    .getSimpleName(),
                                                                            candidate.name(),
                                                                            e.getMessage());
                                                                    return Mono.empty();
                                                                }))
                                .then())
                .thenReturn(
                        (PromotionDecision)
                                new PromotionDecision.Defer(
                                        retryAfter,
                                        "NotifyAndWaitGate: review_request.json written and"
                                                + " notifications dispatched; awaiting external"
                                                + " HarnessAgent.promoteSkill call"));
    }

    private Mono<Void> writeReviewRequest(SkillCandidate candidate, RuntimeContext ctx) {
        return Mono.fromRunnable(
                () -> {
                    if (workspaceManager == null) {
                        return;
                    }
                    try {
                        String json = JSON.writeValueAsString(candidate);
                        String relPath =
                                draftsDir + "/" + candidate.name() + "/.review_request.json";
                        workspaceManager.writeDraftSkillFile(ctx, relPath, json);
                    } catch (Exception e) {
                        log.warn(
                                "Failed to write review_request.json for skill {}: {}",
                                candidate.name(),
                                e.getMessage());
                    }
                });
    }
}
