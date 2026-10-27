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
package io.agentscope.extensions.model.dashscope;

import com.fasterxml.jackson.core.type.TypeReference;
import io.agentscope.core.Version;
import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportException;
import io.agentscope.core.model.transport.HttpTransportFactory;
import io.agentscope.core.util.JsonException;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.extensions.model.dashscope.dto.DashScopeParameters;
import io.agentscope.extensions.model.dashscope.dto.DashScopePublicKeyResponse;
import io.agentscope.extensions.model.dashscope.dto.DashScopeRequest;
import io.agentscope.extensions.model.dashscope.dto.DashScopeResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import javax.crypto.SecretKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * DashScope API 的 HTTP 客户端。
 *
 * <p>使用 DashScope 原生协议与文本生成、多模态生成 API 通信。
 *
 * <p>功能：
 * <ul>
 * <li>根据模型名称自动路由 API 端点。</li>
 * <li>支持同步与流式请求。</li>
 * <li>解析 SSE 流。</li>
 * <li>JSON 序列化/反序列化。</li>
 * </ul>
 *
 * <p>API 端点：
 * <ul>
 * <li>文本生成：/api/v1/services/aigc/text-generation/generation。</li>
 * <li>多模态生成：/api/v1/services/aigc/multimodal-generation/generation。</li>
 * </ul>
 */
public class DashScopeHttpClient {

    private static final Logger log = LoggerFactory.getLogger(DashScopeHttpClient.class);

    /**
     * DashScope API 默认基础 URL。
     */
    public static final String DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com";

    /**
     * 文本生成 API 端点。
     */
    public static final String TEXT_GENERATION_ENDPOINT =
            "/api/v1/services/aigc/text-generation/generation";

    /**
     * 多模态生成 API 端点。
     */
    public static final String MULTIMODAL_GENERATION_ENDPOINT =
            "/api/v1/services/aigc/multimodal-generation/generation";

    /**
     * 公钥 API 端点。
     */
    public static final String PUBLIC_KEYS_ENDPOINT = "/api/v1/public-keys/latest";

    private final HttpTransport transport;
    private final String apiKey;
    private final String baseUrl;
    private final String publicKeyId;
    private final String publicKey;
    private final Set<String> userMultimodalPatterns;

    /**
     * 创建 DashScopeHttpClient。
     *
     * @param transport 使用的 HTTP 传输层
     * @param apiKey DashScope API 密钥
     * @param baseUrl 基础 URL（null 使用默认值）
     * @param publicKeyId 加密 RSA 公钥 ID（null 禁用加密）
     * @param publicKey 加密 RSA 公钥（Base64 编码，null 禁用加密）
     */
    public DashScopeHttpClient(
            HttpTransport transport,
            String apiKey,
            String baseUrl,
            String publicKeyId,
            String publicKey) {
        this(transport, apiKey, baseUrl, publicKeyId, publicKey, null);
    }

    /**
     * 创建带用户自定义多模态模型模式的 DashScopeHttpClient。
     *
     * @param transport 使用的 HTTP 传输层
     * @param apiKey DashScope API 密钥
     * @param baseUrl 基础 URL（null 使用默认值）
     * @param publicKeyId 加密 RSA 公钥 ID（null 禁用加密）
     * @param publicKey 加密 RSA 公钥（Base64 编码，null 禁用加密）
     * @param userMultimodalPatterns 忽略大小写的子串模式，用于扩展内置多模态检测（null 或空集合禁用）
     */
    public DashScopeHttpClient(
            HttpTransport transport,
            String apiKey,
            String baseUrl,
            String publicKeyId,
            String publicKey,
            Collection<String> userMultimodalPatterns) {
        this.transport = transport;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl != null ? baseUrl : DEFAULT_BASE_URL;
        this.publicKeyId = publicKeyId;
        this.publicKey = publicKey;
        this.userMultimodalPatterns =
                userMultimodalPatterns == null || userMultimodalPatterns.isEmpty()
                        ? Set.of()
                        : normalizePatterns(userMultimodalPatterns);
    }

    private static Set<String> normalizePatterns(Collection<String> patterns) {
        Set<String> normalized = new LinkedHashSet<>();
        for (String pattern : patterns) {
            if (pattern != null && !pattern.isBlank()) {
                normalized.add(pattern.trim().toLowerCase());
            }
        }
        return normalized;
    }

