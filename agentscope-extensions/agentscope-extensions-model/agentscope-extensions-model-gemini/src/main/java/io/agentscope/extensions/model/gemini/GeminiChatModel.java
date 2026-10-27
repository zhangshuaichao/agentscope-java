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
package io.agentscope.extensions.model.gemini;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.genai.Client;
import com.google.genai.ResponseStream;
import com.google.genai.types.ClientOptions;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.ProxyOptions;
import io.agentscope.core.formatter.Formatter;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ModelContextWindows;
import io.agentscope.core.model.ModelException;
import io.agentscope.core.model.ModelUtils;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.model.transport.ProxyConfig;
import io.agentscope.extensions.model.gemini.formatter.GeminiChatFormatter;
import io.agentscope.extensions.model.gemini.formatter.GeminiToolsHelper;
import io.agentscope.extensions.model.gemini.tool.GeminiServerTool;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * 使用官方 Google GenAI Java SDK 的 Gemini 聊天模型实现。
 *
 * <p>完整集成 Gemini 内容生成 API，包括工具调用和多智能体对话支持。
 *
 * <p><b>支持特性：</b>
 * <ul>
 * <li>流式与非流式文本生成</li>
 * <li>工具／函数调用</li>
 * <li>Gemini 服务端工具</li>
 * <li>合并历史的多智能体对话</li>
 * <li>视觉能力（图像、音频、视频）</li>
 * <li>思考模式（扩展推理）</li>
 * </ul>
 */
public class GeminiChatModel extends ChatModelBase {

    private static final Logger log = LoggerFactory.getLogger(GeminiChatModel.class);

    private final String apiKey;
    private final String modelName;
    private final boolean streamEnabled;
    private final String project;
    private final String location;
    private final Boolean vertexAI;
    private final HttpOptions httpOptions;
    private final GoogleCredentials credentials;
    private final ClientOptions clientOptions;
    private final Client client;
    private final GenerateOptions defaultOptions;
    private final Formatter<Content, GenerateContentResponse, GenerateContentConfig.Builder>
            formatter;
    private final List<GeminiServerTool> serverTools;

    @Override
    public boolean supportsToolChoiceSpecific() {
        return true;
    }

    /**
     * 创建新的 Gemini 聊天模型实例。
     *
     * @param apiKey 用于认证的 API 密钥（Gemini API）
     * @param baseUrl Gemini API 自定义基础 URL（null 使用默认值）
     * @param modelName 使用的模型名称（例如 "gemini-2.0-flash"、"gemini-1.5-pro"）
     * @param streamEnabled 是否启用流式处理
     * @param project Google Cloud 项目 ID（Vertex AI）
     * @param location Google Cloud 地域（Vertex AI，例如 "us-central1"）
     * @param vertexAI 是否使用 Vertex AI API（null 自动检测）
     * @param httpOptions 客户端 HTTP 选项
     * @param credentials Google 凭据（Vertex AI）
     * @param clientOptions API 客户端选项
     * @param defaultOptions 默认生成选项
     * @param formatter 使用的消息格式化器（null 使用默认 Gemini 格式化器）
     * @param serverTools 由模型提供商执行的 Gemini 内置工具
     */
    public GeminiChatModel(
            String apiKey,
            String baseUrl,
            String modelName,
            boolean streamEnabled,
            String project,
            String location,
            Boolean vertexAI,
            HttpOptions httpOptions,
            GoogleCredentials credentials,
            ClientOptions clientOptions,
            GenerateOptions defaultOptions,
            Formatter<Content, GenerateContentResponse, GenerateContentConfig.Builder> formatter,
            List<GeminiServerTool> serverTools) {
        this.apiKey = apiKey;
        this.modelName = Objects.requireNonNull(modelName, "Model name is required");
        this.streamEnabled = streamEnabled;
        this.project = project;
        this.location = location;
        this.vertexAI = vertexAI;
        this.httpOptions = resolveHttpOptions(baseUrl, httpOptions);
        this.credentials = credentials;
        this.clientOptions = clientOptions;
        this.defaultOptions =
                defaultOptions != null ? defaultOptions : GenerateOptions.builder().build();
        this.formatter = formatter != null ? formatter : new GeminiChatFormatter();
        this.serverTools = serverTools != null ? List.copyOf(serverTools) : List.of();

        // 初始化 Gemini 客户端
        Client.Builder clientBuilder = Client.builder();

        // 配置 API 密钥（用于 Gemini API）
        if (apiKey != null) {
            clientBuilder.apiKey(apiKey);
        }

        // 配置 Vertex AI 参数
        if (project != null) {
            clientBuilder.project(project);
        }
        if (location != null) {
            clientBuilder.location(location);
        }
        if (vertexAI != null) {
            clientBuilder.vertexAI(vertexAI);
        }
        if (credentials != null) {
            clientBuilder.credentials(credentials);
        }

        // 配置 HTTP 与客户端选项
        if (this.httpOptions != null) {
            clientBuilder.httpOptions(this.httpOptions);
        }
        if (clientOptions != null) {
            clientBuilder.clientOptions(clientOptions);
        }

        this.client = clientBuilder.build();
    }

