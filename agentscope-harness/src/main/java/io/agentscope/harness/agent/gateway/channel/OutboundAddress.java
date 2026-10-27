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

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 出站主动消息的投递目标。由 {@link ChannelRouter} 在入站路由期间构造，
 * 网关将它保存为会话的“最近路由”，使主动回复（例如子智能体完成公告）
 * 能返回正确的渠道和对端。
 *
 * @param channelId 投递所用的渠道适配器（例如 {@code "chatui"}、{@code "slack"}）
 * @param accountId 可选多账户标识（单账户渠道可为 null）
 * @param to 格式为 {@code "channelId:peerKind:peerId"} 的投递地址（例如 {@code "dingtalk:GROUP:cidXXX"}）
 * @param threadId 线程回复使用的可选线程上下文（可为 null）
 */
public record OutboundAddress(String channelId, String accountId, String to, String threadId) {

    public OutboundAddress {
        Objects.requireNonNull(channelId, "channelId");
        Objects.requireNonNull(to, "to");
    }

    /**
     * 创建直接消息地址（不带线程）。
     */
    public static OutboundAddress direct(String channelId, String to) {
        return new OutboundAddress(channelId, null, to, null);
    }

    /**
     * 创建带账户上下文的地址。
     */
    public static OutboundAddress withAccount(String channelId, String accountId, String to) {
        return new OutboundAddress(channelId, accountId, to, null);
    }

    /**
     * 将地址序列化为扁平映射，供 {@code BaseStore} 持久化。
     */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new HashMap<>();
        m.put("channelId", channelId);
        if (accountId != null) {
            m.put("accountId", accountId);
        }
        m.put("to", to);
        if (threadId != null) {
            m.put("threadId", threadId);
        }
        return m;
    }

    /**
     * 从持久化映射重建地址，容忍缺失可选字段。
     */
    public static OutboundAddress fromMap(Map<String, Object> m) {
        if (m == null) {
            return null;
        }
        Object channelId = m.get("channelId");
        Object to = m.get("to");
        if (channelId == null || to == null) {
            return null;
        }
        return new OutboundAddress(
                String.valueOf(channelId),
                m.get("accountId") != null ? String.valueOf(m.get("accountId")) : null,
                String.valueOf(to),
                m.get("threadId") != null ? String.valueOf(m.get("threadId")) : null);
    }
}
