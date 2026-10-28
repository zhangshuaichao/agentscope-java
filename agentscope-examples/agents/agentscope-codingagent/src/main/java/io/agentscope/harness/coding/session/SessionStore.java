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
package io.agentscope.harness.coding.session;

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
 * 以 JSON 文件（{@code sessions.json}）为后端的持久会话注册表，对应 OpenClaw 跨重启跟踪元数据的 {@code sessions.json} 存储。
 *
 * <p>线程安全：使用读写锁，允许读者并发、写入串行；写锁持有期间读取仍可能等待。文件写入采用先写临时文件再重命名的方式。
 *
 * <p>存储文件为以 {@code sessionKey} 为键的 JSON 对象，每个值是 {@link StoredEntry}，保存 {@link SessionEntry} 中需要跨重启保留的字段子集。
 *
 * <p>【学习提示】读写锁只保护当前对象所在进程内的访问；临时文件加重命名也不等于多进程事务。另一个 JVM 同时写同一文件可能覆盖更新，持久化失败仅记录日志时，内存状态与磁盘状态也可能不同。会话元数据持久化与运行中智能体缓存是不同层。
 */
public final class SessionStore {

    private static final Logger log = LoggerFactory.getLogger(SessionStore.class);
    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final Path storeFile;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final Map<String, StoredEntry> entries = new LinkedHashMap<>();

    /**
     * 用于磁盘持久化的 {@link SessionEntry} 可 JSON 序列化子集。使用 {@code @JsonIgnoreProperties(ignoreUnknown = true)}，使新增字段时保持前向兼容。
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
            String gateKey,
            String userId) {

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
                    e.gateKey(),
                    e.userId());
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
                    gateKey,
                    userId);
        }
    }

    public SessionStore(Path storeFile) {
        this.storeFile = storeFile;
    }

    /**
     * 将存储文件全部条目加载到内存。启动时调用一次；文件不存在或为空时，从空存储开始。
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
     * 持久化单个会话条目，存在则更新。
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
     * 只更新给定键的 {@code lastActivityMs}，不替换完整条目。
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
                            existing.gateKey(),
                            existing.userId()));
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
     * 返回全部存储条目的快照。
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
