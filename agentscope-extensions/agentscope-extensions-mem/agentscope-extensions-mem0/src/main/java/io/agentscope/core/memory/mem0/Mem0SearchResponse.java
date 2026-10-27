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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Mem0 搜索记忆 API 的响应。
 *
 * <p>原接口示例为 {@code POST /v1/memories/search}；当前客户端平台模式实际使用 v2 搜索端点。
 * 响应包含按相关性排列的记忆结果，通常高分优先。
 *
 * <p>results 为空表示此次查询没有找到相关记忆。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class Mem0SearchResponse {

    /**
     * 按相关性分数降序排列的搜索结果列表。
     * 没有相关记忆时可能为空。
     */
    private List<Mem0SearchResult> results;

    /** 供 Jackson 使用的默认构造器。 */
    public Mem0SearchResponse() {}

    // 读取与设置方法。

    public List<Mem0SearchResult> getResults() {
        return results;
    }

    public void setResults(List<Mem0SearchResult> results) {
        this.results = results;
    }

    @Override
    public String toString() {
        return "Mem0SearchResponse{"
                + "results="
                + (results != null ? results.size() + " items" : "null")
                + '}';
    }
}
