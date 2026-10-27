/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.extensions.model.ollama;

import io.agentscope.core.formatter.Formatter;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ModelUtils;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportConfig;
import io.agentscope.core.model.transport.HttpTransportFactory;
import io.agentscope.core.model.transport.OkHttpTransport;
import io.agentscope.core.model.transport.ProxyConfig;
import io.agentscope.extensions.model.ollama.dto.OllamaMessage;
import io.agentscope.extensions.model.ollama.dto.OllamaRequest;
import io.agentscope.extensions.model.ollama.dto.OllamaResponse;
import io.agentscope.extensions.model.ollama.formatter.OllamaChatFormatter;
import io.agentscope.extensions.model.ollama.formatter.OllamaMultiAgentFormatter;
import io.agentscope.extensions.model.ollama.options.OllamaOptions;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * 面向 Ollama 的 AgentScope ChatModel 实现。通过 HTTP API 集成本地 Ollama 实例。
 *
 * <p>此类为 Ollama 模型实现 AgentScope {@link ChatModelBase} 接口，
 * 支持同步与流式聊天、工具使用和自定义 Ollama 选项。
 *
 * <p>主要特性：
 * <ul>
 *   <li>同时支持 {@link GenerateOptions} 和 Ollama 专属的 {@link OllamaOptions}</li>
 *   <li>集成 AgentScope 消息格式化系统</li>
 *   <li>通过 Project Reactor 的 Flux 支持流式响应</li>
 *   <li>工具使用与函数调用能力</li>
 *   <li>可配置消息格式化器（OllamaChatFormatter、OllamaMultiAgentFormatter）</li>
 *   <li>自动超时与重试机制</li>
 * </ul>
 */
public class OllamaChatModel extends ChatModelBase {
    private static final Logger log = LoggerFactory.getLogger(OllamaChatModel.class);

    private final String modelName;
    private final boolean stream;
    private final OllamaHttpClient httpClient;
    private final OllamaOptions defaultOptions;
    private final Formatter<OllamaMessage, OllamaResponse, OllamaRequest> formatter;

    /**
     * 创建新的 OllamaChatModel。
     *
     * @param modelName 使用的模型名称（例如 "llama2"、"mistral"）
     * @param baseUrl Ollama 服务器基础 URL（例如 "http://localhost:11434"）
     * @param defaultOptions 默认配置选项
     * @param formatter 使用的消息格式化器
     * @param httpTransport 使用的 HTTP 传输
     */
    public OllamaChatModel(
            String modelName,
            String baseUrl,
            OllamaOptions defaultOptions,
            Formatter<OllamaMessage, OllamaResponse, OllamaRequest> formatter,
            HttpTransport httpTransport) {
        this(modelName, baseUrl, defaultOptions, formatter, httpTransport, true);
    }

    /**
     * 创建显式指定流式模式的 OllamaChatModel。
     */
    public OllamaChatModel(
            String modelName,
            String baseUrl,
            OllamaOptions defaultOptions,
            Formatter<OllamaMessage, OllamaResponse, OllamaRequest> formatter,
            HttpTransport httpTransport,
            boolean stream) {
        this.modelName = modelName;
        this.defaultOptions =
                defaultOptions != null ? defaultOptions : OllamaOptions.builder().build();
        this.formatter = formatter != null ? formatter : new OllamaChatFormatter();
        this.stream = stream;
        setNativeStructuredOutput(true);
        setNativeStructuredOutputWithTools(false);

        HttpTransport transport =
                httpTransport != null ? httpTransport : HttpTransportFactory.getDefault();
        this.httpClient = new OllamaHttpClient(transport, baseUrl);
    }

    /**
     * 使用默认设置创建新的 OllamaChatModel。
     *
     * @param modelName 使用的模型名称
     * @param baseUrl Ollama 服务器基础 URL
     * @param defaultOptions 默认配置选项
     */
    public OllamaChatModel(String modelName, String baseUrl, OllamaOptions defaultOptions) {
        this(modelName, baseUrl, defaultOptions, null, null);
    }

    /**
     * 为 OllamaChatModel 创建新构建器。
     *
     * @return 新的 Builder 实例
     */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String getModelName() {
        return this.modelName;
    }

    @Override
    public boolean supportsToolChoiceSpecific() {
        return true;
    }

    public boolean isStreaming() {
        return this.stream;
    }

    /**
     * 使用 Ollama 专属选项与模型聊天。
     * <p>此方法直接使用 Ollama 专属选项，不进行转换。
     *
     * @param messages 待发送的消息
     * @param options Ollama 专属选项
     * @return 聊天响应
     */
    public ChatResponse chat(List<Msg> messages, OllamaOptions options) {
        return streamWithHttpClient(messages, null, null, options, false).blockLast();
    }

    /**
     * 使用默认选项与模型聊天。
     *
     * @param messages 待发送的消息
     * @param options 生成选项
     * @return 聊天响应
     */
    public ChatResponse chat(List<Msg> messages, GenerateOptions options) {
        return this.chat(messages, OllamaOptions.fromGenerateOptions(options));
    }

