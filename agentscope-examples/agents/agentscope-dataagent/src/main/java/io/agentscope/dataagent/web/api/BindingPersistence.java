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
package io.agentscope.dataagent.web.api;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.agentscope.dataagent.runtime.DataAgentBootstrap;
import io.agentscope.dataagent.runtime.config.AgentscopeConfig;
import io.agentscope.dataagent.runtime.config.BindingConfigEntry;
import io.agentscope.dataagent.runtime.config.ChannelConfigEntry;
import io.agentscope.harness.agent.gateway.ChannelManager;
import io.agentscope.harness.agent.gateway.channel.Channel;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * 加载、修改并以原子文件移动重写 {@code agentscope.json}，用于绑定和渠道默认智能体设置；
 * 随后向 {@link ChannelManager} 中的运行中渠道应用新路由配置。
 * <p>通过 {@link ReentrantLock} 串行化本实例内的修改，避免同一实例并发请求丢失更新。
 * <p>学习提示：锁不是跨进程锁；磁盘替换和渠道热更新也不是一个事务。
 * 热更新失败只记日志，磁盘配置仍保留；文件系统不支持原子移动时，写入会报错而非降级。
 */
@Component
public class BindingPersistence {

    private static final Logger log = LoggerFactory.getLogger(BindingPersistence.class);

    private final DataAgentBootstrap bootstrap;
    private final ChannelManager channelManager;
    private final ReentrantLock lock = new ReentrantLock();
    private final ObjectMapper mapper;

    public BindingPersistence(DataAgentBootstrap bootstrap) {
        this.bootstrap = bootstrap;
        this.channelManager = bootstrap.channelManager();
        this.mapper =
                new ObjectMapper()
                        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                        .enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * 将 {@code mutator} 应用于从 {@code agentscope.json} 读取的渠道映射，原子写回文件，
     * 再尝试更新 {@link ChannelManager} 中指定渠道的路由。返回 {@code mutator} 的结果供调用者构造响应。
     */
    public <T> T mutate(MutationFn<T> mutator, List<String> channelsToReload) {
        lock.lock();
        try {
            AgentscopeConfig cfg = loadConfig();
            Map<String, ChannelConfigEntry> channels =
                    cfg.getChannels() != null ? cfg.getChannels() : new LinkedHashMap<>();
            cfg.setChannels(channels);

            T result = mutator.apply(channels);
            writeAtomic(cfg);

            if (channelsToReload != null) {
                for (String id : channelsToReload) {
                    reloadChannel(id, channels.get(id));
                }
            }
            return result;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 返回渠道绑定列表；缺失时创建，已有列表非 ArrayList 时复制为可变列表。
     */
    public List<BindingConfigEntry> mutableBindings(ChannelConfigEntry channel) {
        if (channel.getBindings() == null) {
            channel.setBindings(new ArrayList<>());
        } else if (!(channel.getBindings() instanceof ArrayList)) {
            channel.setBindings(new ArrayList<>(channel.getBindings()));
        }
        return channel.getBindings();
    }

    /**
     * 返回给定渠道 ID 的 {@link ChannelConfigEntry}，缺失时延迟创建。
     */
    public ChannelConfigEntry orCreate(Map<String, ChannelConfigEntry> channels, String channelId) {
        return channels.computeIfAbsent(channelId, k -> new ChannelConfigEntry());
    }

    /**
     * {@link #mutate(MutationFn, List)} 使用的修改函数接口。
     */
    @FunctionalInterface
    public interface MutationFn<T> {
        T apply(Map<String, ChannelConfigEntry> channels);
    }

    // -----------------------------------------------------------------
    // 内部辅助方法
    // -----------------------------------------------------------------

    private AgentscopeConfig loadConfig() {
        try {
            return DataAgentBootstrap.loadConfigFile(bootstrap.configPath());
        } catch (IOException e) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to read agentscope.json: " + e.getMessage());
        }
    }

    private void writeAtomic(AgentscopeConfig cfg) {
        Path target = bootstrap.configPath();
        try {
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            mapper.writeValue(tmp.toFile(), cfg);
            Files.move(
                    tmp,
                    target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to write agentscope.json: " + e.getMessage());
        }
    }

    /**
     * 将磁盘路由配置应用于运行中渠道，使后续入站消息使用新绑定。
     * 委托 {@link Channel#applyRoutingConfig}；返回 {@code false} 的渠道不支持热更新，会记录需要重启的日志。
     * 绑定已写入磁盘，下次进程启动时才会应用。
     */
    private void reloadChannel(String channelId, ChannelConfigEntry entry) {
        if (channelId == null || entry == null) return;
        Optional<Channel> liveOpt = channelManager.getChannel(channelId);
        if (liveOpt.isEmpty()) {
            log.info(
                    "Channel '{}' not currently registered; bindings persisted and will load on"
                            + " next start.",
                    channelId);
            return;
        }
        Channel live = liveOpt.get();
        try {
            ChannelConfig newCfg = entry.toChannelConfig(channelId);
            if (live.applyRoutingConfig(newCfg)) {
                log.info("Hot-reloaded routing config for channel '{}'", channelId);
            } else {
                log.warn(
                        "Channel '{}' does not support live config swap; restart required for"
                                + " binding changes to take effect.",
                        channelId);
            }
        } catch (Exception e) {
            log.warn(
                    "Failed to reload channel '{}': {}. Restart required for live changes.",
                    channelId,
                    e.getMessage());
        }
    }
}
