/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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

package io.agentscope.spring.boot.nacos.properties.a2a;

import io.agentscope.spring.boot.nacos.constants.NacosConstants;
import io.agentscope.spring.boot.nacos.properties.AgentScopeNacosProperties;
import io.agentscope.spring.boot.nacos.properties.BaseNacosProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AgentScope A2A Nacos 集成的 Spring Boot 配置属性。
 *
 * <p>继承 {@link BaseNacosProperties}，提供 AgentScope 应用专用的 Nacos 属性，使用 {@link NacosConstants#A2A_NACOS_PREFIX} 前缀绑定。
 *
 * <p>YAML 示例：
 * <pre>{@code
 * agentscope:
 *   a2a:
 *     nacos:
 *       server-addr: 127.0.0.1:8848
 *       namespace: public
 *       username: nacos
 *       password: nacos
 *       properties:
 *         logAllProperties: true
 * }</pre>
 *
 * <p>对于 {@link BaseNacosProperties} 定义的字段，此类显式配置优先于 {@link AgentScopeNacosProperties}。例如：
 * <pre>{@code
 * agentscope:
 *   nacos:
 *     server-addr: nacos.a.host:8848
 *     username: nacos
 *     password: nacos
 *   a2a:
 *     nacos:
 *       server-addr: nacos.b.host:8848
 *       password: nacos@b
 * }</pre>
 * <p>实际连接地址为 {@code nacos.b.host:8848}，用户名为 {@code nacos}，密码为 {@code nacos@b}。
 */
@ConfigurationProperties(prefix = NacosConstants.A2A_NACOS_PREFIX)
public class AgentScopeA2aNacosProperties extends BaseNacosProperties {

    /**
     * Nacos A2A 注册子属性。
     *
     * <p>控制向 Nacos 注册智能体或智能体端点的行为，详见 {@link NacosA2aRegistryProperties}。
     */
    private NacosA2aRegistryProperties registry = new NacosA2aRegistryProperties();

    /**
     * Nacos A2A 发现子属性。
     *
     * <p>控制从 Nacos 发现智能体或智能体端点的行为，详见 {@link NacosA2aDiscoveryProperties}。
     */
    private NacosA2aDiscoveryProperties discovery = new NacosA2aDiscoveryProperties();

    public NacosA2aRegistryProperties getRegistry() {
        return registry;
    }

    public void setRegistry(NacosA2aRegistryProperties registry) {
        this.registry = registry;
    }

    public NacosA2aDiscoveryProperties getDiscovery() {
        return discovery;
    }

    public void setDiscovery(NacosA2aDiscoveryProperties discovery) {
        this.discovery = discovery;
    }
}
