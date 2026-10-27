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
package io.agentscope.extensions.model.openai.compat.kimi;

import static io.agentscope.core.model.ModelProviderSupport.firstNonBlank;
import static io.agentscope.core.model.ModelProviderSupport.trimToNull;
import static io.agentscope.extensions.model.openai.OpenAIModelProviderSupport.applyAdvancedOptions;

import io.agentscope.core.formatter.Formatter;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelContextWindows;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.spi.ModelProvider;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import java.util.regex.Pattern;

/**
 * 通过 {@link java.util.ServiceLoader} 注册的 Kimi（Moonshot AI）提供者。
 *
 * <p>Kimi 暴露 OpenAI 兼容的聊天补全端点，因此创建为 Kimi 预配置的 {@link OpenAIChatModel}：
 * <ul><li>基础 URL 默认为 {@code https://api.moonshot.cn/v1}。</li>
 * <li>默认格式化器为 {@link KimiFormatter}；自定义 {@link Formatter} 若存在于
 * {@link ModelCreationContext} 中则优先，例如 {@link KimiMultiAgentFormatter}。</li>
 * <li>默认启用原生结构化输出，使用 {@code response_format} 和 {@code json_schema}。</li>
 * <li>默认禁用结构化输出与工具同时使用，因为两者并存时 Kimi 优先处理 {@code response_format}。</li>
 * <li>{@code ToolChoice.Specific} 在启用思考的模型上报告不支持，
 * 包括 {@code kimi-k3}、{@code kimi-k2.7-code}、{@code kimi-k2.6} / {@code kimi-k2.5}，因为 {@link KimiFormatter} 会将其降级为 {@code auto}；其他模型保留。</li></ul>
 *
 * <p>API 密钥依次从 {@link ModelCreationContext#getApiKey()}、环境变量
 * {@code MOONSHOT_API_KEY}、{@code KIMI_API_KEY} 获取。
 *
 * <p>用法：
 * <pre>{@code
 * ReActAgent agent = ReActAgent.builder()
 *     .name("assistant")
 *     .model("kimi:kimi-k3") // resolved by ModelRegistry through this provider
 *     .build();
 * }</pre>
 */
public final class KimiModelProvider implements ModelProvider {

    private static final String PREFIX = "kimi:";
    private static final Pattern MODEL_ID = Pattern.compile("kimi:.+");
    private static final String DEFAULT_BASE_URL = "https://api.moonshot.cn/v1";

    @Override
    public String providerId() {
        return "kimi";
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
            throw new IllegalArgumentException("Unsupported Kimi model id: " + modelId);
        }

        String apiKey =
                firstNonBlank(
                        context.getApiKey(),
                        System.getenv("MOONSHOT_API_KEY"),
                        System.getenv("KIMI_API_KEY"));
        if (apiKey == null) {
            throw new IllegalStateException(
                    "Environment variable MOONSHOT_API_KEY / KIMI_API_KEY is required to"
                            + " auto-create model: "
                            + modelId);
        }
        String modelName = trimToNull(modelId.substring(PREFIX.length()));
        String baseUrl = firstNonBlank(context.getBaseUrl(), DEFAULT_BASE_URL);
        String endpointPath = trimToNull(context.getEndpointPath());
        boolean stream = context.getStream() != null ? context.getStream() : true;

        // ReActAgent 依据此标志选择强制 generate_response 的策略：
        // true 在请求中追加 ToolChoice.Specific，false 注入提示词
        // 提醒。对应 KimiFormatter.applyKimiToolChoice 的 Specific 分支，
        // 后者在思考模型上静默将 Specific 改为 "auto"：
        // kimi-k3 / kimi-k2.7-code 始终如此；kimi-k2.6 / kimi-k2.5 则在请求未
        // 禁用思考时如此。模型列表变化时需同步更新两处。
        // 报告 false 是保守的选择，因为提示词提醒可用于所有模型；
        // 因此模型级标志也保守地将 kimi-k2.6 / kimi-k2.5 设为 false。
        boolean specificUsable =
                !KimiFormatter.isAlwaysThinkingModel(modelName)
                        && !KimiFormatter.hasConfigurableThinking(modelName);

        OpenAIChatModel.Builder builder =
                OpenAIChatModel.builder().apiKey(apiKey).modelName(modelName).stream(stream)
                        .baseUrl(baseUrl)
                        .endpointPath(endpointPath)
                        .formatter(new KimiFormatter())
                        .nativeStructuredOutput(true)
                        .nativeStructuredOutputWithTools(false)
                        .supportsToolChoiceSpecific(specificUsable)
                        .contextWindowSize(
                                ModelContextWindows.lookup(modelName, ModelContextWindows.KIMI));

        GenerateOptions userOptions = context.component(GenerateOptions.class);
        applyAdvancedOptions(builder, context, userOptions);
        return builder.build();
    }
}
