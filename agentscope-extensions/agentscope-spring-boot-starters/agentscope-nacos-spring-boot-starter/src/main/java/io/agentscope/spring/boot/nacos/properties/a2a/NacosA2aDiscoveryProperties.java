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

/**
 * 从 Nacos A2A Discovery 发现智能体的 A2A 属性。
 *
 * <p>包含从 Nacos A2A Registry 发现智能体的子属性，便于开发者按需控制发现行为。
 *
 * <p>YAML 示例：
 * <pre>{@code
 * agentscope:
 *   a2a:
 *     nacos:
 *       discovery:
 *         enabled: true
 * }</pre>
 *
 * <p>注册属性参见 {@link NacosA2aRegistryProperties}。
 */
public class NacosA2aDiscoveryProperties {

    /**
     * 是否启用 Nacos A2A 发现。
     *
     * <p>默认 {@code true}；顶层集成启用后，查询 Nacos 的 AgentCard、A2A 传输信息等发现元数据，以发现其他智能体并进行智能体间通信。
     */
    private boolean enabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
