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
package io.agentscope.spring.boot.jev;

import io.agentscope.extensions.judge.jev.JevClient;
import io.agentscope.extensions.judge.jev.JevRetryPolicy;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Jev 决策模型配置。 */
@ConfigurationProperties(prefix = "agentscope.jev")
public class JevProperties {

    /** 是否启用 Jev 客户端自动配置。 */
    private boolean enabled = true;

    /** TypeSafe API 密钥；未设置时依次回退到 TYPESAFE_API_KEY 和 JEV_API_KEY。 */
    private String apiKey;

    /** TypeSafe System One 基础 URL。 */
    private String baseUrl = JevClient.DEFAULT_BASE_URL;

    /** Jev 模型名称。 */
    private String model = JevClient.DEFAULT_MODEL;

    /** 每次请求尝试的超时时间。 */
    private Duration timeout = JevClient.DEFAULT_TIMEOUT;

    /** 重试配置。 */
    private Retry retry = new Retry();

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

    public Retry getRetry() {
        return retry;
    }

    public void setRetry(Retry retry) {
        this.retry = retry;
    }

    /** 重试策略配置。 */
    public static class Retry {

        /** 首次尝试之后的最大重试次数。 */
        private Integer maxRetries = JevRetryPolicy.defaults().maxRetries();

        /** 指数退避的初始延迟。 */
        private Duration initialBackoff = JevRetryPolicy.defaults().initialBackoff();

        public Integer getMaxRetries() {
            return maxRetries;
        }

        public void setMaxRetries(Integer maxRetries) {
            this.maxRetries = maxRetries;
        }

        public Duration getInitialBackoff() {
            return initialBackoff;
        }

        public void setInitialBackoff(Duration initialBackoff) {
            this.initialBackoff = initialBackoff;
        }
    }
}