    /**
     * 创建 DashScopeHttpClient。
     *
     * @param apiKey DashScope API 密钥
     * @param baseUrl 基础 URL（null 使用默认值）
     */
    public DashScopeHttpClient(String apiKey, String baseUrl) {
        this(apiKey, baseUrl, null, null);
    }

    /**
     * 使用工厂默认传输层创建 DashScopeHttpClient。
     *
     * <p>传输层采用 {@link HttpTransportFactory#getDefault()}，提供自动生命周期管理与 JVM 退出清理。
     *
     * @param apiKey DashScope API 密钥
     * @param baseUrl 基础 URL（null 使用默认值）
     * @param publicKeyId 加密 RSA 公钥 ID（null 禁用加密）
     * @param publicKey 加密 RSA 公钥（Base64 编码，null 禁用加密）
     */
    public DashScopeHttpClient(
            String apiKey, String baseUrl, String publicKeyId, String publicKey) {
        this(HttpTransportFactory.getDefault(), apiKey, baseUrl, publicKeyId, publicKey);
    }

    /**
     * 使用默认传输层和基础 URL 创建 DashScopeHttpClient。
     *
     * @param apiKey DashScope API 密钥
     */
    public DashScopeHttpClient(String apiKey) {
        this(apiKey, null, null, null);
    }

    /**
     * 检查是否启用加密。
     *
     * @return publicKeyId 与 publicKey 均设置时为 true
     */
    public boolean isEncryptionEnabled() {
        return publicKeyId != null
                && !publicKeyId.isEmpty()
                && publicKey != null
                && !publicKey.isEmpty();
    }

    /**
     * 使用附加 HTTP 参数进行同步 API 调用。
     *
     * @param request DashScope 请求
     * @param additionalHeaders 要合并的附加 HTTP 请求头（可为 null）
     * @param additionalBodyParams 要合并的附加请求体参数（可为 null）
     * @param additionalQueryParams 要追加的查询参数（可为 null）
     * @return DashScope 响应
     * @throws DashScopeHttpException 当请求失败时
     */
    public DashScopeResponse call(
            DashScopeRequest request,
            Map<String, String> additionalHeaders,
            Map<String, Object> additionalBodyParams,
            Map<String, String> additionalQueryParams) {
        EndpointType endpointType =
                request.getEndpointType() != null ? request.getEndpointType() : EndpointType.AUTO;
        String endpoint = selectEndpoint(request.getModel(), endpointType);
        String url = buildUrl(endpoint, additionalQueryParams);

        try {
            EncryptionContext encryptionContext = null;
            String requestBody = buildRequestBody(request, additionalBodyParams);
            if (isEncryptionEnabled()) {
                EncryptionResult encryptionResult = encryptRequestBodyIfNeeded(requestBody);
                requestBody = encryptionResult.requestBody;
                encryptionContext = encryptionResult.context;
            }
            final EncryptionContext finalEncryptionContext = encryptionContext;
            log.debug("DashScope request to {}: {}", url, requestBody);

            HttpRequest httpRequest =
                    HttpRequest.builder()
                            .url(url)
                            .method("POST")
                            .headers(buildHeaders(false, additionalHeaders, finalEncryptionContext))
                            .body(requestBody)
                            .build();

            HttpResponse httpResponse = transport.execute(httpRequest);

            if (!httpResponse.isSuccessful()) {
                throw new DashScopeHttpException(
                        "DashScope API request failed with status " + httpResponse.getStatusCode(),
                        httpResponse.getStatusCode(),
                        httpResponse.getBody());
            }

            String responseBody = httpResponse.getBody();
            log.debug("DashScope response: {}", responseBody);

            // 启用加密时解密响应。
            if (finalEncryptionContext != null) {
                responseBody = decryptResponse(responseBody, finalEncryptionContext);
            }

            DashScopeResponse response =
                    JsonUtils.getJsonCodec().fromJson(responseBody, DashScopeResponse.class);

            if (response.isError()) {
                throw new DashScopeHttpException(
                        "DashScope API error: " + response.getMessage(),
                        response.getCode(),
                        responseBody);
            }

            return response;
        } catch (JsonException e) {
            throw new DashScopeHttpException("Failed to serialize/deserialize request", e);
        } catch (HttpTransportException e) {
            throw new DashScopeHttpException("HTTP transport error: " + e.getMessage(), e);
        }
    }

