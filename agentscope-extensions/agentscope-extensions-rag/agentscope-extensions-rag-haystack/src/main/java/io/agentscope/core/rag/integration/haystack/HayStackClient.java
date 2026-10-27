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
package io.agentscope.core.rag.integration.haystack;

import io.agentscope.core.rag.integration.haystack.exception.HayStackApiException;
import io.agentscope.core.rag.integration.haystack.model.HayStackResponse;
import io.agentscope.core.util.JsonUtils;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * 与 HayStack 知识库 API 交互的客户端。
 *
 * <p>封装 OkHttp，提供响应式返回类型，负责 HTTP 请求构建、错误处理和响应解析。
 *
 * <p>用法示例：
 * <pre>{@code
 * HayStackConfig config = HayStackConfig.builder()
 *     .baseUrl(...)
 *     .topK(...)
 *     .build();
 *
 * HayStackClient client = new HayStackClient(config);
 * HayStackResponse response = client.retrieve("query text", 10).block();
 * }</pre>
 *
 * <p>【学习提示】响应式类型不保证非阻塞：此实现仍在订阅时执行同步 HTTP 调用与阻塞退避。
 * 接入异步应用时应检查调用线程，不要让网络等待阻塞事件循环。
 */
public class HayStackClient {

    private static final Logger logger = LoggerFactory.getLogger(HayStackClient.class);

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient httpClient;

    private final HayStackConfig config;

    public HayStackClient(HayStackConfig config) {
        this.config = config;
        this.httpClient = createHttpClient();
    }

    HayStackClient(OkHttpClient httpClient, HayStackConfig config) {
        this.httpClient = httpClient;
        this.config = config;
    }

    /**
     * 从 HayStack 知识库检索相关文档块。
     *
     * <p>Haystack 没有本插件通用适用的标准 REST 响应格式，
     * 因此<b>自行构建的 Haystack RAG 服务</b>应遵循以下约定：
     * <pre>{@code
     * {
     *   "code": 0,
     *   "documents": [
     *     {
     *       "id": "0fab3c1c6c433368",
     *       "content": "Test Content",
     *       "blob": null,
     *       "meta": {
     *         "file_path": "test.txt"
     *       },
     *       "score": 1.2303546667099,
     *       "embedding": [
     *         0.01008153147995472,
     *         -0.04555170238018036,
     *         -0.024434546008706093
     *       ],
     *       "sparse_embedding": null,
     *       "error": null
     *     }
     *   ]
     * }
     * }</pre>
     *
     * <p>典型服务构建示例：
     * <pre>{@code
     * @app.post("/retrieve", response_model=HayStackResponse)
     * async def retriever(req: Request):
     *     result = retriever.run(
     *         query=req.query,
     *         top_k=req.top_k,
     *     )
     *     documents = result["documents"]
     *
     *     return {
     *         "code": 0,
     *         "documents": documents,
     *         "error": None
     *     }
     * }</pre>
     *
     * @param query 查询文本，必填
     * @param topK 检索文档数量，可选，默认使用配置值
     * @return 发出检索响应的 Mono
     */
    public Mono<HayStackResponse> retrieve(String query, Integer topK, Double scoreThreshold) {
        return Mono.fromCallable(
                () -> {
                    if (query == null || query.trim().isEmpty()) {
                        throw new IllegalArgumentException("Query cannot be null or empty");
                    }

                    Map<String, Object> requestBody = new HashMap<>();

                    // 必填：问题文本。
                    requestBody.put("query", query);

                    // 可选：top_k，默认 3。
                    if (topK != null) {
                        requestBody.put("top_k", topK);
                    } else if (config.getTopK() != null) {
                        requestBody.put("top_k", config.getTopK());
                    }

                    // 可选：scale_score。
                    if (config.getScaleScore() != null) {
                        requestBody.put("scale_score", config.getScaleScore());
                    }

                    // 可选：return_embedding。
                    if (config.getReturnEmbedding() != null) {
                        requestBody.put("return_embedding", config.getReturnEmbedding());
                    }

                    Double threshold =
                            scoreThreshold != null ? scoreThreshold : config.getScoreThreshold();

                    // 可选：score_threshold。
                    if (threshold != null) {
                        requestBody.put("score_threshold", threshold);
                    }

                    // 可选：group。
                    if (config.getGroupBy() != null) {
                        requestBody.put("group_by", config.getGroupBy());
                    }

                    // 可选：group_size。
                    if (config.getGroupSize() != null) {
                        requestBody.put("group_size", config.getGroupSize());
                    }

                    // 可选：filters。
                    if (config.getFilters() != null) {
                        requestBody.put("filters", config.getFilters());
                    }

                    // 可选：query_embedding。
                    if (config.getQueryEmbedding() != null) {
                        requestBody.put("query_embedding", config.getQueryEmbedding());
                    }

                    // 可选：query_sparse_embedding。
                    if (config.getQuerySparseEmbedding() != null) {
                        requestBody.put("query_sparse_embedding", config.getQuerySparseEmbedding());
                    }

                    // 可选：documents。
                    if (config.getDocuments() != null) {
                        requestBody.put("documents", config.getDocuments());
                    }

                    // 可选：retrieved_documents。
                    if (config.getRetrievedDocuments() != null) {
                        requestBody.put("retrieved_documents", config.getRetrievedDocuments());
                    }

                    // 可选：window_size。
                    if (config.getWindowSize() != null) {
                        requestBody.put("window_size", config.getWindowSize());
                    }

                    String jsonBody = JsonUtils.getJsonCodec().toJson(requestBody);

                    String url = config.getBaseUrl();

                    logger.debug("HayStack retrieval request: URL={}, body={}", url, jsonBody);

                    Request request =
                            new Request.Builder()
                                    .url(url)
                                    .post(RequestBody.create(jsonBody, JSON))
                                    .addHeader("Content-Type", "application/json")
                                    .build();

                    // 添加自定义请求头。
                    if (config.getCustomHeaders() != null) {
                        Request.Builder builder = request.newBuilder();
                        config.getCustomHeaders().forEach(builder::addHeader);
                        request = builder.build();
                    }

                    try (Response response = httpClient.newCall(request).execute()) {
                        String responseBody =
                                response.body() != null ? response.body().string() : "";

                        logger.debug(
                                "HayStack API response: status={}, body={}",
                                response.code(),
                                responseBody);

                        if (!response.isSuccessful()) {
                            logger.error(
                                    "HayStack API error: status={}, body={}",
                                    response.code(),
                                    responseBody);
                        }

                        HayStackResponse hayStackResponse =
                                JsonUtils.getJsonCodec()
                                        .fromJson(responseBody, HayStackResponse.class);

                        // 检查响应是否表示错误。
                        if (hayStackResponse.getCode() != null
                                && !hayStackResponse.getCode().equals(config.getSuccessCode())) {
                            throw new HayStackApiException(
                                    "HayStack API error: " + hayStackResponse.getError(),
                                    hayStackResponse.getCode());
                        }

                        return hayStackResponse;
                    }
                });
    }

