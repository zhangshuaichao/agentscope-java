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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.extensions.scheduler.ScheduleAgentTask;
import io.agentscope.extensions.scheduler.config.AgentConfig;
import io.agentscope.extensions.scheduler.config.ModelConfig;
import io.agentscope.extensions.scheduler.config.RuntimeAgentConfig;
import io.agentscope.extensions.scheduler.config.ScheduleConfig;
import io.agentscope.extensions.scheduler.config.ScheduleMode;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.quartz.CronScheduleBuilder;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.Trigger.TriggerState;
import org.quartz.TriggerBuilder;
import org.quartz.UnableToInterruptJobException;

/**
 * {@link QuartzAgentScheduler} 单元测试。
 *
 * <p>全部测试使用 {@link StubModelConfig}，且触发器不会在清理前触发，因此无论调度时序如何都不会访问真实模型端点。
 */
@Timeout(30)
class QuartzAgentSchedulerTest {

    /** 初始延迟使 fixedRate/fixedDelay 触发时间远超测试生命周期；无延迟时从 {@code new Date()} 立即开始，会在测试中途触发，与 {@code tearDown} 的 {@code scheduler.shutdown(true)} 竞争。 */
    private static final long INITIAL_DELAY_MS = 60_000L;

    private static final ModelConfig STUB_MODEL_CONFIG = new StubModelConfig();

