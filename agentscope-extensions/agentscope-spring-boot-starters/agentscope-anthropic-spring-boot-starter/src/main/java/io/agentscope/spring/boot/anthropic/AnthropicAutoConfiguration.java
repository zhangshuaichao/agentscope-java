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

import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.anthropic.AnthropicChatModel;
import io.agentscope.spring.boot.AgentscopeAutoConfiguration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/** Anthropic 模型扩展的 Spring Boot 自动配置。 */
@AutoConfiguration(before = AgentscopeAutoConfiguration.class)
@EnableConfigurationProperties(AnthropicProperties.class)
@ConditionalOnClass(AnthropicChatModel.class)
public class AnthropicAutoConfiguration {

    @Bean
    @ConditionalOnProperty(
            prefix = "agentscope.model",
            name = "provider",
            havingValue = "anthropic")
    @ConditionalOnProperty(
            prefix = "agentscope.anthropic",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    @ConditionalOnMissingBean(Model.class)
    public AnthropicChatModel anthropicChatModel(
            AnthropicProperties properties,
            ObjectProvider<AnthropicChatModelBuilderCustomizer> customizerObjectProvider) {
        String modelName = trimToNull(properties.getModelName());
        if (modelName == null) {
            throw new IllegalStateException(
                    "agentscope.anthropic.model-name must be configured when Anthropic provider is"
                            + " selected");
        }

        String apiKey = trimToNull(properties.getApiKey());
        String authToken = trimToNull(properties.getAuthToken());
        AnthropicChatModel.Builder builder =
                AnthropicChatModel.builder()
                        .apiKey(apiKey)
                        .authToken(authToken)
                        .modelName(modelName)
                        .stream(properties.isStream());

        String baseUrl = trimToNull(properties.getBaseUrl());
        if (baseUrl != null) {
            builder.baseUrl(baseUrl);
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
