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
import io.agentscope.spring.boot.AgentscopeAutoConfiguration;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/** Jev 决策模型客户端的 Spring Boot 自动配置。 */
@AutoConfiguration(before = AgentscopeAutoConfiguration.class)
@EnableConfigurationProperties(JevProperties.class)
@ConditionalOnClass(JevClient.class)
public class JevAutoConfiguration {

    @Bean
    @ConditionalOnProperty(
            prefix = "agentscope.jev",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    @ConditionalOnMissingBean(JevClient.class)
    public JevClient jevClient(
            JevProperties properties,
            ObjectProvider<JevClientBuilderCustomizer> customizerObjectProvider) {
        JevClient.Builder builder = JevClient.builder();

        String apiKey = trimToNull(properties.getApiKey());
        if (apiKey != null) {
            builder.apiKey(apiKey);
        }

        String baseUrl = trimToNull(properties.getBaseUrl());
        if (baseUrl != null) {
            builder.baseUrl(baseUrl);
        }

        String model = trimToNull(properties.getModel());
        if (model != null) {
            builder.model(model);
        }

        Duration timeout = properties.getTimeout();
        if (timeout != null) {
            builder.timeout(timeout);
        }

        JevProperties.Retry retry = properties.getRetry();
        if (retry != null) {
            builder.retryPolicy(
                    new JevRetryPolicy(retry.getMaxRetries(), retry.getInitialBackoff()));
        }

        // 【学习提示】先应用配置属性，再按 orderedStream 顺序运行定制器；后者可覆盖先前设置。
        // 定制器是受信任应用代码，修改 baseUrl 时应同时确认目标服务是否应接收所配置的凭据。
        customizerObjectProvider
                .orderedStream()
                .forEach(customizer -> customizer.customize(builder));

        return builder.build();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
