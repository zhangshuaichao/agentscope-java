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
package io.agentscope.core.rag.integration.ragflow;

import io.agentscope.core.rag.integration.ragflow.exception.RAGFlowApiException;
import io.agentscope.core.rag.integration.ragflow.exception.RAGFlowAuthException;
import io.agentscope.core.rag.integration.ragflow.model.RAGFlowResponse;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * RAGFlow API 的 HTTP 客户端。
 *
 * <p>负责与 RAGFlow 服务的所有 HTTP 通信，包括：
 * <ul>
 *   <li>知识检索
 *   <li>认证
 *   <li>错误处理与重试逻辑
 * </ul>
 *
 * @author RAGFlow Integration Team
 */
public class RAGFlowClient {

    private static final Logger logger = LoggerFactory.getLogger(RAGFlowClient.class);

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient httpClient;

    private final RAGFlowConfig config;

    public RAGFlowClient(RAGFlowConfig config) {
        this.config = config;
        this.httpClient = createHttpClient();
    }

    RAGFlowClient(OkHttpClient httpClient, RAGFlowConfig config) {
        this.httpClient = httpClient;
        this.config = config;
    }

    /**
     * 从 RAGFlow 知识库检索文档。
     *
     * <p>调用 RAGFlow 检索 API 搜索相关文档分块。
     * API 端点为 {@code POST /api/v1/retrieval}。
     *
     * <p><b>实际 API 格式：</b>
     *
     * <pre>{@code
     * {
     *   "question": "What is advantage of ragflow?",
     *   "dataset_ids": ["b2a62730759d11ef987d0242ac120004"],
     *   "document_ids": ["77df9ef4759a11ef8bdd0242ac120004"],  // optional
     *   "top_k": 10,
     *   "similarity_threshold": 0.3,
     *   "metadata_condition": {
     *     "logic": "and",
     *     "conditions": [
     *       {"name": "author", "comparison_operator": "=", "value": "Toby"}
     *     ]
     *   }
     * }
     * }</pre>
     *
     * @param question 查询文本（必填）
     * @param topK 检索文档数（可选，默认使用配置值）
     * @param similarityThreshold 最低相似度阈值（可选，默认使用配置值）
     * @param metadataCondition 元数据过滤条件（可选）
     * @return 发出检索响应的 Mono
     */
    public Mono<RAGFlowResponse> retrieve(
            String question,
            Integer topK,
            Double similarityThreshold,
            Map<String, Object> metadataCondition) {

        return Mono.fromCallable(
                () -> {
                    if (question == null || question.trim().isEmpty()) {
                        throw new IllegalArgumentException("Question cannot be null or empty");
                    }

                    Map<String, Object> requestBody = new HashMap<>();

                    // 必填：问题文本
                    requestBody.put("question", question);

                    // 必填：dataset_ids（数组）
                    requestBody.put("dataset_ids", config.getDatasetIds());

                    // 可选：document_ids（限定指定文档）
                    if (config.getDocumentIds() != null && !config.getDocumentIds().isEmpty()) {
                        requestBody.put("document_ids", config.getDocumentIds());
                    }

                    // 可选：top_k（默认 1024）
                    if (topK != null) {
                        requestBody.put("top_k", topK);
                    } else if (config.getTopK() != null) {
                        requestBody.put("top_k", config.getTopK());
                    }

                    // 可选：similarity_threshold（默认 0.2）
                    Double threshold =
                            similarityThreshold != null
                                    ? similarityThreshold
                                    : config.getSimilarityThreshold();
                    if (threshold != null) {
                        requestBody.put("similarity_threshold", threshold);
                    }

                    // 可选：vector_similarity_weight（默认 0.3）
                    if (config.getVectorSimilarityWeight() != null) {
                        requestBody.put(
                                "vector_similarity_weight", config.getVectorSimilarityWeight());
                    }

                    // 可选：page（默认 1）
                    if (config.getPage() != null) {
                        requestBody.put("page", config.getPage());
                    }

                    // 可选：page_size（默认 30）
                    if (config.getPageSize() != null) {
                        requestBody.put("page_size", config.getPageSize());
                    }

                    // 可选：use_kg，多跳查询的知识图谱（默认 false）
                    if (config.getUseKg() != null) {
                        requestBody.put("use_kg", config.getUseKg());
                    }

                    // 可选：toc_enhance，目录增强（默认 false）
                    if (config.getTocEnhance() != null) {
                        requestBody.put("toc_enhance", config.getTocEnhance());
                    }

                    // 可选：rerank_id，重排序模型 ID
                    if (config.getRerankId() != null) {
                        requestBody.put("rerank_id", config.getRerankId());
                    }

                    // 可选：keyword，基于关键词的匹配（默认 false）
                    if (config.getKeyword() != null) {
                        requestBody.put("keyword", config.getKeyword());
                    }

                    // 可选：highlight，高亮匹配词（默认 false）
                    if (config.getHighlight() != null) {
                        requestBody.put("highlight", config.getHighlight());
                    }

                    // 可选：cross_languages，跨语言检索
                    if (config.getCrossLanguages() != null
                            && !config.getCrossLanguages().isEmpty()) {
                        requestBody.put("cross_languages", config.getCrossLanguages());
                    }

                    // 可选：metadata_condition，用于过滤
                    if (metadataCondition != null && !metadataCondition.isEmpty()) {
                        requestBody.put("metadata_condition", metadataCondition);
                    } else if (config.getMetadataCondition() != null
                            && !config.getMetadataCondition().isEmpty()) {
                        requestBody.put("metadata_condition", config.getMetadataCondition());
                    }

                    String jsonBody = JsonUtils.getJsonCodec().toJson(requestBody);

                    String url = config.getBaseUrl() + "/api/v1/retrieval";

                    logger.debug("RAGFlow retrieval request: URL={}, body={}", url, jsonBody);

                    Request request =
                            new Request.Builder()
                                    .url(url)
                                    .post(RequestBody.create(jsonBody, JSON))
                                    .addHeader("Authorization", "Bearer " + config.getApiKey())
                                    .addHeader("Content-Type", "application/json")
                                    .build();

                    // 添加自定义请求头
                    if (config.getCustomHeaders() != null) {
                        Request.Builder builder = request.newBuilder();
                        config.getCustomHeaders()
                                .forEach((key, value) -> builder.addHeader(key, value));
                        request = builder.build();
                    }

                    try (Response response = httpClient.newCall(request).execute()) {
                        String responseBody =
                                response.body() != null ? response.body().string() : "";

                        logger.debug(
                                "RAGFlow API response: status={}, body={}",
                                response.code(),
                                responseBody);

                        if (!response.isSuccessful()) {
                            handleErrorResponse(response.code(), responseBody);
                        }

                        RAGFlowResponse ragFlowResponse =
                                JsonUtils.getJsonCodec()
                                        .fromJson(responseBody, RAGFlowResponse.class);

                        // 检查响应是否表示错误
                        if (ragFlowResponse.getCode() != null && ragFlowResponse.getCode() != 0) {
                            throw new RAGFlowApiException(
                                    "RAGFlow API error: " + ragFlowResponse.getMessage(),
                                    ragFlowResponse.getCode());
                        }

                        // 记录成功响应详情
                        if (logger.isDebugEnabled()
                                && ragFlowResponse.getData() != null
                                && ragFlowResponse.getData().getChunks() != null) {
                            int chunkCount = ragFlowResponse.getData().getChunks().size();
                            Integer total = ragFlowResponse.getData().getTotal();
                            logger.debug(
                                    "RAGFlow retrieval successful: retrieved {} chunks, total={}",
                                    chunkCount,
                                    total);

                            // 存在文档聚合信息时记录
                            if (ragFlowResponse.getData().getDocAggs() != null) {
                                logger.debug(
                                        "Document aggregations: {}",
                                        ragFlowResponse.getData().getDocAggs());
                            }
                        }

                        return ragFlowResponse;
                    }
                });
    }

