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
package io.agentscope.extensions.mongodb.sandbox;

import com.mongodb.MongoCommandException;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Updates;
import com.mongodb.client.result.UpdateResult;
import io.agentscope.extensions.mongodb.MongoConstants;
import io.agentscope.extensions.mongodb.MongoIndexUtils;
import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;
import io.agentscope.harness.agent.sandbox.SandboxIsolationKey;
import io.agentscope.harness.agent.sandbox.SandboxLease;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Date;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 基于 MongoDB 的 {@link SandboxExecutionGuard}。
 *
 * <p>使用专用 MongoDB 集合作为分布式锁机制。每个锁文档的唯一 {@code _id} 来自 {@link SandboxIsolationKey}，并在 {@code expiresAt} 上建立 TTL 索引以自动清理过期锁。
 *
 * <p>获取锁采用两步方式以实现互斥：
 * <ol>
 *   <li>尝试 {@code insertOne}：唯一 {@code _id} 索引保证原子性，只有一个并发调用方能成功。
 *   <li>锁文档已存在（重复键）时，尝试非 upsert 的 {@code findOneAndUpdate}，过滤条件只匹配 {@code expiresAt &lt;= now}，以接管过期锁。
 * </ol>
 *
 * <p>轮询直至获得锁或获取超时（{@code lockTimeout}）。锁文档自身存活 {@code leaseTtl}；返回的 {@link SandboxLease} 未关闭时，后台看门狗每 {@code leaseTtl / 3} 推进 {@code expiresAt} 续租，使超过初始 TTL 的执行可继续持有锁。
 * 若续租失效（锁已删除或被接管），看门狗只告警：与 Redis 保护器一样，TTL 到期是避免永久死锁的安全阀，而非正确性保证。
 *
 * <p>每次获取都生成随机 UUID 令牌并写入锁文档。续租和释放按此令牌过滤，而非按进程标识符，因此同一 JVM 的两个保护器实例（或 PID 相同的两个进程）不会误释放彼此的锁。这对应 Redis 保护器的 {@code SET NX} 加 Lua CAS 令牌模式。
 *
 * <p>续租执行器采用固定大小线程池（2 个线程），避免某个租约缓慢的 {@code updateOne} 阻塞所有其他租约续租。保护器实现 {@link AutoCloseable}，应由 {@link io.agentscope.harness.agent.DistributedStore} 在存储关闭时关闭执行器。
 *
 * <p>【学习提示】令牌匹配防止误续租／误释放，不会自动阻止已失去租约的旧执行继续产生外部副作用。严格互斥还须考虑续租失败后的执行终止或下游隔离令牌校验。
 */