    private QuartzAgentScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = QuartzAgentScheduler.builder().autoStart(true).build();
    }

    @Test
    void testGetAllScheduleAgentTasksFromQuartzFallback() throws SchedulerException {
        Scheduler mockScheduler = mock(Scheduler.class);
        QuartzAgentScheduler mockAgentScheduler =
                QuartzAgentScheduler.builder().scheduler(mockScheduler).build();

        // 模拟 Quartz 返回一个任务键
        JobKey jobKey = new JobKey("QuartzOnlyTask", "agentscope-quartz");
        when(mockScheduler.getJobKeys(any())).thenReturn(Collections.singleton(jobKey));
        when(mockScheduler.checkExists(jobKey)).thenReturn(true);

        // 模拟 JobDetail
        JobDetail jobDetail =
                JobBuilder.newJob(AgentQuartzJob.class)
                        .withIdentity(jobKey)
                        .storeDurably(true)
                        .usingJobData("schedulerId", "test-id")
                        .usingJobData("taskName", "QuartzOnlyTask")
                        .build();
        when(mockScheduler.getJobDetail(jobKey)).thenReturn(jobDetail);

        // 模拟 Cron Trigger
        Trigger trigger =
                TriggerBuilder.newTrigger()
                        .withIdentity("trigger", "agentscope-quartz")
                        .withSchedule(CronScheduleBuilder.cronSchedule("0 0 8 * * ?"))
                        .build();
        // getTriggersOfJob 返回 List<? extends Trigger>，需注意
        // 泛型类型
        doReturn(Collections.singletonList(trigger)).when(mockScheduler).getTriggersOfJob(jobKey);

        // 执行
        List<ScheduleAgentTask> tasks = mockAgentScheduler.getAllScheduleAgentTasks();

        // 断言
        assertNotNull(tasks);
        assertEquals(1, tasks.size());
        assertEquals("QuartzOnlyTask", tasks.get(0).getName());

        mockAgentScheduler.shutdown();
    }

    @AfterEach
    void tearDown() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    @Test
    void testConstructorWithValidScheduler() {
        assertNotNull(scheduler);
    }

    @Test
    void testScheduleWithDefaultScheduleConfig() {
        ModelConfig modelConfig = STUB_MODEL_CONFIG;

        RuntimeAgentConfig agentConfig =
                RuntimeAgentConfig.builder()
                        .name("TestAgent")
                        .modelConfig(modelConfig)
                        .sysPrompt("Test prompt")
                        .build();

        // 测试默认使用 CRON，避免立即执行带来的问题
        ScheduleConfig scheduleConfig = ScheduleConfig.builder().cron("0 0 8 * * ?").build();

        ScheduleAgentTask task = scheduler.schedule(agentConfig, scheduleConfig);

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
        ModelConfig modelConfig = STUB_MODEL_CONFIG;

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
        ModelConfig modelConfig = STUB_MODEL_CONFIG;

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        RuntimeAgentConfig.builder()
                                .name("")
                                .modelConfig(modelConfig)
                                .sysPrompt("Test prompt")
                                .build());
    }

    @Test
    void testCancel() {
        ModelConfig modelConfig = STUB_MODEL_CONFIG;

        RuntimeAgentConfig agentConfig =
                RuntimeAgentConfig.builder()
                        .name("TestAgent")
                        .modelConfig(modelConfig)
                        .sysPrompt("Test prompt")
                        .build();

        ScheduleConfig scheduleConfig = ScheduleConfig.builder().cron("0 0 8 * * ?").build();

        scheduler.schedule(agentConfig, scheduleConfig);

        boolean result = scheduler.cancel("TestAgent");
        assertTrue(result);

        assertNull(scheduler.getScheduledAgent("TestAgent"));
    }

    @Test
    void testCancelNonExistent() {
        boolean result = scheduler.cancel("NonExistentAgent");
        assertFalse(result);
    }

    @Test
    void testGetScheduledAgent() {
        ModelConfig modelConfig = STUB_MODEL_CONFIG;

        RuntimeAgentConfig agentConfig =
                RuntimeAgentConfig.builder()
                        .name("TestAgent")
                        .modelConfig(modelConfig)
                        .sysPrompt("Test prompt")
                        .build();

        ScheduleConfig scheduleConfig = ScheduleConfig.builder().cron("0 0 8 * * ?").build();

        scheduler.schedule(agentConfig, scheduleConfig);

        ScheduleAgentTask task = scheduler.getScheduledAgent("TestAgent");
        assertNotNull(task);
        assertEquals("TestAgent", task.getName());
    }

    @Test
    void testGetScheduledAgentWithNullName() {
        ScheduleAgentTask task = scheduler.getScheduledAgent(null);
        assertNull(task);
    }

    @Test
    void testGetScheduledAgentWithEmptyName() {
        ScheduleAgentTask task = scheduler.getScheduledAgent("");
        assertNull(task);
    }

    @Test
    void testGetScheduledAgentNotFound() {
        ScheduleAgentTask task = scheduler.getScheduledAgent("NonExistentAgent");
        assertNull(task);
    }

    @Test
    void testGetAllScheduleAgentTasks() {
        List<ScheduleAgentTask> tasks = scheduler.getAllScheduleAgentTasks();
        assertNotNull(tasks);
        assertEquals(0, tasks.size());
    }

    @Test
    void testGetAllScheduleAgentTasksAfterScheduling() {
        ModelConfig modelConfig = STUB_MODEL_CONFIG;

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

        ScheduleConfig scheduleConfig = ScheduleConfig.builder().cron("0 0 8 * * ?").build();

        scheduler.schedule(agentConfig1, scheduleConfig);
        scheduler.schedule(agentConfig2, scheduleConfig);

        List<ScheduleAgentTask> tasks = scheduler.getAllScheduleAgentTasks();
        assertNotNull(tasks);
        assertEquals(2, tasks.size());
    }

    @Test
    void testScheduleAlreadyScheduledTask() {
        ModelConfig modelConfig = STUB_MODEL_CONFIG;

        RuntimeAgentConfig agentConfig =
                RuntimeAgentConfig.builder()
                        .name("TestAgent")
                        .modelConfig(modelConfig)
                        .sysPrompt("Test prompt")
                        .build();

        ScheduleConfig scheduleConfig = ScheduleConfig.builder().cron("0 0 8 * * ?").build();

        // 首次调度
        ScheduleAgentTask task1 = scheduler.schedule(agentConfig, scheduleConfig);
        assertNotNull(task1);

        // 再次调度同一智能体
        ScheduleAgentTask task2 = scheduler.schedule(agentConfig, scheduleConfig);
        assertNotNull(task2);

        // 按实现逻辑应返回同一实例
        assertEquals(task1, task2);
    }

    @Test
    void testScheduleWithAgentConfigConversion() {
        ModelConfig modelConfig = STUB_MODEL_CONFIG;

        // 通过构建器创建非 RuntimeAgentConfig 实例
        AgentConfig agentConfig =
                AgentConfig.builder()
                        .name("TestConversionAgent")
                        .modelConfig(modelConfig)
                        .sysPrompt("Test conversion")
                        .build();

        ScheduleConfig scheduleConfig = ScheduleConfig.builder().cron("0 0 8 * * ?").build();

        ScheduleAgentTask task = scheduler.schedule(agentConfig, scheduleConfig);

        assertNotNull(task);
        assertEquals("TestConversionAgent", task.getName());
    }

    @Test
    void testScheduleWithWhitespaceOnlyName() {
        ModelConfig modelConfig = STUB_MODEL_CONFIG;

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
    void testPauseAndResume() {
        ModelConfig modelConfig = STUB_MODEL_CONFIG;

        RuntimeAgentConfig agentConfig =
                RuntimeAgentConfig.builder()
                        .name("TestAgent")
                        .modelConfig(modelConfig)
                        .sysPrompt("Test prompt")
                        .build();

        ScheduleConfig scheduleConfig = ScheduleConfig.builder().cron("0 0 8 * * ?").build();

        scheduler.schedule(agentConfig, scheduleConfig);

        boolean pauseResult = scheduler.pause("TestAgent");
        assertTrue(pauseResult);

        TriggerState statePaused = scheduler.getStatus("TestAgent");
        assertEquals(TriggerState.PAUSED, statePaused);

        boolean resumeResult = scheduler.resume("TestAgent");
        assertTrue(resumeResult);

        TriggerState stateNormal = scheduler.getStatus("TestAgent");
        // 随时序可能为 NORMAL 或其他状态，Cron 通常为 NORMAL
        assertEquals(TriggerState.NORMAL, stateNormal);
    }

    @Test
    void testPauseNonExistent() {
        assertFalse(scheduler.pause("NonExistent"));
    }

    @Test
    void testResumeNonExistent() {
        assertFalse(scheduler.resume("NonExistent"));
    }

    @Test
    void testGetStatusNonExistent() {
        assertNull(scheduler.getStatus("NonExistent"));
    }

    @Test
    void testScheduleWithNoneScheduleMode() {
        ModelConfig modelConfig = STUB_MODEL_CONFIG;

        RuntimeAgentConfig agentConfig =
                RuntimeAgentConfig.builder()
                        .name("TestAgent")
                        .modelConfig(modelConfig)
                        .sysPrompt("Test prompt")
                        .build();

        ScheduleConfig scheduleConfig = ScheduleConfig.builder().build(); // 默认为 NONE

        // NONE 模式应可用，任务注册但不调度
        ScheduleAgentTask task = scheduler.schedule(agentConfig, scheduleConfig);
        assertNotNull(task);
        assertNotNull(scheduler.getScheduledAgent("TestAgent"));
    }

    @Test
    void testScheduleWithSpecialCharactersInName() {
        ModelConfig modelConfig = STUB_MODEL_CONFIG;

        String specialName = "Test-Agent_123!@#";

        RuntimeAgentConfig agentConfig =
                RuntimeAgentConfig.builder()
                        .name(specialName)
                        .modelConfig(modelConfig)
                        .sysPrompt("Test prompt")
                        .build();

        ScheduleConfig scheduleConfig = ScheduleConfig.builder().cron("0 0 8 * * ?").build();

        ScheduleAgentTask task = scheduler.schedule(agentConfig, scheduleConfig);

        assertNotNull(task);
        assertEquals(specialName, task.getName());
    }

    @Test
    void testScheduleWithLongAgentName() {
        ModelConfig modelConfig = STUB_MODEL_CONFIG;

        String longName = "A".repeat(200); // 超长名称

        RuntimeAgentConfig agentConfig =
                RuntimeAgentConfig.builder()
                        .name(longName)
                        .modelConfig(modelConfig)
                        .sysPrompt("Test prompt")
                        .build();

        ScheduleConfig scheduleConfig = ScheduleConfig.builder().cron("0 0 8 * * ?").build();

        ScheduleAgentTask task = scheduler.schedule(agentConfig, scheduleConfig);

        assertNotNull(task);
        assertEquals(longName, task.getName());
    }

    @Test
    void testScheduleWithDifferentModelConfigs() {
        ModelConfig modelConfig1 = STUB_MODEL_CONFIG;

        ModelConfig modelConfig2 = STUB_MODEL_CONFIG;

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

        ScheduleConfig scheduleConfig = ScheduleConfig.builder().cron("0 0 8 * * ?").build();

        ScheduleAgentTask task1 = scheduler.schedule(agentConfig1, scheduleConfig);
        ScheduleAgentTask task2 = scheduler.schedule(agentConfig2, scheduleConfig);

        assertNotNull(task1);
        assertNotNull(task2);
        assertEquals("TestAgent1", task1.getName());
        assertEquals("TestAgent2", task2.getName());
    }

    @Test
    void testGetSchedulerType() {
        assertEquals("quartz", scheduler.getSchedulerType());
    }

    @Test
    void testShutdown() {
        // 不应抛异常
        scheduler.shutdown();
        // 再次调用应安全，保持幂等或平稳处理
        scheduler.shutdown();
    }

    @Test
    void testBuilderWithAutoStartFalse() {
        QuartzAgentScheduler manualScheduler =
                QuartzAgentScheduler.builder().autoStart(false).build();
        assertNotNull(manualScheduler);
        manualScheduler.shutdown();
    }

    @Test
    void testBuilderWithCustomScheduler() throws SchedulerException {
        Scheduler mockScheduler = mock(Scheduler.class);

        QuartzAgentScheduler customScheduler =
                QuartzAgentScheduler.builder().scheduler(mockScheduler).build();
        assertNotNull(customScheduler);

        // 验证调用 start()，autoStart 默认为 true
        verify(mockScheduler).start();

        customScheduler.shutdown();
    }

    @Test
    void testBuilderWithSchedulerStartThrowingException() throws SchedulerException {
        Scheduler mockScheduler = mock(Scheduler.class);
        doThrow(new SchedulerException("Start failed")).when(mockScheduler).start();

        QuartzAgentScheduler.Builder builder =
                QuartzAgentScheduler.builder().scheduler(mockScheduler);
        assertThrows(RuntimeException.class, builder::build);
    }

    @Test
    void testScheduleFixedRate() {
        ModelConfig modelConfig = STUB_MODEL_CONFIG;
        RuntimeAgentConfig agentConfig =
                RuntimeAgentConfig.builder()
                        .name("FixedRateAgent")
                        .modelConfig(modelConfig)
                        .sysPrompt("Test prompt")
                        .build();

        ScheduleConfig scheduleConfig =
                ScheduleConfig.builder().fixedRate(1000L).initialDelay(INITIAL_DELAY_MS).build();

        ScheduleAgentTask task = scheduler.schedule(agentConfig, scheduleConfig);
        assertNotNull(task);
        assertEquals("FixedRateAgent", task.getName());
    }

    @Test
    void testScheduleFixedDelay() {
        ModelConfig modelConfig = STUB_MODEL_CONFIG;
        RuntimeAgentConfig agentConfig =
                RuntimeAgentConfig.builder()
                        .name("FixedDelayAgent")
                        .modelConfig(modelConfig)
                        .sysPrompt("Test prompt")
                        .build();

        ScheduleConfig scheduleConfig =
                ScheduleConfig.builder().fixedDelay(1000L).initialDelay(INITIAL_DELAY_MS).build();

        ScheduleAgentTask task = scheduler.schedule(agentConfig, scheduleConfig);
        assertNotNull(task);
        assertEquals("FixedDelayAgent", task.getName());
    }

    @Test
    void testScheduleFixedRateWithInvalidValue() {
        ModelConfig modelConfig = STUB_MODEL_CONFIG;
        RuntimeAgentConfig agentConfig =
                RuntimeAgentConfig.builder()
                        .name("InvalidFixedRateAgent")
                        .modelConfig(modelConfig)
                        .sysPrompt("Test prompt")
                        .build();

        // 测试 0
        ScheduleConfig scheduleConfigZero = mock(ScheduleConfig.class);
        when(scheduleConfigZero.getScheduleMode()).thenReturn(ScheduleMode.FIXED_RATE);
        when(scheduleConfigZero.getFixedRate()).thenReturn(0L);
        assertThrows(
                IllegalArgumentException.class,
                () -> scheduler.schedule(agentConfig, scheduleConfigZero));

        // 测试负值
        ScheduleConfig scheduleConfigNegative = mock(ScheduleConfig.class);
        when(scheduleConfigNegative.getScheduleMode()).thenReturn(ScheduleMode.FIXED_RATE);
        when(scheduleConfigNegative.getFixedRate()).thenReturn(-100L);
        assertThrows(
                IllegalArgumentException.class,
                () -> scheduler.schedule(agentConfig, scheduleConfigNegative));
    }

    @Test
    void testScheduleFixedDelayWithInvalidValue() {
        ModelConfig modelConfig = STUB_MODEL_CONFIG;
        RuntimeAgentConfig agentConfig =
                RuntimeAgentConfig.builder()
                        .name("InvalidFixedDelayAgent")
                        .modelConfig(modelConfig)
                        .sysPrompt("Test prompt")
                        .build();

        // 测试 0
        ScheduleConfig scheduleConfigZero = mock(ScheduleConfig.class);
        when(scheduleConfigZero.getScheduleMode()).thenReturn(ScheduleMode.FIXED_DELAY);
        when(scheduleConfigZero.getFixedDelay()).thenReturn(0L);
        assertThrows(
                IllegalArgumentException.class,
                () -> scheduler.schedule(agentConfig, scheduleConfigZero));

        // 测试负值
        ScheduleConfig scheduleConfigNegative = mock(ScheduleConfig.class);
        when(scheduleConfigNegative.getScheduleMode()).thenReturn(ScheduleMode.FIXED_DELAY);
        when(scheduleConfigNegative.getFixedDelay()).thenReturn(-100L);
        assertThrows(
                IllegalArgumentException.class,
                () -> scheduler.schedule(agentConfig, scheduleConfigNegative));
    }

    @Test
    void testScheduleCronWithInvalidValue() {
        ModelConfig modelConfig = STUB_MODEL_CONFIG;
        RuntimeAgentConfig agentConfig =
                RuntimeAgentConfig.builder()
                        .name("InvalidCronAgent")
                        .modelConfig(modelConfig)
                        .sysPrompt("Test prompt")
                        .build();

        // 测试 null
        ScheduleConfig scheduleConfigNull = mock(ScheduleConfig.class);
        when(scheduleConfigNull.getScheduleMode()).thenReturn(ScheduleMode.CRON);
        when(scheduleConfigNull.getCronExpression()).thenReturn(null);
        assertThrows(
                IllegalArgumentException.class,
                () -> scheduler.schedule(agentConfig, scheduleConfigNull));

        // 测试空白
        ScheduleConfig scheduleConfigBlank = mock(ScheduleConfig.class);
        when(scheduleConfigBlank.getScheduleMode()).thenReturn(ScheduleMode.CRON);
        when(scheduleConfigBlank.getCronExpression()).thenReturn("  ");
        assertThrows(
                IllegalArgumentException.class,
                () -> scheduler.schedule(agentConfig, scheduleConfigBlank));
    }

    @Test
    void testScheduleWithInitialDelay() {
        ModelConfig modelConfig = STUB_MODEL_CONFIG;
        RuntimeAgentConfig agentConfig =
                RuntimeAgentConfig.builder()
                        .name("InitialDelayAgent")
                        .modelConfig(modelConfig)
                        .sysPrompt("Test prompt")
                        .build();

        ScheduleConfig scheduleConfig =
                ScheduleConfig.builder().cron("0 0 8 * * ?").initialDelay(1000L).build();

        ScheduleAgentTask task = scheduler.schedule(agentConfig, scheduleConfig);
        assertNotNull(task);
    }

    @Test
    void testScheduleWithZoneId() {
        ModelConfig modelConfig = STUB_MODEL_CONFIG;
        RuntimeAgentConfig agentConfig =
                RuntimeAgentConfig.builder()
                        .name("ZoneIdAgent")
                        .modelConfig(modelConfig)
                        .sysPrompt("Test prompt")
                        .build();

        ScheduleConfig scheduleConfig =
                ScheduleConfig.builder().cron("0 0 8 * * ?").zoneId("UTC").build();

        ScheduleAgentTask task = scheduler.schedule(agentConfig, scheduleConfig);
        assertNotNull(task);
    }

    @Test
    void testScheduleThrowingSchedulerException() throws SchedulerException {
        // 模拟基础设施
        Scheduler mockScheduler = mock(Scheduler.class);

        QuartzAgentScheduler mockAgentScheduler =
                QuartzAgentScheduler.builder().scheduler(mockScheduler).build();

        // 配置异常
        doThrow(new SchedulerException("Schedule failed"))
                .when(mockScheduler)
                .scheduleJob(any(JobDetail.class), any(Trigger.class));

        ModelConfig modelConfig = STUB_MODEL_CONFIG;
        RuntimeAgentConfig agentConfig =
                RuntimeAgentConfig.builder()
                        .name("FailAgent")
                        .modelConfig(modelConfig)
                        .sysPrompt("Test prompt")
                        .build();
        ScheduleConfig scheduleConfig = ScheduleConfig.builder().cron("0 0 8 * * ?").build();

        assertThrows(
                RuntimeException.class,
                () -> mockAgentScheduler.schedule(agentConfig, scheduleConfig));

        mockAgentScheduler.shutdown();
    }

    @Test
    void testCancelThrowingSchedulerException() throws SchedulerException {
        Scheduler mockScheduler = mock(Scheduler.class);

        QuartzAgentScheduler mockAgentScheduler =
                QuartzAgentScheduler.builder().scheduler(mockScheduler).build();

        // 需先在映射中放入任务，才能到达 deleteJob 调用。
        // 映射虽私有，但 schedule() 会写入，
        // 因此先成功调度，再模拟 deleteJob 失败。

        ModelConfig modelConfig = STUB_MODEL_CONFIG;
        RuntimeAgentConfig agentConfig =
                RuntimeAgentConfig.builder()
                        .name("CancelFailAgent")
                        .modelConfig(modelConfig)
                        .sysPrompt("Test prompt")
                        .build();
        ScheduleConfig scheduleConfig = ScheduleConfig.builder().cron("0 0 8 * * ?").build();

        mockAgentScheduler.schedule(agentConfig, scheduleConfig);

        doThrow(new SchedulerException("Cancel failed")).when(mockScheduler).deleteJob(any());

        boolean result = mockAgentScheduler.cancel("CancelFailAgent");
        assertFalse(result);

        mockAgentScheduler.shutdown();
    }

    @Test
    void testPauseThrowingSchedulerException() throws SchedulerException {
        Scheduler mockScheduler = mock(Scheduler.class);

        QuartzAgentScheduler mockAgentScheduler =
                QuartzAgentScheduler.builder().scheduler(mockScheduler).build();

        ModelConfig modelConfig = STUB_MODEL_CONFIG;
        RuntimeAgentConfig agentConfig =
                RuntimeAgentConfig.builder()
                        .name("PauseFailAgent")
                        .modelConfig(modelConfig)
                        .sysPrompt("Test prompt")
                        .build();
        ScheduleConfig scheduleConfig = ScheduleConfig.builder().cron("0 0 8 * * ?").build();

        mockAgentScheduler.schedule(agentConfig, scheduleConfig);

        doThrow(new SchedulerException("Pause failed")).when(mockScheduler).pauseJob(any());

        boolean result = mockAgentScheduler.pause("PauseFailAgent");
        assertFalse(result);

        mockAgentScheduler.shutdown();
    }

    @Test
    void testResumeThrowingSchedulerException() throws SchedulerException {
        Scheduler mockScheduler = mock(Scheduler.class);

        QuartzAgentScheduler mockAgentScheduler =
                QuartzAgentScheduler.builder().scheduler(mockScheduler).build();

        ModelConfig modelConfig = STUB_MODEL_CONFIG;
        RuntimeAgentConfig agentConfig =
                RuntimeAgentConfig.builder()
                        .name("ResumeFailAgent")
                        .modelConfig(modelConfig)
                        .sysPrompt("Test prompt")
                        .build();
        ScheduleConfig scheduleConfig = ScheduleConfig.builder().cron("0 0 8 * * ?").build();

        mockAgentScheduler.schedule(agentConfig, scheduleConfig);

        doThrow(new SchedulerException("Resume failed")).when(mockScheduler).resumeJob(any());

        boolean result = mockAgentScheduler.resume("ResumeFailAgent");
        assertFalse(result);

        mockAgentScheduler.shutdown();
    }

    @Test
    void testGetStatusThrowingSchedulerException() throws SchedulerException {
        Scheduler mockScheduler = mock(Scheduler.class);

        QuartzAgentScheduler mockAgentScheduler =
                QuartzAgentScheduler.builder().scheduler(mockScheduler).build();

        ModelConfig modelConfig = STUB_MODEL_CONFIG;
        RuntimeAgentConfig agentConfig =
                RuntimeAgentConfig.builder()
                        .name("StatusFailAgent")
                        .modelConfig(modelConfig)
                        .sysPrompt("Test prompt")
                        .build();
        ScheduleConfig scheduleConfig = ScheduleConfig.builder().cron("0 0 8 * * ?").build();

        mockAgentScheduler.schedule(agentConfig, scheduleConfig);

        doThrow(new SchedulerException("Status failed")).when(mockScheduler).getTriggerState(any());

        assertThrows(RuntimeException.class, () -> mockAgentScheduler.getStatus("StatusFailAgent"));

        mockAgentScheduler.shutdown();
    }

    @Test
    void testShutdownThrowingSchedulerException() throws SchedulerException {
        Scheduler mockScheduler = mock(Scheduler.class);

        QuartzAgentScheduler mockAgentScheduler =
                QuartzAgentScheduler.builder().scheduler(mockScheduler).build();

        doThrow(new SchedulerException("Shutdown failed")).when(mockScheduler).shutdown(true);

        // 应捕获并记录错误，不向外抛出
        mockAgentScheduler.shutdown();

        verify(mockScheduler).shutdown(true);
    }

    @Test
    void testInterruptNonExistent() {
        assertFalse(scheduler.interrupt("NonExistent"));
    }

    @Test
    void testInterruptSuccess() throws SchedulerException {
        Scheduler mockScheduler = mock(Scheduler.class);

        QuartzAgentScheduler mockAgentScheduler =
                QuartzAgentScheduler.builder().scheduler(mockScheduler).build();

        ModelConfig modelConfig = STUB_MODEL_CONFIG;
        RuntimeAgentConfig agentConfig =
                RuntimeAgentConfig.builder()
                        .name("InterruptAgent")
                        .modelConfig(modelConfig)
                        .sysPrompt("Test prompt")
                        .build();
        ScheduleConfig scheduleConfig = ScheduleConfig.builder().cron("0 0 8 * * ?").build();

        mockAgentScheduler.schedule(agentConfig, scheduleConfig);

        assertTrue(mockAgentScheduler.interrupt("InterruptAgent"));
        verify(mockScheduler).interrupt(any(JobKey.class));

        mockAgentScheduler.shutdown();
    }

    @Test
    void testInterruptThrowingSchedulerException() throws SchedulerException {
        Scheduler mockScheduler = mock(Scheduler.class);

        QuartzAgentScheduler mockAgentScheduler =
                QuartzAgentScheduler.builder().scheduler(mockScheduler).build();

        ModelConfig modelConfig = STUB_MODEL_CONFIG;
        RuntimeAgentConfig agentConfig =
                RuntimeAgentConfig.builder()
                        .name("InterruptFailAgent")
                        .modelConfig(modelConfig)
                        .sysPrompt("Test prompt")
                        .build();
        ScheduleConfig scheduleConfig = ScheduleConfig.builder().cron("0 0 8 * * ?").build();

        mockAgentScheduler.schedule(agentConfig, scheduleConfig);

        doThrow(new UnableToInterruptJobException("Interrupt failed"))
                .when(mockScheduler)
                .interrupt(any(JobKey.class));

        assertFalse(mockAgentScheduler.interrupt("InterruptFailAgent"));

        mockAgentScheduler.shutdown();
    }

    @Test
    void testRescheduleNextFixedDelay() throws SchedulerException {
        Scheduler mockScheduler = mock(Scheduler.class);

        QuartzAgentScheduler mockAgentScheduler =
                QuartzAgentScheduler.builder().scheduler(mockScheduler).build();

        JobKey jobKey = new JobKey("testJob", "testGroup");
        long delay = 1000L;

        mockAgentScheduler.rescheduleNextFixedDelay(jobKey, delay);

        verify(mockScheduler).scheduleJob(any(Trigger.class));

        mockAgentScheduler.shutdown();
    }

    @Test
    void testRescheduleNextFixedDelayThrowingException() throws SchedulerException {
        Scheduler mockScheduler = mock(Scheduler.class);

        QuartzAgentScheduler mockAgentScheduler =
                QuartzAgentScheduler.builder().scheduler(mockScheduler).build();

        JobKey jobKey = new JobKey("testJob", "testGroup");

        doThrow(new SchedulerException("Reschedule failed"))
                .when(mockScheduler)
                .scheduleJob(any(Trigger.class));

        assertThrows(
                RuntimeException.class,
                () -> mockAgentScheduler.rescheduleNextFixedDelay(jobKey, 1000L));

        mockAgentScheduler.shutdown();
    }

    @Test
    void testBuilderWithCustomSchedulerId() {
        String customId = "custom-scheduler-id";
        QuartzAgentScheduler customScheduler =
                QuartzAgentScheduler.builder().schedulerId(customId).build();

        assertNotNull(customScheduler);
        // 验证注册表中已注册
        assertEquals(customScheduler, QuartzAgentSchedulerRegistry.get(customId));

        customScheduler.shutdown();
        // 验证已注销
        assertNull(QuartzAgentSchedulerRegistry.get(customId));
    }
}
