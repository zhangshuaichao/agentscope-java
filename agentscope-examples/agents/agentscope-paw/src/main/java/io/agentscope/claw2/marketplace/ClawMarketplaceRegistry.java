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

import io.agentscope.claw2.runtime.ClawBootstrap;
import io.agentscope.claw2.runtime.config.MarketplaceConfigEntry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.file.Path;
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
 * claw 管理的 {@link ClawMarketplace} 活动实例注册表，以用户选择的 ID 为键。
 *
 * <p>此处集中将 {@link MarketplaceConfigEntry} 映射为具体实现；增加市场类型时需扩展 {@link #build(String, MarketplaceConfigEntry)} 中的分支。
 *
 * <p>生命周期：{@link MarketplacePersistence} 将新配置写入磁盘后，调用 {@link #reload(String, MarketplaceConfigEntry)} 和 {@link #unregister(String)} 驱动变更。实例被替换或移除时，注册表负责调用 {@link ClawMarketplace#close()}，及时释放旧 Git 克隆或 Nacos 客户端，而非等待 GC。
 */
@Component
public class ClawMarketplaceRegistry {

    private static final Logger log = LoggerFactory.getLogger(ClawMarketplaceRegistry.class);

    private final ClawBootstrap bootstrap;
    private final ConcurrentHashMap<String, ClawMarketplace> instances = new ConcurrentHashMap<>();

    public ClawMarketplaceRegistry(ClawBootstrap bootstrap) {
        this.bootstrap = bootstrap;
    }

    /** 构建启动配置中声明的所有市场。Spring 上下文完成装配后调用一次；单个市场失败会记录日志但不终止启动，避免某个上游配置错误阻止 claw 启动。 */
    @PostConstruct
    public void initFromBootstrapConfig() {
        Map<String, MarketplaceConfigEntry> declared =
                bootstrap.loadedConfig() != null
                        ? bootstrap.loadedConfig().getMarketplaces()
                        : null;
        if (declared == null || declared.isEmpty()) {
            return;
        }
        for (Map.Entry<String, MarketplaceConfigEntry> e : declared.entrySet()) {
            try {
                reload(e.getKey(), e.getValue());
            } catch (RuntimeException ex) {
                log.warn(
                        "Failed to initialise marketplace '{}' from agentscope.json: {}",
                        e.getKey(),
                        ex.getMessage(),
                        ex);
            }
        }
    }

    /** 全部活动市场的快照，按 ID 排序以稳定 UI 展示。 */
    public List<ClawMarketplace> list() {
        List<ClawMarketplace> snapshot = new ArrayList<>(instances.values());
        snapshot.sort(Comparator.comparing(ClawMarketplace::id));
        return snapshot;
    }

    /** 返回给定 ID 已注册的市场。 */
    public Optional<ClawMarketplace> find(String id) {
        if (id == null) return Optional.empty();
        return Optional.ofNullable(instances.get(id));
    }

    /** 判断给定 ID 的市场是否已注册。 */
    public boolean contains(String id) {
        return id != null && instances.containsKey(id);
    }

    /** 根据 {@code entry} 构建市场，替换或首次安装到 {@code id}。新实例就位后才将旧实例 {@link ClawMarketplace#close() closed}，使重新加载期间调用方仍可看到活动条目。 */
    public ClawMarketplace reload(String id, MarketplaceConfigEntry entry) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(entry, "entry");
        ClawMarketplace next = build(id, entry);
        ClawMarketplace previous = instances.put(id, next);
        closeQuietly(previous);
        return next;
    }

    /** 移除并关闭 {@code id} 对应的市场；未注册时无操作。 */
    public boolean unregister(String id) {
        if (id == null) return false;
        ClawMarketplace removed = instances.remove(id);
        if (removed == null) return false;
        closeQuietly(removed);
        return true;
    }

    /** 关闭全部市场；用于应用关闭时释放 Git 克隆及客户端。 */
    @PreDestroy
    public void closeAll() {
        Collection<ClawMarketplace> snapshot = new ArrayList<>(instances.values());
        instances.clear();
        snapshot.forEach(this::closeQuietly);
    }

    /**
     * 根据配置构建市场实例，但不注册。供 {@code MarketplacesController#test} 使用，使连接探测与实际注册走相同构建路径，同时不占用 ID 槽。
     *
     * @throws IllegalArgumentException {@code entry.type} 未知或缺少必填字段时抛出
     */
    public ClawMarketplace build(String id, MarketplaceConfigEntry entry) {
        if (entry.getType() == null || entry.getType().isBlank()) {
            throw new IllegalArgumentException("marketplace '" + id + "' has no type");
        }
        String type = entry.getType().toLowerCase(Locale.ROOT);
        Map<String, Object> props =
                entry.getProperties() != null ? entry.getProperties() : Map.of();
        return switch (type) {
            case "git" -> buildGit(id, props);
            case "nacos" -> buildNacos(id, props);
            default ->
                    throw new IllegalArgumentException(
                            "unsupported marketplace type '"
                                    + entry.getType()
                                    + "' for '"
                                    + id
                                    + "'");
        };
    }

    private ClawMarketplace buildGit(String id, Map<String, Object> props) {
        String remoteUrl = stringProp(props, "remoteUrl");
        if (remoteUrl == null) {
            throw new IllegalArgumentException(
                    "git marketplace '" + id + "' requires a non-empty 'remoteUrl'");
        }
        String branch = stringProp(props, "branch");
        String skillsRoot = stringProp(props, "skillsRoot");
        Path localPath = bootstrap.clawHome().resolve("marketplaces").resolve("git").resolve(id);
        return new GitClawMarketplace(id, remoteUrl, branch, localPath, skillsRoot);
    }

    private ClawMarketplace buildNacos(String id, Map<String, Object> props) {
        String serverAddr = stringProp(props, "serverAddr");
        if (serverAddr == null) {
            throw new IllegalArgumentException(
                    "nacos marketplace '" + id + "' requires a non-empty 'serverAddr'");
        }
        return new NacosClawMarketplace(
                id,
                serverAddr,
                stringProp(props, "namespaceId"),
                stringProp(props, "username"),
                stringProp(props, "password"),
                stringProp(props, "accessKey"),
                stringProp(props, "secretKey"));
    }

    private static String stringProp(Map<String, Object> props, String key) {
        Object v = props.get(key);
        if (v == null) return null;
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private void closeQuietly(ClawMarketplace mp) {
        if (mp == null) return;
        try {
            mp.close();
        } catch (RuntimeException e) {
            log.warn("Failed to close marketplace '{}': {}", mp.id(), e.getMessage(), e);
        }
    }
}
