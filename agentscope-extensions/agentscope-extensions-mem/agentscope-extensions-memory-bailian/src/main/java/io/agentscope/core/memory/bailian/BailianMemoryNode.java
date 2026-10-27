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

/** 响应中的记忆节点。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class BailianMemoryNode {

    /** 记忆节点唯一标识。 */
    @JsonProperty("memory_node_id")
    private String memoryNodeId;

    /** 记忆内容。 */
    @JsonProperty("content")
    private String content;

    /** 事件类型：ADD、UPDATE、DELETE。 */
    @JsonProperty("event")
    private String event;

    /** 此前内容，仅用于 UPDATE 事件。 */
    @JsonProperty("old_content")
    private String oldContent;

    /** 创建时间，Unix 时间戳。 */
    @JsonProperty("created_at")
    private Long createdAt;

    /** 更新时间，Unix 时间戳。 */
    @JsonProperty("updated_at")
    private Long updatedAt;

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

    /**
     * 获取创建时间戳。
     *
     * @return 创建时间戳
     */
    public Long getCreatedAt() {
        return createdAt;
    }

    /**
     * 设置创建时间戳。
     *
     * @param createdAt 创建时间戳
     */
    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }

    /**
     * 获取更新时间戳。
     *
     * @return 更新时间戳
     */
    public Long getUpdatedAt() {
        return updatedAt;
    }

    /**
     * 设置更新时间戳。
     *
     * @param updatedAt 更新时间戳
     */
    public void setUpdatedAt(Long updatedAt) {
        this.updatedAt = updatedAt;
    }
}
