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
package io.agentscope.spring.boot.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AgentScope Spring Boot starter 的根配置属性。
 *
 * <p>配置主要分为：
 *
 * <ul>
 *   <li>{@code agentscope.agent} 下的 {@link AgentProperties}</li>
 *   <li>{@code agentscope.model} 下的 {@link ModelProperties}</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "agentscope")
public class AgentscopeProperties {

    private final AgentProperties agent = new AgentProperties();

    private final ModelProperties model = new ModelProperties();

    public AgentProperties getAgent() {
        return agent;
    }

    public ModelProperties getModel() {
        return model;
    }
}
