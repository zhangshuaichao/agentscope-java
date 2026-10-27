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
package io.agentscope.extensions.redis.sandbox;

import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;
import io.agentscope.harness.agent.sandbox.SandboxIsolationKey;
import io.agentscope.harness.agent.sandbox.SandboxLease;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.params.SetParams;

/**
 * 基于 Redis 的 {@link SandboxExecutionGuard}，通过 {@code SET NX PX} 租约串行化沙箱隔离槽的并发访问。
 *
 * <h2>机制</h2>
 * <ol><li>{@link #tryEnter} 使用 {@code SET NX PX <ttlMs>} 写入唯一令牌。
 * 键已被占用时，每隔 {@code retryInterval} 重试，直到槽空闲或线程中断。</li>
 * <li>返回的 {@link SandboxLease#close()} 执行 Lua CAS，仅当键值等于自己的令牌时删除，
 * 防止误释放其他持有者的锁。</li></ol>
 *
 * <h2>键格式</h2>
 * <pre>{@code <keyPrefix><scope_lower>:<value>}</pre>
 * <p>默认前缀为 {@code agentscope:sandbox:lock:}，示例：
 * <ul><li>{@code agentscope:sandbox:lock:agent:code-agent}</li>
 * <li>{@code agentscope:sandbox:lock:global:__global__}</li></ul>
 *
 * <h2>用法</h2>
 * <pre>{@code
 * UnifiedJedis jedis = new JedisPooled("localhost", 6379);
 *
 * SandboxExecutionGuard guard = RedisSandboxExecutionGuard.builder(jedis)
 *     .leaseTtl(Duration.ofMinutes(30))   // must exceed worst-case call duration
 *     .retryInterval(Duration.ofMillis(500))
 *     .build();
 *
 * HarnessAgent.builder()
 *     .filesystem(new DockerFilesystemSpec()
 *         .isolationScope(IsolationScope.AGENT)
 *         .executionGuard(guard))
 *     ...
 *     .build();
 * }</pre>
 *
 * <h2>TTL 选择</h2>
 * <p>{@code leaseTtl} 应明显长于最坏智能体调用时长，包括重试与模型延迟。
 * 调用超出 TTL 后键过期，其他调用者可以进入；这是防止永久死锁的兜底，
 * 不是两个调用者可安全共享状态的保证。应监测调用耗时，据此配置 TTL。
 *
 * <p>【学习提示】此处没有自动续租或 fencing token 校验。令牌比较保护“释放谁的锁”，
 * 不能阻止已经过期的旧执行者继续访问沙箱，因此租约超时不等于旧任务已停止。
 *
 * <h2>扩展实现</h2>
 * <p>本类作为 {@link SandboxExecutionGuard} 的参考实现，ZooKeeper、etcd、数据库咨询锁等后端应遵循相同契约：
 * <ul><li>{@link #tryEnter} 阻塞到获得槽或线程中断。</li>
 * <li>返回的 {@link SandboxLease} 在 {@link SandboxLease#close()} 时幂等释放槽。</li>
 * <li>释放失败须记录并吞掉异常，不向外抛出，保证 Harness 调用后处理路径能完成。</li></ul>
 */
public final class RedisSandboxExecutionGuard implements SandboxExecutionGuard {

    private static final Logger log = LoggerFactory.getLogger(RedisSandboxExecutionGuard.class);

    /**
     * Lua 脚本：仅当 {@code KEYS[1]} 的值等于 {@code ARGV[1]} 时原子删除。
     * 成功返回 1；键已不存在或属于其他令牌时返回 0。
     */
    private static final String RELEASE_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "    return redis.call('del', KEYS[1]) "
                    + "else "
                    + "    return 0 "
                    + "end";

    private final UnifiedJedis jedis;
    private final String keyPrefix;
    private final long leaseTtlMs;
    private final long retryIntervalMs;

    private RedisSandboxExecutionGuard(Builder builder) {
        this.jedis = builder.jedis;
        this.keyPrefix = builder.keyPrefix;
        this.leaseTtlMs = builder.leaseTtlMs;
        this.retryIntervalMs = builder.retryIntervalMs;
    }

    /**
     * 创建此守卫的构建器。
     * @param jedis 已初始化客户端，可与 {@link io.agentscope.extensions.redis.snapshot.RedisSnapshotSpec} 使用同一实例
     */
    public static Builder builder(UnifiedJedis jedis) {
        return new Builder(jedis);
    }

