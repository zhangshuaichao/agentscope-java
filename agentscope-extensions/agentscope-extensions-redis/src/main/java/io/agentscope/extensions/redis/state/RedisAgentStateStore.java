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
package io.agentscope.extensions.redis.state;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.ListHashUtil;
import io.agentscope.core.state.State;
import io.agentscope.core.state.VersionedState;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.extensions.redis.state.jedis.JedisClientAdapter;
import io.agentscope.extensions.redis.state.lettuce.LettuceClientAdapter;
import io.agentscope.extensions.redis.state.redisson.RedissonClientAdapter;
import io.lettuce.core.RedisClient;
import io.lettuce.core.cluster.RedisClusterClient;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.redisson.api.RedissonClient;
import redis.clients.jedis.UnifiedJedis;

/**
 * 支持多种 Redis 客户端的会话状态存储实现。
 *
 * <p>统一以下客户端：
 * <ul><li>Jedis：单机、集群、哨兵。</li><li>Lettuce：单机、集群、哨兵。</li>
 * <li>Redisson：单机、集群、哨兵、主从。</li></ul>
 *
 * <p>会话段为 {@code userId/sessionId}，缺省用户时为 {@code __anon__/sessionId}。
 * 默认采用原始 V0 布局；显式选择 V1 后，会话段包裹 Redis 哈希标签以支持集群同槽操作。
 * 共享同一键前缀的所有存储必须配置相同布局。
 *
 * <ul><li>单值状态：{@code {prefix}{sessionSegment}:{stateKey}}，保存 JSON 的 Redis 字符串。</li>
 * <li>版本：{@code {prefix}{sessionSegment}:{stateKey}:ver}，保存乐观版本的字符串。</li>
 * <li>列表状态：{@code {prefix}{sessionSegment}:{stateKey}:list}，保存 JSON 项的列表。</li>
 * <li>列表哈希：{@code {prefix}{sessionSegment}:{stateKey}:list:_hash}，用于变化检测。</li>
 * <li>会话标记：{@code {prefix}{sessionSegment}:_keys}，跟踪全部状态键的集合。</li></ul>
 *
 * <p>所有状态的 {@code {sessionSegment}} 根据布局为 V0 的 {@code userId/sessionId}
 * 或 V1 的 {@code {userId/sessionId}}。
 *
 * <p>【学习提示】单值版本写入经 Lua 原子执行；列表保存则分步读长度、计算哈希、重建或追加、更新索引，
 * 不能将单值 CAS 的原子保证推广到整个列表或整个会话。共享命名空间切换布局不是自动数据迁移。
 *
 * <p><strong>Jedis 示例：</strong>
 * <p>单机 RedisClient：
 * <pre>{@code
 * // 创建 Jedis RedisClient（新 API）
 * RedisClient redisClient = RedisClient.create("redis://localhost:6379");
 *
 * // 构建 RedisAgentStateStore
 * AgentStateStore stateStore = RedisAgentStateStore.builder()
 *     .jedisClient(redisClient)
 *     .build();
 * }</pre>
 * <p>集群 RedisClusterClient：
 * <pre>{@code
 * // 创建 Jedis RedisClusterClient
 * Set<HostAndPort> nodes = new HashSet<>();
 * nodes.add(new HostAndPort("localhost", 7000));
 * nodes.add(new HostAndPort("localhost", 7001));
 * nodes.add(new HostAndPort("localhost", 7002));
 * RedisClusterClient redisClusterClient = RedisClusterClient.create(nodes);
 *
 * // 构建 RedisAgentStateStore
 * AgentStateStore stateStore = RedisAgentStateStore.builder()
 *     .jedisClient(redisClusterClient)
 *     .keyLayoutVersion(RedisAgentStateStore.KeyLayoutVersion.V1)
 *     .build();
 * }</pre>
 * <p>哨兵 RedisSentinelClient：
 * <pre>{@code
 * // 创建 Jedis RedisSentinelClient
 * Set<String> sentinelNodes = new HashSet<>();
 * sentinelNodes.add("localhost:26379");
 * sentinelNodes.add("localhost:26380");
 * RedisSentinelClient redisSentinelClient = RedisSentinelClient.create("mymaster", sentinelNodes);
 *
 * // 构建 RedisAgentStateStore
 * AgentStateStore stateStore = RedisAgentStateStore.builder()
 *     .jedisClient(redisSentinelClient)
 *     .build();
 * }</pre>
 *
 * <p><strong>Lettuce 示例：</strong>
 * <p>单机：
 * <pre>{@code
 * // 创建 Lettuce RedisClient
 * RedisClient redisClient = RedisClient.create("redis://localhost:6379");
 *
 * // 构建 RedisAgentStateStore
 * AgentStateStore stateStore = RedisAgentStateStore.builder()
 *     .lettuceClient(redisClient)
 *     .build();
 * }</pre>
 * <p>集群：
 * <pre>{@code
 * // 创建集群模式的 Lettuce RedisClusterClient
 * RedisClusterClient clusterClient = RedisClusterClient.create(
 *     RedisURI.create("localhost", 7000));
 *
 * // 构建 RedisAgentStateStore
 * AgentStateStore stateStore = RedisAgentStateStore.builder()
 *     .lettuceClusterClient(clusterClient)
 *     .keyLayoutVersion(RedisAgentStateStore.KeyLayoutVersion.V1)
 *     .build();
 * }</pre>
 * <p>哨兵：
 * <pre>{@code
 * // 创建哨兵模式的 Lettuce RedisClient
 * RedisURI sentinelUri = RedisURI.builder()
 *     .withSentinelMasterId("mymaster")
 *     .withSentinel("localhost", 26379)
 *     .withSentinel("localhost", 26380)
 *     .build();
 * RedisClient redisClient = RedisClient.create(sentinelUri);
 *
 * // 构建 RedisAgentStateStore
 * AgentStateStore stateStore = RedisAgentStateStore.builder()
 *     .lettuceClient(redisClient)
 *     .build();
 * }</pre>
 *
 * <p><strong>Redisson 示例：</strong>
 * <pre>{@code
 * // 创建 RedissonClient（按部署模式配置）
 * Config config = new Config();
 * config.useSingleServer().setAddress("redis://localhost:6379");
 * // 或使用集群模式：config.useClusterServers().addNodeAddress("redis://localhost:7000");
 * // 或使用哨兵模式：config.useSentinelServers().setMasterName("mymaster").addSentinelAddress("redis://localhost:26379");
 *
 * RedissonClient redissonClient = Redisson.create(config);
 *
 * // 构建 RedisAgentStateStore
 * AgentStateStore stateStore = RedisAgentStateStore.builder()
 *     .redissonClient(redissonClient)
 *     .build();
 * }</pre>
 *
 * <p><strong>自定义键前缀：</strong>
 * <pre>{@code
 * // 创建 Redis 客户端
 * RedisClient redisClient = RedisClient.create("redis://localhost:6379");
 *
 * // 使用自定义键前缀构建 RedisAgentStateStore
 * AgentStateStore stateStore = RedisAgentStateStore.builder()
 *     .jedisClient(redisClient)
 *     .keyPrefix("myapp:session:")
 *     .build();
 * }</pre>
 */
