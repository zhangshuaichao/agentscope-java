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
package io.agentscope.extensions.model.dashscope;

import io.agentscope.core.formatter.Formatter;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ModelContextWindows;
import io.agentscope.core.model.ModelException;
import io.agentscope.core.model.ModelUtils;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportConfig;
import io.agentscope.core.model.transport.HttpTransportFactory;
import io.agentscope.core.model.transport.OkHttpTransport;
import io.agentscope.core.model.transport.ProxyConfig;
import io.agentscope.extensions.model.dashscope.dto.DashScopeMessage;
import io.agentscope.extensions.model.dashscope.dto.DashScopeRequest;
import io.agentscope.extensions.model.dashscope.dto.DashScopeResponse;
import io.agentscope.extensions.model.dashscope.formatter.DashScopeChatFormatter;
import io.agentscope.extensions.model.dashscope.formatter.DashScopeMultiAgentFormatter;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * 使用原生 HTTP API 的 DashScope 聊天模型。
 *
 * <p>通过可配置 HTTP 传输层直接调用 DashScope API，不依赖 DashScope Java SDK。
 *
 * <p>通过自动端点路由同时支持文本和视觉模型。可用 {@link EndpointType} 显式控制端点选择。
 *
 * <p>【学习提示】模型适配分为三层：格式化器将统一消息转换为供应商 DTO，HTTP 客户端负责协议与端点，响应解析器再恢复统一内容块。工具模式只描述模型可选择的调用，不执行 Java 工具；执行与权限检查仍在智能体工具链。
 *
 * <p>功能：
 * <ul>
 * <li>流式与非流式模式。</li>
 * <li>工具调用支持。</li>
 * <li>思考模式支持。</li>
 * <li>自动消息格式转换。</li>
 * <li>超时与重试配置。</li>
 * </ul>
 */
public class DashScopeChatModel extends ChatModelBase {

    private static final Logger log = LoggerFactory.getLogger(DashScopeChatModel.class);

    private final String modelName;
    private final boolean stream;
    private final Boolean enableThinking; // 可为 null。
    private final Boolean enableSearch; // 可为 null。
    private final EndpointType endpointType;
    private final GenerateOptions defaultOptions;
    private final Formatter<DashScopeMessage, DashScopeResponse, DashScopeRequest> formatter;

    // 用于 API 调用的 HTTP 客户端。
    private final DashScopeHttpClient httpClient;

    @Override
    public boolean supportsToolChoiceSpecific() {
        return true;
    }

    /**
     * 创建自动检测 API 类型的 DashScope 聊天模型实例。
     *
     * <p>此构造方法保持向后兼容。API 类型默认为 AUTO，根据模型名称检测端点。
     *
     * @param apiKey DashScope 认证 API 密钥
     * @param modelName 模型名称（例如 "qwen-max"、"qwen-vl-plus"）
     * @param stream 是否启用流式响应
     * @param enableThinking 是否启用思考模式（null 表示禁用）
     * @param enableSearch 是否启用搜索增强（null 表示禁用）
     * @param defaultOptions 默认生成选项（null 使用默认值）
     * @param baseUrl DashScope API 自定义基础 URL（null 使用默认值）
     * @param formatter 消息格式化器（null 使用默认 DashScope 格式化器）
     * @param httpTransport 自定义 HTTP 传输层（null 使用工厂默认值）
     * @param publicKeyId 加密 RSA 公钥 ID（null 禁用加密）
     * @param publicKey 加密 RSA 公钥（Base64 编码，null 禁用加密）
     */
    public DashScopeChatModel(
            String apiKey,
            String modelName,
            boolean stream,
            Boolean enableThinking,
            Boolean enableSearch,
            GenerateOptions defaultOptions,
            String baseUrl,
            Formatter<DashScopeMessage, DashScopeResponse, DashScopeRequest> formatter,
            HttpTransport httpTransport,
            String publicKeyId,
            String publicKey) {
        this(
                apiKey,
                modelName,
                stream,
                enableThinking,
                enableSearch,
                null,
                defaultOptions,
                baseUrl,
                formatter,
                httpTransport,
                publicKeyId,
                publicKey);
    }

