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

package io.agentscope.core.a2a.server.transport;

import io.agentscope.core.a2a.server.AgentScopeA2aServer;
import java.util.concurrent.Executor;
import org.a2aproject.sdk.server.requesthandlers.RequestHandler;
import org.a2aproject.sdk.spec.AgentCard;

/**
 * 为不同传输构建 {@link TransportWrapper} 的构建器接口。
 */
@SuppressWarnings("rawtypes")
public interface TransportWrapperBuilder<T extends TransportWrapper> {

    /**
     * 获取待构建包装器的传输类型名称。
     *
     * @return 传输类型名称
     */
    String getTransportType();

    /**
     * 构建不带 extendedAgentCard 的传输包装器。
     *
     * @param agentCard 由 {@link AgentScopeA2aServer} 构建的智能体卡片
     * @param requestHandler 由 {@link AgentScopeA2aServer} 构建的请求处理器
     * @param executor 请求处理执行器
     * @return 传输包装器
     */
    default T build(AgentCard agentCard, RequestHandler requestHandler, Executor executor) {
        return build(agentCard, requestHandler, executor, null);
    }

    /**
     * 构建带 extendedAgentCard 的传输包装器。
     *
     * @param agentCard 由 {@link AgentScopeA2aServer} 构建的智能体卡片
     * @param requestHandler 由 {@link AgentScopeA2aServer} 构建的请求处理器
     * @param executor 请求处理执行器
     * @param extendedAgentCard 由 {@link AgentScopeA2aServer} 构建的扩展智能体卡片
     * @return 传输包装器
     */
    T build(
            AgentCard agentCard,
            RequestHandler requestHandler,
            Executor executor,
            AgentCard extendedAgentCard);
}
