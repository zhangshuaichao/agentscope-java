/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.studio;

import io.agentscope.core.message.Msg;
import io.agentscope.core.studio.pojo.PushMessageRequest;
import io.agentscope.core.studio.pojo.RegisterRunRequest;
import io.agentscope.core.studio.pojo.RequestUserInputRequest;
import io.agentscope.core.util.JsonCodec;
import io.agentscope.core.util.JsonUtils;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * 与 AgentScope Studio 通信的 HTTP 客户端。
 * <p>使用 OkHttp 调用 Studio REST API，提供注册运行、推送消息及请求用户输入的方法。操作采用响应式 Mono，便于集成 Project Reactor。
 * <p>失败请求包含自动重试逻辑，可通过 StudioConfig 配置。
 */
public class StudioClient {
    private static final Logger logger = LoggerFactory.getLogger(StudioClient.class);
    private static final MediaType JSON_MEDIA_TYPE =
            MediaType.get("application/json; charset=utf-8");
    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    private final OkHttpClient httpClient;
    private final StudioConfig config;
    private final JsonCodec jsonCodec;
    private final String baseUrl;

    /**
     * 创建 Studio HTTP 客户端。
     * @param config Studio 连接配置
     */
    public StudioClient(StudioConfig config) {
        this.config = config;
        this.baseUrl = config.getStudioUrl();
        this.jsonCodec = JsonUtils.getJsonCodec();
        this.httpClient =
                new OkHttpClient.Builder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .readTimeout(Duration.ofSeconds(30))
                        .writeTimeout(Duration.ofSeconds(30))
                        .build();
    }

    /**
     * 向 Studio 注册新运行。
     * <p>必须在发送消息前调用，以通知 Studio 新的智能体应用即将启动。
     * <p>POST /trpc/registerRun
     * @return 注册成功时完成的 Mono
     */
    public Mono<Void> registerRun() {
        return executeWithRetry(
                Mono.fromCallable(
                        () -> {
                            RegisterRunRequest payload =
                                    RegisterRunRequest.builder()
                                            .id(config.getRunId())
                                            .project(config.getProject())
                                            .name(config.getRunName())
                                            .timestamp(formatTimestamp(Instant.now()))
                                            .pid(ProcessHandle.current().pid())
                                            .status("running")
                                            .runDir("")
                                            .build();

                            String json = jsonCodec.toJson(payload);
                            RequestBody body = RequestBody.create(json, JSON_MEDIA_TYPE);

                            Request request =
                                    new Request.Builder()
                                            .url(baseUrl + "/trpc/registerRun")
                                            .post(body)
                                            .build();

                            try (Response response = httpClient.newCall(request).execute()) {
                                if (!response.isSuccessful()) {
                                    throw new IOException(
                                            "Failed to register run: HTTP " + response.code());
                                }
                            }
                            return null;
                        }));
    }

    /**
     * 推送消息到 Studio 进行可视化。通常由 StudioMessageHook 在智能体产生响应后自动调用，包含重试逻辑。
     * <p>POST /trpc/pushMessage
     * @param msg 待推送消息
     * @return 成功推送时完成的 Mono
     */
    public Mono<Void> pushMessage(Msg msg) {
        return executeWithRetry(
                Mono.fromCallable(
                        () -> {
                            String messageId = UUID.randomUUID().toString();

                            PushMessageRequest payload =
                                    PushMessageRequest.builder()
                                            .runId(config.getRunId())
                                            .replyId(messageId)
                                            .name(msg.getName() != null ? msg.getName() : messageId)
                                            .role(msg.getRole().name().toLowerCase())
                                            .msg(msg)
                                            .build();

                            String json = jsonCodec.toJson(payload);
                            RequestBody body = RequestBody.create(json, JSON_MEDIA_TYPE);

                            Request request =
                                    new Request.Builder()
                                            .url(baseUrl + "/trpc/pushMessage")
                                            .post(body)
                                            .build();

                            try (Response response = httpClient.newCall(request).execute()) {
                                if (!response.isSuccessful()) {
                                    throw new IOException(
                                            "HTTP " + response.code() + ": " + response.message());
                                }
                            }
                            return null;
                        }));
    }

    /**
     * 请求 Studio 网页界面的用户输入。Studio 收到请求后展示表单，实际输入通过 WebSocket 返回（参见 StudioWebSocketClient）。
     * <p>POST /trpc/requestUserInput
     * @param agentId 请求输入的智能体 ID
     * @param agentName 请求输入的智能体名称
     * @param structuredSchema 可选的结构化输入 JSON Schema
     * @return 包含请求 ID 的 Mono，用于匹配 WebSocket 响应
     */
    public Mono<String> requestUserInput(
            String agentId, String agentName, Object structuredSchema) {
        return executeWithRetry(
                Mono.fromCallable(
                        () -> {
                            String requestId = UUID.randomUUID().toString();

                            RequestUserInputRequest payload =
                                    RequestUserInputRequest.builder()
                                            .requestId(requestId)
                                            .runId(config.getRunId())
                                            .agentId(agentId)
                                            .agentName(agentName)
                                            .structuredInput(structuredSchema)
                                            .build();

                            String json = jsonCodec.toJson(payload);
                            RequestBody body = RequestBody.create(json, JSON_MEDIA_TYPE);

                            Request request =
                                    new Request.Builder()
                                            .url(baseUrl + "/trpc/requestUserInput")
                                            .post(body)
                                            .build();

                            try (Response response = httpClient.newCall(request).execute()) {
                                if (!response.isSuccessful()) {
                                    throw new IOException(
                                            "HTTP " + response.code() + ": " + response.message());
                                }
                                return requestId;
                            }
                        }));
    }

    /**
     * 关闭 HTTP 客户端并释放资源。
     */
    public void shutdown() {
        httpClient.dispatcher().executorService().shutdown();
        httpClient.connectionPool().evictAll();
    }

    /**
     * 执行 Studio API 操作并应用自动重试逻辑。
     * @param operation 待执行操作
     * @param <T> 返回类型
     * @return 应用重试逻辑后的 Mono
     */
    private <T> Mono<T> executeWithRetry(Mono<T> operation) {
        // 【学习提示】重试可能重复发送 HTTP 操作；这里提供传输重试，并不保证服务端业务恰好执行一次。
        return operation.retryWhen(
                Retry.backoff(config.getMaxRetries(), Duration.ofMillis(100))
                        .maxBackoff(Duration.ofSeconds(5))
                        .filter(throwable -> throwable instanceof IOException)
                        .doBeforeRetry(
                                signal ->
                                        logger.warn(
                                                "Retrying Studio API call, attempt: {}",
                                                signal.totalRetries() + 1)));
    }

    private String formatTimestamp(Instant instant) {
        return TIMESTAMP_FORMATTER.format(instant);
    }
}
