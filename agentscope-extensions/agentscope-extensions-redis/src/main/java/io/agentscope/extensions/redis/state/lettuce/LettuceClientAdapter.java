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
package io.agentscope.extensions.redis.state.lettuce;

import io.agentscope.extensions.redis.state.RedisClientAdapter;
import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.cluster.api.sync.RedisAdvancedClusterCommands;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lettuce Redis 客户端适配器。
 *
 * <p>通过不同客户端类型支持多种部署：
 * <ul><li>{@link RedisClient}：单机与哨兵。</li>
 * <li>{@link RedisClusterClient}：集群。</li></ul>
 * <p>内部共享连接与命令实例，提高连接利用率。
 * 实现用条件分支选择单机／哨兵和集群的命令 API，适配当前所用 API 的模式差异。
 *
 * <p>单机示例：
 * <pre>{@code
 * // Create standalone RedisClient
 * RedisClient redisClient = RedisClient.create("redis://localhost:6379");
 *
 * // Use with RedisAgentStateStore
 * AgentStateStore stateStore = RedisAgentStateStore.builder()
 *     .lettuceClient(redisClient)
 *     .build();
 * }</pre>
 * <p>集群示例：
 * <pre>{@code
 * // Create cluster client
 * RedisClusterClient clusterClient = RedisClusterClient.create("redis://localhost:7000");
 *
 * // Use with RedisAgentStateStore
 * AgentStateStore stateStore = RedisAgentStateStore.builder()
 *     .lettuceClusterClient(clusterClient)
 *     .keyLayoutVersion(RedisAgentStateStore.KeyLayoutVersion.V1)
 *     .build();
 * }</pre>
 * <p>哨兵示例：
 * <pre>{@code
 * // Create Lettuce RedisClient for sentinel
 * RedisURI sentinelUri = RedisURI.builder()
 *     .withSentinelMasterId("mymaster")
 *     .withSentinel("localhost", 26379)
 *     .withSentinel("localhost", 26380)
 *     .withSentinel("localhost", 26381)
 *     .withDatabase(0)
 *     .build();
 * RedisClient redisClient = RedisClient.create(sentinelUri);
 *
 * // Use with RedisAgentStateStore
 * AgentStateStore stateStore = RedisAgentStateStore.builder()
 *     .lettuceClient(redisClient)
 *     .build();
 * }</pre>
 */
public class LettuceClientAdapter implements RedisClientAdapter {

    private static final Logger log = LoggerFactory.getLogger(LettuceClientAdapter.class);

    /**
     * 单机与哨兵模式使用的 Redis 命令对象，集群模式下为 null。
     */
    private final RedisCommands<String, String> commands;

    /**
     * 集群模式使用的 Redis 命令对象，单机与哨兵模式下为 null。
     */
    private final RedisAdvancedClusterCommands<String, String> clusterCommands;

    /**
     * 可关闭资源句柄，负责连接与客户端清理。
     * 通过策略模式封装单机／哨兵与集群的不同清理逻辑。
     */
    private final AutoCloseable closeable;

    private LettuceClientAdapter(
            RedisCommands<String, String> commands,
            RedisAdvancedClusterCommands<String, String> clusterCommands,
            AutoCloseable closeable) {
        this.commands = commands;
        this.clusterCommands = clusterCommands;
        this.closeable = closeable;
    }

    /**
     * 从 RedisClient 创建单机／哨兵适配器。
     * @param redisClient 单机或哨兵模式的 RedisClient
     * @return 新建的 LettuceClientAdapter
     * @throws NullPointerException redisClient 为 null 时抛出
     */
    public static LettuceClientAdapter of(RedisClient redisClient) {
        if (redisClient == null) {
            throw new NullPointerException("redisClient must not be null");
        }
        StatefulRedisConnection<String, String> connection = redisClient.connect();
        return new LettuceClientAdapter(
                connection.sync(), null, new StandaloneCloser(connection, redisClient));
    }

    /**
     * 从 RedisClusterClient 创建集群适配器。
     * @param redisClusterClient 集群模式的 RedisClusterClient
     * @return 新建的 LettuceClientAdapter
     * @throws NullPointerException redisClusterClient 为 null 时抛出
     */
    public static LettuceClientAdapter of(RedisClusterClient redisClusterClient) {
        if (redisClusterClient == null) {
            throw new NullPointerException("redisClusterClient must not be null");
        }
        StatefulRedisClusterConnection<String, String> connection = redisClusterClient.connect();
        return new LettuceClientAdapter(
                null, connection.sync(), new ClusterCloser(connection, redisClusterClient));
    }

    @Override
    public void set(String key, String value) {
        if (commands != null) {
            commands.set(key, value);
        } else {
            clusterCommands.set(key, value);
        }
    }

