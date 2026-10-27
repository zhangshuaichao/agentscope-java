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
package io.agentscope.harness.agent.gateway.channel;

import java.util.Map;

/**
 * 根据已解析的路由配置及服务商专用属性构建 {@link Channel} 实例。
 */
@FunctionalInterface
public interface ChannelFactory {

    /**
     * 构建渠道。
     *
     * @param channelId 逻辑渠道 ID
     * @param routing 已解析的路由配置（defaultAgentId、dmScope、绑定）
     * @param properties 服务商专用属性；不会为 null，但可为空
     * @return 已完整构造、可执行 {@code init(Gateway)} 与 {@code start()} 的渠道
     */
    Channel create(String channelId, ChannelConfig routing, Map<String, Object> properties);
}
