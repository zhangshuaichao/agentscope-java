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
import java.util.Map;

/**
 * 百炼记忆 API 的添加请求。
 *
 * <p>发送到 POST /api/v2/apps/memory/add 以记录新记忆。
 * 百炼处理消息，通过 LLM 推断提取值得记忆的信息。
 *
 * <p>元数据用于组织和过滤记忆，支持多租户与多用户场景。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BailianAddRequest {

    /** 用于组织记忆的用户标识，必填。 */
    @JsonProperty("user_id")
    private String userId;

    /** 供记忆提取处理的对话消息列表。 */
    private List<BailianMessage> messages;

    /** 直接存储为记忆的自定义内容，可替代 messages。 */
    @JsonProperty("custom_content")
    private String customContent;

    /** 用于组织记忆的记忆库标识。 */
    @JsonProperty("memory_library_id")
    private String memoryLibraryId;

    /** 记忆规则的项目标识。 */
    @JsonProperty("project_id")
    private String projectId;

    /** 用户画像提取的模式标识。 */
    @JsonProperty("profile_schema")
    private String profileSchema;

    /** 保存记忆上下文的附加元数据。 */
    @JsonProperty("meta_data")
    private Map<String, Object> metadata;

    /** 供 Jackson 使用的默认构造器。 */
    public BailianAddRequest() {}

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
     * 获取自定义内容。
     *
     * @return 自定义内容
     */
    public String getCustomContent() {
        return customContent;
    }

    /**
     * 设置自定义内容。
     *
     * @param customContent 自定义内容
     */
    public void setCustomContent(String customContent) {
        this.customContent = customContent;
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
     * 获取项目 ID。
     *
     * @return 项目 ID
     */
    public String getProjectId() {
        return projectId;
    }

    /**
     * 设置项目 ID。
     *
     * @param projectId 项目 ID
     */
    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    /**
     * 获取画像模式。
     *
     * @return 画像模式
     */
    public String getProfileSchema() {
        return profileSchema;
    }

    /**
     * 设置画像模式。
     *
     * @param profileSchema 画像模式
     */
    public void setProfileSchema(String profileSchema) {
        this.profileSchema = profileSchema;
    }

    /**
     * 获取元数据映射。
     *
     * @return 元数据映射
     */
    public Map<String, Object> getMetadata() {
        return metadata;
    }

    /**
     * 设置元数据映射。
     *
     * @param metadata 元数据映射
     */
    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    /**
     * 创建 BailianAddRequest 构建器。
     *
     * @return 新的 Builder 实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /** BailianAddRequest 的构建器。 */
    public static class Builder {
        private String userId;
        private List<BailianMessage> messages;
        private String customContent;
        private String memoryLibraryId;
        private String projectId;
        private String profileSchema;
        private Map<String, Object> metadata;

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
         * 设置自定义内容。
         *
         * @param customContent 自定义内容
         * @return 当前构建器
         */
        public Builder customContent(String customContent) {
            this.customContent = customContent;
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
         * 设置项目 ID。
         *
         * @param projectId 项目 ID
         * @return 当前构建器
         */
        public Builder projectId(String projectId) {
            this.projectId = projectId;
            return this;
        }

        /**
         * 设置画像模式。
         *
         * @param profileSchema 画像模式
         * @return 当前构建器
         */
        public Builder profileSchema(String profileSchema) {
            this.profileSchema = profileSchema;
            return this;
        }

        /**
         * 设置元数据映射。
         *
         * @param metadata 元数据映射
         * @return 当前构建器
         */
        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }

        /**
         * 构建 BailianAddRequest 实例。
         *
         * @return 新的 BailianAddRequest 实例
         */
        public BailianAddRequest build() {
            BailianAddRequest request = new BailianAddRequest();
            request.setUserId(userId);
            request.setMessages(messages);
            request.setCustomContent(customContent);
            request.setMemoryLibraryId(memoryLibraryId);
            request.setProjectId(projectId);
            request.setProfileSchema(profileSchema);
            request.setMetadata(metadata);
            return request;
        }
    }
}
