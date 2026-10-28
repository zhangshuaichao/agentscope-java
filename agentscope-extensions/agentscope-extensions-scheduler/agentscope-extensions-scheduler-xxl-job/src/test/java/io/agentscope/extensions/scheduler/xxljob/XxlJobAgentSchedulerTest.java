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
package io.agentscope.extensions.scheduler.xxljob;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.xxl.job.core.executor.XxlJobExecutor;
import io.agentscope.extensions.scheduler.ScheduleAgentTask;
import io.agentscope.extensions.scheduler.config.AgentConfig;
import io.agentscope.extensions.scheduler.config.DashScopeModelConfig;
import io.agentscope.extensions.scheduler.config.RuntimeAgentConfig;
import io.agentscope.extensions.scheduler.config.ScheduleConfig;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/** {@link XxlJobAgentScheduler} 单元测试。 */
@Timeout(60)
class XxlJobAgentSchedulerTest {

    private XxlJobExecutor mockExecutor;
    private XxlJobAgentScheduler scheduler;

    @BeforeEach
    void setUp() {
        mockExecutor = mock(XxlJobExecutor.class);
        scheduler = new XxlJobAgentScheduler(mockExecutor);
    }

    @Test
    void testConstructorWithValidExecutor() {
        XxlJobAgentScheduler scheduler = new XxlJobAgentScheduler(mockExecutor);
        assertNotNull(scheduler);
    }

    @Test
    void testConstructorWithNullExecutor() {
        assertThrows(IllegalArgumentException.class, () -> new XxlJobAgentScheduler(null));
    }

    @Test
    void testScheduleWithDefaultScheduleConfig() {
        DashScopeModelConfig modelConfig =
                DashScopeModelConfig.builder().apiKey("test-key").modelName("qwen-max").build();

        RuntimeAgentConfig agentConfig =
                RuntimeAgentConfig.builder()
                        .name("TestAgent")
                        .modelConfig(modelConfig)
                        .sysPrompt("Test prompt")
                        .build();

        ScheduleAgentTask task = scheduler.schedule(agentConfig);

        assertNotNull(task);
        assertEquals("TestAgent", task.getName());
    }

    @Test
    void testScheduleWithNullAgentConfig() {
        ScheduleConfig scheduleConfig = ScheduleConfig.builder().build();

        assertThrows(
                IllegalArgumentException.class, () -> scheduler.schedule(null, scheduleConfig));
    }

    @Test
    void testScheduleWithNullScheduleConfig() {
        DashScopeModelConfig modelConfig =
                DashScopeModelConfig.builder().apiKey("test-key").modelName("qwen-max").build();

        RuntimeAgentConfig agentConfig =
                RuntimeAgentConfig.builder()
                        .name("TestAgent")
                        .modelConfig(modelConfig)
                        .sysPrompt("Test prompt")
                        .build();

        assertThrows(IllegalArgumentException.class, () -> scheduler.schedule(agentConfig, null));
    }

    @Test
    void testScheduleWithEmptyAgentName() {
        DashScopeModelConfig modelConfig =
                DashScopeModelConfig.builder().apiKey("test-key").modelName("qwen-max").build();

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        RuntimeAgentConfig.builder()
                                .name("")
                                .modelConfig(modelConfig)
                                .sysPrompt("Test prompt")
                                .build());