public class RedisAgentStateStore implements AgentStateStore {

    /**
     * 此存储实例采用的 Redis 键布局。
     */
    public enum KeyLayoutVersion {
        /**
         * 默认的原始键布局，不含 Redis Cluster 哈希标签。
         */
        V0,
        /**
         * 将会话段放入 Redis 哈希标签、支持集群同槽操作的布局。
         */
        V1
    }

    private static final String DEFAULT_KEY_PREFIX = "agentscope:session:";

    private static final String HASH_SUFFIX = ":_hash";

    private final RedisClientAdapter client;

    private final String keyPrefix;

    private final KeyLayoutVersion keyLayoutVersion;

    private RedisAgentStateStore(Builder builder) {
        if (builder.client == null) {
            throw new IllegalArgumentException("Redis client cannot be null");
        }
        if (builder.keyPrefix == null || builder.keyPrefix.trim().isEmpty()) {
            throw new IllegalArgumentException("Key prefix cannot be null or empty");
        }
        if (builder.keyLayoutVersion == null) {
            throw new IllegalArgumentException("Key layout version cannot be null");
        }
        if (builder.keyLayoutVersion == KeyLayoutVersion.V1) {
            RedisAgentStateKeyLayout.validateV1KeyPrefix(builder.keyPrefix);
        }
        this.client = builder.client;
        this.keyPrefix = builder.keyPrefix;
        this.keyLayoutVersion = builder.keyLayoutVersion;
    }

