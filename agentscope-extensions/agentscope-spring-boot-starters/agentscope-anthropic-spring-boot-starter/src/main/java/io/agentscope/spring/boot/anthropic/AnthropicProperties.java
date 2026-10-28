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
package io.agentscope.spring.boot.anthropic;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Anthropic 提供方专用配置。
 *
 * <p>配置示例：
 *
 * <pre>{@code
 * agentscope:
 *   model:
 *     provider: anthropic
 *   anthropic:
 *     enabled: true
 *     api-key: ${ANTHROPIC_API_KEY}
 *     model-name: claude-sonnet-4.5
 *     # base-url: https://api.anthropic.com # 可选
 *     stream: true
 * }</pre>
 */
@ConfigurationProperties(prefix = "agentscope.anthropic")
public class AnthropicProperties {

    /** 是否启用 Anthropic 模型自动配置。 */
    private boolean enabled = true;

    /** Anthropic API 密钥。未设置时，Anthropic SDK 可以从其默认来源加载密钥。 */
    private String apiKey;

    /** 用于 Anthropic 兼容网关的 bearer 令牌，不含 {@code Bearer } 前缀。与 API 密钥互斥；同时配置两者会导致模型构建失败。 */
    private String authToken;

    /** Anthropic API 基础 URL（可选）。 */
    private String baseUrl;

    /** Anthropic 模型名称，如 {@code claude-sonnet-4.5}。 */
    private String modelName = "claude-sonnet-4.5";

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

    /**
     * 返回网关认证的 bearer 令牌。
     *
     * @return 不含 {@code Bearer } 前缀的令牌；未设置时返回 null
     */
    public String getAuthToken() {
        return authToken;
    }

    /**
     * 设置网关认证的 bearer 令牌。
     *
     * @param authToken 不含 {@code Bearer } 前缀的令牌
     */
    public void setAuthToken(String authToken) {
        this.authToken = authToken;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public boolean isStream() {
        return stream;
    }

    public void setStream(boolean stream) {
        this.stream = stream;
    }
}
