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

import io.agentscope.core.util.JsonUtils;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 基于 JSON 文件的 {@link AgentStateStore} 实现。
 *
 * <p>磁盘布局：
 *
 * <pre>
 * &lt;root&gt;/
 *   __anon__/                       ← anonymous (userId == null) sessions
 *     &lt;safe(sessionId)&gt;/
 *       agent_state.json
 *       memory_messages.jsonl
 *       memory_messages.hash
 *   &lt;safe(userId)&gt;/                ← per-user sessions
 *     &lt;safe(sessionId)&gt;/
 *       agent_state.json
 *       ...
 * </pre>
 *
 * <p>{@code safe(...)} 在字符串匹配 {@code ^[a-zA-Z0-9_\-.]+$} 时保留原值；
 * 否则使用不带填充的 Base64-URL 编码，确保文件系统能够接受。
 *
 * <p>功能包括：原子文件操作、UTF-8 编码、平稳处理不存在的会话，
 * 以及基于哈希判断列表状态应追加还是重写。
 */
public class JsonFileAgentStateStore implements AgentStateStore {

    /** 为传入 {@code userId == null} 的调用方保留的哨兵目录。 */
    private static final String ANON_USER = "__anon__";

    /** 文件系统安全字符的模式：字母、数字、下划线、连字符和点。 */
    private static final Pattern SAFE_FILENAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_\\-.]+$");

    private final Path rootDirectory;

    /**
     * 使用默认根目录创建 {@code JsonFileAgentStateStore}：
     * {@code ~/.agentscope/state}。
     */
    public JsonFileAgentStateStore() {
        this(Paths.get(System.getProperty("user.home"), ".agentscope", "state"));
    }

    /**
     * 以指定目录为根目录创建 {@code JsonFileAgentStateStore}。
     *
     * @param rootDirectory 用户／会话子目录所在的根目录
     */
    public JsonFileAgentStateStore(Path rootDirectory) {
        this.rootDirectory = rootDirectory;
        try {
            Files.createDirectories(rootDirectory);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create root directory: " + rootDirectory, e);
        }
    }

    @Override
    public void save(String userId, String sessionId, String key, State value) {
        Path file = getStatePath(userId, sessionId, key);
        ensureDirectoryExists(file.getParent());
        try {
            String json = JsonUtils.getJsonCodec().toPrettyJson(value);
            atomicWriteString(file, json);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save state: " + key, e);
        }
    }

    @Override
    public void save(String userId, String sessionId, String key, List<? extends State> values) {
        Path file = getListPath(userId, sessionId, key);
        Path hashFile = getHashPath(userId, sessionId, key);
        ensureDirectoryExists(file.getParent());

        try {
            String currentHash = ListHashUtil.computeHash(values);
            String storedHash = readHashFile(hashFile);
            long existingCount = countLines(file);
            boolean needsFullRewrite =
                    ListHashUtil.needsFullRewrite(values, storedHash, (int) existingCount);
            if (needsFullRewrite) {
                rewriteEntireList(file, values);
            } else if (values.size() > existingCount) {
                List<? extends State> newItems = values.subList((int) existingCount, values.size());
                appendToList(file, newItems);
            }
            // 否则：没有变化，跳过写入
            writeHashFile(hashFile, currentHash);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save list: " + key, e);
        }
    }

    private void rewriteEntireList(Path file, List<? extends State> values) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (BufferedWriter writer =
                newUtf8ReplacingWriter(
                        tmp,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE)) {
            for (State item : values) {
                writer.write(JsonUtils.getJsonCodec().toJson(item));
                writer.newLine();
            }
        }
        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private void appendToList(Path file, List<? extends State> items) throws IOException {
        try (BufferedWriter writer =
                newUtf8ReplacingWriter(
                        file, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            for (State item : items) {
                writer.write(JsonUtils.getJsonCodec().toJson(item));
                writer.newLine();
            }
        }
    }

    @Override
    public <T extends State> Optional<T> get(
            String userId, String sessionId, String key, Class<T> type) {
        Path file = getStatePath(userId, sessionId, key);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            return Optional.of(JsonUtils.getJsonCodec().fromJson(json, type));
        } catch (IOException e) {
            throw new RuntimeException("Failed to load state: " + key, e);
        }
    }

    @Override
    public <T extends State> List<T> getList(
            String userId, String sessionId, String key, Class<T> itemType) {
        Path file = getListPath(userId, sessionId, key);
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            List<T> result = new ArrayList<>();
            try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isBlank()) {
                        result.add(JsonUtils.getJsonCodec().fromJson(line, itemType));
                    }
                }
            }
            return result;
        } catch (IOException e) {
            throw new RuntimeException("Failed to load list: " + key, e);
        }
    }

    @Override
    public boolean exists(String userId, String sessionId) {
        return Files.exists(getSessionDir(userId, sessionId));
    }

    @Override
    public void delete(String userId, String sessionId) {
        Path dir = getSessionDir(userId, sessionId);
        if (Files.exists(dir)) {
            deleteDirectory(dir);
        }
    }

    @Override
    public void delete(String userId, String sessionId, String key) {
        try {
            Files.deleteIfExists(getStatePath(userId, sessionId, key));
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete state: " + key, e);
        }
    }

    @Override
    public Set<String> listSessionIds(String userId) {
        Path userDir = rootDirectory.resolve(safeSegment(normalizeUser(userId)));
        if (!Files.isDirectory(userDir)) {
            return Set.of();
        }
        try (Stream<Path> dirs = Files.list(userDir)) {
            Set<String> result = new HashSet<>();
            for (Path d : (Iterable<Path>) dirs.filter(Files::isDirectory)::iterator) {
                result.add(decodeSegment(d.getFileName().toString()));
            }
            return result;
        } catch (IOException e) {
            throw new RuntimeException("Failed to list sessions", e);
        }
    }

    /** 返回配置的根目录（用于诊断）。 */
    public Path getRootDirectory() {
        return rootDirectory;
    }

    /** 清除所有用户的全部会话，返回删除的会话目录数量。 */
    public Mono<Integer> clearAllSessions() {
        return Mono.fromSupplier(
                        () -> {
                            try {
                                if (!Files.exists(rootDirectory)) {
                                    return 0;
                                }
                                int deletedCount = 0;
                                try (Stream<Path> userDirs = Files.list(rootDirectory)) {
                                    for (Path userDir :
                                            (Iterable<Path>)
                                                    userDirs.filter(Files::isDirectory)::iterator) {
                                        try (Stream<Path> sessionDirs = Files.list(userDir)) {
                                            for (Path sessionDir :
                                                    (Iterable<Path>)
                                                            sessionDirs.filter(Files::isDirectory)
                                                                    ::iterator) {
                                                deleteDirectory(sessionDir);
                                                deletedCount++;
                                            }
                                        }
                                    }
                                }
                                return deletedCount;
                            } catch (IOException e) {
                                throw new RuntimeException("Failed to clear sessions", e);
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 返回存放 {@code (userId, sessionId)} 所有状态文件的目录。
     * 子类若需要不同的布局，可重写此方法。
     */
    protected Path getSessionDir(String userId, String sessionId) {
        return rootDirectory
                .resolve(safeSegment(normalizeUser(userId)))
                .resolve(safeSegment(requireSessionId(sessionId)));
    }

    private Path getStatePath(String userId, String sessionId, String key) {
        return getSessionDir(userId, sessionId).resolve(key + ".json");
    }

    private Path getListPath(String userId, String sessionId, String key) {
        return getSessionDir(userId, sessionId).resolve(key + ".jsonl");
    }

    private Path getHashPath(String userId, String sessionId, String key) {
        return getSessionDir(userId, sessionId).resolve(key + ".hash");
    }

    private String readHashFile(Path hashFile) {
        if (!Files.exists(hashFile)) {
            return null;
        }
        try {
            return Files.readString(hashFile, StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return null;
        }
    }

    private void writeHashFile(Path hashFile, String hash) throws IOException {
        atomicWriteString(hashFile, hash);
    }

    private static void atomicWriteString(Path file, String content) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (BufferedWriter writer =
                newUtf8ReplacingWriter(
                        tmp,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE)) {
            writer.write(content);
        }
        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static BufferedWriter newUtf8ReplacingWriter(Path file, StandardOpenOption... options)
            throws IOException {
        return new BufferedWriter(
                new OutputStreamWriter(
                        Files.newOutputStream(file, options), utf8ReplacingEncoder()));
    }

    private static CharsetEncoder utf8ReplacingEncoder() {
        return StandardCharsets.UTF_8
                .newEncoder()
                .replaceWith(new byte[] {(byte) 0xEF, (byte) 0xBF, (byte) 0xBD})
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
    }

    private long countLines(Path file) {
        if (!Files.exists(file)) {
            return 0;
        }
        try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
            return lines.filter(line -> !line.isBlank()).count();
        } catch (IOException e) {
            return 0;
        }
    }

    private void ensureDirectoryExists(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create directory: " + dir, e);
        }
    }

    private void deleteDirectory(Path dir) {
        try {
            if (Files.exists(dir)) {
                try (Stream<Path> paths = Files.walk(dir)) {
                    paths.sorted(Comparator.reverseOrder())
                            .forEach(
                                    path -> {
                                        try {
                                            Files.delete(path);
                                        } catch (IOException e) {
                                            throw new RuntimeException(
                                                    "Failed to delete: " + path, e);
                                        }
                                    });
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete directory: " + dir, e);
        }
    }

    private static String normalizeUser(String userId) {
        return userId == null || userId.isBlank() ? ANON_USER : userId;
    }

    private static String requireSessionId(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        if (sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        return sessionId;
    }

    private static String safeSegment(String value) {
        if (SAFE_FILENAME_PATTERN.matcher(value).matches()) {
            return value;
        }
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeSegment(String segment) {
        if (SAFE_FILENAME_PATTERN.matcher(segment).matches()) {
            return segment;
        }
        try {
            return new String(Base64.getUrlDecoder().decode(segment), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return segment;
        }
    }
}
