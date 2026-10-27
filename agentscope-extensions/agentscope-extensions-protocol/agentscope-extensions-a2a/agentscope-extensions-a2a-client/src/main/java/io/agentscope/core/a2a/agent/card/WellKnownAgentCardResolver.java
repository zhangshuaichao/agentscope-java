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

package io.agentscope.core.a2a.agent.card;

import java.util.Map;
import org.a2aproject.sdk.A2A;
import org.a2aproject.sdk.spec.AgentCard;

/**
 * 通过 well-known URL 提供智能体卡片。
 *
 * <p>遵循 <a href="https://a2a-protocol.org/latest/topics/agent-discovery/#1-well-known-uri">Well-Known URI</a> 定义，
 * 使用 {@link A2A#getAgentCard(String, String, Map)} 获取 {@link AgentCard}。
 *
 * <ul>
 *   <li>{@link #baseUrl} 应为 {@code schema://domain:port[/rootPath]}</li>
 *   <li>{@link #relativeCardPath} 应为 well-known URI 的子路径，默认
 *       {@code /.well-known/agent-card.json}</li>
 *   <li>{@link #authHeaders} 应为访问智能体服务器获取 AgentCard 所需的认证请求头</li>
 * </ul>
 *
 * <p>完整 well-known URI 由 {@link #baseUrl} 与 {@link #relativeCardPath} 组合而成。
 */
public class WellKnownAgentCardResolver implements AgentCardResolver {

    private final String baseUrl;

    private final String relativeCardPath;

    private final Map<String, String> authHeaders;

    private WellKnownAgentCardResolver(
            String baseUrl, String relativeCardPath, Map<String, String> authHeaders) {
        this.baseUrl = baseUrl;
        this.relativeCardPath = relativeCardPath;
        this.authHeaders = authHeaders;
    }

    @Override
    public AgentCard getAgentCard(String agentName) {
        return A2A.getAgentCard(baseUrl, relativeCardPath, authHeaders);
    }

    /**
     * 为 {@link WellKnownAgentCardResolver} 创建新的 {@link Builder} 实例。
     *
     * @return 新构建器实例
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {

        private String baseUrl;

        private String relativeCardPath;

        private Map<String, String> authHeaders;

        private Builder() {
            this.relativeCardPath = "/.well-known/agent-card.json";
            this.authHeaders = Map.of();
        }

        /**
         * 设置智能体卡片解析器的基础 URL。
         *
         * @param baseUrl 待设置的基础 URL
         * @return 当前 Builder 实例，支持链式调用
         */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /**
         * 设置智能体卡片解析器的相对卡片路径。
         *
         * @param relativeCardPath 待设置的相对卡片路径
         * @return 当前 Builder 实例，支持链式调用
         */
        public Builder relativeCardPath(String relativeCardPath) {
            this.relativeCardPath = relativeCardPath;
            return this;
        }

        /**
         * 设置智能体卡片解析器的认证请求头。
         *
         * @param authHeaders 待设置的认证请求头
         * @return 当前 Builder 实例，支持链式调用
         */
        public Builder authHeaders(Map<String, String> authHeaders) {
            this.authHeaders = authHeaders;
            return this;
        }

        /**
         * 构建 WellKnownAgentCardResolver 实例。
         *
         * @return 构建的 WellKnownAgentCardResolver 实例
         */
        public WellKnownAgentCardResolver build() {
            return new WellKnownAgentCardResolver(baseUrl, relativeCardPath, authHeaders);
        }
    }
}
