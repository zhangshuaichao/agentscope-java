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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.openai.core.JsonField;
import com.openai.core.JsonMissing;
import com.openai.core.JsonValue;
import com.openai.core.http.Headers;
import com.openai.core.http.StreamResponse;
import com.openai.errors.BadRequestException;
import com.openai.errors.InternalServerException;
import com.openai.errors.NotFoundException;
import com.openai.errors.OpenAIInvalidDataException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIRetryableException;
import com.openai.errors.PermissionDeniedException;
import com.openai.errors.RateLimitException;
import com.openai.errors.SseException;
import com.openai.errors.UnauthorizedException;
import com.openai.errors.UnexpectedStatusCodeException;
import com.openai.errors.UnprocessableEntityException;
import com.openai.models.ErrorObject;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCodeInterpreterToolCall;
import com.openai.models.responses.ResponseCompletedEvent;
import com.openai.models.responses.ResponseError;
import com.openai.models.responses.ResponseErrorEvent;
import com.openai.models.responses.ResponseFailedEvent;
import com.openai.models.responses.ResponseFileSearchToolCall;
import com.openai.models.responses.ResponseFunctionCallArgumentsDeltaEvent;
import com.openai.models.responses.ResponseFunctionCallArgumentsDoneEvent;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseFunctionWebSearch;
import com.openai.models.responses.ResponseImageGenCallPartialImageEvent;
import com.openai.models.responses.ResponseIncompleteEvent;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputItemAddedEvent;
import com.openai.models.responses.ResponseOutputItemDoneEvent;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseOutputRefusal;
import com.openai.models.responses.ResponseOutputText;
import com.openai.models.responses.ResponseReasoningItem;
import com.openai.models.responses.ResponseReasoningSummaryTextDeltaEvent;
import com.openai.models.responses.ResponseReasoningTextDeltaEvent;
import com.openai.models.responses.ResponseStatus;
import com.openai.models.responses.ResponseStreamEvent;
import com.openai.models.responses.ResponseTextDeltaEvent;
import com.openai.models.responses.ResponseToolSearchCall;
import com.openai.models.responses.ResponseToolSearchOutputItem;
import com.openai.models.responses.ResponseUsage;
import com.openai.models.responses.Tool;
import com.openai.models.responses.ToolChoiceOptions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

/**
 * 构建 SDK 对象的共享测试夹具工厂。
 *
 * <p>提供 {@link OpenAIErrorTranslatorTest} 及后续测试所需的各类 SDK 异常创建辅助方法，以及响应解析测试使用的 SDK {@link Response} 构建方法。所有 SDK 异常构建器都要求 {@link Headers} 对象，本工厂默认提供空对象。
 */
public final class TestSdkFixtures {

    public static final String MODEL_NAME = "gpt-4o";
    private static final String DEFAULT_RESPONSE_ID = "resp_test_123";
    private static final String DEFAULT_MSG_ID = "msg_test_001";
    private static final double DEFAULT_CREATED_AT = 1697000000.5;
    private static final double DEFAULT_COMPLETED_AT = 1697000001.5;

    private TestSdkFixtures() {}

    // ── 类型化 HTTP 异常 ──────────────────────────────────────────────

    public static BadRequestException badRequest(String message) {
        return BadRequestException.builder()
                .headers(emptyHeaders())
                .error(errorWithMessage(message))
                .build();
    }

    public static UnauthorizedException unauthorized(String message) {
        return UnauthorizedException.builder()
                .headers(emptyHeaders())
                .error(errorWithMessage(message))
                .build();
    }

    public static PermissionDeniedException permissionDenied(String message) {
        return PermissionDeniedException.builder()
                .headers(emptyHeaders())
                .error(errorWithMessage(message))
                .build();
    }

    public static NotFoundException notFound(String message) {
        return NotFoundException.builder()
                .headers(emptyHeaders())
                .error(errorWithMessage(message))
                .build();
    }

    public static UnprocessableEntityException unprocessableEntity(String message) {
        return UnprocessableEntityException.builder()
                .headers(emptyHeaders())
                .error(errorWithMessage(message))
                .build();
    }

    public static RateLimitException rateLimit(String message) {
        return RateLimitException.builder()
                .headers(emptyHeaders())
                .error(errorWithMessage(message))
                .build();
    }

