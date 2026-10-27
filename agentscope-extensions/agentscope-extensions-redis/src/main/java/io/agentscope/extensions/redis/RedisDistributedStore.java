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
package io.agentscope.extensions.redis;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.extensions.redis.sandbox.RedisSandboxExecutionGuard;
import io.agentscope.extensions.redis.snapshot.RedisSnapshotSpec;
import io.agentscope.extensions.redis.state.RedisAgentStateStore;
import io.agentscope.extensions.redis.state.RedisAgentStateStore.KeyLayoutVersion;
import io.agentscope.extensions.redis.store.RedisStore;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import java.util.Objects;
import redis.clients.jedis.UnifiedJedis;

/**
 * 基于 Redis 的 {@link DistributedStore}，通过一个 Jedis 连接配置各个分布式存储组件。
 *
 * <p>用法：
 * <pre>{@code
 * JedisPooled jedis = new JedisPooled("redis://localhost:6379");
 * RedisDistributedStore store = RedisDistributedStore.fromJedis(jedis);
 *
 * HarnessAgent agent = HarnessAgent.builder()
 *     .name("my-agent")
 *     .model("dashscope:qwen-plus")
 *     .distributedStore(store)
 *     .filesystem(new RemoteFilesystemSpec(store.baseStore())
 *             .isolationScope(IsolationScope.USER))
 *     .build();
 * }</pre>
 *
 * <p>提供以下组件：
 * <ul><li>{@link RedisAgentStateStore}：智能体会话状态，由 distributedStore 自动装配。</li>
 * <li>{@link RedisStore}：工作区文件系统 KV 存储，通过 {@link #baseStore()} 获取。</li>
 * <li>{@link RedisSnapshotSpec}：沙箱快照存储，在沙箱模式自动装配。</li>
 * <li>{@link RedisSandboxExecutionGuard}：沙箱并发锁，在沙箱模式自动装配。</li></ul>
 */
public class RedisDistributedStore implements DistributedStore {

    private final UnifiedJedis jedis;
    private final String keyPrefix;
    private final KeyLayoutVersion keyLayoutVersion;

    private RedisDistributedStore(
            UnifiedJedis jedis, String keyPrefix, KeyLayoutVersion keyLayoutVersion) {
        this.jedis = Objects.requireNonNull(jedis, "jedis");
        this.keyPrefix = keyPrefix != null ? keyPrefix : "agentscope:";
        this.keyLayoutVersion = Objects.requireNonNull(keyLayoutVersion, "keyLayoutVersion");
    }

    /**
     * 用 Jedis 客户端与默认键前缀创建 Redis 分布式存储。
     * @param jedis 已初始化的 Jedis 客户端，例如 {@code new JedisPooled("redis://localhost:6379")}
     * @return 新建的 Redis 分布式存储
     */
    public static RedisDistributedStore fromJedis(UnifiedJedis jedis) {
        return new RedisDistributedStore(jedis, null, KeyLayoutVersion.V0);
    }

    /**
     * 用 Jedis 客户端与自定义键前缀创建 Redis 分布式存储。
     * @param jedis 已初始化的 Jedis 客户端
     * @param keyPrefix 所有 Redis 键的前缀，例如 {@code "myapp:"}
     * @return 新建的 Redis 分布式存储
     */
    public static RedisDistributedStore fromJedis(UnifiedJedis jedis, String keyPrefix) {
        return new RedisDistributedStore(jedis, keyPrefix, KeyLayoutVersion.V0);
    }

    /**
     * 使用自定义键前缀与智能体状态键布局创建 Redis 分布式存储。
     * @param jedis 已初始化的 Jedis 客户端
     * @param keyPrefix 所有 Redis 键的前缀，例如 {@code "myapp:"}
     * @param keyLayoutVersion 智能体状态存储采用的键布局
     * @return 新建的 Redis 分布式存储
     */
    public static RedisDistributedStore fromJedis(
            UnifiedJedis jedis, String keyPrefix, KeyLayoutVersion keyLayoutVersion) {
        return new RedisDistributedStore(jedis, keyPrefix, keyLayoutVersion);
    }

    @Override
    public AgentStateStore agentStateStore() {
        return RedisAgentStateStore.builder()
                .jedisClient(jedis)
                .keyPrefix(keyPrefix + "session:")
                .keyLayoutVersion(keyLayoutVersion)
                .build();
    }

    @Override
    public BaseStore baseStore() {
        return new RedisStore(jedis, keyPrefix + "store:");
    }

    @Override
    public SandboxSnapshotSpec sandboxSnapshotSpec() {
        return new RedisSnapshotSpec(jedis, keyPrefix + "snapshot:", null);
    }

    @Override
    public SandboxExecutionGuard sandboxExecutionGuard() {
        return RedisSandboxExecutionGuard.builder(jedis).keyPrefix(keyPrefix + "guard:").build();
    }
}
