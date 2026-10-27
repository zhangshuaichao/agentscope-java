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
package io.agentscope.harness.agent.coordination;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JVM 本地的 {@link PeriodicGate}，基于进程级 {@link ConcurrentHashMap} 和 CAS 更新。
 * 适用于单进程部署，或作为未配置分布式存储时的默认实现。
 *
 * <p>映射使用 static，因此节流窗口在多次 {@code HarnessAgent.Builder.build()} 之间保留。
 * 每次重建都会创建新中间件实例；如果使用实例级映射，每个请求都会重置为 {@link Instant#EPOCH}。
 */
public final class LocalPeriodicGate implements PeriodicGate {

    private static final Instant EPOCH = Instant.EPOCH;

    static final ConcurrentHashMap<String, AtomicReference<Instant>> SHARED_LAST_CLAIM_AT =
            new ConcurrentHashMap<>();

    @Override
    public boolean tryClaim(String name, Duration minGap) {
        if (name == null || name.isBlank() || minGap == null) {
            return false;
        }
        Instant now = Instant.now();
        AtomicReference<Instant> ref =
                SHARED_LAST_CLAIM_AT.computeIfAbsent(name, k -> new AtomicReference<>(EPOCH));
        Instant last = ref.get();
        if (Duration.between(last, now).compareTo(minGap) < 0) {
            return false;
        }
        return ref.compareAndSet(last, now);
    }

    /**
     * 移除上次领取时间早于 {@code maxAge} 的条目，限制长期运行且键变化频繁的服务中的映射大小。
     */
    public void cleanupStaleEntries(Duration maxAge) {
        if (maxAge == null) {
            return;
        }
        Instant cutoff = Instant.now().minus(maxAge);
        SHARED_LAST_CLAIM_AT.entrySet().removeIf(e -> e.getValue().get().isBefore(cutoff));
    }

    /**
     * 清空所有条目，仅供单元测试使用。
     */
    public static void clearForTests() {
        SHARED_LAST_CLAIM_AT.clear();
    }

    /**
     * 设置领取时间戳，仅供单元测试使用。
     */
    public static void seedLastClaimAtForTests(String name, Instant instant) {
        SHARED_LAST_CLAIM_AT
                .computeIfAbsent(name, k -> new AtomicReference<>(EPOCH))
                .set(instant != null ? instant : EPOCH);
    }

    /**
     * 返回指定名称的槽位是否存在，仅供单元测试使用。
     */
    public static boolean hasClaimForTests(String name) {
        return SHARED_LAST_CLAIM_AT.containsKey(name);
    }
}