    /**
     * 使用附加 HTTP 参数进行流式 API 调用。
     *
     * <p>【学习提示】传输层先给出 SSE 数据事件，此处再逐事件反序列化，并非按网络字节块直接解析。JSON 解析失败会记录警告并跳过该事件；解析成功但携带 API 错误码会终止流。返回 Flux 不代表每个事件已经是完整回复，工具参数可能仍是不完整 JSON 片段。
     *
     * @param request DashScope 请求
     * @param additionalHeaders 要合并的附加 HTTP 请求头（可为 null）
     * @param additionalBodyParams 要合并的附加请求体参数（可为 null）
     * @param additionalQueryParams 要追加的查询参数（可为 null）
     * @return DashScope 响应 Flux，每个 SSE 事件对应一个响应
     */
    public Flux<DashScopeResponse> stream(
            DashScopeRequest request,
            Map<String, String> additionalHeaders,
            Map<String, Object> additionalBodyParams,
            Map<String, String> additionalQueryParams) {
        EndpointType endpointType =
                request.getEndpointType() != null ? request.getEndpointType() : EndpointType.AUTO;
        String endpoint = selectEndpoint(request.getModel(), endpointType);
        String url = buildUrl(endpoint, additionalQueryParams);

        try {
            // 确保流式响应启用增量输出。
            if (request.getParameters() != null) {
                request.getParameters().setIncrementalOutput(true);
            }

            EncryptionContext encryptionContext = null;
            String requestBody = buildRequestBody(request, additionalBodyParams);
            if (isEncryptionEnabled()) {
                EncryptionResult encryptionResult = encryptRequestBodyIfNeeded(requestBody);
                requestBody = encryptionResult.requestBody;
                encryptionContext = encryptionResult.context;
            }
            final EncryptionContext finalEncryptionContext = encryptionContext;
            log.debug("DashScope streaming request to {}: {}", url, requestBody);

            HttpRequest httpRequest =
                    HttpRequest.builder()
                            .url(url)
                            .method("POST")
                            .headers(buildHeaders(true, additionalHeaders, finalEncryptionContext))
                            .body(requestBody)
                            .build();

            return transport.stream(httpRequest)
                    .<ParsedStreamResponse>handle(
                            (data, sink) -> {
                                try {
                                    // 启用加密时解密响应。
                                    if (finalEncryptionContext != null) {
                                        data = decryptResponse(data, finalEncryptionContext);
                                    }
                                    sink.next(
                                            new ParsedStreamResponse(
                                                    data,
                                                    JsonUtils.getJsonCodec()
                                                            .fromJson(
                                                                    data,
                                                                    DashScopeResponse.class)));
                                } catch (JsonException e) {
                                    log.warn(
                                            "Failed to parse SSE data: {}. Error: {}",
                                            data,
                                            e.getMessage());
                                }
                            })
                    .handle(
                            (streamResponse, sink) -> {
                                DashScopeResponse response = streamResponse.response();
                                if (response.isError()) {
                                    sink.error(
                                            new DashScopeHttpException(
                                                    "DashScope API error: " + response.getMessage(),
                                                    response.getCode(),
                                                    streamResponse.responseBody()));
                                } else {
                                    sink.next(response);
                                }
                            });
        } catch (JsonException e) {
            return Flux.error(new DashScopeHttpException("Failed to serialize request", e));
        }
    }

    /**
     * 根据模型名称选择适当 API 端点。
     *
     * <p>便捷方法，使用 {@link EndpointType#AUTO} 自动检测。
     *
     * @param modelName 模型名称
     * @return API 端点路径
     */
    public String selectEndpoint(String modelName) {
        return selectEndpoint(modelName, EndpointType.AUTO);
    }

