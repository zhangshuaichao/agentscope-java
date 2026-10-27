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

package io.agentscope.core.a2a.agent;

import java.util.HashMap;
import java.util.Map;
import org.a2aproject.sdk.client.config.ClientConfig;
import org.a2aproject.sdk.client.transport.spi.ClientTransport;
import org.a2aproject.sdk.client.transport.spi.ClientTransportConfig;

/**
 * A2A 智能体配置。
 */
public record A2aAgentConfig(
        @SuppressWarnings("rawtypes") Map<Class, ClientTransportConfig> clientTransports,
        ClientConfig clientConfig) {

    /**
     * 为 A2aAgentConfig 创建新构建器实例。
     *
     * @return 新构建器实例
     */
    public static A2aAgentConfigBuilder builder() {
        return new A2aAgentConfigBuilder();
    }

    public static class A2aAgentConfigBuilder {

        @SuppressWarnings("rawtypes")
        private final Map<Class, ClientTransportConfig> clientTransports;

        private ClientConfig clientConfig;

        public A2aAgentConfigBuilder() {
            clientTransports = new HashMap<>();
        }

        /**
         * 添加客户端传输配置，供
         * {@link org.a2aproject.sdk.client.ClientBuilder#withTransport(Class, ClientTransportConfig)} 使用。
         *
         * @param clazz 客户端传输实现类
         * @param config 客户端传输配置
         * @param <T> ClientTransport 的子类型
         * @return 当前 {@link A2aAgentConfigBuilder} 实例，支持链式调用
         */
        public <T extends ClientTransport> A2aAgentConfigBuilder withTransport(
                Class<T> clazz, ClientTransportConfig<T> config) {
            this.clientTransports.put(clazz, config);
            return this;
        }

        /**
         * 为 A2A 客户端添加客户端相关配置。
         *
         * @param clientConfig A2A 客户端配置
         * @return 当前 {@link A2aAgentConfigBuilder} 实例，支持链式调用
         */
        public A2aAgentConfigBuilder clientConfig(ClientConfig clientConfig) {
            this.clientConfig = clientConfig;
            return this;
        }

        public A2aAgentConfig build() {
            return new A2aAgentConfig(this.clientTransports, this.clientConfig);
        }
    }
}
