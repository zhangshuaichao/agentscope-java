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

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * 请求 Studio 网页用户输入的载荷。通过 POST /trpc/requestUserInput 发送以提示用户输入。
 */
public class RequestUserInputRequest {
    @JsonProperty("requestId")
    private final String requestId;

    @JsonProperty("runId")
    private final String runId;

    @JsonProperty("agentId")
    private final String agentId;

    @JsonProperty("agentName")
    private final String agentName;

    @JsonProperty("structuredInput")
    private final Object structuredInput;

    private RequestUserInputRequest(Builder builder) {
        this.requestId = builder.requestId;
        this.runId = builder.runId;
        this.agentId = builder.agentId;
        this.agentName = builder.agentName;
        this.structuredInput = builder.structuredInput;
    }

    /**
     * 获取用户输入请求的唯一标识。
     * @return 请求 ID
     */
    public String getRequestId() {
        return requestId;
    }

    /**
     * 获取输入请求关联的运行 ID。
     * @return 运行 ID
     */
    public String getRunId() {
        return runId;
    }

    /**
     * 获取请求输入的智能体 ID。
     * @return 智能体 ID
     */
    public String getAgentId() {
        return agentId;
    }

    /**
     * 获取请求输入的智能体名称。
     * @return 智能体名称
     */
    public String getAgentName() {
        return agentName;
    }

    /**
     * 获取可选结构化输入模式。
     * @return 结构化输入模式，未指定时为空映射
     */
    public Object getStructuredInput() {
        return structuredInput;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String requestId;
        private String runId;
        private String agentId;
        private String agentName;
        private Object structuredInput = Map.of();

        public Builder requestId(String requestId) {
            this.requestId = requestId;
            return this;
        }

        public Builder runId(String runId) {
            this.runId = runId;
            return this;
        }

        public Builder agentId(String agentId) {
            this.agentId = agentId;
            return this;
        }

        public Builder agentName(String agentName) {
            this.agentName = agentName;
            return this;
        }

        public Builder structuredInput(Object structuredInput) {
            this.structuredInput = structuredInput != null ? structuredInput : Map.of();
            return this;
        }

        public RequestUserInputRequest build() {
            return new RequestUserInputRequest(this);
        }
    }
}