    /**
     * 根据模型名称和端点类型选择适当 API 端点。
     *
     * <p>路由逻辑：
     * <ul>
     * <li>endpointType 为 {@link EndpointType#TEXT}：文本生成 API。</li>
     * <li>endpointType 为 {@link EndpointType#MULTIMODAL}：多模态 API。</li>
     * <li>endpointType 为 {@link EndpointType#AUTO}：
     * <ul>
     * <li>被 {@link #isMultimodalModel(String)} 识别或匹配用户多模态模式的模型：多模态 API。</li>
     * <li>其余模型：文本生成 API。</li>
     * </ul>
     * </li>
     * </ul>
     *
     * @param modelName 模型名称
     * @param endpointType 端点类型（AUTO 表示自动检测）
     * @return API 端点路径
     */
    public String selectEndpoint(String modelName, EndpointType endpointType) {
        if (endpointType == EndpointType.TEXT) {
            log.debug("Using text generation API (explicitly set) for model: {}", modelName);
            return TEXT_GENERATION_ENDPOINT;
        }
        if (endpointType == EndpointType.MULTIMODAL) {
            log.debug("Using multimodal API (explicitly set) for model: {}", modelName);
            return MULTIMODAL_GENERATION_ENDPOINT;
        }
        // AUTO：根据模型名称检测。
        if (modelName == null) {
            return TEXT_GENERATION_ENDPOINT;
        }
        if (isMultimodalModel(modelName) || matchesUserPattern(modelName)) {
            log.debug("Using multimodal API (auto-detected) for model: {}", modelName);
            return MULTIMODAL_GENERATION_ENDPOINT;
        }
        log.debug("Using text generation API (auto-detected) for model: {}", modelName);
        return TEXT_GENERATION_ENDPOINT;
    }

    /**
     * 检查模型是否属于需要 multimodal-generation API 的多模态模型。
     *
     * <p>endpointType 为 AUTO 时使用的内置多模态名称规则：
     * <ul>
     * <li>以 "qvq" 开头（如 qvq-72b、qvq-max）。</li>
     * <li>包含 "-vl"（如 qwen-vl-plus、qwen3-vl-max）。</li>
     * <li>包含 "-asr"（如 qwen3-asr-flash）。</li>
     * <li>以 "qwen3.5" 开头（如 qwen3.5-plus、qwen3.5-flash）。</li>
     * <li>以 "qwen3.6" 开头（如 qwen3.6-plus、qwen3.6-flash）。</li>
     * <li>以 "qwen3.7" 开头，但不是 "qwen3.7-max"（如 qwen3.7-plus）。</li>
     * <li>以 "qwen3.8" 开头，但不是 "qwen3.8-2.4t-a95b"（如 qwen3.8-max、qwen3.8-flash、qwen3.8-27b）。</li>
     * <li>包含 "kimi-k2.5"/"kimi-k2.6"（如 kimi-k2.6、kimi/kimi-k2.5）。</li>
     * </ul>
     *
     * <p>反向排除规则：虽然匹配上述前缀，但按非多模态处理的模型：
     * <ul>
     * <li>"qwen3.6-max-preview"：纯文本预览模型。</li>
     * <li>以 "qwen3.7-max" 开头（如 qwen3.7-max、qwen3.7-max-2026-05-20）：使用文本生成端点。</li>
     * <li>以 "qwen3.8-2.4t-a95b" 开头：使用文本生成端点。</li>
     * </ul>
     *
     * @param modelName 模型名称
     * @return 根据此处规则属于多模态模型时为 true
     */
    public static boolean isMultimodalModel(String modelName) {
        if (modelName == null) {
            return false;
        }
        String lowerModelName = modelName.toLowerCase();
        // 反向排除：前缀原本会匹配的纯文本模型。
        if (lowerModelName.equals("qwen3.6-max-preview")
                || lowerModelName.startsWith("qwen3.7-max")
                || lowerModelName.contains("kimi-k2-thinking")
                || lowerModelName.contains("moonshot-kimi-k2-instruct")
                || lowerModelName.startsWith("qwen3.8-2.4t-a95b")) {
            return false;
        }
        return lowerModelName.startsWith("qvq")
                || lowerModelName.contains("-vl")
                || lowerModelName.contains("-asr")
                || lowerModelName.startsWith("qwen3.5")
                || lowerModelName.startsWith("qwen3.6")
                || lowerModelName.startsWith("qwen3.7")
                || lowerModelName.startsWith("qwen3.8")
                || lowerModelName.contains("kimi-k");
    }

