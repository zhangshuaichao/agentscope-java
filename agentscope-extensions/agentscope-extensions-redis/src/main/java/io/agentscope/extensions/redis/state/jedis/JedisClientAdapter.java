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
package io.agentscope.extensions.redis.state.jedis;

import io.agentscope.extensions.redis.state.RedisClientAdapter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import redis.clients.jedis.RedisClient;
import redis.clients.jedis.RedisClusterClient;
import redis.clients.jedis.RedisSentinelClient;
import redis.clients.jedis.UnifiedJedis;

/**
 * Jedis Redis 客户端适配器。
 *
 * <p>支持以下类型：
 * <ul><li>{@link RedisClient}：单机。</li><li>{@link RedisClusterClient}：集群。</li>
 * <li>{@link RedisSentinelClient}：哨兵。</li><li>{@link UnifiedJedis}：统一基础接口。</li></ul>
 * <p>这些新客户端类型继承 {@link UnifiedJedis}，以统一接口处理不同部署模式。
 *
 * <p>单机 RedisClient 示例：
 * <pre>{@code
 * // 创建单机客户端
 * RedisClient redisClient = RedisClient.create("redis://localhost:6379");
 *
 * // 创建适配器
 * JedisClientAdapter adapter = JedisClientAdapter.of(redisClient);
 * }</pre>
 * <p>集群 RedisClusterClient 示例：
 * <pre>{@code
 * // 创建集群客户端
 * Set<HostAndPort> nodes = new HashSet<>();
 * nodes.add(new HostAndPort("localhost", 7000));
 * nodes.add(new HostAndPort("localhost", 7001));
 * RedisClusterClient clusterClient = RedisClusterClient.create(nodes);
 *
 * // 创建适配器
 * JedisClientAdapter adapter = JedisClientAdapter.of(clusterClient);
 * }</pre>
 * <p>哨兵 RedisSentinelClient 示例：
 * <pre>{@code
 * // 创建哨兵客户端
 * Set<String> sentinels = new HashSet<>();
 * sentinels.add("localhost:26379");
 * RedisSentinelClient sentinelClient = RedisSentinelClient.create("mymaster", sentinels);
 *
 * // 创建适配器
 * JedisClientAdapter adapter = JedisClientAdapter.of(sentinelClient);
 * }</pre>
 * <p>直接使用 UnifiedJedis：
 * <pre>{@code
 * // 创建任意继承 UnifiedJedis 的客户端类型
 * UnifiedJedis unifiedJedis = new RedisClient("redis://localhost:6379");
 *
 * // 创建适配器
 * JedisClientAdapter adapter = JedisClientAdapter.of(unifiedJedis);
 * }</pre>
 */
public class JedisClientAdapter implements RedisClientAdapter {

    private final UnifiedJedis unifiedJedis;

    private JedisClientAdapter(UnifiedJedis unifiedJedis) {
        this.unifiedJedis = unifiedJedis;
    }

    /**
     * 从 UnifiedJedis 创建适配器。
     * @param unifiedJedis UnifiedJedis 实例，可为任意子类
     * @return 新建的 JedisClientAdapter
     */
    public static JedisClientAdapter of(UnifiedJedis unifiedJedis) {
        return new JedisClientAdapter(unifiedJedis);
    }

    @Override
    public void set(String key, String value) {
        unifiedJedis.set(key, value);
    }

    @Override
    public String get(String key) {
        return unifiedJedis.get(key);
    }

    @Override
    public void rightPushList(String key, String value) {
        unifiedJedis.rpush(key, value);
    }

    @Override
    public List<String> rangeList(String key, long start, long end) {
        return unifiedJedis.lrange(key, start, end);
    }

    @Override
    public long getListLength(String key) {
        return unifiedJedis.llen(key);
    }

    @Override
    public void deleteKeys(String... keys) {
        unifiedJedis.del(keys);
    }

    @Override
    public void addToSet(String key, String member) {
        unifiedJedis.sadd(key, member);
    }

    @Override
    public Set<String> getSetMembers(String key) {
        return unifiedJedis.smembers(key);
    }

    @Override
    public long getSetSize(String key) {
        return unifiedJedis.scard(key);
    }

    @Override
    public boolean keyExists(String key) {
        return unifiedJedis.exists(key);
    }

    @Override
    public Set<String> findKeysByPattern(String pattern) {
        Set<String> matchingKeys = new HashSet<>();
        // 与单节点 SCAN 不同，Jedis 的迭代在集群模式遍历每个主节点。
        unifiedJedis.scanIteration(1000, pattern).collect(matchingKeys);
        return matchingKeys;
    }

    @Override
    public long evalScript(String script, List<String> keys, List<String> args) {
        Object result = unifiedJedis.eval(script, keys, args);
        if (result instanceof Number number) {
            return number.longValue();
        }
        throw new IllegalStateException("Unexpected Lua script result: " + result);
    }

    @Override
    public void close() {
        unifiedJedis.close();
    }
}
