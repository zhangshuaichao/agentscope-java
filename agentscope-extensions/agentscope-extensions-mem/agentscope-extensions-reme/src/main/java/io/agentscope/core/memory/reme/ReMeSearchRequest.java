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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * ReMe API 的搜索记忆请求。
 *
 * <p>发送到 {@code POST /retrieve_personal_memory}，根据查询字符串检索相关记忆。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ReMeSearchRequest {

    /** 用于组织记忆的工作区标识。 */
    @JsonProperty("workspace_id")
    private String workspaceId;

    /** 搜索查询字符串。 */
    private String query;

    /** 最多返回结果数。 */
    @JsonProperty("top_k")
    private Integer topK;

    /** 供 Jackson 使用的默认构造器。 */
    public ReMeSearchRequest() {
        this.topK = 5; // 默认值。
    }

    /**
     * 使用指定工作区 ID、查询与 topK 创建 ReMeSearchRequest。
     *
     * @param workspaceId 工作区标识
     * @param query 搜索查询
     * @param topK 最多结果数
     */
    public ReMeSearchRequest(String workspaceId, String query, Integer topK) {
        this.workspaceId = workspaceId;
        this.query = query;
        this.topK = topK;
    }

    // 读取与设置方法。

    public String getWorkspaceId() {
        return workspaceId;
    }

    public void setWorkspaceId(String workspaceId) {
        this.workspaceId = workspaceId;
    }

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public Integer getTopK() {
        return topK;
    }

    public void setTopK(Integer topK) {
        this.topK = topK;
    }

    /**
     * 创建 ReMeSearchRequest 构建器。
     *
     * @return 新构建器实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /** ReMeSearchRequest 的构建器。 */
    public static class Builder {
        private String workspaceId;
        private String query;
        private Integer topK = 5;

        public Builder workspaceId(String workspaceId) {
            this.workspaceId = workspaceId;
            return this;
        }

        public Builder query(String query) {
            this.query = query;
            return this;
        }

        public Builder topK(Integer topK) {
            this.topK = topK;
            return this;
        }

        public ReMeSearchRequest build() {
            return new ReMeSearchRequest(workspaceId, query, topK);
        }
    }
}