    /**
     * 检查模型是否需要多模态 API。
     *
     * <p>便捷方法，使用 {@link EndpointType#AUTO} 自动检测。
     *
     * @param modelName 模型名称
     * @return 需要多模态 API 时为 true
     */
    public boolean requiresMultimodalApi(String modelName) {
        return requiresMultimodalApi(modelName, EndpointType.AUTO);
    }

    /**
     * 根据指定端点类型判断模型是否需要多模态 API。
     *
     * @param modelName 模型名称
     * @param endpointType 端点类型
     * @return 需要多模态 API 时为 true
     */
    public boolean requiresMultimodalApi(String modelName, EndpointType endpointType) {
        if (endpointType == EndpointType.MULTIMODAL) {
            return true;
        }
        if (endpointType == EndpointType.TEXT) {
            return false;
        }
        // AUTO：按模型名称检测，并扩展用户提供的模式。
        return isMultimodalModel(modelName) || matchesUserPattern(modelName);
    }

    /**
     * 检查模型名称是否匹配用户提供的任一多模态模式。
     *
     * <p>与去除首尾空白的模型名称做忽略大小写的子串比较，对齐 {@link #isMultimodalModel(String)} 的 {@code contains} 风格规则。未配置模式或模型名称为 {@code null} 时返回 {@code false}。
     *
     * @param modelName 模型名称
     * @return 匹配用户多模态模式时为 true
     */
    private boolean matchesUserPattern(String modelName) {
        if (modelName == null || userMultimodalPatterns.isEmpty()) {
            return false;
        }
        String lowerModelName = modelName.trim().toLowerCase();
        for (String pattern : userMultimodalPatterns) {
            if (lowerModelName.contains(pattern)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从 DashScope API 获取最新 RSA 公钥。
     *
     * <p>调用公钥 API 获取用于加密的最新公钥及其 ID。
     *
     * @param apiKey DashScope API 密钥
     * @param baseUrl 基础 URL（null 使用默认值）
     * @param transport 使用的 HTTP 传输层
     * @return 包含公钥 ID 与公钥的 PublicKeyResult
     * @throws DashScopeHttpException 当请求失败时
     */
    public static PublicKeyResult fetchPublicKey(
            String apiKey, String baseUrl, HttpTransport transport) {
        String url = (baseUrl != null ? baseUrl : DEFAULT_BASE_URL) + PUBLIC_KEYS_ENDPOINT;

        try {
            Map<String, String> headers = new HashMap<>();
            headers.put("Authorization", "Bearer " + apiKey);
            headers.put("Content-Type", "application/json");
            headers.put("User-Agent", Version.getUserAgent());

            HttpRequest httpRequest =
                    HttpRequest.builder().url(url).method("GET").headers(headers).build();

            HttpResponse httpResponse = transport.execute(httpRequest);

            if (!httpResponse.isSuccessful()) {
                throw new DashScopeHttpException(
                        "Failed to fetch public key: HTTP " + httpResponse.getStatusCode(),
                        httpResponse.getStatusCode(),
                        httpResponse.getBody());
            }

            String responseBody = httpResponse.getBody();
            DashScopePublicKeyResponse response =
                    JsonUtils.getJsonCodec()
                            .fromJson(responseBody, DashScopePublicKeyResponse.class);

            if (response.isError()) {
                throw new DashScopeHttpException(
                        "Failed to fetch public key: " + response.getMessage(),
                        response.getCode(),
                        responseBody);
            }

            DashScopePublicKeyResponse.PublicKeyData data = response.getData();
            if (data == null || data.getPublicKey() == null || data.getPublicKeyId() == null) {
                throw new DashScopeHttpException(
                        "Invalid public key response: data is missing or incomplete",
                        null,
                        responseBody);
            }

            return new PublicKeyResult(data.getPublicKeyId(), data.getPublicKey());
        } catch (JsonException e) {
            throw new DashScopeHttpException("Failed to parse public key response", e);
        } catch (HttpTransportException e) {
            throw new DashScopeHttpException("HTTP transport error: " + e.getMessage(), e);
        }
    }

    /**
     * 从 DashScope API 获取公钥的结果。
     *
     * @param publicKeyId 公钥 ID
     * @param publicKey Base64 编码的公钥
     */
    public record PublicKeyResult(String publicKeyId, String publicKey) {}

    private Map<String, String> buildHeaders(
            boolean streaming, Map<String, String> additionalHeaders, EncryptionContext context) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + apiKey);
        headers.put("Content-Type", "application/json");
        headers.put("User-Agent", Version.getUserAgent());

        if (streaming) {
            headers.put("X-DashScope-SSE", "enable");
        }

        // 启用加密且当前请求存在加密上下文时，添加加密请求头。
        if (context != null) {
            String encryptionHeader = buildEncryptionHeader(context);
            if (encryptionHeader != null) {
                headers.put("X-DashScope-EncryptionKey", encryptionHeader);
            }
        }

        // 合并附加请求头（可覆盖默认请求头）。
        if (additionalHeaders != null && !additionalHeaders.isEmpty()) {
            headers.putAll(additionalHeaders);
        }

        return headers;
    }

    /**
     * 构建带查询参数的 URL。
     *
     * @param endpoint API 端点路径
     * @param queryParams 要追加的查询参数（可为 null）
     * @return 包含查询字符串的完整 URL
     */
    private String buildUrl(String endpoint, Map<String, String> queryParams) {
        String url = baseUrl + endpoint;
        if (queryParams == null || queryParams.isEmpty()) {
            return url;
        }

        StringJoiner joiner = new StringJoiner("&", "?", "");
        for (Map.Entry<String, String> entry : queryParams.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || value == null) {
                log.warn("Skipping null query parameter: key={}, value={}", key, value);
                continue;
            }
            joiner.add(
                    URLEncoder.encode(key, StandardCharsets.UTF_8)
                            + "="
                            + URLEncoder.encode(value, StandardCharsets.UTF_8));
        }
        return url + joiner.toString();
    }