    private void handleErrorResponse(int statusCode, String responseBody) {
        logger.error("RAGFlow API error: status={}, body={}", statusCode, responseBody);

        if (statusCode == 401 || statusCode == 403) {
            throw new RAGFlowAuthException("Authentication failed: " + responseBody, statusCode);
        } else if (statusCode == 404) {
            throw new RAGFlowApiException("Dataset not found: " + responseBody, statusCode);
        } else if (statusCode == 429) {
            throw new RAGFlowApiException("Rate limit exceeded: " + responseBody, statusCode);
        } else if (statusCode >= 500) {
            throw new RAGFlowApiException(
                    "RAGFlow server error (HTTP " + statusCode + "): " + responseBody, statusCode);
        } else {
            throw new RAGFlowApiException(
                    "RAGFlow API error (HTTP " + statusCode + "): " + responseBody, statusCode);
        }
    }

    private OkHttpClient createHttpClient() {
        OkHttpClient.Builder builder =
                new OkHttpClient.Builder()
                        .connectTimeout(config.getTimeout().toMillis(), TimeUnit.MILLISECONDS)
                        .readTimeout(config.getTimeout().toMillis(), TimeUnit.MILLISECONDS)
                        .writeTimeout(config.getTimeout().toMillis(), TimeUnit.MILLISECONDS);

        // 最大重试次数 > 0 时添加重试拦截器
        if (config.getMaxRetries() != null && config.getMaxRetries() > 0) {
            builder.addInterceptor(new RetryInterceptor(config.getMaxRetries()));
        }

        // 添加日志拦截器
        if (logger.isDebugEnabled()) {
            builder.addInterceptor(new LoggingInterceptor());
        }

        return builder.build();
    }

