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

/**
 * 网关轮次的按键互斥，覆盖用户／渠道入站执行和子智能体通知。
 *
 * <p>实现包括 {@link LocalSessionTurnGate}（进程内公平信号量）及控制面托管的跨节点协调锁。
 * 使用分布式轮次门控时，应将 {@link io.agentscope.core.ReActAgent} 的
 * {@code conflictPolicy} 设为 {@code FAIL}，使并发状态写入显式报错，而不是静默覆盖。
 */
public interface SessionTurnGate {

    /**
     * 获取指定键的轮次槽位，阻塞等待可用，或在繁忙时抛出异常。
     *
     * @param key 规范门控键（通常为 {@link MsgContext#canonicalKey()}）
     * @return 必须关闭以释放槽位的租约
     * @throws InterruptedException 等待线程被中断时抛出
     * @throws TurnBusyException 槽位已被占用，且实现采用较短获取超时（分布式门控）时抛出
     */
    TurnLease acquire(String key) throws InterruptedException, TurnBusyException;

    /**
     * 非阻塞检查：指定键当前有轮次占用时返回 {@code true}。
     * {@link WakeupDispatcher} 用它跳过已经活跃的会话。
     *
     * @param key 规范门控键
     * @return 该键有轮次正在执行时为 {@code true}
     */
    boolean isRunning(String key);
}
