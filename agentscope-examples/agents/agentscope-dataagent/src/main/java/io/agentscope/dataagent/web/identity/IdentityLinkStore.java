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
package io.agentscope.dataagent.web.identity;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 将应用 {@code userId} 映射到该用户其他通道身份（Slack、Discord、GitHub 等）的持久化注册表。
 * 数据保存在 {@code {cwd}/.agentscope/identity-links.json}，结构如下：
 * <pre>{@code
 * {
 *   "user-42": {
 *     "slack":   "U7F9LZK1A",
 *     "discord": "212345678901234567"
 *   },
 *   "user-9": {
 *     "github": "alice"
 *   }
 * }
 * }</pre>
 * <p>身份链接可将消息分发给同一逻辑用户的不同通道身份（{@code /dock_<channel>} 命令关联对应通道身份）。
 * 在 agentscope-dataagent 中，此存储是配置来源：通道适配器据此把入站事件映射到已知 {@code userId}，
 * 并通过匹配通道投递出站消息。
 * <p>【学习提示】身份链接改变消息归属；写入映射前必须确认调用方控制对应外部身份，
 * 不能仅凭用户提交一个外部 ID 就认为完成认证。
 */
public class IdentityLinkStore {

    private static final Logger log = LoggerFactory.getLogger(IdentityLinkStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path file;
    private final ReentrantLock writeLock = new ReentrantLock();
    private final ConcurrentHashMap<String, Map<String, String>> byUser = new ConcurrentHashMap<>();

    public IdentityLinkStore(Path agentscopeDir) {
        this.file = agentscopeDir.resolve("identity-links.json");
        load();
    }

    // -----------------------------------------------------------------
    // 查询
    // -----------------------------------------------------------------

    /** 返回完整用户 → 通道 → externalId 映射的快照。 */
    public Map<String, Map<String, String>> snapshot() {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        byUser.forEach((u, m) -> out.put(u, new LinkedHashMap<>(m)));
        return out;
    }

    /** 返回用户通道链接，不存在时返回空映射。 */
    public Map<String, String> linksFor(String userId) {
        return new LinkedHashMap<>(byUser.getOrDefault(userId, Map.of()));
    }

    /** 解析 {@code userId} 在 {@code channelId} 上的外部身份。 */
    public Optional<String> externalIdFor(String userId, String channelId) {
        Map<String, String> m = byUser.get(userId);
        if (m == null) return Optional.empty();
        return Optional.ofNullable(m.get(channelId));
    }

    /** 反向查找：返回在 {@code channelId} 上的链接匹配 {@code externalId} 的应用 {@code userId}。用于将入站通道原生 ID 转换为规范用户 ID。 */
    public Optional<String> userIdByExternal(String channelId, String externalId) {
        if (channelId == null || externalId == null) return Optional.empty();
        for (var entry : byUser.entrySet()) {
            if (externalId.equals(entry.getValue().get(channelId))) {
                return Optional.of(entry.getKey());
            }
        }
        return Optional.empty();
    }

    // -----------------------------------------------------------------
    // 变更
    // -----------------------------------------------------------------

    /** 记录 {@code userId} 在 {@code channelId} 上对应 {@code externalId}，替换相同用户/通道组合的旧值。 */
    public void link(String userId, String channelId, String externalId) {
        if (userId == null || channelId == null || externalId == null) {
            throw new IllegalArgumentException("userId, channelId, externalId are required");
        }
        writeLock.lock();
        try {
            Map<String, String> links = byUser.computeIfAbsent(userId, k -> new LinkedHashMap<>());
            synchronized (links) {
                links.put(channelId, externalId);
            }
            persist();
            log.info(
                    "Identity link added: user={}, channel={}, externalId={}",
                    userId,
                    channelId,
                    externalId);
        } finally {
            writeLock.unlock();
        }
    }

    /** 移除 {@code (userId, channelId)} 的链接（若存在）。 */
    public boolean unlink(String userId, String channelId) {
        writeLock.lock();
        try {
            Map<String, String> links = byUser.get(userId);
            if (links == null) return false;
            String removed;
            synchronized (links) {
                removed = links.remove(channelId);
                if (links.isEmpty()) byUser.remove(userId);
            }
            if (removed != null) {
                persist();
                return true;
            }
            return false;
        } finally {
            writeLock.unlock();
        }
    }

    // -----------------------------------------------------------------
    // 持久化
    // -----------------------------------------------------------------

    private void load() {
        if (!Files.isRegularFile(file)) return;
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            Map<String, Map<String, String>> data =
                    MAPPER.readValue(
                            json, new TypeReference<Map<String, Map<String, String>>>() {});
            data.forEach((u, m) -> byUser.put(u, new LinkedHashMap<>(m)));
            log.info("Loaded {} identity-link records from {}", byUser.size(), file);
        } catch (IOException e) {
            log.warn("Failed to load identity-links from {}: {}", file, e.getMessage());
        }
    }

    private void persist() {
        try {
            Files.createDirectories(file.getParent());
            ObjectMapper m =
                    MAPPER.copy()
                            .enable(
                                    com.fasterxml.jackson.databind.SerializationFeature
                                            .INDENT_OUTPUT);
            m.setSerializationInclusion(JsonInclude.Include.NON_NULL);
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, m.writeValueAsString(snapshot()), StandardCharsets.UTF_8);
            Files.move(
                    tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.error("Failed to persist identity-links: {}", e.getMessage());
            throw new RuntimeException(e);
        }
    }
}
