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
package io.agentscope.extensions.model.openai;

import io.agentscope.core.formatter.Formatter;
import io.agentscope.core.formatter.JsonSchema;
import io.agentscope.core.formatter.ResponseFormat;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ModelContextWindows;
import io.agentscope.core.model.ModelException;
import io.agentscope.core.model.ModelUtils;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportConfig;
import io.agentscope.core.model.transport.HttpTransportFactory;
import io.agentscope.core.model.transport.OkHttpTransport;
import io.agentscope.core.model.transport.ProxyConfig;
import io.agentscope.extensions.model.openai.dto.OpenAIMessage;
import io.agentscope.extensions.model.openai.dto.OpenAIRequest;
import io.agentscope.extensions.model.openai.dto.OpenAIResponse;
import io.agentscope.extensions.model.openai.dto.OpenAIStreamOptions;
import io.agentscope.extensions.model.openai.formatter.OpenAIChatFormatter;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * 使用原生 HTTP API 的 OpenAI 聊天模型。
 *
 * <p>直接通过 HTTP 调用 OpenAI 兼容 API。
 *
 * <p>能力包括：
 * <ul><li>流式与非流式模式。</li><li>工具调用。</li><li>自动消息格式转换。</li>
 * <li>超时与重试配置。</li><li>通过不同 Formatter 支持多种提供商。</li></ul>
 *
 * <p>提供商专用行为由 Formatter 处理，应选择对应格式化器：
 * <ul><li>{@link OpenAIChatFormatter}：标准 OpenAI GPT 模型。</li>
 * <li>{@link io.agentscope.extensions.model.openai.compat.deepseek.DeepSeekFormatter}：DeepSeek Chat 模型。</li>
 * <li>{@link io.agentscope.extensions.model.openai.compat.minimax.MiniMaxFormatter}：MiniMax 模型。</li>
 * <li>{@link io.agentscope.extensions.model.openai.compat.kimi.KimiFormatter}：Kimi（Moonshot AI）模型。</li>
 * <li>{@link io.agentscope.extensions.model.openai.compat.glm.GLMFormatter}：智谱 GLM 模型。</li></ul>
 *
 * <p>【学习提示】本层组织配置、格式化、传输和响应解析，实际工具执行不在此处。
 * “OpenAI 兼容”并不保证所有字段语义一致，格式适配与能力开关仍需按目标端点选择。
 */
public class OpenAIChatModel extends ChatModelBase {

    private static final Logger log = LoggerFactory.getLogger(OpenAIChatModel.class);

    private final OpenAIClient client;
    private final Formatter<OpenAIMessage, OpenAIResponse, OpenAIRequest> formatter;
    private final GenerateOptions configuredOptions;
    private final Boolean strictJsonSchema;

    /**
     * 使用预配置选项创建 OpenAI 聊天模型实例。
     *
     * @param client OpenAI HTTP 客户端
     * @param formatter 消息格式化器
     * @param configuredOptions 预配置选项，无状态用法可为 null
     */
    private OpenAIChatModel(
            OpenAIClient client,
            Formatter<OpenAIMessage, OpenAIResponse, OpenAIRequest> formatter,
            GenerateOptions configuredOptions,
            Boolean strictJsonSchema) {
        this.client = client != null ? client : new OpenAIClient();
        this.formatter = formatter != null ? formatter : new OpenAIChatFormatter();
        this.configuredOptions = configuredOptions;
        this.strictJsonSchema = strictJsonSchema;
    }

    @Override
    protected Flux<ChatResponse> doStream(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        return ModelUtils.applyTimeoutAndRetry(
                doStream0(messages, tools, options),
                options,
                configuredOptions,
                configuredOptions.getModelName(),
                "openai");
    }

