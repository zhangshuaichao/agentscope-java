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
package io.agentscope.harness.agent.gateway;

import io.agentscope.core.agent.Agent;
import io.agentscope.harness.agent.gateway.channel.OutboundAddress;

/**
 * 连接 {@link io.agentscope.harness.agent.tool.AgentSpawnTool} 与网关层的桥梁。
 * 使创建出的子智能体能够公开为用户可寻址入口，而创建工具无需了解渠道、路由器或网关实现。
 *
 * <p>公开子智能体时，网关分配 {@code subagentId}；
 * 用户可通过它直接向子智能体发送消息，跳过普通绑定路由。
 */
@FunctionalInterface
public interface SubagentGatewayBridge {

    /**
     * 向用户公开子智能体的结果。
     *
     * @param subagentId 用户可见、用于直接寻址此子智能体的句柄
     */
    record ExposeResult(String subagentId) {}

    /**
     * 在网关中将已创建子智能体公开为用户可寻址入口。
     *
     * @param agentId 子智能体类型标识
     * @param sessionId 分配给子智能体的会话 ID
     * @param agent 智能体实例
     * @param replyTo 将回复送回用户渠道的出站地址；没有出站渠道上下文时可为 null
     * @return 包含 subagentId 句柄的公开结果
     */
    ExposeResult expose(String agentId, String sessionId, Agent agent, OutboundAddress replyTo);
}
