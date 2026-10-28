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
package io.agentscope.builder.web.managed.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.stereotype.Component;

/** 逐会话 Hands 租约计数器：获取、释放和获取超时。为 {@code HandsLeaseService} 提供阶段 D 可观测性，由 {@code DeploymentService} / 部署状态端点读取，无需接入完整指标后端即可展示 self_hosted Hands 健康状况。 */
@Component
public class HandsMetrics {

    /** 单个会话计数器的不可变快照。 */
    public record Snapshot(long acquires, long releases, long timeouts) {}

    private final ConcurrentHashMap<String, LongAdder> acquires = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LongAdder> releases = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LongAdder> timeouts = new ConcurrentHashMap<>();

    public void recordAcquire(String sessionId) {
        adder(acquires, sessionId).increment();
    }

    public void recordRelease(String sessionId) {
        adder(releases, sessionId).increment();
    }

    public void recordTimeout(String sessionId) {
        adder(timeouts, sessionId).increment();
    }

    /** 返回单个会话的当前计数（从未记录时全部为零）。 */
    public Snapshot snapshot(String sessionId) {
        return new Snapshot(
                count(acquires, sessionId), count(releases, sessionId), count(timeouts, sessionId));
    }

    /** 返回至少记录过一个事件的所有会话，以 sessionId 为键。 */
    public Map<String, Snapshot> snapshotAll() {
        Map<String, Snapshot> result = new ConcurrentHashMap<>();
        for (String sessionId : acquires.keySet()) {
            result.put(sessionId, snapshot(sessionId));
        }
        for (String sessionId : releases.keySet()) {
            result.putIfAbsent(sessionId, snapshot(sessionId));
        }
        for (String sessionId : timeouts.keySet()) {
            result.putIfAbsent(sessionId, snapshot(sessionId));
        }
        return result;
    }

    private static LongAdder adder(ConcurrentHashMap<String, LongAdder> map, String sessionId) {
        return map.computeIfAbsent(sessionId, k -> new LongAdder());
    }

    private static long count(ConcurrentHashMap<String, LongAdder> map, String sessionId) {
        LongAdder adder = map.get(sessionId);
        return adder == null ? 0L : adder.sum();
    }
}
