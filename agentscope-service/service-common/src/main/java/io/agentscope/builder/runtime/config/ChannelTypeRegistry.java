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
package io.agentscope.builder.runtime.config;

import io.agentscope.harness.agent.gateway.channel.ChannelFactory;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 以 {@code type} 为键的 {@link ChannelFactory} 实现注册表。它仅承载 SPI，本身不附带内置工厂；拥有通道适配器及其扩展 JAR 的调度平面在启动时注册具体类型。控制平面仅通过 {@link #registeredTypes()} 进行校验和类型列举。 */
public final class ChannelTypeRegistry {

    private static final ConcurrentHashMap<String, ChannelFactory> FACTORIES =
            new ConcurrentHashMap<>();

    private ChannelTypeRegistry() {}

    /** 在 {@code typeId} 下注册或替换工厂。返回此前注册的工厂（若有）。 */
    public static ChannelFactory register(String typeId, ChannelFactory factory) {
        Objects.requireNonNull(typeId, "typeId");
        Objects.requireNonNull(factory, "factory");
        return FACTORIES.put(typeId, factory);
    }

    /** 返回 {@code typeId} 对应的已注册工厂（若有）。 */
    public static Optional<ChannelFactory> get(String typeId) {
        if (typeId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(FACTORIES.get(typeId));
    }

    /** 返回已注册类型 ID 的不可变快照。 */
    public static Set<String> registeredTypes() {
        return Map.copyOf(FACTORIES).keySet();
    }
}
