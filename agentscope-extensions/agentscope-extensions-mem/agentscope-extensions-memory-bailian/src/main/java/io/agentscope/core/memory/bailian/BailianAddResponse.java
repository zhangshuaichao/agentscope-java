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
 * 百炼记忆 API 添加操作的响应。
 *
 * <p>由 POST /api/v2/apps/memory/add 在添加成功后返回，包含提取的记忆和请求 ID。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class BailianAddResponse {

    /** 用于追踪的请求 ID。 */
    @JsonProperty("request_id")
    private String requestId;

    /** 新建或更新的记忆节点列表。 */
    @JsonProperty("memory_nodes")
    private List<BailianMemoryNode> memoryNodes;

    /** 供 Jackson 使用的默认构造器。 */
    public BailianAddResponse() {}

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

    /** 响应中的记忆节点。 */
    public static class BailianMemoryNode {

        /** 记忆节点唯一标识。 */
        @JsonProperty("memory_node_id")
        private String memoryNodeId;

        /** 记忆内容。 */
        private String content;

        /** 事件类型：ADD、UPDATE、DELETE。 */
        private String event;

        /** 此前内容，仅用于 UPDATE 事件。 */
        @JsonProperty("old_content")
        private String oldContent;

        /** 供 Jackson 使用的默认构造器。 */
        public BailianMemoryNode() {}

        /**
         * 获取记忆节点 ID。
         *
         * @return 记忆节点 ID
         */
        public String getMemoryNodeId() {
            return memoryNodeId;
        }

        /**
         * 设置记忆节点 ID。
         *
         * @param memoryNodeId 记忆节点 ID
         */
        public void setMemoryNodeId(String memoryNodeId) {
            this.memoryNodeId = memoryNodeId;
        }

        /**
         * 获取内容。
         *
         * @return 内容
         */
        public String getContent() {
            return content;
        }

        /**
         * 设置内容。
         *
         * @param content 内容
         */
        public void setContent(String content) {
            this.content = content;
        }

        /**
         * 获取事件类型。
         *
         * @return 事件类型
         */
        public String getEvent() {
            return event;
        }

        /**
         * 设置事件类型。
         *
         * @param event 事件类型
         */
        public void setEvent(String event) {
            this.event = event;
        }

        /**
         * 获取旧内容。
         *
         * @return 旧内容
         */
        public String getOldContent() {
            return oldContent;
        }

        /**
         * 设置旧内容。
         *
         * @param oldContent 旧内容
         */
        public void setOldContent(String oldContent) {
            this.oldContent = oldContent;
        }
    }
}
