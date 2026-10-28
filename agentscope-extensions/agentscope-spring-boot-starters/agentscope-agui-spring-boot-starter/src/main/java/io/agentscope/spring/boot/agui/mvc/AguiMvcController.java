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
package io.agentscope.spring.boot.agui.mvc;

import io.agentscope.core.agui.AguiException;
import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.adapter.AguiAgentAdapterFactory;
import io.agentscope.core.agui.encoder.AguiEventEncoder;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.agui.processor.AguiRequestProcessor;
import io.agentscope.core.agui.registry.AguiAgentRegistry;
import io.agentscope.core.agui.runtime.AguiRuntimeContextRequest;
import io.agentscope.core.agui.runtime.AguiRuntimeContextResolver;
import io.agentscope.spring.boot.agui.common.DefaultAgentResolver;
import io.agentscope.spring.boot.agui.common.ThreadSessionManager;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.BaseSubscriber;

/**
 * AG-UI 协议请求的 MVC 控制器。
 *
 * <p>处理 AG-UI 运行请求，通过 Spring MVC 的 {@link SseEmitter} 返回包含 AG-UI 协议事件的服务器发送事件（SSE）流。
 *
 * <p><b>智能体 ID 解析优先级：</b>
 * <ol>
 *   <li>URL 路径变量：{@code /agui/run/{agentId}}</li>
 *   <li>HTTP 请求头：由 {@code agentIdHeader} 配置（默认 X-Agent-Id）</li>
 *   <li>请求体中的 forwardedProps.agentId</li>
 *   <li>config.defaultAgentId</li>
 *   <li>"default"</li>
 * </ol>
 *
 * <p><b>用法：</b>
 * <pre>{@code
 * AguiMvcController controller = AguiMvcController.builder()
 *     .agentRegistry(registry)
 *     .config(AguiAdapterConfig.defaultConfig())
 *     .agentIdHeader("X-Agent-Id")
 *     .build();
 * }</pre>
 */
public class AguiMvcController {

    private static final Logger logger = LoggerFactory.getLogger(AguiMvcController.class);

    private static final String DEFAULT_AGENT_ID_HEADER = "X-Agent-Id";

    private final AguiRequestProcessor processor;
    private final AguiEventEncoder encoder;
    private final String agentIdHeader;
    private final long sseTimeout;
    private final boolean interruptOnDisconnect;
    private final ExecutorService executorService;

    private AguiMvcController(Builder builder) {
        this.processor =
                AguiRequestProcessor.builder()
                        .agentResolver(
                                DefaultAgentResolver.builder()
                                        .registry(builder.registry)
                                        .sessionManager(builder.sessionManager)
                                        .serverSideMemory(builder.serverSideMemory)
                                        .build())
                        .config(
                                builder.config != null
                                        ? builder.config
                                        : AguiAdapterConfig.defaultConfig())
                        .adapterFactory(builder.adapterFactory)
                        .runtimeContextResolver(builder.runtimeContextResolver)
                        .build();
        this.encoder = new AguiEventEncoder();
        this.agentIdHeader =
                builder.agentIdHeader != null ? builder.agentIdHeader : DEFAULT_AGENT_ID_HEADER;
        this.sseTimeout = builder.sseTimeout > 0 ? builder.sseTimeout : 600000L;
        this.interruptOnDisconnect = builder.interruptOnDisconnect;
        this.executorService = Executors.newCachedThreadPool();
    }

    /**
     * 处理 AG-UI 运行请求。
     *
     * @param input 智能体运行输入
     * @param headerAgentId 来自 HTTP 请求头的智能体 ID（可为 null）
     * @return 用于流式输出 AG-UI 事件的 SseEmitter
     */
    public SseEmitter handle(RunAgentInput input, String headerAgentId) {
        return handle(input, headerAgentId, null);
    }

