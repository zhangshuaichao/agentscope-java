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

package io.agentscope.spring.boot.nacos.properties;

import io.agentscope.spring.boot.nacos.constants.NacosConstants;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AgentScope Nacos 集成的 Spring Boot 配置属性。
 *
 * <p>继承 {@link BaseNacosProperties}，为 AgentScope 应用提供 Nacos 配置，使用 {@link NacosConstants#NACOS_PREFIX} 前缀绑定属性。
 *
 * <p>YAML 配置示例：
 * <pre>{@code
 * agentscope:
 *   nacos:
 *     server-addr: 127.0.0.1:8848
 *     namespace: public
 *     username: nacos
 *     password: nacos
 *     properties:
 *       logAllProperties: true
 * }</pre>
 */
@ConfigurationProperties(prefix = NacosConstants.NACOS_PREFIX)
public class AgentScopeNacosProperties extends BaseNacosProperties {}