    // ── 可变状态码异常 ─────────────────────────────────────────

    public static InternalServerException internalServer(int statusCode, String message) {
        return InternalServerException.builder()
                .statusCode(statusCode)
                .headers(emptyHeaders())
                .error(errorWithMessage(message))
                .build();
    }

    public static UnexpectedStatusCodeException unexpectedStatusCode(
            int statusCode, String message) {
        return UnexpectedStatusCodeException.builder()
                .statusCode(statusCode)
                .headers(emptyHeaders())
                .error(errorWithMessage(message))
                .build();
    }

    public static SseException sseException(int statusCode, String message) {
        return SseException.builder()
                .statusCode(statusCode)
                .headers(emptyHeaders())
                .error(errorWithMessage(message))
                .build();
    }

    // ── 非 HTTP 异常 ────────────────────────────────────────────────

    public static OpenAIIoException ioException(String message) {
        return new OpenAIIoException(message);
    }

    public static OpenAIRetryableException retryableException(String message) {
        return new OpenAIRetryableException(message);
    }

    public static OpenAIInvalidDataException invalidDataException(String message) {
        return new OpenAIInvalidDataException(message);
    }

    public static TimeoutException timeoutException(String message) {
        return new TimeoutException(message);
    }

    // ── 辅助方法 ────────────────────────────────────────────────────────────

    public static Headers emptyHeaders() {
        return Headers.builder().build();
    }

    /**
     * 创建含 X-Should-Retry 请求头值的 Headers 对象。
     *
     * @param shouldRetry X-Should-Retry 值，"true" 或 "false"
     * @return 含 X-Should-Retry 条目的请求头
     */
    public static Headers headersWithShouldRetry(String shouldRetry) {
        return Headers.builder().put("X-Should-Retry", shouldRetry).build();
    }

    /**
     * 创建 message 字段为指定字符串的 {@link ErrorObject}，使类型化 HTTP 异常，如 {@link BadRequestException}，能通过自身 {@code getMessage()} 提供该消息。
     *
     * <p>填充全部四个必填字段，以满足构建器的 {@code checkRequired} 验证；code/param/type 使用占位值。
     *
     * @param message 嵌入 ErrorObject 的错误消息
     * @return 完整构建的 ErrorObject
     */
    private static ErrorObject errorWithMessage(String message) {
        return ErrorObject.builder()
                .message(message)
                .code("test_error")
                .param("test_param")
                .type("invalid_request_error")
                .build();
    }

    // ── 响应构建器 ──────────────────────────────────────────────────

    /**
     * 使用给定输出项、状态和可选用量构建 Response。
     *
     * @param output 输出项
     * @param status 响应状态，如 {@link ResponseStatus#COMPLETED}，或 null
     * @param usage 用量对象，或 null
     * @return 完整构建的 Response
     */
    public static Response response(
            List<ResponseOutputItem> output, ResponseStatus status, ResponseUsage usage) {
        return response(output, status, usage, null, null, null, null);
    }

    public static Response response(
            List<ResponseOutputItem> output,
            ResponseStatus status,
            ResponseUsage usage,
            Double completedAt,
            Response.ServiceTier serviceTier,
            Response.IncompleteDetails incompleteDetails,
            ResponseError error) {
        Response.Builder builder =
                Response.builder()
                        .id(DEFAULT_RESPONSE_ID)
                        .createdAt(DEFAULT_CREATED_AT)
                        .output(output)
                        .error(Optional.empty())
                        .incompleteDetails(Optional.empty())
                        .instructions("test")
                        .metadata(Optional.empty())
                        .model("gpt-4o")
                        .parallelToolCalls(true)
                        .temperature(Optional.empty())
                        .toolChoice(ToolChoiceOptions.AUTO)
                        .tools(List.of())
                        .topP(Optional.empty());
        if (status != null) {
            builder.status(status);
        }
        if (usage != null) {
            builder.usage(usage);
        }
        if (completedAt != null) {
            builder.completedAt(completedAt);
        }
        if (serviceTier != null) {
            builder.serviceTier(serviceTier);
        }
        if (incompleteDetails != null) {
            builder.incompleteDetails(incompleteDetails);
        }
        if (error != null) {
            builder.error(error);
        }
        return builder.build();
    }