    /**
     * 创建 {@link RedisAgentStateStore} 构建器。
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
        saveVersioned(userId, sessionId, key, value, UNCONDITIONAL_EXPECTED);
    }

    private static final long UNCONDITIONAL_EXPECTED = Long.MIN_VALUE;

    private long saveVersioned(
            String userId, String sessionId, String key, State value, long expectedVersion) {
        RedisAgentStateKeyLayout keyLayout = resolveKeyLayout(userId, sessionId);
        String redisKey = keyLayout.getStateKey(key);
        String versionKey = RedisStateVersionSupport.versionKey(redisKey);
        String keysKey = keyLayout.getKeysKey();
        try {
            String json = JsonUtils.getJsonCodec().toJson(value);
            long result =
                    client.evalScript(
                            RedisStateVersionSupport.SAVE_SCRIPT,
                            RedisStateVersionSupport.saveScriptKeys(redisKey, versionKey, keysKey),
                            expectedVersion == UNCONDITIONAL_EXPECTED
                                    ? RedisStateVersionSupport.unconditionalSaveArgs(json, key)
                                    : RedisStateVersionSupport.saveScriptArgs(
                                            json, expectedVersion, key));
            if (result == -1L) {
                throw new RuntimeException("Version conflict saving state: " + key);
            }
            return result;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to save state: " + key, e);
        }
    }

    @Override
    public <T extends State> VersionedState<T> getVersioned(
            String userId, String sessionId, String key, Class<T> type) {
        RedisAgentStateKeyLayout keyLayout = resolveKeyLayout(userId, sessionId);
        String redisKey = keyLayout.getStateKey(key);
        String versionKey = RedisStateVersionSupport.versionKey(redisKey);
        try {
            String json = client.get(redisKey);
            if (json == null) {
                return new VersionedState<>(null, 0L);
            }
            long version = RedisStateVersionSupport.parseVersion(json, client.get(versionKey));
            return new VersionedState<>(JsonUtils.getJsonCodec().fromJson(json, type), version);
        } catch (Exception e) {
            throw new RuntimeException("Failed to get versioned state: " + key, e);
        }
    }

    @Override
    public long saveIfVersion(
            String userId, String sessionId, String key, State value, long expectedVersion) {
        if (expectedVersion == UNVERSIONED) {
            // saveVersioned 直接返回新版本，无需回读；
            // 也不能用 State.class 回读，因为 State 是标记接口，
            // Jackson 无法直接实例化它。
            return saveVersioned(userId, sessionId, key, value, UNCONDITIONAL_EXPECTED);
        }
        RedisAgentStateKeyLayout keyLayout = resolveKeyLayout(userId, sessionId);
        String redisKey = keyLayout.getStateKey(key);
        String versionKey = RedisStateVersionSupport.versionKey(redisKey);
        String keysKey = keyLayout.getKeysKey();
        try {
            String json = JsonUtils.getJsonCodec().toJson(value);
            long result =
                    client.evalScript(
                            RedisStateVersionSupport.SAVE_SCRIPT,
                            RedisStateVersionSupport.saveScriptKeys(redisKey, versionKey, keysKey),
                            RedisStateVersionSupport.saveScriptArgs(json, expectedVersion, key));
            return result == -1L ? UNVERSIONED : result;
        } catch (Exception e) {
            throw new RuntimeException("Failed to save state if version: " + key, e);
        }
    }

    @Override
    public void save(String userId, String sessionId, String key, List<? extends State> values) {
        RedisAgentStateKeyLayout keyLayout = resolveKeyLayout(userId, sessionId);
        String listKey = keyLayout.getListKey(key);
        String hashKey = listKey + HASH_SUFFIX;
        String keysKey = keyLayout.getKeysKey();
        try {
            // 计算当前哈希。
            String currentHash = ListHashUtil.computeHash(values);
            // 读取已存哈希。
            String storedHash = client.get(hashKey);
            // 读取当前列表长度。
            long existingCount = client.getListLength(listKey);
            // 判断是否需要完整重写。
            boolean needsFullRewrite =
                    ListHashUtil.needsFullRewrite(values, storedHash, (int) existingCount);
            if (needsFullRewrite) {
                // 删除并重建列表。
                client.deleteKeys(listKey);
                for (State item : values) {
                    String json = JsonUtils.getJsonCodec().toJson(item);
                    client.rightPushList(listKey, json);
                }
            } else if (values.size() > existingCount) {
                // 增量追加。
                List<? extends State> newItems = values.subList((int) existingCount, values.size());
                for (State item : newItems) {
                    String json = JsonUtils.getJsonCodec().toJson(item);
                    client.rightPushList(listKey, json);
                }
            }
            // 否则没有变化，跳过。
            // 更新哈希。
            client.set(hashKey, currentHash);
            // 在会话键集合中跟踪此键。
            client.addToSet(keysKey, keyLayout.getListTrackKey(key));
        } catch (Exception e) {
            throw new RuntimeException("Failed to save list: " + key, e);
        }
    }

    @Override
    public <T extends State> Optional<T> get(
            String userId, String sessionId, String key, Class<T> type) {
        RedisAgentStateKeyLayout keyLayout = resolveKeyLayout(userId, sessionId);
        String redisKey = keyLayout.getStateKey(key);
        try {
            String json = client.get(redisKey);
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
        RedisAgentStateKeyLayout keyLayout = resolveKeyLayout(userId, sessionId);
        String redisKey = keyLayout.getListKey(key);
        try {
            List<String> jsonList = client.rangeList(redisKey, 0, -1);
            if (jsonList == null || jsonList.isEmpty()) {
                return List.of();
            }
            List<T> result = new ArrayList<>();
            for (String json : jsonList) {
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
        RedisAgentStateKeyLayout keyLayout = resolveKeyLayout(userId, sessionId);
        try {
            return client.getSetSize(keyLayout.getKeysKey()) > 0;
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to check session existence: "
                            + RedisAgentStateKeyLayout.normalizeUser(userId)
                            + "/"
                            + sessionId,
                    e);
        }
    }

    @Override
    public void delete(String userId, String sessionId) {
        RedisAgentStateKeyLayout keyLayout = resolveKeyLayout(userId, sessionId);
        try {
            String keysKey = keyLayout.getKeysKey();
            Set<String> trackedKeys = client.getSetMembers(keysKey);

            if (trackedKeys != null && !trackedKeys.isEmpty()) {
                Set<String> keysToDelete = new HashSet<>();
                keysToDelete.add(keysKey);

                for (String trackedKey : trackedKeys) {
                    if (RedisAgentStateKeyLayout.isListTrackKey(trackedKey)) {
                        String baseKey =
                                RedisAgentStateKeyLayout.baseKeyFromListTrackKey(trackedKey);
                        keysToDelete.add(keyLayout.getListKey(baseKey));
                        keysToDelete.add(keyLayout.getListKey(baseKey) + HASH_SUFFIX);
                    } else {
                        keysToDelete.add(keyLayout.getStateKey(trackedKey));
                        keysToDelete.add(
                                RedisStateVersionSupport.versionKey(
                                        keyLayout.getStateKey(trackedKey)));
                    }
                }

                // 每条命令只删除一个键，避免跨越未知集群槽。
                for (String keyToDelete : keysToDelete) {
                    client.deleteKeys(keyToDelete);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to delete session: "
                            + RedisAgentStateKeyLayout.normalizeUser(userId)
                            + "/"
                            + sessionId,
                    e);
        }
    }

    @Override
    public Set<String> listSessionIds(String userId) {
        String userSegment = RedisAgentStateKeyLayout.normalizeUser(userId);
        if (keyLayoutVersion == KeyLayoutVersion.V1) {
            RedisAgentStateKeyLayout.validateV1UserId(userSegment);
        }
        try {
            Set<String> sessionIds = new HashSet<>();
            if (keyLayoutVersion == KeyLayoutVersion.V1) {
                String pattern = RedisAgentStateKeyLayout.v1KeysPattern(keyPrefix, userSegment);
                for (String keysKey : client.findKeysByPattern(pattern)) {
                    RedisAgentStateKeyLayout.parseV1SessionIdFromKeysKey(
                                    keysKey, keyPrefix, userSegment)
                            .ifPresent(sessionIds::add);
                }
            } else {
                String pattern = RedisAgentStateKeyLayout.v0KeysPattern(keyPrefix, userSegment);
                for (String keysKey : client.findKeysByPattern(pattern)) {
                    RedisAgentStateKeyLayout.parseV0SessionIdFromKeysKey(
                                    keysKey, keyPrefix, userSegment)
                            .ifPresent(sessionIds::add);
                }
            }
            return sessionIds;
        } catch (Exception e) {
            throw new RuntimeException("Failed to list sessions", e);
        }
    }

    /**
     * 按已配置布局解析键。
     */
    private RedisAgentStateKeyLayout resolveKeyLayout(String userId, String sessionId) {
        return switch (keyLayoutVersion) {
            case V0 -> RedisAgentStateKeyLayout.v0(keyPrefix, userId, sessionId);
            case V1 -> RedisAgentStateKeyLayout.v1(keyPrefix, userId, sessionId);
        };
    }