    /**
     * 创建新的 Gemini 聊天模型实例。
     *
     * <p>此重载保留引入服务端工具之前的 API，不配置服务端工具。
     *
     * @param apiKey 用于认证的 API 密钥（Gemini API）
     * @param baseUrl Gemini API 自定义基础 URL（null 使用默认值）
     * @param modelName 使用的模型名称（例如 "gemini-2.0-flash"、"gemini-1.5-pro"）
     * @param streamEnabled 是否启用流式处理
     * @param project Google Cloud 项目 ID（Vertex AI）
     * @param location Google Cloud 地域（Vertex AI，例如 "us-central1"）
     * @param vertexAI 是否使用 Vertex AI API（null 自动检测）
     * @param httpOptions 客户端 HTTP 选项
     * @param credentials Google 凭据（Vertex AI）
     * @param clientOptions API 客户端选项
     * @param defaultOptions 默认生成选项
     * @param formatter 使用的消息格式化器（null 使用默认 Gemini 格式化器）
     */
    public GeminiChatModel(
            String apiKey,
            String baseUrl,
            String modelName,
            boolean streamEnabled,
            String project,
            String location,
            Boolean vertexAI,
            HttpOptions httpOptions,
            GoogleCredentials credentials,
            ClientOptions clientOptions,
            GenerateOptions defaultOptions,
            Formatter<Content, GenerateContentResponse, GenerateContentConfig.Builder> formatter) {
        this(
                apiKey,
                baseUrl,
                modelName,
                streamEnabled,
                project,
                location,
                vertexAI,
                httpOptions,
                credentials,
                clientOptions,
                defaultOptions,
                formatter,
                null);
    }

    /**
     * 创建使用默认端点配置的 Gemini 聊天模型实例。
     *
     * <p>此重载将 {@code baseUrl} 设为 {@code null}，由 SDK 根据提供的配置
     * 为 Gemini API 或 Vertex AI 使用默认端点行为。
     *
     * @param apiKey 用于认证的 API 密钥（Gemini API）
     * @param modelName 使用的模型名称（例如 "gemini-2.0-flash"、"gemini-1.5-pro"）
     * @param streamEnabled 是否启用流式处理
     * @param project Google Cloud 项目 ID（Vertex AI）
     * @param location Google Cloud 地域（Vertex AI，例如 "us-central1"）
     * @param vertexAI 是否使用 Vertex AI API（null 自动检测）
     * @param httpOptions 客户端 HTTP 选项
     * @param credentials Google 凭据（Vertex AI）
     * @param clientOptions API 客户端选项
     * @param defaultOptions 默认生成选项
     * @param formatter 使用的消息格式化器（null 使用默认 Gemini 格式化器）
     */
    public GeminiChatModel(
            String apiKey,
            String modelName,
            boolean streamEnabled,
            String project,
            String location,
            Boolean vertexAI,
            HttpOptions httpOptions,
            GoogleCredentials credentials,
            ClientOptions clientOptions,
            GenerateOptions defaultOptions,
            Formatter<Content, GenerateContentResponse, GenerateContentConfig.Builder> formatter) {
        this(
                apiKey,
                null,
                modelName,
                streamEnabled,
                project,
                location,
                vertexAI,
                httpOptions,
                credentials,
                clientOptions,
                defaultOptions,
                formatter,
                null);
    }

    private static HttpOptions resolveHttpOptions(String baseUrl, HttpOptions httpOptions) {
        if (baseUrl == null) {
            return httpOptions;
        }
        if (httpOptions == null) {
            return HttpOptions.builder().baseUrl(baseUrl).build();
        }
        return httpOptions.toBuilder().baseUrl(baseUrl).build();
    }