    /**
     * 使用指定输出项构建基本的已完成 Response，不含用量。
     */
    public static Response completedResponse(List<ResponseOutputItem> output) {
        return response(output, ResponseStatus.COMPLETED, null);
    }

    /**
     * 构建仅含一条消息的 Response，消息含一个 OutputText 部分。
     */
    public static Response textResponse(String text) {
        return completedResponse(List.of(messageItem(text)));
    }

    /**
     * 构建仅含一条消息的 Response，消息含 Refusal 部分。
     */
    public static Response refusalResponse(String refusal) {
        ResponseOutputMessage msg =
                ResponseOutputMessage.builder()
                        .id(DEFAULT_MSG_ID)
                        .addContent(ResponseOutputRefusal.builder().refusal(refusal).build())
                        .status(ResponseOutputMessage.Status.COMPLETED)
                        .build();
        return completedResponse(List.of(ResponseOutputItem.ofMessage(msg)));
    }

    /**
     * 构建仅含一个 reasoning 项的 Response。
     */
    public static Response reasoningResponse(
            String summaryText, String encryptedContent, String reasoningText) {
        return completedResponse(
                List.of(reasoningItem(summaryText, encryptedContent, reasoningText)));
    }

    /**
     * 构建仅含一次函数调用的 Response。
     */
    public static Response functionCallResponse(String callId, String name, String arguments) {
        return completedResponse(List.of(functionCallItem(callId, name, arguments)));
    }

    /**
     * 构建交错排列输出项的 Response。
     */
    public static Response interleavedResponse() {
        List<ResponseOutputItem> items = new ArrayList<>();
        items.add(messageItem("Hello"));
        items.add(reasoningItem("thinking", "encrypted123", "raw reasoning"));
        items.add(messageItem("World"));
        items.add(functionCallItem("call_1", "get_weather", "{\"city\":\"SF\"}"));
        return completedResponse(items);
    }

    /**
     * 构建含用量数据的 Response。
     */
    public static Response usageResponse(
            long input, long output, long cached, long cacheWrite, long reasoning) {
        ResponseUsage usage = usage(input, output, cached, cacheWrite, reasoning);
        return response(List.of(messageItem("test")), ResponseStatus.COMPLETED, usage);
    }

    /**
     * 构建已完成 Response，并设置 completedAt、serviceTier、incompleteDetails 和 error。
     */
    public static Response fullMetadataResponse() {
        return response(
                List.of(messageItem("hello")),
                ResponseStatus.INCOMPLETE,
                null,
                DEFAULT_COMPLETED_AT,
                Response.ServiceTier.PRIORITY,
                Response.IncompleteDetails.builder()
                        .reason(Response.IncompleteDetails.Reason.MAX_OUTPUT_TOKENS)
                        .build(),
                ResponseError.builder()
                        .code(ResponseError.Code.of("server_error"))
                        .message("Something went wrong")
                        .build());
    }

    // ── 输出项构建器 ────────────────────────────────────────────────

    /**
     * 构建含一个 OutputText 内容部分的 ResponseOutputMessage。
     */
    public static ResponseOutputItem messageItem(String text) {
        ResponseOutputMessage msg =
                ResponseOutputMessage.builder()
                        .id(DEFAULT_MSG_ID)
                        .addContent(
                                ResponseOutputText.builder()
                                        .text(text)
                                        .annotations(List.of())
                                        .build())
                        .status(ResponseOutputMessage.Status.COMPLETED)
                        .build();
        return ResponseOutputItem.ofMessage(msg);
    }

    /**
     * 构建含多个 OutputText 内容部分的 ResponseOutputMessage。
     */
    public static ResponseOutputItem messageItemMultiText(String... texts) {
        ResponseOutputMessage.Builder builder =
                ResponseOutputMessage.builder()
                        .id(DEFAULT_MSG_ID)
                        .status(ResponseOutputMessage.Status.COMPLETED);
        for (String text : texts) {
            builder.addContent(
                    ResponseOutputText.builder().text(text).annotations(List.of()).build());
        }
        return ResponseOutputItem.ofMessage(builder.build());
    }

