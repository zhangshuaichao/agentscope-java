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
package io.agentscope.claw2.marketplace;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.agentscope.claw2.runtime.ClawBootstrap;
import io.agentscope.claw2.runtime.config.AgentscopeConfig;
import io.agentscope.claw2.runtime.config.MarketplaceConfigEntry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
 * 加载、修改并通过临时文件替换重写 {@code agentscope.json} 的 {@code marketplaces} 部分，再驱动 {@link ClawMarketplaceRegistry}，使更改无需重启即可在进程内生效。
 *
 * <p>与 {@code BindingPersistence} 类似，所有本实例的修改通过 {@link ReentrantLock} 串行执行以避免丢失更新。写入使用 {@code tmp + ATOMIC_MOVE}；不支持原子移动时会报错，且未显式 force 文件或目录，不能据此保证断电持久性。
 */
@Component
public class MarketplacePersistence {

    private static final Logger log = LoggerFactory.getLogger(MarketplacePersistence.class);

    private final ClawBootstrap bootstrap;
    private final ClawMarketplaceRegistry registry;
    private final ReentrantLock lock = new ReentrantLock();
    private final ObjectMapper mapper;

    public MarketplacePersistence(ClawBootstrap bootstrap, ClawMarketplaceRegistry registry) {
        this.bootstrap = bootstrap;
        this.registry = registry;
        this.mapper =
                new ObjectMapper()
                        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                        .enable(SerializationFeature.INDENT_OUTPUT);
    }

    /** 对 {@code agentscope.json} 当前市场映射应用 {@code mutator}，持久化结果，再在活动 {@link ClawMarketplaceRegistry} 中重新注册 {@code idsToReload} 中的 ID。若 {@code idsToReload} 中某个 ID 在修改后的映射中已不存在，则注销并关闭旧实例。 */
    public <T> T mutate(MutationFn<T> mutator, List<String> idsToReload) {
        lock.lock();
        try {
            AgentscopeConfig cfg = loadConfig();
            Map<String, MarketplaceConfigEntry> marketplaces =
                    cfg.getMarketplaces() != null ? cfg.getMarketplaces() : new LinkedHashMap<>();
            cfg.setMarketplaces(marketplaces);

            T result = mutator.apply(marketplaces);
            // 【学习提示】先落盘再重建活动实例，不是一个跨磁盘与运行时的事务；重建失败不会回滚已写配置。
            // 此锁也不与 BindingPersistence 的独立锁共享，而两者都改同一 agentscope.json，不能推断跨组件写入互斥。
            writeAtomic(cfg);

            if (idsToReload != null) {
                for (String id : idsToReload) {
                    MarketplaceConfigEntry entry = marketplaces.get(id);
                    if (entry == null) {
                        registry.unregister(id);
                    } else {
                        try {
                            registry.reload(id, entry);
                        } catch (RuntimeException e) {
                            log.warn(
                                    "Failed to (re)register marketplace '{}' after edit: {}",
                                    id,
                                    e.getMessage(),
                                    e);
                            throw new ResponseStatusException(
                                    HttpStatus.BAD_REQUEST,
                                    "Invalid marketplace config for '"
                                            + id
                                            + "': "
                                            + e.getMessage(),
                                    e);
                        }
                    }
                }
            }
            return result;
        } finally {
            lock.unlock();
        }
    }

    /** 不持有写锁读取磁盘上的当前配置，供只读端点获取最近变更后的市场快照。 */
    public AgentscopeConfig currentConfig() {
        return loadConfig();
    }

    /** 用于 {@link #mutate(MutationFn, List)} 修改操作的函数式接口。 */
    @FunctionalInterface
    public interface MutationFn<T> {
        T apply(Map<String, MarketplaceConfigEntry> marketplaces);
    }

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
}
