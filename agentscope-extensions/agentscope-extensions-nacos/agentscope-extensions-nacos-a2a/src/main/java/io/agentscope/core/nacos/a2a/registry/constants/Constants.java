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

package io.agentscope.core.nacos.a2a.registry.constants;

/** Nacos A2A 注册常量。 */
public class Constants {

    /** HTTP 协议字符串常量。 */
    public static final String PROTOCOL_TYPE_HTTP = "http";

    /** HTTPS 协议字符串常量。 */
    public static final String PROTOCOL_TYPE_HTTPS = "https";

    /** 传输属性环境变量的前缀。 */
    public static final String PROPERTIES_ENV_PREFIX = "NACOS_A2A_AGENT_";

    /**
     * 环境变量中的传输属性枚举。
     * <p>环境键格式为
     * {@link #PROPERTIES_ENV_PREFIX}{@code {TRANSPORT}}_{@link TransportPropertiesAttribute#envKey}。
     * 例如 {@code NACOS_A2A_AGENT_JSONRPC_HOST=} 表示传输 {@code JSONRPC} 的 {@link #HOST} 属性。
     */
    public enum TransportPropertiesAttribute {
        HOST("HOST", "host"),
        PORT("PORT", "port"),
        PATH("PATH", "path"),
        PROTOCOL("PROTOCOL", "protocol"),
        QUERY("QUERY", "query"),
        SUPPORT_TLS("SUPPORTTLS", "supportTls");

        private final String envKey;

        private final String propertyKey;

        TransportPropertiesAttribute(String envKey, String propertyKey) {
            this.envKey = envKey;
            this.propertyKey = propertyKey;
        }

        /**
         * 获取此传输属性关联的属性键。
         * @return 属性键
         */
        public String getPropertyKey() {
            return propertyKey;
        }

        /**
         * 按环境键获取 {@link TransportPropertiesAttribute} 实例。
         * @param envKey 环境键
         * @return 找到时返回 {@link TransportPropertiesAttribute} 实例，否则返回 null
         */
        public static TransportPropertiesAttribute getByEnvKey(String envKey) {
            for (TransportPropertiesAttribute each : TransportPropertiesAttribute.values()) {
                if (each.envKey.equals(envKey)) {
                    return each;
                }
            }
            return null;
        }
    }
}
