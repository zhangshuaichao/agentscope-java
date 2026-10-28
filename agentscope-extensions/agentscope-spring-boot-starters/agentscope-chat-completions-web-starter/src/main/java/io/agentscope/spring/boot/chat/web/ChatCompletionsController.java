/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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
package io.agentscope.spring.boot.chat.web;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.chat.completions.builder.ChatCompletionsResponseBuilder;
import io.agentscope.core.chat.completions.converter.ChatMessageConverter;
import io.agentscope.core.chat.completions.converter.OpenAIToolConverter;
import io.agentscope.core.chat.completions.model.ChatCompletionsRequest;
import io.agentscope.core.chat.completions.model.ChatCompletionsResponse;
import io.agentscope.core.message.Msg;
import io.agentscope.spring.boot.chat.service.ChatCompletionsStreamingService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 暴露 OpenAI Chat Completions 风格 API 的 HTTP 控制器。
 *
 * <p>按客户端管理历史的设计接收请求；实际智能体实例是否跨请求共享，取决于注入 bean 的作用域，而不能仅从此控制器推断完全无状态。
 *
 * <p>【学习提示】ObjectProvider 仅延迟取 bean，不会自动创建请求作用域。核心 starter 默认单例，
 * 而此控制器还会注册请求提供的工具；需要隔离请求时应核对实际 bean、工具集及状态的所有权。
 *
 * <p><b>工作方式：</b>
 *
 * <ol>
 *   <li>客户端在 {@code messages} 中发送完整对话历史
 *   <li>服务端获取智能体实例并转换消息
 *   <li>智能体处理请求并返回响应
 *   <li>客户端将 assistant 消息追加到历史，用于下一次请求
 * </ol>
 *
 * <p><b>工具调用：</b>
 *
 * <p>assistant 使用工具时，响应的 assistant 消息包含 {@code tool_calls}。客户端应：
 *
 * <ol>
 *   <li>将含 tool_calls 的 assistant 消息追加到历史
 *   <li>执行工具并创建工具结果消息
 *   <li>在下一次请求中发送更新后的历史
 * </ol>
 *
 * <p>功能：
 *
 * <ul>
 *   <li>非流式 JSON 响应（默认）
 *   <li>Accept: text/event-stream 或 stream=true 时使用 SSE 流式输出
 *   <li>采用 OpenAI 风格的请求与响应 DTO，实际兼容范围以实现为准
 * </ul>
 */
@RestController
@RequestMapping
public class ChatCompletionsController {

    private static final Logger log = LoggerFactory.getLogger(ChatCompletionsController.class);

    private final ObjectProvider<ReActAgent> agentProvider;
    private final ChatMessageConverter messageConverter;
    private final ChatCompletionsResponseBuilder responseBuilder;
    private final ChatCompletionsStreamingService streamingService;
    private final OpenAIToolConverter toolConverter;

    /**
     * 构造 ChatCompletionsController。
     *
     * @param agentProvider 获取智能体实例的对象提供方
     * @param messageConverter 将 HTTP DTO 转换为框架消息的转换器
     * @param responseBuilder 响应对象构造器
     * @param streamingService 流式响应服务
     * @param toolConverter 将 OpenAI 工具转换为 ToolSchema 的转换器
     */
    public ChatCompletionsController(
            ObjectProvider<ReActAgent> agentProvider,
            ChatMessageConverter messageConverter,
            ChatCompletionsResponseBuilder responseBuilder,
            ChatCompletionsStreamingService streamingService,
            OpenAIToolConverter toolConverter) {
        this.agentProvider = agentProvider;
        this.messageConverter = messageConverter;
        this.responseBuilder = responseBuilder;
        this.streamingService = streamingService;
        this.toolConverter = toolConverter;
    }