public final class MongoSandboxExecutionGuard implements SandboxExecutionGuard, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MongoSandboxExecutionGuard.class);

    private static final String FIELD_LOCK_ID = "_id";
    private static final String FIELD_OWNER = "owner";
    private static final String FIELD_TOKEN = "token";
    private static final String FIELD_EXPIRES_AT = "expiresAt";
    private static final int RENEWAL_POOL_SIZE = 2;

    private final MongoCollection<Document> collection;
    private final long lockTimeoutMs;
    private final long leaseTtlMs;
    private final long retryIntervalMs;
    private final String owner;
    private final ScheduledExecutorService renewalExecutor;

    private MongoSandboxExecutionGuard(Builder builder) {
        MongoDatabase db = builder.mongoClient.getDatabase(builder.databaseName);
        this.collection = db.getCollection(builder.collectionName);
        this.lockTimeoutMs = builder.lockTimeout.toMillis();
        this.leaseTtlMs = builder.leaseTtl.toMillis();
        this.retryIntervalMs = builder.retryInterval.toMillis();
        this.owner = builder.owner;
        this.renewalExecutor =
                Executors.newScheduledThreadPool(
                        RENEWAL_POOL_SIZE,
                        runnable -> {
                            Thread thread = new Thread(runnable, "mongo-sandbox-lease-renewal");
                            thread.setDaemon(true);
                            return thread;
                        });
        ensureIndexes();
    }

    /**
     * 创建新构建器。
     *
     * @param mongoClient MongoDB 客户端
     * @return 新构建器
     */
    public static Builder builder(MongoClient mongoClient) {
        return new Builder(mongoClient);
    }

    @Override
    public SandboxLease tryEnter(SandboxIsolationKey key) throws InterruptedException {
        String lockId = composeLockId(key);
        log.debug("[sandbox-guard] Acquiring MongoDB lock: {}", lockId);

        long deadline = System.nanoTime() + Duration.ofMillis(lockTimeoutMs).toNanos();
        while (true) {
            Date now = new Date();
            Date expiresAt = new Date(now.getTime() + leaseTtlMs);
            String token = UUID.randomUUID().toString();

            // 第 1 步：尝试插入新锁文档。此操作原子，
            // _id 唯一索引保证只有一个并发调用方能成功。
            Document lockDoc =
                    new Document(FIELD_LOCK_ID, lockId)
                            .append(FIELD_OWNER, owner)
                            .append(FIELD_TOKEN, token)
                            .append(FIELD_EXPIRES_AT, expiresAt);
            try {
                collection.insertOne(lockDoc);
                log.debug("[sandbox-guard] Acquired MongoDB lock (insert): {}", lockId);
                return new MongoLease(collection, lockId, token, leaseTtlMs, renewalExecutor);
            } catch (MongoWriteException e) {
                if (e.getError().getCode() != 11000) {
                    throw new RuntimeException("Failed to acquire MongoDB lock: " + lockId, e);
                }
                // 重复键：锁文档已存在，继续第 2 步
            } catch (MongoCommandException e) {
                if (e.getErrorCode() != 11000) {
                    throw new RuntimeException("Failed to acquire MongoDB lock: " + lockId, e);
                }
                // 重复键：锁文档已存在，继续第 2 步
            }

            // 第 2 步：锁文档存在，若已过期则尝试接管。
            // 写入自己的令牌，使续租和释放仅作用于本次持有。
            Bson expiredFilter =
                    Filters.and(
                            Filters.eq(FIELD_LOCK_ID, lockId), Filters.lte(FIELD_EXPIRES_AT, now));
            Bson reclaimUpdate =
                    Updates.combine(
                            Updates.set(FIELD_OWNER, owner),
                            Updates.set(FIELD_TOKEN, token),
                            Updates.set(FIELD_EXPIRES_AT, expiresAt));

            Document reclaimed =
                    collection.findOneAndUpdate(
                            expiredFilter,
                            reclaimUpdate,
                            new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));

            if (reclaimed != null) {
                log.debug(
                        "[sandbox-guard] Acquired MongoDB lock (reclaimed from {}): {}",
                        reclaimed.getString(FIELD_OWNER),
                        lockId);
                return new MongoLease(collection, lockId, token, leaseTtlMs, renewalExecutor);
            }

            // 锁存在且尚未过期，由其他调用方持有。
            //
            // 检查与使用间竞争（TOCTOU）安全性：在上面的 insertOne 失败与
            // findOneAndUpdate 接管尝试之间，其他进程可能已释放锁，第三个进程
            // 可能已重新获取。接管过滤条件包含
            // `expiresAt <= now`，新锁的 `expiresAt` 位于未来，
            // 不会匹配接管条件，因此不会被抢走。
            if (log.isDebugEnabled()) {
                Document held = collection.find(Filters.eq(FIELD_LOCK_ID, lockId)).first();
                if (held != null) {
                    log.debug(
                            "[sandbox-guard] Lock held by {}, retrying: {}",
                            held.getString(FIELD_OWNER),
                            lockId);
                }
            }

            if (System.nanoTime() >= deadline) {
                throw new InterruptedException("Timed out waiting for MongoDB lock: " + lockId);
            }

            // MongoDB 没有类似 MySQL GET_LOCK 的服务端阻塞锁，因此使用轮询。
            // InterruptedException 已在方法签名声明，由 sleep 传播。
            Thread.sleep(retryIntervalMs);
        }
    }

    private void ensureIndexes() {
        MongoIndexUtils.createIndexWithMigration(
                collection,
                Indexes.ascending(FIELD_EXPIRES_AT),
                new IndexOptions().expireAfter(0L, TimeUnit.SECONDS));
    }

    private static String composeLockId(SandboxIsolationKey key) {
        String raw = key.getScope().name().toLowerCase() + ":" + key.getValue();
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            return "lock:" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    @Override
    public void close() {
        renewalExecutor.shutdownNow();
        try {
            if (!renewalExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                log.warn("[sandbox-guard] Renewal executor did not terminate cleanly");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class MongoLease implements SandboxLease {

        private final MongoCollection<Document> collection;
        private final String lockId;
        private final String token;
        private final long leaseTtlMs;
        private final ScheduledFuture<?> renewal;

        MongoLease(
                MongoCollection<Document> collection,
                String lockId,
                String token,
                long leaseTtlMs,
                ScheduledExecutorService renewalExecutor) {
            this.collection = collection;
            this.lockId = lockId;
            this.token = token;
            this.leaseTtlMs = leaseTtlMs;
            // 按租期三分之一的间隔续租，预留余量，降低单次调度遗漏或
            // 缓慢写入导致租约丢失的风险。
            long renewalIntervalMs = Math.max(1L, leaseTtlMs / 3);
            this.renewal =
                    renewalExecutor.scheduleAtFixedRate(
                            this::renew,
                            renewalIntervalMs,
                            renewalIntervalMs,
                            TimeUnit.MILLISECONDS);
        }

        /**
         * 看门狗周期任务：租约未关闭时推进 {@code expiresAt}，使超过初始 TTL 的执行继续持有自己的锁。按唯一令牌过滤，只续租当前持有者的租约。
         */
        private void renew() {
            try {
                Date expiresAt = new Date(System.currentTimeMillis() + leaseTtlMs);
                UpdateResult result =
                        collection.updateOne(
                                Filters.and(
                                        Filters.eq(FIELD_LOCK_ID, lockId),
                                        Filters.eq(FIELD_TOKEN, token)),
                                Updates.set(FIELD_EXPIRES_AT, expiresAt));
                if (result.getMatchedCount() == 0) {
                    // 锁文档消失或令牌不匹配：它已过期，并在
                    // 续租成功前被接管。仅告警；与 Redis 保护器相同，
                    // TTL 到期是安全阀，不是正确性保证。
                    log.warn(
                            "[sandbox-guard] MongoDB lock {} lost during execution (expired or"
                                    + " reclaimed); continuing without the lock",
                            lockId);
                }
            } catch (Exception e) {
                // 不要让异常逃逸定时任务，否则执行器会
                // 悄然停止后续续租。
                log.warn(
                        "[sandbox-guard] Failed to renew MongoDB lock {}: {}",
                        lockId,
                        e.getMessage());
            }
        }

        @Override
        public void close() {
            renewal.cancel(false);
            try {
                // 只在令牌仍匹配时删除，防止租约过期并被其他进程
                // 重新获取后调用 close() 时释放别人的锁。
                // 这也使 close() 幂等：已释放时，deleteOne
                // 不执行任何删除。
                collection.deleteOne(
                        Filters.and(
                                Filters.eq(FIELD_LOCK_ID, lockId), Filters.eq(FIELD_TOKEN, token)));
                log.debug("[sandbox-guard] Released MongoDB lock: {}", lockId);
            } catch (Exception e) {
                log.warn(
                        "[sandbox-guard] Failed to release MongoDB lock {}: {}",
                        lockId,
                        e.getMessage());
            }
        }
    }

    /**
     * {@link MongoSandboxExecutionGuard} 的构建器。
     */
    public static final class Builder {

        private static final Duration DEFAULT_RETRY_INTERVAL = Duration.ofMillis(500);
        private static final Duration DEFAULT_LEASE_TTL = Duration.ofMinutes(30);

        private final MongoClient mongoClient;
        private String databaseName = MongoConstants.DEFAULT_DATABASE;
        private String collectionName = MongoConstants.SANDBOX_LOCKS_COLLECTION;
        private Duration lockTimeout = Duration.ofMinutes(30);
        private Duration leaseTtl = DEFAULT_LEASE_TTL;
        private Duration retryInterval = DEFAULT_RETRY_INTERVAL;
        private String owner = "agentscope:" + ProcessHandle.current().pid();

        Builder(MongoClient mongoClient) {
            this.mongoClient = Objects.requireNonNull(mongoClient, "mongoClient");
        }

        public Builder databaseName(String databaseName) {
            this.databaseName = databaseName;
            return this;
        }

        public Builder collectionName(String collectionName) {
            this.collectionName = collectionName;
            return this;
        }

        /**
         * 设置获取锁的最长等待时间，超时后 {@link #tryEnter} 抛出 {@link InterruptedException}。此值仅限制排队延迟，不限制锁的存活期；后者由 {@link #leaseTtl(Duration)} 控制。
         *
         * <p>默认 {@code 30 minutes}。
         *
         * @param timeout 获取超时，必须为正
         * @return 当前构建器
         */
        public Builder lockTimeout(Duration timeout) {
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("timeout must be positive");
            }
            this.lockTimeout = timeout;
            return this;
        }

        /**
         * 设置获取时写入的锁文档存活期（{@code expiresAt}）。租约未关闭时，后台看门狗每 {@code leaseTtl / 3} 续租，使超过 TTL 的执行可继续持有锁。持有者未释放便终止时，经过 {@code leaseTtl} 后其他调用方可接管锁；MongoDB TTL 后台删除另有延迟，不是精确计时器。
         *
         * <p>【学习提示】后台通常按约 60 秒周期检查，负载下删除可能更晚，不能把 60 秒当成硬性上限。接管路径主动比较 expiresAt，不必等待文档被后台删除。参见 <a href="https://www.mongodb.com/docs/manual/core/index-ttl/">MongoDB TTL 官方说明</a>。
         *
         * <p>默认 {@code 30 minutes}。
         *
         * @param ttl 租约 TTL，必须为正
         * @return 当前构建器
         */
        public Builder leaseTtl(Duration ttl) {
            Objects.requireNonNull(ttl, "ttl");
            if (ttl.isNegative() || ttl.isZero()) {
                throw new IllegalArgumentException("ttl must be positive");
            }
            this.leaseTtl = ttl;
            return this;
        }

        /**
         * 设置获取锁尝试之间的轮询间隔。
         *
         * <p>默认 {@code 500 ms}。较小值以更多 MongoDB 往返换取较低延迟；较大值降低负载，但增加排队延迟。
         *
         * @param interval 重试间隔，必须为正
         * @return 当前构建器
         */
        public Builder retryInterval(Duration interval) {
            Objects.requireNonNull(interval, "retryInterval");
            if (interval.isNegative() || interval.isZero()) {
                throw new IllegalArgumentException("retryInterval must be positive");
            }
            this.retryInterval = interval;
            return this;
        }

        /**
         * 设置用于诊断的人类可读所有者标签。它保存于锁文档以便观察，但不用于续租或释放过滤；过滤使用随机 UUID 令牌，保证保护器实例间唯一。
         *
         * @param owner 所有者标签
         * @return 当前构建器
         */
        public Builder owner(String owner) {
            this.owner = owner;
            return this;
        }

        public MongoSandboxExecutionGuard build() {
            return new MongoSandboxExecutionGuard(this);
        }
    }
}
