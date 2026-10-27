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
package io.agentscope.extensions.channel.common;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link InboundEventDeduplicator} 的有界进程内实现，按 {@code msgId} 去重，适用于失败后重试同一消息 ID 的 webhook。
 * <p>映射限制为 {@link #maxEntries}；满时先淘汰过期项，再淘汰未指定的条目。
 * 条目按 {@link #ttlMillis} 惰性过期。
 * <p>状态只在 JVM 堆中，去重限于单进程；多实例部署参见 {@link InboundEventDeduplicator}。此类线程安全。
 */
public final class IdempotencyStore implements InboundEventDeduplicator {

    private final long ttlMillis;
    private final int maxEntries;
    private final ConcurrentHashMap<String, Long> seen = new ConcurrentHashMap<>();

    /**
     * 默认 TTL 为 5 分钟，容量为 1 万条；实际是否足够应按渠道重试峰值配置。
     */
    public IdempotencyStore() {
        this(5 * 60_000L, 10_000);
    }

    public IdempotencyStore(long ttlMillis, int maxEntries) {
        if (ttlMillis <= 0) {
            throw new IllegalArgumentException("ttlMillis must be positive");
        }
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be positive");
        }
        this.ttlMillis = ttlMillis;
        this.maxEntries = maxEntries;
    }

    /**
     * 登记 {@code key}：TTL 内未观察到 {@code key} 时返回 {@code true}，调用方继续处理；重复交付返回 {@code false}。
     * 被接受的新观察重启保留窗口；TTL 后只有首个重投通过，其余继续被丢弃。
     */
    @Override
    public boolean firstSeen(String key) {
        if (key == null) {
            return true;
        }
        long now = System.currentTimeMillis();
        sweep(now);
        Long prior = seen.putIfAbsent(key, now);
        if (prior == null) {
            return true;
        }
        if (now - prior > ttlMillis) {
            // 仅 CAS 获胜者重启窗口，失败者视为重复交付。
            return seen.replace(key, prior, now);
        }
        return false;
    }

    /**
     * 移除超过 {@link #ttlMillis} 的条目，并将大小限制到 {@link #maxEntries}。
     */
    private void sweep(long now) {
        if (seen.size() < maxEntries) {
            return;
        }
        Iterator<Map.Entry<String, Long>> it = seen.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> e = it.next();
            if (now - e.getValue() > ttlMillis) {
                it.remove();
            }
        }
        // 若仍超过容量上限，任意淘汰条目。
        while (seen.size() >= maxEntries) {
            Iterator<String> keyIt = seen.keySet().iterator();
            if (!keyIt.hasNext()) {
                break;
            }
            seen.remove(keyIt.next());
        }
    }

    /**
     * 返回当前大小，主要用于测试和可观测性。
     */
    public int size() {
        return seen.size();
    }
}
