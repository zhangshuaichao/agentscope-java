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
package io.agentscope.core.chat.completions.streaming;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.chat.completions.model.ChatCompletionsChunk;
import io.agentscope.core.chat.completions.model.ToolCall;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import reactor.core.publisher.Flux;

/**
 * 用于处理流式聊天补全响应的框架无关适配器。
 *
 * <p>将智能体事件转换为兼容 OpenAI 的 {@link ChatCompletionsChunk} 对象，由具体框架适配器（如 Spring SSE、Quarkus Multi）完成最终序列化和传输。
 *
 * <p><b>设计思想：</b>
 *
 * <ul>
 *   <li>核心流式逻辑不依赖具体框架
 *   <li>返回由 {@link ChatCompletionsChunk} 构成的 {@link Flux}，而非框架专用类型
 *   <li>starter 模块中的框架适配器负责 SSE／传输细节
 * </ul>
 *
 * <p><b>支持的事件类型：</b>
 *
 * <ul>
 *   <li>{@link EventType#REASONING}：文本内容和工具调用决策
 *   <li>{@link EventType#TOOL_RESULT}：工具执行结果
 * </ul>
 *
 * <p><b>输出分块类型：</b>
 *
 * <ul>
 *   <li>文本分块：增量文本内容
 *   <li>工具调用分块：智能体决定调用工具时产生
 *   <li>结束分块：携带 finish_reason 表示流结束
 * </ul>
 */
public class ChatCompletionsStreamingAdapter {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 构造新的 {@code ChatCompletionsStreamingAdapter}。 */
    public ChatCompletionsStreamingAdapter() {}

    /**
     * 将智能体事件流转换为兼容 OpenAI 的分块流。
     *
     * <p>订阅智能体事件流，按 OpenAI 流式格式将每个事件转换为一个或多个 {@link ChatCompletionsChunk} 对象。
     *
     * <p><b>文本去重：</b>在增量模式下，智能体先发送文本增量（isLast=false），再发送最终累计事件（isLast=true）。若已经看到增量分块，则过滤最后一个 REASONING 事件中的文本，避免重复，但仍保留工具调用和结束原因。
     *
     * @param agent 提供事件流的智能体
     * @param messages 发给智能体的消息
     * @param requestId 用于跟踪的请求 ID（用作分块 ID）
     * @param model 分块中包含的模型名称
     * @return 由 {@link ChatCompletionsChunk} 对象构成的 {@link Flux}
     */
    public Flux<ChatCompletionsChunk> stream(
            ReActAgent agent, List<Msg> messages, String requestId, String model) {
        StreamOptions options =
                StreamOptions.builder()
                        .eventTypes(EventType.REASONING, EventType.TOOL_RESULT)
                        .incremental(true)
                        .build();

        // 跟踪是否出现非末尾的 REASONING 事件（表明使用增量模式）
        // 【学习提示】去重标记属于本次 stream() 调用，而非 defer 内的每次订阅；同一返回流重复订阅会共享它。
        // 标记在后续推理轮次也不重置，阅读多轮行为时需结合上游增量事件约定。
        AtomicBoolean hasSeenIncrementalReasoning = new AtomicBoolean(false);

        return agent.stream(messages, options)
                .filter(event -> event.getMessage() != null)
                .doOnNext(
                        event -> {
                            // 记录遇到的非末尾 REASONING 事件（增量分块）
                            if (event.getType() == EventType.REASONING && !event.isLast()) {
                                hasSeenIncrementalReasoning.set(true);
                            }
                        })
                .flatMap(
                        event -> {
                            // 若已经看到增量事件，则过滤最后一个 REASONING 事件中的文本
                            // 增量事件的文本已输出，无需重复累计文本
                            if (event.getType() == EventType.REASONING
                                    && event.isLast()
                                    && hasSeenIncrementalReasoning.get()) {
                                // 这是最终累计文本事件，过滤其文本
                                // 但保留工具调用和结束原因
                                return convertEventToChunksWithoutText(event, requestId, model);
                            }
                            return convertEventToChunks(event, requestId, model);
                        });
    }

    /**
     * 将智能体事件转换为一个或多个流式分块。
     *
     * <p>一个事件若同时包含文本与工具调用，可能生成多个分块。
     *
     * @param event 智能体事件
     * @param requestId 用于跟踪的请求 ID
     * @param model 模型名称
     * @return ChatCompletionsChunk 对象流
     */
    public Flux<ChatCompletionsChunk> convertEventToChunks(
            Event event, String requestId, String model) {
        return convertEventToChunksInternal(event, requestId, model, true);
    }

    /**
     * 将智能体事件转换为分块，但不包含文本内容。
     *
     * <p>用于增量模式下的最终累计事件：保留工具调用和结束原因，过滤重复文本。
     *
     * @param event 智能体事件
     * @param requestId 请求 ID
     * @param model 模型名称
     * @return ChatCompletionsChunk 对象流（不含文本分块）
     */
    private Flux<ChatCompletionsChunk> convertEventToChunksWithoutText(
            Event event, String requestId, String model) {
        return convertEventToChunksInternal(event, requestId, model, false);
    }

