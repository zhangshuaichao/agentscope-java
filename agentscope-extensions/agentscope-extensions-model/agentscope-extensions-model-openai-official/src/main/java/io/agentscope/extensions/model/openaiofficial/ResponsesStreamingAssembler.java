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
package io.agentscope.extensions.model.openaiofficial;

import com.openai.core.http.StreamResponse;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseError;
import com.openai.models.responses.ResponseErrorEvent;
import com.openai.models.responses.ResponseFailedEvent;
import com.openai.models.responses.ResponseFunctionCallArgumentsDeltaEvent;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseImageGenCallPartialImageEvent;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputItemAddedEvent;
import com.openai.models.responses.ResponseOutputItemDoneEvent;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseReasoningItem;
import com.openai.models.responses.ResponseReasoningSummaryTextDeltaEvent;
import com.openai.models.responses.ResponseReasoningTextDeltaEvent;
import com.openai.models.responses.ResponseStreamEvent;
import com.openai.models.responses.ResponseTextDeltaEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.tool.ToolValidator;
import io.agentscope.extensions.model.openaiofficial.tool.ResponsesServerToolHelper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * 将 Responses API 流式事件组装为 {@code Flux<ChatResponse>}。
 *
 * <p>将每个 {@link ResponseStreamEvent} 路由给相应处理器：文本增量产生
 * {@link TextBlock} 片段，推理摘要增量产生 {@link ThinkingBlock} 片段，
 * 函数调用参数增量产生片段式 {@link ToolUseBlock}；若未收到 output-item-added
 * 事件，则使用占位 ID／名称。
 *
 * <p>终止事件（completed／incomplete）触发从最终 {@link Response} 重新提取
 * 拒绝内容、推理元数据和用量。优先检查拒绝内容，遇到拒绝时立即抛出不可重试异常。
 * 失败／错误事件转换为 {@link OpenAIOfficialModelException}。
 *
 * <p>任何终止信号（完成、错误、取消）都会通过 {@code doFinally}
 * 关闭 SDK {@link StreamResponse}。
 */
final class ResponsesStreamingAssembler {

    private ResponsesStreamingAssembler() {}

    private static final Logger log = LoggerFactory.getLogger(ResponsesStreamingAssembler.class);

    private static final String FRAGMENT_PLACEHOLDER = "__fragment__";

    /**
     * 将 SDK 流式响应转换为 {@code Flux<ChatResponse>}。
     *
     * @param streamResponse SDK 的 {@link ResponseStreamEvent} 流
     * @param modelName 用于错误上下文的模型名称
     * @param startTime 用于用量计时的开始时间
     * @return 聊天响应流，每个发出的块对应一个响应
     */
    static Flux<ChatResponse> assemble(
            StreamResponse<ResponseStreamEvent> streamResponse,
            String modelName,
            Instant startTime) {
        // 【学习提示】一个 SDK 响应流对应一个累积状态对象；concatMap 保持事件处理顺序，
        // 使工具参数片段与终态元数据沿同一状态机组装，而不是并行乱序更新。
        StreamingState state = new StreamingState(modelName, startTime);

        return Flux.fromStream(streamResponse.stream())
                .doFinally(signal -> closeQuietly(streamResponse))
                .concatMap(
                        event -> {
                            try {
                                return Flux.fromIterable(state.processEvent(event));
                            } catch (OpenAIOfficialModelException e) {
                                return Flux.error(e);
                            } catch (RuntimeException e) {
                                return Flux.error(OpenAIErrorTranslator.translate(e, modelName));
                            }
                        })
                .onErrorMap(e -> OpenAIErrorTranslator.translate(e, modelName));
    }

    static void closeQuietly(StreamResponse<?> streamResponse) {
        try {
            streamResponse.close();
        } catch (Exception e) {
            log.debug("Failed to close SDK stream", e);
        }
    }

    // ── 流式状态 ──────────────────────────────────────────────────

    private static final class StreamingState {
        private final String modelName;
        private final Instant startTime;
        private final Map<String, ToolCallInfo> itemRegistry = new HashMap<>();
        private final StringBuilder reasoningTextAccumulator = new StringBuilder();
        private final ResponsesServerToolHelper.HostedToolSearchPairing toolSearchPairing =
                new ResponsesServerToolHelper.HostedToolSearchPairing();

        StreamingState(String modelName, Instant startTime) {
            this.modelName = modelName;
            this.startTime = startTime;
        }

