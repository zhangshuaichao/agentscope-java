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
package io.agentscope.dataagent.web.session;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.agentscope.dataagent.runtime.DataAgentBootstrap;
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
 * 每用户、每会话的最后阅读时间跟踪器，供 Threads 收件箱计算未读标记。
 * <p>会话 {@code lastActivityMs} 大于保存的已读时间时视为<em>未读</em>。
 * 标为已读时更新时间为 {@link
 * System#currentTimeMillis()} 或调用方指定值。
 * <p>状态以扁平 JSON 映射持久化在 {@code .agentscope/session-read-state.json}，跨重启保留。
 * 写入通过临时文件原子重命名完成。
 */
@Component
public class SessionReadStateStore {

    private static final Logger log = LoggerFactory.getLogger(SessionReadStateStore.class);
    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final Path stateFile;
    private final Map<String, Long> lastReadAt = new ConcurrentHashMap<>();

    public SessionReadStateStore(DataAgentBootstrap builderBootstrap) {
        this.stateFile =
                builderBootstrap.cwd().resolve(".agentscope").resolve("session-read-state.json");
        load();
    }

    /** 在 {@code System.currentTimeMillis()} 将用户/会话组合标为已读。 */
    public long markRead(String userId, String sessionKey) {
        return markRead(userId, sessionKey, System.currentTimeMillis());
    }

    /** 在指定纪元毫秒时间将用户/会话组合标为已读。 */
    public long markRead(String userId, String sessionKey, long readAtMs) {
        String k = key(userId, sessionKey);
        lastReadAt.put(k, readAtMs);
        flush();
        return readAtMs;
    }

    /** 返回用户/会话组合的最后已读时间，从未读过时为 {@code 0L}。 */
    public long lastReadAt(String userId, String sessionKey) {
        Long v = lastReadAt.get(key(userId, sessionKey));
        return v != null ? v : 0L;
    }

    /** 会话对该用户是否未读，即 {@code lastActivityMs} 严格大于已读时间；从未阅读的会话视为未读。 */
    public boolean isUnread(String userId, String sessionKey, long lastActivityMs) {
        return lastActivityMs > lastReadAt(userId, sessionKey);
    }

    private static String key(String userId, String sessionKey) {
        return (userId != null ? userId : "__anon__") + sessionKey;
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
            Files.createDirectories(stateFile.getParent());
            Path tmp = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
            // 使用稳定迭代顺序，使写入结果便于比较差异。
            Map<String, Long> ordered = new LinkedHashMap<>(lastReadAt);
            Files.writeString(tmp, MAPPER.writeValueAsString(ordered), StandardCharsets.UTF_8);
            Files.move(
                    tmp,
                    stateFile,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.warn("Failed to persist session read-state to {}: {}", stateFile, e.getMessage());
        }
    }
}
