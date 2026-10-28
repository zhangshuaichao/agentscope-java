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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.training.util.TrainingTestConstants;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TaskExecutionRegistry Unit Tests")
class TaskExecutionRegistryTest {

    @BeforeEach
    void setUp() {
        TaskExecutionRegistry.clearAll();
    }

    @AfterEach
    void tearDown() {
        TaskExecutionRegistry.clearAll();
    }

    @Test
    @DisplayName("Should register execution context")
    void shouldRegisterExecutionContext() {
        // 准备
        RunExecutionContext context =
                RunExecutionContext.create(
                        TrainingTestConstants.TEST_TASK_ID, TrainingTestConstants.TEST_RUN_ID);

        // 执行
        TaskExecutionRegistry.register(context);

        // 断言
        assertEquals(1, TaskExecutionRegistry.getTaskCount());
        assertEquals(1, TaskExecutionRegistry.getTotalRunCount());
    }

    @Test
    @DisplayName("Should ignore null context")
    void shouldIgnoreNullContext() {
        // 执行
        TaskExecutionRegistry.register(null);

        // 断言
        assertEquals(0, TaskExecutionRegistry.getTaskCount());
    }

    @Test
    @DisplayName("Should get runs by task")
    void shouldGetRunsByTask() {
        // 准备
        RunExecutionContext ctx0 =
                RunExecutionContext.create(TrainingTestConstants.TEST_TASK_ID, "0");
        RunExecutionContext ctx1 =
                RunExecutionContext.create(TrainingTestConstants.TEST_TASK_ID, "1");
        RunExecutionContext ctx2 =
                RunExecutionContext.create(TrainingTestConstants.TEST_TASK_ID, "2");

        TaskExecutionRegistry.register(ctx2);
        TaskExecutionRegistry.register(ctx0);
        TaskExecutionRegistry.register(ctx1);

        // 执行
        List<RunExecutionContext> runs =
                TaskExecutionRegistry.getRunsByTask(TrainingTestConstants.TEST_TASK_ID);

        // 断言
        assertEquals(3, runs.size());
        // 应按 runId 排序。
        assertEquals("0", runs.get(0).getRunId());
        assertEquals("1", runs.get(1).getRunId());
        assertEquals("2", runs.get(2).getRunId());
    }

    @Test
    @DisplayName("Should return empty list for non-existent task")
    void shouldReturnEmptyListForNonExistentTask() {
        // 执行
        List<RunExecutionContext> runs = TaskExecutionRegistry.getRunsByTask("non-existent");

        // 断言
        assertTrue(runs.isEmpty());
    }

    @Test
    @DisplayName("Should return empty list for null taskId")
    void shouldReturnEmptyListForNullTaskId() {
        // 执行
        List<RunExecutionContext> runs = TaskExecutionRegistry.getRunsByTask(null);

        // 断言
        assertTrue(runs.isEmpty());
    }

    @Test
    @DisplayName("Should get specific run")
    void shouldGetSpecificRun() {
        // 准备
        RunExecutionContext ctx =
                RunExecutionContext.create(
                        TrainingTestConstants.TEST_TASK_ID, TrainingTestConstants.TEST_RUN_ID);
        ctx.addMsgId(TrainingTestConstants.TEST_MSG_ID_1);
        TaskExecutionRegistry.register(ctx);

        // 执行
        RunExecutionContext result =
                TaskExecutionRegistry.getRun(
                        TrainingTestConstants.TEST_TASK_ID, TrainingTestConstants.TEST_RUN_ID);

        // 断言
        assertNotNull(result);
        assertEquals(TrainingTestConstants.TEST_TASK_ID, result.getTaskId());
        assertEquals(TrainingTestConstants.TEST_RUN_ID, result.getRunId());
    }

    @Test
    @DisplayName("Should return null for non-existent run")
    void shouldReturnNullForNonExistentRun() {
        // 执行
        RunExecutionContext result = TaskExecutionRegistry.getRun("task", "999");

        // 断言
        assertNull(result);
    }

    @Test
    @DisplayName("Should return null when taskId or runId is null")
    void shouldReturnNullWhenTaskIdOrRunIdIsNull() {
        // 断言
        assertNull(TaskExecutionRegistry.getRun(null, "0"));
        assertNull(TaskExecutionRegistry.getRun("task", null));
    }

    @Test
    @DisplayName("Should get all task IDs")
    void shouldGetAllTaskIds() {
        // 准备
        TaskExecutionRegistry.register(RunExecutionContext.create("task-1", "0"));
        TaskExecutionRegistry.register(RunExecutionContext.create("task-2", "0"));
        TaskExecutionRegistry.register(RunExecutionContext.create("task-3", "0"));

        // 执行
        Set<String> taskIds = TaskExecutionRegistry.getAllTaskIds();

        // 断言
        assertEquals(3, taskIds.size());
        assertTrue(taskIds.contains("task-1"));
        assertTrue(taskIds.contains("task-2"));
        assertTrue(taskIds.contains("task-3"));
    }

