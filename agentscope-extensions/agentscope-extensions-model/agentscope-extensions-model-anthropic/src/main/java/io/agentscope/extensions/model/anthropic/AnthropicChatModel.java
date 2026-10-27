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

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.RawMessageStreamEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ModelContextWindows;
import io.agentscope.core.model.ModelException;
import io.agentscope.core.model.ModelUtils;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.model.transport.ProxyConfig;
import io.agentscope.extensions.model.anthropic.formatter.AnthropicBaseFormatter;
import io.agentscope.extensions.model.anthropic.formatter.AnthropicChatFormatter;
import io.agentscope.extensions.model.anthropic.formatter.AnthropicResponseParser;
import io.agentscope.extensions.model.anthropic.tool.AnthropicServerTool;
import java.net.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 使用官方 Anthropic Java SDK 的聊天模型实现。
 *
 * <p>集成 Anthropic Messages API，包含工具调用、流式输出和扩展思考能力。
 *
 * <p>重要说明：
 * <ul><li>系统消息通过 system 参数处理，不作为普通消息发送。</li>
 * <li>工具结果必须放在独立用户消息中。</li><li>支持 Claude 模型，如 claude-3-*、claude-sonnet-*。</li></ul>
 */
public class AnthropicChatModel extends ChatModelBase {

    private static final Logger log = LoggerFactory.getLogger(AnthropicChatModel.class);

    private final String baseUrl;
    private final String apiKey;
    private final String modelName;
    private final boolean streamEnabled;
    private final AnthropicClient client;
    private final GenerateOptions defaultOptions;
    private final AnthropicBaseFormatter formatter;
    private final List<AnthropicServerTool> serverTools;

    @Override
    public boolean supportsToolChoiceSpecific() {
        return true;
    }

    public AnthropicChatModel(
            String baseUrl,
            String apiKey,
            String modelName,
            boolean streamEnabled,
            GenerateOptions defaultOptions,
            AnthropicBaseFormatter formatter,
            ProxyConfig proxyConfig,
            String cacheTtl) {
        this(
                baseUrl,
                apiKey,
                null,
                modelName,
                streamEnabled,
                defaultOptions,
                formatter,
                proxyConfig,
                null,
                cacheTtl);
    }

    /**
     * 创建启用内置服务端工具的 Anthropic 聊天模型实例。
     *
     * @param baseUrl Anthropic API 基础 URL，null 使用默认值
     * @param apiKey 认证 API 密钥，null 从 ANTHROPIC_API_KEY 环境变量加载
     * @param modelName 使用的模型名称
     * @param streamEnabled 是否启用流式输出
     * @param defaultOptions 默认生成选项
     * @param formatter 消息格式化器，null 使用默认值
     * @param proxyConfig 代理配置，null 表示无代理
     * @param serverTools 要启用的 Anthropic 内置服务端工具
     * @param cacheTtl 提示词缓存标记的 TTL，null 默认 5m
     */
    public AnthropicChatModel(
            String baseUrl,
            String apiKey,
            String modelName,
            boolean streamEnabled,
            GenerateOptions defaultOptions,
            AnthropicBaseFormatter formatter,
            ProxyConfig proxyConfig,
            List<AnthropicServerTool> serverTools,
            String cacheTtl) {
        this(
                baseUrl,
                apiKey,
                null,
                modelName,
                streamEnabled,
                defaultOptions,
                formatter,
                proxyConfig,
                serverTools,
                cacheTtl);
    }

