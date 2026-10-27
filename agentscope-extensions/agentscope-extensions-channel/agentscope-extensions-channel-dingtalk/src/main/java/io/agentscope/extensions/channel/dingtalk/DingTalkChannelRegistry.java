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
package io.agentscope.extensions.channel.dingtalk;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程级 {@code channelId} 到 {@link DingTalkChannel} 实例查找表。
 * {@link DingTalkCallbackController} 用它将 URL 路由请求分发到正确渠道。
 *
 * <p>HTTP 回调模式渠道在 {@link DingTalkChannel#start()} 中注册，
 * 在 {@link DingTalkChannel#stop()} 中移除。控制器通过 {@link #instance()} 获取同一单例，
 * 无需 Spring 装配：渠道由 {@link io.agentscope.harness.agent.gateway.channel.ChannelFactory}
 * 静态工厂创建，而控制器由 Spring 组件扫描创建，两者事先并不知道对方。
 *
 * <p>线程安全，由 {@link ConcurrentHashMap} 支持。
 */
public final class DingTalkChannelRegistry {

    private static final DingTalkChannelRegistry INSTANCE = new DingTalkChannelRegistry();

    private final ConcurrentHashMap<String, DingTalkChannel> channels = new ConcurrentHashMap<>();

    private DingTalkChannelRegistry() {}

    /**
     * 返回进程级单例。
     */
    public static DingTalkChannelRegistry instance() {
        return INSTANCE;
    }

    /**
     * 以渠道 ID 注册渠道。重复注册同一实例不执行操作；不同实例已占用该 ID 时立即失败，
     * 防止静默替换将正在运行渠道的回调不留痕迹地转移给其他实例。
     */
    public void register(DingTalkChannel channel) {
        DingTalkChannel existing = channels.putIfAbsent(channel.channelId(), channel);
        if (existing != null && existing != channel) {
            throw new IllegalStateException(
                    "A DingTalk channel is already registered for id '"
                            + channel.channelId()
                            + "'");
        }
    }

    /**
     * 仅当 {@code channelId} 当前映射到 {@code expected} 时移除注册，
     * 防止关闭中的旧实例移除后继实例的注册。
     */
    public void unregister(String channelId, DingTalkChannel expected) {
        channels.remove(channelId, Objects.requireNonNull(expected, "expected"));
    }

    /**
     * 返回 {@code channelId} 对应的已注册渠道；没有时为 {@code null}。
     */
    public DingTalkChannel get(String channelId) {
        return channels.get(channelId);
    }
}