    /**
     * 处理 AG-UI 运行请求。
     *
     * @param input 智能体运行输入
     * @param headerAgentId 来自 HTTP 请求头的智能体 ID（可为 null）
     * @param request 原生 servlet 请求（可为 null）
     * @return 用于流式输出 AG-UI 事件的 SseEmitter
     */
    public SseEmitter handle(
            RunAgentInput input, String headerAgentId, HttpServletRequest request) {
        return handleInternal(input, headerAgentId, null, request);
    }

    /**
     * 处理 URL 路径中包含智能体 ID 的 AG-UI 运行请求。
     *
     * @param input 智能体运行输入
     * @param headerAgentId 来自 HTTP 请求头的智能体 ID（可为 null）
     * @param pathAgentId 来自 URL 路径变量的智能体 ID
     * @return 用于流式输出 AG-UI 事件的 SseEmitter
     */
    public SseEmitter handleWithAgentId(
            RunAgentInput input, String headerAgentId, String pathAgentId) {
        return handleWithAgentId(input, headerAgentId, pathAgentId, null);
    }

    /**
     * 处理 URL 路径中包含智能体 ID 的 AG-UI 运行请求。
     *
     * @param input 智能体运行输入
     * @param headerAgentId 来自 HTTP 请求头的智能体 ID（可为 null）
     * @param pathAgentId 来自 URL 路径变量的智能体 ID
     * @param request 原生 servlet 请求（可为 null）
     * @return 用于流式输出 AG-UI 事件的 SseEmitter
     */
    public SseEmitter handleWithAgentId(
            RunAgentInput input,
            String headerAgentId,
            String pathAgentId,
            HttpServletRequest request) {
        return handleInternal(input, headerAgentId, pathAgentId, request);
    }

    private SseEmitter handleInternal(
            RunAgentInput input,
            String headerAgentId,
            String pathAgentId,
            HttpServletRequest request) {
        SseEmitter emitter = new SseEmitter(sseTimeout);
        String threadId = input.getThreadId();
        String runId = input.getRunId();

        executorService.submit(
                () -> {
                    try {
                        // 处理请求，同时返回智能体和事件流
                        AguiRequestProcessor.ProcessResult result =
                                processor.process(
                                        runtimeContextRequest(
                                                input, headerAgentId, pathAgentId, request));
                        BaseSubscriber<AguiEvent> subscription =
                                new BaseSubscriber<>() {
                                    @Override
                                    protected void hookOnNext(AguiEvent event) {
                                        if (sendEvent(emitter, event) || !interruptOnDisconnect) {
                                            return;
                                        }
                                        logger.info(
                                                "SSE event send failed for run {}, interrupting"
                                                        + " agent",
                                                runId);
                                        interruptAndCancel(result, threadId, this);
                                    }

                                    @Override
                                    protected void hookOnError(Throwable error) {
                                        logger.error(
                                                "Error during AG-UI run: {}", error.getMessage());
                                        sendErrorAndComplete(
                                                emitter, threadId, runId, error.getMessage());
                                    }

                                    @Override
                                    protected void hookOnComplete() {
                                        try {
                                            emitter.complete();
                                        } catch (Exception e) {
                                            logger.debug(
                                                    "Error completing emitter: {}", e.getMessage());
                                        }
                                    }
                                };

                        // 设置客户端断开连接的处理回调
                        // 使用结果中的同一智能体实例
                        emitter.onCompletion(
                                () -> logger.debug("SSE connection completed for run {}", runId));
                        emitter.onTimeout(
                                () -> {
                                    if (interruptOnDisconnect) {
                                        logger.info(
                                                "SSE connection timed out for run {}, interrupting"
                                                        + " agent",
                                                runId);
                                        interruptAndCancel(result, threadId, subscription);
                                    } else {
                                        logger.info(
                                                "SSE connection timed out for run {}, agent"
                                                        + " continues running",
                                                runId);
                                    }
                                });
                        emitter.onError(
                                (ex) -> {
                                    if (interruptOnDisconnect) {
                                        logger.info(
                                                "SSE connection error for run {}: {}, interrupting"
                                                        + " agent",
                                                runId,
                                                ex.getMessage());
                                        interruptAndCancel(result, threadId, subscription);
                                    } else {
                                        logger.info(
                                                "SSE connection error for run {}: {}, agent"
                                                        + " continues running",
                                                runId,
                                                ex.getMessage());
                                    }
                                });

                        // 订阅同一结果中的事件流
                        // 先创建订阅者，再注册断开连接回调，
                        // 即使客户端在 subscribe() 之前断开，取消也能安全处理。
                        result.events().subscribe(subscription);

                    } catch (AguiException.AgentNotFoundException e) {
                        logger.error("Agent not found: {}", e.getMessage());
                        sendErrorAndComplete(emitter, threadId, runId, e.getMessage());
                    } catch (Exception e) {
                        logger.error("Error processing AG-UI request: {}", e.getMessage());
                        sendErrorAndComplete(emitter, threadId, runId, e.getMessage());
                    }
                });

        return emitter;
    }