    /**
     * 创建可选使用 Bearer 令牌认证的 Anthropic 聊天模型。
     *
     * <p>{@code apiKey} 与 {@code authToken} 互斥。
     *
     * @param baseUrl Anthropic API 基础 URL，null 使用默认值
     * @param apiKey 认证 API 密钥，null 表示省略
     * @param authToken 不带 {@code Bearer } 前缀的令牌，null 表示省略
     * @param modelName 使用的模型名称
     * @param streamEnabled 是否启用流式输出
     * @param defaultOptions 默认生成选项
     * @param formatter 消息格式化器，null 使用默认格式化器
     * @param proxyConfig 代理配置，null 表示无代理
     * @param cacheTtl 提示词缓存标记的 TTL，null 默认 5m
     * @throws IllegalArgumentException 同时配置 API 密钥与 Bearer 令牌时抛出
     */
    public AnthropicChatModel(
            String baseUrl,
            String apiKey,
            String authToken,
            String modelName,
            boolean streamEnabled,
            GenerateOptions defaultOptions,
            AnthropicBaseFormatter formatter,
            ProxyConfig proxyConfig,
            String cacheTtl) {
        this(
                baseUrl,
                apiKey,
                authToken,
                modelName,
                streamEnabled,
                defaultOptions,
                formatter,
                proxyConfig,
                null,
                cacheTtl);
    }

    /**
     * 创建可选使用 Bearer 认证及内置服务端工具的 Anthropic 聊天模型。
     *
     * <p>{@code apiKey} 与 {@code authToken} 互斥。
     *
     * @param baseUrl Anthropic API 基础 URL，null 使用默认值
     * @param apiKey 认证 API 密钥，null 表示省略
     * @param authToken 不带 {@code Bearer } 前缀的令牌，null 表示省略
     * @param modelName 使用的模型名称
     * @param streamEnabled 是否启用流式输出
     * @param defaultOptions 默认生成选项
     * @param formatter 消息格式化器，null 使用默认格式化器
     * @param proxyConfig 代理配置，null 表示无代理
     * @param serverTools 要启用的 Anthropic 内置服务端工具
     * @param cacheTtl 提示词缓存标记的 TTL，null 默认 5m
     * @throws IllegalArgumentException 同时配置 API 密钥与 Bearer 令牌时抛出
     */
    public AnthropicChatModel(
            String baseUrl,
            String apiKey,
            String authToken,
            String modelName,
            boolean streamEnabled,
            GenerateOptions defaultOptions,
            AnthropicBaseFormatter formatter,
            ProxyConfig proxyConfig,
            List<AnthropicServerTool> serverTools,
            String cacheTtl) {
        if (apiKey != null && authToken != null) {
            throw new IllegalArgumentException(
                    "apiKey and authToken are mutually exclusive; configure only one credential");
        }
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.streamEnabled = streamEnabled;
        this.defaultOptions =
                defaultOptions != null ? defaultOptions : GenerateOptions.builder().build();
        this.formatter = formatter != null ? formatter : new AnthropicChatFormatter();
        this.serverTools = serverTools != null ? List.copyOf(serverTools) : List.of();
        this.formatter.cacheTtl(cacheTtl);

        // 初始化 Anthropic 客户端。
        AnthropicOkHttpClient.Builder clientBuilder = AnthropicOkHttpClient.builder();

        if (apiKey != null) {
            clientBuilder.apiKey(apiKey);
        }

        if (authToken != null) {
            clientBuilder.authToken(authToken);
        }

        if (baseUrl != null) {
            clientBuilder.baseUrl(baseUrl);
        }

        // 提供代理配置时应用代理。
        if (proxyConfig != null) {
            Proxy proxy = proxyConfig.toJavaProxy();
            clientBuilder.proxy(proxy);
        }

        this.client = clientBuilder.build();
    }