    @Override
    public String get(String key) {
        if (commands != null) {
            return commands.get(key);
        } else {
            return clusterCommands.get(key);
        }
    }

    @Override
    public void rightPushList(String key, String value) {
        if (commands != null) {
            commands.rpush(key, value);
        } else {
            clusterCommands.rpush(key, value);
        }
    }

    @Override
    public List<String> rangeList(String key, long start, long end) {
        if (commands != null) {
            return commands.lrange(key, start, end);
        } else {
            return clusterCommands.lrange(key, start, end);
        }
    }

    @Override
    public long getListLength(String key) {
        if (commands != null) {
            return commands.llen(key);
        } else {
            return clusterCommands.llen(key);
        }
    }

    @Override
    public void deleteKeys(String... keys) {
        if (commands != null) {
            commands.del(keys);
        } else {
            clusterCommands.del(keys);
        }
    }

    @Override
    public void addToSet(String key, String member) {
        if (commands != null) {
            commands.sadd(key, member);
        } else {
            clusterCommands.sadd(key, member);
        }
    }

    @Override
    public Set<String> getSetMembers(String key) {
        if (commands != null) {
            return new HashSet<>(commands.smembers(key));
        } else {
            return new HashSet<>(clusterCommands.smembers(key));
        }
    }

    @Override
    public long getSetSize(String key) {
        if (commands != null) {
            return commands.scard(key);
        } else {
            return clusterCommands.scard(key);
        }
    }

    @Override
    public boolean keyExists(String key) {
        if (commands != null) {
            return commands.exists(key) > 0;
        } else {
            return clusterCommands.exists(key) > 0;
        }
    }

    @Override
    public Set<String> findKeysByPattern(String pattern) {
        if (commands != null) {
            return scanKeys(pattern, commands::scan);
        } else {
            return scanKeys(pattern, clusterCommands::scan);
        }
    }

    /**
     * 使用给定扫描函数查找键的辅助方法。
     * @param pattern 待匹配键模式
     * @param scanFunction 执行扫描的函数
     * @return 匹配键集合
     */
    private static Set<String> scanKeys(String pattern, ScanFunction scanFunction) {
        Set<String> keys = new HashSet<>();
        ScanCursor cursor = ScanCursor.INITIAL;
        ScanArgs scanArgs = ScanArgs.Builder.matches(pattern);
        while (!cursor.isFinished()) {
            KeyScanCursor<String> scanResult = scanFunction.scan(cursor, scanArgs);
            keys.addAll(scanResult.getKeys());
            cursor = scanResult;
        }
        return keys;
    }

    /**
     * 扫描操作的函数式接口。
     */
    @FunctionalInterface
    private interface ScanFunction {
        KeyScanCursor<String> scan(ScanCursor cursor, ScanArgs scanArgs);
    }

    @Override
    public long evalScript(String script, List<String> keys, List<String> args) {
        Object result;
        String[] keyArray = keys.toArray(new String[0]);
        String[] argArray = args.toArray(new String[0]);
        if (commands != null) {
            result = commands.eval(script, ScriptOutputType.INTEGER, keyArray, argArray);
        } else {
            result = clusterCommands.eval(script, ScriptOutputType.INTEGER, keyArray, argArray);
        }
        if (result instanceof Number number) {
            return number.longValue();
        }
        throw new IllegalStateException("Unexpected Lua script result: " + result);
    }

    @Override
    public void close() {
        try {
            closeable.close();
        } catch (Exception e) {
            log.warn("Exception while closing Lettuce adapter: {}", e.getMessage());
            if (log.isDebugEnabled()) {
                log.debug("Full stack trace:", e);
            }
        }
    }

    private static class StandaloneCloser implements AutoCloseable {
        private final StatefulRedisConnection<String, String> connection;
        private final RedisClient client;

        StandaloneCloser(StatefulRedisConnection<String, String> connection, RedisClient client) {
            this.connection = connection;
            this.client = client;
        }

        @Override
        public void close() {
            try {
                connection.close();
                client.shutdown();
            } catch (Exception e) {
                log.debug("Exception closing Lettuce standalone: {}", e.getMessage());
            }
        }
    }

    private static class ClusterCloser implements AutoCloseable {
        private final StatefulRedisClusterConnection<String, String> connection;
        private final RedisClusterClient client;

        ClusterCloser(
                StatefulRedisClusterConnection<String, String> connection,
                RedisClusterClient client) {
            this.connection = connection;
            this.client = client;
        }

        @Override
        public void close() {
            try {
                connection.close();
                client.shutdown();
            } catch (Exception e) {
                log.debug("Exception closing Lettuce cluster: {}", e.getMessage());
            }
        }
    }
}
