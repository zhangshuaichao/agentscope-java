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
package io.agentscope.core.memory.mem0;

import com.fasterxml.jackson.core.type.TypeReference;
import io.agentscope.core.util.JsonCodec;
import io.agentscope.core.util.JsonUtils;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 与 Mem0 API 交互的 HTTP 客户端。
 *
 * <p>支持平台与自托管部署：
 * <ul><li><b>平台：</b>使用 /v1/memories/ 和 /v2/memories/search/。</li>
 * <li><b>自托管：</b>当前实现使用 /memories 和 /search。</li></ul>
 *
 * <p>默认使用平台端点；自托管部署应选择相应 apiType。
 */
public class Mem0Client {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    // 平台 Mem0 端点。
    private static final String PLATFORM_MEMORIES_ENDPOINT = "/v1/memories/";
    private static final String PLATFORM_SEARCH_ENDPOINT = "/v2/memories/search/";

    // 自托管 Mem0 端点。
    private static final String SELF_HOSTED_MEMORIES_ENDPOINT = "/memories";
    private static final String SELF_HOSTED_SEARCH_ENDPOINT = "/search";

    private final OkHttpClient httpClient;
    private final String apiBaseUrl;
    private final String apiKey;
    private final Mem0ApiType apiType;
    private final JsonCodec jsonCodec;
    private final String addEndpoint;
    private final String searchEndpoint;

    /**
     * 使用指定配置创建 Mem0Client，默认为平台模式。
     *
     * @param apiBaseUrl Mem0 API 基础 URL，例如 "http://localhost:8000"
     * @param apiKey 认证 API 密钥，无认证本地部署可为 null
     */
    public Mem0Client(String apiBaseUrl, String apiKey) {
        this(apiBaseUrl, apiKey, Duration.ofSeconds(60));
    }

    /**
     * 使用自定义超时创建 Mem0Client，默认为平台模式。
     *
     * @param apiBaseUrl Mem0 API 基础 URL
     * @param apiKey 认证 API 密钥，无认证本地部署可为 null
     * @param timeout HTTP 请求超时时长
     */
    public Mem0Client(String apiBaseUrl, String apiKey, Duration timeout) {
        this(apiBaseUrl, apiKey, Mem0ApiType.PLATFORM, timeout);
    }

    /**
     * 使用指定 API 类型创建 Mem0Client。
     *
     * @param apiBaseUrl Mem0 API 基础 URL
     * @param apiKey 认证 API 密钥，无认证本地部署可为 null
     * @param apiType API 类型枚举
     * @param timeout HTTP 请求超时时长
     */
    public Mem0Client(String apiBaseUrl, String apiKey, Mem0ApiType apiType, Duration timeout) {
        this.apiBaseUrl =
                apiBaseUrl.endsWith("/")
                        ? apiBaseUrl.substring(0, apiBaseUrl.length() - 1)
                        : apiBaseUrl;
        this.apiKey = apiKey;
        this.apiType = apiType != null ? apiType : Mem0ApiType.PLATFORM;
        this.jsonCodec = JsonUtils.getJsonCodec();
        this.httpClient =
                new OkHttpClient.Builder()
                        .connectTimeout(Duration.ofSeconds(30))
                        .readTimeout(timeout)
                        .writeTimeout(Duration.ofSeconds(30))
                        .build();

        // 根据 API 类型选择端点。
        if (this.apiType == Mem0ApiType.SELF_HOSTED) {
            this.addEndpoint = SELF_HOSTED_MEMORIES_ENDPOINT;
            this.searchEndpoint = SELF_HOSTED_SEARCH_ENDPOINT;
        } else {
            // 默认使用平台端点。
            this.addEndpoint = PLATFORM_MEMORIES_ENDPOINT;
            this.searchEndpoint = PLATFORM_SEARCH_ENDPOINT;
        }
    }