    /**
     * 重试失败请求的拦截器。
     */
    private static class RetryInterceptor implements Interceptor {

        private final int maxRetries;

        RetryInterceptor(int maxRetries) {
            this.maxRetries = maxRetries;
        }

        @Override
        public Response intercept(Chain chain) throws IOException {
            Request request = chain.request();
            Response response = null;
            boolean responseReturned = false;

            try {
                for (int attempt = 0; attempt <= maxRetries; attempt++) {
                    try {
                        // 【学习提示】重试前关闭上一响应，释放连接资源；最终交给调用方的响应
                        // 必须保持可读。资源所有权与“是否还会重试”应一起分析。
                        if (response != null) {
                            response.close();
                            response = null;
                        }
                        response = chain.proceed(request);

                        // 成功响应或客户端错误（4xx）不重试
                        if (response.isSuccessful() || response.code() < 500) {
                            responseReturned = true;
                            return response;
                        }

                        logger.warn(
                                "RAGFlow request failed with status {}, attempt {}/{}",
                                response.code(),
                                attempt + 1,
                                maxRetries + 1);

                    } catch (IOException e) {
                        logger.warn(
                                "RAGFlow request failed with exception, attempt {}/{}: {}",
                                attempt + 1,
                                maxRetries + 1,
                                e.getMessage());

                        if (attempt == maxRetries) {
                            throw e;
                        }
                    }

                    // 重试前等待（指数退避）
                    if (attempt < maxRetries) {
                        try {
                            long waitTime = (long) Math.pow(2, attempt) * 1000;
                            Thread.sleep(Math.min(waitTime, 10000)); // 最多 10 秒
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IOException("Retry interrupted", e);
                        }
                    }
                }

                responseReturned = true;
                return response;
            } finally {
                // 保持响应打开，供调用方消费，包括最终错误
                // 响应。
                if (response != null && !responseReturned) {
                    response.close();
                }
            }
        }
    }

    /**
     * 记录 HTTP 请求与响应的拦截器。
     */
    private static class LoggingInterceptor implements Interceptor {

        @Override
        public Response intercept(Chain chain) throws IOException {
            Request request = chain.request();

            logger.debug("RAGFlow HTTP Request: {} {}", request.method(), request.url());

            long startTime = System.currentTimeMillis();
            Response response = chain.proceed(request);
            long duration = System.currentTimeMillis() - startTime;

            logger.debug("RAGFlow HTTP Response: {} in {}ms", response.code(), duration);

            return response;
        }
    }
}
