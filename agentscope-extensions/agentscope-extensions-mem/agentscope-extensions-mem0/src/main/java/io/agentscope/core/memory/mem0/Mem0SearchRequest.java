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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Mem0 v2 API 的搜索记忆请求。
 *
 * <p>平台模式发送到 {@code POST /v2/memories/search/}，按查询字符串的语义相似性查找相关记忆。
 *
 * <p>v2 使用 filters 对象指定 user_id、agent_id、run_id、app_id 等条件，区分多租户数据。
 * API 要求 filters 字段；未指定过滤条件时发送空对象。
 *
 * <p>【学习提示】空 filters 不代表自动限制到当前用户。直接使用此请求对象时，
 * 调用方必须明确所需数据作用域，不能把 API 密钥拥有的全部可见数据误当为当前会话授权范围。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Mem0SearchRequest {

    /** 用于语义相似性匹配的查询字符串。 */
    private String query;

    /** API 版本，默认 "v2"。 */
    private String version = "v2";

    /**
     * 搜索过滤条件，如 user_id、agent_id、run_id、app_id。
     * API 要求此字段，未指定时发送空映射。
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private Map<String, Object> filters = new HashMap<>();

    /** 最多返回结果数，默认 10。 */
    @JsonProperty("top_k")
    private Integer topK;

    /** 响应要包含的字段名称列表，可选。 */
    private List<String> fields;

    /** 是否重排序记忆，默认 false。 */
    private Boolean rerank;

    /** 是否使用关键词搜索，默认 false。 */
    @JsonProperty("keyword_search")
    private Boolean keywordSearch;

    /** 是否过滤记忆，默认 false。 */
    @JsonProperty("filter_memories")
    private Boolean filterMemories;

    /** 返回结果的最低相似度阈值，默认 0.3。 */
    private Double threshold;

    /** 组织 ID，可选。 */
    @JsonProperty("org_id")
    private String orgId;

    /** 项目 ID，可选。 */
    @JsonProperty("project_id")
    private String projectId;

    /** 用于过滤记忆的用户标识，可选。 */
    @JsonProperty("user_id")
    private String userId;

    /** 供 Jackson 使用的默认构造器。 */
    public Mem0SearchRequest() {
        this.topK = 10; // 按 v2 API 约定设置默认值。
        this.version = "v2";
        this.filters = new HashMap<>(); // 确保 filters 不为 null。
    }

    // 读取与设置方法。

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public Map<String, Object> getFilters() {
        return filters;
    }

    public void setFilters(Map<String, Object> filters) {
        this.filters = filters != null ? filters : new HashMap<>();
    }

    public Integer getTopK() {
        return topK;
    }

    public void setTopK(Integer topK) {
        this.topK = topK;
    }

    public List<String> getFields() {
        return fields;
    }

    public void setFields(List<String> fields) {
        this.fields = fields;
    }

    public Boolean getRerank() {
        return rerank;
    }

    public void setRerank(Boolean rerank) {
        this.rerank = rerank;
    }

    public Boolean getKeywordSearch() {
        return keywordSearch;
    }

    public void setKeywordSearch(Boolean keywordSearch) {
        this.keywordSearch = keywordSearch;
    }

    public Boolean getFilterMemories() {
        return filterMemories;
    }

    public void setFilterMemories(Boolean filterMemories) {
        this.filterMemories = filterMemories;
    }

    public Double getThreshold() {
        return threshold;
    }

    public void setThreshold(Double threshold) {
        this.threshold = threshold;
    }

    public String getOrgId() {
        return orgId;
    }

    public void setOrgId(String orgId) {
        this.orgId = orgId;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
        // 设置后自动同步到 filters。
        if (userId != null && filters != null) {
            filters.put("user_id", userId);
        }
    }

    /**
     * 创建 Mem0SearchRequest 构建器。
     *
     * @return 新构建器实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Mem0SearchRequest 的构建器。 */
    public static class Builder {
        private String query;
        private String version = "v2";
        private Map<String, Object> filters = new HashMap<>();
        private Integer topK = 10;
        private List<String> fields;
        private Boolean rerank;
        private Boolean keywordSearch;
        private Boolean filterMemories;
        private Double threshold;
        private String orgId;
        private String projectId;
        private String userId;

        public Builder query(String query) {
            this.query = query;
            return this;
        }

        public Builder version(String version) {
            this.version = version;
            return this;
        }

        public Builder filters(Map<String, Object> filters) {
            this.filters = filters;
            return this;
        }

        public Map<String, Object> getFilters() {
            return this.filters;
        }

        /**
         * 向 filters 添加 agent_id 的便捷方法。
         *
         * @param agentId 智能体标识
         * @return 当前构建器
         */
        public Builder agentId(String agentId) {
            if (agentId != null) {
                this.filters.put("agent_id", agentId);
            }
            return this;
        }

        /**
         * 设置用户标识。
         *
         * <p>同时设置 userId 字段并加入 filters，以兼容 v2 API。
         *
         * @param userId 用户标识
         * @return 当前构建器
         */
        public Builder userId(String userId) {
            this.userId = userId;
            if (userId != null) {
                this.filters.put("user_id", userId);
            }
            return this;
        }

        /**
         * 向 filters 添加 run_id 的便捷方法。
         *
         * @param runId 运行/会话标识
         * @return 当前构建器
         */
        public Builder runId(String runId) {
            if (runId != null) {
                this.filters.put("run_id", runId);
            }
            return this;
        }

        /**
         * 向 filters 添加 app_id 的便捷方法。
         *
         * @param appId 应用标识
         * @return 当前构建器
         */
        public Builder appId(String appId) {
            if (appId != null) {
                this.filters.put("app_id", appId);
            }
            return this;
        }

        public Builder topK(Integer topK) {
            this.topK = topK;
            return this;
        }

        /**
         * 向后兼容的便捷方法。
         *
         * @param limit 最多结果数，映射为 top_k
         * @return 当前构建器
         * @deprecated 请改用 {@link #topK(Integer)}
         */
        @Deprecated
        public Builder limit(Integer limit) {
            this.topK = limit;
            return this;
        }

        public Builder fields(List<String> fields) {
            this.fields = fields;
            return this;
        }

        public Builder rerank(Boolean rerank) {
            this.rerank = rerank;
            return this;
        }

        public Builder keywordSearch(Boolean keywordSearch) {
            this.keywordSearch = keywordSearch;
            return this;
        }

        public Builder filterMemories(Boolean filterMemories) {
            this.filterMemories = filterMemories;
            return this;
        }

        public Builder threshold(Double threshold) {
            this.threshold = threshold;
            return this;
        }

        public Builder orgId(String orgId) {
            this.orgId = orgId;
            return this;
        }

        public Builder projectId(String projectId) {
            this.projectId = projectId;
            return this;
        }

        public Mem0SearchRequest build() {
            Mem0SearchRequest request = new Mem0SearchRequest();
            request.setQuery(query);
            request.setVersion(version);
            request.setFilters(filters);
            request.setTopK(topK);
            request.setFields(fields);
            request.setRerank(rerank);
            request.setKeywordSearch(keywordSearch);
            request.setFilterMemories(filterMemories);
            request.setThreshold(threshold);
            request.setOrgId(orgId);
            request.setProjectId(projectId);
            // 在 filters 之后设置 userId，确保同步到过滤条件。
            if (userId != null) {
                request.setUserId(userId);
            }
            return request;
        }
    }
}