    /**
     * 非流式聊天补全端点。
     *
     * <p>处理完整消息历史并返回 assistant 响应。
     *
     * <p>请求包含 {@code stream=true} 时，即使没有 {@code Accept: text/event-stream} 请求头，也自动切换到流式模式，以提高客户端兼容性。
     *
     * @param request 包含完整消息历史的聊天补全请求
     * @return 包含智能体回复 {@link ChatCompletionsResponse} 的 {@link Mono}；请求流式输出时返回由 {@link ServerSentEvent} 构成的 {@link Flux}
     */
    @PostMapping(
            value = "${agentscope.chat-completions.base-path:/v1/chat/completions}",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public Object createCompletion(@Valid @RequestBody ChatCompletionsRequest request) {
        String requestId = UUID.randomUUID().toString();

        log.debug(
                "Processing chat completion request: requestId={}, messageCount={}, stream={}",
                requestId,
                request.getMessages() != null ? request.getMessages().size() : 0,
                request.getStream());

        // stream=true 时自动切换到流式模式，以兼容客户端
        // 兼容设置 stream=true 却没有设置 Accept: text/event-stream 的客户端
        if (Boolean.TRUE.equals(request.getStream())) {
            log.debug("Auto-switching to streaming mode: requestId={}", requestId);
            // 返回带显式内容类型的 Flux，Spring MVC 需要它来处理 SSE
            Flux<ServerSentEvent<String>> stream = createCompletionStream(request);
            return ResponseEntity.ok()
                    .header("Content-Type", MediaType.TEXT_EVENT_STREAM_VALUE)
                    .body(stream);
        }

        try {
            // 为本次请求获取智能体实例，是否新建取决于 bean 作用域
            ReActAgent agent = agentProvider.getObject();
            if (agent == null) {
                return Mono.error(
                        new IllegalStateException(
                                "Failed to create ReActAgent: agentProvider returned null"));
            }

            // 若请求提供工具，则注册仅含模式的工具
            if (request.getTools() != null && !request.getTools().isEmpty()) {
                var toolSchemas = toolConverter.convertToToolSchemas(request.getTools());
                if (!toolSchemas.isEmpty()) {
                    agent.getToolkit().registerSchemas(toolSchemas);
                    log.debug(
                            "Registered {} schema-only tools from request: requestId={}",
                            toolSchemas.size(),
                            requestId);
                }
            }

            // 转换请求中的全部消息
            List<Msg> messages = messageConverter.convertMessages(request.getMessages());
            if (messages.isEmpty()) {
                log.warn("Empty messages list in request: requestId={}", requestId);
                return Mono.error(new IllegalArgumentException("At least one message is required"));
            }

            long startTime = System.currentTimeMillis();
            return agent.call(messages)
                    .map(
                            reply -> {
                                long duration = System.currentTimeMillis() - startTime;
                                log.debug(
                                        "Request completed: requestId={}, duration={}ms",
                                        requestId,
                                        duration);
                                return responseBuilder.buildResponse(request, reply, requestId);
                            })
                    .onErrorResume(
                            error -> {
                                log.error(
                                        "Error processing chat completion request: requestId={}",
                                        requestId,
                                        error);
                                return Mono.just(
                                        responseBuilder.buildErrorResponse(
                                                request, error, requestId));
                            });
        } catch (IllegalArgumentException e) {
            log.error("Invalid request: requestId={}", requestId, e);
            return Mono.error(e);
        } catch (Exception e) {
            log.error("Error creating agent or processing request: requestId={}", requestId, e);
            return Mono.error(new RuntimeException("Failed to process request", e));
        }
    }

    /**
     * 流式聊天补全端点。
     *
     * <p>处理完整消息历史，并以服务器发送事件流式返回响应。
     *
     * @param request 包含完整消息历史的聊天补全请求
     * @return 包含文本增量与完成事件的 {@link ServerSentEvent} {@link Flux}
     */
    @PostMapping(
            value = "${agentscope.chat-completions.base-path:/v1/chat/completions}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> createCompletionStream(
            @Valid @RequestBody ChatCompletionsRequest request) {
        String requestId = UUID.randomUUID().toString();

        log.debug(
                "Processing streaming chat completion request: requestId={}, messageCount={},"
                        + " stream={}",
                requestId,
                request.getMessages() != null ? request.getMessages().size() : 0,
                request.getStream());

        // 在流式端点拒绝显式非流式请求
        // stream 显式为 false 时返回错误，以保持一致性
        if (Boolean.FALSE.equals(request.getStream())) {
            log.warn(
                    "Non-streaming request received on streaming endpoint: requestId={},"
                            + " stream=false",
                    requestId);
            return Flux.error(
                    new IllegalArgumentException(
                            "Non-streaming requests (stream=false) should use the non-streaming"
                                    + " endpoint without Accept: text/event-stream header"));
        }

        try {
            // 为本次请求获取智能体实例，是否新建取决于 bean 作用域
            ReActAgent agent = agentProvider.getObject();
            if (agent == null) {
                return Flux.error(
                        new IllegalStateException(
                                "Failed to create ReActAgent: agentProvider returned null"));
            }

            // 若请求提供工具，则注册仅含模式的工具
            if (request.getTools() != null && !request.getTools().isEmpty()) {
                var toolSchemas = toolConverter.convertToToolSchemas(request.getTools());
                if (!toolSchemas.isEmpty()) {
                    agent.getToolkit().registerSchemas(toolSchemas);
                    log.debug(
                            "Registered {} schema-only tools from request: requestId={}",
                            toolSchemas.size(),
                            requestId);
                }
            }

            // 转换请求中的全部消息
            List<Msg> messages = messageConverter.convertMessages(request.getMessages());
            if (messages.isEmpty()) {
                log.warn("Empty messages list in streaming request: requestId={}", requestId);
                return Flux.error(new IllegalArgumentException("At least one message is required"));
            }

            String model = request.getModel();
            return streamingService
                    .streamAsSse(agent, messages, requestId, model)
                    .onErrorResume(
                            error -> {
                                log.error(
                                        "Error in streaming response: requestId={}",
                                        requestId,
                                        error);
                                return Flux.just(
                                        streamingService.createErrorSseEvent(
                                                error, requestId, model));
                            });
        } catch (IllegalArgumentException e) {
            log.error("Invalid streaming request: requestId={}", requestId, e);
            return Flux.error(e);
        } catch (Exception e) {
            log.error(
                    "Error creating agent or processing streaming request: requestId={}",
                    requestId,
                    e);
            return Flux.error(new RuntimeException("Failed to process streaming request", e));
        }
    }
}