    /**
     * 构建合并附加参数后的请求体。
     *
     * @param request DashScope 请求
     * @param additionalBodyParams 要合并的附加参数（可为 null）
     * @return 序列化后的请求体
     */
    private String buildRequestBody(
            DashScopeRequest request, Map<String, Object> additionalBodyParams) {
        String requestBody = JsonUtils.getJsonCodec().toJson(request);

        if (additionalBodyParams == null || additionalBodyParams.isEmpty()) {
            return requestBody;
        }

        // 反序列化为 Map，合并附加参数，再重新序列化。
        // 附加参数应加入 DashScope parameters。
        DashScopeParameters parameters = request.getParameters();
        String parametersJson = JsonUtils.getJsonCodec().toJson(parameters);
        Map<String, Object> parametersMap =
                JsonUtils.getJsonCodec().fromJson(parametersJson, new TypeReference<>() {});
        parametersMap.putAll(additionalBodyParams);

        // 将合并后的 parameters 设置到 DashScope 请求体映射。
        Map<String, Object> bodyMap =
                JsonUtils.getJsonCodec().fromJson(requestBody, new TypeReference<>() {});
        bodyMap.put("parameters", parametersMap);
        requestBody = JsonUtils.getJsonCodec().toJson(bodyMap);
        return requestBody;
    }

    /**
     * 请求体存在 input 字段时对其加密。
     *
     * @param requestBody 原始请求体 JSON 字符串
     * @return 加密结果，包含可能更新的请求体与上下文（无 input 时上下文为 null）
     */
    private EncryptionResult encryptRequestBodyIfNeeded(String requestBody) {
        try {
            // 解析请求体以获取 input 字段。
            Map<String, Object> bodyMap =
                    JsonUtils.getJsonCodec()
                            .fromJson(requestBody, new TypeReference<Map<String, Object>>() {});

            Object inputObj = bodyMap.get("input");
            if (inputObj == null) {
                return new EncryptionResult(requestBody, null); // 没有可加密的 input。
            }

            // 将 input 序列化为 JSON 字符串。
            String inputJson = JsonUtils.getJsonCodec().toJson(inputObj);

            // 为本次请求生成 AES 密钥与 IV。
            SecretKey aesSecretKey = DashScopeEncryptionUtils.generateAesSecretKey();
            byte[] iv = DashScopeEncryptionUtils.generateIv();

            // 加密 input。
            String encryptedInput =
                    DashScopeEncryptionUtils.encryptWithAes(aesSecretKey, iv, inputJson);

            // 使用 RSA 公钥加密 AES 密钥。
            String encryptedAesKey =
                    DashScopeEncryptionUtils.encryptAesKeyWithRsa(aesSecretKey, publicKey);

            EncryptionContext context = new EncryptionContext(aesSecretKey, iv, encryptedAesKey);

            // 用加密值替换 input。
            bodyMap.put("input", encryptedInput);

            return new EncryptionResult(JsonUtils.getJsonCodec().toJson(bodyMap), context);
        } catch (Exception e) {
            log.error("Failed to encrypt request body", e);
            throw new DashScopeHttpException(
                    "Failed to encrypt request body: " + e.getMessage(), e);
        }
    }