    @Override
    protected Flux<ChatResponse> doStream(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        boolean effectiveStream =
                options != null && options.getStream() != null ? options.getStream() : this.stream;
        return streamWithHttpClient(
                messages,
                tools,
                options != null ? options.getToolChoice() : null,
                OllamaOptions.fromGenerateOptions(options),
                effectiveStream);
    }

    /**
     * 流式与非流式请求共用的逻辑。
     */
    private Flux<ChatResponse> streamWithHttpClient(
            List<Msg> messages,
            List<ToolSchema> tools,
            ToolChoice toolChoice,
            OllamaOptions options,
            boolean stream) {

        // 计算合并选项，供 ModelUtils.applyTimeoutAndRetry 使用
        OllamaOptions mergedOptions = this.defaultOptions.merge(options);

        List<OllamaMessage> formattedMessages = formatter.format(messages);

        OllamaRequest request;
        if (formatter instanceof OllamaChatFormatter chatFormatter) {
            request =
                    chatFormatter.buildRequest(
                            modelName,
                            formattedMessages,
                            stream,
                            options,
                            defaultOptions,
                            tools,
                            toolChoice);
        } else if (formatter instanceof OllamaMultiAgentFormatter multiAgentFormatter) {
            request =
                    multiAgentFormatter.buildRequest(
                            modelName,
                            formattedMessages,
                            stream,
                            options,
                            defaultOptions,
                            tools,
                            toolChoice);
        } else {
            throw new IllegalStateException(
                    "Unsupported formatter type: " + formatter.getClass().getName());
        }

        Flux<ChatResponse> responseFlux;
        if (stream) {
            responseFlux =
                    Flux.defer(
                            () -> {
                                // 【学习提示】稳定响应 ID 属于本次订阅；放在 defer 内使重新订阅
                                // 获得独立状态，同一流的后续分片则复用首个响应 ID。
                                final String[] finalStableId = new String[1];

                                return httpClient.stream(request)
                                        .map(
                                                response -> {
                                                    ChatResponse parsedResponse =
                                                            formatter.parseResponse(
                                                                    response, Instant.now());

                                                    if (finalStableId[0] == null) {
                                                        finalStableId[0] = parsedResponse.getId();
                                                        return parsedResponse;
                                                    }

                                                    return parsedResponse.withId(finalStableId[0]);
                                                });
                            });
        } else {
            // 【学习提示】非流式 HTTP 调用仍封装为延迟执行的 Flux，并调度到 boundedElastic，
            // 避免在响应式事件循环线程直接执行阻塞 I/O；两种模式最终共用超时／重试策略。
            responseFlux =
                    Flux.defer(
                                    () -> {
                                        Instant startTime = Instant.now();
                                        try {
                                            OllamaResponse response = httpClient.chat(request);
                                            return Flux.just(
                                                    formatter.parseResponse(response, startTime));
                                        } catch (Exception e) {
                                            return Flux.error(e);
                                        }
                                    })
                            .subscribeOn(Schedulers.boundedElastic());
        }

        return responseFlux.transform(
                flux ->
                        ModelUtils.applyTimeoutAndRetry(
                                flux,
                                mergedOptions.toGenerateOptions(),
                                defaultOptions.toGenerateOptions(),
                                modelName,
                                "ollama"));
    }

    public static class Builder {
        private String modelName;
        private String baseUrl;
        private OllamaOptions defaultOptions;
        private Formatter<OllamaMessage, OllamaResponse, OllamaRequest> formatter;
        private HttpTransport httpTransport;
        private ProxyConfig proxyConfig;
        private int contextWindowSize = -1;
        private boolean stream = true;
        private boolean nativeStructuredOutput = true;
        private boolean nativeStructuredOutputWithTools = false;

        public Builder modelName(String modelName) {
            this.modelName = modelName;
            return this;
        }

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /**
         * 设置是否启用流式处理。
         * <p>默认为 {@code true}
         */
        public Builder stream(boolean stream) {
            this.stream = stream;
            return this;
        }

        public Builder defaultOptions(OllamaOptions defaultOptions) {
            this.defaultOptions = defaultOptions;
            return this;
        }

        public Builder formatter(
                Formatter<OllamaMessage, OllamaResponse, OllamaRequest> formatter) {
            this.formatter = formatter;
            return this;
        }

        /**
         * 设置使用的 HTTP 传输。
         *
         * <p><b>代理配置说明：</b>{@code httpTransport} 是已构建完成的 HTTP 客户端实例，
         * 创建后不能再修改其配置（包括代理）。如果需要配置代理，可选择：
         * <ul>
         *   <li>仅需自定义代理时，使用 {@link #proxy(ProxyConfig)}，不调用 {@code httpTransport()}。
         *   <li>构建传输实例时，直接在其 {@link HttpTransportConfig} 中配置代理。
         * </ul>
         *
         * <p>同时调用 {@code httpTransport()} 和 {@code proxy()} 时，
         * {@code httpTransport()} 完全优先，{@code proxy()} 被忽略
         * （构建时记录警告）。
         *
         * @param httpTransport HTTP 传输（null 表示使用工厂默认值）
         * @return 此构建器实例
         */
        public Builder httpTransport(HttpTransport httpTransport) {
            this.httpTransport = httpTransport;
            return this;
        }

