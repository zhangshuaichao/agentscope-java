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
package io.agentscope.dataagent.runtime.marketplace;

import io.agentscope.dataagent.runtime.config.MarketplaceConfigEntry;
import io.agentscope.dataagent.web.workspace.WorkspaceManagerFactory;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 每用户 {@link DataAgentMarketplace} 实例的运行时注册表，以 {@code userId} → 市场 ID 为键。
 * <p>用户配置的市场相互私有：不同用户可复用相同 ID 而不冲突，且不查看彼此条目。
 * 相比 claw 的 {@code ClawMarketplaceRegistry}，增加外层用户 ID 维度，不在启动时预加载；
 * 用户数量可能很大，因此实例按需构建。
 * <p>生命周期：{@code UserMarketplacePersistence} 写入行后，通过
 * {@link #reload(String, String, MarketplaceConfigEntry)} 与 {@link #unregister(String, String)} 驱动变更。
 * 注册表负责对被替换/移除实例调用 {@link DataAgentMarketplace#close()}，及时释放旧 Git 克隆/Nacos 客户端。
 */
@Component
public class UserMarketplaceRegistry {

    private static final Logger log = LoggerFactory.getLogger(UserMarketplaceRegistry.class);

    private final WorkspaceManagerFactory workspaceFactory;
    private final UserMarketplacePersistence persistence;
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, DataAgentMarketplace>>
            byUser = new ConcurrentHashMap<>();
    private final Map<String, DataAgentMarketplaceFactory> factories;

    public UserMarketplaceRegistry(
            WorkspaceManagerFactory workspaceFactory,
            UserMarketplacePersistence persistence,
            List<DataAgentMarketplaceFactoryRegistration> registrations) {
        this.workspaceFactory = workspaceFactory;
        this.persistence = persistence;
        Map<String, DataAgentMarketplaceFactory> map = new java.util.HashMap<>();
        for (DataAgentMarketplaceFactoryRegistration r : registrations) {
            map.put(r.type().toLowerCase(Locale.ROOT), r.factory());
        }
        this.factories = Map.copyOf(map);
    }

    /** 可由 Spring 注入的 {@link DataAgentMarketplaceFactory} 类型注册。任意配置类提供返回此记录的 {@code @Bean}，即可向注册表开放对应市场类型。 */
    public record DataAgentMarketplaceFactoryRegistration(
            String type, DataAgentMarketplaceFactory factory) {}

    /** 返回 {@code userId} 所有运行中市场的快照，按 ID 排序。该用户首次调用时，从数据库延迟装载每用户映射。 */
    public List<DataAgentMarketplace> list(String userId) {
        ConcurrentHashMap<String, DataAgentMarketplace> map = ensureLoaded(userId);
        List<DataAgentMarketplace> snapshot = new ArrayList<>(map.values());
        snapshot.sort(Comparator.comparing(DataAgentMarketplace::id));
        return snapshot;
    }

    /** 返回 {@code userId} 的指定 ID 市场（若已注册）。 */
    public Optional<DataAgentMarketplace> find(String userId, String id) {
        if (userId == null || id == null) return Optional.empty();
        ConcurrentHashMap<String, DataAgentMarketplace> map = ensureLoaded(userId);
        return Optional.ofNullable(map.get(id));
    }

    /** {@code userId} 是否拥有给定 ID 的市场。 */
    public boolean contains(String userId, String id) {
        if (userId == null || id == null) return false;
        return ensureLoaded(userId).containsKey(id);
    }

    /** 使用 {@code entry} 构建的实例替换（或首次安装）{@code userId} 在 {@code id} 处的市场。新实例就位后关闭旧实例（若有）。 */
    public DataAgentMarketplace reload(String userId, String id, MarketplaceConfigEntry entry) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(entry, "entry");
        DataAgentMarketplace next = build(userId, id, entry);
        ConcurrentHashMap<String, DataAgentMarketplace> map = ensureLoaded(userId);
        DataAgentMarketplace previous = map.put(id, next);
        closeQuietly(previous);
        return next;
    }

    /** 移除并关闭 {@code userId} 在 {@code id} 处的市场。未注册时不做操作。 */
    public boolean unregister(String userId, String id) {
        if (userId == null || id == null) return false;
        ConcurrentHashMap<String, DataAgentMarketplace> map = byUser.get(userId);
        if (map == null) return false;
        DataAgentMarketplace removed = map.remove(id);
        if (removed == null) return false;
        closeQuietly(removed);
        return true;
    }

    /**
     * 从配置构建市场实例，但不注册。供 {@code MarketplacesController#testTransient} 使用，
     * 使连接探测经过与真实注册相同的路径，同时不因探测失败占用 ID 槽位。
     * @throws IllegalArgumentException 当 {@code entry.type} 未知或缺少必填字段时
     */
    public DataAgentMarketplace build(String userId, String id, MarketplaceConfigEntry entry) {
        if (entry.getType() == null || entry.getType().isBlank()) {
            throw new IllegalArgumentException("marketplace '" + id + "' has no type");
        }
        String type = entry.getType().toLowerCase(Locale.ROOT);
        Map<String, Object> props =
                entry.getProperties() != null ? entry.getProperties() : Map.of();
        DataAgentMarketplaceFactory factory = factories.get(type);
        if (factory == null) {
            throw new IllegalArgumentException(
                    "unsupported marketplace type '"
                            + entry.getType()
                            + "' for '"
                            + id
                            + "' — register a "
                            + DataAgentMarketplaceFactory.class.getSimpleName()
                            + " bean for this type");
        }
        return factory.create(userId, id, props, workspaceFactory);
    }

    /**
     * 插入市场存储的 SPI。v1 以 {@code "local"} 类型提供
     * {@link io.agentscope.dataagent.runtime.marketplace.LocalApprovalMarketplace}。
     * {@code GitDataAgentMarketplace} / {@code NacosDataAgentMarketplace} 适配类虽在源码中，
     * 是否作为可用类型取决于应用工厂注册，不能仅凭类存在就假定已启用。
     */
    @FunctionalInterface
    public interface DataAgentMarketplaceFactory {
        DataAgentMarketplace create(
                String userId, String id, Map<String, Object> props, WorkspaceManagerFactory wsf);
    }

    /** 关闭全部市场，供应用关闭时使用，避免泄漏 Git 克隆或客户端。 */
    @PreDestroy
    public void closeAll() {
        Collection<ConcurrentHashMap<String, DataAgentMarketplace>> snapshot =
                new ArrayList<>(byUser.values());
        byUser.clear();
        for (ConcurrentHashMap<String, DataAgentMarketplace> map : snapshot) {
            for (DataAgentMarketplace mp : map.values()) {
                closeQuietly(mp);
            }
        }
    }

    private ConcurrentHashMap<String, DataAgentMarketplace> ensureLoaded(String userId) {
        return byUser.computeIfAbsent(userId, this::hydrateFromStore);
    }

    private ConcurrentHashMap<String, DataAgentMarketplace> hydrateFromStore(String userId) {
        ConcurrentHashMap<String, DataAgentMarketplace> map = new ConcurrentHashMap<>();
        for (Map.Entry<String, MarketplaceConfigEntry> e :
                persistence.loadAllForUser(userId).entrySet()) {
            try {
                map.put(e.getKey(), build(userId, e.getKey(), e.getValue()));
            } catch (RuntimeException ex) {
                log.warn(
                        "Failed to initialise marketplace '{}' for user '{}': {}",
                        e.getKey(),
                        userId,
                        ex.getMessage(),
                        ex);
            }
        }
        return map;
    }

    private void closeQuietly(DataAgentMarketplace mp) {
        if (mp == null) return;
        try {
            mp.close();
        } catch (RuntimeException e) {
            log.warn("Failed to close marketplace '{}': {}", mp.id(), e.getMessage(), e);
        }
    }
}
