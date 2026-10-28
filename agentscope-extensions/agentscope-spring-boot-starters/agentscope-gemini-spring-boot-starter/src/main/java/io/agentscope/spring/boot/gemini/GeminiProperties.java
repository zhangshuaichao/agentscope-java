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
package io.agentscope.spring.boot.gemini;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Gemini 提供方专用配置。
 *
 * <p>直接使用 Gemini API 的配置示例：
 *
 * <pre>{@code
 * agentscope:
 *   model:
 *     provider: gemini
 *   gemini:
 *     enabled: true
 *     api-key: ${GEMINI_API_KEY}
 *     model-name: gemini-2.0-flash
 *     # base-url: # 可选
 *     stream: true
 * }</pre>
 *
 * <p>使用 Vertex AI 的配置示例：
 *
 * <pre>{@code
 * agentscope:
 *   model:
 *     provider: gemini
 *   gemini:
 *     enabled: true
 *     project: your-gcp-project-id
 *     location: us-central1
 *     model-name: gemini-2.0-flash
 *     vertex-ai: true
 *     stream: true
 * }</pre>
 */
@ConfigurationProperties(prefix = "agentscope.gemini")
public class GeminiProperties {

    /** 是否启用 Gemini 模型自动配置。 */
    private boolean enabled = true;

    /** Gemini API 密钥（直接使用 Gemini API 时）。 */
    private String apiKey;

    /** Gemini 模型名称，如 {@code gemini-2.0-flash}。 */
    private String modelName = "gemini-2.0-flash";

    /** Gemini API 兼容端点的可选基础 URL。 */
    private String baseUrl;

    /** 是否启用流式响应。 */
    private boolean stream = true;

    /** vertex-ai 为 true 时，Vertex AI 使用的 Google Cloud 项目 ID。 */
    private String project;

    /** Google Cloud 区域，如 {@code us-central1}。 */
    private String location;

    /** 是否使用 Vertex AI（true）而不是直接使用 Gemini API。 */
    private Boolean vertexAI;

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

    public String getProject() {
        return project;
    }

    public void setProject(String project) {
        this.project = project;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public Boolean getVertexAI() {
        return vertexAI;
    }

    public void setVertexAI(Boolean vertexAI) {
        this.vertexAI = vertexAI;
    }
}