    /**
     * 构建包含公钥 ID、加密 AES 密钥和 IV 的加密请求头。
     *
     * @return 加密请求头 JSON 字符串
     */
    private String buildEncryptionHeader(EncryptionContext context) {
        try {
            String ivBase64 = Base64.getEncoder().encodeToString(context.iv);

            // 构建请求头 JSON：{"public_key_id": "...", "encrypt_key": "...", "iv": "..."}
            Map<String, String> headerMap = new HashMap<>();
            headerMap.put("public_key_id", publicKeyId);
            headerMap.put("encrypt_key", context.encryptedAesKey);
            headerMap.put("iv", ivBase64);

            return JsonUtils.getJsonCodec().toJson(headerMap);
        } catch (Exception e) {
            log.error("Failed to build encryption header", e);
            throw new DashScopeHttpException(
                    "Failed to build encryption header: " + e.getMessage(), e);
        }
    }

    /**
     * 解密响应体中的 output 字段。
     *
     * @param responseBody 加密响应体 JSON 字符串
     * @return 解密后的响应体 JSON 字符串
     */
    private String decryptResponse(String responseBody, EncryptionContext context) {
        try {
            // 解析响应以获取加密 output。
            Map<String, Object> responseMap =
                    JsonUtils.getJsonCodec()
                            .fromJson(responseBody, new TypeReference<Map<String, Object>>() {});

            Object outputObj = responseMap.get("output");
            if (outputObj == null || !(outputObj instanceof String)) {
                return responseBody; // 没有需要解密的 output。
            }

            String encryptedOutput = (String) outputObj;

            // 解密 output。
            String decryptedOutput =
                    DashScopeEncryptionUtils.decryptWithAes(
                            context.secretKey, context.iv, encryptedOutput);

            // 用解密值替换 output，并解析为 JSON 对象。
            Object decryptedOutputObj =
                    JsonUtils.getJsonCodec().fromJson(decryptedOutput, Object.class);
            responseMap.put("output", decryptedOutputObj);

            return JsonUtils.getJsonCodec().toJson(responseMap);
        } catch (Exception e) {
            log.error("Failed to decrypt response body", e);
            // 解密失败时返回原响应。
            return responseBody;
        }
    }

    /**
     * 保存加密上下文（AES 密钥、IV 及加密 AES 密钥）的内部类。
     */
    private static class EncryptionContext {
        final SecretKey secretKey;
        final byte[] iv;
        final String encryptedAesKey;

        EncryptionContext(SecretKey secretKey, byte[] iv, String encryptedAesKey) {
            this.secretKey = secretKey;
            this.iv = iv;
            this.encryptedAesKey = encryptedAesKey;
        }
    }

    private static class EncryptionResult {
        final String requestBody;
        final EncryptionContext context;

