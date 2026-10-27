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

import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 基于 {@link BaseStore#putIfVersion} 的分布式 {@link PeriodicGate}。
 * 用于跨节点协调周期性后台工作，例如记忆维护、技能整理等。
 *
 * <p>【学习提示】先读取时间戳与版本，再用 CAS 争抢本轮执行资格；竞争失败最多重读五次。
 * 领取成功即写入时间戳，并不等待后台任务成功，因此这是最小间隔门控，不是任务事务或可续租锁。
 * 实际跨节点互斥依赖存储正确实现条件写；机器时钟偏差也会影响间隔判断。
 */
public final class StoreBackedPeriodicGate implements PeriodicGate {

    private static final Logger log = LoggerFactory.getLogger(StoreBackedPeriodicGate.class);

    private static final List<String> NAMESPACE = List.of("coordination", "periodic");
    private static final int MAX_CAS_RETRIES = 5;
    private static final Instant EPOCH = Instant.EPOCH;

    private final BaseStore store;

    public StoreBackedPeriodicGate(BaseStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override
    public boolean tryClaim(String name, Duration minGap) {
        if (name == null || name.isBlank() || minGap == null) {
            return false;
        }
        Instant now = Instant.now();
        for (int attempt = 0; attempt < MAX_CAS_RETRIES; attempt++) {
            StoreItem item;
            try {
                item = store.get(NAMESPACE, name);
            } catch (RuntimeException e) {
                log.warn("Failed to read periodic gate {}: {}", name, e.getMessage());
                return false;
            }
            long expectedVersion = item != null ? item.version() : 0L;
            Instant last = readLastClaimAt(item);
            if (Duration.between(last, now).compareTo(minGap) < 0) {
                return false;
            }
            Map<String, Object> value = new HashMap<>();
            value.put("lastClaimAt", now.toEpochMilli());
            try {
                if (store.putIfVersion(NAMESPACE, name, value, expectedVersion)) {
                    return true;
                }
            } catch (RuntimeException e) {
                log.warn("Failed to claim periodic gate {}: {}", name, e.getMessage());
                return false;
            }
        }
        return false;
    }

    private static Instant readLastClaimAt(StoreItem item) {
        if (item == null || item.value() == null) {
            return EPOCH;
        }
        Object ts = item.value().get("lastClaimAt");
        if (ts instanceof Number n) {
            return Instant.ofEpochMilli(n.longValue());
        }
        return EPOCH;
    }
}
