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

import io.agentscope.core.agent.RuntimeContext;

/**
 * 为渠道／网关轮次解析调用方提供的 {@link RuntimeContext}。
 *
 * <p>应用可将它接入 {@link io.agentscope.harness.agent.gateway.HarnessGateway}，
 * 或通过 {@link io.agentscope.harness.agent.gateway.GatewayBootstrap} 接入，
 * 附加租户信息、强制同步标志、工具依赖等请求级值。网关身份字段（{@code
 * sessionId}、{@code userId}、{@code outboundAddress} 等）仍在解析之后应用，
 * 并覆盖冲突的调用方值。
 *
 * <p>返回 {@code null} 时，保留已有调用方上下文
 * （来自 {@link ChannelRuntimeContextRequest#callerContext()}）不变。
 */
@FunctionalInterface
public interface ChannelRuntimeContextResolver {

    /**
     * 解析当前渠道轮次的运行上下文。
     *
     * @param request 渠道轮次元数据，包括调用方提供的上下文
     * @return 作为合并基础的运行上下文；{@code null} 表示保留 {@link
     *     ChannelRuntimeContextRequest#callerContext()}
     */
    RuntimeContext resolve(ChannelRuntimeContextRequest request);
}