    /**
     * 为 {@code key} 获取 Redis 租约，每隔 {@code retryInterval} 重试直到槽空闲。
     * @throws InterruptedException 等待时调用线程被中断则抛出
     */
    @Override
    public SandboxLease tryEnter(SandboxIsolationKey key) throws InterruptedException {
        String redisKey = composeKey(key);
        String token = UUID.randomUUID().toString();
        SetParams params = SetParams.setParams().nx().px(leaseTtlMs);

        log.debug(
                "[sandbox-guard] Acquiring Redis lease for key={} ttlMs={}", redisKey, leaseTtlMs);

        while (true) {
            String result = jedis.set(redisKey, token, params);
            if ("OK".equals(result)) {
                log.debug("[sandbox-guard] Acquired Redis lease for key={}", redisKey);
                return () -> releaseLock(redisKey, token);
            }
            if (Thread.interrupted()) {
                throw new InterruptedException(
                        "Interrupted while waiting for sandbox execution guard on " + redisKey);
            }
            Thread.sleep(retryIntervalMs);
        }
    }

    private void releaseLock(String redisKey, String token) {
        try {
            Object result = jedis.eval(RELEASE_SCRIPT, List.of(redisKey), List.of(token));
            if (Long.valueOf(0L).equals(result)) {
                log.warn(
                        "[sandbox-guard] Redis lease for key={} was already gone or held by a"
                                + " different token — possible TTL expiry during a long call",
                        redisKey);
            } else {
                log.debug("[sandbox-guard] Released Redis lease for key={}", redisKey);
            }
        } catch (Exception e) {
            log.warn(
                    "[sandbox-guard] Failed to release Redis lease for key={}: {}",
                    redisKey,
                    e.getMessage(),
                    e);
        }
    }

    private String composeKey(SandboxIsolationKey key) {
        return keyPrefix + key.getScope().name().toLowerCase() + ":" + key.getValue();
    }

    // -------------------------------------------------------------------------
    // 构建器
    // -------------------------------------------------------------------------

    /**
     * {@link RedisSandboxExecutionGuard} 构建器。
     */
    public static final class Builder {

        private static final Duration DEFAULT_LEASE_TTL = Duration.ofMinutes(30);
        private static final Duration DEFAULT_RETRY_INTERVAL = Duration.ofMillis(500);
        private static final String DEFAULT_KEY_PREFIX = "agentscope:sandbox:lock:";

        private final UnifiedJedis jedis;
        private String keyPrefix = DEFAULT_KEY_PREFIX;
        private long leaseTtlMs = DEFAULT_LEASE_TTL.toMillis();
        private long retryIntervalMs = DEFAULT_RETRY_INTERVAL.toMillis();

        private Builder(UnifiedJedis jedis) {
            this.jedis = Objects.requireNonNull(jedis, "jedis must not be null");
        }

        /**
         * 设置锁键前缀。
         * <p>默认为 {@code "agentscope:sandbox:lock:"}；多个环境或租户共享 Redis 时可分离命名空间。
         * @param keyPrefix 键前缀，不得为 null 或空白
         */
        public Builder keyPrefix(String keyPrefix) {
            if (keyPrefix == null || keyPrefix.isBlank()) {
                throw new IllegalArgumentException("keyPrefix must not be blank");
            }
            this.keyPrefix = keyPrefix.endsWith(":") ? keyPrefix : keyPrefix + ":";
            return this;
        }

        /**
         * 设置各 Redis 锁键的 TTL。
         * <p>默认为 {@code 30 minutes}，必须超过最坏智能体调用时长，包括模型延迟、重试与快照操作。
         * 调用超时后键过期，下一个等待者可进入；这是兜底，不是正确性保证。
         * @param ttl 租约 TTL，必须为正数
         */
        public Builder leaseTtl(Duration ttl) {
            Objects.requireNonNull(ttl, "leaseTtl must not be null");
            if (ttl.isNegative() || ttl.isZero()) {
                throw new IllegalArgumentException("leaseTtl must be positive");
            }
            this.leaseTtlMs = ttl.toMillis();
            return this;
        }

        /**
         * 设置两次获取锁尝试之间的轮询间隔。
         * <p>默认为 {@code 500 ms}；较小值降低延迟但增加 Redis 往返，较大值减轻负载但增加排队延迟。
         * @param interval 重试间隔，必须为正数
         */
        public Builder retryInterval(Duration interval) {
            Objects.requireNonNull(interval, "retryInterval must not be null");
            if (interval.isNegative() || interval.isZero()) {
                throw new IllegalArgumentException("retryInterval must be positive");
            }
            this.retryIntervalMs = interval.toMillis();
            return this;
        }

        /**
         * 构建 {@link RedisSandboxExecutionGuard}。
         */
        public RedisSandboxExecutionGuard build() {
            return new RedisSandboxExecutionGuard(this);
        }
    }
}