        EncryptionResult(String requestBody, EncryptionContext context) {
            this.requestBody = requestBody;
            this.context = context;
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
     * 创建 DashScopeHttpClient 构建器。
     *
     * @return 新的 Builder 实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * DashScopeHttpClient 的构建器。
     */
    public static class Builder {
        private HttpTransport transport;
        private String apiKey;
        private String baseUrl;
        private String publicKeyId;
        private String publicKey;
        private Collection<String> userMultimodalPatterns;

        /**
         * 设置 HTTP 传输层。
         *
         * @param transport 使用的传输层
         * @return 当前构建器
         */
        public Builder transport(HttpTransport transport) {
            this.transport = transport;
            return this;
        }

        /**
         * 设置 API 密钥。
         *
         * @param apiKey DashScope API 密钥
         * @return 当前构建器
         */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /**
         * 设置基础 URL。
         *
         * @param baseUrl 基础 URL（null 使用默认值）
         * @return 当前构建器
         */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /**
         * 设置加密 RSA 公钥 ID。
         *
         * <p>publicKeyId 和 publicKey 均设置时，按阿里云协议使用 AES-GCM 与 RSA 密钥交换加密请求和响应。
         *
         * @param publicKeyId 公钥 ID（null 禁用加密）
         * @return 当前构建器
         */
        public Builder publicKeyId(String publicKeyId) {
            this.publicKeyId = publicKeyId;
            return this;
        }

        /**
         * 设置 Base64 编码的加密 RSA 公钥。
         *
         * <p>publicKeyId 和 publicKey 均设置时，按阿里云协议使用 AES-GCM 与 RSA 密钥交换加密请求和响应。
         *
         * @param publicKey Base64 编码的 RSA 公钥（null 禁用加密）
         * @return 当前构建器
         */
        public Builder publicKey(String publicKey) {
            this.publicKey = publicKey;
            return this;
        }

        /**
         * 设置额外的多模态模型检测子串模式，忽略大小写。
         *
         * <p>使用 {@link EndpointType#AUTO} 路由时，除了 {@link DashScopeHttpClient#isMultimodalModel(String)} 的内置规则，名称包含任一模式（忽略大小写）的模型也路由到多模态生成 API。用户可扩展尚未被内置名单覆盖的模型（例如 {@code "deepseek-v4.1"}），无需等待框架更新。
         *
         * <p>示例：
         * <pre>{@code
         * DashScopeHttpClient client = DashScopeHttpClient.builder()
         *     .apiKey("sk-xxx")
         *     .multimodalModelPatterns(List.of("deepseek-v4"))
         *     .build();
         * }</pre>
         *
         * @param userMultimodalPatterns 忽略大小写的子串模式（可为 null）
         * @return 当前构建器
         */
        public Builder multimodalModelPatterns(Collection<String> userMultimodalPatterns) {
            this.userMultimodalPatterns = userMultimodalPatterns;
            return this;
        }

        /**
         * 构建 DashScopeHttpClient。
         *
         * <p>未指定传输层时使用 {@link HttpTransportFactory#getDefault()}，提供自动生命周期管理及 JVM 退出清理。
         *
         * @return 新的 DashScopeHttpClient 实例
         */
        public DashScopeHttpClient build() {
            if (apiKey == null || apiKey.isEmpty()) {
                throw new IllegalArgumentException("API key is required");
            }
            if (transport == null) {
                transport = HttpTransportFactory.getDefault();
            }
            return new DashScopeHttpClient(
                    transport, apiKey, baseUrl, publicKeyId, publicKey, userMultimodalPatterns);
        }
    }

    private record ParsedStreamResponse(String responseBody, DashScopeResponse response) {}

    /**
     * DashScope HTTP 操作失败时抛出的异常。
     */
    public static class DashScopeHttpException extends RuntimeException {
        private final Integer statusCode;
        private final String errorCode;
        private final String responseBody;

        public DashScopeHttpException(String message) {
            super(message);
            this.statusCode = null;
            this.errorCode = null;
            this.responseBody = null;
        }

        public DashScopeHttpException(String message, Throwable cause) {
            super(message, cause);
            this.statusCode = null;
            this.errorCode = null;
            this.responseBody = null;
        }

        public DashScopeHttpException(String message, int statusCode, String responseBody) {
            super(message);
            this.statusCode = statusCode;
            this.errorCode = null;
            this.responseBody = responseBody;
        }

        public DashScopeHttpException(String message, String errorCode, String responseBody) {
            super(message);
            this.statusCode = null;
            this.errorCode = errorCode;
            this.responseBody = responseBody;
        }

        public Integer getStatusCode() {
            return statusCode;
        }

        public String getErrorCode() {
            return errorCode;
        }

        public String getResponseBody() {
            return responseBody;
        }
    }
}
