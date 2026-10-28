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
package io.agentscope.spring.boot.agui.webflux;

import io.agentscope.core.agui.AguiException;
import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.adapter.AguiAgentAdapterFactory;
import io.agentscope.core.agui.encoder.AguiEventEncoder;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.agui.processor.AguiRequestProcessor;
import io.agentscope.core.agui.registry.AguiAgentRegistry;
import io.agentscope.core.agui.runtime.AguiRequestBodyParser;
import io.agentscope.core.agui.runtime.AguiRuntimeContextRequest;
import io.agentscope.core.agui.runtime.AguiRuntimeContextResolver;
import io.agentscope.spring.boot.agui.common.DefaultAgentResolver;
import io.agentscope.spring.boot.agui.common.ThreadSessionManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * AG-UI 协议请求的 WebFlux 处理器。
 *
 * <p>处理 AG-UI 运行请求，返回包含 AG-UI 协议事件的服务器发送事件（SSE）流。
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
 * AguiWebFluxHandler handler = AguiWebFluxHandler.builder()
 *     .agentRegistry(registry)
 *     .config(AguiAdapterConfig.defaultConfig())
 *     .agentIdHeader("X-Agent-Id")
 *     .build();
 *
 * RouterFunction<ServerResponse> routes = RouterFunctions.route()
 *     .POST("/agui/run", handler::handle)
 *     .POST("/agui/run/{agentId}", handler::handleWithAgentId)
 *     .build();
 * }</pre>
 */
public class AguiWebFluxHandler {

    private static final Logger logger = LoggerFactory.getLogger(AguiWebFluxHandler.class);

    private static final String DEFAULT_AGENT_ID_HEADER = "X-Agent-Id";
    private static final String AGENT_ID_PATH_VARIABLE = "agentId";

    private final AguiRequestProcessor processor;
    private final AguiEventEncoder encoder;
    private final AguiRequestBodyParser requestBodyParser;
    private final String agentIdHeader;
    private final boolean interruptOnDisconnect;

    private AguiWebFluxHandler(Builder builder) {
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
        this.requestBodyParser =
                builder.requestBodyParser != null
                        ? builder.requestBodyParser
                        : new AguiRequestBodyParser();
        this.agentIdHeader =
                builder.agentIdHeader != null ? builder.agentIdHeader : DEFAULT_AGENT_ID_HEADER;
        this.interruptOnDisconnect = builder.interruptOnDisconnect;
    }

    /**
     * 处理 AG-UI 运行请求。
     *
     * <p>将请求体解析为 {@link RunAgentInput}，从注册表解析智能体，返回 AG-UI 事件 SSE 流。
     *
     * @param request 服务端请求
     * @return 包含 SSE 流响应的 Mono
     */
    public Mono<ServerResponse> handle(ServerRequest request) {
        return request.bodyToMono(String.class)
                .map(requestBodyParser::parse)
                .flatMap(input -> processInput(input, request, null))
                .onErrorResume(this::handleParseError);
    }

    /**
     * 处理 URL 路径中包含智能体 ID 的 AG-UI 运行请求。
     *
     * <p>处理 {@code /agui/run/{agentId}} 请求。解析智能体时，路径变量具有最高优先级。
     *
     * @param request 包含 agentId 路径变量的服务端请求
     * @return 包含 SSE 流响应的 Mono
     */
    public Mono<ServerResponse> handleWithAgentId(ServerRequest request) {
        String pathAgentId = request.pathVariable(AGENT_ID_PATH_VARIABLE);
        return request.bodyToMono(String.class)
                .map(requestBodyParser::parse)
                .flatMap(input -> processInput(input, request, pathAgentId))
                .onErrorResume(this::handleParseError);
    }

    private Mono<ServerResponse> processInput(
            RunAgentInput input, ServerRequest request, String pathAgentId) {
        String threadId = input.getThreadId();
        String runId = input.getRunId();

        try {
            // 获取请求头中的智能体 ID
            String headerAgentId = request.headers().firstHeader(agentIdHeader);

            // 处理请求，同时返回智能体和事件流
            AguiRequestProcessor.ProcessResult result =
                    processor.process(
                            runtimeContextRequest(input, headerAgentId, pathAgentId, request));

            // 使用 ServerSentEvent 创建 SSE 流，以正确执行流式输出
            // 【学习提示】禁用断连中断时使用 autoConnect(1) 保持上游连接，客户端取消不等于智能体停止；
            // 启用时则在取消回调请求中断。两条路径都不能撤销已经发生的工具外部副作用。
            Flux<AguiEvent> events =
                    interruptOnDisconnect
                            ? result.events()
                            : result.events().publish().autoConnect(1);
            Flux<ServerSentEvent<String>> sseStream =
                    events.map(
                                    event ->
                                            ServerSentEvent.<String>builder()
                                                    .data(encoder.encodeToJson(event).trim())
                                                    .build())
                            // 客户端关闭连接时，按配置决定是否中断智能体
                            .doOnCancel(
                                    () -> {
                                        if (interruptOnDisconnect) {
                                            logger.info(
                                                    "SSE stream cancelled for run {}, interrupting"
                                                            + " agent",
                                                    runId);
                                            result.interrupt(threadId);
                                        } else {
                                            logger.info(
                                                    "SSE stream cancelled for run {}, agent"
                                                            + " continues running",
                                                    runId);
                                        }
                                    });

            return ServerResponse.ok()
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .body(sseStream, ServerSentEvent.class);

        } catch (AguiException.AgentNotFoundException e) {
            logger.error("Agent not found: {}", e.getMessage());
            return createErrorResponse(threadId, runId, e.getMessage());
        } catch (Exception e) {
            logger.error("Error processing AG-UI request: {}", e.getMessage());
            return createErrorResponse(threadId, runId, e.getMessage());
        }
    }

