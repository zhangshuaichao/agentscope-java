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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 任务执行注册表，保存各任务的运行上下文，支持查询与统计。
 * <p>注册 {@link RunExecutionContext}，按任务查询全部运行或指定运行，并统计任务／运行数量。
 * <h2>结构</h2>
 * <pre>
 * Map&lt;String, List&lt;RunExecutionContext&gt;&gt;
 *   ├─ "task-001" → [ctx(run=0), ctx(run=1), ctx(run=2)]
 *   ├─ "task-002" → [ctx(run=0), ctx(run=1)]
 *   └─ "task-003" → [ctx(run=0)]
 * </pre>
 * <p>底层为 {@link ConcurrentHashMap}，列表使用同步包装，支持并发读写。
 * 提供 {@link #cleanup(String)} 与 {@link #clearAll()} 手动清理，长生命周期进程应安排清理以限制内存占用。
 * <p>【学习提示】这些静态进程内表不是持久训练日志，重启会丢失；读取上下文也不会自动复制其可变对象。
 * @see RunExecutionContext
 * @see RunRegistry
 */
public class TaskExecutionRegistry {

    private static final Logger logger = LoggerFactory.getLogger(TaskExecutionRegistry.class);

    /**
     * 任务 ID 到运行上下文列表的映射，查询时按运行 ID 排序。
     */
    private static final ConcurrentHashMap<String, List<RunExecutionContext>> registry =
            new ConcurrentHashMap<>();

    // 私有构造器，禁止实例化。
    private TaskExecutionRegistry() {}

    /**
     * 注册运行上下文，通常在影子智能体完成后由 TrainingRouter 调用。
     * @param context 运行上下文
     */
    public static void register(RunExecutionContext context) {
        if (context == null) {
            logger.warn("Attempted to register null context, ignoring");
            return;
        }

        String taskId = context.getTaskId();
        registry.compute(
                taskId,
                (k, existingList) -> {
                    List<RunExecutionContext> list =
                            existingList != null
                                    ? existingList
                                    : Collections.synchronizedList(new ArrayList<>());
                    list.add(context);
                    return list;
                });

        logger.debug(
                "Registered context: {}, total runs for this task: {}",
                context,
                registry.get(taskId).size());
    }

    /**
     * 获取指定任务全部运行上下文，返回按运行 ID 升序排列的列表副本。
     * @param taskId 任务 ID
     * @return 任务的全部运行上下文；任务不存在时为空列表
     */
    public static List<RunExecutionContext> getRunsByTask(String taskId) {
        if (taskId == null) {
            return Collections.emptyList();
        }

        List<RunExecutionContext> contexts = registry.get(taskId);
        if (contexts == null || contexts.isEmpty()) {
            return Collections.emptyList();
        }

        // 返回列表副本，按 runId 排序。
        synchronized (contexts) {
            return contexts.stream()
                    .sorted(Comparator.comparing(ctx -> Integer.parseInt(ctx.getRunId())))
                    .collect(Collectors.toList());
        }
    }

    /**
     * 获取指定任务和运行的上下文。
     * @param taskId 任务 ID
     * @param runId 运行 ID
     * @return 对应上下文，未找到时为 null
     */
    public static RunExecutionContext getRun(String taskId, String runId) {
        if (taskId == null || runId == null) {
            return null;
        }

        List<RunExecutionContext> contexts = registry.get(taskId);
        if (contexts == null) {
            return null;
        }

        synchronized (contexts) {
            return contexts.stream()
                    .filter(ctx -> ctx.getRunId().equals(runId))
                    .findFirst()
                    .orElse(null);
        }
    }

    /**
     * 获取全部任务 ID。
     * @return 任务 ID 集合
     */
    public static Set<String> getAllTaskIds() {
        return new HashSet<>(registry.keySet());
    }

    /**
     * 获取指定任务的运行次数。
     * @param taskId 任务 ID
     * @return 运行次数，任务不存在时为 0
     */
    public static int getRunCount(String taskId) {
        if (taskId == null) {
            return 0;
        }
        List<RunExecutionContext> contexts = registry.get(taskId);
        return contexts != null ? contexts.size() : 0;
    }

    /**
     * 获取任务总数。
     * @return 任务数
     */
    public static int getTaskCount() {
        return registry.size();
    }

    /**
     * 获取所有任务的运行总数。
     * @return 运行总数
     */
    public static int getTotalRunCount() {
        return registry.values().stream().mapToInt(List::size).sum();
    }

    /**
     * 清理指定任务全部数据，释放长期运行进程持有的内存。
     * @param taskId 任务 ID
     * @return 清理的运行数量
     */
    public static int cleanup(String taskId) {
        if (taskId == null) {
            return 0;
        }

        List<RunExecutionContext> removed = registry.remove(taskId);
        int count = removed != null ? removed.size() : 0;

        if (count > 0) {
            logger.info("Cleaned up {} runs for task: {}", count, taskId);
        }

        return count;
    }

    /**
     * 清空全部任务的运行数据。
     * <p><b>警告：</b>影响此进程注册表中的所有任务，谨慎调用。
     */
    public static void clearAll() {
        int taskCount = registry.size();
        int runCount = getTotalRunCount();
        registry.clear();
        logger.info("Cleared all data: {} tasks, {} runs", taskCount, runCount);
    }

    /**
     * 获取统计摘要。
     * @return 统计信息
     */
    public static RegistryStats getStats() {
        return new RegistryStats(getTaskCount(), getTotalRunCount());
    }

    /**
     * 统计信息。
     */
    public static class RegistryStats {
        private final int taskCount;
        private final int totalRunCount;

        private RegistryStats(int taskCount, int totalRunCount) {
            this.taskCount = taskCount;
            this.totalRunCount = totalRunCount;
        }

        public int getTaskCount() {
            return taskCount;
        }

        public int getTotalRunCount() {
            return totalRunCount;
        }

        public double getAverageRunsPerTask() {
            return taskCount > 0 ? (double) totalRunCount / taskCount : 0.0;
        }

        @Override
        public String toString() {
            return String.format(
                    "RegistryStats{tasks=%d, runs=%d, avg=%.2f}",
                    taskCount, totalRunCount, getAverageRunsPerTask());
        }
    }
}