    private static void interruptAndCancel(
            AguiRequestProcessor.ProcessResult result, String threadId, Disposable subscription) {
        // 【学习提示】先请求中断，再在 finally 中取消订阅，确保中断逻辑抛错也会释放订阅；
        // 这是协作式终止，不是对已完成工具操作的事务回滚。
        try {
            result.interrupt(threadId);
        } finally {
            subscription.dispose();
        }
    }

    private AguiRuntimeContextRequest<HttpServletRequest> runtimeContextRequest(
            RunAgentInput input,
            String headerAgentId,
            String pathAgentId,
            HttpServletRequest request) {
        return AguiRuntimeContextRequest.<HttpServletRequest>builder()
                .input(input)
                .headerAgentId(headerAgentId)
                .pathAgentId(pathAgentId)
                .transport(AguiRuntimeContextRequest.Transport.MVC)
                .method(request != null ? request.getMethod() : null)
                .path(request != null ? request.getRequestURI() : null)
                .headers(headers(request))
                .queryParams(queryParams(request))
                .nativeRequest(request)
                .build();
    }

    private static Map<String, List<String>> headers(HttpServletRequest request) {
        if (request == null) {
            return Map.of();
        }
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (String name : Collections.list(request.getHeaderNames())) {
            headers.put(name, Collections.list(request.getHeaders(name)));
        }
        return headers;
    }

    private static Map<String, List<String>> queryParams(HttpServletRequest request) {
        if (request == null) {
            return Map.of();
        }
        Map<String, List<String>> queryParams = new LinkedHashMap<>();
        request.getParameterMap().forEach((name, values) -> queryParams.put(name, List.of(values)));
        return queryParams;
    }

    private boolean sendEvent(SseEmitter emitter, AguiEvent event) {
        String jsonData = encoder.encodeToJson(event);
        try {
            emitter.send(SseEmitter.event().data(jsonData, MediaType.APPLICATION_JSON));
            return true;
        } catch (IOException | IllegalStateException e) {
            logger.debug("Failed to send SSE event: {}", e.getMessage());
            return false;
        }
    }

    private void sendErrorAndComplete(
            SseEmitter emitter, String threadId, String runId, String errorMessage) {
        try {
            String errorJson =
                    encoder.encodeToJson(
                            new AguiEvent.Raw(
                                    threadId,
                                    runId,
                                    Map.of(
                                            "error",
                                            errorMessage != null
                                                    ? errorMessage
                                                    : "Unknown error")));
            String finishJson = encoder.encodeToJson(new AguiEvent.RunFinished(threadId, runId));
            emitter.send(SseEmitter.event().data(errorJson, MediaType.APPLICATION_JSON));
            emitter.send(SseEmitter.event().data(finishJson, MediaType.APPLICATION_JSON));
            emitter.complete();
        } catch (Exception e) {
            logger.debug("Failed to send error event: {}", e.getMessage());
            try {
                emitter.completeWithError(e);
            } catch (Exception ex) {
                logger.debug("Failed to complete emitter with error: {}", ex.getMessage());
            }
        }
    }

