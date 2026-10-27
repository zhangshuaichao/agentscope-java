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

package io.agentscope.core.a2a.server.transport.jsonrpc;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.google.protobuf.Empty;
import com.google.protobuf.MessageOrBuilder;
import io.agentscope.core.a2a.server.constants.A2aServerConstants;
import io.agentscope.core.a2a.server.transport.TransportWrapper;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.Flow;
import org.a2aproject.sdk.common.A2AHeaders;
import org.a2aproject.sdk.grpc.utils.JSONRPCUtils;
import org.a2aproject.sdk.grpc.utils.ProtoUtils;
import org.a2aproject.sdk.jsonrpc.common.json.IdJsonMappingException;
import org.a2aproject.sdk.jsonrpc.common.json.InvalidParamsJsonMappingException;
import org.a2aproject.sdk.jsonrpc.common.json.JsonMappingException;
import org.a2aproject.sdk.jsonrpc.common.json.JsonProcessingException;
import org.a2aproject.sdk.jsonrpc.common.json.MethodNotFoundJsonMappingException;
import org.a2aproject.sdk.jsonrpc.common.wrappers.A2AErrorResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.A2ARequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.A2AResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.CancelTaskRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.CancelTaskResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.CreateTaskPushNotificationConfigRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.CreateTaskPushNotificationConfigResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.DeleteTaskPushNotificationConfigRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.DeleteTaskPushNotificationConfigResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.GetExtendedAgentCardRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.GetExtendedAgentCardResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.GetTaskPushNotificationConfigRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.GetTaskPushNotificationConfigResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.GetTaskRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.GetTaskResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.ListTaskPushNotificationConfigsRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.ListTaskPushNotificationConfigsResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.NonStreamingJSONRPCRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendMessageRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendMessageResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendStreamingMessageRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendStreamingMessageResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SubscribeToTaskRequest;
import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.auth.UnauthenticatedUser;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.InternalError;
import org.a2aproject.sdk.spec.InvalidParamsError;
import org.a2aproject.sdk.spec.InvalidRequestError;
import org.a2aproject.sdk.spec.JSONParseError;
import org.a2aproject.sdk.spec.MethodNotFoundError;
import org.a2aproject.sdk.spec.TransportProtocol;
import org.a2aproject.sdk.spec.UnsupportedOperationError;
import org.a2aproject.sdk.transport.jsonrpc.context.JSONRPCContextKeys;
import org.a2aproject.sdk.transport.jsonrpc.handler.JSONRPCHandler;
import org.reactivestreams.FlowAdapters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.BufferOverflowStrategy;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SynchronousSink;

/**
 * JSON-RPC 传输请求包装器。
 *
 * <p>包装所有 JSON-RPC 请求预处理逻辑；开发者应从请求中提取字符串请求体、
 * 请求头和元数据。
 * <p>预处理包括：
 * <ul>
 *   <li>将请求体反序列化为 JSON-RPC 请求。</li>
 *   <li>判断是否为流式请求。</li>
 *   <li>根据 JSON-RPC 方法、请求头与全部元数据构建 {@link ServerCallContext}。</li>
 *   <li>处理 JSON-RPC 请求处理中的错误和异常。</li>
 *   <li>通过 A2A SDK 将响应序列化为 JSON，不依赖宿主框架的对象序列化方式。</li>
 * </ul>
 */
public class JsonRpcTransportWrapper implements TransportWrapper<String, Object> {

    private static final Logger log = LoggerFactory.getLogger(JsonRpcTransportWrapper.class);

    private static final String STREAMING_BACKPRESSURE_BUFFER_SIZE_PROPERTY =
            "agentscope.a2a.streaming.backpressure-buffer-size";

    private static final int DEFAULT_STREAMING_BACKPRESSURE_BUFFER_SIZE = 8192;

    private static final String INTERNAL_ERROR_MESSAGE = "Internal error";

