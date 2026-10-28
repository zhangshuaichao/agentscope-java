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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** {@link BotLoopGuard} 测试，包括空闲对端清理。 */
class BotLoopGuardTest {

    @Test
    void eventsWithinBudgetAreAllowed() {
        BotLoopGuard guard = new BotLoopGuard(3, 60_000L, 60_000L);
        assertTrue(guard.allow("peer-1"));
        assertTrue(guard.allow("peer-1"));
        assertTrue(guard.allow("peer-1"));
        assertEquals(1, guard.trackedPeers());
    }

    @Test
    void exceedingTheWindowTripsCooldown() {
        BotLoopGuard guard = new BotLoopGuard(2, 60_000L, 60_000L);
        assertTrue(guard.allow("peer-1"));
        assertTrue(guard.allow("peer-1"));
        assertFalse(guard.allow("peer-1"));
        assertTrue(guard.isCoolingDown("peer-1"));
        assertEquals(1, guard.trackedPeers());
    }

    @Test
    void nullAndBlankPeerKeysAreAllowed() {
        BotLoopGuard guard = new BotLoopGuard(1, 60_000L, 60_000L);
        assertTrue(guard.allow(null));
        assertTrue(guard.allow("  "));
        assertEquals(0, guard.trackedPeers());
    }

    @Test
    void cooldownStateIsNotEvictedWhileActive() {
        BotLoopGuard guard = new BotLoopGuard(1, 60_000L, 60_000L);
        assertTrue(guard.allow("peer-1"));
        assertFalse(guard.allow("peer-1")); // 触发冷却
        assertEquals(0, guard.evictIdlePeers()); // 距离空闲阈值还有 120 秒
        assertTrue(guard.isCoolingDown("peer-1"));
    }

    @Test
    void idlePeersAreEvictedAfterWindowPlusCooldown() throws InterruptedException {
        // 高负载运行器可能让 60 毫秒休眠超过 160 毫秒阈值，CI 已观察到此情况；
        // 因此非空闲阶段使用实际停顿难以跨越的阈值，而空闲阶段
        // 只依赖 Thread.sleep 不会提前返回，总休眠仍为 240 毫秒。
        BotLoopGuard notIdle = new BotLoopGuard(5, 60_000L, 60_000L);
        assertTrue(notIdle.allow("peer-1"));
        Thread.sleep(60);
        assertEquals(0, notIdle.evictIdlePeers()); // 尚未空闲到阈值
        BotLoopGuard guard = new BotLoopGuard(5, 80L, 80L); // 空闲阈值为 160 毫秒
        assertTrue(guard.allow("peer-1"));
        Thread.sleep(180);
        assertEquals(1, guard.evictIdlePeers());
        assertEquals(0, guard.trackedPeers());
        // 清除后，对端从全新状态开始。
        assertTrue(guard.allow("peer-1"));
    }

    @Test
    void activePeersSurviveEvictionOfIdleOnes() throws InterruptedException {
        // allow() 使用与空闲清理相同的 windowMillis + cooldownMillis 阈值顺带扫描，
        // 两次 allow 间若停顿，扫描可能在显式 evictIdlePeers() 前就清除
        // 空闲对端。各阶段为数百毫秒停顿预留 400 毫秒余量：
        // 首次 allow 距 1000 毫秒扫描阈值尚有 600 毫秒，
        // 两个清理点中活动对端均距离空闲阈值 600 毫秒，
        // 显式清理只依赖 Thread.sleep 不提前返回，
        // 即休眠 1200 毫秒超过 1000 毫秒阈值。
        BotLoopGuard guard = new BotLoopGuard(5, 500L, 500L); // 扫描／空闲阈值：1000 毫秒
        assertTrue(guard.allow("idle-peer"));
        Thread.sleep(600);
        assertTrue(guard.allow("active-peer"));
        Thread.sleep(600); // 空闲对端已空闲约 1200 毫秒，活动对端约 600 毫秒
        assertEquals(1, guard.evictIdlePeers());
        assertTrue(guard.allow("active-peer"));
        assertEquals(1, guard.trackedPeers());
    }

    @Test
    void sweepRunsOnTheFirstCallAfterTheSweepInterval() throws InterruptedException {
        BotLoopGuard guard =
                new BotLoopGuard(5, 80L, 80L); // 扫描间隔等于空闲阈值：160 毫秒
        assertTrue(guard.allow("stale-peer")); // 首次调用记录扫描时钟
        Thread.sleep(200);
        assertTrue(guard.allow("busy-peer")); // 超过间隔后的首次调用清理空闲对端
        assertEquals(1, guard.trackedPeers());
        assertFalse(guard.isCoolingDown("stale-peer"));
    }
}
