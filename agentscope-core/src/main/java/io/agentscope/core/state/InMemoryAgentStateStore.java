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

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link AgentStateStore} 接口的内存实现。
 *
 * <p>使用 {@link ConcurrentHashMap} 在内存中保存智能体状态，
 * 适用于不要求跨重启持久化的单进程应用。
 *
 * <p><b>槽位键：</b>{@code (userId, sessionId)} 二元组标识存储槽位。
 * {@code null} userId 映射到字面量为 {@code "__anon__"} 的分区，
 * 避免匿名会话与实际名称为 {@code ""} 的用户冲突。
 *
 * <p><b>线程安全：</b>此类是线程安全的。
 *
 * <p><b>限制：</b>
 * <ul>
 *   <li>JVM 退出后状态丢失
 *   <li>不适用于分布式环境
 *   <li>内存使用量随会话数量增长
 * </ul>
 */
public class InMemoryAgentStateStore implements AgentStateStore {

    /** 为传入 {@code userId == null} 的调用方保留的哨兵命名空间。 */
    private static final String ANON_USER = "__anon__";

    /** 用户 →（sessionId → SessionData） */
    private final Map<String, Map<String, SessionData>> users = new ConcurrentHashMap<>();

    @Override
    public void save(String userId, String sessionId, String key, State value) {
        SessionData data = lookupOrCreate(userId, sessionId);
        data.setSingleState(key, value);
    }

    @Override
    public boolean supportsVersioning() {
        return true;
    }

    @Override
    public <T extends State> VersionedState<T> getVersioned(
            String userId, String sessionId, String key, Class<T> type) {
        SessionData data = lookup(userId, sessionId);
        if (data == null) {
            return new VersionedState<>(null, 0L);
        }
        VersionedEntry entry = data.getVersionedSingleState(key);
        if (entry == null) {
            return new VersionedState<>(null, 0L);
        }
        State state = entry.value();
        if (!type.isInstance(state)) {
            throw new ClassCastException(
                    "State for key '"
                            + key
                            + "' is of type "
                            + state.getClass().getName()
                            + ", expected "
                            + type.getName());
        }
        return new VersionedState<>(type.cast(state), entry.version());
    }

    @Override
    public long saveIfVersion(
            String userId, String sessionId, String key, State value, long expectedVersion) {
        if (expectedVersion == UNVERSIONED) {
            // 在同一个同步区间内完成写入和版本读取；若先 save() 再单独
            // 查询，共享实例时可能返回其他并发写入方的版本。
            // 不委派给可重写的 save()（与 JDBC #3220 保持一致）。
            SessionData data = lookupOrCreate(userId, sessionId);
            return data.setSingleStateReturningVersion(key, value);
        }
        SessionData data = lookupOrCreate(userId, sessionId);
        return data.casSingleState(key, value, expectedVersion);
    }

    @Override
    public void save(String userId, String sessionId, String key, List<? extends State> values) {
        SessionData data = lookupOrCreate(userId, sessionId);
        data.setListState(key, values);
    }

    @Override
    public <T extends State> Optional<T> get(
            String userId, String sessionId, String key, Class<T> type) {
        SessionData data = lookup(userId, sessionId);
        if (data == null) {
            return Optional.empty();
        }
        State state = data.getSingleState(key);
        if (state == null) {
            return Optional.empty();
        }
        if (!type.isInstance(state)) {
            throw new ClassCastException(
                    "State for key '"
                            + key
                            + "' is of type "
                            + state.getClass().getName()
                            + ", expected "
                            + type.getName());
        }
        return Optional.of(type.cast(state));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends State> List<T> getList(
            String userId, String sessionId, String key, Class<T> itemType) {
        SessionData data = lookup(userId, sessionId);
        if (data == null) {
            return List.of();
        }
        List<? extends State> list = data.getListState(key);
        if (list == null) {
            return List.of();
        }
        return (List<T>) list;
    }

    @Override
    public boolean exists(String userId, String sessionId) {
        return lookup(userId, sessionId) != null;
    }

    @Override
    public void delete(String userId, String sessionId) {
        Map<String, SessionData> userBucket = users.get(normalizeUser(userId));
        if (userBucket != null) {
            userBucket.remove(requireSessionId(sessionId));
        }
    }

    @Override
    public void delete(String userId, String sessionId, String key) {
        SessionData data = lookup(userId, sessionId);
        if (data != null) {
            data.removeSingleState(key);
        }
    }

    @Override
    public Set<String> listSessionIds(String userId) {
        Map<String, SessionData> userBucket = users.get(normalizeUser(userId));
        if (userBucket == null) {
            return Set.of();
        }
        return new HashSet<>(userBucket.keySet());
    }

    /** 当前存储的 {@code (userId, sessionId)} 槽位总数。 */
    public int getSessionCount() {
        return users.values().stream().mapToInt(Map::size).sum();
    }

    /** 清空所有数据（便于测试）。 */
    public void clearAll() {
        users.clear();
    }

    private SessionData lookupOrCreate(String userId, String sessionId) {
        return users.computeIfAbsent(normalizeUser(userId), u -> new ConcurrentHashMap<>())
                .computeIfAbsent(requireSessionId(sessionId), s -> new SessionData());
    }

    private SessionData lookup(String userId, String sessionId) {
        Map<String, SessionData> userBucket = users.get(normalizeUser(userId));
        if (userBucket == null) {
            return null;
        }
        return userBucket.get(requireSessionId(sessionId));
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

    private record VersionedEntry(State value, long version) {}

    /** 保存会话数据的内部类。 */
    private static class SessionData {
        private final Map<String, VersionedEntry> singleStates = new ConcurrentHashMap<>();
        private final Map<String, List<State>> listStates = new ConcurrentHashMap<>();

        synchronized void setSingleState(String key, State value) {
            setSingleStateReturningVersion(key, value);
        }

        /**
         * 无条件写入，并原子地返回本次写入获得的版本。
         * 供 {@code saveIfVersion(..., UNVERSIONED)} 使用，
         * 确保调用方不会读到其他并发写入方的版本。
         */
        synchronized long setSingleStateReturningVersion(String key, State value) {
            VersionedEntry prev = singleStates.get(key);
            long next = prev == null ? 1L : prev.version() + 1L;
            singleStates.put(key, new VersionedEntry(value, next));
            return next;
        }

        synchronized long casSingleState(String key, State value, long expectedVersion) {
            VersionedEntry prev = singleStates.get(key);
            long current = prev == null ? 0L : prev.version();
            if (current != expectedVersion) {
                return UNVERSIONED;
            }
            long next = expectedVersion + 1L;
            singleStates.put(key, new VersionedEntry(value, next));
            return next;
        }

        State getSingleState(String key) {
            VersionedEntry entry = singleStates.get(key);
            return entry == null ? null : entry.value();
        }

        VersionedEntry getVersionedSingleState(String key) {
            return singleStates.get(key);
        }

        void removeSingleState(String key) {
            singleStates.remove(key);
        }

        void setListState(String key, List<? extends State> values) {
            listStates.put(key, List.copyOf(values));
        }

        List<? extends State> getListState(String key) {
            return listStates.get(key);
        }
    }
}
