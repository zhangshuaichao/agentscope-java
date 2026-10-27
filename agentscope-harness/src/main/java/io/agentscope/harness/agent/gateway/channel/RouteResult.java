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

import io.agentscope.harness.agent.gateway.MsgContext;

/**
 * {@link ChannelRouter#resolveRoute} 的输出：解析出的智能体 ID、
 * 传给 {@link io.agentscope.harness.agent.gateway.Gateway#run} 的 {@link MsgContext}、
 * 表示命中绑定层或回退路径的诊断标签 {@code matchedBy}，以及将回复送回原渠道／对端的
 * {@link OutboundAddress}。
 *
 * @param agentId 解析出的目标智能体 ID
 * @param context 可供网关执行的路由上下文（渠道、会话作用域键、agentId 附加字段）
 * @param matchedBy 可读的命中层标签（例如 {@code "peer"}、{@code "guild+roles"}、{@code "default"}），
 *     供调试与日志使用
 * @param outboundAddress 主动回复（例如子智能体公告）的投递目标；从入站消息的渠道与对端元数据派生
 */
public record RouteResult(
        String agentId, MsgContext context, String matchedBy, OutboundAddress outboundAddress) {}
