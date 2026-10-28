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
package io.agentscope.claw2.web.session;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.agentscope.claw2.runtime.ClawBootstrap;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 逐会话记录“最后已读时间”，供线程收件箱推导未读标记。
 *
 * <p>会话 {@code lastActivityMs} 大于已保存的最后已读时间时视为<em>未读</em>。标记已读会将时间更新为 {@link System#currentTimeMillis()} 或调用方传入值。
 *
 * <p>以扁平 JSON 映射持久化到 {@code ${clawHome}/session-read-state.json}，使已读状态跨重启保留；写入通过临时文件重命名替换。
 */
@Component
public class SessionReadStateStore {

    private static final Logger log = LoggerFactory.getLogger(SessionReadStateStore.class);
    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final Path stateFile;
    private final Map<String, Long> lastReadAt = new ConcurrentHashMap<>();

    public SessionReadStateStore(ClawBootstrap bootstrap) {
        this.stateFile = bootstrap.clawHome().resolve("session-read-state.json");
        load();
    }

    /** 以 {@code System.currentTimeMillis()} 将 {@code sessionKey} 标记为已读。 */
    public long markRead(String sessionKey) {
        return markRead(sessionKey, System.currentTimeMillis());
    }

    /** 以指定 epoch 毫秒时间戳将 {@code sessionKey} 标记为已读。 */
    public long markRead(String sessionKey, long readAtMs) {
        lastReadAt.put(sessionKey, readAtMs);
        flush();
        return readAtMs;
    }

    /** 返回 {@code sessionKey} 的最后已读时间；从未读过时返回 {@code 0L}。 */
    public long lastReadAt(String sessionKey) {
        Long v = lastReadAt.get(sessionKey);
        return v != null ? v : 0L;
    }

    /** 判断会话是否未读，即 {@code lastActivityMs} 是否严格大于已存最后已读时间；从未标记已读的会话使用默认已读时间参与比较。 */
    public boolean isUnread(String sessionKey, long lastActivityMs) {
        return lastActivityMs > lastReadAt(sessionKey);
    }

    private synchronized void load() {
        if (!Files.isRegularFile(stateFile)) {
            return;
        }
        try {
            String json = Files.readString(stateFile, StandardCharsets.UTF_8);
            Map<String, Long> raw =
                    MAPPER.readValue(json, new TypeReference<Map<String, Long>>() {});
            if (raw != null) {
                lastReadAt.putAll(raw);
            }
        } catch (IOException e) {
            log.warn("Failed to load session read-state from {}: {}", stateFile, e.getMessage());
        }
    }

    private synchronized void flush() {
        try {
            Path parent = stateFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
            Map<String, Long> ordered = new LinkedHashMap<>(lastReadAt);
            Files.writeString(tmp, MAPPER.writeValueAsString(ordered), StandardCharsets.UTF_8);
            try {
                Files.move(
                        tmp,
                        stateFile,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(tmp, stateFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.warn("Failed to persist session read-state to {}: {}", stateFile, e.getMessage());
        }
    }
}
