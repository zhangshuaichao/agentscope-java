/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.extensions.model.openai;

import io.agentscope.core.Version;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportException;
import io.agentscope.core.model.transport.HttpTransportFactory;
import io.agentscope.core.util.JsonException;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.extensions.model.openai.dto.OpenAIChoice;
import io.agentscope.extensions.model.openai.dto.OpenAIRequest;
import io.agentscope.extensions.model.openai.dto.OpenAIResponse;
import io.agentscope.extensions.model.openai.exception.OpenAIException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * OpenAI 兼容 API 的无状态 HTTP 客户端。
 *
 * <p>直接通过 HTTP 与 OpenAI Chat Completion API 通信。
 * API 密钥和基础 URL 等配置均随请求传入，因此客户端无状态，可在多个模型实例之间安全共享。
 *
 * <p>能力包括：
 * <ul><li>同步与流式请求。</li><li>SSE 流解析。</li><li>JSON 序列化与反序列化。</li>
 * <li>通过自定义基础 URL 支持兼容 API。</li><li>为图像、音频等其他端点提供通用调用入口。</li></ul>
 *
 * <p>API 端点：
 * <ul><li>聊天补全：/v1/chat/completions。</li>
 * <li>图像：/v1/images/generations、/v1/images/edits、/v1/images/variations。</li>
 * <li>音频：/v1/audio/speech、/v1/audio/transcriptions、/v1/audio/translations。</li></ul>
 *
 * <p>【学习提示】客户端处理协议与传输，重试由模型层统一管理。
 * 收到 SSE 结束标记就应结束响应，不应等长连接关闭；事件流结束与网络连接关闭是不同边界。
 */
