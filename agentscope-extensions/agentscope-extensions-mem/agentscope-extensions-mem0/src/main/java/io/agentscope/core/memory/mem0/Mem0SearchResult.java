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
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Mem0 v2 API 的单条记忆搜索结果。
 *
 * <p>包含记忆文本、唯一标识、用户标识、元数据、分类、不可变标志、可选过期时间，以及创建和更新时间。
 * 结果通常按相关性排列。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class Mem0SearchResult {

    /** 记忆唯一标识，UUID 格式。 */
    private String id;

    /** 实际记忆文本内容。 */
    private String memory;

    /** 与此记忆关联的用户标识。 */
    @JsonProperty("user_id")
    private String userId;

    /** 关联的附加元数据。 */
    private Map<String, Object> metadata;

    /** 关联分类。 */
    private List<String> categories;

    /** 记忆是否不可变。 */
    private Boolean immutable;

    /** 记忆过期日期与时间，可选。 */
    @JsonProperty("expiration_date")
    private OffsetDateTime expirationDate;

    /** 记忆创建时间戳。 */
    @JsonProperty("created_at")
    private OffsetDateTime createdAt;

    /** 记忆最后更新时间戳。 */
    @JsonProperty("updated_at")
    private OffsetDateTime updatedAt;

    /** v2 搜索 API 返回的相关性分数。 */
    private Double score;

    /**
     * 从记忆提取的结构化时间属性，
     * 包含 day、month、year、hour、minute、day_of_week 等字段。
     */
    @JsonProperty("structured_attributes")
    private Map<String, Object> structuredAttributes;

    /** 供 Jackson 使用的默认构造器。 */
    public Mem0SearchResult() {}

    // 读取与设置方法。

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getMemory() {
        return memory;
    }

    public void setMemory(String memory) {
        this.memory = memory;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    public List<String> getCategories() {
        return categories;
    }

    public void setCategories(List<String> categories) {
        this.categories = categories;
    }

    public Boolean getImmutable() {
        return immutable;
    }

    public void setImmutable(Boolean immutable) {
        this.immutable = immutable;
    }

    public OffsetDateTime getExpirationDate() {
        return expirationDate;
    }

    public void setExpirationDate(OffsetDateTime expirationDate) {
        this.expirationDate = expirationDate;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    /**
     * 获取相关性分数。
     *
     * @return 相关性分数
     */
    public Double getScore() {
        return score;
    }

    /**
     * 设置相关性分数。
     *
     * @param score 相关性分数
     */
    public void setScore(Double score) {
        this.score = score;
    }

    /**
     * 获取结构化时间属性。
     *
     * @return 结构化属性映射
     */
    public Map<String, Object> getStructuredAttributes() {
        return structuredAttributes;
    }

    /**
     * 设置结构化时间属性。
     *
     * @param structuredAttributes 结构化属性映射
     */
    public void setStructuredAttributes(Map<String, Object> structuredAttributes) {
        this.structuredAttributes = structuredAttributes;
    }

    @Override
    public String toString() {
        return "Mem0SearchResult{"
                + "id='"
                + id
                + '\''
                + ", memory='"
                + memory
                + '\''
                + ", userId='"
                + userId
                + '\''
                + ", categories="
                + categories
                + ", immutable="
                + immutable
                + ", createdAt="
                + createdAt
                + ", updatedAt="
                + updatedAt
                + '}';
    }
}