    /**
     * 创建显式指定 API 类型的 DashScope 聊天模型实例。
     *
     * @param apiKey DashScope 认证 API 密钥
     * @param modelName 模型名称（例如 "qwen-max"、"qwen-vl-plus"）
     * @param stream 是否启用流式响应
     * @param enableThinking 是否启用思考模式（null 表示禁用）
     * @param enableSearch 是否启用搜索增强（null 表示禁用）
     * @param endpointType 端点类型（null 自动检测）
     * @param defaultOptions 默认生成选项（null 使用默认值）
     * @param baseUrl DashScope API 自定义基础 URL（null 使用默认值）
     * @param formatter 消息格式化器（null 使用默认 DashScope 格式化器）
     * @param httpTransport 自定义 HTTP 传输层（null 使用工厂默认值）
     * @param publicKeyId 加密 RSA 公钥 ID（null 禁用加密）
     * @param publicKey 加密 RSA 公钥（Base64 编码，null 禁用加密）
     */
    public DashScopeChatModel(
            String apiKey,
            String modelName,
            boolean stream,
            Boolean enableThinking,
            Boolean enableSearch,
            EndpointType endpointType,
            GenerateOptions defaultOptions,
            String baseUrl,
            Formatter<DashScopeMessage, DashScopeResponse, DashScopeRequest> formatter,
            HttpTransport httpTransport,
            String publicKeyId,
            String publicKey) {
        this(
                apiKey,
                modelName,
                stream,
                enableThinking,
                enableSearch,
                endpointType,
                defaultOptions,
                baseUrl,
                formatter,
                httpTransport,
                publicKeyId,
                publicKey,
                null);
    }

    /**
     * 创建显式指定 API 类型及额外多模态模型模式的 DashScope 聊天模型实例。
     *
     * @param apiKey DashScope 认证 API 密钥
     * @param modelName 模型名称（例如 "qwen-max"、"qwen-vl-plus"）
     * @param stream 是否启用流式响应
     * @param enableThinking 是否启用思考模式（null 表示禁用）
     * @param enableSearch 是否启用搜索增强（null 表示禁用）
     * @param endpointType 端点类型（null 自动检测）
     * @param defaultOptions 默认生成选项（null 使用默认值）
     * @param baseUrl DashScope API 自定义基础 URL（null 使用默认值）
     * @param formatter 消息格式化器（null 使用默认 DashScope 格式化器）
     * @param httpTransport 自定义 HTTP 传输层（null 使用工厂默认值）
     * @param publicKeyId 加密 RSA 公钥 ID（null 禁用加密）
     * @param publicKey 加密 RSA 公钥（Base64 编码，null 禁用加密）
     * @param multimodalModelPatterns 忽略大小写的子串模式，endpointType 为 AUTO 时扩展内置多模态检测（null 禁用）
     */
    public DashScopeChatModel(
            String apiKey,
            String modelName,
            boolean stream,
            Boolean enableThinking,
            Boolean enableSearch,
            EndpointType endpointType,
            GenerateOptions defaultOptions,
            String baseUrl,
            Formatter<DashScopeMessage, DashScopeResponse, DashScopeRequest> formatter,
            HttpTransport httpTransport,
            String publicKeyId,
            String publicKey,
            Collection<String> multimodalModelPatterns) {
        this.modelName = modelName;
        // DashScope 仅对部分开源思考模型拒绝
        // 非流式加思考模式的组合。
        this.stream = stream;
        this.enableThinking = enableThinking;
        this.enableSearch = enableSearch;
        this.endpointType = endpointType != null ? endpointType : EndpointType.AUTO;
        this.defaultOptions =
                defaultOptions != null ? defaultOptions : GenerateOptions.builder().build();
        this.formatter = formatter != null ? formatter : new DashScopeChatFormatter();

        // 使用提供的传输层或工厂默认值初始化 HTTP 客户端。
        HttpTransport transport =
                httpTransport != null ? httpTransport : HttpTransportFactory.getDefault();
        this.httpClient =
                DashScopeHttpClient.builder()
                        .transport(transport)
                        .apiKey(apiKey)
                        .baseUrl(baseUrl)
                        .publicKeyId(publicKeyId)
                        .publicKey(publicKey)
                        .multimodalModelPatterns(multimodalModelPatterns)
                        .build();
    }

