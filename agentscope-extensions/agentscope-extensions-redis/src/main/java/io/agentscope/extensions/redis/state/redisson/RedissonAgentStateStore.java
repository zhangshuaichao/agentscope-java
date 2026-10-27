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
package io.agentscope.extensions.redis.state.redisson;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.core.state.VersionedState;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.extensions.redis.state.RedisAgentStateStore;
import io.agentscope.extensions.redis.state.RedisStateVersionSupport;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.redisson.api.RBucket;
import org.redisson.api.RKeys;
import org.redisson.api.RList;
import org.redisson.api.RScript;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 使用 Redisson 的 Redis 会话状态存储实现。
 *
 * @deprecated 请改用 {@link io.agentscope.extensions.redis.state.RedisAgentStateStore} 并设置 redissonClient。
 * 统一实现支持多种 Redis 客户端；基础用法可使用简单构造器，高级配置使用 {@link RedisAgentStateStore#builder()}。
 *
 * <p>状态键结构：
 * <ul><li>单值状态：{@code {prefix}{sessionId}:{stateKey}}，JSON 字符串。</li>
 * <li>列表状态：{@code {prefix}{sessionId}:{stateKey}:list}，JSON 项列表。</li>
 * <li>会话标记：{@code {prefix}{sessionId}:_keys}，跟踪全部状态键的集合。</li></ul>
 *
 * <p>支持列表增量追加、Jackson 类型化序列化与会话键自动跟踪。
 */
@Deprecated
public class RedissonAgentStateStore implements AgentStateStore {

    private static final String DEFAULT_KEY_PREFIX = "agentscope:session:";
    private static final String KEYS_SUFFIX = ":_keys";
    private static final String LIST_SUFFIX = ":list";

    private final RedissonClient redissonClient;
    private final String keyPrefix;

    private RedissonAgentStateStore(Builder builder) {
        if (builder.keyPrefix == null || builder.keyPrefix.trim().isEmpty()) {
            throw new IllegalArgumentException("Key prefix cannot be null or empty");
        }
        if (builder.redissonClient == null) {
            throw new IllegalArgumentException("RedissonClient cannot be null");
        }
        this.keyPrefix = builder.keyPrefix;
        this.redissonClient = builder.redissonClient;
    }

    /**
     * 创建 {@link RedissonAgentStateStore} 构建器。
     * @return 新构建器实例
     */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public boolean supportsVersioning() {
        return true;
    }

    @Override
    public void save(String userId, String sessionId, String key, State value) {
        evalSave(userId, sessionId, key, value, RedisStateVersionSupport.UNCONDITIONAL);
    }

    @Override
    public <T extends State> VersionedState<T> getVersioned(
            String userId, String sessionId, String key, Class<T> type) {
        String slotId = slotId(userId, sessionId);
        String redisKey = getStateKey(slotId, key);
        String versionKey = RedisStateVersionSupport.versionKey(redisKey);

        try {
            RBucket<String> bucket = redissonClient.getBucket(redisKey, StringCodec.INSTANCE);
            String json = bucket.get();
            if (json == null) {
                return new VersionedState<>(null, 0L);
            }
            RBucket<String> versionBucket =
                    redissonClient.getBucket(versionKey, StringCodec.INSTANCE);
            long version = RedisStateVersionSupport.parseVersion(json, versionBucket.get());
            return new VersionedState<>(JsonUtils.getJsonCodec().fromJson(json, type), version);
        } catch (Exception e) {
            throw new RuntimeException("Failed to get versioned state: " + key, e);
        }
    }

    @Override
    public long saveIfVersion(
            String userId, String sessionId, String key, State value, long expectedVersion) {
        if (expectedVersion == UNVERSIONED) {
            // evalSave 使用 UNCONDITIONAL 哨兵时已直接返回新版本，
            // 无需回读；也无法用 State.class 读取，
            // 因为 State 是 Jackson 无法实例化的标记接口。
            return evalSave(userId, sessionId, key, value, RedisStateVersionSupport.UNCONDITIONAL);
        }
        return evalSave(userId, sessionId, key, value, Long.toString(expectedVersion));
    }

    private long evalSave(
            String userId, String sessionId, String key, State value, String expectedVersionArg) {
        String slotId = slotId(userId, sessionId);
        String redisKey = getStateKey(slotId, key);
        String versionKey = RedisStateVersionSupport.versionKey(redisKey);
        String keysKey = getKeysKey(slotId);

        try {
            String json = JsonUtils.getJsonCodec().toJson(value);
            List<String> scriptKeys =
                    RedisStateVersionSupport.saveScriptKeys(redisKey, versionKey, keysKey);
            List<String> scriptArgs = List.of(json, expectedVersionArg, key);
            Object result =
                    redissonClient
                            .getScript(StringCodec.INSTANCE)
                            .eval(
                                    RScript.Mode.READ_WRITE,
                                    RedisStateVersionSupport.SAVE_SCRIPT,
                                    RScript.ReturnType.LONG,
                                    new ArrayList<>(scriptKeys),
                                    scriptArgs.toArray());
            long newVersion = ((Number) result).longValue();
            return newVersion == -1L ? UNVERSIONED : newVersion;
        } catch (Exception e) {
            throw new RuntimeException("Failed to save state: " + key, e);
        }
    }

    @Override
    public void save(String userId, String sessionId, String key, List<? extends State> values) {
        String slotId = slotId(userId, sessionId);
        String redisKey = getListKey(slotId, key);
        String keysKey = getKeysKey(slotId);

        try {
            RList<String> rList = redissonClient.getList(redisKey, StringCodec.INSTANCE);

            // 读取当前列表长度，以支持增量追加。
            int existingCount = rList.size();

            // 仅追加新增项。
            if (values.size() > existingCount) {
                List<? extends State> newItems = values.subList(existingCount, values.size());

                for (State item : newItems) {
                    String json = JsonUtils.getJsonCodec().toJson(item);
                    rList.add(json);
                }
            }

            // 在会话键集合中跟踪此键。
            RSet<String> keysSet = redissonClient.getSet(keysKey, StringCodec.INSTANCE);
            keysSet.add(key + LIST_SUFFIX);
        } catch (Exception e) {
            throw new RuntimeException("Failed to save list: " + key, e);
        }
    }

    @Override
    public <T extends State> Optional<T> get(
            String userId, String sessionId, String key, Class<T> type) {
        String slotId = slotId(userId, sessionId);
        String redisKey = getStateKey(slotId, key);

        try {
            RBucket<String> bucket = redissonClient.getBucket(redisKey, StringCodec.INSTANCE);
            String json = bucket.get();

            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(JsonUtils.getJsonCodec().fromJson(json, type));
        } catch (Exception e) {
            throw new RuntimeException("Failed to get state: " + key, e);
        }
    }

    @Override
    public <T extends State> List<T> getList(
            String userId, String sessionId, String key, Class<T> itemType) {
        String slotId = slotId(userId, sessionId);
        String redisKey = getListKey(slotId, key);

        try {
            RList<String> rList = redissonClient.getList(redisKey, StringCodec.INSTANCE);

            if (rList.isEmpty()) {
                return List.of();
            }

            List<T> result = new ArrayList<>();
            for (String json : rList) {
                T item = JsonUtils.getJsonCodec().fromJson(json, itemType);
                result.add(item);
            }
            return result;
        } catch (Exception e) {
            throw new RuntimeException("Failed to get list: " + key, e);
        }
    }

    @Override
    public boolean exists(String userId, String sessionId) {
        String slotId = slotId(userId, sessionId);
        String keysKey = getKeysKey(slotId);

        try {
            RSet<String> keysSet = redissonClient.getSet(keysKey, StringCodec.INSTANCE);
            return keysSet.isExists() && keysSet.size() > 0;
        } catch (Exception e) {
            throw new RuntimeException("Failed to check session existence: " + slotId, e);
        }
    }

    @Override
    public void delete(String userId, String sessionId) {
        String slotId = slotId(userId, sessionId);
        String keysKey = getKeysKey(slotId);

        try {
            RSet<String> keysSet = redissonClient.getSet(keysKey, StringCodec.INSTANCE);
            Set<String> trackedKeys = keysSet.readAll();

            if (trackedKeys != null && !trackedKeys.isEmpty()) {
                Set<String> keysToDelete = new HashSet<>();
                keysToDelete.add(keysKey);

                for (String trackedKey : trackedKeys) {
                    if (trackedKey.endsWith(LIST_SUFFIX)) {
                        String baseKey =
                                trackedKey.substring(0, trackedKey.length() - LIST_SUFFIX.length());
                        keysToDelete.add(getListKey(slotId, baseKey));
                    } else {
                        keysToDelete.add(getStateKey(slotId, trackedKey));
                        keysToDelete.add(
                                RedisStateVersionSupport.versionKey(
                                        getStateKey(slotId, trackedKey)));
                    }
                }

                RKeys keys = redissonClient.getKeys();
                keys.delete(keysToDelete.toArray(new String[0]));
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to delete session: " + slotId, e);
        }
    }

    @Override
    public Set<String> listSessionIds(String userId) {
        String userSegment = normalizeUser(userId);
        try {
            RKeys keys = redissonClient.getKeys();
            String pattern = keyPrefix + userSegment + "/*" + KEYS_SUFFIX;
            Iterable<String> keysIterable = keys.getKeysByPattern(pattern);

            Set<String> sessionIds = new HashSet<>();
            String userPrefix = keyPrefix + userSegment + "/";
            for (String keysKey : keysIterable) {
                String withoutPrefix = keysKey.substring(userPrefix.length());
                String sessionId =
                        withoutPrefix.substring(0, withoutPrefix.length() - KEYS_SUFFIX.length());
                sessionIds.add(sessionId);
            }
            return sessionIds;
        } catch (Exception e) {
            throw new RuntimeException("Failed to list sessions", e);
        }
    }

    @Override
    public void close() {
        redissonClient.shutdown();
    }

    private static final String ANON_USER = "__anon__";

    private static String normalizeUser(String userId) {
        return userId == null || userId.isBlank() ? ANON_USER : userId;
    }

    private static String slotId(String userId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        return normalizeUser(userId) + "/" + sessionId;
    }

    /**
     * 清除当前键前缀下的全部会话数据，用于测试或清理。
     * @return 完成时产生已删除匹配键数量的 Mono
     */
    public Mono<Integer> clearAllSessions() {
        return Mono.fromSupplier(
                        () -> {
                            try {
                                RKeys keys = redissonClient.getKeys();
                                Iterable<String> keyIterable =
                                        keys.getKeysByPattern(keyPrefix + "*");

                                List<String> keysToDelete = new ArrayList<>();
                                for (String key : keyIterable) {
                                    keysToDelete.add(key);
                                }

                                if (!keysToDelete.isEmpty()) {
                                    keys.delete(keysToDelete.toArray(new String[0]));
                                }

                                return keysToDelete.size();
                            } catch (Exception e) {
                                throw new RuntimeException("Failed to clear sessions", e);
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 获取单值状态的 Redis 键。
     * @param sessionId 会话 ID
     * @param key 状态键
     * @return 格式为 {prefix}{sessionId}:{key} 的 Redis 键
     */
    private String getStateKey(String sessionId, String key) {
        return keyPrefix + sessionId + ":" + key;
    }

    /**
     * 获取列表状态的 Redis 键。
     * @param sessionId 会话 ID
     * @param key 状态键
     * @return 格式为 {prefix}{sessionId}:{key}:list 的 Redis 键
     */
    private String getListKey(String sessionId, String key) {
        return keyPrefix + sessionId + ":" + key + LIST_SUFFIX;
    }

    /**
     * 获取跟踪会话键的 Redis 键。
     * @param sessionId 会话 ID
     * @return 格式为 {prefix}{sessionId}:_keys 的 Redis 键
     */
    private String getKeysKey(String sessionId) {
        return keyPrefix + sessionId + KEYS_SUFFIX;
    }

    /**
     * {@link RedissonAgentStateStore} 构建器。
     */
    public static class Builder {

        private String keyPrefix = DEFAULT_KEY_PREFIX;
        private RedissonClient redissonClient;

        public Builder keyPrefix(String keyPrefix) {
            this.keyPrefix = keyPrefix;
            return this;
        }

        public Builder redissonClient(RedissonClient redissonClient) {
            this.redissonClient = redissonClient;
            return this;
        }

        public RedissonAgentStateStore build() {
            return new RedissonAgentStateStore(this);
        }
    }
}
