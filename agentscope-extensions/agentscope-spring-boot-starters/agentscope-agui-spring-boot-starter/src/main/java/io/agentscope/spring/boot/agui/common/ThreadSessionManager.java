/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.spring.boot.agui.common;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agui.AguiUtil;
import io.agentscope.core.state.AgentState;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 按 {@code (userId, threadId)} 管理智能体会话，以支持服务端记忆。
 *
 * <p>启用后，同一用户和线程的请求复用同一智能体实例，从而跨请求保留对话历史。不同用户即使使用同一线程 ID，也不会共享会话槽。
 *
 * <p><b>用法：</b>
 *
 * <pre>{@code
 * ThreadSessionManager manager = new ThreadSessionManager(1000, 30);
 *
 * Agent agent =
 *         manager.getOrCreateAgent(
 *                 "user-1", "thread-123", "default", () -> createAgent());
 *
 * boolean hasMemory =
 *         manager.hasMemory(
 *                 RuntimeContext.builder()
 *                         .userId("user-1")
 *                         .sessionId("thread-123")
 *                         .build());
 *
 * manager.cleanupExpiredSessions();
 * }</pre>
 */
public class ThreadSessionManager {

    private static final Logger logger = LoggerFactory.getLogger(ThreadSessionManager.class);

    /**
     * 用于 {@code userId == null} 调用方的哨兵命名空间。
     *
     * <p>必须与 {@code ReActAgent#slotKey} 使用的匿名哨兵（{@value #ANON_USER}）保持一致。
     */
    static final String ANON_USER = "__anon__";

    private final Map<SessionKey, ThreadSession> sessions = new ConcurrentHashMap<>();
    private final int maxSessions;
    private final int sessionTimeoutMinutes;

    /**
     * 创建 ThreadSessionManager。
     *
     * @param maxSessions 维护的最大会话数
     * @param sessionTimeoutMinutes 会话超时时间，单位为分钟（0 表示不过期）
     */
    public ThreadSessionManager(int maxSessions, int sessionTimeoutMinutes) {
        this.maxSessions = maxSessions;
        this.sessionTimeoutMinutes = sessionTimeoutMinutes;
    }

    /**
     * 在匿名用户槽下，获取或创建指定 threadId 的智能体。
     *
     * @param threadId 线程标识
     * @param agentId 智能体类型标识
     * @param agentFactory 需要时用于创建智能体的工厂
     * @return 此线程的智能体
     */
    public Agent getOrCreateAgent(String threadId, String agentId, Supplier<Agent> agentFactory) {
        return getOrCreateAgent(null, threadId, agentId, agentFactory);
    }

    /**
     * 获取或创建指定用户和线程的智能体。
     *
     * <p>此方法通过并发映射协调创建；同一 {@code (userId, threadId)} 的并发请求共享同一智能体实例。
     *
     * @param userId 用户标识；匿名用户可为 {@code null}
     * @param threadId 线程标识
     * @param agentId 智能体类型标识
     * @param agentFactory 需要时用于创建智能体的工厂
     * @return 此用户和线程的智能体
     */
    public Agent getOrCreateAgent(
            String userId, String threadId, String agentId, Supplier<Agent> agentFactory) {
        // 【学习提示】会话键区分用户与线程，但本方法不验证 userId 身份；认证后的身份应由上下文解析器注入。
        // compute 只协调映射更新，不会串行化随后对同一智能体的运行，也不会把工厂返回的共享单例变成独立实例。
        ensureCapacity();
        return sessions.compute(
                        sessionKey(userId, threadId),
                        (k, existing) ->
                                upsertSession(
                                        existing, userId, threadId, agentId, null, agentFactory))
                .getAgent();
    }

    /**
     * 确保匿名用户槽下存在指定 threadId 的会话。
     *
     * @param threadId 线程标识
     * @param agentId 智能体类型标识
     * @param name 线程显示名称（可为 null）
     * @param agentFactory 需要时用于创建智能体的工厂
     * @return 此线程的会话
     */
    public ThreadSession ensureSession(
            String threadId, String agentId, String name, Supplier<Agent> agentFactory) {
        return ensureSession(null, threadId, agentId, name, agentFactory);
    }

