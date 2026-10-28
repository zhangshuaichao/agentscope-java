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
package io.agentscope.spring.boot.dashscope;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * DashScope 提供方专用配置属性。
 *
 * <p>配置示例（省略 {@code agentscope.model.provider} 时使用的默认提供方）：
 *
 * <pre>{@code
 * agentscope:
 *   dashscope:
 *     enabled: true
 *     api-key: ${DASHSCOPE_API_KEY}
 *     model-name: qwen-plus
 *     # base-url: # 可选
 *     stream: true
 *     enable-thinking: true
 *     # multimodal-model-patterns: ["deepseek-v4.1"]   # 可选
 * }</pre>
 */
@ConfigurationProperties(prefix = "agentscope.dashscope")
public class DashScopeProperties {

    /** 是否启用 DashScope 模型自动配置。 */
    private boolean enabled = true;

    /** DashScope API 密钥。 */
    private String apiKey;

    /** DashScope 模型名称，如 {@code qwen-plus} 或 {@code qwen-max}。 */
    private String modelName = "qwen-plus";

    /** 可选的 DashScope API 基础 URL。 */
    private String baseUrl;

    /** 是否启用流式响应。 */
    private boolean stream = true;

    /** 是否启用思考模式（可选）。 */
    private Boolean enableThinking;

    /**
     * 用于检测多模态模型的额外子串模式，不区分大小写。
     *
     * <p>{@code endpointType} 为 {@code AUTO}（默认）时，除内置模型名称规则外，匹配这些模式的模型也会路由到多模态生成 API。这样即可使用内置白名单尚未覆盖的多模态模型（如 {@code deepseek-v4.1}），不必等待框架更新。
     */
    private List<String> multimodalModelPatterns;

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

    public boolean isStream() {
        return stream;
    }

    public void setStream(boolean stream) {
        this.stream = stream;
    }

    public Boolean getEnableThinking() {
        return enableThinking;
    }

    public void setEnableThinking(Boolean enableThinking) {
        this.enableThinking = enableThinking;
    }

    public List<String> getMultimodalModelPatterns() {
        return multimodalModelPatterns;
    }

    public void setMultimodalModelPatterns(List<String> multimodalModelPatterns) {
        this.multimodalModelPatterns = multimodalModelPatterns;
    }
}
