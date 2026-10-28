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
package io.agentscope.extensions.scheduler.config;

import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.dashscope.formatter.DashScopeChatFormatter;
import java.util.Objects;

/**
 * DashScope 模型配置，封装聊天模型设置，与 {@code DashScopeChatModel.Builder} 对齐。
 * <p>支持文本及视觉模型、流式与思考模式、自定义生成选项及协议设置、API 基础 URL。
 * <p>使用示例：
 * <pre>{@code
 * // 基础配置
 * DashScopeModelConfig config = DashScopeModelConfig.builder()
 *     .apiKey(System.getenv("DASHSCOPE_API_KEY"))
 *     .modelName("qwen-max")
 *     .stream(true)
 *     .build();
 *
 * // 启用思考模式
 * DashScopeModelConfig thinkingConfig = DashScopeModelConfig.builder()
 *     .apiKey(apiKey)
 *     .modelName("qwen-max")
 *     .enableThinking(true)
 *     .defaultOptions(GenerateOptions.builder()
 *         .thinkingBudget(5000)
 *         .build())
 *     .build();
 *
 * // 在 AgentConfig 中使用
 * AgentConfig agentConfig = AgentConfig.builder()
 *     .name("MyAgent")
 *     .modelConfig(config)
 *     .build();
 * }</pre>
 * @see ModelConfig
 * @see AgentConfig
 */
public class DashScopeModelConfig implements ModelConfig {

    private final String apiKey;
    private final String modelName;
    private final boolean stream;
    private final boolean enableThinking;
    private final String baseUrl;

    private DashScopeModelConfig(Builder builder) {
        this.apiKey = builder.apiKey;
        this.modelName = builder.modelName;
        this.stream = builder.stream;
        this.enableThinking = builder.enableThinking;
        this.baseUrl = builder.baseUrl;

        validate();
    }

    /**
     * 校验配置。
     */
    private void validate() {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalArgumentException("API key must not be null or empty");
        }
        if (modelName == null || modelName.trim().isEmpty()) {
            throw new IllegalArgumentException("Model name must not be null or empty");
        }
    }

    @Override
    public String getModelName() {
        return modelName;
    }

    /**
     * 根据配置创建 {@link DashScopeChatModel}，应用全部设置后即可供智能体执行。
     * @return 配置后的 DashScopeChatModel 实例
     * @throws IllegalStateException 无法创建模型时
     */
    @Override
    public Model createModel() {
        DashScopeChatModel.Builder builder =
                DashScopeChatModel.builder()
                        .apiKey(apiKey)
                        .modelName(modelName)
                        .formatter(new DashScopeChatFormatter())
                        .stream(stream);

        if (enableThinking) {
            builder.enableThinking(enableThinking);
        }

        if (baseUrl != null) {
            builder.baseUrl(baseUrl);
        }

        return builder.build();
    }

    /**
     * 获取 API 密钥。
     * @return API 密钥
     */
    public String getApiKey() {
        return apiKey;
    }

    /**
     * 检查是否启用流式输出。
     * @return 启用时为 true
     */
    public boolean isStream() {
        return stream;
    }

    /**
     * 检查是否启用思考模式。
     * @return 启用为 true，禁用为 false
     */
    public boolean getEnableThinking() {
        return enableThinking;
    }

    /**
     * 获取基础 URL。
     * @return 自定义基础 URL，为 null 时使用默认值
     */
    public String getBaseUrl() {
        return baseUrl;
    }

    /**
     * 创建构建器。
     * @return 新 Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DashScopeModelConfig that = (DashScopeModelConfig) o;
        return stream == that.stream
                && Objects.equals(apiKey, that.apiKey)
                && Objects.equals(modelName, that.modelName)
                && Objects.equals(enableThinking, that.enableThinking)
                && Objects.equals(baseUrl, that.baseUrl);
    }

    @Override
    public int hashCode() {
        return Objects.hash(apiKey, modelName, stream, enableThinking, baseUrl);
    }

    @Override
    public String toString() {
        return "DashScopeModelConfig{"
                + "modelName='"
                + modelName
                + '\''
                + ", stream="
                + stream
                + ", enableThinking="
                + enableThinking
                + ", baseUrl='"
                + baseUrl
                + '\''
                + '}';
    }

    /**
     * 创建 DashScopeModelConfig 的构建器。
     */
    public static class Builder {
        private String apiKey;
        private String modelName;
        private boolean stream = true;
        private boolean enableThinking;
        private String baseUrl;

        private Builder() {}

        /**
         * 设置 DashScope 认证所需的 API 密钥。
         * @param apiKey API 密钥
         * @return 当前构建器
         */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /**
         * 设置必填模型名称。视觉模型（qvq* 或 *-vl*）使用 MultiModalConversation API，文本模型使用 Generation API。
         * <p>示例名称："qwen-max"、"qwen-plus"、"qwen-turbo"、视觉语言模型 "qwen-vl-plus"；具体可用性取决于提供方。
         * @param modelName 模型名称
         * @return 当前构建器
         */
        public Builder modelName(String modelName) {
            this.modelName = modelName;
            return this;
        }

        /**
         * 设置是否流式输出，可选，默认 true。
         * @param stream true 启用流式，false 使用非流式
         * @return 当前构建器
         */
        public Builder stream(boolean stream) {
            this.stream = stream;
            return this;
        }

        /**
         * 设置是否启用思考模式，可选，默认 false；允许模型输出推理内容。
         * @param enableThinking true 启用，false 禁用
         * @return 当前构建器
         */
        public Builder enableThinking(boolean enableThinking) {
            this.enableThinking = enableThinking;
            return this;
        }

        /**
         * 设置可选 DashScope API 基础 URL，用于区域端点、自定义代理或模拟服务测试。
         * @param baseUrl 基础 URL，为 null 时使用默认值
         * @return 当前构建器
         */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /**
         * 构建 DashScopeModelConfig。
         * @return 新实例
         * @throws IllegalArgumentException 必填字段缺失或无效时
         */
        public DashScopeModelConfig build() {
            return new DashScopeModelConfig(this);
        }
    }
}
