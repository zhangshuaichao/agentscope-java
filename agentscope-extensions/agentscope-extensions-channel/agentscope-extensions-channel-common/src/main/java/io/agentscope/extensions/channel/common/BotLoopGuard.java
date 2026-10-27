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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按对端划分的入站事件滑动窗口限流器，防止机器人被其他机器人或卡住的用户脚本带入失控循环。
 * <p>默认采用 OpenClaw 风格的 {@code 20} 事件／{@code 60s} 窗口；触发后冷却 {@code 60s}，期间 {@link #allow(String)} 返回 {@code false}。
 * <p>对端超过 {@code windowMillis + cooldownMillis} 未记录事件时可淘汰；此时窗口为空且冷却已结束。
 * 清理搭载于调用，没有后台线程：每个 {@code windowMillis + cooldownMillis} 周期后的首次 {@link #allow(String)} 执行清扫。
 * 没有后续调用就不会自动清扫，因此回收时间取决于调用到达。也可显式调用 {@link #evictIdlePeers()}。此类线程安全。
 */
public final class BotLoopGuard {

    private final int maxEventsPerWindow;
    private final long windowMillis;
    private final long cooldownMillis;

    private final ConcurrentHashMap<String, PeerState> states = new ConcurrentHashMap<>();
    private volatile long lastSweepMs;

    public BotLoopGuard() {
        this(20, 60_000L, 60_000L);
    }

    public BotLoopGuard(int maxEventsPerWindow, long windowMillis, long cooldownMillis) {
        if (maxEventsPerWindow <= 0 || windowMillis <= 0 || cooldownMillis <= 0) {
            throw new IllegalArgumentException("all bounds must be positive");
        }
        this.maxEventsPerWindow = maxEventsPerWindow;
        this.windowMillis = windowMillis;
        this.cooldownMillis = cooldownMillis;
    }

    /**
     * 为 {@code peerKey} 记录事件，预算内返回 {@code true}；超出窗口上限后进入冷却，冷却期间返回 {@code false}。
     */
    public boolean allow(String peerKey) {
        if (peerKey == null || peerKey.isBlank()) {
            return true;
        }
        long now = System.currentTimeMillis();
        maybeSweep(now);
        PeerState state = states.computeIfAbsent(peerKey, k -> new PeerState(now));
        synchronized (state) {
            if (state.cooldownUntilMs > now) {
                return false;
            }
            // 移除窗口之前的事件。
            while (!state.events.isEmpty() && now - state.events.peekFirst() > windowMillis) {
                state.events.pollFirst();
            }
            if (state.events.size() >= maxEventsPerWindow) {
                state.cooldownUntilMs = now + cooldownMillis;
                state.events.clear();
                state.lastEventMs = now;
                return false;
            }
            state.events.addLast(now);
            state.lastEventMs = now;
            return true;
        }
    }

    /**
     * 在 {@link #allow(String)} 上搭载空闲清扫，每个 {@code windowMillis + cooldownMillis} 周期后的首次调用触发。
     * 不启动后台线程；并发调用可能重复清扫。
     */
    private void maybeSweep(long now) {
        if (now - lastSweepMs >= windowMillis + cooldownMillis) {
            lastSweepMs = now;
            evictIdlePeers(now);
        }
    }

    /**
     * {@code peerKey} 当前处于冷却时返回 {@code true}。
     */
    public boolean isCoolingDown(String peerKey) {
        PeerState state = states.get(peerKey);
        if (state == null) {
            return false;
        }
        synchronized (state) {
            return state.cooldownUntilMs > System.currentTimeMillis();
        }
    }

    /**
     * 返回当前跟踪的对端数量，主要用于测试和可观测性。
     */
    public int trackedPeers() {
        return states.size();
    }

    /**
     * 移除超过 {@code windowMillis + cooldownMillis} 未记录事件的对端；此时窗口已空、冷却已过，
     * 下次事件会开启新窗口。新条目以创建时间作为最后事件时间，避免刚创建即被判为空闲。
     * @return 移除的对端数量
     */
    public int evictIdlePeers() {
        return evictIdlePeers(System.currentTimeMillis());
    }

    private int evictIdlePeers(long now) {
        long idleThreshold = windowMillis + cooldownMillis;
        int removed = 0;
        for (Map.Entry<String, PeerState> entry : states.entrySet()) {
            PeerState state = entry.getValue();
            synchronized (state) {
                // 条件移除：只有键仍指向已检查为空闲的同一对象时才删除，
                // 不会删除并发替换进去的新对象。
                if (now - state.lastEventMs > idleThreshold
                        && states.remove(entry.getKey(), state)) {
                    removed++;
                }
            }
        }
        return removed;
    }

    private static final class PeerState {
        final Deque<Long> events = new ArrayDeque<>();
        long cooldownUntilMs;
        long lastEventMs;

        PeerState(long now) {
            // 创建时写入时间戳，防止新条目被立即判为空闲。
            this.lastEventMs = now;
        }
    }
}
