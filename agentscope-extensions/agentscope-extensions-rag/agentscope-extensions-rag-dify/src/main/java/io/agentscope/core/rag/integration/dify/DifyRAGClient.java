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
package io.agentscope.core.rag.integration.dify;

import io.agentscope.core.rag.integration.dify.exception.DifyApiException;
import io.agentscope.core.rag.integration.dify.exception.DifyAuthException;
import io.agentscope.core.rag.integration.dify.model.DifyResponse;
import io.agentscope.core.util.JsonCodec;
import io.agentscope.core.util.JsonUtils;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * 与 Dify 知识库 API 交互的客户端。
 *
 * <p>封装 OkHttp 并提供响应式返回类型，负责 HTTP 请求构建、认证、错误处理和响应解析。
 *
 * <p>用法示例：
 * <pre>{@code
 * DifyRAGConfig config = DifyRAGConfig.builder()
 *     .apiKey(...)
 *     .datasetId(...)
 *     .build();
 *
 * DifyRAGClient client = new DifyRAGClient(config);
 * DifyResponse response = client.retrieve("query text", 10).block();
 * }</pre>
 *
 * <p>【学习提示】返回 Mono 不自动意味着底层 I/O 非阻塞：这里在 fromCallable 中执行同步 HTTP 调用，
 * 重试退避也会阻塞当前线程。调用方在接入事件循环时应关注订阅线程的调度方式。
 */
public class DifyRAGClient {

    private static final Logger log = LoggerFactory.getLogger(DifyRAGClient.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private final DifyRAGConfig config;
    private final JsonCodec jsonCodec;

    /**
     * 创建 DifyRAGClient 实例。
     *
     * @param config Dify 配置
     */
    public DifyRAGClient(DifyRAGConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("DifyRAGConfig cannot be null");
        }

        this.config = config;
        this.jsonCodec = JsonUtils.getJsonCodec();
        this.httpClient = createHttpClient(config);

        log.info(
                "DifyRAGClient initialized for dataset: {} at endpoint: {}",
                config.getDatasetId(),
                config.getApiBaseUrl());
    }

    /**
     * 供测试使用的包内可见构造器。
     *
     * <p>允许注入模拟 HTTP 客户端，无需访问真实 Dify API 即可单元测试。
     *
     * @param httpClient HTTP 客户端
     * @param config Dify 配置
     * @param jsonCodec JSON 编解码器
     */
    DifyRAGClient(OkHttpClient httpClient, DifyRAGConfig config, JsonCodec jsonCodec) {
        if (httpClient == null) {
            throw new IllegalArgumentException("HTTP client cannot be null");
        }
        if (config == null) {
            throw new IllegalArgumentException("DifyRAGConfig cannot be null");
        }

        this.httpClient = httpClient;
        this.config = config;
        this.jsonCodec = jsonCodec != null ? jsonCodec : JsonUtils.getJsonCodec();

        log.info("DifyRAGClient initialized for testing with dataset: {}", config.getDatasetId());
    }

