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

import io.agentscope.core.agent.RuntimeContext;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * 单副本 HITL（人在回路）门控。调用用户提供的提示函数，根据人工输入（终端、CLI、IDE 提示等）返回批准或拒绝。提示函数超时则延后。
 *
 * <p>默认构造方法接入始终延后的空操作提示器。实际部署应提供连接到可用 HITL 界面的提示器，通常可通过 {@code RequestStopEvent} 让智能体外层循环暂停等待提示。
 */
public class LocalApprovalGate implements SkillPromotionGate {

    private static final Logger log = LoggerFactory.getLogger(LocalApprovalGate.class);

    @FunctionalInterface
    public interface Prompter
            extends Function<SkillCandidate, CompletableFuture<PromotionDecision>> {}

    private final Duration timeout;
    private final Prompter prompter;
    private final List<String> targetEnvironments;

    public LocalApprovalGate() {
        this(Duration.ofMinutes(30), defaultPrompter(), List.of("prod"));
    }

    public LocalApprovalGate(Duration timeout) {
        this(timeout, defaultPrompter(), List.of("prod"));
    }

    public LocalApprovalGate(Duration timeout, Prompter prompter, List<String> targetEnvironments) {
        this.timeout = timeout != null ? timeout : Duration.ofMinutes(30);
        this.prompter = prompter != null ? prompter : defaultPrompter();
        this.targetEnvironments =
                targetEnvironments != null ? List.copyOf(targetEnvironments) : List.of("prod");
    }

    @Override
    public Mono<PromotionDecision> review(SkillCandidate candidate, RuntimeContext ctx) {
        return Mono.fromFuture(prompter.apply(candidate))
                .timeout(timeout)
                .onErrorResume(
                        e -> {
                            log.warn(
                                    "LocalApprovalGate prompter failed/timeout for skill={}: {}",
                                    candidate.name(),
                                    e.getMessage());
                            return Mono.just(
                                    new PromotionDecision.Defer(
                                            Duration.ofHours(1),
                                            "LocalApprovalGate: prompter timeout/error"));
                        });
    }

    /**
     * 默认提示器：从不批准。适合测试或尚未接入提示器时使用。
     */
    public static Prompter defaultPrompter() {
        return candidate ->
                CompletableFuture.completedFuture(
                        new PromotionDecision.Defer(
                                Duration.ofHours(24), "LocalApprovalGate has no prompter wired"));
    }

    /**
     * 示例提示器：从 stdin 读取 yes/no，适合 CLI 演示与测试。
     */
    public static Prompter stdinPrompter(java.io.InputStream in, java.io.PrintStream out) {
        return candidate -> {
            CompletableFuture<PromotionDecision> f = new CompletableFuture<>();
            new Thread(
                            () -> {
                                try {
                                    out.println(
                                            "[skill-promote] approve skill '"
                                                    + candidate.name()
                                                    + "'? [y/N]");
                                    var reader =
                                            new java.io.BufferedReader(
                                                    new java.io.InputStreamReader(in));
                                    String line = reader.readLine();
                                    if (line != null && line.trim().equalsIgnoreCase("y")) {
                                        f.complete(
                                                new PromotionDecision.Approve(
                                                        "stdin-user",
                                                        List.of("prod"),
                                                        Instant.now()));
                                    } else {
                                        f.complete(
                                                new PromotionDecision.Reject(
                                                        "user said no", "stdin-user"));
                                    }
                                } catch (Exception e) {
                                    f.complete(
                                            new PromotionDecision.Defer(
                                                    Duration.ofMinutes(5), e.getMessage()));
                                }
                            },
                            "skill-promote-stdin")
                    .start();
            return f.orTimeout(30, TimeUnit.MINUTES);
        };
    }

    public List<String> getTargetEnvironments() {
        return targetEnvironments;
    }
}
