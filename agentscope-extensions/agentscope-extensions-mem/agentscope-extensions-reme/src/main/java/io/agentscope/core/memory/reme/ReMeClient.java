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
package io.agentscope.core.memory.reme;

import io.agentscope.core.util.JsonUtils;
import java.io.IOException;
import java.time.Duration;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** 与 ReMe API 交互的 HTTP 客户端。 */
public class ReMeClient {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final String SUMMARY_ENDPOINT = "/summary_personal_memory";
    private static final String RETRIEVE_ENDPOINT = "/retrieve_personal_memory";

    private final OkHttpClient httpClient;
    private final String apiBaseUrl;

    /**
     * 使用指定配置创建 ReMeClient。
     *
     * @param apiBaseUrl ReMe API 基础 URL，例如 "http://localhost:8002"
     */
    public ReMeClient(String apiBaseUrl) {
        this(apiBaseUrl, Duration.ofSeconds(60));
    }

    /**
     * 使用自定义超时创建 ReMeClient。
     *
     * @param apiBaseUrl ReMe API 基础 URL
     * @param timeout HTTP 请求超时时长
     */
    public ReMeClient(String apiBaseUrl, Duration timeout) {
        this.apiBaseUrl =
                apiBaseUrl.endsWith("/")
                        ? apiBaseUrl.substring(0, apiBaseUrl.length() - 1)
                        : apiBaseUrl;
        this.httpClient =
                new OkHttpClient.Builder()
                        .connectTimeout(Duration.ofSeconds(30))
                        .readTimeout(timeout)
                        .writeTimeout(Duration.ofSeconds(30))
                        .build();
    }

    /**
     * 向 ReMe API 执行 POST 请求并解析响应。
     *
     * <p>此通用方法统一处理所有 POST 端点的 HTTP 通信、JSON 序列化、错误处理与响应解析。
     *
     * @param endpoint API 端点路径，例如 "/summary_personal_memory"
     * @param request 待序列化为 JSON 的请求对象
     * @param responseType 响应类型
     * @param operationName 错误消息使用的可读操作名称
     * @param <T> 请求类型
     * @param <R> 响应类型
     * @return 发出解析结果的 Mono
     */
    private <T, R> Mono<R> executePost(
            String endpoint, T request, Class<R> responseType, String operationName) {
        return Mono.fromCallable(
                        () -> {
                            // 将请求序列化为 JSON。
                            String json = JsonUtils.getJsonCodec().toJson(request);

                            // 构建 HTTP 请求。
                            Request httpRequest =
                                    new Request.Builder()
                                            .url(apiBaseUrl + endpoint)
                                            .addHeader("Content-Type", "application/json")
                                            .post(RequestBody.create(json, JSON))
                                            .build();

                            // 执行请求。
                            try (Response response = httpClient.newCall(httpRequest).execute()) {
                                if (!response.isSuccessful()) {
                                    String errorBody =
                                            response.body() != null
                                                    ? response.body().string()
                                                    : "No error details";
                                    throw new IOException(
                                            "ReMe API "
                                                    + operationName
                                                    + " failed with status "
                                                    + response.code()
                                                    + ": "
                                                    + errorBody);
                                }

                                // 解析并返回响应。
                                ResponseBody body = response.body();
                                if (body == null) {
                                    // 响应体为 null 时返回空响应对象。
                                    return JsonUtils.getJsonCodec().fromJson("{}", responseType);
                                }
                                String responseBody = body.string();
                                if (responseBody == null || responseBody.trim().isEmpty()) {
                                    // 响应体为空时返回空响应对象。
                                    return JsonUtils.getJsonCodec().fromJson("{}", responseType);
                                }
                                return JsonUtils.getJsonCodec()
                                        .fromJson(responseBody, responseType);
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 向 ReMe 发送待处理轨迹以添加记忆。
     *
     * <p>调用 {@code POST /summary_personal_memory}，由 ReMe 处理轨迹并提取值得记忆的信息。
     *
     * <p>操作调度到 boundedElastic 线程池，避免同步网络等待阻塞调用线程。
     *
     * <p>【学习提示】返回 Mono 只是描述待执行操作，订阅才会发起请求；
     * HTTP 响应完成与服务端业务成功仍需结合响应字段判断，不能仅凭对象存在就认定提取成功。
     *
     * @param request 包含轨迹与工作区 ID 的添加请求
     * @return 发出响应的 Mono
     */
    public Mono<ReMeAddResponse> add(ReMeAddRequest request) {
        return executePost(
                SUMMARY_ENDPOINT, request, ReMeAddResponse.class, "summary_personal_memory");
    }

    /**
     * 使用给定查询搜索 ReMe 记忆。
     *
     * <p>调用 {@code POST /retrieve_personal_memory}，查找与查询字符串相关的记忆。
     *
     * <p>操作调度到 boundedElastic 线程池，避免同步网络等待阻塞调用线程。
     *
     * @param request 包含查询、工作区 ID 和 topK 的搜索请求
     * @return 发出含相关记忆的搜索响应的 Mono
     */
    public Mono<ReMeSearchResponse> search(ReMeSearchRequest request) {
        return executePost(
                RETRIEVE_ENDPOINT, request, ReMeSearchResponse.class, "retrieve_personal_memory");
    }

    /**
     * 关闭 HTTP 客户端并释放资源。
     *
     * <p>不再需要客户端时调用，关闭后不得继续发起请求。
     */
    public void shutdown() {
        httpClient.dispatcher().executorService().shutdown();
        httpClient.connectionPool().evictAll();
    }
}