        /**
         * 将单个流式事件路由到相应处理器。
         *
         * @return 含 0 或 1 个 {@link ChatResponse} 块的列表；
         *     终止事件产生带用量和元数据的单个块
         */
        List<ChatResponse> processEvent(ResponseStreamEvent event) {
            List<ChatResponse> results = new ArrayList<>();

            if (event.isOutputItemAdded()) {
                handleOutputItemAdded(event.asOutputItemAdded());
            } else if (event.isOutputItemDone()) {
                results.addAll(handleOutputItemDone(event.asOutputItemDone()));
            } else if (event.isImageGenerationCallPartialImage()) {
                results.add(
                        handleImageGenerationPartialImage(
                                event.asImageGenerationCallPartialImage()));
            } else if (event.isOutputTextDelta()) {
                ResponseTextDeltaEvent deltaEvent = event.asOutputTextDelta();
                if (!deltaEvent.delta().isEmpty()) {
                    results.add(handleTextDelta(deltaEvent));
                }
            } else if (event.isReasoningSummaryTextDelta()) {
                ResponseReasoningSummaryTextDeltaEvent deltaEvent =
                        event.asReasoningSummaryTextDelta();
                if (!deltaEvent.delta().isEmpty()) {
                    results.add(handleReasoningSummaryDelta(deltaEvent));
                }
            } else if (event.isReasoningTextDelta()) {
                handleReasoningTextDelta(event.asReasoningTextDelta());
            } else if (event.isFunctionCallArgumentsDelta()) {
                ResponseFunctionCallArgumentsDeltaEvent deltaEvent =
                        event.asFunctionCallArgumentsDelta();
                if (!deltaEvent.delta().isEmpty()) {
                    results.add(handleFunctionCallArgumentsDelta(deltaEvent));
                }
            } else if (event.isCompleted()) {
                results.add(handleTerminal(event.asCompleted().response()));
            } else if (event.isIncomplete()) {
                results.add(handleTerminal(event.asIncomplete().response()));
            } else if (event.isFailed()) {
                throw handleFailed(event.asFailed());
            } else if (event.isError()) {
                throw handleErrorEvent(event.asError());
            }
            // 其他所有事件均不产生 ChatResponse 块。
            return results;
        }

        // ── 事件处理器 ──

        private void handleOutputItemAdded(ResponseOutputItemAddedEvent event) {
            ResponseOutputItem item = event.item();
            if (item.isFunctionCall()) {
                ResponseFunctionToolCall call = item.asFunctionCall();
                Optional<String> itemIdOpt = call.id();
                if (itemIdOpt.isPresent()) {
                    if (!ToolValidator.requireNonBlank(
                            "OpenAI official", call.name(), call.callId())) {
                        return;
                    }
                    itemRegistry.put(
                            itemIdOpt.get(),
                            new ToolCallInfo(
                                    call.callId(), call.name(), call.namespace().orElse(null)));
                } else {
                    log.warn(
                            "Function call output item missing item ID; subsequent argument"
                                    + " deltas will use placeholder, callId={}, name={}",
                            call.callId(),
                            call.name());
                }
            }
        }

        private List<ChatResponse> handleOutputItemDone(ResponseOutputItemDoneEvent event) {
            ResponseOutputItem item = event.item();
            String companionCallId = toolSearchPairing.companionCallId(item);
            return ResponsesServerToolHelper.decodeBlocks(item, companionCallId).stream()
                    .map(block -> ChatResponse.builder().content(List.of(block)).build())
                    .toList();
        }

        private ChatResponse handleImageGenerationPartialImage(
                ResponseImageGenCallPartialImageEvent event) {
            ToolResultBlock result = ResponsesServerToolHelper.decodePartialImage(event);
            return ChatResponse.builder().content(List.of(result)).build();
        }

        private ChatResponse handleTextDelta(ResponseTextDeltaEvent event) {
            List<ContentBlock> content = new ArrayList<>();
            content.add(TextBlock.builder().text(event.delta()).build());
            return ChatResponse.builder().content(content).build();
        }

        private ChatResponse handleReasoningSummaryDelta(
                ResponseReasoningSummaryTextDeltaEvent event) {
            List<ContentBlock> content = new ArrayList<>();
            content.add(ThinkingBlock.builder().thinking(event.delta()).build());
            return ChatResponse.builder().content(content).build();
        }

        private void handleReasoningTextDelta(ResponseReasoningTextDeltaEvent event) {
            String delta = event.delta();
            if (!delta.isEmpty()) {
                reasoningTextAccumulator.append(delta);
            }
        }