    /**
     * 执行 POST 请求，返回字符串形式的原始响应体。
     *
     * <p>此底层方法处理 HTTP 通信、JSON 序列化与错误，响应解析交给调用方。
     *
     * @param endpoint API 端点路径，例如 "/v1/memories"
     * @param request 待序列化为 JSON 的请求对象
     * @param operationName 错误消息使用的可读操作名称
     * @param <T> 请求类型
     * @return 发出原始响应体字符串的 Mono
     * @throws IOException HTTP 请求失败时抛出
     */
    private <T> Mono<String> executePostRaw(String endpoint, T request, String operationName) {
        return Mono.fromCallable(
                        () -> {
                            // 将请求序列化为 JSON。
                            String json = jsonCodec.toJson(request);

                            // 构建 HTTP 请求。
                            Request.Builder requestBuilder =
                                    new Request.Builder()
                                            .url(apiBaseUrl + endpoint)
                                            .addHeader("Content-Type", "application/json")
                                            .post(RequestBody.create(json, JSON));

                            // 仅提供 apiKey 时添加当前部署类型所需的认证请求头。
                            if (apiKey != null && !apiKey.isEmpty()) {
                                if (apiType == Mem0ApiType.SELF_HOSTED) {
                                    requestBuilder.addHeader("X-API-Key", apiKey);
                                } else {
                                    requestBuilder.addHeader("Authorization", "Token " + apiKey);
                                }
                            }

                            Request httpRequest = requestBuilder.build();

                            // 执行请求。
                            try (Response response = httpClient.newCall(httpRequest).execute()) {
                                if (!response.isSuccessful()) {
                                    String errorBody =
                                            response.body() != null
                                                    ? response.body().string()
                                                    : "No error details";
                                    throw new IOException(
                                            "Mem0 API "
                                                    + operationName
                                                    + " failed with status "
                                                    + response.code()
                                                    + ": "
                                                    + errorBody);
                                }

                                // 返回原始响应体。
                                return response.body().string();
                            } catch (IOException e) {
                                // 原样重抛 IOException，它已包含状态码信息。
                                throw e;
                            } catch (Exception e) {
                                // 包装其他异常。
                                throw new IOException("Mem0 API " + operationName + " failed", e);
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 向 Mem0 API 执行 POST 请求并解析响应。
     *
     * <p>统一处理 POST 端点的 HTTP 通信、JSON 序列化、错误处理与响应解析。
     *
     * @param endpoint API 端点路径，例如 "/v1/memories"
     * @param request 待序列化为 JSON 的请求对象
     * @param responseType 响应类型
     * @param operationName 错误消息使用的可读操作名称
     * @param <T> 请求类型
     * @param <R> 响应类型
     * @return 发出解析结果的 Mono
     * @throws IOException HTTP 请求失败或响应无法解析时抛出
     */
    private <T, R> Mono<R> executePost(
            String endpoint, T request, Class<R> responseType, String operationName) {
        return executePostRaw(endpoint, request, operationName)
                .map(responseBody -> jsonCodec.fromJson(responseBody, responseType));
    }

    /**
     * 向 Mem0 发送消息以添加记忆。
     *
     * <p>平台端点为 {@code POST /v1/memories}，自托管使用已配置的对应端点。
     * Mem0 使用 LLM 推断提取记忆，除非请求中 {@code infer} 为 false。
     *
     * <p>同步网络工作调度到 boundedElastic，避免阻塞调用线程。
     *
     * @param request 包含消息与元数据的添加请求
     * @return 发出添加响应的 Mono，异步提取模式下不保证响应已包含最终记忆
     */
    public Mono<Mem0AddResponse> add(Mem0AddRequest request) {
        return executePost(addEndpoint, request, Mem0AddResponse.class, "add request");
    }

    /**
     * 通过语义相似性搜索 Mem0 记忆。
     *
     * <p>平台端点为 {@code POST /v2/memories/search/}，自托管使用对应端点。
     * 服务端通常按相关性分数从高到低返回结果。
     *
     * <p>自动兼容两种响应形式：
     * <ul><li><b>v1.1 格式</b>：带 {@code results} 字段的 JSON 对象，
     * 例如 {@code {"results": [...]}}，直接反序列化为 {@link Mem0SearchResponse}。</li>
     * <li><b>v1.0 格式</b>：直接返回 JSON 数组，例如 {@code [...]}，
     * 先解析列表，再包装为 {@link Mem0SearchResponse}。</li></ul>
     *
     * <p>请求中的 agent_id、user_id、run_id 等过滤条件用于限定检索上下文。
     *
     * @param request 包含查询与过滤条件的搜索请求
     * @return 发出相关记忆搜索响应的 Mono
     */
    public Mono<Mem0SearchResponse> search(Mem0SearchRequest request) {
        return executePostRaw(searchEndpoint, request, "search request")
                .map(
                        responseBody -> {
                            // 兼容直接数组和带 results 的对象两种响应形式。
                            String trimmed = responseBody != null ? responseBody.trim() : "";
                            if (trimmed.startsWith("[")) {
                                // 数组响应：解析为列表并包装到 results。
                                List<Mem0SearchResult> results =
                                        jsonCodec.fromJson(
                                                responseBody,
                                                new TypeReference<List<Mem0SearchResult>>() {});
                                Mem0SearchResponse searchResponse = new Mem0SearchResponse();
                                searchResponse.setResults(results);
                                return searchResponse;
                            }
                            // 对象响应，例如 {"results": [...]}。
                            return jsonCodec.fromJson(responseBody, Mem0SearchResponse.class);
                        });
    }

    /**
     * 关闭 HTTP 客户端并释放资源。
     *
     * <p>不再需要客户端时调用；关闭后不得继续发起请求。
     */
    public void shutdown() {
        httpClient.dispatcher().executorService().shutdown();
        httpClient.connectionPool().evictAll();
    }
}
