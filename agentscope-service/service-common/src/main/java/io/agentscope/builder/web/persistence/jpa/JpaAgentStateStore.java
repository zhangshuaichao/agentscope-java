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
package io.agentscope.builder.web.persistence.jpa;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.builder.web.managed.service.DeletedSessionRegistry;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.core.state.VersionedState;
import io.agentscope.core.util.JacksonJsonCodec;
import io.agentscope.core.util.JsonCodec;
import io.agentscope.core.util.JsonUtils;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.transaction.annotation.Transactional;

/**
 * agentscope-builder 使用的数据库版 {@link AgentStateStore}。
 * <p>与 Builder 目录其余部分共用 Spring {@code DataSource} / JPA 模式
 * （默认 H2 文件，{@code jdbc} 配置环境可使用 MySQL/PostgreSQL），无需 Redis 即可在副本间共享短期 Brain 状态。
 * <p>通过数据平面的 {@code DataPlaneConfig#agentStateStore} 注册为 Spring Bean；
 * 此类本身不标注 {@code @Component}，方便测试提供替代 {@link AgentStateStore}。
 * <p>【学习提示】数据库共享状态不等于同一会话允许并发推进。轮次执行权需要协调层约束；
 * 状态持久化也不构成外部工具调用的跨系统事务。
 */
@Transactional
public class JpaAgentStateStore implements AgentStateStore {

    private static final String ANON_USER = "__anon__";

    private final AgentStateEntityRepository repository;
    private final DeletedSessionRegistry deletedSessions;
    private final JsonCodec codec;
    private final ObjectMapper mapper;

    public JpaAgentStateStore(
            AgentStateEntityRepository repository, DeletedSessionRegistry deletedSessions) {
        this.repository = repository;
        this.deletedSessions = deletedSessions;
        this.codec = JsonUtils.getJsonCodec();
        if (!(codec instanceof JacksonJsonCodec jackson)) {
            throw new IllegalStateException(
                    "JpaAgentStateStore requires JacksonJsonCodec for list deserialization");
        }
        this.mapper = jackson.getObjectMapper();
    }

    @Override
    public void save(String userId, String sessionId, String key, State value) {
        validate(sessionId, key);
        try {
            upsert(userId, sessionId, key, false, codec.toJson(value));
        } catch (Exception ex) {
            throw new RuntimeException("Failed to save agent state: " + key, ex);
        }
    }

    @Override
    public void save(String userId, String sessionId, String key, List<? extends State> values) {
        validate(sessionId, key);
        try {
            upsert(userId, sessionId, key, true, codec.toJson(values == null ? List.of() : values));
        } catch (Exception ex) {
            throw new RuntimeException("Failed to save agent state list: " + key, ex);
        }
    }

    /**
     * 重写仅为确保读取数据行时事务已开启。
     * <p>若继承接口默认方法，其声明属于 {@link AgentStateStore}，Spring 无法找到事务属性，
     * 类级 {@link Transactional} 也不会生效；随后调用 {@link #get} 又是绕过代理的自调用。
     * 没有事务就无法读取 {@code state_data} 背后的 PostgreSQL 大对象，导致每个轮次都从空上下文开始。
     */
    @Override
    @Transactional(readOnly = true)
    public <T extends State> VersionedState<T> getVersioned(
            String userId, String sessionId, String key, Class<T> type) {
        return new VersionedState<>(get(userId, sessionId, key, type).orElse(null), UNVERSIONED);
    }

    @Override
    @Transactional(readOnly = true)
    public <T extends State> Optional<T> get(
            String userId, String sessionId, String key, Class<T> type) {
        validate(sessionId, key);
        return repository
                .findByUserIdAndSessionIdAndStateKey(normalizeUser(userId), sessionId, key)
                .filter(e -> !e.isListKind())
                .map(
                        e -> {
                            try {
                                return codec.fromJson(e.getStateData(), type);
                            } catch (Exception ex) {
                                throw new RuntimeException(
                                        "Failed to deserialize agent state: " + key, ex);
                            }
                        });
    }

    @Override
    @Transactional(readOnly = true)
    public <T extends State> List<T> getList(
            String userId, String sessionId, String key, Class<T> itemType) {
        validate(sessionId, key);
        Optional<AgentStateEntity> row =
                repository.findByUserIdAndSessionIdAndStateKey(
                        normalizeUser(userId), sessionId, key);
        if (row.isEmpty() || !row.get().isListKind()) {
            return List.of();
        }
        try {
            JavaType listType =
                    mapper.getTypeFactory().constructCollectionType(List.class, itemType);
            List<T> values = mapper.readValue(row.get().getStateData(), listType);
            return values == null ? List.of() : values;
        } catch (Exception ex) {
            throw new RuntimeException("Failed to deserialize agent state list: " + key, ex);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public boolean exists(String userId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        return repository.existsByUserIdAndSessionId(normalizeUser(userId), sessionId);
    }

    @Override
    public void delete(String userId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        repository.deleteByUserIdAndSessionId(normalizeUser(userId), sessionId);
    }

    @Override
    public void delete(String userId, String sessionId, String key) {
        validate(sessionId, key);
        repository.deleteByUserIdAndSessionIdAndStateKey(normalizeUser(userId), sessionId, key);
    }

    @Override
    @Transactional(readOnly = true)
    public Set<String> listSessionIds(String userId) {
        List<String> ids = repository.findDistinctSessionIdsByUserId(normalizeUser(userId));
        return ids == null || ids.isEmpty() ? Collections.emptySet() : new LinkedHashSet<>(ids);
    }

    private void upsert(
            String userId, String sessionId, String key, boolean listKind, String json) {
        // 会话删除后正在退出的轮次仍可能保存状态，
        // 这会重新创建清理流程刚删除的数据行。
        if (deletedSessions.isDeleted(sessionId)) {
            return;
        }
        String uid = normalizeUser(userId);
        long now = System.currentTimeMillis();
        AgentStateEntity entity =
                repository
                        .findByUserIdAndSessionIdAndStateKey(uid, sessionId, key)
                        .orElseGet(AgentStateEntity::new);
        entity.setUserId(uid);
        entity.setSessionId(sessionId);
        entity.setStateKey(key);
        entity.setListKind(listKind);
        entity.setStateData(json);
        entity.setUpdatedAt(now);
        repository.save(entity);
    }

    private static String normalizeUser(String userId) {
        return userId == null || userId.isBlank() ? ANON_USER : userId;
    }

    private static void validate(String sessionId, String key) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key must not be blank");
        }
    }
}