    /**
     * 构建提供商请求配置。
     */
    GenerateContentConfig buildGenerateContentConfig(
            List<ToolSchema> tools, GenerateOptions options) {
        GenerateContentConfig.Builder configBuilder = GenerateContentConfig.builder();

        if (tools != null && !tools.isEmpty()) {
            formatter.applyTools(configBuilder, tools);
            if (options != null && options.getToolChoice() != null) {
                formatter.applyToolChoice(configBuilder, options.getToolChoice());
            }
        }

        formatter.applyOptions(configBuilder, options, defaultOptions);
        return GeminiToolsHelper.mergeServerTools(configBuilder.build(), serverTools);
    }

    /**
     * 从 Gemini API 流式获取聊天补全响应。
     *
     * <p>此方法使用已配置的格式化器在内部处理消息格式。
     * 启用流式时，响应到达即增量返回；禁用流式时，返回单个完整响应。
     *
     * @param messages 发送给模型的 AgentScope 消息
     * @param tools 可选工具 schema 列表（无工具时为 null 或空）
     * @param options 可选生成选项（null 表示使用默认值）
     * @return 聊天响应的 Flux 流
     */
    @Override
    protected Flux<ChatResponse> doStream(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        return ModelUtils.applyTimeoutAndRetry(
                doStream0(messages, tools, options), options, defaultOptions, modelName, "gemini");
    }

