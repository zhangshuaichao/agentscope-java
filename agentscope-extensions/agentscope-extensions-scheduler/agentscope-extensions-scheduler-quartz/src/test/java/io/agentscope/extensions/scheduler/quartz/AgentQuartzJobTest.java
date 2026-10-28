/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.extensions.scheduler.quartz;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.extensions.scheduler.config.ScheduleConfig;
import io.agentscope.extensions.scheduler.config.ScheduleMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.JobKey;
import org.quartz.Trigger;
import org.quartz.TriggerKey;
import reactor.core.publisher.Mono;

/** {@link AgentQuartzJob} 单元测试。 */
class AgentQuartzJobTest {

    private AgentQuartzJob agentQuartzJob;
    private QuartzAgentScheduler mockScheduler;
    private JobExecutionContext mockContext;
    private JobDetail mockJobDetail;
    private JobDataMap mockJobDataMap;
    private QuartzScheduleAgentTask mockTask;
    private final String schedulerId = "test-scheduler-id";
    private final String taskName = "test-task-name";

    @BeforeEach
    void setUp() {
        agentQuartzJob = new AgentQuartzJob();
        mockScheduler = mock(QuartzAgentScheduler.class);
        mockContext = mock(JobExecutionContext.class);
        mockJobDetail = mock(JobDetail.class);
        mockJobDataMap = mock(JobDataMap.class);
        mockTask = mock(QuartzScheduleAgentTask.class);

        // 配置公共 mock 行为
        when(mockContext.getJobDetail()).thenReturn(mockJobDetail);
        when(mockJobDetail.getJobDataMap()).thenReturn(mockJobDataMap);
        when(mockJobDataMap.getString("schedulerId")).thenReturn(schedulerId);
        when(mockJobDataMap.getString("taskName")).thenReturn(taskName);

        // 注册模拟调度器
        QuartzAgentSchedulerRegistry.register(schedulerId, mockScheduler);
    }

    @AfterEach
    void tearDown() {
        QuartzAgentSchedulerRegistry.unregister(schedulerId);
    }

    @Test
    void testExecuteSuccess() throws JobExecutionException {
        // 准备任务
        when(mockScheduler.getScheduledAgent(taskName)).thenReturn(mockTask);
        when(mockTask.run())
                .thenReturn(
                        Mono.just(
                                Msg.builder()
                                        .content(TextBlock.builder().text("test").build())
                                        .build()));

        // 准备 ScheduleConfig，默认为 NONE 或 CRON
        ScheduleConfig scheduleConfig = mock(ScheduleConfig.class);
        when(scheduleConfig.getScheduleMode()).thenReturn(ScheduleMode.CRON);
        when(mockTask.getScheduleConfig()).thenReturn(scheduleConfig);

        // 执行
        agentQuartzJob.execute(mockContext);

        // 验证
        verify(mockTask, times(1)).run();
        verify(mockScheduler, never()).rescheduleNextFixedDelay(any(), anyLong());
    }

    @Test
    void testExecuteWithMissingSchedulerIdFallback() throws JobExecutionException {
        // 配置缺失 schedulerId 的情况
        when(mockJobDataMap.getString("schedulerId")).thenReturn(null);

        // 注册默认调度器
        QuartzAgentScheduler defaultScheduler = mock(QuartzAgentScheduler.class);
        QuartzAgentSchedulerRegistry.register("default-scheduler", defaultScheduler);

        // 在默认调度器上准备任务
        QuartzScheduleAgentTask defaultTask = mock(QuartzScheduleAgentTask.class);
        when(defaultScheduler.getScheduledAgent(taskName)).thenReturn(defaultTask);
        when(defaultTask.run())
                .thenReturn(
                        Mono.just(
                                Msg.builder()
                                        .content(TextBlock.builder().text("test").build())
                                        .build()));

        ScheduleConfig scheduleConfig = mock(ScheduleConfig.class);
        when(scheduleConfig.getScheduleMode()).thenReturn(ScheduleMode.CRON);
        when(defaultTask.getScheduleConfig()).thenReturn(scheduleConfig);

        try {
            // 执行
            agentQuartzJob.execute(mockContext);

            // 验证
            verify(defaultTask, times(1)).run();
        } finally {
            QuartzAgentSchedulerRegistry.unregister("default-scheduler");
        }
    }

    @Test
    void testExecuteSchedulerNotFound() throws JobExecutionException {
        // 注销调度器以模拟不存在
        QuartzAgentSchedulerRegistry.unregister(schedulerId);

        agentQuartzJob.execute(mockContext);

        // 验证没有与为空的调度器或任务交互
        verify(mockScheduler, never()).getScheduledAgent(any());
    }

    @Test
    void testExecuteTaskNotFound() throws JobExecutionException {
        // 调度器存在，但任务不存在
        when(mockScheduler.getScheduledAgent(taskName)).thenReturn(null);

        agentQuartzJob.execute(mockContext);

        // 验证
        verify(mockTask, never()).run();
    }

    @Test
    void testExecuteWithException() {
        // 配置任务抛出异常
        when(mockScheduler.getScheduledAgent(taskName)).thenReturn(mockTask);
        when(mockTask.run()).thenThrow(new RuntimeException("Execution failed"));

        // 验证抛出 JobExecutionException
        assertThrows(JobExecutionException.class, () -> agentQuartzJob.execute(mockContext));
    }