    /**
     * 确保指定用户和线程的会话存在，必要时创建。
     *
     * @param userId 用户标识；匿名用户可为 {@code null}
     * @param threadId 线程标识
     * @param agentId 智能体类型标识
     * @param name 线程显示名称（可为 null）
     * @param agentFactory 需要时用于创建智能体的工厂
     * @return 此用户和线程的会话
     */
    public ThreadSession ensureSession(
            String userId,
            String threadId,
            String agentId,
            String name,
            Supplier<Agent> agentFactory) {
        ensureCapacity();
        return sessions.compute(
                sessionKey(userId, threadId),
                (k, existing) ->
                        upsertSession(existing, userId, threadId, agentId, name, agentFactory));
    }

    /**
     * 检查给定运行时上下文的会话是否存在且包含记忆。
     *
     * <p>会话以 {@link RuntimeContext#getUserId()} 和 {@link RuntimeContext#getSessionId()} 为键。智能体为 harness 包装器时，检查内部 {@link ReActAgent}，但不关闭它。
     *
     * @param runtimeContext 标识用户和线程的运行时上下文
     * @return 会话存在且智能体记忆非空时返回 true
     */
    public boolean hasMemory(RuntimeContext runtimeContext) {
        if (runtimeContext == null || runtimeContext.getSessionId() == null) {
            return false;
        }
        ThreadSession session =
                sessions.get(sessionKey(runtimeContext.getUserId(), runtimeContext.getSessionId()));
        if (session == null) {
            return false;
        }

        ReActAgent reActAgent = AguiUtil.asReActAgent(session.getAgent());
        if (reActAgent == null) {
            return false;
        }
        AgentState state = reActAgent.getAgentState(runtimeContext);
        return state != null && !state.getContext().isEmpty();
    }

    /**
     * 获取匿名用户槽下已有的 threadId 会话。
     *
     * @param threadId 线程标识
     * @return 包含会话的 Optional；不存在时为空
     */
    public Optional<ThreadSession> getSession(String threadId) {
        return getSession(null, threadId);
    }

    /**
     * 获取指定用户和线程已有的会话。
     *
     * @param userId 用户标识；匿名用户可为 {@code null}
     * @param threadId 线程标识
     * @return 包含会话的 Optional；不存在时为空
     */
    public Optional<ThreadSession> getSession(String userId, String threadId) {
        return Optional.ofNullable(sessions.get(sessionKey(userId, threadId)));
    }

    /**
     * 返回以 threadId 为键的全部会话的不可修改快照。
     *
     * <p>两个用户使用同一线程 ID 时，迭代顺序靠后的条目会覆盖快照中的前者。应使用各值的 {@link ThreadSession#getUserId()} 与 {@link ThreadSession#getThreadId()} 共同识别租户身份。
     *
     * @return 会话快照
     */
    public Map<String, ThreadSession> getSessions() {
        Map<String, ThreadSession> snapshot = new LinkedHashMap<>();
        for (ThreadSession session : sessions.values()) {
            snapshot.put(session.getThreadId(), session);
        }
        return Collections.unmodifiableMap(snapshot);
    }

    /**
     * 移除匿名用户槽下指定 threadId 的会话。
     *
     * @param threadId 线程标识
     * @return 移除了会话时返回 true
     */
    public boolean removeSession(String threadId) {
        return removeSession(null, threadId);
    }

    /**
     * 按用户和线程移除会话。
     *
     * @param userId 用户标识；匿名用户可为 {@code null}
     * @param threadId 线程标识
     * @return 移除了会话时返回 true
     */
    public boolean removeSession(String userId, String threadId) {
        return sessions.remove(sessionKey(userId, threadId)) != null;
    }

    /** 清理空闲时长超过超时限制的会话。 */
    // 【学习提示】这里及移除／清空路径只删除会话映射，没有关闭 Agent、终止在途运行或清除外部持久化状态；
    // 内存缓存淘汰与资源释放是不同职责，调用方仍需规划共享资源的生命周期。
    public void cleanupExpiredSessions() {
        if (sessionTimeoutMinutes <= 0) {
            return;
        }

        Instant cutoff = Instant.now().minusSeconds(sessionTimeoutMinutes * 60L);
        int removed = 0;

        var iterator = sessions.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (entry.getValue().getLastAccess().isBefore(cutoff)) {
                iterator.remove();
                removed++;
            }
        }

