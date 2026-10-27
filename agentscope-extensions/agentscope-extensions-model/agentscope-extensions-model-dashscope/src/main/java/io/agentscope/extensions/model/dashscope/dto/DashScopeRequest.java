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
package io.agentscope.extensions.model.dashscope.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.agentscope.extensions.model.dashscope.EndpointType;

/**
 * DashScope API 请求 DTO。
 *
 * <p>表示文本生成及多模态生成 API 的顶层请求结构。
 *
 * <p>JSON 示例：
 * <pre>{@code
 * {
 *   "model": "qwen-plus",
 *   "input": {
 *     "messages": [...]
 *   },
 *   "parameters": {
 *     "result_format": "message",
 *     "temperature": 0.7
 *   }
 * }
 * }</pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DashScopeRequest {

    /**
     * 模型名称（例如 "qwen-plus"、"qwen-vl-max"）。
     */
    @JsonProperty("model")
    private String model;

    /**
     * 包含消息的输入。
     */
    @JsonProperty("input")
    private DashScopeInput input;

    /**
     * 生成参数。
     */
    @JsonProperty("parameters")
    private DashScopeParameters parameters;

    /**
     * 用于端点选择的端点类型（不序列化为 JSON）。
     *
     * <p>此内部字段决定使用哪个 DashScope API 端点，不发送给 API。
     */
    @JsonIgnore private EndpointType endpointType;

    public DashScopeRequest() {
        this.endpointType = EndpointType.AUTO;
    }

    public DashScopeRequest(String model, DashScopeInput input, DashScopeParameters parameters) {
        this.model = model;
        this.input = input;
        this.parameters = parameters;
        this.endpointType = EndpointType.AUTO;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public DashScopeInput getInput() {
        return input;
    }

    public void setInput(DashScopeInput input) {
        this.input = input;
    }

    public DashScopeParameters getParameters() {
        return parameters;
    }

    public void setParameters(DashScopeParameters parameters) {
        this.parameters = parameters;
    }

    /**
     * 获取用于端点选择的端点类型。
     *
     * @return 端点类型（默认 AUTO）
     */
    public EndpointType getEndpointType() {
        return endpointType;
    }

    /**
     * 设置用于端点选择的端点类型。
     *
     * @param endpointType 端点类型
     */
    public void setEndpointType(EndpointType endpointType) {
        this.endpointType = endpointType;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String model;
        private DashScopeInput input;
        private DashScopeParameters parameters;
        private EndpointType endpointType = EndpointType.AUTO;

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder input(DashScopeInput input) {
            this.input = input;
            return this;
        }

        public Builder parameters(DashScopeParameters parameters) {
            this.parameters = parameters;
            return this;
        }

        /**
         * 设置用于端点选择的端点类型。
         *
         * @param endpointType 端点类型
         * @return 当前构建器
         */
        public Builder endpointType(EndpointType endpointType) {
            this.endpointType = endpointType;
            return this;
        }

        public DashScopeRequest build() {
            DashScopeRequest request = new DashScopeRequest(model, input, parameters);
            request.setEndpointType(endpointType);
            return request;
        }
    }
}
