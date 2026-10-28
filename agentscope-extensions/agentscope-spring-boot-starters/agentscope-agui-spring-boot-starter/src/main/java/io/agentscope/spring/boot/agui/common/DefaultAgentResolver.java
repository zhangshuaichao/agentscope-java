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
package io.agentscope.spring.boot.agui.common;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agui.AguiException;
import io.agentscope.core.agui.processor.AgentResolver;
import io.agentscope.core.agui.registry.AguiAgentRegistry;
import java.util.Objects;

/**
 * Spring Boot 集成中 {@link AgentResolver} 的默认实现。
 *
 * <p>支持两种模式：
 * <ul>
 *   <li><b>简单模式：</b>直接从注册表查找智能体</li>
 *   <li><b>服务端记忆模式：</b>通过 {@link ThreadSessionManager} 管理会话</li>
 * </ul>
 */
public class DefaultAgentResolver implements AgentResolver {

    private final AguiAgentRegistry registry;
    private final ThreadSessionManager sessionManager;
    private final boolean serverSideMemory;

    /**
     * 创建不支持会话的简单解析器。
     *
     * @param registry 智能体注册表
     */
    public DefaultAgentResolver(AguiAgentRegistry registry) {
        this(registry, null, false);
    }

    /**
     * 创建可选支持会话的解析器。
     *
     * @param registry 智能体注册表
     * @param sessionManager 会话管理器（可为 null）
     * @param serverSideMemory 是否启用服务端记忆
     */
    public DefaultAgentResolver(
            AguiAgentRegistry registry,
            ThreadSessionManager sessionManager,
            boolean serverSideMemory) {
        this.registry = Objects.requireNonNull(registry, "registry cannot be null");
        this.sessionManager = sessionManager;
        this.serverSideMemory = serverSideMemory && sessionManager != null;
    }

    @Override
    public Agent resolveAgent(String agentId, String threadId) {
        return resolveAgent(agentId, threadId, null);
    }

    @Override
    public Agent resolveAgent(String agentId, String threadId, String userId) {
        if (serverSideMemory && sessionManager != null) {
            return sessionManager.getOrCreateAgent(
                    userId,
                    threadId,
                    agentId,
                    () ->
                            registry.getAgent(agentId)
                                    .orElseThrow(
                                            () ->
                                                    new AguiException.AgentNotFoundException(
                                                            agentId)));
        }
        return registry.getAgent(agentId)
                .orElseThrow(() -> new AguiException.AgentNotFoundException(agentId));
    }

    @Override
    public boolean hasMemory(RuntimeContext runtimeContext) {
        if (serverSideMemory && sessionManager != null) {
            return sessionManager.hasMemory(runtimeContext);
        }
        return false;
    }

    /**
     * 创建 DefaultAgentResolver 构建器。
     *
     * @return 新的构建器实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /** DefaultAgentResolver 的构建器。 */
    public static class Builder {

        private AguiAgentRegistry registry;
        private ThreadSessionManager sessionManager;
        private boolean serverSideMemory = false;

        /**
         * 设置智能体注册表。
         *
         * @param registry 智能体注册表
         * @return 当前构建器
         */
        public Builder registry(AguiAgentRegistry registry) {
            this.registry = registry;
            return this;
        }

        /**
         * 设置支持服务端记忆的会话管理器。
         *
         * @param sessionManager 会话管理器
         * @return 当前构建器
         */
        public Builder sessionManager(ThreadSessionManager sessionManager) {
            this.sessionManager = sessionManager;
            return this;
        }

        /**
         * 启用或禁用服务端记忆管理。
         *
         * @param enabled 是否启用服务端记忆
         * @return 当前构建器
         */
        public Builder serverSideMemory(boolean enabled) {
            this.serverSideMemory = enabled;
            return this;
        }

        /**
         * 构建解析器。
         *
         * @return 构建完成的解析器
         * @throws NullPointerException 未设置 registry 时抛出
         */
        public DefaultAgentResolver build() {
            return new DefaultAgentResolver(registry, sessionManager, serverSideMemory);
        }
    }
}
