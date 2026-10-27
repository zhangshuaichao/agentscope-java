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
package io.agentscope.extensions.redis.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import redis.clients.jedis.UnifiedJedis;

/**
 * 基于 Redis 的 {@link BaseStore} 实现。
 *
 * <h2>键布局</h2>
 * <p>命名空间 {@code [a, b, c]} 中键为 {@code k} 的条目使用两个 Redis 键：
 * <ul><li><b>条目哈希</b> {@code <prefix>item:<ns>\0<k>}：字段 {@code value}
 * 保存 JSON 编码的 {@code Map<String,Object>}，字段 {@code version} 保存字符串形式的 long。</li>
 * <li><b>命名空间索引</b> {@code <prefix>idx:<ns>}：所有分数均为 {@code 0} 的有序集合，
 * 保存该命名空间写入的每个 {@code k}，使 {@link #search} 能用 {@code ZRANGEBYLEX}
 * 按字典序查找，无需扫描整个键空间。</li></ul>
 * <p>{@code <ns>} 为用 {@code "\0"} 连接的命名空间段。
 *
 * <h2>并发</h2>
 * <p>{@link #put} 与 {@link #putIfVersion} 各自执行单次 Lua {@code EVAL}，
 * 将版本读取、哈希写入和索引更新合并为一个原子 Redis 操作。
 * {@link #putIfVersion} 因此可作为共享同一 Redis 实例的跨进程 CAS。
 * <p>{@link #search} 不与 {@link #put} 处于同一事务：读完索引后条目可能被并发删除，
 * 实现会跳过缺失条目。单次 Lua 内部 {@code ZADD} 与 {@code HSET} 的中间状态不会被外部命令观察到。
 *
 * <p>【学习提示】CAS 保护单次条目更新，不把多次 search/get/put 自动组成事务。
 * 键布局没有自动插入集群哈希标签，不能仅凭 Lua 原子性就断言任意 Redis Cluster 部署可跨槽执行。
 */
public class RedisStore implements BaseStore {

    /**
     * 默认 Redis 键前缀；应选择足够独特的值，避免与其他应用冲突。
     */
    public static final String DEFAULT_KEY_PREFIX = "agentscope:store:";

    private static final String NS_SEPARATOR = "\0";

    /**
     * 原子写入：无条件递增版本，写入值、版本和索引成员。返回字符串形式的新版本。
     */
    private static final String PUT_SCRIPT =
            "local v = tonumber(redis.call('HGET', KEYS[1], 'version') or '0') + 1 "
                    + "redis.call('HSET', KEYS[1], 'value', ARGV[1], 'version', tostring(v)) "
                    + "redis.call('ZADD', KEYS[2], 0, ARGV[2]) "
                    + "return tostring(v)";

    /**
     * 原子 CAS 写入：仅当存储版本等于 ARGV[3] 时写入。
     * 成功返回新版本，版本不匹配返回字面值 {@code "0"}；预期版本 {@code 0} 表示仅在不存在时创建。
     */
    private static final String PUT_IF_VERSION_SCRIPT =
            "local cur = tonumber(redis.call('HGET', KEYS[1], 'version') or '0') "
                    + "if cur ~= tonumber(ARGV[3]) then return '0' end "
                    + "local v = cur + 1 "
                    + "redis.call('HSET', KEYS[1], 'value', ARGV[1], 'version', tostring(v)) "
                    + "redis.call('ZADD', KEYS[2], 0, ARGV[2]) "
                    + "return tostring(v)";

    /**
     * 原子删除条目哈希及其索引成员。
     */
    private static final String DELETE_SCRIPT =
            "redis.call('DEL', KEYS[1]) " + "redis.call('ZREM', KEYS[2], ARGV[1]) " + "return 1";

    private final UnifiedJedis jedis;
    private final String keyPrefix;
    private final ObjectMapper objectMapper;

    /**
     * 使用 默认键前缀（{@linkplain #DEFAULT_KEY_PREFIX default key prefix}） 创建 Redis 存储。
     * @param jedis 已初始化客户端，不得为 {@code null}
     */
    public RedisStore(UnifiedJedis jedis) {
        this(jedis, DEFAULT_KEY_PREFIX, new ObjectMapper());
    }

    /**
     * 创建 Redis 存储。
     * @param jedis 已初始化客户端，不得为 {@code null}
     * @param keyPrefix Redis 键前缀，缺少尾部 {@code ":"} 时自动添加；空白时使用 默认值（{@linkplain #DEFAULT_KEY_PREFIX default}）
     */
    public RedisStore(UnifiedJedis jedis, String keyPrefix) {
        this(jedis, keyPrefix, new ObjectMapper());
    }