    @Test
    @DisplayName("Should get run count for task")
    void shouldGetRunCountForTask() {
        // 准备
        TaskExecutionRegistry.register(
                RunExecutionContext.create(TrainingTestConstants.TEST_TASK_ID, "0"));
        TaskExecutionRegistry.register(
                RunExecutionContext.create(TrainingTestConstants.TEST_TASK_ID, "1"));
        TaskExecutionRegistry.register(
                RunExecutionContext.create(TrainingTestConstants.TEST_TASK_ID, "2"));

        // 执行
        int count = TaskExecutionRegistry.getRunCount(TrainingTestConstants.TEST_TASK_ID);

        // 断言
        assertEquals(3, count);
    }

    @Test
    @DisplayName("Should return 0 for non-existent task run count")
    void shouldReturnZeroForNonExistentTaskRunCount() {
        // 执行
        int count = TaskExecutionRegistry.getRunCount("non-existent");

        // 断言
        assertEquals(0, count);
    }

    @Test
    @DisplayName("Should return 0 for null taskId run count")
    void shouldReturnZeroForNullTaskIdRunCount() {
        // 执行
        int count = TaskExecutionRegistry.getRunCount(null);

        // 断言
        assertEquals(0, count);
    }

    @Test
    @DisplayName("Should get total run count")
    void shouldGetTotalRunCount() {
        // 准备
        TaskExecutionRegistry.register(RunExecutionContext.create("task-1", "0"));
        TaskExecutionRegistry.register(RunExecutionContext.create("task-1", "1"));
        TaskExecutionRegistry.register(RunExecutionContext.create("task-2", "0"));

        // 执行
        int total = TaskExecutionRegistry.getTotalRunCount();

        // 断言
        assertEquals(3, total);
    }

    @Test
    @DisplayName("Should cleanup task")
    void shouldCleanupTask() {
        // 准备
        TaskExecutionRegistry.register(
                RunExecutionContext.create(TrainingTestConstants.TEST_TASK_ID, "0"));
        TaskExecutionRegistry.register(
                RunExecutionContext.create(TrainingTestConstants.TEST_TASK_ID, "1"));
        TaskExecutionRegistry.register(RunExecutionContext.create("other-task", "0"));

        // 执行
        int cleaned = TaskExecutionRegistry.cleanup(TrainingTestConstants.TEST_TASK_ID);

        // 断言
        assertEquals(2, cleaned);
        assertEquals(1, TaskExecutionRegistry.getTaskCount());
        assertEquals(0, TaskExecutionRegistry.getRunCount(TrainingTestConstants.TEST_TASK_ID));
    }

    @Test
    @DisplayName("Should return 0 when cleanup null taskId")
    void shouldReturnZeroWhenCleanupNullTaskId() {
        // 执行
        int cleaned = TaskExecutionRegistry.cleanup(null);

        // 断言
        assertEquals(0, cleaned);
    }

    @Test
    @DisplayName("Should clear all data")
    void shouldClearAllData() {
        // 准备
        TaskExecutionRegistry.register(RunExecutionContext.create("task-1", "0"));
        TaskExecutionRegistry.register(RunExecutionContext.create("task-2", "0"));

        // 执行
        TaskExecutionRegistry.clearAll();

        // 断言
        assertEquals(0, TaskExecutionRegistry.getTaskCount());
        assertEquals(0, TaskExecutionRegistry.getTotalRunCount());
    }

    @Test
    @DisplayName("Should get stats")
    void shouldGetStats() {
        // 准备
        TaskExecutionRegistry.register(RunExecutionContext.create("task-1", "0"));
        TaskExecutionRegistry.register(RunExecutionContext.create("task-1", "1"));
        TaskExecutionRegistry.register(RunExecutionContext.create("task-2", "0"));
        TaskExecutionRegistry.register(RunExecutionContext.create("task-2", "1"));
        TaskExecutionRegistry.register(RunExecutionContext.create("task-2", "2"));

        // 执行
        TaskExecutionRegistry.RegistryStats stats = TaskExecutionRegistry.getStats();

        // 断言
        assertEquals(2, stats.getTaskCount());
        assertEquals(5, stats.getTotalRunCount());
        assertEquals(2.5, stats.getAverageRunsPerTask(), 0.001);
    }

    @Test
    @DisplayName("Should return 0 average when no tasks")
    void shouldReturnZeroAverageWhenNoTasks() {
        // 执行
        TaskExecutionRegistry.RegistryStats stats = TaskExecutionRegistry.getStats();

        // 断言
        assertEquals(0, stats.getTaskCount());
        assertEquals(0, stats.getTotalRunCount());
        assertEquals(0.0, stats.getAverageRunsPerTask(), 0.001);
    }

    @Test
    @DisplayName("Should produce readable stats toString")
    void shouldProduceReadableStatsToString() {
        // 准备
        TaskExecutionRegistry.register(RunExecutionContext.create("task-1", "0"));
        TaskExecutionRegistry.register(RunExecutionContext.create("task-1", "1"));

        // 执行
        String str = TaskExecutionRegistry.getStats().toString();

        // 断言
        assertTrue(str.contains("tasks=1"));
        assertTrue(str.contains("runs=2"));
    }
}
