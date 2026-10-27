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

import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.gateway.channel.Channel;
import io.agentscope.harness.agent.gateway.channel.OutboundAddress;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link Channel} 适配器的注册表与生命周期管理器。提供出站分发能力，
 * 使网关可通过正确渠道投递主动消息（例如子智能体公告）。
 *
 * <h2>生命周期</h2>
 *
 * <ol>
 *   <li>{@link #register(Channel)} — 将渠道加入注册表
 *   <li>{@link #initAll(Gateway)} — 向全部已注册渠道注入网关
 *   <li>{@link #startAll()} — 启动全部渠道（连接外部传输）
 *   <li>{@link #stopAll()} — 停止全部渠道并释放资源
 * </ol>
 *
 * <h2>出站投递</h2>
 *
 * {@link #deliver(OutboundAddress, List)} 按 {@link
 * OutboundAddress#channelId()} 查找目标渠道，并委托给 {@link Channel#deliver(OutboundAddress, List)}。
 */
public final class ChannelManager {

    private static final Logger log = LoggerFactory.getLogger(ChannelManager.class);

    private final ConcurrentHashMap<String, Channel> channels = new ConcurrentHashMap<>();
    private volatile boolean started = false;

    public ChannelManager() {}

    /**
     * 注册渠道适配器。若同一 {@link Channel#channelId()} 已注册渠道，则替换它。
     */
    public void register(Channel channel) {
        Objects.requireNonNull(channel, "channel");
        channels.put(channel.channelId(), channel);
    }

    /**
     * 停止并移除以 {@code channelId} 注册的渠道（若存在）。
     * 移除了渠道时返回 {@code true}，该 ID 原本未注册渠道时返回 {@code false}。
     */
    public boolean unregister(String channelId) {
        if (channelId == null) {
            return false;
        }
        Channel removed = channels.remove(channelId);
        if (removed == null) {
            return false;
        }
        try {
            removed.stop();
            log.info("Channel unregistered and stopped: {}", channelId);
        } catch (Exception e) {
            log.warn(
                    "Error stopping channel '{}' during unregister: {}",
                    channelId,
                    e.getMessage(),
                    e);
        }
        return true;
    }

    /**
     * 返回指定 ID 下注册的渠道（若存在）。
     */
    public Optional<Channel> getChannel(String channelId) {
        if (channelId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(channels.get(channelId));
    }

    /**
     * 返回全部已注册渠道 ID 的不可修改快照。
     */
    public List<String> channelIds() {
        return List.copyOf(channels.keySet());
    }

    /**
     * 返回全部已注册渠道的不可修改快照。
     */
    public Collection<Channel> getAllChannels() {
        return List.copyOf(channels.values());
    }

    /**
     * 通过 {@link Channel#init(Gateway)} 向全部已注册渠道注入网关。
     * 在启动引导期间、{@link #startAll()} 之前调用一次。
     */
    public void initAll(Gateway gateway) {
        Objects.requireNonNull(gateway, "gateway");
        for (Channel ch : channels.values()) {
            try {
                ch.init(gateway);
            } catch (Exception e) {
                log.error("Failed to init channel '{}': {}", ch.channelId(), e.getMessage(), e);
            }
        }
    }

    /**
     * 启动全部已注册渠道。渠道启动失败会记日志，但不会中止整体启动。
     */
    public void startAll() {
        for (Channel ch : channels.values()) {
            try {
                ch.start();
                log.info("Channel started: {}", ch.channelId());
            } catch (Exception e) {
                log.error("Failed to start channel '{}': {}", ch.channelId(), e.getMessage(), e);
            }
        }
        started = true;
    }

    /**
     * 停止全部已注册渠道并清空注册表。
     */
    public void stopAll() {
        started = false;
        for (Channel ch : channels.values()) {
            try {
                ch.stop();
                log.info("Channel stopped: {}", ch.channelId());
            } catch (Exception e) {
                log.warn("Error stopping channel '{}': {}", ch.channelId(), e.getMessage(), e);
            }
        }
    }

    /**
     * 是否已调用 {@link #startAll()}。
     */
    public boolean isStarted() {
        return started;
    }

    /**
     * 通过 {@link OutboundAddress#channelId()} 指定的渠道投递主动出站消息。
     * 若目标渠道未注册，则丢弃消息并记录警告。
     *
     * @param address 投递目标
     * @param messages 要投递的消息
     */
    public void deliver(OutboundAddress address, List<Msg> messages) {
        Objects.requireNonNull(address, "address");
        if (messages == null || messages.isEmpty()) {
            return;
        }
        Channel ch = channels.get(address.channelId());
        if (ch == null) {
            log.warn(
                    "Outbound delivery skipped: no channel registered for '{}'",
                    address.channelId());
            return;
        }
        try {
            ch.deliver(address, messages);
        } catch (Exception e) {
            log.error(
                    "Outbound delivery failed: channel='{}', to='{}': {}",
                    address.channelId(),
                    address.to(),
                    e.getMessage(),
                    e);
        }
    }
}
