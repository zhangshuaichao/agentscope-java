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
package io.agentscope.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.transport.HttpTransportException;
import java.net.SocketException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import reactor.util.retry.Retry;

/**
 * 模型超时与重试功能单元测试。
 */
@DisplayName("Model Timeout and Retry Tests")
class ModelTimeoutRetryTest {

    @Test
    @DisplayName("Should timeout when model request exceeds configured duration")
    void testModelRequestTimeout() {
        // 创建延迟响应的模拟模型
        Model slowModel = createSlowModel(Duration.ofSeconds(5));

        ExecutionConfig executionConfig =
                ExecutionConfig.builder().timeout(Duration.ofMillis(100)).build();

        GenerateOptions options =
                GenerateOptions.builder().executionConfig(executionConfig).build();

        Msg testMsg =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("test").build())
                        .build();

        // 应超时
        StepVerifier.create(slowModel.stream(List.of(testMsg), null, options))
                .expectError(ModelException.class)
                .verify();
    }

    @Test
    @DisplayName("Should complete successfully when request is within timeout")
    void testModelRequestWithinTimeout() {
        Model fastModel = createFastModel();

        ExecutionConfig executionConfig =
                ExecutionConfig.builder().timeout(Duration.ofSeconds(10)).build();

        GenerateOptions options =
                GenerateOptions.builder().executionConfig(executionConfig).build();

        Msg testMsg =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("test").build())
                        .build();

        // 应成功完成
        StepVerifier.create(fastModel.stream(List.of(testMsg), null, options))
                .expectNextCount(1)
                .verifyComplete();
    }

    @Test
    @DisplayName("Should not apply timeout when executionConfig is null")
    void testNoTimeoutWhenNull() {
        Model slowModel = createSlowModel(Duration.ofMillis(500));

        GenerateOptions options = GenerateOptions.builder().temperature(0.7).build();

        Msg testMsg =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("test").build())
                        .build();

        // 未配置超时时，即使缓慢也应成功完成
        StepVerifier.create(slowModel.stream(List.of(testMsg), null, options))
                .expectNextCount(1)
                .verifyComplete();
    }

    @Test
    @DisplayName("Should retry failed requests according to ExecutionConfig")
    void testModelRetry() {
        AtomicInteger attemptCount = new AtomicInteger(0);

        // 创建失败两次后成功的模型
        Model flakyModel = createFlakyModel(attemptCount, 2);

        ExecutionConfig executionConfig = ExecutionConfig.builder().maxAttempts(3).build();

        GenerateOptions options =
                GenerateOptions.builder().executionConfig(executionConfig).build();

        Msg testMsg =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("test").build())
                        .build();

        // 重试后应成功
        StepVerifier.create(flakyModel.stream(List.of(testMsg), null, options))
                .expectNextCount(1)
                .verifyComplete();

        // 验证发生重试，总共 3 次尝试：首次 + 2 次重试
        assertEquals(3, attemptCount.get());
    }

    @Test
    @DisplayName("Should fail when all retry attempts are exhausted")
    void testRetryExhaustion() {
        AtomicInteger attemptCount = new AtomicInteger(0);

        // 创建始终失败的模型
        Model alwaysFailModel = createFlakyModel(attemptCount, Integer.MAX_VALUE);

        ExecutionConfig executionConfig =
                ExecutionConfig.builder()
                        .maxAttempts(3)
                        .initialBackoff(Duration.ofMillis(10))
                        .build();

        GenerateOptions options =
                GenerateOptions.builder().executionConfig(executionConfig).build();

        Msg testMsg =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("test").build())
                        .build();

        // 所有重试后应失败
        StepVerifier.create(alwaysFailModel.stream(List.of(testMsg), null, options))
                .expectError(RuntimeException.class)
                .verify();

        // 验证尝试 maxAttempts 次
        assertEquals(3, attemptCount.get());
    }

    @Test
    @DisplayName("Should respect retryOn predicate filter")
    void testRetryOnPredicate() {
        AtomicInteger attemptCount = new AtomicInteger(0);

        // 创建抛 IllegalArgumentException 的模型
        Model model =
                new Model() {
                    @Override
                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        attemptCount.incrementAndGet();
                        return Flux.error(new IllegalArgumentException("test error"));
                    }

                    @Override
                    public String getModelName() {
                        return "test";
                    }
                };

        // 仅对 ModelException 重试，不对 IllegalArgumentException 重试
        ExecutionConfig executionConfig =
                ExecutionConfig.builder()
                        .maxAttempts(5)
                        .retryOn(error -> error instanceof ModelException)
                        .build();

        GenerateOptions options =
                GenerateOptions.builder().executionConfig(executionConfig).build();

        Msg testMsg =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("test").build())
                        .build();

        // 应立即失败，不重试
        StepVerifier.create(model.stream(List.of(testMsg), null, options))
                .expectError(IllegalArgumentException.class)
                .verify();

        // 仅尝试一次，错误不匹配谓词因而不重试
        assertEquals(1, attemptCount.get());
    }

    @Test
    @DisplayName("Should not retry when executionConfig is null")
    void testNoRetryWhenNull() {
        AtomicInteger attemptCount = new AtomicInteger(0);

        Model flakyModel = createFlakyModel(attemptCount, 1);

        GenerateOptions options = GenerateOptions.builder().temperature(0.7).build();

        Msg testMsg =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("test").build())
                        .build();

        // 应立即失败，不重试
        StepVerifier.create(flakyModel.stream(List.of(testMsg), null, options))
                .expectError(RuntimeException.class)
                .verify();

        // 仅尝试一次
        assertEquals(1, attemptCount.get());
    }

    @Test
    @DisplayName("Should retry connection reset before the first response is emitted")
    void shouldRetryConnectionResetBeforeFirstResponseEmitted() {
        AtomicInteger attemptCount = new AtomicInteger(0);

        // 首次尝试未发出任何内容就连接重置，复现 #3057；
        // 重试打开新连接并成功。
        Flux<ChatResponse> source =
                Flux.defer(
                        () -> {
                            if (attemptCount.incrementAndGet() == 1) {
                                return Flux.error(
                                        new HttpTransportException(
                                                "SSE/NDJSON stream failed: Connection reset",
                                                new SocketException("Connection reset")));
                            }
                            return Flux.just(createMockResponse());
                        });

        ExecutionConfig executionConfig =
                ExecutionConfig.builder()
                        .maxAttempts(3)
                        .initialBackoff(Duration.ofMillis(10))
                        .build();
        GenerateOptions options =
                GenerateOptions.builder().executionConfig(executionConfig).build();

        StepVerifier.create(
                        ModelUtils.applyTimeoutAndRetry(
                                source, options, null, "test-model", "test"))
                .expectNextCount(1)
                .verifyComplete();

        // 首次失败前没有发出内容，因此允许重试
        assertEquals(2, attemptCount.get());
    }

    @Test
    @DisplayName("Should not retry after a response has already been emitted")
    void shouldNotRetryAfterResponseEmitted() {
        AtomicInteger attemptCount = new AtomicInteger(0);

        // 每次尝试都先发出一个响应块，再连接重置：
        // 重试会重新发出请求并重复已交付块。
        Flux<ChatResponse> source =
                Flux.defer(
                        () -> {
                            attemptCount.incrementAndGet();
                            return Flux.concat(
                                    Flux.just(createMockResponse()),
                                    Flux.error(
                                            new HttpTransportException(
                                                    "SSE/NDJSON stream failed: Connection reset",
                                                    new SocketException("Connection reset"))));
                        });

        ExecutionConfig executionConfig =
                ExecutionConfig.builder()
                        .maxAttempts(3)
                        .initialBackoff(Duration.ofMillis(10))
                        .build();
        GenerateOptions options =
                GenerateOptions.builder().executionConfig(executionConfig).build();

        // 先交付已发出块，再传播错误，不重试
        StepVerifier.create(
                        ModelUtils.applyTimeoutAndRetry(
                                source, options, null, "test-model", "test"))
                .expectNextCount(1)
                .expectError(HttpTransportException.class)
                .verify();

        assertEquals(1, attemptCount.get());
    }

    @Test
    @DisplayName("Should retry after role-only chunks that carry no visible content")
    void shouldRetryAfterEmptyContentChunks() {
        AtomicInteger attemptCount = new AtomicInteger(0);

        // 首次尝试在连接重置前发出仅角色、无内容块的片段；
        // 没有交付用户可见内容，因此重试不会重复内容。
        Flux<ChatResponse> source =
                Flux.defer(
                        () -> {
                            if (attemptCount.incrementAndGet() == 1) {
                                return Flux.concat(
                                        Flux.just(
                                                new ChatResponse(
                                                        "role-only-chunk",
                                                        List.of(),
                                                        null,
                                                        null,
                                                        null)),
                                        Flux.error(
                                                new HttpTransportException(
                                                        "SSE/NDJSON stream failed: Connection"
                                                                + " reset",
                                                        new SocketException("Connection reset"))));
                            }
                            return Flux.just(createMockResponse());
                        });

        ExecutionConfig executionConfig =
                ExecutionConfig.builder()
                        .maxAttempts(3)
                        .initialBackoff(Duration.ofMillis(10))
                        .build();
        GenerateOptions options =
                GenerateOptions.builder().executionConfig(executionConfig).build();

        StepVerifier.create(
                        ModelUtils.applyTimeoutAndRetry(
                                source, options, null, "test-model", "test"))
                // 第一次尝试的仅角色块 + 第二次尝试的完整响应
                .expectNextCount(2)
                .verifyComplete();

        // 仅角色块不带可见内容，因此允许重试
        assertEquals(2, attemptCount.get());
    }

    // 创建测试模型的辅助方法

    /**
     * 创建延迟指定时长响应的模拟模型，用于测试超时行为。
     *
     * @param delay 响应前延迟时长
     * @return 延迟响应的 Model 实例
     */
    private Model createSlowModel(Duration delay) {
        return new Model() {
            @Override
            public Flux<ChatResponse> stream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                Flux<ChatResponse> responseFlux =
                        Flux.just(createMockResponse()).delayElements(delay).next().flux();
                return applyTimeoutAndRetry(responseFlux, options);
            }

            @Override
            public String getModelName() {
                return "slow-model";
            }
        };
    }

    /**
     * 创建立即响应、无延迟的模拟模型，用于测试超时范围内成功操作。
     *
     * @return 立即响应的 Model 实例
     */
    private Model createFastModel() {
        return new Model() {
            @Override
            public Flux<ChatResponse> stream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                Flux<ChatResponse> responseFlux = Flux.just(createMockResponse());
                return applyTimeoutAndRetry(responseFlux, options);
            }

            @Override
            public String getModelName() {
                return "fast-model";
            }
        };
    }

    /**
     * 创建失败指定次数后成功的模拟模型，用于测试重试行为和重试耗尽。
     *
     * @param attemptCount 跟踪尝试次数的原子计数器
     * @param failCount 成功前模型应失败的次数
     * @return 前 failCount 次失败、随后成功的 Model 实例
     */
    private Model createFlakyModel(AtomicInteger attemptCount, int failCount) {
        return new Model() {
            @Override
            public Flux<ChatResponse> stream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                Flux<ChatResponse> responseFlux =
                        Flux.defer(
                                () -> {
                                    int attempt = attemptCount.incrementAndGet();
                                    if (attempt <= failCount) {
                                        return Flux.error(
                                                new RuntimeException(
                                                        "Simulated failure " + attempt));
                                    }
                                    return Flux.just(createMockResponse());
                                });
                return applyTimeoutAndRetry(responseFlux, options);
            }

            @Override
            public String getModelName() {
                return "flaky-model";
            }
        };
    }

    /**
     * 对响应 Flux 应用超时与重试逻辑的辅助方法，模拟提供方扩展模型中的行为。
     */
    private Flux<ChatResponse> applyTimeoutAndRetry(
            Flux<ChatResponse> responseFlux, GenerateOptions options) {
        if (options == null) {
            return responseFlux;
        }

        ExecutionConfig executionConfig = options.getExecutionConfig();
        if (executionConfig == null) {
            return responseFlux;
        }

        // 配置超时时应用超时
        Duration timeout = executionConfig.getTimeout();
        if (timeout != null) {
            responseFlux =
                    responseFlux.timeout(
                            timeout,
                            Flux.error(
                                    new ModelException(
                                            "Model request timeout after " + timeout,
                                            "test-model",
                                            "test")));
        }

        // 配置重试时应用重试
        Integer maxAttempts = executionConfig.getMaxAttempts();
        if (maxAttempts != null && maxAttempts > 1) {
            Duration initialBackoff = executionConfig.getInitialBackoff();
            Duration maxBackoff = executionConfig.getMaxBackoff();
            Predicate<Throwable> retryOn = executionConfig.getRetryOn();

            // 未指定时使用默认值
            if (initialBackoff == null) {
                initialBackoff = Duration.ofSeconds(1);
            }
            if (maxBackoff == null) {
                maxBackoff = Duration.ofSeconds(10);
            }
            if (retryOn == null) {
                retryOn = error -> true; // 默认重试所有错误
            }

            Retry retrySpec =
                    Retry.backoff(maxAttempts - 1, initialBackoff)
                            .maxBackoff(maxBackoff)
                            .jitter(0.5)
                            .filter(retryOn);
            responseFlux = responseFlux.retryWhen(retrySpec);
        }

        return responseFlux;
    }

    private ChatResponse createMockResponse() {
        return new ChatResponse(
                "test-id",
                List.of(TextBlock.builder().text("test response").build()),
                null,
                null,
                null);
    }
}