    private OkHttpClient createHttpClient() {
        OkHttpClient.Builder builder =
                new OkHttpClient.Builder()
                        .connectTimeout(config.getTimeout().toMillis(), TimeUnit.MILLISECONDS)
                        .readTimeout(config.getTimeout().toMillis(), TimeUnit.MILLISECONDS)
                        .writeTimeout(config.getTimeout().toMillis(), TimeUnit.MILLISECONDS);

        // 最大重试次数大于 0 时添加重试拦截器。
        if (config.getMaxRetries() != null && config.getMaxRetries() > 0) {
            builder.addInterceptor(new RetryInterceptor(config.getMaxRetries()));
        }

        // 添加日志拦截器。
        if (logger.isDebugEnabled()) {
            builder.addInterceptor(new LoggingInterceptor());
        }

        return builder.build();
    }

    /** 重试失败请求的拦截器。 */
    private record RetryInterceptor(int maxRetries) implements Interceptor {

        @Override
        public @NonNull Response intercept(Chain chain) throws IOException {
            Request request = chain.request();
            Response response = null;
            IOException lastException = null;

            try {
                for (int attempt = 0; attempt <= maxRetries; attempt++) {
                    try {
                        if (response != null) {
                            response.close();
                        }
                        response = chain.proceed(request);

                        // 成功响应或客户端错误（4xx）不重试。
                        if (response.isSuccessful() || response.code() < 500) {
                            lastException = null;
                            return response;
                        }

                        logger.warn(
                                "HayStack request failed with status {}, attempt {}/{}",
                                response.code(),
                                attempt + 1,
                                maxRetries + 1);

                    } catch (IOException e) {
                        lastException = e;
                        logger.warn(
                                "HayStack request failed with exception, attempt {}/{}: {}",
                                attempt + 1,
                                maxRetries + 1,
                                e.getMessage());

                        if (attempt == maxRetries) {
                            throw e;
                        }
                    }

                    // 重试前等待，采用指数退避。
                    if (attempt < maxRetries) {
                        try {
                            long waitTime = (long) Math.pow(2, attempt) * 1000;
                            Thread.sleep(Math.min(waitTime, 10000)); // 最长 10 秒。
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IOException("Retry interrupted", e);
                        }
                    }
                }

                if (lastException != null) {
                    throw lastException;
                }

                return response;
            } finally {
                // 未成功返回响应时，确保将其关闭。
                if (response != null && (lastException != null || !response.isSuccessful())) {
                    response.close();
                }
            }
        }
    }

    /** 记录 HTTP 请求和响应的日志拦截器。 */
    private static class LoggingInterceptor implements Interceptor {

        @Override
        public @NonNull Response intercept(Chain chain) throws IOException {
            Request request = chain.request();

            logger.debug("HayStack HTTP Request: {} {}", request.method(), request.url());

            long startTime = System.currentTimeMillis();
            Response response = chain.proceed(request);
            long duration = System.currentTimeMillis() - startTime;

            logger.debug("HayStack HTTP Response: {} in {}ms", response.code(), duration);

            return response;
        }
    }
}
