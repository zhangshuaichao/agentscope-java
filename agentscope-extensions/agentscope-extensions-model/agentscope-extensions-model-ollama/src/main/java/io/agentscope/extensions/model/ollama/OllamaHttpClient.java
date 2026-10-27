/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.extensions.model.ollama;

import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportException;
import io.agentscope.core.model.transport.HttpTransportFactory;
import io.agentscope.core.model.transport.TransportConstants;
import io.agentscope.core.util.JsonException;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.extensions.model.ollama.dto.OllamaEmbeddingRequest;
import io.agentscope.extensions.model.ollama.dto.OllamaEmbeddingResponse;
import io.agentscope.extensions.model.ollama.dto.OllamaRequest;
import io.agentscope.extensions.model.ollama.dto.OllamaResponse;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * Ollama API 的 HTTP 客户端。
 *
 * <p>负责与 Ollama 聊天及嵌入 API 通信，支持同步和异步（流式）请求，
 * 并提供适当的 JSON 序列化／反序列化与错误处理。
 *
 * <p>特性：
 * <ul>
 *   <li>支持同步和流式请求</li>
 *   <li>对流式响应进行 NDJSON 解析</li>
 *   <li>使用 snake_case 命名自动序列化／反序列化 JSON</li>
 *   <li>提供详细异常信息的错误处理</li>
 *   <li>可配置基础 URL 和 HTTP 传输</li>
 * </ul>
 *
 * <p>默认基础 URL 为 http://localhost:11434，即标准 Ollama 服务器地址。
 */
public class OllamaHttpClient {

    private static final Logger log = LoggerFactory.getLogger(OllamaHttpClient.class);

    /**
     * Ollama API 的默认基础 URL。
     */
    public static final String DEFAULT_BASE_URL = "http://localhost:11434";

    /**
     * 聊天 API 端点。
     */
    public static final String CHAT_ENDPOINT = "/api/chat";

    /**
     * 嵌入 API 端点。
     */
    public static final String EMBED_ENDPOINT = "/api/embed";

    private final HttpTransport transport;
    private final String baseUrl;

    /**
     * 创建新的 OllamaHttpClient。
     *
     * @param transport 使用的 HTTP 传输
     * @param baseUrl 基础 URL（null 表示使用默认值）
     */
    public OllamaHttpClient(HttpTransport transport, String baseUrl) {
        this.transport = transport;
        this.baseUrl = baseUrl != null ? baseUrl : DEFAULT_BASE_URL;
    }

    /**
     * 执行同步聊天 API 调用。
     *
     * @param request Ollama 请求
     * @return Ollama 响应
     */
    public OllamaResponse chat(OllamaRequest request) {
        return call(CHAT_ENDPOINT, request);
    }

    /**
     * 执行同步嵌入 API 调用。
     *
     * @param request 嵌入请求
     * @return 嵌入响应
     */
    public OllamaEmbeddingResponse embed(OllamaEmbeddingRequest request) {
        return call(EMBED_ENDPOINT, request, OllamaEmbeddingResponse.class);
    }

    /**
     * 同步调用指定 API 端点。
     *
     * @param endpoint API 端点
     * @param request 请求对象
     * @return Ollama 响应
     */
    public OllamaResponse call(String endpoint, Object request) {
        return call(endpoint, request, OllamaResponse.class);
    }