    @Test
    void testExecuteFixedDelay() throws JobExecutionException {
        // 准备任务
        when(mockScheduler.getScheduledAgent(taskName)).thenReturn(mockTask);
        when(mockTask.run())
                .thenReturn(
                        Mono.just(
                                Msg.builder()
                                        .content(TextBlock.builder().text("test").build())
                                        .build()));

        // 准备 FIXED_DELAY 的 ScheduleConfig
        ScheduleConfig scheduleConfig = mock(ScheduleConfig.class);
        when(scheduleConfig.getScheduleMode()).thenReturn(ScheduleMode.FIXED_DELAY);
        when(mockTask.getScheduleConfig()).thenReturn(scheduleConfig);

        // 在 JobDataMap 中配置固定延迟值
        long delay = 1000L;
        when(mockJobDataMap.getLongValue("fixedDelay")).thenReturn(delay);
        JobKey jobKey = new JobKey(taskName);
        when(mockJobDetail.getKey()).thenReturn(jobKey);

        // 执行
        agentQuartzJob.execute(mockContext);

        // 验证调用 run 并重新调度
        verify(mockTask, times(1)).run();
        verify(mockScheduler, times(1)).rescheduleNextFixedDelay(jobKey, delay);
    }

    @Test
    void testInterrupt() {
        assertDoesNotThrow(() -> agentQuartzJob.interrupt());
        // interrupted 字段为私有且仅内部使用，目前未广泛参与逻辑，
        // 因此
        // 仅验证不抛异常。
    }

    @Test
    void testExecuteInterruptedSkipsRun() throws JobExecutionException {
        when(mockScheduler.getScheduledAgent(taskName)).thenReturn(mockTask);
        agentQuartzJob.interrupt();
        agentQuartzJob.execute(mockContext);
        verify(mockTask, never()).run();
        verify(mockScheduler, never()).rescheduleNextFixedDelay(any(), anyLong());
    }

    @Test
    void testExecuteInterruptedSkipsRescheduleFixedDelay() throws JobExecutionException {
        when(mockScheduler.getScheduledAgent(taskName)).thenReturn(mockTask);
        ScheduleConfig scheduleConfig = mock(ScheduleConfig.class);
        when(scheduleConfig.getScheduleMode()).thenReturn(ScheduleMode.FIXED_DELAY);
        when(mockTask.getScheduleConfig()).thenReturn(scheduleConfig);
        long delay = 1000L;
        when(mockJobDataMap.getLongValue("fixedDelay")).thenReturn(delay);
        JobKey jobKey = new JobKey(taskName);
        when(mockJobDetail.getKey()).thenReturn(jobKey);

        agentQuartzJob.interrupt();
        agentQuartzJob.execute(mockContext);

        verify(mockTask, never()).run();
        verify(mockScheduler, never()).rescheduleNextFixedDelay(any(), anyLong());
    }

    @Test
    void testExecuteWithInputMsgSuccess() throws JobExecutionException {
        when(mockScheduler.getScheduledAgent(taskName)).thenReturn(mockTask);
        when(mockTask.run(any(Msg.class)))
                .thenReturn(
                        Mono.just(
                                Msg.builder()
                                        .content(
                                                TextBlock.builder().text("test with input").build())
                                        .build()));

        ScheduleConfig scheduleConfig = mock(ScheduleConfig.class);
        when(scheduleConfig.getScheduleMode()).thenReturn(ScheduleMode.CRON);
        when(mockTask.getScheduleConfig()).thenReturn(scheduleConfig);
        when(mockJobDataMap.getString("inputMsg")).thenReturn("{\"name\":\"test_input\"}");

        assertDoesNotThrow(() -> agentQuartzJob.execute(mockContext));

        verify(mockTask, times(1)).run(any(Msg.class));
        verify(mockTask, never()).run();
    }

    @Test
    void testExecuteWithInputMsgDeserializationFailure() {
        when(mockScheduler.getScheduledAgent(taskName)).thenReturn(mockTask);

        Trigger mockTrigger = mock(Trigger.class);
        when(mockContext.getTrigger()).thenReturn(mockTrigger);
        when(mockTrigger.getKey()).thenReturn(new TriggerKey("test-trigger"));
        when(mockJobDetail.getKey()).thenReturn(new JobKey(taskName));

        // 在 JobDataMap 放入无效 JSON，强制触发反序列化异常
        when(mockJobDataMap.getString("inputMsg")).thenReturn("{invalid-json-format}");

        // 执行并捕获 JobExecutionException
        JobExecutionException exception =
                assertThrows(
                        JobExecutionException.class, () -> agentQuartzJob.execute(mockContext));

        // 验证异常被标记为取消当前触发器调度
        assertTrue(
                exception.unscheduleFiringTrigger(),
                "The exception should trigger unscheduling due to toxic data.");
        assertTrue(
                exception.getMessage().contains("Invalid inputMsg JSON"),
                "The exception message should indicate an invalid JSON format.");

        // 验证 run() 和 run(Msg) 都未调用，因为执行已中止
        verify(mockTask, never()).run();
        verify(mockTask, never()).run(any(Msg.class));
    }
}