        ScheduleConfig scheduleConfig = ScheduleConfig.builder().build();
    }

    @Test
    void testScheduleWithUnsupportedScheduleMode() {
        DashScopeModelConfig modelConfig =
                DashScopeModelConfig.builder().apiKey("test-key").modelName("qwen-max").build();

        RuntimeAgentConfig agentConfig =
                RuntimeAgentConfig.builder()
                        .name("TestAgent")
                        .modelConfig(modelConfig)
                        .sysPrompt("Test prompt")
                        .build();

        ScheduleConfig scheduleConfig = ScheduleConfig.builder().cron("0 0 8 * * ?").build();

        assertThrows(
                UnsupportedOperationException.class,
                () -> scheduler.schedule(agentConfig, scheduleConfig));
    }

    @Test
    void testCancelThrowsUnsupportedOperation() {
        assertThrows(UnsupportedOperationException.class, () -> scheduler.cancel("TestAgent"));
    }

    @Test
    void testGetScheduledAgent() {
        DashScopeModelConfig modelConfig =
                DashScopeModelConfig.builder().apiKey("test-key").modelName("qwen-max").build();

        RuntimeAgentConfig agentConfig =
                RuntimeAgentConfig.builder()
                        .name("TestAgent")
                        .modelConfig(modelConfig)
                        .sysPrompt("Test prompt")
                        .build();

        scheduler.schedule(agentConfig);

        ScheduleAgentTask task = scheduler.getScheduledAgent("TestAgent");
        assertNotNull(task);
        assertEquals("TestAgent", task.getName());
    }

    @Test
    void testGetScheduledAgentWithNullName() {
        ScheduleAgentTask task = scheduler.getScheduledAgent(null);
        assertTrue(task == null);
    }

    @Test
    void testGetScheduledAgentWithEmptyName() {
        ScheduleAgentTask task = scheduler.getScheduledAgent("");
        assertTrue(task == null);
    }

    @Test
    void testGetScheduledAgentNotFound() {
        ScheduleAgentTask task = scheduler.getScheduledAgent("NonExistentAgent");
        assertTrue(task == null);
    }

    @Test
    void testGetAllScheduleAgentTasks() {
        List<ScheduleAgentTask> tasks = scheduler.getAllScheduleAgentTasks();
        assertNotNull(tasks);
        assertEquals(0, tasks.size());
    }

    @Test
    void testGetAllScheduleAgentTasksAfterScheduling() {
        DashScopeModelConfig modelConfig =
                DashScopeModelConfig.builder().apiKey("test-key").modelName("qwen-max").build();

        RuntimeAgentConfig agentConfig1 =
                RuntimeAgentConfig.builder()
                        .name("TestAgent1")
                        .modelConfig(modelConfig)
                        .sysPrompt("Test prompt")
                        .build();

        RuntimeAgentConfig agentConfig2 =
                RuntimeAgentConfig.builder()
                        .name("TestAgent2")
                        .modelConfig(modelConfig)
                        .sysPrompt("Test prompt")
                        .build();

        scheduler.schedule(agentConfig1);
        scheduler.schedule(agentConfig2);

        List<ScheduleAgentTask> tasks = scheduler.getAllScheduleAgentTasks();
        assertNotNull(tasks);
        assertEquals(2, tasks.size());
    }

    @Test
    void testGetSchedulerType() {
        assertEquals("xxl-job", scheduler.getSchedulerType());
    }

    @Test
    void testShutdown() {
        // 不应抛异常
        scheduler.shutdown();
    }

    @Test
    void testScheduleAlreadyScheduledTask() {
        DashScopeModelConfig modelConfig =
                DashScopeModelConfig.builder().apiKey("test-key").modelName("qwen-max").build();

        RuntimeAgentConfig agentConfig =
                RuntimeAgentConfig.builder()
                        .name("TestAgent")
                        .modelConfig(modelConfig)
                        .sysPrompt("Test prompt")
                        .build();

        // 首次调度
        ScheduleAgentTask task1 = scheduler.schedule(agentConfig);
        assertNotNull(task1);

        // 再次调度同一智能体
        ScheduleAgentTask task2 = scheduler.schedule(agentConfig);
        assertNotNull(task2);

        // 应返回同一实例
        assertSame(task1, task2);
    }

    @Test
    void testScheduleWithAgentConfigConversion() {
        DashScopeModelConfig modelConfig =
                DashScopeModelConfig.builder().apiKey("test-key").modelName("qwen-max").build();

        // 通过构建器创建非 RuntimeAgentConfig 实例
        AgentConfig agentConfig =
                AgentConfig.builder()
                        .name("TestConversionAgent")
                        .modelConfig(modelConfig)
                        .sysPrompt("Test conversion")
                        .build();

        ScheduleAgentTask task = scheduler.schedule(agentConfig);

        assertNotNull(task);
        assertEquals("TestConversionAgent", task.getName());
    }

    @Test
    void testScheduleWithWhitespaceOnlyName() {
        DashScopeModelConfig modelConfig =
                DashScopeModelConfig.builder().apiKey("test-key").modelName("qwen-max").build();

        // 仅空白名称应抛异常
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        RuntimeAgentConfig.builder()
                                .name("   ")
                                .modelConfig(modelConfig)
                                .sysPrompt("Test prompt")
                                .build());
    }

    @Test
    void testScheduleWithCronScheduleMode() {
        DashScopeModelConfig modelConfig =
                DashScopeModelConfig.builder().apiKey("test-key").modelName("qwen-max").build();

        RuntimeAgentConfig agentConfig =
                RuntimeAgentConfig.builder()
                        .name("TestAgent")
                        .modelConfig(modelConfig)
                        .sysPrompt("Test prompt")
                        .build();

        ScheduleConfig scheduleConfig = ScheduleConfig.builder().cron("0 0 8 * * ?").build();

        assertThrows(
                UnsupportedOperationException.class,
                () -> scheduler.schedule(agentConfig, scheduleConfig));
    }

    @Test
    void testScheduleWithFixedRateScheduleMode() {
        DashScopeModelConfig modelConfig =
                DashScopeModelConfig.builder().apiKey("test-key").modelName("qwen-max").build();

        RuntimeAgentConfig agentConfig =
                RuntimeAgentConfig.builder()
                        .name("TestAgent")
                        .modelConfig(modelConfig)
                        .sysPrompt("Test prompt")
                        .build();

        ScheduleConfig scheduleConfig = ScheduleConfig.builder().fixedRate(5000L).build();

        assertThrows(
                UnsupportedOperationException.class,
                () -> scheduler.schedule(agentConfig, scheduleConfig));
    }

    @Test
    void testScheduleWithFixedDelayScheduleMode() {
        DashScopeModelConfig modelConfig =
                DashScopeModelConfig.builder().apiKey("test-key").modelName("qwen-max").build();

        RuntimeAgentConfig agentConfig =
                RuntimeAgentConfig.builder()
                        .name("TestAgent")
                        .modelConfig(modelConfig)
                        .sysPrompt("Test prompt")
                        .build();

        ScheduleConfig scheduleConfig = ScheduleConfig.builder().fixedDelay(3000L).build();

        assertThrows(
                UnsupportedOperationException.class,
                () -> scheduler.schedule(agentConfig, scheduleConfig));
    }

    @Test
    void testScheduleWithNoneScheduleMode() {
        DashScopeModelConfig modelConfig =
                DashScopeModelConfig.builder().apiKey("test-key").modelName("qwen-max").build();

        RuntimeAgentConfig agentConfig =
                RuntimeAgentConfig.builder()
                        .name("TestAgent")
                        .modelConfig(modelConfig)
                        .sysPrompt("Test prompt")
                        .build();

        ScheduleConfig scheduleConfig = ScheduleConfig.builder().build();

        // NONE 模式应可用
        ScheduleAgentTask task = scheduler.schedule(agentConfig, scheduleConfig);
        assertNotNull(task);
    }

    @Test
    void testConcurrentScheduling() throws InterruptedException {
        int threadCount = 10;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        DashScopeModelConfig modelConfig =
                DashScopeModelConfig.builder().apiKey("test-key").modelName("qwen-max").build();

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            executorService.submit(
                    () -> {
                        try {
                            startLatch.await();
                            RuntimeAgentConfig agentConfig =
                                    RuntimeAgentConfig.builder()
                                            .name("ConcurrentAgent" + index)
                                            .modelConfig(modelConfig)
                                            .sysPrompt("Test prompt")
                                            .build();
                            ScheduleAgentTask task = scheduler.schedule(agentConfig);
                            if (task != null) {
                                successCount.incrementAndGet();
                            }
                        } catch (Exception e) {
                            // 此测试忽略异常
                        } finally {
                            endLatch.countDown();
                        }
                    });
        }

        // 同时启动所有线程
        startLatch.countDown();
        endLatch.await(10, TimeUnit.SECONDS);
        executorService.shutdown();

        // 全部应成功
        assertTrue(successCount.get() > 0);
        assertEquals(threadCount, scheduler.getAllScheduleAgentTasks().size());
    }

    @Test
    void testShutdownClearsAllTasks() {
        DashScopeModelConfig modelConfig =
                DashScopeModelConfig.builder().apiKey("test-key").modelName("qwen-max").build();

        RuntimeAgentConfig agentConfig1 =
                RuntimeAgentConfig.builder()
                        .name("TestAgent1")
                        .modelConfig(modelConfig)
                        .sysPrompt("Test prompt")
                        .build();

        RuntimeAgentConfig agentConfig2 =
                RuntimeAgentConfig.builder()
                        .name("TestAgent2")
                        .modelConfig(modelConfig)
                        .sysPrompt("Test prompt")
                        .build();

        scheduler.schedule(agentConfig1);
        scheduler.schedule(agentConfig2);

        assertEquals(2, scheduler.getAllScheduleAgentTasks().size());

        // 关闭应清空所有任务
        scheduler.shutdown();

        assertEquals(0, scheduler.getAllScheduleAgentTasks().size());
    }

    @Test
    void testGetScheduledAgentWithWhitespaceName() {
        ScheduleAgentTask task = scheduler.getScheduledAgent("   ");
        assertTrue(task == null);
    }

    @Test
    void testMultipleGetScheduledAgent() {
        DashScopeModelConfig modelConfig =
                DashScopeModelConfig.builder().apiKey("test-key").modelName("qwen-max").build();

        RuntimeAgentConfig agentConfig =
                RuntimeAgentConfig.builder()
                        .name("TestAgent")
                        .modelConfig(modelConfig)
                        .sysPrompt("Test prompt")
                        .build();

        scheduler.schedule(agentConfig);

        // 多次获取同一智能体
        ScheduleAgentTask task1 = scheduler.getScheduledAgent("TestAgent");
        ScheduleAgentTask task2 = scheduler.getScheduledAgent("TestAgent");

        assertNotNull(task1);
        assertNotNull(task2);
        assertSame(task1, task2);
    }

    @Test
    void testGetAllScheduleAgentTasksReturnsNewList() {
        DashScopeModelConfig modelConfig =
                DashScopeModelConfig.builder().apiKey("test-key").modelName("qwen-max").build();

        RuntimeAgentConfig agentConfig =
                RuntimeAgentConfig.builder()
                        .name("TestAgent")
                        .modelConfig(modelConfig)
                        .sysPrompt("Test prompt")
                        .build();

        scheduler.schedule(agentConfig);

        // 获取两次任务列表
        List<ScheduleAgentTask> tasks1 = scheduler.getAllScheduleAgentTasks();
        List<ScheduleAgentTask> tasks2 = scheduler.getAllScheduleAgentTasks();

        // 应返回不同列表实例
        assertTrue(tasks1 != tasks2);
        assertEquals(tasks1.size(), tasks2.size());
    }

    @Test
    void testScheduleWithDifferentModelConfigs() {
        DashScopeModelConfig modelConfig1 =
                DashScopeModelConfig.builder().apiKey("test-key-1").modelName("qwen-max").build();

        DashScopeModelConfig modelConfig2 =
                DashScopeModelConfig.builder().apiKey("test-key-2").modelName("qwen-plus").build();

        RuntimeAgentConfig agentConfig1 =
                RuntimeAgentConfig.builder()
                        .name("TestAgent1")
                        .modelConfig(modelConfig1)
                        .sysPrompt("Test prompt 1")
                        .build();

        RuntimeAgentConfig agentConfig2 =
                RuntimeAgentConfig.builder()
                        .name("TestAgent2")
                        .modelConfig(modelConfig2)
                        .sysPrompt("Test prompt 2")
                        .build();

        ScheduleAgentTask task1 = scheduler.schedule(agentConfig1);
        ScheduleAgentTask task2 = scheduler.schedule(agentConfig2);

        assertNotNull(task1);
        assertNotNull(task2);
        assertEquals("TestAgent1", task1.getName());
        assertEquals("TestAgent2", task2.getName());
    }

    @Test
    void testScheduleWithLongAgentName() {
        DashScopeModelConfig modelConfig =
                DashScopeModelConfig.builder().apiKey("test-key").modelName("qwen-max").build();

        String longName = "A".repeat(200); // 超长名称

        RuntimeAgentConfig agentConfig =
                RuntimeAgentConfig.builder()
                        .name(longName)
                        .modelConfig(modelConfig)
                        .sysPrompt("Test prompt")
                        .build();

        ScheduleAgentTask task = scheduler.schedule(agentConfig);

        assertNotNull(task);
        assertEquals(longName, task.getName());
    }

    @Test
    void testScheduleWithSpecialCharactersInName() {
        DashScopeModelConfig modelConfig =
                DashScopeModelConfig.builder().apiKey("test-key").modelName("qwen-max").build();

        String specialName = "Test-Agent_123!@#";

        RuntimeAgentConfig agentConfig =
                RuntimeAgentConfig.builder()
                        .name(specialName)
                        .modelConfig(modelConfig)
                        .sysPrompt("Test prompt")
                        .build();

        ScheduleAgentTask task = scheduler.schedule(agentConfig);

        assertNotNull(task);
        assertEquals(specialName, task.getName());
    }

    @Test
    void testGetSchedulerTypeConsistency() {
        // 类型应保持一致
        String type1 = scheduler.getSchedulerType();
        String type2 = scheduler.getSchedulerType();

        assertEquals("xxl-job", type1);
        assertEquals(type1, type2);
    }

    @Test
    void testScheduleAfterScheduleAndGet() {
        DashScopeModelConfig modelConfig =
                DashScopeModelConfig.builder().apiKey("test-key").modelName("qwen-max").build();

        RuntimeAgentConfig agentConfig1 =
                RuntimeAgentConfig.builder()
                        .name("TestAgent1")
                        .modelConfig(modelConfig)
                        .sysPrompt("Test prompt")
                        .build();

        // 调度第一个智能体
        scheduler.schedule(agentConfig1);
        ScheduleAgentTask retrievedTask = scheduler.getScheduledAgent("TestAgent1");
        assertNotNull(retrievedTask);

        // 获取后调度第二个智能体
        RuntimeAgentConfig agentConfig2 =
                RuntimeAgentConfig.builder()
                        .name("TestAgent2")
                        .modelConfig(modelConfig)
                        .sysPrompt("Test prompt")
                        .build();

        ScheduleAgentTask task2 = scheduler.schedule(agentConfig2);
        assertNotNull(task2);

        // 两者都应可查询
        assertEquals(2, scheduler.getAllScheduleAgentTasks().size());
    }

    @Test
    void testScheduleWithRegistryJobHandlerException() {
        DashScopeModelConfig modelConfig =
                DashScopeModelConfig.builder().apiKey("test-key").modelName("qwen-max").build();

        RuntimeAgentConfig agentConfig =
                RuntimeAgentConfig.builder()
                        .name("TestAgentWithException")
                        .modelConfig(modelConfig)
                        .sysPrompt("Test prompt")
                        .build();

        // 模拟 XxlJobExecutor.registryJobHandler 抛异常
        try (MockedStatic<XxlJobExecutor> mockedStatic = Mockito.mockStatic(XxlJobExecutor.class)) {
            mockedStatic
                    .when(
                            () ->
                                    XxlJobExecutor.registryJobHandler(
                                            Mockito.anyString(), Mockito.any()))
                    .thenThrow(new RuntimeException("Failed to register job handler"));

            // 注册失败时应抛 RuntimeException
            RuntimeException exception =
                    assertThrows(RuntimeException.class, () -> scheduler.schedule(agentConfig));

            // 验证异常消息
            assertTrue(exception.getMessage().contains("Failed to schedule task"));
            assertTrue(exception.getMessage().contains("TestAgentWithException"));

            // 验证任务未加入已调度任务集合
            assertEquals(0, scheduler.getAllScheduleAgentTasks().size());
        }
    }
}
