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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 百炼记忆 API 的搜索请求。
 *
 * <p>发送到 POST /api/v2/apps/memory/memory_nodes/search，按与查询消息的语义相似性检索相关记忆。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BailianSearchRequest {

    /** 用于过滤记忆的用户标识，必填。 */
    @JsonProperty("user_id")
    private String userId;

    /** 语义相似性搜索使用的查询消息列表。 */
    private List<BailianMessage> messages;

    /** 用于过滤的记忆库标识。 */
    @JsonProperty("memory_library_id")
    private String memoryLibraryId;

    /**
     * 记忆规则的项目标识列表。
     * 可传入多个记忆片段规则标识进行混合检索。
     */
    @JsonProperty("project_ids")
    private List<String> projectIds;

    /** 最多返回结果数，默认 10。 */
    @JsonProperty("top_k")
    private Integer topK;

    /** 最低相似度分数阈值，范围 [0, 1]，默认 0.3。 */
    @JsonProperty("min_score")
    private Double minScore;

    /** 是否启用搜索结果重排序，默认 false。 */
    @JsonProperty("enable_rerank")
    private Boolean enableRerank;

    /** 是否启用意图判别回调，默认 false。 */
    @JsonProperty("enable_judge")
    private Boolean enableJudge;

    /** 是否启用查询改写，默认 false。 */
    @JsonProperty("enable_rewrite")
    private Boolean enableRewrite;

    /** 供 Jackson 使用的默认构造器。 */
    public BailianSearchRequest() {}

    /**
     * 获取用户 ID。
     *
     * @return 用户 ID
     */
    public String getUserId() {
        return userId;
    }

    /**
     * 设置用户 ID。
     *
     * @param userId 用户 ID
     */
    public void setUserId(String userId) {
        this.userId = userId;
    }

    /**
     * 获取消息列表。
     *
     * @return 消息列表
     */
    public List<BailianMessage> getMessages() {
        return messages;
    }

    /**
     * 设置消息列表。
     *
     * @param messages 消息列表
     */
    public void setMessages(List<BailianMessage> messages) {
        this.messages = messages;
    }

    /**
     * 获取记忆库 ID。
     *
     * @return 记忆库 ID
     */
    public String getMemoryLibraryId() {
        return memoryLibraryId;
    }

    /**
     * 设置记忆库 ID。
     *
     * @param memoryLibraryId 记忆库 ID
     */
    public void setMemoryLibraryId(String memoryLibraryId) {
        this.memoryLibraryId = memoryLibraryId;
    }

    /**
     * 获取项目 ID 列表。
     *
     * @return 项目 ID 列表
     */
    public List<String> getProjectIds() {
        return projectIds;
    }

    /**
     * 设置项目 ID 列表。
     *
     * @param projectIds 项目 ID 列表
     */
    public void setProjectIds(List<String> projectIds) {
        this.projectIds = projectIds;
    }

    /**
     * 获取top K 值。
     *
     * @return top K 值
     */
    public Integer getTopK() {
        return topK;
    }

    /**
     * 设置top K 值。
     *
     * @param topK top K 值
     */
    public void setTopK(Integer topK) {
        this.topK = topK;
    }

    /**
     * 获取最低分数阈值。
     *
     * @return 最低分数阈值
     */
    public Double getMinScore() {
        return minScore;
    }

    /**
     * 设置最低分数阈值。
     *
     * @param minScore 最低分数阈值
     */
    public void setMinScore(Double minScore) {
        this.minScore = minScore;
    }

    /**
     * 获取重排序开关。
     *
     * @return 重排序开关
     */
    public Boolean getEnableRerank() {
        return enableRerank;
    }

    /**
     * 设置重排序开关。
     *
     * @param enableRerank 重排序开关
     */
    public void setEnableRerank(Boolean enableRerank) {
        this.enableRerank = enableRerank;
    }

    /**
     * 获取意图判别开关。
     *
     * @return 意图判别开关
     */
    public Boolean getEnableJudge() {
        return enableJudge;
    }

    /**
     * 设置意图判别开关。
     *
     * @param enableJudge 意图判别开关
     */
    public void setEnableJudge(Boolean enableJudge) {
        this.enableJudge = enableJudge;
    }

    /**
     * 获取查询改写开关。
     *
     * @return 查询改写开关
     */
    public Boolean getEnableRewrite() {
        return enableRewrite;
    }

    /**
     * 设置查询改写开关。
     *
     * @param enableRewrite 查询改写开关
     */
    public void setEnableRewrite(Boolean enableRewrite) {
        this.enableRewrite = enableRewrite;
    }

    /**
     * 创建 BailianSearchRequest 构建器。
     *
     * @return 新的 Builder 实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /** BailianSearchRequest 的构建器。 */
    public static class Builder {
        private String userId;
        private List<BailianMessage> messages;
        private String memoryLibraryId;
        private List<String> projectIds;
        private Integer topK;
        private Double minScore;
        private Boolean enableRerank;
        private Boolean enableJudge;
        private Boolean enableRewrite;

        /**
         * 设置用户 ID。
         *
         * @param userId 用户 ID
         * @return 当前构建器
         */
        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        /**
         * 设置消息列表。
         *
         * @param messages 消息列表
         * @return 当前构建器
         */
        public Builder messages(List<BailianMessage> messages) {
            this.messages = messages;
            return this;
        }

        /**
         * 设置记忆库 ID。
         *
         * @param memoryLibraryId 记忆库 ID
         * @return 当前构建器
         */
        public Builder memoryLibraryId(String memoryLibraryId) {
            this.memoryLibraryId = memoryLibraryId;
            return this;
        }

        /**
         * 设置项目 ID 列表。
         *
         * @param projectIds 项目 ID 列表
         * @return 当前构建器
         */
        public Builder projectIds(List<String> projectIds) {
            this.projectIds = projectIds;
            return this;
        }

        /**
         * 设置top K 值。
         *
         * @param topK top K 值
         * @return 当前构建器
         */
        public Builder topK(Integer topK) {
            this.topK = topK;
            return this;
        }

        /**
         * 设置最低分数阈值。
         *
         * @param minScore 最低分数阈值
         * @return 当前构建器
         */
        public Builder minScore(Double minScore) {
            this.minScore = minScore;
            return this;
        }

        /**
         * 设置重排序开关。
         *
         * @param enableRerank 重排序开关
         * @return 当前构建器
         */
        public Builder enableRerank(Boolean enableRerank) {
            this.enableRerank = enableRerank;
            return this;
        }

        /**
         * 设置意图判别开关。
         *
         * @param enableJudge 意图判别开关
         * @return 当前构建器
         */
        public Builder enableJudge(Boolean enableJudge) {
            this.enableJudge = enableJudge;
            return this;
        }

        /**
         * 设置查询改写开关。
         *
         * @param enableRewrite 查询改写开关
         * @return 当前构建器
         */
        public Builder enableRewrite(Boolean enableRewrite) {
            this.enableRewrite = enableRewrite;
            return this;
        }

        /**
         * 构建 BailianSearchRequest 实例。
         *
         * @return 新的 BailianSearchRequest 实例
         */
        public BailianSearchRequest build() {
            BailianSearchRequest request = new BailianSearchRequest();
            request.setUserId(userId);
            request.setMessages(messages);
            request.setMemoryLibraryId(memoryLibraryId);
            request.setProjectIds(projectIds);
            request.setTopK(topK);
            request.setMinScore(minScore);
            request.setEnableRerank(enableRerank);
            request.setEnableJudge(enableJudge);
            request.setEnableRewrite(enableRewrite);
            return request;
        }
    }
}