    private AguiRuntimeContextRequest<ServerRequest> runtimeContextRequest(
            RunAgentInput input, String headerAgentId, String pathAgentId, ServerRequest request) {
        return AguiRuntimeContextRequest.<ServerRequest>builder()
                .input(input)
                .headerAgentId(headerAgentId)
                .pathAgentId(pathAgentId)
                .transport(AguiRuntimeContextRequest.Transport.WEBFLUX)
                .method(request != null ? request.method().name() : null)
                .path(request != null ? request.path() : null)
                .headers(headers(request))
                .queryParams(queryParams(request))
                .nativeRequest(request)
                .build();
    }

    private static Map<String, List<String>> headers(ServerRequest request) {
        if (request == null) {
            return Map.of();
        }
        Map<String, List<String>> headers = new LinkedHashMap<>();
        request.headers()
                .asHttpHeaders()
                .forEach((name, values) -> headers.put(name, List.copyOf(values)));
        return headers;
    }

    private static Map<String, List<String>> queryParams(ServerRequest request) {
        if (request == null) {
            return Map.of();
        }
        Map<String, List<String>> queryParams = new LinkedHashMap<>();
        request.queryParams().forEach((name, values) -> queryParams.put(name, List.copyOf(values)));
        return queryParams;
    }

    private Mono<ServerResponse> handleParseError(Throwable error) {
        logger.error("Error parsing AG-UI request: {}", error.getMessage());
        return ServerResponse.badRequest()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(
                        createErrorEventStream(
                                "unknown",
                                "unknown",
                                "Failed to parse request: " + error.getMessage()),
                        ServerSentEvent.class);
    }

    private Mono<ServerResponse> createErrorResponse(
            String threadId, String runId, String errorMessage) {
        return ServerResponse.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(createErrorEventStream(threadId, runId, errorMessage), ServerSentEvent.class);
    }

    /**
     * 创建包含错误事件和结束事件的 SSE 流。
     *
     * @param threadId 线程 ID
     * @param runId 运行 ID
     * @param errorMessage 错误消息
     * @return ServerSentEvent 对象流
     */
    private Flux<ServerSentEvent<String>> createErrorEventStream(
            String threadId, String runId, String errorMessage) {
        String errorEvent =
                encoder.encodeToJson(
                                new AguiEvent.Raw(
                                        threadId,
                                        runId,
                                        Map.of(
                                                "error",
                                                errorMessage != null
                                                        ? errorMessage
                                                        : "Unknown error")))
                        .trim();
        String finishEvent =
                encoder.encodeToJson(new AguiEvent.RunFinished(threadId, runId)).trim();
        return Flux.just(
                ServerSentEvent.<String>builder().data(errorEvent).build(),
                ServerSentEvent.<String>builder().data(finishEvent).build());
    }

    /**
     * 创建 AguiWebFluxHandler 构建器。
     *
     * @return 新的构建器实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /** AguiWebFluxHandler 的构建器。 */
    public static class Builder {

        private AguiAgentRegistry registry;
        private ThreadSessionManager sessionManager;
        private AguiAdapterConfig config;
        private boolean serverSideMemory = false;
        private String agentIdHeader;
        private boolean interruptOnDisconnect = true;
        private AguiRuntimeContextResolver runtimeContextResolver;
        private AguiAgentAdapterFactory adapterFactory;
        private AguiRequestBodyParser requestBodyParser;

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
         * 设置请求体解析器。
         *
         * @param requestBodyParser 请求体解析器
         * @return 当前构建器
         */
        public Builder requestBodyParser(AguiRequestBodyParser requestBodyParser) {
            this.requestBodyParser = requestBodyParser;
            return this;
        }

        /**
         * 构建处理器。
         *
         * @return 构建完成的处理器
         * @throws IllegalStateException 未设置 registry 时抛出
         */
        public AguiWebFluxHandler build() {
            if (registry == null) {
                throw new IllegalStateException("Agent registry must be set");
            }
            return new AguiWebFluxHandler(this);
        }
    }
}
