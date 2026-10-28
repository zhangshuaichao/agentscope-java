/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.studio.pojo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * Studio 用户输入消息的元数据。
 * <p>为 AgentScope Studio 消息提供类型安全的元数据，包括来源、请求 ID 及可选结构化输入。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UserInputMetadata {
    @JsonProperty("source")
    private final String source;

    @JsonProperty("requestId")
    private final String requestId;

    @JsonProperty("structuredInput")
    private final Map<String, Object> structuredInput;

    private UserInputMetadata(Builder builder) {
        this.source = builder.source;
        this.requestId = builder.requestId;
        this.structuredInput = builder.structuredInput;
    }

    /**
     * 获取用户输入来源。
     * @return 来源标识，通常为 "studio"
     */
    public String getSource() {
        return source;
    }

    /**
     * 获取此输入回应的请求 ID。
     * @return 请求 ID，未回应特定请求时为 null
     */
    public String getRequestId() {
        return requestId;
    }

    /**
     * 获取可选结构化输入数据。
     * @return 结构化输入字段映射，未提供时为 null
     */
    public Map<String, Object> getStructuredInput() {
        return structuredInput;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String source = "studio";
        private String requestId;
        private Map<String, Object> structuredInput;

        public Builder source(String source) {
            this.source = source;
            return this;
        }

        public Builder requestId(String requestId) {
            this.requestId = requestId;
            return this;
        }

        public Builder structuredInput(Map<String, Object> structuredInput) {
            this.structuredInput = structuredInput;
            return this;
        }

        public UserInputMetadata build() {
            return new UserInputMetadata(this);
        }
    }
}