    /**
     * 获取智能体 ID 请求头名称。
     *
     * @return 请求头名称
     */
    public String getAgentIdHeader() {
        return agentIdHeader;
    }

    /**
     * 创建 AguiMvcController 构建器。
     *
     * @return 新的构建器实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /** AguiMvcController 的构建器。 */
    public static class Builder {

        private AguiAgentRegistry registry;
        private ThreadSessionManager sessionManager;
        private AguiAdapterConfig config;
        private boolean serverSideMemory = false;
        private String agentIdHeader;
        private long sseTimeout = 600000L;
        private boolean interruptOnDisconnect = true;
        private AguiRuntimeContextResolver runtimeContextResolver;
        private AguiAgentAdapterFactory adapterFactory;

        /**
         * 设置智能体注册表。
         *
         * @param registry 智能体注册表
         * @return 当前构建器
         */
        public Builder agentRegistry(AguiAgentRegistry registry) {
            this.registry = registry;
            return this;
        }

        /**
         * 设置支持服务端记忆的线程会话管理器。
         *
         * @param sessionManager 会话管理器
         * @return 当前构建器
         */
        public Builder sessionManager(ThreadSessionManager sessionManager) {
            this.sessionManager = sessionManager;
            return this;
        }

        /**
         * 启用或禁用服务端记忆管理。
         *
         * @param enabled 是否启用服务端记忆
         * @return 当前构建器
         */
        public Builder serverSideMemory(boolean enabled) {
            this.serverSideMemory = enabled;
            return this;
        }

        /**
         * 设置适配器配置。
         *
         * @param config 适配器配置
         * @return 当前构建器
         */
        public Builder config(AguiAdapterConfig config) {
            this.config = config;
            return this;
        }

        /**
         * 设置读取智能体 ID 的 HTTP 请求头名称。
         *
         * @param agentIdHeader 请求头名称（默认 X-Agent-Id）
         * @return 当前构建器
         */
        public Builder agentIdHeader(String agentIdHeader) {
            this.agentIdHeader = agentIdHeader;
            return this;
        }

        /**
         * 设置 SSE 超时时间，单位为毫秒。
         *
         * @param sseTimeout 超时时间
         * @return 当前构建器
         */
        public Builder sseTimeout(long sseTimeout) {
            this.sseTimeout = sseTimeout;
            return this;
        }

        /**
         * 设置客户端断开连接时是否中断智能体。
         *
         * @param interruptOnDisconnect 是否中断智能体
         * @return 当前构建器
         */
        public Builder interruptOnDisconnect(boolean interruptOnDisconnect) {
            this.interruptOnDisconnect = interruptOnDisconnect;
            return this;
        }

        /**
         * 设置运行时上下文解析器。
         *
         * @param runtimeContextResolver 每个请求使用的解析器
         * @return 当前构建器
         */
        public Builder runtimeContextResolver(AguiRuntimeContextResolver runtimeContextResolver) {
            this.runtimeContextResolver = runtimeContextResolver;
            return this;
        }

        /**
         * 设置适配器工厂。
         *
         * @param adapterFactory 为每个请求创建适配器的工厂
         * @return 当前构建器
         */
        public Builder adapterFactory(AguiAgentAdapterFactory adapterFactory) {
            this.adapterFactory = adapterFactory;
            return this;
        }

        /**
         * 构建控制器。
         *
         * @return 构建完成的控制器
         * @throws IllegalStateException 未设置 registry 时抛出
         */
        public AguiMvcController build() {
            if (registry == null) {
                throw new IllegalStateException("Agent registry must be set");
            }
            return new AguiMvcController(this);
        }
    }
}