    private static final int STREAMING_BACKPRESSURE_BUFFER_SIZE =
            Integer.getInteger(
                    STREAMING_BACKPRESSURE_BUFFER_SIZE_PROPERTY,
                    DEFAULT_STREAMING_BACKPRESSURE_BUFFER_SIZE);

    private final JSONRPCHandler jsonRpcHandler;

    public JsonRpcTransportWrapper(JSONRPCHandler jsonrpcHandler) {
        this.jsonRpcHandler = jsonrpcHandler;
    }

    @Override
    public String getTransportType() {
        return TransportProtocol.JSONRPC.asString();
    }

    /**
     * 处理 JSON-RPC 请求，包括流式与非流式请求。
     *
     * @param body JSON-RPC 请求体字符串
     * @param headers JSON-RPC 请求头映射
     * @param metadata 来自请求或开发者的其他 JSON-RPC 请求元数据
     * @return JSON 字符串形式的 JSON-RPC 响应；流式请求返回 JSON 字符串的
     *     {@link Flux}，每个字符串对应一条响应。错误作为 JSON-RPC 错误响应返回
     */
    @Override
    public Object handleRequest(
            String body, Map<String, String> headers, Map<String, Object> metadata) {
        ServerCallContext context = buildServerCallContext(headers, metadata);
        A2ARequest<?> request;
        try {
            if (body == null || body.isBlank()) {
                return toJson(new A2AErrorResponse(new JSONParseError("Empty request body")));
            }
            if (!JsonParser.parseString(body).isJsonObject()) {
                return toJson(new A2AErrorResponse(new InvalidRequestError()));
            }
            request = JSONRPCUtils.parseRequestBody(body, null);
        } catch (InvalidParamsJsonMappingException e) {
            return toJson(
                    new A2AErrorResponse(
                            e.getId(), new InvalidParamsError(null, e.getMessage(), null)));
        } catch (MethodNotFoundJsonMappingException e) {
            return toJson(
                    new A2AErrorResponse(
                            e.getId(), new MethodNotFoundError(null, e.getMessage(), null)));
        } catch (IdJsonMappingException e) {
            return toJson(
                    new A2AErrorResponse(
                            e.getId(), new InvalidRequestError(null, e.getMessage(), null)));
        } catch (JsonMappingException e) {
            // 请求 ID 无效及某些参数无效时抛出，异常中不带 ID。
            Object id = requestId(body);
            return toJson(
                    null == id
                            ? new A2AErrorResponse(
                                    new InvalidRequestError(null, e.getMessage(), null))
                            : new A2AErrorResponse(
                                    id, new InvalidParamsError(null, e.getMessage(), null)));
        } catch (JsonSyntaxException | JsonProcessingException e) {
            log.error("JSON parsing error: ", e);
            return toJson(new A2AErrorResponse(new JSONParseError(e.getMessage())));
        } catch (RuntimeException e) {
            log.error("Failed to parse JSON-RPC request:", e);
            return toJson(new A2AErrorResponse(new InternalError(INTERNAL_ERROR_MESSAGE)));
        }
        boolean streaming = !(request instanceof NonStreamingJSONRPCRequest);
        context.getState().put(JSONRPCContextKeys.METHOD_NAME_KEY, request.getMethod());
        context.getState().put(A2aServerConstants.ContextKeys.IS_STREAM_KEY, streaming);
        try {
            if (streaming) {
                Flux<String> result =
                        handleStreamRequest(request, context)
                                .<String>handle(JsonRpcTransportWrapper::toJsonOrEnd);
                log.info("Handling streaming request, returning SSE Flux");
                return result;
            }
            String result =
                    toJson(
                            handleNonStreamRequest(
                                    (NonStreamingJSONRPCRequest<?>) request, context));
            log.info("Handling non-streaming request, returning JSON response");
            return result;
        } catch (A2AError e) {
            return toJson(new A2AErrorResponse(request.getId(), e));
        } catch (Throwable t) {
            log.error("Handle JSON-RPC request error:", t);
            return toJson(
                    new A2AErrorResponse(
                            request.getId(), new InternalError(INTERNAL_ERROR_MESSAGE)));
        }
    }

