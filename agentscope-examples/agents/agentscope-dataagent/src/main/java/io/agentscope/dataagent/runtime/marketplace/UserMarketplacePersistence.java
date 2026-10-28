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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.dataagent.runtime.config.MarketplaceConfigEntry;
import io.agentscope.dataagent.web.persistence.jpa.UserMarketplaceEntity;
import io.agentscope.dataagent.web.persistence.jpa.UserMarketplaceRepository;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/** 基于 JPA 的市场持久化。每个用户的各市场保存为 {@code dataagent_user_marketplace} 中的一行，以 {@code (user_id, marketplace_id)} 为键。<em>不修改</em> {@code agentscope.json}，因为 DataAgent 将其视为平台状态而非单用户配置。<p>所有变更均在 Spring 管理事务中执行，成功后驱动 {@link UserMarketplaceRegistry#reload(String, String, MarketplaceConfigEntry)} 或 {@link UserMarketplaceRegistry#unregister(String, String)} 更新运行中的内存注册表，无需重启。 */
@Component
public class UserMarketplacePersistence {

    private static final Logger log = LoggerFactory.getLogger(UserMarketplacePersistence.class);

    private final UserMarketplaceRepository repository;
    private final UserMarketplaceRegistry registry;
    private final ObjectMapper mapper = new ObjectMapper();

    public UserMarketplacePersistence(
            UserMarketplaceRepository repository, @Lazy UserMarketplaceRegistry registry) {
        this.repository = repository;
        this.registry = registry;
    }

    /** 将 {@code userId} 拥有的全部市场加载为按 ID 排序的 {@code (id → entry)} 映射，可直接装载到 {@link UserMarketplaceRegistry}。 */
    public Map<String, MarketplaceConfigEntry> loadAllForUser(String userId) {
        List<UserMarketplaceEntity> rows = repository.findByUserIdOrderByMarketplaceIdAsc(userId);
        Map<String, MarketplaceConfigEntry> out = new LinkedHashMap<>();
        for (UserMarketplaceEntity row : rows) {
            try {
                out.put(row.getMarketplaceId(), toEntry(row));
            } catch (RuntimeException ex) {
                log.warn(
                        "Skipping malformed marketplace row id={} for user='{}': {}",
                        row.getId(),
                        userId,
                        ex.getMessage());
            }
        }
        return out;
    }

    /** 按 ID 加载 {@code userId} 的单个市场（若存在）。 */
    public Optional<MarketplaceConfigEntry> load(String userId, String marketplaceId) {
        return repository.findByUserIdAndMarketplaceId(userId, marketplaceId).map(this::toEntry);
    }

    /** {@code userId} 是否拥有给定 ID 的市场。 */
    public boolean exists(String userId, String marketplaceId) {
        return repository.existsByUserIdAndMarketplaceId(userId, marketplaceId);
    }

    /** 为 {@code userId} 插入新市场行。调用方负责确保 ID 尚不存在；先调用 {@link #exists(String, String)}，以便向用户返回 409。 */
    @Transactional
    public void insert(String userId, String marketplaceId, MarketplaceConfigEntry entry) {
        UserMarketplaceEntity row =
                new UserMarketplaceEntity(
                        userId, marketplaceId, entry.getType(), writePropertiesJson(entry));
        row.setCreatedAt(Instant.now());
        row.setUpdatedAt(Instant.now());
        repository.save(row);
        registry.reload(userId, marketplaceId, entry);
    }

    /** 替换 {@code (userId, marketplaceId)} 对应的已有行。 */
    @Transactional
    public void update(String userId, String marketplaceId, MarketplaceConfigEntry entry) {
        UserMarketplaceEntity row =
                repository
                        .findByUserIdAndMarketplaceId(userId, marketplaceId)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Marketplace not found: " + marketplaceId));
        row.setType(entry.getType());
        row.setPropertiesJson(writePropertiesJson(entry));
        row.setUpdatedAt(Instant.now());
        repository.save(row);
        registry.reload(userId, marketplaceId, entry);
    }

    /** 移除数据行并注销运行实例。行已不存在时不做操作。 */
    @Transactional
    public boolean delete(String userId, String marketplaceId) {
        if (!repository.existsByUserIdAndMarketplaceId(userId, marketplaceId)) {
            return false;
        }
        repository.deleteByUserIdAndMarketplaceId(userId, marketplaceId);
        registry.unregister(userId, marketplaceId);
        return true;
    }

    // -----------------------------------------------------------------
    // 辅助方法
    // -----------------------------------------------------------------

    private MarketplaceConfigEntry toEntry(UserMarketplaceEntity row) {
        MarketplaceConfigEntry entry = new MarketplaceConfigEntry();
        entry.setType(row.getType());
        Map<String, Object> props = readPropertiesJson(row.getPropertiesJson());
        if (props != null) {
            for (Map.Entry<String, Object> e : props.entrySet()) {
                entry.setProperty(e.getKey(), e.getValue());
            }
        }
        return entry;
    }

    private String writePropertiesJson(MarketplaceConfigEntry entry) {
        Map<String, Object> props =
                entry.getProperties() != null ? entry.getProperties() : Map.of();
        try {
            return mapper.writeValueAsString(props);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Cannot serialise marketplace properties: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> readPropertiesJson(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Cannot deserialise marketplace properties: " + e.getMessage(), e);
        }
    }
}
