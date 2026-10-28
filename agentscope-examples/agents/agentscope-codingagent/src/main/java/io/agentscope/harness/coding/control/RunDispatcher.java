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
package io.agentscope.harness.coding.control;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.gateway.Gateway;
import io.agentscope.harness.agent.gateway.MsgContext;
import io.agentscope.harness.coding.middleware.MessageQueueMiddleware;
import io.agentscope.harness.coding.observability.CodingAgentMetrics;
import io.micrometer.core.instrument.Timer;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 分发智能体运行，处理繁忙线程排队。
 * <ul>
 * <li>线程空闲时通过 {@link Gateway#run} 立即分发</li>
 * <li>线程繁忙时将消息加入 {@code SqliteBaseStore} 队列，由 {@link MessageQueueMiddleware} 在下一推理步骤注入</li>
 * </ul>
 */
public class RunDispatcher {

    private static final Logger log = LoggerFactory.getLogger(RunDispatcher.class);

    private final Gateway gateway;
    private final BaseStore store;
    private final CodingAgentMetrics metrics;

    public RunDispatcher(Gateway gateway, BaseStore store) {
        this(gateway, store, null);
    }

    public RunDispatcher(Gateway gateway, BaseStore store, CodingAgentMetrics metrics) {
        this.gateway = gateway;
        this.store = store;
        this.metrics = metrics;
    }

    /**
     * 不含 {@code userId} 的向后兼容重载。
     */
    public Mono<Void> dispatch(String threadId, String agentId, String prompt, String githubToken) {
        return dispatch(threadId, agentId, prompt, githubToken, null);
    }

    /**
     * 分发智能体运行；线程繁忙时将消息入队。
     *
     * @param threadId 规范线程 ID，在上下文中用作 sessionId
     * @param agentId 目标智能体，例如 "coding" 或 "reviewer"
     * @param prompt 用户提示词 / webhook 事件描述
     * @param githubToken 此会话可选的已解密 GitHub 令牌
     * @param userId 可选已认证用户身份，传播到 {@link MsgContext} 以实现 HarnessAgent 命名空间隔离
     */
    public Mono<Void> dispatch(
            String threadId, String agentId, String prompt, String githubToken, String userId) {
        Msg userMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text(prompt).build())
                        .build();

        MsgContext ctx =
                new MsgContext(
                        "webhook",
                        null,
                        null,
                        threadId,
                        null,
                        agentId != null ? Map.of("agentId", agentId) : Map.of(),
                        userId);
        if (githubToken != null && !githubToken.isBlank()) {
            ctx =
                    new MsgContext(
                            ctx.channel(),
                            ctx.group(),
                            ctx.room(),
                            ctx.threadId(),
                            ctx.threadTs(),
                            mergeMaps(ctx.extra(), Map.of("github_token", githubToken)),
                            ctx.userId());
        }
        final MsgContext finalCtx = ctx;

        if (metrics != null) {
            metrics.recordDispatch();
        }
        Timer.Sample sample = metrics != null ? Timer.start() : null;

        return Mono.fromCallable(
                        () -> {
                            MessageQueueMiddleware.CURRENT_THREAD_ID.set(threadId);
                            return threadId;
                        })
                .flatMap(
                        id -> {
                            try {
                                return gateway.run(finalCtx, List.of(userMsg)).then();
                            } finally {
                                MessageQueueMiddleware.CURRENT_THREAD_ID.remove();
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .doFinally(
                        s -> {
                            if (sample != null) {
                                sample.stop(metrics.getDispatchDuration());
                            }
                        })
                .onErrorResume(
                        e -> {
                            if (metrics != null) {
                                metrics.recordDispatchError();
                            }
                            log.error(
                                    "[dispatcher] Run failed for thread={} agent={}: {}",
                                    threadId,
                                    agentId,
                                    e.getMessage(),
                                    e);
                            return Mono.empty();
                        });
    }

    /**
     * 将消息入队，供下次推理步骤注入。
     *
     * @param threadId 目标线程
     * @param payload 消息载荷文本
     */
    public void enqueue(String threadId, String payload) {
        String key = String.valueOf(System.currentTimeMillis()) + "-" + UUID.randomUUID();
        store.put(List.of("queue", threadId), key, Map.of("payload", payload));
        log.debug("[dispatcher] Enqueued message for thread={}", threadId);
    }

    /**
     * 对 PR URL 分发审查智能体，由 {@link io.agentscope.harness.coding.tools.RequestPrReviewTool} 与 CLI {@code review} 子命令使用。
     *
     * @param prUrl 完整 GitHub PR URL
     */
    public Mono<Void> dispatchReviewer(String prUrl) {
        String threadId =
                ThreadIdFactory.fromGitHubReviewer(
                        extractOwner(prUrl), extractRepo(prUrl), extractPrNumber(prUrl));
        String prompt =
                "Please review the following pull request and provide detailed feedback: " + prUrl;
        return dispatch(threadId, "reviewer", prompt, null);
    }

    // -----------------------------------------------------------------
    // PR URL 解析辅助方法
    // -----------------------------------------------------------------

    static String extractOwner(String prUrl) {
        String[] parts = prUrl.replaceAll("https://github\\.com/", "").split("/");
        return parts.length > 0 ? parts[0] : "unknown";
    }

    static String extractRepo(String prUrl) {
        String[] parts = prUrl.replaceAll("https://github\\.com/", "").split("/");
        return parts.length > 1 ? parts[1] : "unknown";
    }

    static int extractPrNumber(String prUrl) {
        try {
            String[] parts = prUrl.split("/");
            return Integer.parseInt(parts[parts.length - 1]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static Map<String, String> mergeMaps(
            Map<String, String> base, Map<String, String> extra) {
        java.util.LinkedHashMap<String, String> merged = new java.util.LinkedHashMap<>(base);
        merged.putAll(extra);
        return java.util.Collections.unmodifiableMap(merged);
    }
}
