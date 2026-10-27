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
package io.agentscope.extensions.redis.snapshot;

import io.agentscope.harness.agent.sandbox.snapshot.RemoteSnapshotSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import redis.clients.jedis.UnifiedJedis;

/**
 * Redis 快照存储的便捷 {@link SandboxSnapshotSpec}。
 */
public class RedisSnapshotSpec extends RemoteSnapshotSpec {

    /**
     * 创建 Redis 快照配置。
     * @param jedis 已初始化的 Jedis 客户端
     * @param keyPrefix 可选 Redis 键前缀
     * @param ttlSeconds 可选 TTL 秒数，null 或非正数表示不设 TTL
     */
    public RedisSnapshotSpec(UnifiedJedis jedis, String keyPrefix, Integer ttlSeconds) {
        super(new RedisRemoteSnapshotClient(jedis, keyPrefix, ttlSeconds));
    }
}