    /**
     * 创建 DashScopeChatModel 构建器。
     *
     * @return 新的 Builder 实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 从 DashScope API 流式获取聊天补全响应。
     *
     * <p>根据模型名称自动路由到适当 API：
     * <ul>
     * <li>视觉模型（qvq* 或 *-vl*）→ MultiModal API。</li>
     * <li>文本模型 → Text Generation API。</li>
     * </ul>
     *
     * <p>支持通过 GenerateOptions 配置超时与重试。
     *
     * @param messages 发送给模型的 AgentScope 消息
     * @param tools 可选工具模式列表
     * @param options 可选生成参数（null 使用默认值）
     * @return 聊天响应的 Flux 流
     */
    @Override
    protected Flux<ChatResponse> doStream(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {

        if (log.isDebugEnabled()) {
            boolean useMultimodal = httpClient.requiresMultimodalApi(modelName, endpointType);
            log.debug(
                    "DashScope API call: model={}, endpointType={}, multimodal={}",
                    modelName,
                    endpointType,
                    useMultimodal);
        }

        Flux<ChatResponse> responseFlux = streamWithHttpClient(messages, tools, options);

        // 应用已配置的超时与重试。
        return ModelUtils.applyTimeoutAndRetry(
                responseFlux, options, defaultOptions, modelName, "dashscope");
    }

    /**
     * 通过 HTTP 客户端流式调用。
     *
     * <p>此方法通过所配置的 HTTP 传输层使用原生 DashScope HTTP API。
     */
    private Flux<ChatResponse> streamWithHttpClient(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        Instant start = Instant.now();
        boolean useMultimodal = httpClient.requiresMultimodalApi(modelName, endpointType);

        // 与 defaultOptions 合并，options 优先。
        GenerateOptions effectiveOptions = GenerateOptions.mergeOptions(options, defaultOptions);
        ToolChoice toolChoice = effectiveOptions.getToolChoice();

        // 使用格式化器转换消息。
        List<DashScopeMessage> dashScopeMessages;
        if (useMultimodal) {
            if (formatter instanceof DashScopeChatFormatter chatFormatter) {
                dashScopeMessages = chatFormatter.formatMultiModal(messages, effectiveOptions);
            } else if (formatter instanceof DashScopeMultiAgentFormatter multiAgentFormatter) {
                dashScopeMessages =
                        multiAgentFormatter.formatMultiModal(messages, effectiveOptions);
            } else {
                throw new IllegalStateException(
                        "DashScope vision models require DashScopeChatFormatter or"
                                + " DashScopeMultiAgentFormatter, but got: "
                                + formatter.getClass().getName());
            }
        } else {
            dashScopeMessages = formatter.format(messages, effectiveOptions);
        }

        // 使用格式化器构建请求。
        DashScopeRequest request;
        if (formatter instanceof DashScopeChatFormatter chatFormatter) {
            request =
                    chatFormatter.buildRequest(
                            modelName,
                            dashScopeMessages,
                            stream,
                            options,
                            defaultOptions,
                            tools,
                            toolChoice);
        } else if (formatter instanceof DashScopeMultiAgentFormatter multiAgentFormatter) {
            request = multiAgentFormatter.buildRequest(modelName, dashScopeMessages, stream);
            // 为多智能体格式化器手动应用选项与工具。
            multiAgentFormatter.applyOptions(request, options, defaultOptions);
            multiAgentFormatter.applyTools(request, tools);
            multiAgentFormatter.applyToolChoice(request, toolChoice);
        } else {
            throw new IllegalStateException(
                    "Unsupported formatter type: " + formatter.getClass().getName());
        }

        // 启用时应用思考模式。
        applyThinkingMode(request, effectiveOptions);

        // 设置用于端点选择的端点类型。
        request.setEndpointType(endpointType);

        if (stream) {
            // 流式模式。
            return httpClient.stream(
                            request,
                            effectiveOptions.getAdditionalHeaders(),
                            effectiveOptions.getAdditionalBodyParams(),
                            effectiveOptions.getAdditionalQueryParams())
                    .map(response -> formatter.parseResponse(response, start));
        } else {
            // 非流式模式。
            return Flux.defer(
                            () -> {
                                try {
                                    DashScopeResponse response =
                                            httpClient.call(
                                                    request,
                                                    effectiveOptions.getAdditionalHeaders(),
                                                    effectiveOptions.getAdditionalBodyParams(),
                                                    effectiveOptions.getAdditionalQueryParams());
                                    ChatResponse chatResponse =
                                            formatter.parseResponse(response, start);
                                    return Flux.just(chatResponse);
                                } catch (Exception e) {
                                    log.error("DashScope HTTP client error: {}", e.getMessage(), e);
                                    return Flux.error(
                                            new ModelException(
                                                    "DashScope API call failed: " + e.getMessage(),
                                                    e));
                                }
                            })
                    .subscribeOn(Schedulers.boundedElastic());
        }
    }

    /**
     * 启用时将思考模式配置应用到请求。
     */
    private void applyThinkingMode(DashScopeRequest request, GenerateOptions options) {
        // 校验思考配置。
        if (options.getThinkingBudget() != null && !Boolean.TRUE.equals(enableThinking)) {
            throw new IllegalStateException(
                    "thinkingBudget is set but enableThinking is not enabled. To use thinking mode"
                        + " with budget control, you must explicitly enable thinking by calling"
                        + " .enableThinking(true) on the model builder. Example:"
                        + " DashScopeChatModel.builder().enableThinking(true)"
                        + ".defaultOptions(GenerateOptions.builder().thinkingBudget(1000).build())");
        }

        if (enableThinking != null) {
            // 显式赋值思考模式。
            request.getParameters().setEnableThinking(enableThinking);
        }

        if (Boolean.TRUE.equals(enableThinking) && options.getThinkingBudget() != null) {
            request.getParameters().setThinkingBudget(options.getThinkingBudget());
        }

        if (Boolean.TRUE.equals(enableThinking)) {
            degradeForcedToolChoiceForThinkingMode(request);
        }

        // 搜索模式的模型专属设置。
        if (enableSearch != null) {
            // 显式赋值搜索模式。
            request.getParameters().setEnableSearch(enableSearch);
        }
    }

    /**
     * DashScope 思考模式不支持 required/object 等强制 tool_choice 值。
     * 将它们降级为 auto，使结构化输出重试能继续，而不会收到 400 响应。
     */
    private void degradeForcedToolChoiceForThinkingMode(DashScopeRequest request) {
        if (request == null || request.getParameters() == null) {
            return;
        }

        Object toolChoice = request.getParameters().getToolChoice();
        if (!(toolChoice instanceof Map<?, ?>)) {
            return;
        }

        log.warn(
                "DashScope thinking mode does not support forced tool_choice values; degrading to"
                        + " 'auto'");
        request.getParameters().setToolChoice("auto");
    }

    /**
     * 获取用于日志与标识的模型名称。
     *
     * @return 模型名称
     */
    @Override
    public String getModelName() {
        return modelName;
    }

    @Override
    public boolean supportsNativeStructuredOutput() {
        if (Boolean.TRUE.equals(enableThinking)) {
            return false;
        }
        return super.supportsNativeStructuredOutput();
    }

    public static class Builder {
        private String apiKey;
        private String modelName;
        private boolean stream = true;
        private Boolean enableThinking;
        private Boolean enableSearch;
        private EndpointType endpointType;
        private GenerateOptions defaultOptions = null;
        private String baseUrl;
        private Formatter<DashScopeMessage, DashScopeResponse, DashScopeRequest> formatter;
        private HttpTransport httpTransport;
        private boolean enableEncrypt = false;
        private ProxyConfig proxyConfig;
        private int contextWindowSize = -1;
        private Boolean nativeStructuredOutput;
        private Boolean nativeStructuredOutputWithTools;
        private Collection<String> multimodalModelPatterns;

        /**
         * 设置 DashScope 认证 API 密钥。
         *
         * @param apiKey API 密钥
         * @return 当前构建器实例
         */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /**
         * 设置模型名称。
         *
         * <p>endpointType 为 AUTO 时，模型名称决定使用哪个 API。
         *
         * @param modelName 模型名称（例如 "qwen-max"、"qwen-vl-plus"）
         * @return 当前构建器实例
         * @see DashScopeHttpClient#isMultimodalModel(String)
         */
        public Builder modelName(String modelName) {
            this.modelName = modelName;
            return this;
        }

        /**
         * 设置是否启用流式响应。
         *
         * @param stream true 启用流式，false 使用非流式
         * @return 当前构建器实例
         */
        public Builder stream(boolean stream) {
            this.stream = stream;
            return this;
        }

        /**
         * 设置是否启用思考模式。
         *
         * @param enableThinking true 启用，false 禁用，null 使用默认值
         * @return 当前构建器实例
         */
        public Builder enableThinking(Boolean enableThinking) {
            this.enableThinking = enableThinking;
            return this;
        }

        /**
         * 设置是否启用搜索增强。
         *
         * <p>启用后，模型可访问互联网搜索，提供更及时、准确的响应。
         *
         * @param enableSearch true 启用搜索，false 禁用，null 使用默认值（禁用）
         * @return 当前构建器实例
         */
        public Builder enableSearch(Boolean enableSearch) {
            this.enableSearch = enableSearch;
            return this;
        }

        /**
         * 设置路由使用的端点类型。
         *
         * @param endpointType 端点类型（null 表示 AUTO）
         * @return 当前构建器实例
         * @see EndpointType
         * @see DashScopeHttpClient#isMultimodalModel(String)
         */
        public Builder endpointType(EndpointType endpointType) {
            this.endpointType = endpointType;
            return this;
        }

        /**
         * 设置默认生成选项。
         *
         * @param options 默认选项（null 使用默认值）
         * @return 当前构建器实例
         */
        public Builder defaultOptions(GenerateOptions options) {
            this.defaultOptions = options;
            return this;
        }

        /**
         * 设置 DashScope API 的自定义基础 URL。
         *
         * @param baseUrl 基础 URL（null 使用默认值）
         * @return 当前构建器实例
         */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /**
         * 设置使用的消息格式化器。
         *
         * @param formatter 格式化器（null 使用默认 DashScope 格式化器）
         * @return 当前构建器实例
         */
        public Builder formatter(
                Formatter<DashScopeMessage, DashScopeResponse, DashScopeRequest> formatter) {
            this.formatter = formatter;
            return this;
        }

        /**
         * 设置使用的 HTTP 传输层。
         *
         * <p>未设置时使用 {@link HttpTransportFactory} 的默认传输层。多个模型可共享一个传输实例，便于资源管理。
         *
         * <p>示例：
         * <pre>{@code
         * HttpTransport custom = OkHttpTransport.builder()
         *     .config(HttpTransportConfig.builder()
         *         .connectTimeout(Duration.ofSeconds(30))
         *         .build())
         *     .build();
         *
         * DashScopeChatModel model = DashScopeChatModel.builder()
         *     .apiKey("xxx")
         *     .modelName("qwen-plus")
         *     .httpTransport(custom)
         *     .build();
         * }</pre>
         *
         * <p><b>代理配置说明：</b>{@code httpTransport} 是已构造完成的 HTTP 客户端实例，创建后不能修改其配置（包括代理）。若需代理，可采用以下方式：
         * <ul>
         * <li>仅需自定义代理时，调用 {@link #proxy(ProxyConfig)}，不要调用 {@code httpTransport()}。
         * <li>构建传输实例时，直接在其 {@link HttpTransportConfig} 中配置代理。
         * </ul>
         *
         * <p>若同时调用 {@code httpTransport()} 和 {@code proxy()}，则 {@code httpTransport()} 完全优先，忽略 {@code proxy()}，构建时记录警告。
         *
         * @param httpTransport HTTP 传输层（null 使用工厂默认值）
         * @return 当前构建器实例
         */
        public Builder httpTransport(HttpTransport httpTransport) {
            this.httpTransport = httpTransport;
            return this;
        }

        /**
         * 设置 HTTP 流量的代理配置。
         *
         * <p><b>与 {@link #httpTransport(HttpTransport)} 的关系：</b>{@code httpTransport} 是已构造完成的 HTTP 客户端，创建后无法修改配置。最终传输层按如下规则确定：
         * <ul>
         * <li>仅调用 {@code proxy()}：使用默认 {@link OkHttpTransport} 并应用指定代理。
         * <li>仅调用 {@code httpTransport()}：原样使用传入实例（代理需在其自身 {@link HttpTransportConfig} 中设置）。
         * <li>两者都调用：{@code httpTransport()} 完全优先，<b>忽略</b> {@code proxy()} 并记录警告。自定义传输层的代理应直接在其 {@link HttpTransportConfig} 中设置。
         * <li>两者都未调用：使用 {@link HttpTransportFactory} 默认传输层（无代理）。
         * </ul>
         *
         * @param proxyConfig 代理配置（参见 {@link ProxyConfig}）
         * @return 当前构建器实例
         */
        public Builder proxy(ProxyConfig proxyConfig) {
            this.proxyConfig = proxyConfig;
            return this;
        }

        /**
         * 设置是否启用加密。
         *
         * <p>启用时，模型自动从 DashScope API 获取最新 RSA 公钥，并按阿里云加密协议通过 RSA 密钥交换与 AES-GCM 加密请求和响应。此机制配合 TLS、令牌认证等措施，为企业安全配置提供支持。
         *
         * <p>若 build() 获取公钥失败，则抛出异常，避免创建加密配置错误的模型。
         *
         * <p>示例：
         * <pre>{@code
         * DashScopeChatModel model = DashScopeChatModel.builder()
         *     .apiKey("sk-xxx")
         *     .modelName("qwen-max")
         *     .enableEncrypt(true)
         *     .build();
         * }</pre>
         *
         * @param enableEncrypt true 启用加密（自动获取公钥），false 禁用
         * @return 当前构建器实例
         */
        public Builder enableEncrypt(boolean enableEncrypt) {
            this.enableEncrypt = enableEncrypt;
            return this;
        }

        /**
         * 覆盖推断的上下文窗口大小（token 数）。未设置时（{@code -1}），根据模型名称从内置表推断。
         */
        public Builder contextWindowSize(int contextWindowSize) {
            this.contextWindowSize = contextWindowSize;
            return this;
        }

        /**
         * 设置模型是否通过 {@code response_format} 的 {@code json_schema} 类型支持原生结构化输出。
         *
         * <p>默认为 {@code false}。此适配器默认按 DashScope 原生端点支持 {@code json_object}（自由 JSON）、不支持 {@code json_schema}（严格模式校验）处理。为 {@code false} 时，框架对结构化输出请求采用 {@code generate_response} 工具回退。
         *
         * <p>只有确认模型/端点在 {@code response_format} 中支持 {@code json_schema} 时，才设为 {@code true}。
         *
         * @param nativeStructuredOutput true 启用原生 json_schema 路径
         * @return 当前构建器实例
         */
        public Builder nativeStructuredOutput(boolean nativeStructuredOutput) {
            this.nativeStructuredOutput = nativeStructuredOutput;
            return this;
        }

        /**
         * 设置模型能否同时正确处理原生结构化输出（{@code response_format}）与工具调用。
         *
         * <p>默认 {@code false}（继承 {@link #nativeStructuredOutput(boolean)}）。仅在模型同时支持 {@code response_format} 和工具调用时设为 {@code true}。
         *
         * @param nativeStructuredOutputWithTools false 表示存在工具时使用回退路径
         * @return 当前构建器实例
         */
        public Builder nativeStructuredOutputWithTools(boolean nativeStructuredOutputWithTools) {
            this.nativeStructuredOutputWithTools = nativeStructuredOutputWithTools;
            return this;
        }

        /**
         * 设置额外的多模态模型检测子串模式，匹配时忽略大小写。
         *
         * <p>使用 {@link EndpointType#AUTO} 时，除了 {@link DashScopeHttpClient#isMultimodalModel(String)} 的内置模型名称规则，名称包含任一模式（忽略大小写）的模型也路由到多模态生成 API。这样可使用尚未被内置名单覆盖的多模态模型（例如 {@code "deepseek-v4.1"}），无需等待框架更新。
         *
         * <p>示例：
         * <pre>{@code
         * DashScopeChatModel model = DashScopeChatModel.builder()
         *     .apiKey("sk-xxx")
         *     .modelName("deepseek-v4.1")
         *     .multimodalModelPatterns(List.of("deepseek-v4"))
         *     .build();
         * }</pre>
         *
         * @param multimodalModelPatterns 忽略大小写的子串模式（可为 null）
         * @return 当前构建器实例
         * @see DashScopeHttpClient.Builder#multimodalModelPatterns(Collection)
         */
        public Builder multimodalModelPatterns(Collection<String> multimodalModelPatterns) {
            this.multimodalModelPatterns = multimodalModelPatterns;
            return this;
        }

        /**
         * 构建 DashScopeChatModel 实例。
         *
         * <p>确保 defaultOptions 应用正确的 executionConfig。
         *
         * <p>启用加密时，自动从 DashScope API 获取公钥；获取失败则抛出异常。
         *
         * <p><b>代理解析：</b>若同时设置 {@link #proxy(ProxyConfig)} 和 {@link #httpTransport(HttpTransport)}，则 {@code httpTransport()} 优先并记录警告。否则将代理应用到默认传输层，或使用工厂默认值。
         *
         * @return 配置完成的 DashScopeChatModel 实例
         * @throws DashScopeHttpClient.DashScopeHttpException 当启用加密但获取公钥失败时
         */
        public DashScopeChatModel build() {
            GenerateOptions effectiveOptions =
                    ModelUtils.ensureDefaultExecutionConfig(defaultOptions);

            String finalPublicKeyId = null;
            String finalPublicKey = null;

            HttpTransport transport = resolveTransport();

            if (enableEncrypt) {
                DashScopeHttpClient.PublicKeyResult publicKeyResult =
                        DashScopeHttpClient.fetchPublicKey(apiKey, baseUrl, transport);
                finalPublicKeyId = publicKeyResult.publicKeyId();
                finalPublicKey = publicKeyResult.publicKey();
            }

            DashScopeChatModel model =
                    new DashScopeChatModel(
                            apiKey,
                            modelName,
                            stream,
                            enableThinking,
                            enableSearch,
                            endpointType,
                            effectiveOptions,
                            baseUrl,
                            formatter,
                            transport,
                            finalPublicKeyId,
                            finalPublicKey,
                            multimodalModelPatterns);
            model.setContextWindowSize(
                    contextWindowSize >= 0
                            ? contextWindowSize
                            : ModelContextWindows.lookup(modelName, ModelContextWindows.DASHSCOPE));
            if (nativeStructuredOutput != null) {
                model.setNativeStructuredOutput(nativeStructuredOutput);
            }
            if (nativeStructuredOutputWithTools != null) {
                model.setNativeStructuredOutputWithTools(nativeStructuredOutputWithTools);
            }
            return model;
        }

        /**
         * 解析最终使用的 HttpTransport。
         *
         * <p>设置了 {@code httpTransport()} 时，它完全优先，忽略 {@code proxyConfig} 并记录警告。否则，若设置 {@code proxy()}，则创建带代理的默认传输层；均未设置时使用工厂默认值。
         *
         * @return 解析后的 HttpTransport
         */
        private HttpTransport resolveTransport() {
            if (httpTransport != null) {
                if (proxyConfig != null) {
                    log.warn(
                            "DashScopeChatModel: both proxy() and httpTransport() are set. "
                                    + "httpTransport() takes precedence, proxy() is ignored. "
                                    + "To configure proxy, use one of the following:\n"
                                    + "  1. Simple: only call proxy() without httpTransport()\n"
                                    + "  2. Advanced: set proxy in HttpTransportConfig when "
                                    + "building a custom HttpTransport");
                }
                return httpTransport;
            }

            if (proxyConfig != null) {
                // 仅调用 proxy()：使用配置代理的默认传输层。
                return OkHttpTransport.builder()
                        .config(HttpTransportConfig.builder().proxy(proxyConfig).build())
                        .build();
            }

            // 均未调用：使用工厂默认值。
            return HttpTransportFactory.getDefault();
        }
    }
}