    /**
     * 从知识库检索相关文档。
     *
     * <p>按 DifyRAGConfig 中配置的关键词、语义或混合模式查询相关文档。
     *
     * <p>端点：POST /datasets/{dataset_id}/retrieve。
     *
     * @param query 查询文本
     * @param limit 最大检索文档数，null 使用默认值
     * @return 发出 Dify API 响应的 Mono
     */
    public Mono<DifyResponse> retrieve(String query, Integer limit) {
        if (query == null || query.trim().isEmpty()) {
            return Mono.error(new IllegalArgumentException("Query cannot be null or empty"));
        }

        return Mono.fromCallable(
                () -> {
                    log.debug(
                            "Retrieving from dataset: {} with query: {} (limit: {})",
                            config.getDatasetId(),
                            truncate(query, 50),
                            limit);

                    // 构建 retrieval_model 对象。
                    Map<String, Object> retrievalModel = new HashMap<>();

                    // search_method 支持 hybrid_search、semantic_search、keyword_search，
                    // 以及 full_text_search。
                    retrievalModel.put("search_method", config.getRetrievalMode().getValue());

                    // top_k：召回数量。
                    int topK = limit != null ? limit : config.getTopK();
                    retrievalModel.put("top_k", topK);

                    // score_threshold：分数阈值。
                    if (config.getScoreThreshold() != null && config.getScoreThreshold() > 0) {
                        retrievalModel.put("score_threshold_enabled", true);
                        retrievalModel.put("score_threshold", config.getScoreThreshold());
                    } else {
                        retrievalModel.put("score_threshold_enabled", false);
                    }

                    // 重排序配置。
                    if (Boolean.TRUE.equals(config.getEnableRerank())) {
                        retrievalModel.put("reranking_enable", true);

                        if (config.getRerankConfig() != null) {
                            Map<String, Object> rerankingModel = new HashMap<>();
                            if (config.getRerankConfig().getProviderName() != null) {
                                rerankingModel.put(
                                        "reranking_provider_name",
                                        config.getRerankConfig().getProviderName());
                            }
                            if (config.getRerankConfig().getModelName() != null) {
                                rerankingModel.put(
                                        "reranking_model_name",
                                        config.getRerankConfig().getModelName());
                            }
                            if (!rerankingModel.isEmpty()) {
                                retrievalModel.put("reranking_model", rerankingModel);
                            }
                        }
                    } else {
                        retrievalModel.put("reranking_enable", false);
                    }

                    // 混合搜索权重。
                    if (config.getWeights() != null) {
                        retrievalModel.put("weights", config.getWeights());
                    }

                    // 元数据过滤条件。
                    if (config.getMetadataFilter() != null) {
                        Map<String, Object> metadataFilteringConditions = new HashMap<>();
                        metadataFilteringConditions.put(
                                "logical_operator",
                                config.getMetadataFilter().getLogicalOperator());

                        List<Map<String, String>> conditions = new ArrayList<>();
                        for (MetadataFilterCondition condition :
                                config.getMetadataFilter().getConditions()) {
                            Map<String, String> conditionMap = new HashMap<>();
                            conditionMap.put("name", condition.getName());
                            conditionMap.put(
                                    "comparison_operator", condition.getComparisonOperator());
                            conditionMap.put("value", condition.getValue());
                            conditions.add(conditionMap);
                        }
                        metadataFilteringConditions.put("conditions", conditions);

                        retrievalModel.put(
                                "metadata_filtering_conditions", metadataFilteringConditions);
                    }

                    // 构建请求体。
                    Map<String, Object> requestBody = new HashMap<>();
                    requestBody.put("query", query);
                    requestBody.put("retrieval_model", retrievalModel);

                    // 构建 URL：/datasets/{dataset_id}/retrieve。
                    String url =
                            config.getApiBaseUrl()
                                    + "/datasets/"
                                    + config.getDatasetId()
                                    + "/retrieve";

                    // 构建请求。
                    String jsonBody = jsonCodec.toJson(requestBody);
                    log.debug("Dify API request body: {}", jsonBody);

                    Request.Builder requestBuilder =
                            new Request.Builder()
                                    .url(url)
                                    .post(RequestBody.create(jsonBody, JSON))
                                    .addHeader("Authorization", "Bearer " + config.getApiKey())
                                    .addHeader("Content-Type", "application/json");

                    // 添加自定义请求头。
                    for (Map.Entry<String, String> header : config.getCustomHeaders().entrySet()) {
                        requestBuilder.addHeader(header.getKey(), header.getValue());
                    }

                    Request request = requestBuilder.build();

                    // 执行请求。
                    try (Response response = httpClient.newCall(request).execute()) {
                        String responseBody =
                                response.body() != null ? response.body().string() : "";

                        log.debug(
                                "Dify API response: status={}, bodyLength={}",
                                response.code(),
                                responseBody.length());

                        // 处理错误。
                        if (!response.isSuccessful()) {
                            handleErrorResponse(response.code(), responseBody);
                        }

                        // 解析响应。
                        DifyResponse difyResponse =
                                jsonCodec.fromJson(responseBody, DifyResponse.class);

                        if (difyResponse == null) {
                            log.warn("Dify API returned null response");
                            return new DifyResponse(); // 返回空响应。
                        }

                        int resultCount =
                                difyResponse.getRecords() != null
                                        ? difyResponse.getRecords().size()
                                        : 0;
                        log.debug(
                                "Retrieved {} documents from dataset: {}",
                                resultCount,
                                config.getDatasetId());

                        return difyResponse;
                    }
                });
    }

