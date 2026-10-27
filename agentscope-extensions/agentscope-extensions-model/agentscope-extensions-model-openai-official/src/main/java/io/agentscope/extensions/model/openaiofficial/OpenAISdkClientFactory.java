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
package io.agentscope.extensions.model.openaiofficial;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.http.ProxyAuthenticator;
import io.agentscope.core.model.transport.ProxyConfig;
import io.agentscope.core.model.transport.ProxyType;
import java.net.Proxy;
import java.net.URI;
import java.time.Duration;
import java.util.Map;

/**
 * 构建官方 OpenAI Java SDK 客户端的工厂。
 *
 * <p>这是生产客户端创建的唯一入口。测试绕过此工厂，
 * 通过 {@code OpenAIResponsesChatModel} 的包可见构造器注入假的／模拟的
 * {@link OpenAIClient}。
 *
 * <p>关键行为：
 * <ul>
 *   <li>{@code maxRetries = 0}：由 AgentScope 负责重试。</li>
 *   <li>构建器级附加请求头注入 {@code ClientOptions}，在所有请求中保持不变。
 *       不支持单次请求级请求头。</li>
 *   <li>{@code apiKey} 缺失或空白：快速失败，抛出不可重试的
 *       {@link OpenAIOfficialModelException}。调用方（SPI／Builder）应在调用工厂前
 *       解析 {@code OPENAI_API_KEY} 回退值。</li>
 *   <li>{@code baseUrl} 为 null／空白：SDK 默认使用 {@code https://api.openai.com/v1}。</li>
 * </ul>
 */
final class OpenAISdkClientFactory {

    private OpenAISdkClientFactory() {}

    /**
     * 根据已解析配置创建 {@link OpenAIClient}。
     *
     * @param apiKey API 密钥（必须非空白；调用方在调用前解析 {@code OPENAI_API_KEY} 回退值）
     * @param baseUrl 基础 URL，null／空白使用 SDK 默认值
     * @param additionalHeaders 注入客户端的构建器级请求头（可以为 null）
     * @param timeout 请求超时，null 使用 SDK 默认值
     * @param proxyConfig 代理配置，null 使用 SDK 默认值
     * @return 配置好的 {@link OpenAIClient}，其 {@code maxRetries=0}
     * @throws OpenAIOfficialModelException apiKey 缺失／空白或 SDK 构造失败时抛出
     */
    static OpenAIClient createClient(
            String apiKey,
            String baseUrl,
            Map<String, String> additionalHeaders,
            Duration timeout,
            ProxyConfig proxyConfig) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new OpenAIOfficialModelException(
                    "apiKey is required for the openai-official provider. Set it via"
                            + " builder.apiKey(...) or the OPENAI_API_KEY environment variable.",
                    null,
                    null);
        }

        try {
            // 【学习提示】关闭 SDK 自身重试，让 AgentScope 统一控制尝试次数与超时策略，
            // 避免 SDK 和外层各重试一轮，造成实际请求次数乘法增长。
            OpenAIOkHttpClient.Builder builder =
                    OpenAIOkHttpClient.builder().apiKey(apiKey).maxRetries(0);

            if (baseUrl != null && !baseUrl.isBlank()) {
                builder.baseUrl(baseUrl);
            }

            if (timeout != null) {
                builder.timeout(timeout);
            }

            if (additionalHeaders != null && !additionalHeaders.isEmpty()) {
                additionalHeaders.forEach(builder::putHeader);
            }

            applyProxy(builder, proxyConfig, baseUrl);

            return builder.build();
        } catch (RuntimeException e) {
            throw new OpenAIOfficialModelException(
                    "Failed to construct OpenAI client: " + e.getMessage(), e, null);
        }
    }

    /**
     * 将 {@link ProxyConfig} 应用到 OpenAI SDK 客户端构建器。
     *
     * <p>支持 HTTP 代理和无需认证的 SOCKS 代理。HTTP 认证使用 SDK 代理认证器，
     * 拒绝需要认证的 SOCKS 代理。模型在其生命周期内使用一个基础 URL，因此
     * {@code nonProxyHosts} 针对此 URL 解析，可为目标主机绕过代理。
     */
    static void applyProxy(
            OpenAIOkHttpClient.Builder builder, ProxyConfig proxyConfig, String baseUrl) {
        if (proxyConfig == null) {
            return;
        }

        boolean authenticated = proxyConfig.hasAuthentication();
        if (authenticated && proxyConfig.getType() != ProxyType.HTTP) {
            throw new OpenAIOfficialModelException(
                    "Authenticated SOCKS proxies are not supported by the openai-official"
                            + " provider",
                    null,
                    null);
        }

        String effectiveBaseUrl =
                baseUrl != null && !baseUrl.isBlank() ? baseUrl : "https://api.openai.com/v1";
        URI uri = URI.create(effectiveBaseUrl);
        if (proxyConfig.shouldBypass(uri.getHost())) {
            builder.proxy(Proxy.NO_PROXY);
            return;
        }

        builder.proxy(proxyConfig.toJavaProxy());
        if (authenticated) {
            builder.proxyAuthenticator(
                    ProxyAuthenticator.basic(proxyConfig.getUsername(), proxyConfig.getPassword()));
        }
    }
}