        private ChatResponse handleFunctionCallArgumentsDelta(
                ResponseFunctionCallArgumentsDeltaEvent event) {
            ToolCallInfo info = itemRegistry.get(event.itemId());
            String callId = info != null ? info.callId() : "";
            String name = info != null ? info.name() : FRAGMENT_PLACEHOLDER;
            Map<String, Object> metadata = new HashMap<>();
            if (info != null && info.namespace() != null) {
                metadata.put(OpenAIOfficialConstants.MD_FUNCTION_CALL_NAMESPACE, info.namespace());
            }
            List<ContentBlock> content = new ArrayList<>();
            content.add(
                    ToolUseBlock.builder()
                            .id(callId)
                            .name(name)
                            .input(new HashMap<>())
                            .content(event.delta())
                            .metadata(metadata.isEmpty() ? null : metadata)
                            .build());
            return ChatResponse.builder().content(content).build();
        }

        private ChatResponse handleTerminal(Response response) {
            // 【学习提示】增量文本到达不代表请求已成功结束；终态仍须检查拒绝，
            // 并补齐用量及供历史重放使用的元数据，不能仅靠最后一个文本增量推断完成。
            // 拒绝检查关卡
            String refusal = extractRefusal(response);
            if (!refusal.isEmpty()) {
                throw new OpenAIOfficialModelException(
                        "Model response was refused: " + refusal, null, modelName);
            }

            // 从终态 response.output() 重新提取
            String encryptedContent = null;
            for (ResponseOutputItem item : response.output()) {
                if (item.isReasoning()) {
                    ResponseReasoningItem reasoning = item.asReasoning();
                    if (encryptedContent == null) {
                        Optional<String> ec = reasoning.encryptedContent();
                        if (ec.isPresent() && !ec.get().isEmpty()) {
                            encryptedContent = ec.get();
                        }
                    }
                }
            }
            String reasoningText = reasoningTextAccumulator.toString();

            // 构建元数据
            String responseId = response.id();
            Map<String, Object> metadata = ResponsesHelper.extractResponseMetadata(response);
            String finishReason = (String) metadata.get(OpenAIOfficialConstants.MD_RESPONSE_STATUS);
            ChatUsage usage = ResponsesHelper.extractUsage(response, startTime);

            // 推理元数据放在 ThinkingBlock 上，用于推理重放
            List<ContentBlock> terminalContent = new ArrayList<>();
            if (encryptedContent != null || !reasoningText.isEmpty()) {
                Map<String, Object> thinkingMetadata = new HashMap<>();
                if (encryptedContent != null) {
                    thinkingMetadata.put(
                            OpenAIOfficialConstants.MD_REASONING_ENCRYPTED_CONTENT,
                            encryptedContent);
                }
                if (!reasoningText.isEmpty()) {
                    thinkingMetadata.put(OpenAIOfficialConstants.MD_REASONING_TEXT, reasoningText);
                }
                terminalContent.add(ThinkingBlock.builder().metadata(thinkingMetadata).build());
            }

            return ChatResponse.builder()
                    .id(responseId)
                    .content(terminalContent)
                    .usage(usage)
                    .metadata(metadata)
                    .finishReason(finishReason)
                    .build();
        }

        private String extractRefusal(Response response) {
            StringBuilder refusalBuilder = new StringBuilder();
            for (ResponseOutputItem item : response.output()) {
                if (item.isMessage()) {
                    for (ResponseOutputMessage.Content content : item.asMessage().content()) {
                        if (content.isRefusal()) {
                            refusalBuilder.append(content.asRefusal().refusal());
                        }
                    }
                }
            }
            return refusalBuilder.toString();
        }

        private OpenAIOfficialModelException handleFailed(ResponseFailedEvent event) {
            Response response = event.response();
            Optional<ResponseError> errorOpt = response.error();
            if (errorOpt.isEmpty()) {
                return new OpenAIOfficialModelException(
                        "OpenAI API stream failed", null, modelName);
            }
            ResponseError error = errorOpt.get();
            String message = error.message();
            try {
                message = message + " (code: " + error.code().asString() + ")";
            } catch (RuntimeException ignored) {
                // code 字段可能缺失，ResponseError.code() 使用 getRequired
            }
            return new OpenAIOfficialModelException(message, null, modelName);
        }

        private OpenAIOfficialModelException handleErrorEvent(ResponseErrorEvent event) {
            String message = event.message();
            Optional<String> code = event.code();
            if (code.isPresent()) {
                message = message + " (code: " + code.get() + ")";
            }
            return new OpenAIOfficialModelException(message, null, modelName);
        }
    }

    private record ToolCallInfo(String callId, String name, String namespace) {}
}
