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
package io.agentscope.core.memory.bailian;

import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportFactory;
import io.agentscope.core.util.JsonCodec;
import io.agentscope.core.util.JsonUtils;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 与百炼长期记忆 API 交互的 HTTP 客户端。
 *
 * <p>提供添加记忆和基于语义相似性搜索相关记忆的响应式接口。
 *
 * <p>用法示例：
 * <pre>{@code
 * // 使用构建器
 * BailianMemoryClient client = BailianMemoryClient.builder()
 *     .apiKey(System.getenv("DASHSCOPE_API_KEY"))
 *     .build();
 *
 * // 添加记忆
 * BailianAddRequest addRequest = BailianAddRequest.builder()
 *     .userId("user_001")
 *     .memoryLibraryId("memory_library_123")
 *     .projectId(project_id_123);
 *     .profileSchema(profile_schema_123);
 *     .messages(messages)
 *     .build();
 * client.add(addRequest).block();
 *
 * // 搜索记忆
 * BailianSearchRequest searchRequest = BailianSearchRequest.builder()
 *     .userId("user_001")
 *     .messages(searchMessages)
 *     .memoryLibraryId("memory_library_123")
 *     .projectIds(List.of("project_id_123"));
 *     .topK(5)
 *     .build();
 * BailianSearchResponse response = client.search(searchRequest).block();
 *
 * // 使用完毕后关闭客户端
 * client.close();
 * }</pre>
 */
public class BailianMemoryClient {

    private static final Logger log = LoggerFactory.getLogger(BailianMemoryClient.class);
    private static final String DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com";
    private static final String ADD_MEMORY_ENDPOINT = "/api/v2/apps/memory/add";
    private static final String SEARCH_MEMORY_ENDPOINT = "/api/v2/apps/memory/memory_nodes/search";

    private final String apiBaseUrl;
    private final String apiKey;
    private final HttpTransport httpTransport;
    private final JsonCodec jsonCodec;

    /**
     * 创建 {@link BailianMemoryClient} 实例。
     *
     * @param builder 用于配置客户端的构建器
     */
    private BailianMemoryClient(Builder builder) {
        if (builder.apiBaseUrl == null || builder.apiBaseUrl.isBlank()) {
            throw new IllegalArgumentException("apiBaseUrl cannot be null or blank");
        }
        if (builder.apiKey == null || builder.apiKey.isBlank()) {
            throw new IllegalArgumentException("apiKey cannot be null or blank");
        }
        if (builder.httpTransport == null) {
            throw new IllegalArgumentException("httpTransport cannot be null");
        }
        this.apiBaseUrl = builder.apiBaseUrl;
        this.apiKey = builder.apiKey;
        this.httpTransport = builder.httpTransport;
        this.jsonCodec = JsonUtils.getJsonCodec();
    }

    /**
     * 向百炼长期记忆添加记忆。
     *
     * <p>调用 POST /api/v2/apps/memory/add，将对话消息提交为记忆节点来源，百炼会从中提取关键信息。
     *
     * @param request 包含消息与元数据的添加请求
     * @return 发出添加响应的 Mono
     */
    public Mono<BailianAddResponse> add(BailianAddRequest request) {
        return executePost(ADD_MEMORY_ENDPOINT, request, BailianAddResponse.class, "add memory");
    }

    /**
     * 基于语义相似性搜索相关记忆。
     *
     * <p>调用 POST /api/v2/apps/memory/memory_nodes/search，查找与查询消息相关的记忆，结果按相关性排列。
     *
     * @param request 包含查询与过滤条件的搜索请求
     * @return 发出搜索响应的 Mono
     */
    public Mono<BailianSearchResponse> search(BailianSearchRequest request) {
        return executePost(
                SEARCH_MEMORY_ENDPOINT, request, BailianSearchResponse.class, "search memory");
    }

    /**
     * 向百炼 API 执行 POST 请求。
     *
     * <p>将请求序列化为 JSON，发到指定端点并解析响应。HTTP 操作在 boundedElastic 调度器执行。
     *
     * <p>【学习提示】这里把同步网络工作移到适合阻塞任务的线程池，并不把底层 HTTP 变成非阻塞。
     * 返回的 Mono 需被订阅才执行；调用 record 返回 Mono 也不等于已经写入远程记忆。
     *
     * @param endPoint API 端点，例如 "/api/v2/apps/memory/add"
     * @param request 待序列化的请求对象
     * @param responseType 响应解析类型
     * @param operationName 日志使用的操作名称
     * @param <T> 请求类型
     * @param <R> 响应类型
     * @return 发出解析结果的 Mono
     */
    private <T, R> Mono<R> executePost(
            String endPoint, T request, Class<R> responseType, String operationName) {
        return Mono.fromCallable(
                        () -> {
                            String json = jsonCodec.toJson(request);

                            HttpRequest httpRequest =
                                    HttpRequest.builder()
                                            .url(apiBaseUrl + endPoint)
                                            .method("POST")
                                            .header("Authorization", "Bearer " + apiKey)
                                            .header("Content-Type", "application/json")
                                            .body(json)
                                            .build();

                            log.debug(
                                    "Executing {} request to {}",
                                    operationName,
                                    httpRequest.getUrl());

                            HttpResponse response = httpTransport.execute(httpRequest);

                            if (!response.isSuccessful()) {
                                throw new IOException(
                                        "Bailian API "
                                                + operationName
                                                + " failed with status "
                                                + response.getStatusCode()
                                                + ": "
                                                + response.getBody());
                            }

                            String responseBody = response.getBody();
                            if (responseBody == null || responseBody.isEmpty()) {
                                throw new IOException(
                                        "Bailian API "
                                                + operationName
                                                + " returned empty response");
                            }

                            return jsonCodec.fromJson(responseBody, responseType);
                        })
                .onErrorMap(
                        th -> {
                            if (th instanceof IOException) {
                                return th;
                            }
                            return new IOException(
                                    "Bailian API " + operationName + " execute error", th);
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 关闭 HTTP 传输并释放资源。 */
    public void close() {
        if (httpTransport != null) {
            httpTransport.close();
        }
    }

    /**
     * 创建 {@link BailianMemoryClient} 构建器。
     * @return 新的 Builder 实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /** {@link BailianMemoryClient} 的构建器。 */
    public static class Builder {
        private String apiBaseUrl = DEFAULT_BASE_URL;
        private String apiKey;
        private HttpTransport httpTransport = HttpTransportFactory.getDefault();

        /**
         * 设置百炼 API 基础 URL。
         *
         * @param apiBaseUrl 百炼 API 基础 URL
         * @return 当前构建器
         */
        public Builder apiBaseUrl(String apiBaseUrl) {
            this.apiBaseUrl = apiBaseUrl;
            return this;
        }

        /**
         * 设置认证 API 密钥。
         *
         * @param apiKey 认证 API 密钥
         * @return 当前构建器
         */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /**
         * 设置 API 请求使用的 HTTP 传输。
         *
         * <p>未指定时使用 {@link HttpTransportFactory#getDefault()} 返回的默认传输。
         *
         * @param httpTransport HTTP 传输实现
         * @return 当前构建器
         */
        public Builder httpTransport(HttpTransport httpTransport) {
            this.httpTransport = httpTransport;
            return this;
        }

        /**
         * 构建 {@link BailianMemoryClient} 实例。
         *
         * @return 新的 {@link BailianMemoryClient} 实例
         */
        public BailianMemoryClient build() {
            return new BailianMemoryClient(this);
        }
    }
}
