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

package io.agentscope.extensions.judge.jev;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import reactor.core.publisher.Mono;

/**
 * 应用级判分器，要求所有必要准则通过；不授权也不执行动作。
 *
 * <p>【学习提示】判分先验证答案数量、类型及概率范围，再把概率投影到期望答案：期望为否时使用 1-p。FAIL 优先于 INCONCLUSIVE，只有全部必要准则通过才 PASS；ERROR 是后端/响应问题，不应混为质量不合格或自动许可。外层 timeout 包住整个后端 Publisher，因此包括其重试退避。
 */
public final class JevJudge {
    public enum Status {
        PASS,
        FAIL,
        INCONCLUSIVE,
        ERROR
    }

    public enum ErrorCode {
        NONE,
        TIMEOUT,
        INVALID_RESPONSE,
        BACKEND
    }

    /**
     * 概率阈值作用于期望答案，不一定总是 "yes"。
     */
    public record Criterion(
            String id,
            NoulQuestion question,
            boolean expected,
            double failAtOrBelow,
            double passAtOrAbove) {
        public Criterion {
            if (id == null || id.isBlank())
                throw new IllegalArgumentException("criterion id is required");
            Objects.requireNonNull(question, "question");
            if (!Double.isFinite(failAtOrBelow)
                    || !Double.isFinite(passAtOrAbove)
                    || failAtOrBelow < 0
                    || passAtOrAbove > 1
                    || failAtOrBelow >= passAtOrAbove) {
                throw new IllegalArgumentException("require 0 <= fail < pass <= 1");
            }
        }
    }

    /**
     * 不可变、带版本的评估定义；所有准则均为必需。
     */
    public record Definition(String version, List<Criterion> criteria) {
        public Definition {
            if (version == null || version.isBlank())
                throw new IllegalArgumentException("version is required");
            criteria = List.copyOf(criteria);
            if (criteria.isEmpty())
                throw new IllegalArgumentException("criteria must not be empty");
            if (criteria.stream().map(Criterion::id).distinct().count() != criteria.size()) {
                throw new IllegalArgumentException("duplicate criterion id");
            }
        }
    }

    public record Finding(Status status, double yesProbability, double expectedProbability) {}

    /**
     * 不生成解释：发现项仅含概率，绝不编造证据。
     */
    public record Result(
            String definitionVersion,
            Status status,
            Map<String, Finding> findings,
            ErrorCode error,
            String model,
            Usage usage,
            Duration elapsed) {
        public Result {
            findings = Map.copyOf(findings);
        }
    }

    private final Function<SystemOneRequest, Mono<SystemOneResult>> caller;
    private final Duration budget;

    public JevJudge(JevClient client, Duration budget) {
        this(Objects.requireNonNull(client, "client")::systemOne, budget);
    }

    /**
     * 调用方必须迅速返回非阻塞 Publisher；适合离线测试。
     */
    public JevJudge(Function<SystemOneRequest, Mono<SystemOneResult>> caller, Duration budget) {
        this.caller = Objects.requireNonNull(caller, "caller");
        this.budget = Objects.requireNonNull(budget, "budget");
        if (budget.isZero() || budget.isNegative())
            throw new IllegalArgumentException("budget must be positive");
    }

    /**
     * 每次订阅评估一次。预算包含客户端重试及退避。
     * 无效定义在组装时抛异常；后端错误转为 ERROR，取消继续传播。
     * 订阅完成前不得修改状态。
     */
    public Mono<Result> judge(Object state, Definition definition) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(definition, "definition");
        var builder = SystemOneRequest.builder().state(state);
        definition.criteria().forEach(c -> builder.question(c.id(), c.question()));
        var request = builder.build();
        return Mono.defer(
                () -> {
                    long started = System.nanoTime();
                    return Mono.defer(() -> caller.apply(request))
                            .switchIfEmpty(Mono.error(new InvalidResponse()))
                            .map(response -> evaluate(definition, response, started))
                            .timeout(budget)
                            .onErrorResume(
                                    error ->
                                            Mono.just(
                                                    new Result(
                                                            definition.version(),
                                                            Status.ERROR,
                                                            Map.of(),
                                                            error instanceof TimeoutException
                                                                    ? ErrorCode.TIMEOUT
                                                                    : error
                                                                                    instanceof
                                                                                    InvalidResponse
                                                                            ? ErrorCode
                                                                                    .INVALID_RESPONSE
                                                                            : ErrorCode.BACKEND,
                                                            null,
                                                            null,
                                                            elapsed(started))));
                });
    }

    private Result evaluate(Definition definition, SystemOneResult response, long started) {
        if (response.answers() == null
                || response.answers().size() != definition.criteria().size()) {
            throw new InvalidResponse();
        }
        Map<String, Finding> findings = new LinkedHashMap<>();
        Status overall = Status.PASS;
        for (Criterion criterion : definition.criteria()) {
            if (!(response.answers().get(criterion.id()) instanceof NoulAnswer answer)
                    || answer.noul() == null
                    || !Double.isFinite(answer.noul())
                    || answer.noul() < 0
                    || answer.noul() > 1) throw new InvalidResponse();
            double probability = criterion.expected() ? answer.noul() : 1 - answer.noul();
            Status status =
                    probability >= criterion.passAtOrAbove()
                            ? Status.PASS
                            : probability <= criterion.failAtOrBelow()
                                    ? Status.FAIL
                                    : Status.INCONCLUSIVE;
            findings.put(criterion.id(), new Finding(status, answer.noul(), probability));
            if (status == Status.FAIL) overall = Status.FAIL;
            else if (status == Status.INCONCLUSIVE && overall == Status.PASS)
                overall = Status.INCONCLUSIVE;
        }
        return new Result(
                definition.version(),
                overall,
                findings,
                ErrorCode.NONE,
                response.model(),
                response.usage(),
                elapsed(started));
    }

    private static Duration elapsed(long started) {
        return Duration.ofNanos(System.nanoTime() - started);
    }

    private static final class InvalidResponse extends RuntimeException {}
}
