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
package io.agentscope.spring.boot.openai;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OpenAI 提供方专用配置。
 *
 * <p>配置示例：
 *
 * <pre>{@code
 * agentscope:
 *   model:
 *     provider: openai
 *   openai:
 *     enabled: true
 *     api-key: ${OPENAI_API_KEY}
 *     model-name: gpt-4.1-mini
 *     # base-url: https://api.openai.com/v1 # 可选，用于兼容端点
 *     # endpoint-path: /v1/chat/completions # 可选，用于自定义端点路径
 *     stream: true
 * }</pre>
 */
@ConfigurationProperties(prefix = "agentscope.openai")
public class OpenAIProperties {

    /** 是否启用 OpenAI 模型自动配置。 */
    private boolean enabled = true;

    /**
     * OpenAI API 密钥。
     *
     * <p>可选：部分 OpenAI 兼容提供方（如某些免费模型端点）不要求 API 密钥；对这些提供方可以不设置。
     */
    private String apiKey;

    /** OpenAI 模型名称，如 {@code gpt-4.1-mini}。 */
    private String modelName = "gpt-4.1-mini";

    /** OpenAI 兼容端点的可选基础 URL。 */
    private String baseUrl;

    /**
     * OpenAI 兼容端点的可选路径。
     * <p>用于定制与标准 OpenAI API 路径不同的兼容 API，例如 "/v4/chat/completions"。
     */
    private String endpointPath;

    /** 是否启用流式响应。 */
    private boolean stream = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getEndpointPath() {
        return endpointPath;
    }

    public void setEndpointPath(String endpointPath) {
        this.endpointPath = endpointPath;
    }

    public boolean isStream() {
        return stream;
    }

    public void setStream(boolean stream) {
        this.stream = stream;
    }
}
