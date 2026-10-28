/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.studio;

import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.tracing.TracerRegistry;
import io.agentscope.core.tracing.telemetry.TelemetryTracer;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * AgentScope Studio 集成的中心管理器。
 * <p>提供简化的初始化及客户端访问 API，管理到 Studio 的 HTTP 与 WebSocket 连接生命周期。
 * <p>使用示例：
 * <pre>{@code
 * // 初始化 Studio 集成
 * StudioManager.init()
 *     .studioUrl("http://localhost:8000")
 *     .project("MyProject")
 *     .runName("experiment_001")
 *     .initialize()
 *     .block();
 *
 * // 创建带 Studio 钩子的智能体
 * ReActAgent agent = ReActAgent.builder()
 *     .name("Assistant")
 *     .model(model)
 *     .hook(new StudioMessageHook(StudioManager.getClient()))
 *     .build();
 *
 * // 智能体调用自动向 Studio 发送消息
 * agent.call(msg).block();
 * }</pre>
 */
public class StudioManager {
    private static final Logger logger = LoggerFactory.getLogger(StudioManager.class);

    private static volatile StudioConfig config;
    private static volatile StudioClient client;
    private static volatile StudioWebSocketClient wsClient;

    private StudioManager() {
        // 私有构造函数，禁止实例化
    }

    /**
     * 创建初始化构建器。
     * @return 配置 Studio 集成的新构建器
     */
    public static Builder init() {
        return new Builder();
    }

    /**
     * 获取 Studio HTTP 客户端。
     * @return HTTP 客户端，未初始化时为 null
     */
    public static StudioClient getClient() {
        return client;
    }

    /**
     * 获取 Studio WebSocket 客户端。
     * @return WebSocket 客户端，未初始化时为 null
     */
    public static StudioWebSocketClient getWebSocketClient() {
        return wsClient;
    }

    /**
     * 获取 Studio 配置。
     * @return Studio 配置，未初始化时为 null
     */
    public static StudioConfig getConfig() {
        return config;
    }

    /**
     * 检查 Studio 集成是否已初始化。
     * @return 已初始化为 true，否则为 false
     */
    public static boolean isInitialized() {
        return config != null && client != null;
    }

    /**
     * 关闭 Studio 集成并释放资源。
     */
    public static void shutdown() {
        if (client != null) {
            client.shutdown();
        }
        if (wsClient != null) {
            wsClient.close();
        }
        config = null;
        client = null;
        wsClient = null;
        TracerRegistry.resetToNoop();
    }

    /**
     * 配置并初始化 Studio 集成的构建器。
     */
    public static class Builder {
        private final StudioConfig.Builder configBuilder;

        private Builder() {
            this.configBuilder = StudioConfig.builder();
        }

        /**
         * 设置 Studio URL。
         * @param studioUrl Studio 服务 URL，例如 "http://localhost:8000"
         * @return 当前构建器
         */
        public Builder studioUrl(String studioUrl) {
            configBuilder.studioUrl(studioUrl);
            return this;
        }

        /**
         * 设置跟踪 URL，默认 {studioUrl}/v1/traces。
         * @param tracingUrl OpenTelemetry 跟踪端点 URL
         * @return 当前构建器
         */
        public Builder tracingUrl(String tracingUrl) {
            configBuilder.tracingUrl(tracingUrl);
            return this;
        }

        /**
         * 设置项目名称。
         * @param project 项目名称
         * @return 当前构建器
         */
        public Builder project(String project) {
            configBuilder.project(project);
            return this;
        }

        /**
         * 设置运行名称。
         * @param runName 运行名称
         * @return 当前构建器
         */
        public Builder runName(String runName) {
            configBuilder.runName(runName);
            return this;
        }

        /**
         * 设置 HTTP 请求最大重试次数。
         * @param maxRetries 最大重试次数，默认 3
         * @return 当前构建器
         */
        public Builder maxRetries(int maxRetries) {
            configBuilder.maxRetries(maxRetries);
            return this;
        }

        /**
         * 设置 WebSocket 重连次数。
         * @param reconnectAttempts 最大重连次数，默认 3
         * @return 当前构建器
         */
        public Builder reconnectAttempts(int reconnectAttempts) {
            configBuilder.reconnectAttempts(reconnectAttempts);
            return this;
        }

        /**
         * 初始化 Studio 集成。
         * <ol><li>构建配置。</li><li>创建 HTTP 与 WebSocket 客户端。</li><li>向 Studio 注册运行。</li><li>建立 WebSocket 连接。</li></ol>
         * @return 初始化成功时完成的 Mono
         */
        public Mono<Void> initialize() {
            return Mono.fromRunnable(
                            () -> {
                                // 构建配置
                                config = configBuilder.build();

                                // 创建客户端
                                client = new StudioClient(config);
                                wsClient = new StudioWebSocketClient(config);
                            })
                    .then(
                            Mono.defer(
                                    () ->
                                            // 向 Studio 注册运行
                                            // 【学习提示】先注册运行，再连接 WebSocket；then 串联的是完成信号，错误进入统一清理，不会继续安装后续钩子。
                                            client.registerRun()
                                                    .doOnSuccess(
                                                            v ->
                                                                    logger.info(
                                                                            "Registered run with"
                                                                                    + " Studio: {}",
                                                                            config.getRunId()))))
                    .then(
                            Mono.defer(
                                    () ->
                                            // 连接 WebSocket
                                            wsClient.connect()
                                                    .doOnSuccess(
                                                            v ->
                                                                    logger.info(
                                                                            "Studio integration"
                                                                                + " initialized"
                                                                                + " successfully"))))
                    .doOnSuccess(
                            (v) -> {
                                AgentBase.addSystemHook(
                                        new StudioMessageHook(StudioManager.getClient()));
                            })
                    .doOnSuccess(
                            (v) -> {
                                String traceEndpoint =
                                        URI.create(config.getStudioUrl()).getPath() + "/v1/traces";
                                if (config.getTracingUrl() != null) {
                                    traceEndpoint = config.getTracingUrl();
                                }
                                TracerRegistry.register(
                                        TelemetryTracer.builder().endpoint(traceEndpoint).build());
                            })
                    .doOnError(e -> logger.error("Failed to initialize Studio", e))
                    .onErrorResume(
                            e -> {
                                // 出错时清理
                                shutdown();
                                return Mono.error(e);
                            });
        }
    }
}
