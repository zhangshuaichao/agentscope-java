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
package io.agentscope.claw2.runtime.session;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 以 JSON 文件（{@code sessions.json}）支持的持久化会话注册表，跨重启跟踪会话元数据。
 *
 * <p>线程安全：使用读写锁允许并发读，写操作串行化。文件通过先写临时文件再重命名的方式发布。
 *
 * <p>存储文件为按 {@code sessionKey} 索引的 JSON 对象，每个值是 {@link StoredEntry}，保存 {@link SessionEntry} 中需要跨重启保留的字段子集。
 */
public final class SessionStore {

    private static final Logger log = LoggerFactory.getLogger(SessionStore.class);
    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final Path storeFile;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final Map<String, StoredEntry> entries = new LinkedHashMap<>();

    /**
     * {@link SessionEntry} 中用于磁盘持久化的 JSON 可序列化子集。使用 {@code @JsonIgnoreProperties(ignoreUnknown = true)}，在添加新字段时提供前向兼容。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StoredEntry(
            String sessionKey,
            String agentId,
            String sessionId,
            String label,
            String kind,
            String spawnedBy,
            int spawnDepth,
            long createdAtMs,
            long lastActivityMs,
            String sessionFilePath,
            String spawnRunId,
            String gateKey) {

        public static StoredEntry from(SessionEntry e) {
            return new StoredEntry(
                    e.sessionKey(),
                    e.agentId(),
                    e.sessionId(),
                    e.label(),
                    e.kind().getValue(),
                    e.spawnedBy(),
                    e.spawnDepth(),
                    e.createdAtMs(),
                    e.lastActivityMs(),
                    e.sessionFilePath(),
                    e.spawnRunId(),
                    e.gateKey());
        }

        public SessionEntry toSessionEntry() {
            SessionKind sk = "main".equals(kind) ? SessionKind.MAIN : SessionKind.SUBAGENT;
            return new SessionEntry(
                    sessionKey,
                    agentId,
                    sessionId,
                    label,
                    sk,
                    spawnedBy,
                    spawnDepth,
                    createdAtMs,
                    lastActivityMs,
                    sessionFilePath,
                    spawnRunId,
                    gateKey);
        }
    }

    public SessionStore(Path storeFile) {
        this.storeFile = storeFile;
    }

    /**
     * 从存储文件加载全部条目到内存，启动时调用一次；文件不存在或为空时从空存储开始。
     */
    public void load() {
        lock.writeLock().lock();
        try {
            entries.clear();
            if (!Files.isRegularFile(storeFile)) {
                return;
            }
            String json = Files.readString(storeFile, StandardCharsets.UTF_8);
            if (json.isBlank()) {
                return;
            }
            Map<String, StoredEntry> loaded =
                    MAPPER.readValue(
                            json, new TypeReference<LinkedHashMap<String, StoredEntry>>() {});
            if (loaded != null) {
                entries.putAll(loaded);
            }
            log.info("Loaded {} session entries from {}", entries.size(), storeFile);
        } catch (IOException e) {
            log.warn("Failed to load session store from {}: {}", storeFile, e.getMessage());
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 持久化单个会话条目，存在则更新，否则插入。
     */
    public void save(SessionEntry entry) {
        lock.writeLock().lock();
        try {
            entries.put(entry.sessionKey(), StoredEntry.from(entry));
            flushToDisk();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 仅更新给定键的 {@code lastActivityMs}，不替换完整条目。
     */
    public void touch(String sessionKey, long lastActivityMs) {
        lock.writeLock().lock();
        try {
            StoredEntry existing = entries.get(sessionKey);
            if (existing == null) {
                return;
            }
            entries.put(
                    sessionKey,
                    new StoredEntry(
                            existing.sessionKey(),
                            existing.agentId(),
                            existing.sessionId(),
                            existing.label(),
                            existing.kind(),
                            existing.spawnedBy(),
                            existing.spawnDepth(),
                            existing.createdAtMs(),
                            lastActivityMs,
                            existing.sessionFilePath(),
                            existing.spawnRunId(),
                            existing.gateKey()));
            flushToDisk();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 按键移除会话条目。
     */
    public void remove(String sessionKey) {
        lock.writeLock().lock();
        try {
            if (entries.remove(sessionKey) != null) {
                flushToDisk();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 返回所有已存储条目的快照。
     */
    public Collection<StoredEntry> listAll() {
        lock.readLock().lock();
        try {
            return List.copyOf(entries.values());
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 按键返回单个条目（若存在）。
     */
    public Optional<StoredEntry> get(String sessionKey) {
        lock.readLock().lock();
        try {
            return Optional.ofNullable(entries.get(sessionKey));
        } finally {
            lock.readLock().unlock();
        }
    }

    public int size() {
        lock.readLock().lock();
        try {
            return entries.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 底层存储文件路径。
     */
    public Path getStoreFile() {
        return storeFile;
    }

    // 【学习提示】先更新内存，再尝试写盘；IOException 只记日志，不回滚内存也不向调用方传播。
    // ATOMIC_MOVE 保证受支持文件系统上的替换原子性，不等于 fsync 持久性，也不提供跨实例锁。
    private void flushToDisk() {
        try {
            Files.createDirectories(storeFile.getParent());
            Path tmp = storeFile.resolveSibling(storeFile.getFileName() + ".tmp");
            byte[] bytes = MAPPER.writeValueAsBytes(entries);
            Files.write(
                    tmp, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(
                    tmp,
                    storeFile,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.warn("Failed to flush session store to {}: {}", storeFile, e.getMessage());
        }
    }
}
