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
package io.agentscope.core.rag.integration.bailian;

import com.aliyun.bailian20231229.Client;
import com.aliyun.bailian20231229.models.RetrieveRequest;
import com.aliyun.bailian20231229.models.RetrieveResponse;
import com.aliyun.tea.utils.StringUtils;
import com.aliyun.teaopenapi.models.Config;
import com.aliyun.teautil.models.RuntimeOptions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * 与阿里云百炼知识库 API 交互的客户端。
 *
 * <p>包装百炼 SDK，为知识库操作提供响应式 API，
 * 处理客户端初始化、请求构建与错误处理。
 *
 * <p>使用示例：
 * <pre>{@code
 * BailianConfig config = BailianConfig.builder()
 *     .accessKeyId(...)
 *     .accessKeySecret(...)
 *     .workspaceId(...)
 *     .build();
 *
 * BailianClient client = new BailianClient(config);
 * RetrieveResponse response = client.retrieve("indexId", "query").block();
 * }</pre>
 */
public class BailianClient {

    private static final Logger log = LoggerFactory.getLogger(BailianClient.class);

    private final Client sdkClient;
    private final String workspaceId;
    private final BailianConfig config;

    /**
     * 创建新的 BailianClient 实例。
     *
     * @param config 百炼配置
     * @throws Exception 客户端初始化失败时抛出
     */
    public BailianClient(BailianConfig config) throws Exception {
        if (config == null) {
            throw new IllegalArgumentException("BailianConfig cannot be null");
        }

        this.config = config;
        this.workspaceId = config.getWorkspaceId();
        this.sdkClient = createSdkClient(config);

        log.info(
                "BailianClient initialized for workspace: {} at endpoint: {}",
                workspaceId,
                config.getEndpoint());
    }

    /**
     * 用于测试的包可见构造器。
     *
     * <p>允许注入模拟 SDK 客户端进行单元测试，无需真实阿里云凭据。
     *
     * @param sdkClient 百炼 SDK 客户端
     * @param workspaceId 工作空间 ID
     * @param config 百炼配置（测试时可以为 null）
     */
    BailianClient(Client sdkClient, String workspaceId, BailianConfig config) {
        if (sdkClient == null) {
            throw new IllegalArgumentException("SDK Client cannot be null");
        }
        if (workspaceId == null || workspaceId.trim().isEmpty()) {
            throw new IllegalArgumentException("WorkspaceId cannot be null or empty");
        }

        this.sdkClient = sdkClient;
        this.workspaceId = workspaceId;
        this.config = config;

        log.info("BailianClient initialized for testing with workspace: {}", workspaceId);
    }

    /**
     * 从知识库检索相关文档，并限制数量。
     *
     * <p>在指定知识库（索引）中搜索与查询相关的文档，结果按相关性分数排序，
     * 最多返回指定数量的文档。
     *
     * @param indexId 待搜索的知识库 ID
     * @param query 搜索查询文本
     * @param limit 最大检索文档数（null 使用默认值）
     * @return 发出检索响应的 Mono
     */
    public Mono<RetrieveResponse> retrieve(String indexId, String query, Integer limit) {
        return retrieve(indexId, query, limit, null);
    }

