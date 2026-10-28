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
package io.agentscope.spring.boot.chat.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.chat.completions.model.ChatCompletionsChunk;
import io.agentscope.core.chat.completions.streaming.ChatCompletionsStreamingAdapter;
import io.agentscope.core.message.Msg;
import java.util.List;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

/**
 * 用于流式聊天补全响应的 Spring 专用服务。
 *
 * <p>这一薄适配层负责：
 *
 * <ul>
 *   <li>将核心流式逻辑委托给 {@link ChatCompletionsStreamingAdapter}
 *   <li>将 {@link ChatCompletionsChunk} 转换为 Spring 的 {@link ServerSentEvent}
 *   <li>为 SSE data 字段执行 JSON 序列化
 * </ul>
 *
 * <p><b>架构：</b>
 *
 * <pre>
 * ChatCompletionsStreamingAdapter (framework-agnostic, in extension-core)
 *           ↓ Flux&lt;ChatCompletionsChunk&gt;
 * ChatCompletionsStreamingService (Spring-specific, in starter)
 *           ↓ Flux&lt;ServerSentEvent&lt;String&gt;&gt;
 * HTTP Response (SSE stream)
 * </pre>
 *
 * <p>此设计让核心流式逻辑可在 Spring、Quarkus 等框架间复用，而将框架专有细节留在 starter 模块。
 */
public class ChatCompletionsStreamingService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ChatCompletionsStreamingAdapter streamingAdapter;

    /**
     * 构造 {@code ChatCompletionsStreamingService}。
     *
     * @param streamingAdapter 框架无关的流式适配器
     */
    public ChatCompletionsStreamingService(ChatCompletionsStreamingAdapter streamingAdapter) {
        this.streamingAdapter = streamingAdapter;
    }

    /**
     * 将智能体事件以服务器发送事件（SSE）流式输出。
     *
     * <p>每个 SSE "data" 字段包含按 OpenAI 流式格式序列化的 {@link ChatCompletionsChunk} JSON；流以 "data: [DONE]" 事件结束。
     *
     * @param agent 提供事件流的智能体
     * @param messages 发给智能体的消息
     * @param requestId 用于跟踪的请求 ID
     * @param model 响应中的模型名称
     * @return 由 {@link ServerSentEvent} 对象构成的 {@link Flux}
     */
    public Flux<ServerSentEvent<String>> streamAsSse(
            ReActAgent agent, List<Msg> messages, String requestId, String model) {
        return streamingAdapter.stream(agent, messages, requestId, model)
                .map(this::chunkToSseEvent)
                .concatWith(Flux.just(createDoneSseEvent()));
    }

    /**
     * 将分块转换为 SSE 事件。
     *
     * @param chunk 待转换的分块
     * @return data 携带 JSON 的 SSE 事件
     */
    private ServerSentEvent<String> chunkToSseEvent(ChatCompletionsChunk chunk) {
        try {
            String json = OBJECT_MAPPER.writeValueAsString(chunk);
            return ServerSentEvent.<String>builder().data(json).build();
        } catch (JsonProcessingException e) {
            return ServerSentEvent.<String>builder()
                    .data("{\"error\":\"Serialization error\"}")
                    .build();
        }
    }

    /**
     * 创建用于标识流完成的结束事件。
     *
     * @return data 为 "[DONE]" 的结束 SSE 事件
     */
    private ServerSentEvent<String> createDoneSseEvent() {
        return ServerSentEvent.<String>builder().data("[DONE]").build();
    }

    /**
     * 创建错误 SSE 事件。
     *
     * @param error 发生的错误
     * @param requestId 用于跟踪的请求 ID
     * @param model 模型名称
     * @return 含错误信息的 {@link ServerSentEvent}
     */
    public ServerSentEvent<String> createErrorSseEvent(
            Throwable error, String requestId, String model) {
        ChatCompletionsChunk errorChunk =
                streamingAdapter.createErrorChunk(error, requestId, model);
        return chunkToSseEvent(errorChunk);
    }
}
