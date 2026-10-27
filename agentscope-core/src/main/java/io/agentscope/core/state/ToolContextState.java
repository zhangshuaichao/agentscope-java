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
package io.agentscope.core.state;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 文件操作工具（Read / Write / Edit）的读取缓存支撑状态。
 *
 * <p>维护有序的 {@link ReadCacheEntry} LRU 列表，同时限制条目数量和总字节数。
 * 读取缓存时会核对文件当前的修改时间；访问过期条目时将其移除。
 * 所有文件系统交互都运行在 {@link Schedulers#boundedElastic()} 上，
 * 避免阻塞 Reactor 的非阻塞调度器。
 *
 * <p>只有配置字段（{@code maxCacheFiles}、{@code maxCacheBytes}、
 * {@code activatedGroups}）参与 JSON 序列化。内存中的 LRU 缓存本身属于运行时状态，
 * 反序列化时会重建为空缓存。
 *
 * <p>【学习提示】恢复会话不等于恢复文件缓存内容。缓存命中还要检查文件修改时间；
 * 子智能体创建登记则由单独的 spawn_registry 属性序列化，不能与读取缓存混为一谈。
 */
@JsonPropertyOrder({"max_cache_files", "max_cache_bytes", "activated_groups", "spawn_registry"})
public final class ToolContextState {

    private final int maxCacheFiles;
    private final double maxCacheBytes;
    private final List<ReadCacheEntry> readFileCache = new ArrayList<>();
    private final List<String> activatedGroups;
    private final Map<String, SpawnEntry> spawnRegistry;

    private ToolContextState(Builder builder) {
        if (builder.maxCacheFiles <= 1) {
            throw new IllegalArgumentException("maxCacheFiles must be > 1");
        }
        if (builder.maxCacheBytes <= 10000) {
            throw new IllegalArgumentException("maxCacheBytes must be > 10000");
        }
        this.maxCacheFiles = builder.maxCacheFiles;
        this.maxCacheBytes = builder.maxCacheBytes;
        this.activatedGroups = new ArrayList<>(builder.activatedGroups);
        this.spawnRegistry = new ConcurrentHashMap<>(builder.spawnRegistry);
    }

    @JsonCreator
    static ToolContextState fromJson(
            @JsonProperty("max_cache_files") Integer maxCacheFiles,
            @JsonProperty("max_cache_bytes") Double maxCacheBytes,
            @JsonProperty("activated_groups") List<String> activatedGroups,
            @JsonProperty("spawn_registry") Map<String, SpawnEntry> spawnRegistry) {
        Builder b = builder();
        if (maxCacheFiles != null) {
            b.maxCacheFiles(maxCacheFiles);
        }
        if (maxCacheBytes != null) {
            b.maxCacheBytes(maxCacheBytes);
        }
        if (activatedGroups != null) {
            activatedGroups.forEach(b::addActivatedGroup);
        }
        if (spawnRegistry != null) {
            b.spawnRegistry.putAll(spawnRegistry);
        }
        return b.build();
    }

    @JsonProperty("max_cache_files")
    public int getMaxCacheFiles() {
        return maxCacheFiles;
    }

    @JsonProperty("max_cache_bytes")
    public double getMaxCacheBytes() {
        return maxCacheBytes;
    }

    /**
     * 调用时缓存列表的快照，修改该快照不会影响实时缓存。
     *
     * <p>【学习提示】快照用来观察状态，不是写入入口；要修改实际缓存，必须走维护缓存约束的方法。
     */
    @JsonIgnore
    public List<ReadCacheEntry> getReadFileCache() {
        synchronized (readFileCache) {
            return List.copyOf(readFileCache);
        }
    }

    @JsonProperty("activated_groups")
    public synchronized List<String> getActivatedGroups() {
        return List.copyOf(activatedGroups);
    }

    /**
     * 将已激活工具组替换为指定列表。
     *
     * @param groups 新的已激活工具组；{@code null} 会清空列表
     */
    public synchronized void setActivatedGroups(List<String> groups) {
        this.activatedGroups.clear();
        if (groups != null) {
            for (String group : groups) {
                if (group != null && !activatedGroups.contains(group)) {
                    this.activatedGroups.add(group);
                }
            }
        }
    }

    /**
     * 将指定工具组加入激活集合，保持顺序并忽略 null 和重复项。
     * 这是原子的读—改—写操作：并发调用方（例如同一会话内的并行工具调用）
     * 不会丢失彼此新增的组。
     *
     * @param groups 要激活的工具组；{@code null} 不执行操作
     */
    public synchronized void addActivatedGroups(List<String> groups) {
        if (groups == null) {
            return;
        }
        for (String group : groups) {
            if (group != null && !activatedGroups.contains(group)) {
                activatedGroups.add(group);
            }
        }
    }

    /**
     * 从激活集合中移除指定工具组。采用原子读—改—写操作，
     * 保留其余工具组的顺序。
     *
     * @param groups 要停用的工具组；{@code null} 不执行操作
     */
    public synchronized void removeActivatedGroups(List<String> groups) {
        if (groups == null) {
            return;
        }
        activatedGroups.removeAll(groups);
    }

    /**
     * 原子地移除 {@code toRemove} 中的组，再加入 {@code toAdd}，
     * 保持顺序并忽略 null 和重复项。这是单次读—改—写操作，因此并发执行的
     * 追加型工具（例如技能激活）的结果，不会被交错执行的替换型工具
     * （例如 {@code reset_equipped_tools}）在读取与写入之间覆盖丢失。
     *
     * @param toRemove 要移除的工具组（例如 META 作用域组名）；{@code null} 不执行移除
     * @param toAdd 要加入的工具组；{@code null} 不执行添加
     */
    public synchronized void replaceActivatedGroups(
            Collection<String> toRemove, Collection<String> toAdd) {
        if (toRemove != null) {
            activatedGroups.removeIf(toRemove::contains);
        }
        if (toAdd != null) {
            for (String group : toAdd) {
                if (group != null && !activatedGroups.contains(group)) {
                    activatedGroups.add(group);
                }
            }
        }
    }

    @JsonProperty("spawn_registry")
    public Map<String, SpawnEntry> getSpawnRegistry() {
        return Map.copyOf(spawnRegistry);
    }

    /**
     * 注册已创建的子智能体条目，使其可跨会话恢复和副本路由继续使用。
     *
     * @param key 智能体键（例如 {@code agent:general-purpose:uuid}）
     * @param entry 可序列化的创建元数据
     */
    public void putSpawnEntry(String key, SpawnEntry entry) {
        if (key != null && entry != null) {
            this.spawnRegistry.put(key, entry);
        }
    }

    /** 按键移除子智能体创建条目。 */
    public void removeSpawnEntry(String key) {
        if (key != null) {
            this.spawnRegistry.remove(key);
        }
    }

    /**
     * 已创建子智能体的可序列化元数据，与父智能体状态一起持久化，
     * 使 {@code agent_send} 能在会话恢复后或其他副本上按需重建实时 {@code Agent} 实例。
     */
    public record SpawnEntry(
            @JsonProperty("key") String key,
            @JsonProperty("agent_id") String agentId,
            @JsonProperty("session_id") String sessionId,
            @JsonProperty("label") String label,
            @JsonProperty("depth") int depth) {

        @JsonCreator
        public SpawnEntry(
                @JsonProperty("key") String key,
                @JsonProperty("agent_id") String agentId,
                @JsonProperty("session_id") String sessionId,
                @JsonProperty("label") String label,
                @JsonProperty("depth") int depth) {
            this.key = key;
            this.agentId = agentId;
            this.sessionId = sessionId;
            this.label = label;
            this.depth = depth;
        }
    }

    /**
     * 查找 {@code filePath} 对应的缓存条目。若文件修改时间已变化或文件已不存在，
     * 则移除条目并返回空值。
     */
    public Mono<Optional<ReadCacheEntry>> getCache(String filePath) {
        return Mono.fromCallable(
                        () -> {
                            synchronized (readFileCache) {
                                Iterator<ReadCacheEntry> iterator = readFileCache.iterator();
                                while (iterator.hasNext()) {
                                    ReadCacheEntry entry = iterator.next();
                                    if (!entry.filePath().equals(filePath)) {
                                        continue;
                                    }
                                    Double current = currentMtime(filePath);
                                    if (current != null && current == entry.updatedAt()) {
                                        return Optional.of(entry);
                                    }
                                    iterator.remove();
                                    return Optional.<ReadCacheEntry>empty();
                                }
                                return Optional.<ReadCacheEntry>empty();
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 将 {@code lines} 作为 {@code filePath} 的内容加入缓存，移除较旧的条目，
     * 同时满足 {@code maxCacheFiles} 和 {@code maxCacheBytes} 限制。
     * 若无法取得文件修改时间，则静默跳过插入。
     */
    public Mono<Void> cacheFile(String filePath, List<String> lines) {
        return Mono.<Void>fromCallable(
                        () -> {
                            Double updatedAt = currentMtime(filePath);
                            if (updatedAt == null) {
                                return null;
                            }
                            double newEntryBytes = approximateKilobytes(lines);
                            synchronized (readFileCache) {
                                readFileCache.removeIf(entry -> entry.filePath().equals(filePath));
                                while (readFileCache.size() >= maxCacheFiles) {
                                    readFileCache.remove(0);
                                }
                                double currentBytes =
                                        readFileCache.stream()
                                                .mapToDouble(ReadCacheEntry::bytes)
                                                .sum();
                                while (!readFileCache.isEmpty()
                                        && currentBytes + newEntryBytes > maxCacheBytes) {
                                    currentBytes -= readFileCache.remove(0).bytes();
                                }
                                readFileCache.add(
                                        new ReadCacheEntry(
                                                lines, updatedAt, newEntryBytes, filePath));
                            }
                            return null;
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    private static Double currentMtime(String filePath) {
        try {
            return Files.getLastModifiedTime(Path.of(filePath)).toMillis() / 1000.0;
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            return null;
        }
    }

    private static double approximateKilobytes(List<String> lines) {
        long bytes = 0L;
        for (String line : lines) {
            bytes += line.getBytes(StandardCharsets.UTF_8).length;
        }
        return bytes / 1024.0;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private int maxCacheFiles = 100;
        private double maxCacheBytes = 25_000;
        private final List<String> activatedGroups = new ArrayList<>();
        private final Map<String, SpawnEntry> spawnRegistry = new LinkedHashMap<>();

        private Builder() {}

        public Builder maxCacheFiles(int maxCacheFiles) {
            this.maxCacheFiles = maxCacheFiles;
            return this;
        }

        public Builder maxCacheBytes(double maxCacheBytes) {
            this.maxCacheBytes = maxCacheBytes;
            return this;
        }

        public Builder addActivatedGroup(String groupName) {
            if (groupName != null) {
                this.activatedGroups.add(groupName);
            }
            return this;
        }

        public ToolContextState build() {
            return new ToolContextState(this);
        }
    }
}