    /**
     * 从 Anthropic API 获取聊天补全响应流。
     *
     * <p>内部使用已配置格式化器转换消息，并依据 streamEnabled 支持流式与非流式模式。
     *
     * <p>通过 GenerateOptions 支持超时与重试配置。
     *
     * @param messages 发送给模型的 AgentScope 消息
     * @param tools 可选工具模式列表，无工具时为 null 或空
     * @param options 可选生成选项，null 使用默认值
     * @return 聊天响应 Flux 流
     */
    @Override
    protected Flux<ChatResponse> doStream(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        Instant startTime = Instant.now();
        log.debug(
                "Anthropic stream: model={}, messages={}, tools_present={}",
                modelName,
                messages != null ? messages.size() : 0,
                tools != null && !tools.isEmpty());

        Flux<ChatResponse> responseFlux =
                Flux.defer(
                        () -> {
                            try {
                                // 构建消息创建参数。
                                MessageCreateParams.Builder paramsBuilder =
                                        MessageCreateParams.builder()
                                                .model(modelName)
                                                .maxTokens(4096);

                                GenerateOptions effectiveOptions =
                                        GenerateOptions.mergeOptions(options, defaultOptions);
                                boolean cacheControlEnabled =
                                        effectiveOptions != null
                                                && Boolean.TRUE.equals(
                                                        effectiveOptions.getCacheControl());

                                // 提取并应用系统消息，
                                // 满足 Anthropic 特定要求；
                                // 启用提示词缓存时添加 cache_control。
                                formatter.applySystemMessage(
                                        paramsBuilder, messages, cacheControlEnabled);

                                // 开头的系统消息已经应用到上面的 system
                                // 字段，因此应从消息体排除，避免系统
                                // 提示词发送两次。
                                List<Msg> conversationMessages = messages;
                                if (messages != null
                                        && !messages.isEmpty()
                                        && messages.get(0).getRole() == MsgRole.SYSTEM) {
                                    conversationMessages = messages.subList(1, messages.size());
                                }

                                // 使用格式化器将 Msg 转换为 Anthropic
                                // MessageParam 消息参数。
                                List<MessageParam> formattedMessages =
                                        formatter.format(conversationMessages);

                                // 应用自动缓存控制策略，
                                // 标记最后一条消息以缓存对话前缀。
                                if (cacheControlEnabled) {
                                    formattedMessages =
                                            formatter.applyCacheControl(formattedMessages);
                                }

                                for (MessageParam param : formattedMessages) {
                                    paramsBuilder.addMessage(param);
                                }

                                // 通过格式化器应用生成选项。
                                formatter.applyOptions(paramsBuilder, options, defaultOptions);

                                // 统一添加客户端与服务端工具，使名称
                                // 校验、工具选择及并行调用设置都覆盖两者。
                                formatter.applyTools(paramsBuilder, tools, serverTools);

                                // 创建请求。
                                MessageCreateParams params = paramsBuilder.build();

                                if (streamEnabled) {
                                    // 执行流式 API 调用。
                                    StreamResponse<RawMessageStreamEvent> streamResponse =
                                            client.messages().createStreaming(params);

                                    // 将 SDK Stream 转换为 Flux。
                                    return AnthropicResponseParser.parseStreamEvents(
                                                    Flux.fromStream(streamResponse.stream())
                                                            .subscribeOn(
                                                                    Schedulers.boundedElastic()),
                                                    startTime)
                                            .doFinally(
                                                    signalType -> {
                                                        try {
                                                            streamResponse.close();
                                                        } catch (Exception e) {
                                                            log.debug(
                                                                    "Error closing stream"
                                                                            + " response",
                                                                    e);
                                                        }
                                                    });
                                } else {
                                    // 非流式模式只执行一次调用，
                                    // 通过 CompletableFuture 返回。
                                    return Mono.fromFuture(client.async().messages().create(params))
                                            .map(
                                                    message ->
                                                            formatter.parseResponse(
                                                                    message, startTime))
                                            .flux();
                                }
                            } catch (Exception e) {
                                return Flux.error(
                                        new ModelException(
                                                "Failed to stream Anthropic API: " + e.getMessage(),
                                                e,
                                                modelName,
                                                "anthropic"));
                            }
                        });

        // 若已配置则应用超时与重试。
        return ModelUtils.applyTimeoutAndRetry(
                responseFlux, options, defaultOptions, modelName, "anthropic");
    }

    /**
     * 获取用于日志和标识的模型名称。
     *
     * @return 模型名称
     */
    @Override
    public String getModelName() {
        return modelName;
    }

    /**
     * 创建用于构建 AnthropicChatModel 的构建器。
     *
     * @return 新构建器
     */
    public static Builder builder() {
        return new Builder();
    }

    /** AnthropicChatModel 的构建器。 */
    public static class Builder {
        private String baseUrl;
        private String apiKey;
        private String authToken;
        private String modelName = "claude-sonnet-5-5";
        private boolean streamEnabled = true;
        private GenerateOptions defaultOptions;
        private AnthropicBaseFormatter formatter;
        private ProxyConfig proxyConfig;
        private int contextWindowSize = -1;
        private final List<AnthropicServerTool> serverTools = new ArrayList<>();
        private String cacheTtl;

