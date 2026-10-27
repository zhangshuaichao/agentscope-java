/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.agentscope.core.training.runner;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 运行 ID 注册表，管理每个任务的执行计数。
 * <p>同一任务的递增 Run ID 可关联对比实验、失败重试或 A/B 测试。
 * 使用 {@link ConcurrentHashMap} 保存计数器，以 {@link AtomicInteger} 并发递增。
 * 提供手工清理接口；周期性过期清理目前只是预留接口，不会自动发生。
 * <p>【学习提示】这是进程内计数，不提供多节点全局顺序；清理后同一 taskId 会重新从 0 计数。
 * @see TaskIdGenerator
 * @see RunExecutionContext
 */
class RunRegistry {

    /**
     * 任务 ID 到递增运行计数器的映射。
     */
    private static final ConcurrentHashMap<String, AtomicInteger> taskRunCounters =
            new ConcurrentHashMap<>();

    // 私有构造器，禁止实例化。
    private RunRegistry() {}

    /**
     * 为指定任务分配新运行 ID，从 0 递增，支持多线程并发调用。
     * @param taskId 任务 ID，不得为 null
     * @return 字符串运行 ID，例如 "0"、"1"、"2"
     * @throws IllegalArgumentException taskId 为 null 时抛出
     */
    public static String allocateRunId(String taskId) {
        if (taskId == null) {
            throw new IllegalArgumentException("Task ID cannot be null");
        }

        AtomicInteger counter = taskRunCounters.computeIfAbsent(taskId, k -> new AtomicInteger(0));

        int runId = counter.getAndIncrement();
        return String.valueOf(runId);
    }

    /**
     * 获取指定任务当前运行次数。
     * @param taskId 任务 ID
     * @return 当前次数，任务不存在时为 0
     */
    public static int getCurrentRunCount(String taskId) {
        if (taskId == null) {
            return 0;
        }
        AtomicInteger counter = taskRunCounters.get(taskId);
        return counter != null ? counter.get() : 0;
    }

    /**
     * 清理指定任务计数器，释放内存。
     * <p>可在任务完成或反馈提交成功后调用，但应避免与同任务新运行并发重用 ID。
     * @param taskId 任务 ID
     */
    public static void cleanup(String taskId) {
        if (taskId != null) {
            taskRunCounters.remove(taskId);
        }
    }

    /**
     * 清空所有任务计数器，谨慎调用。
     * <p>通常用于 TrainingRunner 停止或系统重置。
     */
    public static void clearAll() {
        taskRunCounters.clear();
    }

    /**
     * 获取注册表中的任务数量。
     * @return 任务数量
     */
    public static int size() {
        return taskRunCounters.size();
    }

    /**
     * 按 TTL 清理旧计数器的预留接口，当前未实现。
     * <p>未来可记录任务最后访问时间，再定期扫描和清理。
     * @param ttl 过期时长
     */
    public static void cleanupOldTasks(Duration ttl) {
        // TODO：未来可实现按时间自动清理。
        // 当前版本需要手动调用 cleanup()。
    }
}