    /**
     * 构建含一个带 URL 引用的 OutputText 内容部分的 ResponseOutputMessage。
     */
    public static ResponseOutputItem messageItemWithUrlCitation(
            String text, String title, String url, long startIndex, long endIndex) {
        ResponseOutputText.Annotation.UrlCitation citation =
                ResponseOutputText.Annotation.UrlCitation.builder()
                        .startIndex(startIndex)
                        .endIndex(endIndex)
                        .title(title)
                        .url(url)
                        .build();
        ResponseOutputMessage msg =
                ResponseOutputMessage.builder()
                        .id(DEFAULT_MSG_ID)
                        .addContent(
                                ResponseOutputText.builder()
                                        .text(text)
                                        .annotations(
                                                List.of(
                                                        ResponseOutputText.Annotation.ofUrlCitation(
                                                                citation)))
                                        .build())
                        .status(ResponseOutputMessage.Status.COMPLETED)
                        .build();
        return ResponseOutputItem.ofMessage(msg);
    }

    /**
     * 构建含一个带容器文件引用的 OutputText 内容部分的 ResponseOutputMessage。
     */
    public static ResponseOutputItem messageItemWithContainerFileCitation(
            String text,
            String containerId,
            String fileId,
            String filename,
            long startIndex,
            long endIndex) {
        ResponseOutputText.Annotation.ContainerFileCitation citation =
                ResponseOutputText.Annotation.ContainerFileCitation.builder()
                        .containerId(containerId)
                        .endIndex(endIndex)
                        .fileId(fileId)
                        .filename(filename)
                        .startIndex(startIndex)
                        .build();
        ResponseOutputMessage msg =
                ResponseOutputMessage.builder()
                        .id(DEFAULT_MSG_ID)
                        .addContent(
                                ResponseOutputText.builder()
                                        .text(text)
                                        .annotations(
                                                List.of(
                                                        ResponseOutputText.Annotation
                                                                .ofContainerFileCitation(citation)))
                                        .build())
                        .status(ResponseOutputMessage.Status.COMPLETED)
                        .build();
        return ResponseOutputItem.ofMessage(msg);
    }

    /**
     * 构建含摘要、加密内容与推理文本的 ResponseReasoningItem。
     */
    public static ResponseOutputItem reasoningItem(
            String summaryText, String encryptedContent, String reasoningText) {
        ResponseReasoningItem.Builder builder = ResponseReasoningItem.builder().id("rs_test_001");
        if (summaryText != null) {
            builder.addSummary(ResponseReasoningItem.Summary.builder().text(summaryText).build());
        }
        if (encryptedContent != null) {
            builder.encryptedContent(encryptedContent);
        }
        if (reasoningText != null) {
            builder.content(
                    List.of(ResponseReasoningItem.Content.builder().text(reasoningText).build()));
        }
        return ResponseOutputItem.ofReasoning(builder.build());
    }

    /**
     * 构建包装在 ResponseOutputItem 中的 ResponseFunctionToolCall。
     */
    public static ResponseOutputItem functionCallItem(
            String callId, String name, String arguments) {
        return ResponseOutputItem.ofFunctionCall(
                ResponseFunctionToolCall.builder()
                        .arguments(arguments)
                        .callId(callId)
                        .name(name)
                        .build());
    }

    /**
     * 构建 file_search_call 输出项，属于解析器不处理的未知类型。
     */
    public static ResponseOutputItem fileSearchItem() {
        return ResponseOutputItem.ofFileSearchCall(
                ResponseFileSearchToolCall.builder()
                        .id("fs_test_001")
                        .queries(List.of("test query"))
                        .status(ResponseFileSearchToolCall.Status.COMPLETED)
                        .build());
    }

    /**
     * 构建已完成的 web_search_call 输出项。
     */
    public static ResponseOutputItem webSearchItem() {
        return webSearchItem("OpenAI Responses");
    }

    /**
     * 使用指定查询构建已完成的 web_search_call 输出项。
     */
    public static ResponseOutputItem webSearchItem(String query) {
        return ResponseOutputItem.ofWebSearchCall(
                ResponseFunctionWebSearch.builder()
                        .id("ws_test_001")
                        .action(
                                ResponseFunctionWebSearch.Action.ofSearch(
                                        ResponseFunctionWebSearch.Action.Search.builder()
                                                .query(query)
                                                .build()))
                        .status(ResponseFunctionWebSearch.Status.COMPLETED)
                        .build());
    }