        /**
         * 设置 Anthropic API 基础 URL。
         *
         * @param baseUrl 基础 URL
         * @return 当前构建器
         */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /**
         * 设置认证 API 密钥。
         *
         * @param apiKey API 密钥
         * @return 当前构建器
         */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /**
         * 设置用于 Anthropic 兼容网关认证的 Bearer 令牌。
         *
         * <p>SDK 会将 {@code Bearer } 前缀添加到 {@code Authorization} 请求头。
         * 同时配置 API 密钥和 Bearer 令牌会导致模型构建失败。
         *
         * @param authToken 不带 {@code Bearer } 前缀的令牌，null 表示省略
         * @return 当前构建器
         */
        public Builder authToken(String authToken) {
            this.authToken = authToken;
            return this;
        }

        /**
         * 设置模型名称。
         *
         * @param modelName 模型名称
         * @return 当前构建器
         */
        public Builder modelName(String modelName) {
            this.modelName = modelName;
            return this;
        }

        /**
         * 启用或禁用流式输出。
         *
         * @param streamEnabled true 表示启用流式
         * @return 当前构建器
         */
        public Builder stream(boolean streamEnabled) {
            this.streamEnabled = streamEnabled;
            return this;
        }

        /**
         * 设置默认生成选项。
         *
         * @param defaultOptions 默认选项
         * @return 当前构建器
         */
        public Builder defaultOptions(GenerateOptions defaultOptions) {
            this.defaultOptions = defaultOptions;
            return this;
        }

        /**
         * 设置自定义格式化器。
         *
         * @param formatter 格式化器
         * @return 当前构建器
         */
        public Builder formatter(AnthropicBaseFormatter formatter) {
            this.formatter = formatter;
            return this;
        }

        /**
         * 设置 HTTP 流量的代理配置。
         *
         * <p>此处使用的 Anthropic Java SDK 不支持代理认证或 {@code nonProxyHosts}。
         * 仅使用 {@link ProxyConfig#toJavaProxy()}；{@code username}、{@code password} 和
         * {@code nonProxyHosts} 即使在 {@link ProxyConfig} 中设置，也不会生效。
         *
         * @param proxyConfig 代理配置，参见 {@link ProxyConfig}
         * @return 当前构建器
         */
        public Builder proxy(ProxyConfig proxyConfig) {
            this.proxyConfig = proxyConfig;
            return this;
        }

        /**
         * 设置提示词缓存标记的 TTL，例如 {@code "1h"}。
         *
         * @param cacheTtl 缓存 TTL 字符串，null 默认 5m ephemeral
         * @return 当前构建器
         */
        public Builder cacheTtl(String cacheTtl) {
            this.cacheTtl = cacheTtl;
            return this;
        }

        public Builder contextWindowSize(int contextWindowSize) {
            this.contextWindowSize = contextWindowSize;
            return this;
        }

        /**
         * 添加每次请求都启用的 Anthropic 内置服务端工具，例如 Web 搜索。
         * 服务端工具在单次模型调用内由 Anthropic 基础设施执行，独立于工具集中注册的客户端工具。
         *
         * @param serverTool 服务端工具定义
         * @return 当前构建器
         */
        public Builder addServerTool(AnthropicServerTool serverTool) {
            if (serverTool != null) {
                this.serverTools.add(serverTool);
            }
            return this;
        }

        /**
         * 构建 AnthropicChatModel 实例。
         *
         * @return 新的 AnthropicChatModel
         */
        public AnthropicChatModel build() {
            AnthropicChatModel model =
                    new AnthropicChatModel(
                            baseUrl,
                            apiKey,
                            authToken,
                            modelName,
                            streamEnabled,
                            defaultOptions,
                            formatter,
                            proxyConfig,
                            serverTools,
                            cacheTtl);
            model.setContextWindowSize(
                    contextWindowSize >= 0
                            ? contextWindowSize
                            : ModelContextWindows.lookup(modelName, ModelContextWindows.ANTHROPIC));
            return model;
        }
    }
}
