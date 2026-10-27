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

import java.util.Map;
import java.util.Objects;

/**
 * 入站轮次（直接 API、渠道适配器、群组／房间／线程）的路由上下文。
 * 网关用它将稳定对话键映射到会话 ID。
 *
 * <p>{@link #userId} 字段携带消息发送者身份，供
 * {@link io.agentscope.harness.agent.HarnessAgent} 实施多租户命名空间隔离。
 * 它来自 {@link io.agentscope.harness.agent.gateway.channel.InboundMessage#senderId()}，
 * <em>不</em>参与 {@link #canonicalKey()} 计算；仅改变 userId 不会改变同一逻辑对话的路由会话键。
 *
 * @param channel 逻辑渠道名（例如 slack、discord、web）
 * @param group 可选群组／团队／工作区 ID
 * @param room 可选房间／频道 ID
 * @param threadId 可选线程／话题 ID
 * @param threadTs 可选的服务商专用线程时间戳或消息锚点
 * @param extra 适配器使用的附加键值对
 * @param userId HarnessAgent 命名空间隔离使用的可选已认证用户身份
 */
public record MsgContext(
        String channel,
        String group,
        String room,
        String threadId,
        String threadTs,
        Map<String, String> extra,
        String userId) {

    public MsgContext {
        extra = extra != null ? Map.copyOf(extra) : Map.of();
    }

    /**
     * 不带 {@code userId} 的便捷构造方法，向后兼容未携带用户身份的调用点。
     */
    public MsgContext(
            String channel,
            String group,
            String room,
            String threadId,
            String threadTs,
            Map<String, String> extra) {
        this(channel, group, room, threadId, threadTs, extra, null);
    }

    /**
     * 默认单对话上下文（无渠道元数据，无 userId）。
     */
    public static MsgContext defaultContext() {
        return new MsgContext("default", null, null, null, null, Map.of(), null);
    }

    /**
     * 返回设置了指定 {@code userId} 的上下文副本。
     */
    public MsgContext withUserId(String userId) {
        return new MsgContext(channel, group, room, threadId, threadTs, extra, userId);
    }

    /**
     * 会话路由的稳定键：同一逻辑对话映射到相同网关会话 ID。
     */
    public String canonicalKey() {
        StringBuilder sb = new StringBuilder(64);
        sb.append(Objects.requireNonNullElse(channel, "default"));
        if (group != null && !group.isBlank()) {
            sb.append("|g:").append(group.trim());
        }
        if (room != null && !room.isBlank()) {
            sb.append("|r:").append(room.trim());
        }
        if (threadId != null && !threadId.isBlank()) {
            sb.append("|t:").append(threadId.trim());
        }
        if (threadTs != null && !threadTs.isBlank()) {
            sb.append("|ts:").append(threadTs.trim());
        }
        if (!extra.isEmpty()) {
            extra.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(
                            e ->
                                    sb.append("|x:")
                                            .append(e.getKey())
                                            .append('=')
                                            .append(e.getValue()));
        }
        return sb.toString();
    }
}