    /**
     * 构建已完成的 code_interpreter_call 输出项。
     */
    public static ResponseOutputItem codeInterpreterItem() {
        return ResponseOutputItem.ofCodeInterpreterCall(
                ResponseCodeInterpreterToolCall.builder()
                        .id("ci_test_001")
                        .code("print('hello')")
                        .containerId("container_001")
                        .addLogsOutput("hello")
                        .status(ResponseCodeInterpreterToolCall.Status.COMPLETED)
                        .build());
    }

    /**
     * 构建含日志与图像的已完成 code_interpreter_call 输出项。
     */
    public static ResponseOutputItem codeInterpreterItemWithImageOutput() {
        return ResponseOutputItem.ofCodeInterpreterCall(
                ResponseCodeInterpreterToolCall.builder()
                        .id("ci_image_001")
                        .code("print('hello')")
                        .containerId("container_image_001")
                        .addLogsOutput("hello")
                        .addImageOutput("https://example.com/generated-image.png")
                        .status(ResponseCodeInterpreterToolCall.Status.COMPLETED)
                        .build());
    }

    /**
     * 构建已完成的 image_generation_call 输出项。
     */
    public static ResponseOutputItem imageGenerationItem() {
        return ResponseOutputItem.ofImageGenerationCall(
                ResponseOutputItem.ImageGenerationCall.builder()
                        .id("ig_test_001")
                        .result("image-result")
                        .status(ResponseOutputItem.ImageGenerationCall.Status.COMPLETED)
                        .build());
    }

    /**
     * 构建托管 tool_search_call 项，服务端执行，call_id 为 null。
     */
    public static ResponseOutputItem toolSearchCallItem() {
        return toolSearchCallItem("ts_call_001");
    }

    /**
     * 使用指定项 ID 构建托管 tool_search_call 项，服务端执行。
     */
    public static ResponseOutputItem toolSearchCallItem(String id) {
        return ResponseOutputItem.ofToolSearchCall(
                ResponseToolSearchCall.builder()
                        .id(id)
                        .arguments(JsonValue.from(Map.of("query", "weather")))
                        .callId((String) null)
                        .execution(ResponseToolSearchCall.Execution.SERVER)
                        .status(ResponseToolSearchCall.Status.COMPLETED)
                        .build());
    }

    /**
     * 构建独立的托管 tool_search_output 项，服务端执行，call_id 为 null。
     */
    public static ResponseOutputItem toolSearchOutputItem() {
        return toolSearchOutputItem("ts_output_001");
    }

    /**
     * 使用指定项 ID 构建独立的托管 tool_search_output 项。
     */
    public static ResponseOutputItem toolSearchOutputItem(String id) {
        return ResponseOutputItem.ofToolSearchOutput(
                ResponseToolSearchOutputItem.builder()
                        .id(id)
                        .callId((String) null)
                        .execution(ResponseToolSearchOutputItem.Execution.SERVER)
                        .status(ResponseToolSearchOutputItem.Status.COMPLETED)
                        .tools(
                                List.of(
                                        Tool.ofCodeInterpreter(
                                                Tool.CodeInterpreter.builder()
                                                        .container("container_1")
                                                        .build())))
                        .build());
    }

    // ── 用量构建器 ───────────────────────────────────────────────────────

    public static ResponseUsage usage(
            long input, long output, long cached, long cacheWrite, long reasoning) {
        return ResponseUsage.builder()
                .inputTokens(input)
                .inputTokensDetails(
                        ResponseUsage.InputTokensDetails.builder()
                                .cacheWriteTokens(cacheWrite)
                                .cachedTokens(cached)
                                .build())
                .outputTokens(output)
                .outputTokensDetails(
                        ResponseUsage.OutputTokensDetails.builder()
                                .reasoningTokens(reasoning)
                                .build())
                .totalTokens(input + output)
                .build();
    }

