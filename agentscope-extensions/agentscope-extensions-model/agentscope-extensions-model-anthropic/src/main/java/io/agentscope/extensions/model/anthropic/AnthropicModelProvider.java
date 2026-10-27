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
package io.agentscope.extensions.model.anthropic;

import static io.agentscope.core.model.ModelProviderSupport.firstNonBlank;
import static io.agentscope.core.model.ModelProviderSupport.intOption;
import static io.agentscope.core.model.ModelProviderSupport.stringOption;
import static io.agentscope.core.model.ModelProviderSupport.trimToNull;

import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.spi.ModelProvider;
import io.agentscope.core.model.transport.ProxyConfig;
import io.agentscope.extensions.model.anthropic.formatter.AnthropicBaseFormatter;
import java.util.regex.Pattern;

/**
 * 通过 {@link java.util.ServiceLoader} 注册的 Anthropic 提供者。
 *
 * <p>凭证来自上下文标准 {@code apiKey} 字段或 {@code
 * "authToken"} 选项，后者用于 Anthropic 兼容网关的 Bearer 令牌，两者互斥。
 * 均未设置时，依次使用环境变量 {@code ANTHROPIC_API_KEY} 和 {@code ANTHROPIC_AUTH_TOKEN}。
 */
public final class AnthropicModelProvider implements ModelProvider {

    private static final String PREFIX = "anthropic:";
    private static final Pattern MODEL_ID = Pattern.compile("anthropic:.+");
    private static final String OPTION_CONTEXT_WINDOW_SIZE = "contextWindowSize";
    private static final String OPTION_AUTH_TOKEN = "authToken";

    @Override
    public String providerId() {
        return "anthropic";
    }

    @Override
    public boolean supports(String modelId) {
        return modelId != null && MODEL_ID.matcher(modelId).matches();
    }

    @Override
    public Model create(String modelId) {
        return create(modelId, ModelCreationContext.empty());
    }

    @Override
    public Model create(String modelId, ModelCreationContext context) {
        if (!supports(modelId)) {
            throw new IllegalArgumentException("Unsupported Anthropic model id: " + modelId);
        }
        String modelName = modelId.substring(PREFIX.length());
        String apiKey = trimToNull(context.getApiKey());
        String authToken = stringOption(context, OPTION_AUTH_TOKEN);
        if (apiKey == null && authToken == null) {
            // 没有显式凭证时回退到环境变量，保留历史上的
            // ANTHROPIC_API_KEY 优先于 ANTHROPIC_AUTH_TOKEN 的顺序。
            apiKey = trimToNull(System.getenv("ANTHROPIC_API_KEY"));
            if (apiKey == null) {
                authToken = trimToNull(System.getenv("ANTHROPIC_AUTH_TOKEN"));
            }
        }
        AnthropicChatModel.Builder builder =
                AnthropicChatModel.builder()
                        .apiKey(apiKey)
                        .authToken(authToken)
                        .modelName(modelName)
                        .stream(context.getStream() != null ? context.getStream() : true);
        String baseUrl = firstNonBlank(context.getBaseUrl(), System.getenv("ANTHROPIC_BASE_URL"));
        if (baseUrl != null) {
            builder.baseUrl(baseUrl);
        }
        applyAdvancedOptions(builder, context);
        return builder.build();
    }

    private static void applyAdvancedOptions(
            AnthropicChatModel.Builder builder, ModelCreationContext context) {
        GenerateOptions defaultOptions = context.component(GenerateOptions.class);
        if (defaultOptions != null) {
            builder.defaultOptions(defaultOptions);
        }
        ProxyConfig proxyConfig = context.component(ProxyConfig.class);
        if (proxyConfig != null) {
            builder.proxy(proxyConfig);
        }
        AnthropicBaseFormatter formatter = context.component(AnthropicBaseFormatter.class);
        if (formatter != null) {
            builder.formatter(formatter);
        }
        Integer contextWindowSize = intOption(context, OPTION_CONTEXT_WINDOW_SIZE);
        if (contextWindowSize != null) {
            builder.contextWindowSize(contextWindowSize);
        }
    }
}
