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

import java.util.HashMap;
import java.util.Map;

/**
 * 将 A2A 智能体卡片和端点注册到 Nacos 的属性。
 * <p>属性说明：
 * <ul>
 *     <li>{@code isSetAsLatest}：始终将 A2A 服务注册为最新版本，默认为 {@code false}。</li>
 *     <li>{@code enabledRegisterEndpoint}：自动将所有 Transport 注册为此 A2A 服务的端点，
 *     默认为 {@code true}。设为 {@code false} 时仅发布智能体卡片。</li>
 *     <li>{@code overwritePreferredTransport}：注册 A2A 服务时，用此 Transport 覆盖卡片中的
 *     preferredTransport 和 url，默认为 null。</li>
 * </ul>
 */
public record NacosA2aRegistryProperties(
        boolean isSetAsLatest,
        boolean enabledRegisterEndpoint,
        String overwritePreferredTransport,
        Map<String, NacosA2aRegistryTransportProperties> transportProperties) {

    /**
     * 向注册配置添加传输属性。
     * <p>每种传输会转换为 {@link com.alibaba.nacos.api.ai.model.a2a.AgentEndpoint} 并注册到 Nacos。
     * @param transport 传输属性
     */
    public void addTransport(NacosA2aRegistryTransportProperties transport) {
        transportProperties.put(transport.transport(), transport);
    }

    /**
     * 创建 {@link NacosA2aRegistryProperties} 构建器实例。
     * @return 构建器实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 从已有原型创建 {@link NacosA2aRegistryProperties} 构建器实例。
     * <p>新构建器复制原型的各项值，但不复制 {@link #transportProperties}。
     * @param prototype 要复制属性的原型实例
     * @return 构建器实例
     */
    public static Builder builder(NacosA2aRegistryProperties prototype) {
        Builder newBuilder = builder();
        newBuilder.setAsLatest(prototype.isSetAsLatest());
        newBuilder.enabledRegisterEndpoint(prototype.enabledRegisterEndpoint());
        newBuilder.overwritePreferredTransport(prototype.overwritePreferredTransport());
        return newBuilder;
    }

    /** {@link NacosA2aRegistryProperties} 的构建器。 */
    public static class Builder {

        private final Map<String, NacosA2aRegistryTransportProperties> transportProperties;

        private boolean setAsLatest;

        private boolean enabledRegisterEndpoint;

        private String overwritePreferredTransport;

        private Builder() {
            transportProperties = new HashMap<>();
            enabledRegisterEndpoint = true;
        }

        /**
         * 设置是否将 A2A 服务注册为最新版本。
         * @param setAsLatest {@code true} 表示注册为最新版本，否则为 {@code false}
         * @return 用于链式调用的构建器实例
         */
        public Builder setAsLatest(boolean setAsLatest) {
            this.setAsLatest = setAsLatest;
            return this;
        }

        /**
         * 设置是否自动将所有 Transport 注册为此 A2A 服务的端点。
         * @param enabledRegisterEndpoint {@code true} 启用端点注册，{@code false} 禁用
         * @return 用于链式调用的构建器实例
         */
        public Builder enabledRegisterEndpoint(boolean enabledRegisterEndpoint) {
            this.enabledRegisterEndpoint = enabledRegisterEndpoint;
            return this;
        }

        /**
         * 设置用于覆盖卡片中 preferredTransport 与 URL 的传输。
         * @param overwritePreferredTransport 用于覆盖的传输；{@code null} 表示不覆盖
         * @return 用于链式调用的构建器实例
         */
        public Builder overwritePreferredTransport(String overwritePreferredTransport) {
            this.overwritePreferredTransport = overwritePreferredTransport;
            return this;
        }

        /**
         * 构建 {@link NacosA2aRegistryProperties} 新实例。
         * @return {@link NacosA2aRegistryProperties} 新实例
         */
        public NacosA2aRegistryProperties build() {
            return new NacosA2aRegistryProperties(
                    setAsLatest,
                    enabledRegisterEndpoint,
                    overwritePreferredTransport,
                    transportProperties);
        }
    }
}
