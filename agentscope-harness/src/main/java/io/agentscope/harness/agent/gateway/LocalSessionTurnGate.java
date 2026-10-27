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
package io.agentscope.harness.agent.gateway;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * 网关轮次使用的进程内、按键公平互斥。
 *
 * <p>每个键使用一个公平 {@link Semaphore}。{@link #acquire(String)} 阻塞等待槽位可用；
 * 不会抛出 {@link TurnBusyException}。
 *
 * <p>【学习提示】这里的公平队列只协调当前进程中的同键调用，不保护其他节点。
 * 接口相同不等于锁范围相同；跨节点部署必须核对实际注入的 SessionTurnGate 实现。
 */
public final class LocalSessionTurnGate implements SessionTurnGate {

    private final ConcurrentHashMap<String, Semaphore> gates = new ConcurrentHashMap<>();

    @Override
    public TurnLease acquire(String key) throws InterruptedException {
        Semaphore semaphore = gates.computeIfAbsent(key, k -> new Semaphore(1, true));
        semaphore.acquire();
        // 【学习提示】TurnLease 契约要求幂等，但此返回值直接调用 release，没有一次性保护。
        // 重复 close 会增加许可数；调用路径不能依赖本实现自动防止重复释放。
        return semaphore::release;
    }

    @Override
    public boolean isRunning(String key) {
        Semaphore semaphore = gates.get(key);
        return semaphore != null && semaphore.availablePermits() == 0;
    }
}
