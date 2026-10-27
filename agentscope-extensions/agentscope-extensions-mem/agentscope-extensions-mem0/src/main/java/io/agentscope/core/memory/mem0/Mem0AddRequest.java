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
import java.util.List;
import java.util.Map;

/**
 * Mem0 API 的添加记忆请求。
 *
 * <p>平台模式发送到 {@code POST /v1/memories/} 记录新记忆。
 * Mem0 使用 LLM 推断提取值得记忆的信息，除非 {@code infer} 为 false。
 *
 * <p>agentId、userId、runId、appId 等字段用于组织和过滤记忆，支持多租户和多智能体。
 *
 * <p>此请求支持 v2，将 {@code version} 设置为 "v2" 可选择对应 API 行为。
 *
 * <p>【学习提示】async_mode 默认为 true，客户端收到添加响应不必然表示后台提取完成或立即可搜索。
 * 不要把请求已接收、提取完成和检索可见三个阶段混为一谈。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Mem0AddRequest {

    /** 供记忆提取处理的消息列表。 */
    private List<Mem0Message> messages;

    /** 用于组织记忆的智能体标识。 */
    @JsonProperty("agent_id")
    private String agentId;

    /** 用于组织记忆的用户标识。 */
    @JsonProperty("user_id")
    private String userId;

    /** 用于组织记忆的应用标识。 */
    @JsonProperty("app_id")
    private String appId;

    /** 用于组织记忆的运行/会话标识。 */
    @JsonProperty("run_id")
    private String runId;

    /** 保存记忆上下文的附加元数据。 */
    private Map<String, Object> metadata;

    /** 指定记忆中应包含的偏好。 */
    private String includes;

    /** 指定记忆中应排除的偏好。 */
    private String excludes;

    /** 是否用 LLM 推断提取记忆，默认 true。 */
    private Boolean infer;

    /**
     * 输出格式版本，v1.0 或 v1.1，默认 v1.1。
     * v1.0 已弃用，直接返回数组；v1.1 返回带 results 键的对象。
     */
    @JsonProperty("output_format")
    private String outputFormat;

    /** 包含类别名称与描述的自定义分类。 */
    @JsonProperty("custom_categories")
    private Map<String, Object> customCategories;

    /** 处理和组织记忆的自定义指令。 */
    @JsonProperty("custom_instructions")
    private String customInstructions;

    /** 记忆是否不可变，默认 false。 */
    private Boolean immutable;

    /** 是否以完全异步方式添加记忆，默认 true。 */
    @JsonProperty("async_mode")
    private Boolean asyncMode;

    /** 记忆的 Unix 时间戳。 */
    private Long timestamp;

    /** 过期日期，格式 YYYY-MM-DD。 */
    @JsonProperty("expiration_date")
    private String expirationDate;

    /** 组织 ID。 */
    @JsonProperty("org_id")
    private String orgId;

    /** 项目 ID。 */
    @JsonProperty("project_id")
    private String projectId;

    /** API 版本，新应用通常使用 "v2"。 */
    private String version;

    /**
     * 记忆类型，例如 "semantic"、"procedural"。
     *
     * @deprecated 不属于所依据的官方 API 规范，仅为向后兼容保留
     */
    @Deprecated
    @JsonProperty("memory_type")
    private String memoryType;

    /** 供 Jackson 使用的默认构造器。 */
    public Mem0AddRequest() {
        this.infer = true;
        this.outputFormat = "v1.1";
        this.asyncMode = true;
        this.immutable = false;
    }

    // 读取与设置方法。

    public List<Mem0Message> getMessages() {
        return messages;
    }

    public void setMessages(List<Mem0Message> messages) {
        this.messages = messages;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getAppId() {
        return appId;
    }

    public void setAppId(String appId) {
        this.appId = appId;
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    public String getIncludes() {
        return includes;
    }

    public void setIncludes(String includes) {
        this.includes = includes;
    }

    public String getExcludes() {
        return excludes;
    }

    public void setExcludes(String excludes) {
        this.excludes = excludes;
    }

    public Boolean getInfer() {
        return infer;
    }

    public void setInfer(Boolean infer) {
        this.infer = infer;
    }

    public String getOutputFormat() {
        return outputFormat;
    }

    public void setOutputFormat(String outputFormat) {
        this.outputFormat = outputFormat;
    }

    public Map<String, Object> getCustomCategories() {
        return customCategories;
    }

    public void setCustomCategories(Map<String, Object> customCategories) {
        this.customCategories = customCategories;
    }

    public String getCustomInstructions() {
        return customInstructions;
    }

    public void setCustomInstructions(String customInstructions) {
        this.customInstructions = customInstructions;
    }

    public Boolean getImmutable() {
        return immutable;
    }

    public void setImmutable(Boolean immutable) {
        this.immutable = immutable;
    }

    public Boolean getAsyncMode() {
        return asyncMode;
    }

    public void setAsyncMode(Boolean asyncMode) {
        this.asyncMode = asyncMode;
    }

    public Long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Long timestamp) {
        this.timestamp = timestamp;
    }

    public String getExpirationDate() {
        return expirationDate;
    }

    public void setExpirationDate(String expirationDate) {
        this.expirationDate = expirationDate;
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

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    /**
     * 获取记忆类型。
     *
     * @return 记忆类型
     * @deprecated 不属于所依据的官方 API 规范
     */
    @Deprecated
    public String getMemoryType() {
        return memoryType;
    }

    /**
     * 设置记忆类型。
     *
     * @param memoryType 记忆类型
     * @deprecated 不属于所依据的官方 API 规范
     */
    @Deprecated
    public void setMemoryType(String memoryType) {
        this.memoryType = memoryType;
    }

    /**
     * 创建 Mem0AddRequest 构建器。
     *
     * @return 新构建器实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Mem0AddRequest 的构建器。 */
    public static class Builder {
        private List<Mem0Message> messages;
        private String agentId;
        private String userId;
        private String appId;
        private String runId;
        private Map<String, Object> metadata;
        private String includes;
        private String excludes;
        private Boolean infer = true;
        private String outputFormat = "v1.1";
        private Map<String, Object> customCategories;
        private String customInstructions;
        private Boolean immutable = false;
        private Boolean asyncMode = true;
        private Long timestamp;
        private String expirationDate;
        private String orgId;
        private String projectId;
        private String version;
        private String memoryType;

        public Builder messages(List<Mem0Message> messages) {
            this.messages = messages;
            return this;
        }

        public Builder agentId(String agentId) {
            this.agentId = agentId;
            return this;
        }

        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public Builder appId(String appId) {
            this.appId = appId;
            return this;
        }

        public Builder runId(String runId) {
            this.runId = runId;
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }

        public Builder includes(String includes) {
            this.includes = includes;
            return this;
        }

        public Builder excludes(String excludes) {
            this.excludes = excludes;
            return this;
        }

        public Builder infer(Boolean infer) {
            this.infer = infer;
            return this;
        }

        public Builder outputFormat(String outputFormat) {
            this.outputFormat = outputFormat;
            return this;
        }

        public Builder customCategories(Map<String, Object> customCategories) {
            this.customCategories = customCategories;
            return this;
        }

        public Builder customInstructions(String customInstructions) {
            this.customInstructions = customInstructions;
            return this;
        }

        public Builder immutable(Boolean immutable) {
            this.immutable = immutable;
            return this;
        }

        public Builder asyncMode(Boolean asyncMode) {
            this.asyncMode = asyncMode;
            return this;
        }

        public Builder timestamp(Long timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder expirationDate(String expirationDate) {
            this.expirationDate = expirationDate;
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

        public Builder version(String version) {
            this.version = version;
            return this;
        }

        /**
         * 设置记忆类型。
         *
         * @param memoryType 记忆类型
         * @return 当前构建器
         * @deprecated 不属于所依据的官方 API 规范
         */
        @Deprecated
        public Builder memoryType(String memoryType) {
            this.memoryType = memoryType;
            return this;
        }

        public Mem0AddRequest build() {
            Mem0AddRequest request = new Mem0AddRequest();
            request.setMessages(messages);
            request.setAgentId(agentId);
            request.setUserId(userId);
            request.setAppId(appId);
            request.setRunId(runId);
            request.setMetadata(metadata);
            request.setIncludes(includes);
            request.setExcludes(excludes);
            request.setInfer(infer);
            request.setOutputFormat(outputFormat);
            request.setCustomCategories(customCategories);
            request.setCustomInstructions(customInstructions);
            request.setImmutable(immutable);
            request.setAsyncMode(asyncMode);
            request.setTimestamp(timestamp);
            request.setExpirationDate(expirationDate);
            request.setOrgId(orgId);
            request.setProjectId(projectId);
            request.setVersion(version);
            request.setMemoryType(memoryType);
            return request;
        }
    }
}