    @Override
    public void close() {
        client.close();
    }

    /**
     * {@link RedisAgentStateStore} 构建器。
     *
     * <p>支持多种 Redis 客户端，每次仅应配置一种。
     * <p>支持类型：
     * <ul><li>Jedis：{@link #jedisClient(UnifiedJedis)}</li>
     * <li>Lettuce 单机／哨兵：{@link #lettuceClient(RedisClient)}</li>
     * <li>Lettuce 集群：{@link #lettuceClusterClient(RedisClusterClient)}</li>
     * <li>Redisson：{@link #redissonClient(RedissonClient)}</li>
     * <li>自定义：{@link #clientAdapter(RedisClientAdapter)}</li></ul>
     */
    public static class Builder {

        private String keyPrefix = DEFAULT_KEY_PREFIX;

        private RedisClientAdapter client;

        private KeyLayoutVersion keyLayoutVersion = KeyLayoutVersion.V0;

        public Builder keyPrefix(String keyPrefix) {
            this.keyPrefix = keyPrefix;
            return this;
        }

        /**
         * 选择此存储的键布局。
         * <p>为兼容旧数据默认使用 V0；V1 必须显式选择，共享 Redis 命名空间的所有实例须保持一致。
         * @param keyLayoutVersion 键布局版本，不得为 {@code null}
         * @return 当前构建器
         */
        public Builder keyLayoutVersion(KeyLayoutVersion keyLayoutVersion) {
            this.keyLayoutVersion = keyLayoutVersion;
            return this;
        }

        public Builder jedisClient(UnifiedJedis unifiedJedis) {
            this.client = JedisClientAdapter.of(unifiedJedis);
            return this;
        }

        public Builder lettuceClient(RedisClient redisClient) {
            this.client = LettuceClientAdapter.of(redisClient);
            return this;
        }

        public Builder lettuceClusterClient(RedisClusterClient redisClusterClient) {
            this.client = LettuceClientAdapter.of(redisClusterClient);
            return this;
        }

        public Builder redissonClient(RedissonClient redissonClient) {
            this.client = RedissonClientAdapter.of(redissonClient);
            return this;
        }

        public Builder clientAdapter(RedisClientAdapter clientAdapter) {
            this.client = clientAdapter;
            return this;
        }

        public RedisAgentStateStore build() {
            return new RedisAgentStateStore(this);
        }
    }
}