    protected Flux<ChatResponse> doStream0(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {

        // 合并调用选项与预配置选项，调用选项优先。
        GenerateOptions effectiveOptions = GenerateOptions.mergeOptions(options, configuredOptions);

        if (effectiveOptions == null || effectiveOptions.getModelName() == null) {
            throw new IllegalArgumentException(
                    "modelName must be specified in GenerateOptions or configured in builder");
        }

        String modelName = effectiveOptions.getModelName();
        log.debug("OpenAI API call: model={}", modelName);

        // 确定流式模式，effectiveOptions.stream 优先。
        boolean stream =
                effectiveOptions.getStream() != null ? effectiveOptions.getStream() : false;

        // 从 effectiveOptions 获取 apiKey 与 baseUrl。
        String apiKey = effectiveOptions.getApiKey();
        String baseUrl = effectiveOptions.getBaseUrl();

        Instant start = Instant.now();

        // 使用格式化器转换消息，处理提供商差异。
        List<OpenAIMessage> openaiMessages = formatter.format(messages, effectiveOptions);

        // 构建请求。
        OpenAIRequest.Builder requestBuilder =
                OpenAIRequest.builder().model(modelName).messages(openaiMessages).stream(stream);

        // 所有流式调用的 stream_options 都请求包含用量。
        // 这样最后一个响应分块可以提供 token 用量信息。
        // DashScope、百炼等 OpenAI 兼容 API 需要此设置。
        if (stream) {
            requestBuilder.streamOptions(new OpenAIStreamOptions(true));
        }

        OpenAIRequest request = requestBuilder.build();

        // 将工具应用到请求，工具格式差异由格式化器处理。
        if (tools != null && !tools.isEmpty()) {
            formatter.applyTools(request, tools);
        }

        // 应用生成选项，提供商选项差异由格式化器处理。
        formatter.applyOptions(request, effectiveOptions, null);
        applyStrictJsonSchemaDefault(request);

        // 若指定工具选择则应用，差异由格式化器处理。
        if (effectiveOptions.getToolChoice() != null) {
            formatter.applyToolChoice(request, effectiveOptions.getToolChoice());
        }

        // 执行 API 调用。
        if (stream) {
            // 流式模式延迟创建请求，使 retryWhen 重新订阅时重新发出
            // HTTP 请求；如果没有 defer，stream() 绑定单个 future 的传输实现
            // （如 JdkHttpTransport）只会重放同一个失败流。
            return Flux.defer(
                            () ->
                                    client.stream(apiKey, baseUrl, request, effectiveOptions)
                                            .map(
                                                    response ->
                                                            formatter.parseResponse(
                                                                    response, start))
                                            .filter(Objects::nonNull))
                    .subscribeOn(Schedulers.boundedElastic());
        } else {
            // 非流式模式：执行一次调用并以 Flux 返回。
            return Flux.defer(
                            () -> {
                                try {
                                    OpenAIResponse response =
                                            client.call(apiKey, baseUrl, request, effectiveOptions);
                                    ChatResponse chatResponse =
                                            formatter.parseResponse(response, start);
                                    return Flux.just(chatResponse);
                                } catch (Exception e) {
                                    return Flux.error(
                                            new ModelException(
                                                    "Failed to call OpenAI API: " + e.getMessage(),
                                                    e,
                                                    modelName,
                                                    "openai"));
                                }
                            })
                    .subscribeOn(Schedulers.boundedElastic());
        }
    }

    /** 应用模型级严格 JSON Schema 默认值，但不修改已携带请求级 strict 值的模式。 */
    private void applyStrictJsonSchemaDefault(OpenAIRequest request) {
        if (strictJsonSchema == null
                || !(request.getResponseFormat() instanceof ResponseFormat responseFormat)
                || !"json_schema".equals(responseFormat.getType())) {
            return;
        }

        JsonSchema jsonSchema = responseFormat.getJsonSchema();
        if (jsonSchema == null || jsonSchema.getStrict() != null) {
            return;
        }

        request.setResponseFormat(
                ResponseFormat.jsonSchema(
                        JsonSchema.builder()
                                .name(jsonSchema.getName())
                                .description(jsonSchema.getDescription())
                                .schema(jsonSchema.getSchema())
                                .strict(strictJsonSchema)
                                .build()));
    }

    /**
     * 获取用于日志和标识的模型名称。
     *
     * @return 模型名称；未配置时返回 null
     */
    @Override
    public String getModelName() {
        return configuredOptions != null ? configuredOptions.getModelName() : null;
    }

    /** 包内可见的测试入口，用于验证模型级严格 JSON Schema 配置。 */
    Boolean getStrictJsonSchema() {
        return strictJsonSchema;
    }

    /**
     * 创建 OpenAIChatModel 构建器。
     *
     * @return 新的 Builder 实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * OpenAIChatModel 的构建器。
     *
     * <p>构建出的模型在内部封装配置；调用未显式传选项时使用构建器提供的值。
     */
    public static class Builder {
        private String apiKey;
        private String modelName;
        private boolean stream = true;
        private GenerateOptions defaultOptions;
        private String baseUrl;
        private String endpointPath;
        private Formatter<OpenAIMessage, OpenAIResponse, OpenAIRequest> formatter;
        private HttpTransport httpTransport;
        private ProxyConfig proxyConfig;
        private int contextWindowSize = -1;
        private Boolean nativeStructuredOutput;
        private Boolean nativeStructuredOutputWithTools;
        private Boolean supportsToolChoiceSpecific;
        private Boolean strictJsonSchema;

        /**
         * 设置 OpenAI 认证 API 密钥。
         *
         * @param apiKey API 密钥
         * @return 当前构建器实例
         */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /**
         * 设置要使用的模型名称。
         *
         * @param modelName 模型名称，例如 "gpt-4"、"gpt-3.5-turbo"
         * @return 当前构建器实例
         */
        public Builder modelName(String modelName) {
            this.modelName = modelName;
            return this;
        }

        /**
         * 设置是否启用流式输出。
         *
         * @param stream true 启用流式，false 使用非流式
         * @return 当前构建器实例
         */
        public Builder stream(boolean stream) {
            this.stream = stream;
            return this;
        }

        /**
         * 设置默认生成选项。
         *
         * @param options 要使用的默认选项，null 使用默认值
         * @return 当前构建器实例
         */
        public Builder generateOptions(GenerateOptions options) {
            this.defaultOptions = options;
            return this;
        }

        /**
         * 设置 OpenAI API 的自定义基础 URL。
         *
         * @param baseUrl 基础 URL，null 使用默认值
         * @return 当前构建器实例
         */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /**
         * 设置 API 请求的自定义端点路径。
         *
         * <p>用于端点路径与标准 OpenAI API 不同的兼容 API，例如 "/v4/chat/completions"、
         * "/api/v1/llm/chat"。为 null 时使用默认端点路径。
         *
         * @param endpointPath 端点路径，例如 "/v1/chat/completions"
         * @return 当前构建器实例
         */
        public Builder endpointPath(String endpointPath) {
            this.endpointPath = endpointPath;
            return this;
        }

        /**
         * 设置消息格式化器。
         *
         * <p>不同提供商使用对应格式化器：
         * <ul><li>{@link OpenAIChatFormatter}：标准 OpenAI GPT 模型。</li>
         * <li>{@link io.agentscope.extensions.model.openai.compat.deepseek.DeepSeekFormatter}：DeepSeek Chat 模型。</li>
         * <li>{@link io.agentscope.extensions.model.openai.compat.glm.GLMFormatter}：智谱 GLM 模型。</li>
         * <li>{@link io.agentscope.extensions.model.openai.compat.minimax.MiniMaxFormatter}：MiniMax 模型。</li>
         * <li>{@link io.agentscope.extensions.model.openai.compat.kimi.KimiFormatter}：Kimi（Moonshot AI）模型。</li></ul>
         *
         * @param formatter 格式化器，null 使用默认 OpenAI 格式化器
         * @return 当前构建器实例
         */
        public Builder formatter(
                Formatter<OpenAIMessage, OpenAIResponse, OpenAIRequest> formatter) {
            this.formatter = formatter;
            return this;
        }

        /**
         * 设置 HTTP 传输实现。
         *
         * <p><b>代理配置注意事项：</b>{@code httpTransport} 是已构建完成的 HTTP 客户端实例，
         * 创建后不能修改其配置，包括代理。如需配置代理，可选择：
         * <ul><li>仅通过 {@link #proxy(ProxyConfig)} 自定义代理，不调用 {@code httpTransport()}。</li>
         * <li>构建传输实例时，直接在其 {@link HttpTransportConfig} 中配置代理。</li></ul>
         *
         * <p>同时调用 {@code httpTransport()} 和 {@code proxy()} 时，
         * {@code httpTransport()} 完全优先，{@code proxy()} 被忽略，构建时会记录警告。
         *
         * @param httpTransport HTTP 传输实现，null 使用工厂默认值
         * @return 当前构建器实例
         */
        public Builder httpTransport(HttpTransport httpTransport) {
            this.httpTransport = httpTransport;
            return this;
        }

        /**
         * 设置 HTTP 流量的代理配置。
         *
         * <p><b>与 {@link #httpTransport(HttpTransport)} 的关系：</b>
         * {@code httpTransport} 是已构建完成的客户端实例，创建后不能修改配置。最终传输实现按以下规则确定：
         * <ul><li>仅调用 {@code proxy()}：使用默认 {@link OkHttpTransport} 并应用指定代理配置。</li>
         * <li>仅调用 {@code httpTransport()}：原样使用传入实例，代理必须在其自身的 {@link HttpTransportConfig} 中配置。</li>
         * <li>两者都调用：{@code httpTransport()} 完全优先，{@code proxy()} 被忽略并记录警告。
         * 自定义传输的代理应直接配置在其 {@link HttpTransportConfig} 中。</li>
         * <li>两者都不调用：使用 {@link HttpTransportFactory} 的默认传输，不设置代理。</li></ul>
         *
         * @param proxyConfig 代理配置，参见 {@link ProxyConfig}
         * @return 当前构建器实例
         */
        public Builder proxy(ProxyConfig proxyConfig) {
            this.proxyConfig = proxyConfig;
            return this;
        }

        public Builder contextWindowSize(int contextWindowSize) {
            this.contextWindowSize = contextWindowSize;
            return this;
        }

        /**
         * 设置模型是否支持通过 {@code response_format} 提供原生结构化输出。
         *
         * <p>默认为 {@code true}，适用于 OpenAI 自有模型。兼容提供商应设为 {@code false}，
         * 若其不能可靠地通过 {@code response_format} 返回受模式约束的 JSON；
         * 智能体会改用 {@code generate_response} 回退工具。
         *
         * @param nativeStructuredOutput false 表示禁用原生结构化输出
         * @return 当前构建器实例
         */
        public Builder nativeStructuredOutput(boolean nativeStructuredOutput) {
            this.nativeStructuredOutput = nativeStructuredOutput;
            return this;
        }

        /**
         * 设置模型是否能在工具调用同时启用时正确处理原生结构化输出（{@code response_format}）。
         *
         * <p>默认为 {@code true}，适用于 GPT-4o 等 OpenAI 自有模型。
         * 对于 Kimi、DeepSeek 等兼容提供商，应设为 {@code false}，如果其 {@code response_format} 优先于工具调用，
         * 导致两者并存时跳过工具。
         *
         * @param nativeStructuredOutputWithTools false 表示存在工具时使用回退方案
         * @return 当前构建器实例
         */
        public Builder nativeStructuredOutputWithTools(boolean nativeStructuredOutputWithTools) {
            this.nativeStructuredOutputWithTools = nativeStructuredOutputWithTools;
            return this;
        }

        /**
         * 设置模型是否支持通过 {@code tool_choice} 强制调用指定工具，
         * 即 {@link io.agentscope.core.model.ToolChoice.Specific}。
         *
         * <p>默认为 {@code false}，因为此构建器可指向任何 OpenAI 兼容端点。
         * 仅在端点确实支持强制命名函数时设为 {@code true}，通过 {@code tool_choice} 实现；
         * 否则智能体需要强制 {@code generate_response} 工具时会使用提示词提醒。
         *
         * @param supportsToolChoiceSpecific 不支持 {@code ToolChoice.Specific} 时设为 false
         * @return 当前构建器实例
         */
        public Builder supportsToolChoiceSpecific(boolean supportsToolChoiceSpecific) {
            this.supportsToolChoiceSpecific = supportsToolChoiceSpecific;
            return this;
        }

        /**
         * 设置 JSON Schema 响应格式的默认 strict 值。
         *
         * <p>请求级模式中的 strict 值优先。此值为 {@code null} 时，原样发送模式自带的 strict；
         * 模式也未设置时省略该字段。
         *
         * @param strictJsonSchema 模型级严格 JSON Schema 设置
         * @return 当前构建器实例
         */
        public Builder strictJsonSchema(Boolean strictJsonSchema) {
            this.strictJsonSchema = strictJsonSchema;
            return this;
        }

        /**
         * 构建 OpenAIChatModel 实例。
         *
         * @return 已配置的 OpenAIChatModel 实例
         * @throws IllegalArgumentException 未设置 modelName 时抛出
         */
        public OpenAIChatModel build() {
            Objects.requireNonNull(modelName, "modelName must be set");

            // 从构建器字段生成选项，这些字段优先。
            GenerateOptions.Builder optionsBuilder =
                    GenerateOptions.builder()
                            .apiKey(apiKey)
                            .baseUrl(baseUrl)
                            .modelName(modelName)
                            .stream(stream);

            if (endpointPath != null) {
                optionsBuilder.endpointPath(endpointPath);
            }

            GenerateOptions builderOptions = optionsBuilder.build();

            // 与 defaultOptions 合并，构建器字段优先。
            GenerateOptions mergedOptions =
                    GenerateOptions.mergeOptions(builderOptions, defaultOptions);

            // 确保执行配置具有默认值。
            GenerateOptions effectiveOptions =
                    ModelUtils.ensureDefaultExecutionConfig(mergedOptions);

            // 解析传输实现。
            HttpTransport transport = resolveTransport();
            OpenAIClient client = new OpenAIClient(transport);
            Formatter<OpenAIMessage, OpenAIResponse, OpenAIRequest> fmt =
                    formatter != null ? formatter : new OpenAIChatFormatter();

            OpenAIChatModel model =
                    new OpenAIChatModel(client, fmt, effectiveOptions, strictJsonSchema);
            model.setContextWindowSize(
                    contextWindowSize >= 0
                            ? contextWindowSize
                            : ModelContextWindows.lookup(modelName, ModelContextWindows.OPENAI));
            model.setNativeStructuredOutput(
                    nativeStructuredOutput != null ? nativeStructuredOutput : true);
            if (nativeStructuredOutputWithTools != null) {
                model.setNativeStructuredOutputWithTools(nativeStructuredOutputWithTools);
            }
            if (supportsToolChoiceSpecific != null) {
                model.setSupportsToolChoiceSpecific(supportsToolChoiceSpecific);
            }
            return model;
        }

        /**
         * 确定最终使用的 HttpTransport。
         *
         * <p>已设置 {@code httpTransport()} 时，它完全优先，忽略 {@code proxyConfig} 并记录警告。
         * 否则若设置了 {@code proxy()}，则创建应用该代理的默认传输；两者均未设置时使用工厂默认值。
         *
         * @return 确定后的 HttpTransport
         */
        private HttpTransport resolveTransport() {
            if (httpTransport != null) {
                if (proxyConfig != null) {
                    log.warn(
                            "OpenAIChatModel: both proxy() and httpTransport() are set. "
                                    + "httpTransport() takes precedence, proxy() is ignored. "
                                    + "To configure proxy, use one of the following:\n"
                                    + "  1. Simple: only call proxy() without httpTransport()\n"
                                    + "  2. Advanced: set proxy in HttpTransportConfig when "
                                    + "building a custom HttpTransport");
                }
                return httpTransport;
            }

            if (proxyConfig != null) {
                // 仅调用 proxy()：使用带代理的默认传输。
                return OkHttpTransport.builder()
                        .config(HttpTransportConfig.builder().proxy(proxyConfig).build())
                        .build();
            }

            // 两者均未调用：使用工厂默认值。
            return HttpTransportFactory.getDefault();
        }
    }
}