    /**
     * 同步调用指定 API 端点，并使用自定义响应类型。
     *
     * @param endpoint API 端点
     * @param request 请求对象
     * @param responseType 响应类
     * @param <T> 响应类型
     * @return 响应对象
     */
    public <T> T call(String endpoint, Object request, Class<T> responseType) {
        String url = baseUrl + endpoint;

        final String requestBody;
        try {
            requestBody = JsonUtils.getJsonCodec().toJson(request);
            log.debug("Ollama request to {}: {}", url, requestBody);
        } catch (JsonException e) {
            // 已知的 Jackson 受检异常，包装为 OllamaHttpException
            throw new OllamaHttpException("Failed to serialize/deserialize request", e);
        } catch (RuntimeException e) {
            // 某些序列化失败可能表现为 RuntimeException；为调用方／测试
            // 统一异常形式。
            throw new OllamaHttpException("JSON serialization error", e);
        }

        HttpRequest httpRequest =
                HttpRequest.builder()
                        .url(url)
                        .method("POST")
                        .header("Content-Type", "application/json")
                        .body(requestBody)
                        .build();

        HttpResponse httpResponse;
        try {
            httpResponse = transport.execute(httpRequest);
        } catch (HttpTransportException e) {
            throw new OllamaHttpException("HTTP transport error: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            // 捕获传输抛出的运行时异常并包装为 OllamaHttpException
            throw new OllamaHttpException(
                    "Runtime error during transport execution: " + e.getMessage(), e);
        }

        if (!httpResponse.isSuccessful()) {
            throw new OllamaHttpException(
                    "Ollama API request failed with status " + httpResponse.getStatusCode(),
                    httpResponse.getStatusCode(),
                    httpResponse.getBody());
        }

        String responseBody = httpResponse.getBody();
        log.debug("Ollama response: {}", responseBody);

        try {
            return JsonUtils.getJsonCodec().fromJson(responseBody, responseType);
        } catch (JsonException e) {
            throw new OllamaHttpException("Failed to serialize/deserialize response", e);
        } catch (RuntimeException e) {
            // 某些反序列化失败可能表现为 RuntimeException
            throw new OllamaHttpException("JSON deserialization error", e);
        }
    }

    /**
     * 执行流式聊天 API 调用。
     *
     * @param request Ollama 请求
     * @return Ollama 响应的 Flux
     */
    public Flux<OllamaResponse> stream(OllamaRequest request) {
        String url = baseUrl + CHAT_ENDPOINT;

        try {
            String requestBody = JsonUtils.getJsonCodec().toJson(request);
            log.debug("Ollama streaming request to {}: {}", url, requestBody);

            Map<String, String> headers = new HashMap<>();
            headers.put("Content-Type", "application/json");
            // 【学习提示】Ollama 的流式协议是逐行 JSON（NDJSON），并非直接把整个响应
            // 当成单个 JSON 文档；传输层按协议切分后，本层才将每项解码为响应 DTO。
            // 在 OkHttpTransport 中启用 NDJSON 解析
            headers.put(
                    TransportConstants.STREAM_FORMAT_HEADER,
                    TransportConstants.STREAM_FORMAT_NDJSON);

            HttpRequest httpRequest =
                    HttpRequest.builder()
                            .url(url)
                            .method("POST")
                            .headers(headers)
                            .body(requestBody)
                            .build();

            return transport.stream(httpRequest)
                    .map(
                            data -> {
                                try {
                                    OllamaResponse response =
                                            JsonUtils.getJsonCodec()
                                                    .fromJson(data, OllamaResponse.class);
                                    if (response.getError() != null) {
                                        log.error(
                                                "Ollama streaming error: {}", response.getError());
                                        throw new OllamaHttpException(
                                                "Ollama streaming error: " + response.getError());
                                    }
                                    return response;
                                } catch (JsonException e) {
                                    log.warn(
                                            "Failed to parse Ollama NDJSON data: {}. Error: {}",
                                            data,
                                            e.getMessage());
                                    return null;
                                }
                            })
                    .filter(response -> response != null);

        } catch (JsonException e) {
            return Flux.error(new OllamaHttpException("Failed to serialize request", e));
        }
    }

    /**
     * 获取基础 URL。
     *
     * @return 基础 URL
     */
    public String getBaseUrl() {
        return baseUrl;
    }

    /**
     * 关闭传输。
     */
    public void close() {
        transport.close();
    }

    /**
     * 为 OllamaHttpClient 创建新构建器。
     *
     * @return 新的 Builder 实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * OllamaHttpClient 的构建器。
     */
    public static class Builder {
        private HttpTransport transport;
        private String baseUrl;

        public Builder transport(HttpTransport transport) {
            this.transport = transport;
            return this;
        }

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public OllamaHttpClient build() {
            if (transport == null) {
                transport = HttpTransportFactory.getDefault();
            }
            return new OllamaHttpClient(transport, baseUrl);
        }
    }

    /**
     * Ollama HTTP 操作失败时抛出的异常。
     */
    public static class OllamaHttpException extends RuntimeException {
        private final Integer statusCode;
        private final String responseBody;

        public OllamaHttpException(String message) {
            super(message);
            this.statusCode = null;
            this.responseBody = null;
        }

        public OllamaHttpException(String message, Throwable cause) {
            super(message, cause);
            this.statusCode = null;
            this.responseBody = null;
        }

        public OllamaHttpException(String message, int statusCode, String responseBody) {
            super(message);
            this.statusCode = statusCode;
            this.responseBody = responseBody;
        }

        public OllamaHttpException(String message, String errorCode, String responseBody) {
            super(message);
            this.statusCode = null;
            this.responseBody = responseBody;
        }

        public Integer getStatusCode() {
            return statusCode;
        }

        public String getResponseBody() {
            return responseBody;
        }
    }
}