        if (removed > 0) {
            logger.debug("Cleaned up {} expired sessions", removed);
        }
    }

    private ThreadSession upsertSession(
            ThreadSession existing,
            String userId,
            String threadId,
            String agentId,
            String name,
            Supplier<Agent> agentFactory) {
        if (existing == null) {
            logger.debug(
                    "Creating new session for user {} threadId {}",
                    normalizeUser(userId),
                    threadId);
            ThreadSession created =
                    new ThreadSession(userId, threadId, agentId, agentFactory.get());
            if (name != null && !name.isBlank()) {
                created.setName(name);
            }
            return created;
        }
        if (!existing.getAgentId().equals(agentId)) {
            logger.debug(
                    "Agent type changed for user {} threadId {}: {} -> {}",
                    normalizeUser(userId),
                    threadId,
                    existing.getAgentId(),
                    agentId);
            ThreadSession replacement =
                    new ThreadSession(userId, threadId, agentId, agentFactory.get());
            replacement.setName(name != null && !name.isBlank() ? name : existing.getName());
            replacement.setArchived(existing.isArchived());
            return replacement;
        }
        if (name != null && !name.isBlank()) {
            existing.setName(name);
        }
        existing.updateLastAccess();
        return existing;
    }

    private static SessionKey sessionKey(String userId, String threadId) {
        return new SessionKey(normalizeUser(userId), threadId);
    }

    private static String normalizeUser(String userId) {
        return userId == null || userId.isBlank() ? ANON_USER : userId;
    }

    private void ensureCapacity() {
        if (sessions.size() >= maxSessions) {
            cleanupExpiredSessions();
            if (sessions.size() >= maxSessions) {
                removeOldestSession();
            }
        }
    }

    /** 移除最旧会话，为新会话腾出空间。 */
    private void removeOldestSession() {
        SessionKey oldestKey = null;
        Instant oldestTime = Instant.MAX;

        for (var entry : sessions.entrySet()) {
            if (entry.getValue().getLastAccess().isBefore(oldestTime)) {
                oldestTime = entry.getValue().getLastAccess();
                oldestKey = entry.getKey();
            }
        }

        if (oldestKey != null) {
            sessions.remove(oldestKey);
            logger.debug(
                    "Removed oldest session: user {} thread {}",
                    oldestKey.userId(),
                    oldestKey.threadId());
        }
    }

    /**
     * 获取当前活动会话数。
     *
     * @return 会话数量
     */
    public int getSessionCount() {
        return sessions.size();
    }

    /** 清空所有会话。 */
    public void clear() {
        sessions.clear();
    }

    private record SessionKey(String userId, String threadId) {}

    /** 包含智能体及其元数据的线程会话。 */
    public static class ThreadSession {

        private final String userId;
        private final String threadId;
        private final String agentId;
        private final Agent agent;
        private final Instant createdAt;
        private Instant lastAccess;
        private volatile String name;
        private volatile boolean archived;

        ThreadSession(String userId, String threadId, String agentId, Agent agent) {
            this.userId = normalizeUser(userId);
            this.threadId = threadId;
            this.agentId = agentId;
            this.agent = agent;
            Instant now = Instant.now();
            this.createdAt = now;
            this.lastAccess = now;
        }

        public String getUserId() {
            return userId;
        }

        public String getThreadId() {
            return threadId;
        }

        public String getAgentId() {
            return agentId;
        }

        public Agent getAgent() {
            return agent;
        }

        public Instant getCreatedAt() {
            return createdAt;
        }

        public Instant getLastAccess() {
            return lastAccess;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public boolean isArchived() {
            return archived;
        }

        public void setArchived(boolean archived) {
            this.archived = archived;
        }

        public void updateLastAccess() {
            this.lastAccess = Instant.now();
        }
    }
}
