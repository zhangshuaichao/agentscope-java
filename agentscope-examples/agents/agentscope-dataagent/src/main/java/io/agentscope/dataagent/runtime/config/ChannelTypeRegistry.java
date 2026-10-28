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
package io.agentscope.dataagent.runtime.config;

import io.agentscope.dataagent.runtime.channel.webhook.WebhookChannel;
import io.agentscope.extensions.channel.dingtalk.DingTalkChannel;
import io.agentscope.harness.agent.gateway.channel.ChannelFactory;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiChannel;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 以 {@code type} 为键的 {@link ChannelFactory} 实现注册表。
 * {@link io.agentscope.dataagent.runtime.DataAgentBootstrap} 从 {@code agentscope.json} 自动实例化通道时查询此表。
 * <p>DataAgent v1 内置类型：{@code chatui}（始终启用的主要交互界面）、{@code dingtalk}
 * （按需启用的 IM 桥接）、{@code webhook}（供 IM/工单/CI 系统调用 DataAgent 并接收结果的通用 HTTP 旁路）。
 * v1 刻意不附带飞书 / 企业微信 / GitHub / GitLab 适配器；调用方可在
 * {@link io.agentscope.dataagent.runtime.DataAgentBootstrap.Builder#build()} 执行前
 * 通过 {@link #register(String, ChannelFactory) register} 注册其他类型。
 */
public final class ChannelTypeRegistry {

    private static final ConcurrentHashMap<String, ChannelFactory> FACTORIES =
            new ConcurrentHashMap<>();

    static {
        register(
                ChatUiChannel.CHANNEL_ID,
                (channelId, routing, properties) -> ChatUiChannel.create(routing));
        register(DingTalkChannel.TYPE, DingTalkChannel::fromProperties);
        register(WebhookChannel.TYPE, WebhookChannel::fromProperties);
    }

    private ChannelTypeRegistry() {}

    /** 在 {@code typeId} 下注册或替换工厂。返回此前注册的工厂（若有）。 */
    public static ChannelFactory register(String typeId, ChannelFactory factory) {
        Objects.requireNonNull(typeId, "typeId");
        Objects.requireNonNull(factory, "factory");
        return FACTORIES.put(typeId, factory);
    }

    /** 返回 {@code typeId} 对应的已注册工厂（若有）。 */
    public static Optional<ChannelFactory> get(String typeId) {
        if (typeId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(FACTORIES.get(typeId));
    }

    /** 返回已注册类型 ID 的不可变快照。 */
    public static Set<String> registeredTypes() {
        return Map.copyOf(FACTORIES).keySet();
    }
}
