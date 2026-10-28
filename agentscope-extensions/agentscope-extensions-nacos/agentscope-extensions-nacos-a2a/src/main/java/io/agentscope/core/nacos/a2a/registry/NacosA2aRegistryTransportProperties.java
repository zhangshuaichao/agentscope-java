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

package io.agentscope.core.nacos.a2a.registry;

import com.alibaba.nacos.common.utils.StringUtils;
import io.agentscope.core.nacos.a2a.registry.constants.Constants;

/**
 * 将 A2A 传输端点注册到 Nacos 的属性。
 * <p>配置要注册的 A2A 接口（端点），各属性合并为 transport 和 url，格式为：
 * {@code [protocol://]host:port[/path][?query]}。
 * <p>{@link #protocol} 为 null 时使用 {@link Constants#PROTOCOL_TYPE_HTTP}。
 * <p>{@link #protocol} 为 {@link Constants#PROTOCOL_TYPE_HTTP}（包括由 null 设定）且
 * {@link #supportTls} 为 {@code true} 时，会判定是否将 {@link #protocol} 转为
 * {@link Constants#PROTOCOL_TYPE_HTTPS}。
 * <p>【学习提示】这些属性描述对外发布的端点地址；声明支持 TLS 不会在此配置证书或启动 TLS 监听。
 */
public record NacosA2aRegistryTransportProperties(
        String transport,
        String host,
        int port,
        String path,
        boolean supportTls,
        String protocol,
        String query) {

    /**
     * 创建 {@link NacosA2aRegistryTransportProperties} 构建器实例。
     * @return 构建器实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /** {@link NacosA2aRegistryTransportProperties} 的构建器。<p>提供流式 API，通过不同配置选项构造实例。 */
    public static class Builder {

        /** A2A 端点的传输标识符。 */
        private String transport;

        /** A2A 端点的主机地址。 */
        private String host;

        /** A2A 端点的端口号。 */
        private int port;

        /** A2A 端点 URL 的路径部分。 */
        private String path;

        /** A2A 端点是否支持 TLS 加密的标志。 */
        private boolean supportTls;

        /** A2A 端点的协议（例如 http、https）。 */
        private String protocol;

        /** A2A 端点 URL 的查询字符串。 */
        private String query;

        /**
         * 设置 A2A 端点的传输标识符。
         * @param transport 传输标识符
         * @return 用于链式调用的构建器实例
         */
        public Builder transport(String transport) {
            this.transport = transport;
            return this;
        }

        /**
         * 设置 A2A 端点的主机地址。
         * @param host 主机地址
         * @return 用于链式调用的构建器实例
         */
        public Builder host(String host) {
            this.host = host;
            return this;
        }

        /**
         * 设置 A2A 端点的端口号。
         * @param port 端口号
         * @return 用于链式调用的构建器实例
         */
        public Builder port(int port) {
            this.port = port;
            return this;
        }

        /**
         * 设置 A2A 端点 URL 的路径部分。
         * @param path 路径部分
         * @return 用于链式调用的构建器实例
         */
        public Builder path(String path) {
            this.path = path;
            return this;
        }

        /**
         * 设置 A2A 端点是否支持 TLS 加密。
         * @param supportTls 支持 TLS 时为 {@code true}，否则为 {@code false}
         * @return 用于链式调用的构建器实例
         */
        public Builder supportTls(boolean supportTls) {
            this.supportTls = supportTls;
            return this;
        }

        /**
         * 设置 A2A 端点的协议（例如 http、https）。
         * @param protocol 使用的协议
         * @return 用于链式调用的构建器实例
         */
        public Builder protocol(String protocol) {
            this.protocol = protocol;
            return this;
        }

        /**
         * 设置 A2A 端点 URL 的查询字符串。
         * @param query 查询字符串
         * @return 用于链式调用的构建器实例
         */
        public Builder query(String query) {
            this.query = query;
            return this;
        }

        /**
         * 使用配置属性构建 {@link NacosA2aRegistryTransportProperties} 新实例。
         * <p>验证必需属性 transport 不为空。
         * @return {@link NacosA2aRegistryTransportProperties} 新实例
         * @throws IllegalArgumentException 当 transport 为空时
         */
        public NacosA2aRegistryTransportProperties build() {
            if (StringUtils.isEmpty(transport)) {
                throw new IllegalArgumentException("A2A Endpoint `transport` can not be empty.");
            }
            return new NacosA2aRegistryTransportProperties(
                    transport, host, port, path, supportTls, protocol, query);
        }
    }
}
