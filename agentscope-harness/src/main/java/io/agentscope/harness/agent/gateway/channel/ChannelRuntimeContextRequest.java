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
import io.agentscope.harness.agent.gateway.MsgContext;

/**
 * 为网关轮次解析调用方上下文时，{@link ChannelRuntimeContextResolver} 可用的输入。
 *
 * @param channelId 来源渠道 ID（例如 {@code "chatui"}、{@code "dingtalk"}）；可为 null
 * @param inboundMessage 经 {@link Channel#dispatch} 进入时的规范化入站信封；
 *     跳过入站信封的编程式 {@code SendOptions} 路径为 {@code null}
 * @param msgContext 本轮解析后的路由上下文
 * @param outboundAddress 回复投递目标；可为 null
 * @param callerContext {@link
 *     io.agentscope.harness.agent.gateway.channel.chatui.SendOptions} 或 {@link InboundMessage}
 *     携带的上下文；可为 null
 */
public record ChannelRuntimeContextRequest(
        String channelId,
        InboundMessage inboundMessage,
        MsgContext msgContext,
        OutboundAddress outboundAddress,
        RuntimeContext callerContext) {}
