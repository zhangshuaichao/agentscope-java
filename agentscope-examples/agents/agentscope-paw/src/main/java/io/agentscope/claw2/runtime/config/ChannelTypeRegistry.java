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
package io.agentscope.claw2.runtime.config;

import io.agentscope.extensions.channel.dingtalk.DingTalkChannel;
import io.agentscope.extensions.channel.feishu.FeishuChannel;
import io.agentscope.extensions.channel.github.GitHubChannel;
import io.agentscope.extensions.channel.gitlab.GitLabChannel;
import io.agentscope.extensions.channel.wecom.WeComChannel;
import io.agentscope.harness.agent.gateway.channel.ChannelFactory;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiChannel;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按 {@code type} 索引的 {@link ChannelFactory} 实现注册表，供 {@link io.agentscope.claw2.runtime.ClawBootstrap} 从 {@code agentscope.json} 自动实例化通道时查询。
 *
 * <p>内置类型：{@code chatui}、{@code dingtalk}、{@code wecom}、{@code feishu}、{@code github}、{@code gitlab}。调用方可在 {@link io.agentscope.claw2.runtime.ClawBootstrap.Builder#build()} 执行前{@link #register(String, ChannelFactory) register}其他类型。
 */
public final class ChannelTypeRegistry {

    private static final ConcurrentHashMap<String, ChannelFactory> FACTORIES =
            new ConcurrentHashMap<>();

    static {
        register(
                ChatUiChannel.CHANNEL_ID,
                (channelId, routing, properties) -> ChatUiChannel.create(routing));
        register(DingTalkChannel.TYPE, DingTalkChannel::fromProperties);
        register(WeComChannel.TYPE, WeComChannel::fromProperties);
        register(FeishuChannel.TYPE, FeishuChannel::fromProperties);
        register(GitHubChannel.TYPE, GitHubChannel::fromProperties);
        register(GitLabChannel.TYPE, GitLabChannel::fromProperties);
    }

    private ChannelTypeRegistry() {}

    /**
     * 在 {@code typeId} 下注册或替换工厂，返回此前注册的工厂（若有）。
     */
    public static ChannelFactory register(String typeId, ChannelFactory factory) {
        Objects.requireNonNull(typeId, "typeId");
        Objects.requireNonNull(factory, "factory");
        return FACTORIES.put(typeId, factory);
    }

    /**
     * 返回 {@code typeId} 注册的工厂（若有）。
     */
    public static Optional<ChannelFactory> get(String typeId) {
        if (typeId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(FACTORIES.get(typeId));
    }

    /**
     * 返回已注册类型 ID 的不可变快照。
     */
    public static Set<String> registeredTypes() {
        return Map.copyOf(FACTORIES).keySet();
    }
}
