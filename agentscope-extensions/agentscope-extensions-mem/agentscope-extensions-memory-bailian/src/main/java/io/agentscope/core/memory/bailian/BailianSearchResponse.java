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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 百炼记忆 API 搜索操作的响应。
 *
 * <p>由 POST /api/v2/apps/memory/memory_nodes/search 在语义搜索后返回，包含按相关性排列的记忆节点。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class BailianSearchResponse {

    /** 用于追踪的请求 ID。 */
    @JsonProperty("request_id")
    private String requestId;

    /** 按相关性排列的记忆节点列表。 */
    @JsonProperty("memory_nodes")
    private List<BailianMemoryNode> memoryNodes;

    /** 供 Jackson 使用的默认构造器。 */
    public BailianSearchResponse() {}

    /**
     * 获取请求 ID。
     *
     * @return 请求 ID
     */
    public String getRequestId() {
        return requestId;
    }

    /**
     * 设置请求 ID。
     *
     * @param requestId 请求 ID
     */
    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    /**
     * 获取记忆节点列表。
     *
     * @return 记忆节点列表
     */
    public List<BailianMemoryNode> getMemoryNodes() {
        return memoryNodes;
    }

    /**
     * 设置记忆节点列表。
     *
     * @param memoryNodes 记忆节点列表
     */
    public void setMemoryNodes(List<BailianMemoryNode> memoryNodes) {
        this.memoryNodes = memoryNodes;
    }
}