    /**
     * 处理 Dify API 的错误响应。
     *
     * @param statusCode HTTP 状态码
     * @param responseBody 响应体
     * @throws DifyApiException API 错误时抛出
     */
    private void handleErrorResponse(int statusCode, String responseBody) {
        log.error("Dify API error: status={}, body={}", statusCode, truncate(responseBody, 200));

        // 尽可能解析错误消息。
        String errorMessage = "Dify API error";
        String errorCode = null;

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> errorBody = jsonCodec.fromJson(responseBody, Map.class);
            if (errorBody.containsKey("message")) {
                errorMessage = errorBody.get("message").toString();
            }
            if (errorBody.containsKey("code")) {
                errorCode = errorBody.get("code").toString();
            }
        } catch (Exception e) {
            // 解析失败时使用原始响应体。
            if (responseBody != null && !responseBody.isEmpty()) {
                errorMessage = responseBody;
            }
        }

        // 抛出对应异常。
        if (statusCode == 401 || statusCode == 403) {
            throw new DifyAuthException(statusCode, "Authentication failed: " + errorMessage);
        } else {
            throw new DifyApiException(statusCode, errorCode, errorMessage);
        }
    }

    /**
     * 创建并配置 OkHttp 客户端。
     *
     * @param config Dify 配置
     * @return 已配置的 OkHttpClient 实例
     */
    private OkHttpClient createHttpClient(DifyRAGConfig config) {
        OkHttpClient.Builder builder =
                new OkHttpClient.Builder()
                        .connectTimeout(
                                config.getConnectTimeout().toMillis(), TimeUnit.MILLISECONDS)
                        .readTimeout(config.getReadTimeout().toMillis(), TimeUnit.MILLISECONDS)
                        .writeTimeout(config.getReadTimeout().toMillis(), TimeUnit.MILLISECONDS);

        // maxRetries 大于 0 时添加重试拦截器。
        if (config.getMaxRetries() > 0) {
            builder.addInterceptor(new RetryInterceptor(config.getMaxRetries()));
        }

        // 调试模式下添加日志拦截器。
        if (log.isDebugEnabled()) {
            builder.addInterceptor(new LoggingInterceptor());
        }

        return builder.build();
    }

    /**
     * 获取 Dify 配置。
     *
     * @return 配置
     */
    public DifyRAGConfig getConfig() {
        return config;
    }

    /**
     * 将字符串截断到指定长度并附加省略号。
     *
     * @param text 待截断文本
     * @param maxLength 最大长度
     * @return 截断后的文本
     */
    private String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength - 3) + "...";
    }

    /** OkHttp 重试拦截器。 */
    private static class RetryInterceptor implements Interceptor {
        private final int maxRetries;

        RetryInterceptor(int maxRetries) {
            this.maxRetries = maxRetries;
        }

        @Override
        public Response intercept(Chain chain) throws IOException {
            Request request = chain.request();
            Response response = null;
            IOException lastException = null;

            for (int attempt = 0; attempt <= maxRetries; attempt++) {
                try {
                    if (response != null) {
                        response.close();
                    }

                    response = chain.proceed(request);

                    // 对 5xx 或 429 限流错误重试。
                    if (response.isSuccessful()
                            || (response.code() >= 400
                                    && response.code() < 500
                                    && response.code() != 429)) {
                        return response;
                    }

                    if (attempt < maxRetries) {
                        log.debug(
                                "Request failed with status {}, retrying... (attempt {}/{})",
                                response.code(),
                                attempt + 1,
                                maxRetries);

                        // 指数退避。
                        try {
                            Thread.sleep((long) Math.pow(2, attempt) * 1000);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IOException("Retry interrupted", e);
                        }
                    }
                } catch (IOException e) {
                    lastException = e;
                    if (attempt < maxRetries) {
                        log.debug(
                                "Request failed with exception, retrying... (attempt {}/{})",
                                attempt + 1,
                                maxRetries,
                                e);

                        try {
                            Thread.sleep((long) Math.pow(2, attempt) * 1000);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new IOException("Retry interrupted", ie);
                        }
                    }
                }
            }

            if (response != null) {
                return response;
            }
            throw lastException != null ? lastException : new IOException("Max retries exceeded");
        }
    }

    /** OkHttp 日志拦截器，仅在调试模式使用。 */
    private static class LoggingInterceptor implements Interceptor {
        private static final Logger log = LoggerFactory.getLogger(LoggingInterceptor.class);

        @Override
        public Response intercept(Chain chain) throws IOException {
            Request request = chain.request();

            log.debug("HTTP Request: {} {}", request.method(), request.url());

            long startTime = System.currentTimeMillis();
            Response response = chain.proceed(request);
            long duration = System.currentTimeMillis() - startTime;

            log.debug("HTTP Response: {} {} ({}ms)", response.code(), response.message(), duration);

            return response;
        }
    }
}