public class OpenAIClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAIClient.class);

    /** OpenAI 流式 API 发出的 SSE 哨兵，用于标记流结束。 */
    private static final String SSE_DONE_MARKER = "[DONE]";

    /** OpenAI API 默认基础 URL。 */
    public static final String DEFAULT_BASE_URL = "https://api.openai.com";

    /** 包含版本的默认基础 URL。 */
    public static final String DEFAULT_BASE_URL_WITH_VERSION = DEFAULT_BASE_URL + "/v1";

    /** 聊天补全 API 端点。 */
    public static final String CHAT_COMPLETIONS_ENDPOINT = "/v1/chat/completions";

    private final HttpTransport transport;

    /**
     * 创建无状态 OpenAIClient。
     *
     * @param transport 使用的 HTTP 传输实现
     */
    public OpenAIClient(HttpTransport transport) {
        this.transport = transport;
    }

    /**
     * 使用工厂默认传输创建 OpenAIClient。
     *
     * <p>传输实现通过 {@link io.agentscope.core.model.transport.HttpTransportFactory#getDefault()} 获取。
     */
    public OpenAIClient() {
        this(HttpTransportFactory.getDefault());
    }

    private static final Pattern VERSION_PATTERN = Pattern.compile(".*/v\\d+$");
    private static final Pattern RATE_LIMIT_PATTERN = Pattern.compile(".*\\b429\\b.*");

    /**
     * 移除末尾斜杠，规范化基础 URL。
     *
     * @param url 待规范化的基础 URL
     * @return 移除末尾斜杠后的基础 URL
     */
    private static String normalizeBaseUrl(String url) {
        if (url == null || url.isEmpty()) {
            return url;
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /**
     * 合理组合基础 URL 与端点路径，构建完整 API URL。
     *
     * @param baseUrl 已规范化、不含末尾斜杠的基础 URL
     * @param endpointPath 待追加端点路径，例如 "/v1/chat/completions"
     * @return 完整 API URL
     */
    private String buildApiUrl(String baseUrl, String endpointPath) {
        try {
            URI baseUri = URI.create(baseUrl);
            String basePath = baseUri.getPath();

            // 基础 URL 已含版本路径（如 /v1、/v4）时，移除端点中的 /v1。
            String adjustedEndpoint = endpointPath;
            if (basePath != null
                    && VERSION_PATTERN
                            .matcher(
                                    basePath.endsWith("/")
                                            ? basePath.substring(0, basePath.length() - 1)
                                            : basePath)
                            .matches()) {
                adjustedEndpoint =
                        endpointPath.startsWith("/v1/")
                                ? endpointPath.substring(3) // 移除 "/v1/"。
                                : (endpointPath.equals("/v1") ? "" : endpointPath);
            }

            // 拼接基础路径与端点路径，正确处理斜杠。
            String finalPath = joinPaths(basePath, adjustedEndpoint);

            // 构建最终 URI，保留 scheme、authority、query 和 fragment。
            URI finalUri =
                    new URI(
                            baseUri.getScheme(),
                            baseUri.getAuthority(),
                            finalPath,
                            baseUri.getQuery(),
                            baseUri.getFragment());

            return finalUri.toString();
        } catch (Exception e) {
            // URI 解析失败时回退到简单字符串拼接。
            log.warn(
                    "Failed to parse base URL as URI, using simple concatenation: {}",
                    e.getMessage());
            return buildApiUrlFallback(baseUrl, endpointPath);
        }
    }

    /**
     * 拼接两个路径段，正确处理末尾与开头斜杠。
     *
     * @param path1 第一个路径段，可为 null 或空
     * @param path2 第二个路径段，可为 null 或空
     * @return 拼接后的路径
     */
    private String joinPaths(String path1, String path2) {
        if (path2 == null || path2.isEmpty()) {
            return path1 != null ? path1 : "";
        }
        if (path1 == null || path1.isEmpty()) {
            return path2.startsWith("/") ? path2 : "/" + path2;
        }

        // 移除 path1 末尾斜杠。
        String p1 = path1.endsWith("/") ? path1.substring(0, path1.length() - 1) : path1;
        // 移除 path2 开头斜杠。
        String p2 = path2.startsWith("/") ? path2.substring(1) : path2;

        return p1.isEmpty() ? "/" + p2 : p1 + "/" + p2;
    }

    /**
     * 以简单字符串拼接回退构建 URL。
     *
     * @param baseUrl 基础 URL
     * @param endpointPath 端点路径
     * @return 完整 URL
     */
    private String buildApiUrlFallback(String baseUrl, String endpointPath) {
        // 移除基础 URL 中存在的 /v{number}，以及端点中的 /v1。
        String normalizedBase = VERSION_PATTERN.matcher(baseUrl).replaceFirst("");
        String normalizedEndpoint =
                endpointPath.startsWith("/v1/") ? endpointPath.substring(3) : endpointPath;

        String separator = normalizedBase.endsWith("/") ? "" : "/";
        return normalizedBase
                + separator
                + (normalizedEndpoint.startsWith("/")
                        ? normalizedEndpoint.substring(1)
                        : normalizedEndpoint);
    }

    /**
     * 获取有效基础 URL，使用选项中的 baseUrl 或默认值。
     *
     * @param baseUrl 选项中的基础 URL
     * @return 有效基础 URL
     */
    private String getEffectiveBaseUrl(String baseUrl) {
        if (baseUrl != null && !baseUrl.isEmpty()) {
            return normalizeBaseUrl(baseUrl);
        }
        return DEFAULT_BASE_URL_WITH_VERSION;
    }

    /**
     * 获取有效 API 密钥，使用选项中的 apiKey 或 null。
     *
     * @param apiKey 选项中的 API 密钥
     * @return 有效 API 密钥
     */
    private String getEffectiveApiKey(String apiKey) {
        return apiKey;
    }

    /**
     * 执行同步 API 调用。
     *
     * @param apiKey 认证 API 密钥
     * @param baseUrl 基础 URL，null 使用默认值
     * @param request OpenAI 请求
     * @return OpenAI 响应
     * @throws OpenAIException 请求失败时抛出
     */
    public OpenAIResponse call(String apiKey, String baseUrl, OpenAIRequest request) {
        return call(apiKey, baseUrl, request, null);
    }

    /**
     * 使用选项执行同步 API 调用。
     *
     * @param apiKey 认证 API 密钥
     * @param baseUrl 基础 URL，null 使用默认值
     * @param request OpenAI 请求
     * @param options 请求头与查询参数等附加选项
     * @return OpenAI 响应
     * @throws OpenAIException 请求失败时抛出
     */
    public OpenAIResponse call(
            String apiKey, String baseUrl, OpenAIRequest request, GenerateOptions options) {
        Objects.requireNonNull(request, "Request cannot be null");

        String effectiveBaseUrl = getEffectiveBaseUrl(baseUrl);
        String effectiveApiKey = getEffectiveApiKey(apiKey);

        // 允许 options 覆盖 apiKey、baseUrl 和 endpointPath。
        if (options != null) {
            if (options.getApiKey() != null) {
                effectiveApiKey = options.getApiKey();
            }
            if (options.getBaseUrl() != null) {
                effectiveBaseUrl = getEffectiveBaseUrl(options.getBaseUrl());
            }
        }

        // 从选项获取端点路径，或使用默认值。
        String endpointPath = CHAT_COMPLETIONS_ENDPOINT;
        if (options != null && options.getEndpointPath() != null) {
            endpointPath = options.getEndpointPath();
        }

        String apiUrl = buildApiUrl(effectiveBaseUrl, endpointPath);
        String url = buildUrl(apiUrl, options);

        try {
            // 非流式调用确保 stream 为 false。
            request.setStream(false);

            String requestBody = JsonUtils.getJsonCodec().toJson(request);
            log.debug("OpenAI request to {}: {}", url, requestBody);

            HttpRequest httpRequest =
                    HttpRequest.builder()
                            .url(url)
                            .method("POST")
                            .headers(buildHeaders(effectiveApiKey, options))
                            .body(requestBody)
                            .build();

            HttpResponse httpResponse = execute(httpRequest);

            if (!httpResponse.isSuccessful()) {
                int statusCode = httpResponse.getStatusCode();
                String responseBody = httpResponse.getBody();
                String errorMessage =
                        "OpenAI API request failed with status "
                                + statusCode
                                + " | "
                                + responseBody;
                throw OpenAIException.create(statusCode, errorMessage, null, responseBody);
            }

            String responseBody = httpResponse.getBody();
            if (responseBody == null || responseBody.isEmpty()) {
                throw new OpenAIException(
                        "OpenAI API returned empty response body",
                        httpResponse.getStatusCode(),
                        null);
            }
            log.debug("OpenAI response: {}", responseBody);

            OpenAIResponse response;
            try {
                response = JsonUtils.getJsonCodec().fromJson(responseBody, OpenAIResponse.class);
            } catch (JsonException e) {
                throw new OpenAIException(
                        "Failed to parse OpenAI response: "
                                + e.getMessage()
                                + ". Response body: "
                                + responseBody,
                        e);
            }

            // 反序列化后防御性检查 null。
            if (response == null) {
                throw new OpenAIException(
                        "OpenAI API returned null response after deserialization",
                        httpResponse.getStatusCode(),
                        responseBody);
            }

            if (response.isError()) {
                String errorMessage = response.getEffectiveErrorMessage();
                String errorCode = response.getEffectiveErrorCode();
                int statusCode = resolveErrorStatusCode(httpResponse.getStatusCode(), errorCode);
                throw OpenAIException.create(
                        statusCode, "OpenAI API error: " + errorMessage, errorCode, responseBody);
            }

            return response;
        } catch (JsonException | HttpTransportException e) {
            throw new OpenAIException("Failed to execute request: " + e.getMessage(), e);
        }
    }

    /**
     * 使用请求头与查询参数选项执行流式 API 调用。
     *
     * @param apiKey 认证 API 密钥
     * @param baseUrl 基础 URL，null 使用默认值
     * @param request OpenAI 请求
     * @param options 含附加请求头和查询参数的生成选项
     * @return OpenAI 响应 Flux，每个 SSE 事件对应一个响应
     */
    public Flux<OpenAIResponse> stream(
            String apiKey, String baseUrl, OpenAIRequest request, GenerateOptions options) {
        Objects.requireNonNull(request, "Request cannot be null");

        String effectiveBaseUrl = getEffectiveBaseUrl(baseUrl);
        String effectiveApiKey = getEffectiveApiKey(apiKey);

        // 允许 options 覆盖 apiKey、baseUrl 和 endpointPath。
        if (options != null) {
            if (options.getApiKey() != null) {
                effectiveApiKey = options.getApiKey();
            }
            if (options.getBaseUrl() != null) {
                effectiveBaseUrl = getEffectiveBaseUrl(options.getBaseUrl());
            }
        }

        // 从选项获取端点路径，或使用默认值。
        String endpointPath = CHAT_COMPLETIONS_ENDPOINT;
        if (options != null && options.getEndpointPath() != null) {
            endpointPath = options.getEndpointPath();
        }

        String apiUrl = buildApiUrl(effectiveBaseUrl, endpointPath);
        String url = buildUrl(apiUrl, options);

        try {
            // 启用流式输出。
            request.setStream(true);

            String requestBody = JsonUtils.getJsonCodec().toJson(request);
            log.debug("OpenAI streaming request to {}: {}", url, requestBody);

            HttpRequest httpRequest =
                    HttpRequest.builder()
                            .url(url)
                            .method("POST")
                            .headers(buildHeaders(effectiveApiKey, options))
                            .body(requestBody)
                            .build();

            AtomicBoolean seenChunk = new AtomicBoolean(false);
            return transport.stream(httpRequest)
                    // SSE 的 [DONE] 哨兵终止流：应在此完成 Flux，
                    // 而不只是过滤此事件，否则会延迟到
                    // 底层连接关闭时才完成。保持长连接的网关可能一直拖到
                    // 空闲超时，即使模型早已结束响应。
                    .takeWhile(data -> !SSE_DONE_MARKER.equals(data))
                    .<OpenAIResponse>handle(
                            (data, sink) -> {
                                OpenAIResponse response = parseStreamData(data);
                                if (response != null) {
                                    // 检查流式响应分块中的错误。
                                    if (response.isError()) {
                                        String errorMessage = response.getEffectiveErrorMessage();
                                        String errorCode = response.getEffectiveErrorCode();
                                        int statusCode = resolveErrorStatusCode(200, errorCode);
                                        sink.error(
                                                OpenAIException.create(
                                                        statusCode,
                                                        "OpenAI API error in streaming response: "
                                                                + errorMessage,
                                                        errorCode,
                                                        data));
                                        return;
                                    }
                                    // 部分 OpenAI 兼容提供商，例如 MiniMax，会在普通
                                    // 分块之后额外发出非分块的 chat.completion 汇总事件。
                                    // 应丢弃重复的汇总消息内容，但只有在
                                    // 已观察到至少一个分块之后才这样做，以便保留仅以完整
                                    // chat.completion 事件返回的流式响应。
                                    if (response.isChunk()) {
                                        seenChunk.set(true);
                                    } else if (seenChunk.get()) {
                                        OpenAIChoice summaryChoice = response.getFirstChoice();
                                        if (summaryChoice != null) {
                                            summaryChoice.setMessage(null);
                                        }
                                    }
                                    sink.next(response);
                                }
                                // 响应为 null（分块格式错误）时静默跳过。
                            })
                    .onErrorMap(
                            ex -> {
                                if (ex instanceof HttpTransportException hte) {
                                    Integer code = hte.getStatusCode();
                                    String msg =
                                            "HTTP transport error during streaming: "
                                                    + ex.getMessage();
                                    if (code != null) {
                                        return OpenAIException.create(
                                                code, msg, null, hte.getResponseBody());
                                    }
                                    return new OpenAIException(msg, ex);
                                }
                                return ex;
                            });
        } catch (JsonException | HttpTransportException e) {
            return Flux.error(
                    new OpenAIException("Failed to initialize request: " + e.getMessage(), e));
        }
    }

    /**
     * API 返回 200 OK 但响应体包含错误时，解析实际 HTTP 错误状态码。
     *
     * @param httpStatusCode 原始 HTTP 状态码
     * @param errorCode 从响应体提取的错误码
     * @return 有效 HTTP 错误状态码，4xx 或 5xx
     */
    private int resolveErrorStatusCode(int httpStatusCode, String errorCode) {
        if (httpStatusCode >= 400) {
            return httpStatusCode;
        }

        // 处理 HTTP 200 但响应体包含错误的情况。
        if (errorCode != null) {
            // 使用正则确保 "429" 作为独立词出现。
            // 例如 "HTTP 429 Too Many Requests"。
            // 同时兼容 OpenAI 标准错误字符串。
            if (RATE_LIMIT_PATTERN.matcher(errorCode).matches()
                    || "rate_limit_exceeded".equalsIgnoreCase(errorCode)) {
                return 429;
            }

            try {
                int parsedCode = Integer.parseInt(errorCode);
                if (parsedCode >= 400 && parsedCode <= 599) {
                    return parsedCode;
                }
            } catch (NumberFormatException e) {
                // 忽略非数值类型的错误码。
            }
        }

        // 提取失败，回退默认值。
        return 400;
    }

    /**
     * 将单条 SSE 数据解析为 OpenAIResponse。
     *
     * @param data SSE 数据，不含 "data: " 前缀
     * @return 解析后的 OpenAIResponse；解析失败时返回 null
     */
    private OpenAIResponse parseStreamData(String data) {
        if (log.isDebugEnabled()) {
            log.debug("SSE data: {}", data);
        }
        try {
            if (data == null || data.isEmpty()) {
                log.debug("Ignoring empty SSE data");
                return null;
            }
            OpenAIResponse response = JsonUtils.getJsonCodec().fromJson(data, OpenAIResponse.class);

            // 反序列化后防御性检查 null。
            if (response == null) {
                log.warn(
                        "OpenAIResponse deserialization returned null for data: {}",
                        data.length() > 100 ? data.substring(0, 100) + "..." : data);
                return null;
            }
            return response;
        } catch (JsonException e) {
            log.error(
                    "Failed to parse SSE data - JSON error: {}. Content: {}.",
                    e.getMessage(),
                    data != null && data.length() > 100 ? data.substring(0, 100) + "..." : data);
            return null;
        } catch (Exception e) {
            log.warn("Failed to parse SSE data - unexpected error: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 构建 API 请求的 HTTP 请求头。
     *
     * @param apiKey 认证 API 密钥
     * @param options 请求头附加选项
     * @return 请求头映射
     */
    private Map<String, String> buildHeaders(String apiKey, GenerateOptions options) {
        return buildHeaders(apiKey, options, "application/json");
    }

    /**
     * 构建 API 请求的 HTTP 请求头。
     *
     * @param apiKey 认证 API 密钥
     * @param options 请求头附加选项
     * @param contentType Content-Type 请求头值，null 时默认为 "application/json"
     * @return 请求头映射
     */
    private Map<String, String> buildHeaders(
            String apiKey, GenerateOptions options, String contentType) {
        Map<String, String> headers = new HashMap<>();
        if (apiKey != null && !apiKey.isEmpty()) {
            headers.put("Authorization", "Bearer " + apiKey);
        }
        headers.put("Content-Type", contentType != null ? contentType : "application/json");

        // 添加 User-Agent 请求头，Version.getUserAgent() 返回 null 时使用回退值。
        String userAgent = Version.getUserAgent();
        headers.put("User-Agent", userAgent);

        // 应用选项中的附加请求头。
        if (options != null) {
            Map<String, String> additionalHeaders = options.getAdditionalHeaders();
            if (additionalHeaders != null && !additionalHeaders.isEmpty()) {
                headers.putAll(additionalHeaders);
            }
        }

        return headers;
    }

    /** 构建带可选查询参数的 URL。 */
    private String buildUrl(String baseUrl, GenerateOptions options) {
        if (options == null) {
            return baseUrl;
        }

        Map<String, String> queryParams = options.getAdditionalQueryParams();
        if (queryParams == null || queryParams.isEmpty()) {
            return baseUrl;
        }

        StringBuilder url = new StringBuilder(baseUrl);
        boolean first = !baseUrl.contains("?");

        for (Map.Entry<String, String> entry : queryParams.entrySet()) {
            url.append(first ? "?" : "&");
            first = false;
            String key = entry.getKey();
            String value = entry.getValue();
            if (key != null && value != null) {
                url.append(URLEncoder.encode(key, StandardCharsets.UTF_8))
                        .append("=")
                        .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
            }
        }

        return url.toString();
    }

    /**
     * 使用 JSON 请求体对任意 OpenAI 端点执行通用 API 调用。
     *
     * <p>可用于聊天补全之外的端点，例如：
     * <ul><li>/v1/images/generations。</li><li>/v1/audio/speech。</li><li>其他基于 JSON 的 OpenAI API 端点。</li></ul>
     *
     * @param apiKey 认证 API 密钥
     * @param baseUrl 基础 URL，null 使用默认值
     * @param endpoint API 端点路径，例如 "/v1/images/generations"
     * @param requestBody JSON 请求体，可为 Map 或任意可序列化对象
     * @return 字符串形式的原始 HTTP 响应体
     * @throws OpenAIException 请求失败时抛出
     */
    public String callApi(String apiKey, String baseUrl, String endpoint, Object requestBody) {
        return callApi(apiKey, baseUrl, endpoint, requestBody, null);
    }

    /**
     * 使用 JSON 请求体和自定义选项，对任意 OpenAI 端点执行通用 API 调用。
     *
     * @param apiKey 认证 API 密钥
     * @param baseUrl 基础 URL，null 使用默认值
     * @param endpoint API 端点路径，例如 "/v1/images/generations"
     * @param requestBody JSON 请求体，可为 Map 或任意可序列化对象
     * @param options 请求头与查询参数等附加选项
     * @return 字符串形式的原始 HTTP 响应体
     * @throws OpenAIException 请求失败时抛出
     */
    public String callApi(
            String apiKey,
            String baseUrl,
            String endpoint,
            Object requestBody,
            GenerateOptions options) {
        String effectiveBaseUrl = getEffectiveBaseUrl(baseUrl);
        String effectiveApiKey = getEffectiveApiKey(apiKey);

        // 允许 options 覆盖 apiKey 与 baseUrl。
        if (options != null) {
            if (options.getApiKey() != null) {
                effectiveApiKey = options.getApiKey();
            }
            if (options.getBaseUrl() != null) {
                effectiveBaseUrl = getEffectiveBaseUrl(options.getBaseUrl());
            }
        }

        String apiUrl = buildApiUrl(effectiveBaseUrl, endpoint);
        String url = buildUrl(apiUrl, options);

        try {
            String requestBodyJson =
                    requestBody instanceof String
                            ? (String) requestBody
                            : JsonUtils.getJsonCodec().toJson(requestBody);
            log.debug("OpenAI API request to {}: {}", url, requestBodyJson);

            HttpRequest httpRequest =
                    HttpRequest.builder()
                            .url(url)
                            .method("POST")
                            .headers(buildHeaders(effectiveApiKey, options, "application/json"))
                            .body(requestBodyJson)
                            .build();

            HttpResponse httpResponse = execute(httpRequest);

            if (!httpResponse.isSuccessful()) {
                int statusCode = httpResponse.getStatusCode();
                String responseBody = httpResponse.getBody();
                String errorMessage = "OpenAI API request failed with status " + statusCode;
                throw OpenAIException.create(statusCode, errorMessage, null, responseBody);
            }

            String responseBody = httpResponse.getBody();
            if (responseBody == null || responseBody.isEmpty()) {
                throw new OpenAIException(
                        "OpenAI API returned empty response body",
                        httpResponse.getStatusCode(),
                        null);
            }
            log.debug("OpenAI API response: {}", responseBody);
            return responseBody;
        } catch (JsonException e) {
            throw new OpenAIException("Failed to serialize request", e);
        } catch (HttpTransportException e) {
            throw new OpenAIException("HTTP transport error: " + e.getMessage(), e);
        }
    }

    /**
     * 执行 HTTP 请求，不在内部重试；重试由 Model 层处理。
     *
     * @param request HTTP 请求
     * @return HTTP 响应
     * @throws OpenAIException 执行失败时抛出
     */
    HttpResponse execute(HttpRequest request) {
        try {
            return transport.execute(request);
        } catch (HttpTransportException e) {
            throw new OpenAIException("HTTP transport failed: " + e.getMessage(), e);
        }
    }

    /**
     * 获取此客户端使用的 HTTP 传输实现。
     *
     * @return HTTP 传输实现
     */
    public HttpTransport getTransport() {
        return transport;
    }
}