        /**
         * 设置 HTTP 流量的代理配置。
         *
         * <p><b>与 {@link #httpTransport(HttpTransport)} 的关系：</b>
         * {@code httpTransport} 是已构建完成的 HTTP 客户端实例，创建后不能再修改其配置。
         * 最终传输按以下规则确定：
         * <ul>
         *   <li>仅调用 {@code proxy()}：使用默认 {@link OkHttpTransport} 并应用指定代理。
         *   <li>仅调用 {@code httpTransport()}：原样使用提供的传输
         *       （代理必须在该传输自身的 {@link HttpTransportConfig} 中配置）。
         *   <li><i>两者都调用</i>：{@code httpTransport()} 完全优先。
         *       {@code proxy()} 设置被<b>忽略</b>并记录警告。
         *       自定义传输需要代理时，应直接在其 {@link HttpTransportConfig} 中设置。
         *   <li>两者都不调用：使用 {@link HttpTransportFactory} 的默认传输（无代理）。
         * </ul>
         *
         * @param proxyConfig 代理配置（参见 {@link ProxyConfig}）
         * @return 此构建器实例
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
         * 设置此模型是否通过 Ollama 的 {@code format} 字段支持原生结构化输出。
         *
         * <p>默认为 {@code true}。端点不支持 JSON Schema 约束输出时设为
         * {@code false}；智能体将改用合成的 {@code generate_response} 工具。
         *
         * @param nativeStructuredOutput false 表示禁用原生结构化输出
         * @return 此构建器实例
         */
        public Builder nativeStructuredOutput(boolean nativeStructuredOutput) {
            this.nativeStructuredOutput = nativeStructuredOutput;
            return this;
        }

        /**
         * 设置此模型是否能在工具调用同时处理原生结构化输出。
         *
         * <p>默认为 {@code false}，因为 Ollama 未在文档中保证组合使用
         * {@code format} 与 {@code tools} 的行为。对于已验证该组合的模型和端点，可设为 {@code true}。
         *
         * @param nativeStructuredOutputWithTools true 表示与工具一同使用原生输出
         * @return 此构建器实例
         */
        public Builder nativeStructuredOutputWithTools(boolean nativeStructuredOutputWithTools) {
            this.nativeStructuredOutputWithTools = nativeStructuredOutputWithTools;
            return this;
        }

        public OllamaChatModel build() {
            OllamaOptions finalOptions =
                    defaultOptions != null
                            ? defaultOptions.copy()
                            : OllamaOptions.builder().build();

            ExecutionConfig mergedConfig =
                    ExecutionConfig.mergeConfigs(
                            finalOptions.getExecutionConfig(), ExecutionConfig.MODEL_DEFAULTS);

            finalOptions.setExecutionConfig(mergedConfig);

            HttpTransport transport = resolveTransport();

            OllamaChatModel model =
                    new OllamaChatModel(
                            modelName, baseUrl, finalOptions, formatter, transport, stream);
            model.setNativeStructuredOutput(nativeStructuredOutput);
            model.setNativeStructuredOutputWithTools(nativeStructuredOutputWithTools);
            if (contextWindowSize >= 0) {
                model.setContextWindowSize(contextWindowSize);
            }
            return model;
        }

        /**
         * 解析最终使用的 HttpTransport。
         *
         * <p>设置 {@code httpTransport()} 时它完全优先，忽略 {@code proxyConfig}
         * 并记录警告。否则，设置 {@code proxy()} 时创建应用了代理的默认传输。
         * 两者都未设置时使用工厂默认值。
         *
         * @return 解析出的 HttpTransport
         */
        private HttpTransport resolveTransport() {
            if (httpTransport != null) {
                if (proxyConfig != null) {
                    log.warn(
                            "OllamaChatModel: both proxy() and httpTransport() are set. "
                                    + "httpTransport() takes precedence, proxy() is ignored. "
                                    + "To configure proxy, use one of the following:\n"
                                    + "  1. Simple: only call proxy() without httpTransport()\n"
                                    + "  2. Advanced: set proxy in HttpTransportConfig when "
                                    + "building a custom HttpTransport");
                }
                return httpTransport;
            }

            if (proxyConfig != null) {
                // 仅调用 proxy()：使用带代理的默认传输
                return OkHttpTransport.builder()
                        .config(HttpTransportConfig.builder().proxy(proxyConfig).build())
                        .build();
            }

            // 两者都未调用：使用工厂默认值
            return HttpTransportFactory.getDefault();
        }
    }
}
