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
package io.agentscope.claw2.web.api;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.agentscope.claw2.runtime.ClawBootstrap;
import io.agentscope.claw2.runtime.config.AgentscopeConfig;
import io.agentscope.claw2.runtime.config.BindingConfigEntry;
import io.agentscope.claw2.runtime.config.ChannelConfigEntry;
import io.agentscope.claw2.runtime.config.ChannelTypeRegistry;
import io.agentscope.harness.agent.gateway.ChannelManager;
import io.agentscope.harness.agent.gateway.channel.Channel;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import io.agentscope.harness.agent.gateway.channel.ChannelFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * 加载、修改并通过文件替换重写 {@code agentscope.json} 的绑定／渠道默认值配置，再将受影响渠道重新注册到活动 {@link ChannelManager}，使变更立即生效。
 *
 * <p>本实例内所有修改通过 {@link ReentrantLock} 串行执行，避免并发请求丢失更新。
 */
@Component
public class BindingPersistence {

    private static final Logger log = LoggerFactory.getLogger(BindingPersistence.class);

    private final ClawBootstrap bootstrap;
    private final ChannelManager channelManager;
    private final ReentrantLock lock = new ReentrantLock();
    private final ObjectMapper mapper;

    public BindingPersistence(ClawBootstrap bootstrap) {
        this.bootstrap = bootstrap;
        this.channelManager = bootstrap.channelManager();
        this.mapper =
                new ObjectMapper()
                        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                        .enable(SerializationFeature.INDENT_OUTPUT);
    }

    /** 对 {@code agentscope.json} 当前渠道映射应用 {@code mutator}，持久化结果，再在活动 {@link ChannelManager} 中重新注册指定渠道 ID。返回 {@code mutator} 的结果，便于调用方构造响应。 */
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

    /** 从磁盘读取当前 {@code agentscope.json}，不加锁；调用方不应原地修改返回配置。供只读端点获取最新渠道状态，因为 {@link ClawBootstrap#loadedConfig()} 仅为启动时快照。 */
    public AgentscopeConfig currentConfig() {
        return loadConfig();
    }

    /** 返回指定渠道的绑定列表；不存在时创建。 */
    public List<BindingConfigEntry> mutableBindings(ChannelConfigEntry channel) {
        if (channel.getBindings() == null) {
            channel.setBindings(new ArrayList<>());
        } else if (!(channel.getBindings() instanceof ArrayList)) {
            channel.setBindings(new ArrayList<>(channel.getBindings()));
        }
        return channel.getBindings();
    }

    /** 返回指定渠道 ID 的 {@link ChannelConfigEntry}，必要时延迟创建。 */
    public ChannelConfigEntry orCreate(Map<String, ChannelConfigEntry> channels, String channelId) {
        return channels.computeIfAbsent(channelId, k -> new ChannelConfigEntry());
    }

    /** 用于 {@link #mutate(MutationFn, List)} 修改操作的函数式接口。 */
    @FunctionalInterface
    public interface MutationFn<T> {
        T apply(Map<String, ChannelConfigEntry> channels);
    }

    // -----------------------------------------------------------------
    // 内部辅助方法
    // -----------------------------------------------------------------

    private AgentscopeConfig loadConfig() {
        try {
            return ClawBootstrap.loadConfigFile(bootstrap.configPath());
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
     * 从磁盘配置重新注册渠道，使绑定变更对后续入站消息生效。根据条目的 {@code type} 通过 {@link ChannelTypeRegistry} 解析实现，让 chatui、dingtalk、wecom、feishu、github、gitlab 及用户注册的类型走同一热重载路径。{@link ChannelManager#unregister} 在新实例启动前停止旧实例，使钉钉 Stream、webhook 回调等外部传输经历完整重建周期。
     *
     * <p>{@code entry} 为 {@code null}（渠道已移除）或含 {@code disabled=true} 时，只注销，不重新构建。
     */
    private void reloadChannel(String channelId, ChannelConfigEntry entry) {
        if (channelId == null) return;
        if (entry == null || Boolean.TRUE.equals(entry.getDisabled())) {
            if (channelManager.unregister(channelId)) {
                log.info("Unregistered channel '{}' after edit (entry={})", channelId, entry);
            }
            return;
        }
        String type = entry.getType();
        if (type == null || type.isBlank()) {
            log.warn("Skipping reload of '{}': missing 'type' in agentscope.json entry", channelId);
            return;
        }
        ChannelFactory factory = ChannelTypeRegistry.get(type).orElse(null);
        if (factory == null) {
            log.warn(
                    "Skipping reload of '{}': no factory registered for type '{}' (known types:"
                            + " {})",
                    channelId,
                    type,
                    ChannelTypeRegistry.registeredTypes());
            return;
        }
        try {
            ChannelConfig newCfg = entry.toChannelConfig(channelId);
            Map<String, Object> props =
                    entry.getProperties() != null ? entry.getProperties() : Map.of();
            channelManager.unregister(channelId);
            Channel rebuilt = factory.create(channelId, newCfg, props);
            channelManager.register(rebuilt);
            rebuilt.init(bootstrap.gateway());
            if (channelManager.isStarted()) {
                rebuilt.start();
            }
            log.info("Reloaded channel '{}' (type={}) after edit", channelId, type);
        } catch (Exception e) {
            log.warn(
                    "Failed to reload channel '{}' (type={}): {}. Restart required for live"
                            + " changes.",
                    channelId,
                    type,
                    e.getMessage(),
                    e);
        }
    }
}