    /**
     * 按 SDK 方式读取的请求 ID；请求没有有效 ID 时为 {@code null}。
     */
    private static Object requestId(String body) {
        try {
            JsonElement id = JsonParser.parseString(body).getAsJsonObject().get("id");
            if (null == id || !id.isJsonPrimitive()) {
                return null;
            }
            try {
                return id.getAsInt();
            } catch (UnsupportedOperationException | NumberFormatException e) {
                return id.getAsString();
            }
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 写出 {@code response}；失败时为同一请求写出内部错误。
     */
    private static String toJson(A2AResponse<?> response) {
        try {
            return write(response);
        } catch (RuntimeException e) {
            return serializationError(response, e);
        }
    }

    /**
     * 写出流式 {@code response}。失败时，以同一请求的内部错误结束流，
     * 并取消对剩余响应的订阅。
     */
    private static void toJsonOrEnd(A2AResponse<?> response, SynchronousSink<String> sink) {
        try {
            sink.next(write(response));
        } catch (RuntimeException e) {
            sink.next(serializationError(response, e));
            sink.complete();
        }
    }

    private static String serializationError(A2AResponse<?> response, RuntimeException cause) {
        log.error(
                "Failed to serialize JSON-RPC response: {}", response.getClass().getName(), cause);
        return JSONRPCUtils.toJsonRPCErrorResponse(
                response.getId(), new InternalError("Failed to serialize JSON-RPC response"));
    }

    private static String write(A2AResponse<?> response) {
        if (response.getError() != null) {
            return JSONRPCUtils.toJsonRPCErrorResponse(response.getId(), response.getError());
        }
        return JSONRPCUtils.toJsonRPCResultResponse(response.getId(), toProto(response));
    }

    private static MessageOrBuilder toProto(A2AResponse<?> response) {
        if (response instanceof GetTaskResponse r) {
            return ProtoUtils.ToProto.task(r.getResult());
        } else if (response instanceof CancelTaskResponse r) {
            return ProtoUtils.ToProto.task(r.getResult());
        } else if (response instanceof SendMessageResponse r) {
            return ProtoUtils.ToProto.taskOrMessage(r.getResult());
        } else if (response instanceof SendStreamingMessageResponse r) {
            return ProtoUtils.ToProto.taskOrMessageStream(r.getResult());
        } else if (response instanceof CreateTaskPushNotificationConfigResponse r) {
            return ProtoUtils.ToProto.createTaskPushNotificationConfigResponse(r.getResult());
        } else if (response instanceof GetTaskPushNotificationConfigResponse r) {
            return ProtoUtils.ToProto.getTaskPushNotificationConfigResponse(r.getResult());
        } else if (response instanceof ListTaskPushNotificationConfigsResponse r) {
            return ProtoUtils.ToProto.listTaskPushNotificationConfigsResponse(r.getResult());
        } else if (response instanceof DeleteTaskPushNotificationConfigResponse) {
            return Empty.getDefaultInstance();
        } else if (response instanceof GetExtendedAgentCardResponse r) {
            return ProtoUtils.ToProto.getExtendedCardResponse(r.getResult());
        }
        throw new IllegalArgumentException(
                "Unknown response type: " + response.getClass().getName());
    }

    private ServerCallContext buildServerCallContext(
            Map<String, String> headers, Map<String, Object> metadata) {
        Map<String, Object> state = new HashMap<>();
        state.put(JSONRPCContextKeys.HEADERS_KEY, headers);
        // TODO：支持认证后添加用户
        return new ServerCallContext(
                UnauthenticatedUser.INSTANCE,
                state,
                new HashSet<>(),
                header(headers, A2AHeaders.A2A_VERSION));
    }

    private static String header(Map<String, String> headers, String name) {
        for (Map.Entry<String, String> header : headers.entrySet()) {
            if (name.equalsIgnoreCase(header.getKey())) {
                return header.getValue();
            }
        }
        return null;
    }

    private Flux<? extends A2AResponse<?>> handleStreamRequest(
            A2ARequest<?> request, ServerCallContext context) {
        Flow.Publisher<? extends A2AResponse<?>> publisher;
        if (request instanceof SendStreamingMessageRequest req) {
            publisher = jsonRpcHandler.onMessageSendStream(req, context);
        } else if (request instanceof SubscribeToTaskRequest req) {
            // 【学习提示】重新订阅已有任务与重新发送输入是不同操作；这里委托 SDK
            // 订阅任务事件，不调用新消息发送路径，也不在包装层自行重跑智能体。
            publisher = jsonRpcHandler.onSubscribeToTask(req, context);
        } else {
            return Flux.just(generateErrorResponse(request, new UnsupportedOperationError()));
        }

        String method = (String) context.getState().get(JSONRPCContextKeys.METHOD_NAME_KEY);
        Object requestId = request.getId();
        return applyStreamingBackpressureBuffer(
                        Flux.from(FlowAdapters.toPublisher(publisher)), method, requestId)
                .delaySubscription(Duration.ofMillis(10));
    }

    private Flux<? extends A2AResponse<?>> applyStreamingBackpressureBuffer(
            Flux<? extends A2AResponse<?>> stream, String method, Object requestId) {
        if (STREAMING_BACKPRESSURE_BUFFER_SIZE <= 0) {
            return stream.onBackpressureBuffer();
        }
        return stream.onBackpressureBuffer(
                STREAMING_BACKPRESSURE_BUFFER_SIZE,
                response ->
                        log.error(
                                "JsonRpcTransportWrapper.stream backpressure buffer overflow:"
                                        + " method={}, requestId={}, bufferSize={}, dropped={}",
                                method,
                                requestId,
                                STREAMING_BACKPRESSURE_BUFFER_SIZE,
                                summarizeResponse(response)),
                BufferOverflowStrategy.ERROR);
    }

    private String summarizeResponse(A2AResponse<?> response) {
        if (response == null) {
            return "responseType=null";
        }
        return "responseType=" + response.getClass().getName();
    }

    private A2AResponse<?> handleNonStreamRequest(
            NonStreamingJSONRPCRequest<?> request, ServerCallContext context) {
        if (request instanceof GetTaskRequest req) {
            return jsonRpcHandler.onGetTask(req, context);
        } else if (request instanceof SendMessageRequest req) {
            return jsonRpcHandler.onMessageSend(req, context);
        } else if (request instanceof CancelTaskRequest req) {
            return jsonRpcHandler.onCancelTask(req, context);
        } else if (request instanceof GetTaskPushNotificationConfigRequest req) {
            return jsonRpcHandler.getPushNotificationConfig(req, context);
        } else if (request instanceof CreateTaskPushNotificationConfigRequest req) {
            return jsonRpcHandler.setPushNotificationConfig(req, context);
        } else if (request instanceof ListTaskPushNotificationConfigsRequest req) {
            return jsonRpcHandler.listPushNotificationConfigs(req, context);
        } else if (request instanceof DeleteTaskPushNotificationConfigRequest req) {
            return jsonRpcHandler.deletePushNotificationConfig(req, context);
        } else if (request instanceof GetExtendedAgentCardRequest req) {
            return jsonRpcHandler.onGetExtendedCardRequest(req, context);
        }
        // 包括 ListTasks：尚未授权调用方，开放它会列出所有会话的
        // 任务。
        return generateErrorResponse(request, new UnsupportedOperationError());
    }

    private A2AErrorResponse generateErrorResponse(A2ARequest<?> request, A2AError error) {
        return new A2AErrorResponse(request.getId(), error);
    }
}