    /**
     * 携带对话历史从知识库检索相关文档。
     *
     * <p>在指定知识库（索引）中搜索与查询相关的文档。若提供对话历史，
     * 且配置中启用了查询改写，则根据对话上下文改写查询。
     *
     * <p>检索参数（denseSimilarityTopK、sparseSimilarityTopK、重排序等）
     * 从构造客户端时提供的 BailianConfig 读取。
     *
     * @param indexId 待搜索的知识库 ID
     * @param query 搜索查询文本
     * @param limit 最大检索文档数（null 使用默认值）
     * @param conversationHistory 用于多轮改写的对话历史（可以为 null）
     * @return 发出检索响应的 Mono
     */
    public Mono<RetrieveResponse> retrieve(
            String indexId,
            String query,
            Integer limit,
            List<QueryHistoryEntry> conversationHistory) {
        if (indexId == null || indexId.trim().isEmpty()) {
            return Mono.error(new IllegalArgumentException("IndexId cannot be null or empty"));
        }
        if (query == null || query.trim().isEmpty()) {
            return Mono.error(new IllegalArgumentException("Query cannot be null or empty"));
        }

        return Mono.fromCallable(
                () -> {
                    log.debug(
                            "Retrieving from index: {} with query: {} (limit: {}, historySize: {})",
                            indexId,
                            query,
                            limit,
                            conversationHistory != null ? conversationHistory.size() : 0);

                    // 根据配置中的全部参数构建检索请求
                    RetrieveRequest request = new RetrieveRequest();
                    request.setIndexId(indexId);
                    request.setQuery(query);

                    // 【学习提示】召回数量与最终返回数量不是同一层：显式稠密 top K 优先，
                    // 未设置时才回退到本次 limit；稀疏召回单独配置，最终结果还会在 Knowledge 层裁剪。
                    // 根据配置或 limit 参数设置稠密与稀疏相似度 top K
                    if (config.getDenseSimilarityTopK() != null) {
                        request.setDenseSimilarityTopK(config.getDenseSimilarityTopK());
                    } else if (limit != null && limit > 0) {
                        request.setDenseSimilarityTopK(limit);
                    }

                    if (config.getSparseSimilarityTopK() != null) {
                        request.setSparseSimilarityTopK(config.getSparseSimilarityTopK());
                    }

                    // 根据配置设置重排序
                    if (config.getEnableReranking() != null) {
                        request.setEnableReranking(config.getEnableReranking());
                    }
                    if (config.getRerankConfig() != null) {
                        RerankConfig rerankConfig = config.getRerankConfig();
                        List<RetrieveRequest.RetrieveRequestRerank> rerankList = new ArrayList<>();
                        RetrieveRequest.RetrieveRequestRerank rerank =
                                new RetrieveRequest.RetrieveRequestRerank();

                        if (!StringUtils.isEmpty(rerankConfig.getModelName())) {
                            rerank.setModelName(rerankConfig.getModelName());
                        }

                        if (rerankConfig.getRerankMinScore() != null) {
                            request.setRerankMinScore(rerankConfig.getRerankMinScore());
                        }

                        if (rerankConfig.getRerankTopN() != null) {
                            request.setRerankTopN(rerankConfig.getRerankTopN());
                        }

                        rerankList.add(rerank);
                        request.setRerank(rerankList);
                    }

                    // 根据配置设置多轮对话改写
                    if (config.getEnableRewrite() != null) {
                        request.setEnableRewrite(config.getEnableRewrite());
                    }
                    if (config.getRewriteConfig() != null) {
                        RewriteConfig rewriteConfig = config.getRewriteConfig();
                        List<RetrieveRequest.RetrieveRequestRewrite> rewriteList =
                                new ArrayList<>();
                        RetrieveRequest.RetrieveRequestRewrite rewrite =
                                new RetrieveRequest.RetrieveRequestRewrite();

                        if (!StringUtils.isEmpty(rewriteConfig.getModelName())) {
                            rewrite.setModelName(rewriteConfig.getModelName());
                        }

                        rewriteList.add(rewrite);
                        request.setRewrite(rewriteList);
                    }

                    // 设置用于多轮改写的查询历史（来自参数）
                    if (conversationHistory != null && !conversationHistory.isEmpty()) {
                        List<RetrieveRequest.RetrieveRequestQueryHistory> historyList =
                                new ArrayList<>();
                        for (QueryHistoryEntry entry : conversationHistory) {
                            RetrieveRequest.RetrieveRequestQueryHistory historyEntry =
                                    new RetrieveRequest.RetrieveRequestQueryHistory();
                            historyEntry.setRole(entry.getRole());
                            historyEntry.setContent(entry.getContent());
                            historyList.add(historyEntry);
                        }
                        request.setQueryHistory(historyList);
                    }

                    // 根据配置设置搜索过滤条件
                    if (config.getSearchFilters() != null && !config.getSearchFilters().isEmpty()) {
                        List<Map<String, String>> filters = config.getSearchFilters();
                        List<Map<String, String>> requestFilters = new ArrayList<>(filters);
                        request.setSearchFilters(requestFilters);
                    }

                    // 根据配置设置是否保存检索历史
                    if (config.getSaveRetrieverHistory() != null) {
                        request.setSaveRetrieverHistory(config.getSaveRetrieverHistory());
                    }

                    // 执行检索请求
                    RuntimeOptions runtime = new RuntimeOptions();
                    RetrieveResponse response =
                            sdkClient.retrieveWithOptions(
                                    workspaceId, request, new HashMap<>(), runtime);

                    if (response == null || response.getBody() == null) {
                        throw new RuntimeException("Bailian API returned null response");
                    }

                    if (response.getBody().getData() == null) {
                        log.warn(
                                "Bailian API returned no data for query: {}. requestId={},statu={},"
                                        + " code={}, message={}",
                                query,
                                response.getBody().getRequestId(),
                                response.getBody().getStatus(),
                                response.getBody().getCode(),
                                response.getBody().getMessage());
                    }

                    log.debug(
                            "Retrieved {} documents from index: {}",
                            response.getBody().getData() != null
                                            && response.getBody().getData().getNodes() != null
                                    ? response.getBody().getData().getNodes().size()
                                    : 0,
                            indexId);

                    return response;
                });
    }

    /**
     * 获取此客户端关联的工作空间 ID。
     *
     * @return 工作空间 ID
     */
    public String getWorkspaceId() {
        return workspaceId;
    }

    /**
     * 创建并配置百炼 SDK 客户端。
     *
     * @param config 百炼配置
     * @return 配置好的 Client 实例
     * @throws Exception 无效凭据（Access Key ID 或 Secret 无效）、网络连接问题、
     *     SDK 初始化错误或无效端点配置导致客户端创建失败时抛出
     */
    private Client createSdkClient(BailianConfig config) throws Exception {
        Config sdkConfig = new Config();
        sdkConfig.setAccessKeyId(config.getAccessKeyId());
        sdkConfig.setAccessKeySecret(config.getAccessKeySecret());
        sdkConfig.setEndpoint(config.getEndpoint());

        return new Client(sdkConfig);
    }
}