    /**
     * 使用自定义 JSON 映射器创建 Redis 存储。
     * @param jedis 已初始化客户端，不得为 {@code null}
     * @param keyPrefix Redis 键前缀，缺少尾部 {@code ":"} 时自动添加
     * @param objectMapper 用于条目值序列化／反序列化的 Jackson 映射器
     */
    public RedisStore(UnifiedJedis jedis, String keyPrefix, ObjectMapper objectMapper) {
        this.jedis = Objects.requireNonNull(jedis, "jedis must not be null");
        this.keyPrefix = normalizePrefix(keyPrefix);
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    public StoreItem get(List<String> namespace, String key) {
        validateKey(key);
        String itemKey = itemKey(namespace, key);
        Map<String, String> hash = jedis.hgetAll(itemKey);
        if (hash == null || hash.isEmpty()) {
            return null;
        }
        return toItem(key, hash);
    }

    @Override
    public void put(List<String> namespace, String key, Map<String, Object> value) {
        validateKey(key);
        String itemKey = itemKey(namespace, key);
        String idxKey = indexKey(namespace);
        String json = serialize(value);
        jedis.eval(PUT_SCRIPT, List.of(itemKey, idxKey), List.of(json, key));
    }

    @Override
    public boolean supportsAtomicSessionStorage() {
        return true;
    }

    @Override
    public boolean putIfVersion(
            List<String> namespace, String key, Map<String, Object> value, long expectedVersion) {
        validateKey(key);
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must be non-negative");
        }
        String itemKey = itemKey(namespace, key);
        String idxKey = indexKey(namespace);
        String json = serialize(value);
        Object result =
                jedis.eval(
                        PUT_IF_VERSION_SCRIPT,
                        List.of(itemKey, idxKey),
                        List.of(json, key, Long.toString(expectedVersion)));
        return !"0".equals(asString(result));
    }

    @Override
    public List<StoreItem> search(List<String> namespace, int limit, int offset) {
        if (limit <= 0) {
            return List.of();
        }
        int safeOffset = Math.max(offset, 0);
        String idxKey = indexKey(namespace);
        List<String> keys = jedis.zrangeByLex(idxKey, "-", "+", safeOffset, limit);
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        List<StoreItem> items = new ArrayList<>(keys.size());
        for (String k : keys) {
            Map<String, String> hash = jedis.hgetAll(itemKey(namespace, k));
            if (hash == null || hash.isEmpty()) {
                continue; // 容忍已失效的索引成员。
            }
            items.add(toItem(k, hash));
        }
        return items;
    }

    @Override
    public void delete(List<String> namespace, String key) {
        validateKey(key);
        String itemKey = itemKey(namespace, key);
        String idxKey = indexKey(namespace);
        jedis.eval(DELETE_SCRIPT, List.of(itemKey, idxKey), List.of(key));
    }

    // -------------------------------------------------------------------------
    // 辅助方法
    // -------------------------------------------------------------------------

    private StoreItem toItem(String key, Map<String, String> hash) {
        String json = hash.get("value");
        String versionStr = hash.get("version");
        long version = 0L;
        if (versionStr != null) {
            try {
                version = Long.parseLong(versionStr);
            } catch (NumberFormatException ignored) {
                // 保持版本为 0，调用方将其视为未知版本。
            }
        }
        Map<String, Object> value = deserialize(json);
        return new StoreItem(key, value, version);
    }

    private String serialize(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to JSON-encode store value", e);
        }
    }

    private Map<String, Object> deserialize(String json) {
        if (json == null || json.isEmpty()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Failed to JSON-decode store value", e);
        }
    }

    private String itemKey(List<String> namespace, String key) {
        return keyPrefix + "item:" + namespacePath(namespace) + NS_SEPARATOR + key;
    }

    private String indexKey(List<String> namespace) {
        return keyPrefix + "idx:" + namespacePath(namespace);
    }

    private static String namespacePath(List<String> namespace) {
        Objects.requireNonNull(namespace, "namespace must not be null");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < namespace.size(); i++) {
            String segment = namespace.get(i);
            if (segment == null) {
                throw new IllegalArgumentException("namespace segment must not be null");
            }
            if (i > 0) {
                sb.append(NS_SEPARATOR);
            }
            sb.append(segment);
        }
        return sb.toString();
    }

    private static void validateKey(String key) {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("key must not be null or empty");
        }
        if (key.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("key must not contain the NUL character");
        }
    }

    private static String asString(Object eval) {
        if (eval == null) {
            return null;
        }
        if (eval instanceof byte[] bytes) {
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }
        return eval.toString();
    }

    private static String normalizePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return DEFAULT_KEY_PREFIX;
        }
        return prefix.endsWith(":") ? prefix : prefix + ":";
    }
}
