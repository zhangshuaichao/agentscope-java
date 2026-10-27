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
package io.agentscope.harness.agent.memory;

import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 跟踪已启动但尚未完成的记忆后台任务（提取写入、维护），使
 * {@code HarnessAgent.close()} 能在释放资源前等待它们结束。
 *
 * <p>分发这些任务的中间件实例按智能体调用创建，因此智能体的 {@code close()} 方法无法直接访问它们。
 * 在途计数是进程级的：{@code close()} 会等待本次等待调用之前启动的所有后台任务完成，
 * 避免异步工作区写入与资源清理竞争（例如测试删除临时工作区）。
 */
public final class MemoryBackgroundTasks {

    private static final Logger log = LoggerFactory.getLogger(MemoryBackgroundTasks.class);

    private static final Object MONITOR = new Object();
    private static int inFlight = 0;

    private MemoryBackgroundTasks() {}

    /** 标记一个无需调用方等待的后台任务开始。 */
    public static void begin() {
        synchronized (MONITOR) {
            inFlight++;
        }
    }

    /** 标记一个无需调用方等待的后台任务结束，必须与 {@link #begin()} 配对。 */
    public static void end() {
        synchronized (MONITOR) {
            if (inFlight > 0) {
                inFlight--;
            }
            if (inFlight == 0) {
                MONITOR.notifyAll();
            }
        }
    }

    /**
     * 阻塞等待本次调用前启动的所有后台任务完成，或等待超时。
     *
     * @param timeout 最长等待时间
     * @param unit {@code timeout} 的时间单位
     * @return 任务在超时前全部结束时返回 {@code true}
     */
    public static boolean awaitQuiescence(long timeout, TimeUnit unit) {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        synchronized (MONITOR) {
            while (inFlight > 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return false;
                }
                try {
                    MONITOR.wait(remaining / 1_000_000L, (int) (remaining % 1_000_000L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.debug("Memory background tasks await interrupted: {}", e.getMessage());
                    return false;
                }
            }
            return true;
        }
    }
}