    /**
     * 将智能体事件转换为分块的内部方法，可选择是否过滤文本。
     *
     * @param event 智能体事件
     * @param requestId 请求 ID
     * @param model 模型名称
     * @param includeText 是否包含文本内容
     * @return ChatCompletionsChunk 对象流
     */
    private Flux<ChatCompletionsChunk> convertEventToChunksInternal(
            Event event, String requestId, String model, boolean includeText) {
        Msg msg = event.getMessage();
        if (msg == null) {
            return Flux.empty();
        }

        List<ContentBlock> contentBlocks = msg.getContent();
        if (contentBlocks == null || contentBlocks.isEmpty()) {
            return Flux.empty();
        }

        List<ChatCompletionsChunk> chunks = new ArrayList<>();

        // 提取文本内容
        StringBuilder textBuilder = new StringBuilder();
        for (ContentBlock block : contentBlocks) {
            if (block instanceof TextBlock) {
                String text = ((TextBlock) block).getText();
                if (text != null && !text.isEmpty()) {
                    textBuilder.append(text);
                }
            }
        }

        String textContent = textBuilder.toString();
        if (!textContent.isEmpty() && includeText) {
            chunks.add(ChatCompletionsChunk.textChunk(requestId, model, textContent));
        }

        // 提取工具调用（仅处理来自 assistant 的 REASONING 事件）
        if (event.getType() == EventType.REASONING) {
            List<ToolCall> toolCalls = new ArrayList<>();
            int toolCallIndex = 0;
            for (ContentBlock block : contentBlocks) {
                if (block instanceof ToolUseBlock) {
                    ToolUseBlock toolUseBlock = (ToolUseBlock) block;
                    // 优先使用 content 字段（原始 JSON 字符串），其次才使用 input 映射
                    // DashScope 等提供方将参数存储在 content 字段中
                    String argumentsJson;
                    String content = toolUseBlock.getContent();
                    Map<String, Object> input = toolUseBlock.getInput();

                    if (content != null && !content.isEmpty()) {
                        argumentsJson = content;
                    } else if (input != null && !input.isEmpty()) {
                        // 仅在 input 非空时进行序列化
                        argumentsJson = serializeMapToJson(input);
                    } else {
                        // content 和 input 均为空：流式响应使用空字符串
                        // 这样客户端才能正确累积后续分块
                        argumentsJson = "";
                    }
                    toolCalls.add(
                            new ToolCall(
                                    toolCallIndex,
                                    toolUseBlock.getId(),
                                    toolUseBlock.getName(),
                                    argumentsJson));
                    toolCallIndex++;
                }
            }

            if (!toolCalls.isEmpty()) {
                chunks.add(ChatCompletionsChunk.toolCallChunk(requestId, model, toolCalls));
            }
        }

        // 提取工具结果（仅处理 TOOL_RESULT 事件）
        if (event.getType() == EventType.TOOL_RESULT) {
            for (ContentBlock block : contentBlocks) {
                if (block instanceof ToolResultBlock) {
                    ToolResultBlock resultBlock = (ToolResultBlock) block;
                    String resultContent = extractToolResultContent(resultBlock);
                    chunks.add(
                            ChatCompletionsChunk.toolResultChunk(
                                    requestId,
                                    model,
                                    resultBlock.getId(),
                                    resultBlock.getName(),
                                    resultContent));
                }
            }
        }

        // 若这是最后一个事件，则追加结束分块
        if (event.isLast()) {
            // 根据 GenerateReason 或工具调用决定结束原因
            // 与 ChatCompletionsResponseBuilder 中的逻辑保持一致
            String finishReason;
            GenerateReason generateReason =
                    event.getMessage() != null ? event.getMessage().getGenerateReason() : null;

            if (generateReason == GenerateReason.TOOL_SUSPENDED) {
                finishReason = "tool_calls";
            } else if (generateReason == GenerateReason.MAX_ITERATIONS) {
                finishReason = "length";
            } else {
                // 回退为检查内容中是否存在工具调用
                boolean hasToolCalls =
                        contentBlocks.stream().anyMatch(block -> block instanceof ToolUseBlock);
                finishReason = hasToolCalls ? "tool_calls" : "stop";
            }

            chunks.add(ChatCompletionsChunk.finishChunk(requestId, model, finishReason));
        }

        return Flux.fromIterable(chunks);
    }

    /**
     * 创建错误分块。
     *
     * <p>框架适配器可借此统一错误处理方式。
     *
     * @param error 发生的错误
     * @param requestId 用于跟踪的请求 ID
     * @param model 模型名称
     * @return 表示错误的 ChatCompletionsChunk
     */
    public ChatCompletionsChunk createErrorChunk(Throwable error, String requestId, String model) {
        String errorMessage = error != null ? error.getMessage() : "Unknown error occurred";
        return ChatCompletionsChunk.textChunk(requestId, model, "Error: " + errorMessage);
    }

    /**
     * 从 ToolResultBlock 提取内容。
     *
     * <p>拼接工具结果输出中的全部 TextBlock 文本。
     *
     * @param resultBlock 工具结果块
     * @return 提取出的内容字符串
     */
    private String extractToolResultContent(ToolResultBlock resultBlock) {
        if (resultBlock.getOutput() == null || resultBlock.getOutput().isEmpty()) {
            return "";
        }

        StringBuilder contentBuilder = new StringBuilder();
        for (ContentBlock outputBlock : resultBlock.getOutput()) {
            if (outputBlock instanceof TextBlock) {
                String text = ((TextBlock) outputBlock).getText();
                if (text != null && !text.isEmpty()) {
                    contentBuilder.append(text);
                }
            }
        }
        return contentBuilder.toString();
    }

    /**
     * 将 Map 序列化为 JSON 字符串。
     *
     * @param map 待序列化的映射
     * @return JSON 字符串；序列化失败时返回 "{}"
     */
    private String serializeMapToJson(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return "{}";
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
