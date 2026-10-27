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

package io.agentscope.core.a2a.server.registry;

import io.agentscope.core.a2a.server.transport.TransportProperties;
import java.util.List;
import org.a2aproject.sdk.spec.AgentCard;

/**
 * 向智能体注册中心注册的接口。
 */
public interface AgentRegistry {

    /**
     * 注册中心名称。
     *
     * @return 注册中心名称
     */
    String registryName();

    /**
     * 根据 AgentCard 和部署属性执行注册。
     *
     * @param agentCard 此智能体的卡片
     * @param transportProperties 传输属性
     */
    void register(AgentCard agentCard, List<TransportProperties> transportProperties);
}