    protected Flux<ChatResponse> doStream0(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        Instant startTime = Instant.now();
        log.debug(
                "Gemini stream: model={}, messages={}, tools_present={}, streaming={}",
                modelName,
                messages != null ? messages.size() : 0,
                tools != null && !tools.isEmpty(),
                streamEnabled);

        // 【学习提示】将格式化和 SDK 调用放入 defer，使订阅时才实际发起请求；
        // 外层 boundedElastic 承接 SDK 的阻塞调用，重试重新订阅时也会重新执行此请求路径。
        return Flux.defer(
                        () -> {
                            try {
                                // 使用格式化器将 Msg 转为 Gemini
                                // Content
                                List<Content> formattedMessages = formatter.format(messages);

                                GenerateContentConfig config =
                                        buildGenerateContentConfig(tools, options);

                                // 按流式标志选择 API
                                if (streamEnabled) {
                                    // 使用流式 API
                                    ResponseStream<GenerateContentResponse> responseStream =
                                            client.models.generateContentStream(
                                                    modelName, formattedMessages, config);

                                    // 【学习提示】SDK 返回可迭代且需要关闭的流；转换为 Flux 后，
                                    // doFinally 统一覆盖完成、错误和取消的释放路径，避免取消订阅时遗留连接。
                                    // 将 ResponseStream 转为 Flux
                                    return Flux.fromIterable(responseStream)
                                            .subscribeOn(Schedulers.boundedElastic())
                                            .map(
                                                    response ->
                                                            formatter.parseResponse(
                                                                    response, startTime))
                                            .doFinally(
                                                    signalType -> {
                                                        // 结束时
                                                        // 关闭流
                                                        try {
                                                            responseStream.close();
                                                        } catch (Exception e) {
                                                            log.warn(
                                                                    "Error closing"
                                                                            + " response"
                                                                            + " stream: {}",
                                                                    e.getMessage());
                                                        }
                                                    });
                                } else {
                                    // 使用非流式 API
                                    GenerateContentResponse response =
                                            client.models.generateContent(
                                                    modelName, formattedMessages, config);

                                    // 使用格式化器解析响应
                                    ChatResponse chatResponse =
                                            formatter.parseResponse(response, startTime);

                                    return Flux.just(chatResponse);
                                }

                            } catch (Exception e) {
                                log.error("Gemini API call failed: {}", e.getMessage(), e);
                                return Flux.error(
                                        new ModelException(
                                                "Gemini API call failed: " + e.getMessage(), e));
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public String getModelName() {
        return modelName;
    }

    /**
     * 关闭 Gemini 客户端。
     */
    public void close() {
        try {
            if (client != null) {
                client.close();
            }
        } catch (Exception e) {
            log.warn("Error closing Gemini client: {}", e.getMessage());
        }
    }

    /**
     * 为 GeminiChatModel 创建新构建器。
     *
     * @return 新的 Builder 实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 创建 GeminiChatModel 实例的构建器。
     */
    public static class Builder {
        private String apiKey;
        private String baseUrl;
        private String modelName = "gemini-2.5-flash";
        private boolean streamEnabled = true;
        private String project;
        private String location;
        private Boolean vertexAI;
        private HttpOptions httpOptions;
        private GoogleCredentials credentials;
        private ClientOptions clientOptions;
        private GenerateOptions defaultOptions;
        private Formatter<Content, GenerateContentResponse, GenerateContentConfig.Builder>
                formatter;
        private List<GeminiServerTool> serverTools;
        private ProxyConfig proxyConfig;
        private int contextWindowSize = -1;

        /**
         * 设置 API 密钥（用于 Gemini API）。
         *
         * @param apiKey Gemini API 密钥
         * @return 此构建器
         */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /**
         * 设置自定义基础 URL（用于 Gemini API）。
         *
         * @param baseUrl 自定义 Gemini API 基础 URL
         * @return 此构建器
         */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /**
         * 设置模型名称。
         *
         * @param modelName 模型名称（默认 "gemini-2.5-flash"）
         * @return 此构建器
         */
        public Builder modelName(String modelName) {
            this.modelName = modelName;
            return this;
        }

        /**
         * 设置是否启用流式处理。
         *
         * @param streamEnabled true 表示启用流式（默认 false）
         * @return 此构建器
         */
        public Builder streamEnabled(boolean streamEnabled) {
            this.streamEnabled = streamEnabled;
            return this;
        }

        /**
         * 设置 Google Cloud 项目 ID（用于 Vertex AI）。
         *
         * @param project 项目 ID
         * @return 此构建器
         */
        public Builder project(String project) {
            this.project = project;
            return this;
        }

        /**
         * 设置 Google Cloud 地域（用于 Vertex AI）。
         *
         * @param location 地域（例如 "us-central1"）
         * @return 此构建器
         */
        public Builder location(String location) {
            this.location = location;
            return this;
        }

        /**
         * 设置是否使用 Vertex AI API。
         *
         * @param vertexAI true 使用 Vertex AI，false 使用 Gemini API
         * @return 此构建器
         */
        public Builder vertexAI(boolean vertexAI) {
            this.vertexAI = vertexAI;
            return this;
        }

        /**
         * 设置客户端 HTTP 选项。
         *
         * @param httpOptions HTTP 选项
         * @return 此构建器
         */
        public Builder httpOptions(HttpOptions httpOptions) {
            this.httpOptions = httpOptions;
            return this;
        }

        /**
         * 设置 Google 凭据（用于 Vertex AI）。
         *
         * @param credentials Google 凭据
         * @return 此构建器
         */
        public Builder credentials(GoogleCredentials credentials) {
            this.credentials = credentials;
            return this;
        }

        /**
         * 设置客户端选项。
         *
         * @param clientOptions 客户端选项
         * @return 此构建器
         */
        public Builder clientOptions(ClientOptions clientOptions) {
            this.clientOptions = clientOptions;
            return this;
        }

        /**
         * 设置默认生成选项。
         *
         * @param defaultOptions 默认选项
         * @return 此构建器
         */
        public Builder defaultOptions(GenerateOptions defaultOptions) {
            this.defaultOptions = defaultOptions;
            return this;
        }

        /**
         * 设置格式化器。
         *
         * @param formatter 使用的格式化器
         * @return 此构建器
         */
        public Builder formatter(
                Formatter<Content, GenerateContentResponse, GenerateContentConfig.Builder>
                        formatter) {
            this.formatter = formatter;
            return this;
        }

        /**
         * 设置由模型提供商执行的 Gemini 内置工具。
         *
         * @param serverTools Gemini 服务端工具
         * @return 此构建器
         */
        public Builder serverTools(List<GeminiServerTool> serverTools) {
            this.serverTools = serverTools;
            return this;
        }

        /**
         * 设置 HTTP 流量的代理配置。
         *
         * <p><b>与 {@link #clientOptions(ClientOptions)} 的关系：</b>
         * 此方法执行<i>智能合并</i>而非简单覆盖。传给 Google SDK 客户端的最终
         * {@code ClientOptions} 按以下规则确定：
         * <ul>
         *   <li>仅调用 {@code proxy()}：创建仅包含代理设置的新 {@code ClientOptions}。
         *   <li>仅调用 {@code clientOptions()}：原样使用提供的选项。
         *   <li><i>两者都调用</i>：
         *       <ul>
         *         <li>提供的 {@code ClientOptions} <b>尚未</b>包含代理设置
         *             （{@code clientOptions.getProxyOptions().isEmpty()}）时，
         *             将 {@code proxy()} 的代理<b>合并到</b>现有选项（保留连接池等配置）。
         *         <li>提供的 {@code ClientOptions} <b>已有</b>代理设置时，已有设置优先。
         *             记录警告，并忽略 {@code proxy()} 设置以避免配置冲突。
         *       </ul>
         *   <li>两者都不调用：不应用代理配置。
         * </ul>
         *
         * <p><b>注意：</b>Google GenAI SDK <b>不支持</b> {@link ProxyConfig} 的
         * {@code nonProxyHosts} 字段，转换时会丢失它。需要排除主机时，可考虑
         * 将 {@code http.nonProxyHosts} 设置为 JVM 系统属性。
         *
         * @param proxyConfig 代理配置（参见 {@link ProxyConfig}）
         * @return 此构建器
         */
        public Builder proxy(ProxyConfig proxyConfig) {
            this.proxyConfig = proxyConfig;
            return this;
        }

        /**
         * 构建 GeminiChatModel 实例。
         *
         * <p><b>代理智能合并：</b>同时设置 {@link #proxy(ProxyConfig)} 和
         * {@link #clientOptions(ClientOptions)} 时，若提供的 {@code ClientOptions}
         * 尚无代理设置，就将 {@code proxy()} 的代理合并进去（保留连接池等配置）。
         * 如果 {@code ClientOptions} 已配置代理，则已有配置优先，并记录警告。
         *
         * @return 新的 GeminiChatModel
         */
        public Builder contextWindowSize(int contextWindowSize) {
            this.contextWindowSize = contextWindowSize;
            return this;
        }

        public GeminiChatModel build() {
            ClientOptions resolvedClientOptions = resolveClientOptions();
            GeminiChatModel model =
                    new GeminiChatModel(
                            apiKey,
                            baseUrl,
                            modelName,
                            streamEnabled,
                            project,
                            location,
                            vertexAI,
                            httpOptions,
                            credentials,
                            resolvedClientOptions,
                            ModelUtils.ensureDefaultExecutionConfig(defaultOptions),
                            formatter,
                            serverTools);
            model.setContextWindowSize(
                    contextWindowSize >= 0
                            ? contextWindowSize
                            : ModelContextWindows.lookup(modelName, ModelContextWindows.GEMINI));
            return model;
        }

        /**
         * 通过合并 proxy() 与 clientOptions() 解析最终 ClientOptions。
         *
         * @return 解析后的 ClientOptions
         */
        private ClientOptions resolveClientOptions() {
            if (proxyConfig == null) {
                // 未设置代理，原样返回 clientOptions
                return clientOptions;
            }

            ProxyOptions googleProxy = toGoogleProxy(proxyConfig);

            if (clientOptions == null) {
                // 仅调用 proxy()，创建带代理的新 ClientOptions
                return ClientOptions.builder().proxyOptions(googleProxy).build();
            }

            if (clientOptions.proxyOptions().isEmpty()) {
                // 已设置 clientOptions 但未配置代理：将代理合并进去
                return clientOptions.toBuilder().proxyOptions(googleProxy).build();
            }

            // 两者都有代理设置：clientOptions 优先，并警告用户
            log.warn(
                    "GeminiChatModel: both proxy() and clientOptions.proxyOptions() are set. "
                            + "clientOptions.proxyOptions() takes precedence, proxy() is ignored.");
            return clientOptions;
        }

        /**
         * 将 AgentScope ProxyConfig 转换为 Google SDK ProxyOptions。
         *
         * <p><b>注意：</b>Google GenAI SDK 不支持 ProxyConfig 中的
         * {@code nonProxyHosts} 字段，转换时会丢失。
         *
         * @param config AgentScope 代理配置
         * @return Google SDK ProxyOptions
         */
        private static ProxyOptions toGoogleProxy(ProxyConfig config) {
            ProxyOptions.Builder builder =
                    ProxyOptions.builder().host(config.getHost()).port(config.getPort());

            // 使用 ProxyType.Known 枚举映射代理类型
            switch (config.getType()) {
                case SOCKS4:
                case SOCKS5:
                    builder.type(com.google.genai.types.ProxyType.Known.SOCKS);
                    break;
                default:
                    builder.type(com.google.genai.types.ProxyType.Known.HTTP);
            }

            // 存在认证信息时添加认证
            if (config.hasAuthentication()) {
                builder.username(config.getUsername()).password(config.getPassword());
            }

            return builder.build();
        }
    }
}
