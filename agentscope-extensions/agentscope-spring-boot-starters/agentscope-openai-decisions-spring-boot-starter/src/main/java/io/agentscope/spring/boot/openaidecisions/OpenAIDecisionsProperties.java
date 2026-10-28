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
package io.agentscope.spring.boot.openaidecisions;

import io.agentscope.extensions.judge.openaidecisions.OpenAIDecisionsClient;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** OpenAI Decisions 配置。 */
@ConfigurationProperties(prefix = "agentscope.openai-decisions")
public class OpenAIDecisionsProperties {

    /** 是否启用 OpenAI Decisions 客户端自动配置。 */
    private boolean enabled = true;

    /** OpenAI API 密钥；未设置时回退到 OPENAI_API_KEY。 */
    private String apiKey;

    /** OpenAI API 基础 URL，包含 /v1。 */
    private String baseUrl = OpenAIDecisionsClient.DEFAULT_BASE_URL;

    /** OpenAI Decisions 模型名称。 */
    private String model = OpenAIDecisionsClient.DEFAULT_MODEL;

    /** 请求超时时间。 */
    private Duration timeout = OpenAIDecisionsClient.DEFAULT_TIMEOUT;

    private final Retry retry = new Retry();

    public Retry getRetry() {
        return retry;
    }

    /** 包装层重试配置；自动配置客户端仍禁用 SDK 自身重试。 */
    public static class Retry {
        private int maxRetries;
        private Duration initialBackoff = Duration.ofMillis(500);

        public int getMaxRetries() {
            return maxRetries;
        }

        public void setMaxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
        }

        public Duration getInitialBackoff() {
            return initialBackoff;
        }

        public void setInitialBackoff(Duration initialBackoff) {
            this.initialBackoff = initialBackoff;
        }
    }

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

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }
}
