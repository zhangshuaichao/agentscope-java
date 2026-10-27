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
package io.agentscope.extensions.channel.wecom;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程范围的查找表，将 {@code channelId} 映射到 {@link WeComChannel} 实例，供 {@link WeComCallbackController} 将 URL 路由请求分发到正确渠道。
 *
 * <p>渠道在 {@link WeComChannel#start()} 注册，在 {@link WeComChannel#stop()} 移除。控制器通过 {@link #instance()} 获取同一单例，无需 Spring 注入；渠道由 {@link io.agentscope.harness.agent.gateway.channel.ChannelFactory} 静态工厂创建，控制器由 Spring 组件扫描创建，两者事先互不知晓。
 */
public final class WeComChannelRegistry {

    private static final WeComChannelRegistry INSTANCE = new WeComChannelRegistry();

    private final ConcurrentHashMap<String, WeComChannel> channels = new ConcurrentHashMap<>();

    private WeComChannelRegistry() {}

    /**
 * 返回进程范围的单例实例。
 */
    public static WeComChannelRegistry instance() {
        return INSTANCE;
    }

    public void register(WeComChannel channel) {
        channels.put(channel.channelId(), channel);
    }

    public void unregister(String channelId) {
        channels.remove(channelId);
    }

    public WeComChannel get(String channelId) {
        return channels.get(channelId);
    }
}