    /**
     * 构建细分字段在 JSON 中缺失的用量，模拟 DeepSeek Responses API 等提供商：报告 {@code cached_tokens} 却省略 {@code cache_write_tokens}，或完全省略 {@code reasoning_tokens}。SDK 反序列化器接受此类载荷，但必填字段访问器读取时抛异常，参见问题 #3564。
     *
     * @param input 输入 token 数
     * @param output 输出 token 数
     * @param cached 缓存 token 数
     * @return 缺少缓存写入与推理细分字段的用量
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static ResponseUsage usageWithMissingBreakdownFields(
            long input, long output, long cached) {
        JsonField missing = JsonMissing.of();
        return ResponseUsage.builder()
                .inputTokens(input)
                .inputTokensDetails(
                        ResponseUsage.InputTokensDetails.builder()
                                .cacheWriteTokens(missing)
                                .cachedTokens(cached)
                                .build())
                .outputTokens(output)
                .outputTokensDetails(
                        ResponseUsage.OutputTokensDetails.builder()
                                .reasoningTokens(missing)
                                .build())
                .totalTokens(input + output)
                .build();
    }

    // ── 流式事件夹具 ──────────────────────────────────────────

    public static StreamResponse<ResponseStreamEvent> streamOf(List<ResponseStreamEvent> events) {
        return new StreamResponse<ResponseStreamEvent>() {
            private final Stream<ResponseStreamEvent> stream = events.stream();

            @Override
            public Stream<ResponseStreamEvent> stream() {
                return stream;
            }

            @Override
            public void close() {
                stream.close();
            }
        };
    }

    public static ResponseStreamEvent textDeltaEvent(String delta, String itemId) {
        ResponseTextDeltaEvent textDelta =
                ResponseTextDeltaEvent.builder()
                        .delta(delta)
                        .itemId(itemId)
                        .contentIndex(0L)
                        .outputIndex(0L)
                        .sequenceNumber(0L)
                        .logprobs(List.of())
                        .build();
        ResponseStreamEvent event = mock(ResponseStreamEvent.class);
        when(event.isOutputTextDelta()).thenReturn(true);
        when(event.outputTextDelta()).thenReturn(Optional.of(textDelta));
        when(event.asOutputTextDelta()).thenReturn(textDelta);
        return event;
    }

    public static ResponseStreamEvent reasoningSummaryDeltaEvent(String delta, String itemId) {
        ResponseReasoningSummaryTextDeltaEvent evt =
                ResponseReasoningSummaryTextDeltaEvent.builder()
                        .delta(delta)
                        .itemId(itemId)
                        .outputIndex(0L)
                        .sequenceNumber(0L)
                        .summaryIndex(0L)
                        .build();
        ResponseStreamEvent event = mock(ResponseStreamEvent.class);
        when(event.isReasoningSummaryTextDelta()).thenReturn(true);
        when(event.reasoningSummaryTextDelta()).thenReturn(Optional.of(evt));
        when(event.asReasoningSummaryTextDelta()).thenReturn(evt);
        return event;
    }

    public static ResponseStreamEvent reasoningTextDeltaEvent(String delta, String itemId) {
        ResponseReasoningTextDeltaEvent evt =
                ResponseReasoningTextDeltaEvent.builder()
                        .delta(delta)
                        .itemId(itemId)
                        .contentIndex(0L)
                        .outputIndex(0L)
                        .sequenceNumber(0L)
                        .build();
        ResponseStreamEvent event = mock(ResponseStreamEvent.class);
        when(event.isReasoningTextDelta()).thenReturn(true);
        when(event.reasoningTextDelta()).thenReturn(Optional.of(evt));
        when(event.asReasoningTextDelta()).thenReturn(evt);
        return event;
    }

    public static ResponseStreamEvent functionCallArgsDeltaEvent(String delta, String itemId) {
        ResponseFunctionCallArgumentsDeltaEvent evt =
                ResponseFunctionCallArgumentsDeltaEvent.builder()
                        .delta(delta)
                        .itemId(itemId)
                        .outputIndex(0L)
                        .sequenceNumber(0L)
                        .build();
        ResponseStreamEvent event = mock(ResponseStreamEvent.class);
        when(event.isFunctionCallArgumentsDelta()).thenReturn(true);
        when(event.functionCallArgumentsDelta()).thenReturn(Optional.of(evt));
        when(event.asFunctionCallArgumentsDelta()).thenReturn(evt);
        return event;
    }

    public static ResponseStreamEvent functionCallArgsDoneEvent(String arguments, String itemId) {
        ResponseFunctionCallArgumentsDoneEvent evt =
                ResponseFunctionCallArgumentsDoneEvent.builder()
                        .arguments(arguments)
                        .itemId(itemId)
                        .outputIndex(0L)
                        .sequenceNumber(0L)
                        .build();
        ResponseStreamEvent event = mock(ResponseStreamEvent.class);
        when(event.isFunctionCallArgumentsDone()).thenReturn(true);
        when(event.functionCallArgumentsDone()).thenReturn(Optional.of(evt));
        when(event.asFunctionCallArgumentsDone()).thenReturn(evt);
        return event;
    }

    public static ResponseStreamEvent outputItemAddedEvent(ResponseOutputItem item) {
        ResponseOutputItemAddedEvent evt =
                ResponseOutputItemAddedEvent.builder()
                        .item(item)
                        .outputIndex(0L)
                        .sequenceNumber(0L)
                        .build();
        ResponseStreamEvent event = mock(ResponseStreamEvent.class);
        when(event.isOutputItemAdded()).thenReturn(true);
        when(event.outputItemAdded()).thenReturn(Optional.of(evt));
        when(event.asOutputItemAdded()).thenReturn(evt);
        return event;
    }

    public static ResponseStreamEvent outputItemDoneEvent(ResponseOutputItem item) {
        ResponseOutputItemDoneEvent evt =
                ResponseOutputItemDoneEvent.builder()
                        .item(item)
                        .outputIndex(0L)
                        .sequenceNumber(0L)
                        .build();
        ResponseStreamEvent event = mock(ResponseStreamEvent.class);
        when(event.isOutputItemDone()).thenReturn(true);
        when(event.outputItemDone()).thenReturn(Optional.of(evt));
        when(event.asOutputItemDone()).thenReturn(evt);
        return event;
    }

    public static ResponseStreamEvent imageGenerationPartialImageEvent(
            String itemId, long partialImageIndex, String base64, String outputFormat) {
        ResponseImageGenCallPartialImageEvent evt =
                ResponseImageGenCallPartialImageEvent.builder()
                        .itemId(itemId)
                        .outputIndex(0L)
                        .partialImageB64(base64)
                        .partialImageIndex(partialImageIndex)
                        .sequenceNumber(0L)
                        .outputFormat(outputFormat)
                        .build();
        ResponseStreamEvent event = mock(ResponseStreamEvent.class);
        when(event.isImageGenerationCallPartialImage()).thenReturn(true);
        when(event.imageGenerationCallPartialImage()).thenReturn(Optional.of(evt));
        when(event.asImageGenerationCallPartialImage()).thenReturn(evt);
        return event;
    }

    public static ResponseStreamEvent completedEvent(Response response) {
        ResponseCompletedEvent evt =
                ResponseCompletedEvent.builder().response(response).sequenceNumber(0L).build();
        ResponseStreamEvent event = mock(ResponseStreamEvent.class);
        when(event.isCompleted()).thenReturn(true);
        when(event.completed()).thenReturn(Optional.of(evt));
        when(event.asCompleted()).thenReturn(evt);
        return event;
    }

    public static ResponseStreamEvent incompleteEvent(Response response) {
        ResponseIncompleteEvent evt =
                ResponseIncompleteEvent.builder().response(response).sequenceNumber(0L).build();
        ResponseStreamEvent event = mock(ResponseStreamEvent.class);
        when(event.isIncomplete()).thenReturn(true);
        when(event.incomplete()).thenReturn(Optional.of(evt));
        when(event.asIncomplete()).thenReturn(evt);
        return event;
    }

    public static ResponseStreamEvent failedEvent(Response response) {
        ResponseFailedEvent evt =
                ResponseFailedEvent.builder().response(response).sequenceNumber(0L).build();
        ResponseStreamEvent event = mock(ResponseStreamEvent.class);
        when(event.isFailed()).thenReturn(true);
        when(event.failed()).thenReturn(Optional.of(evt));
        when(event.asFailed()).thenReturn(evt);
        return event;
    }

    public static ResponseStreamEvent errorEvent(String message, String code) {
        ResponseErrorEvent.Builder builder =
                ResponseErrorEvent.builder().message(message).sequenceNumber(0L);
        builder.param((String) null);
        if (code != null) {
            builder.code(code);
        }
        ResponseErrorEvent evt = builder.build();
        ResponseStreamEvent event = mock(ResponseStreamEvent.class);
        when(event.isError()).thenReturn(true);
        when(event.error()).thenReturn(Optional.of(evt));
        when(event.asError()).thenReturn(evt);
        return event;
    }

    public static ResponseStreamEvent noopEvent() {
        return mock(ResponseStreamEvent.class);
    }
}
