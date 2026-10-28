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

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.Model;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.extensions.scheduler.AgentScheduler;
import io.agentscope.extensions.scheduler.ScheduleAgentTask;
import io.agentscope.extensions.scheduler.config.AgentConfig;
import io.agentscope.extensions.scheduler.config.ModelConfig;
import io.agentscope.extensions.scheduler.config.RuntimeAgentConfig;
import io.agentscope.extensions.scheduler.config.ScheduleConfig;
import io.agentscope.extensions.scheduler.config.ScheduleMode;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import org.quartz.CronScheduleBuilder;
import org.quartz.CronTrigger;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.SimpleTrigger;
import org.quartz.Trigger;
import org.quartz.Trigger.TriggerState;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.quartz.impl.StdSchedulerFactory;
import org.quartz.impl.matchers.GroupMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 基于 Quartz 的 AgentScheduler 实现，提供应用内智能体调度及生命周期管理。
 * <p>支持由 Quartz 属性配置的内存／持久化作业存储，Cron、固定频率、固定延迟，以及调度、暂停、恢复、取消等控制。需要 Quartz 依赖；持久化时须正确配置 Quartz 属性。
 * <p>使用示例：
 * <pre>{@code
 * // 1. 创建调度器
 * AgentScheduler scheduler = QuartzAgentScheduler.builder()
 *     .autoStart(true)
 *     .build();
 *
 * // 2. 创建智能体配置
 * AgentConfig agentConfig = AgentConfig.builder()
 *     .name("MyAgent")
 *     .description("My scheduled agent")
 *     .agentFactory(() -> ReActAgent.builder()
 *         .name("MyAgent")
 *         .model(model)
 *         .toolkit(toolkit)
 *         .build())
 *     .build();
 *
 * // 3. 配置调度
 * ScheduleConfig scheduleConfig = ScheduleConfig.builder()
 *     .scheduleMode(ScheduleMode.CRON)
 *     .cronExpression("0 0 8 * * ?") // 每天 8:00 执行
 *     .build();
 *
 * // 4. 调度智能体
 * ScheduleAgentTask task = scheduler.schedule(agentConfig, scheduleConfig);
 *
 * // 5. 管理任务
 * scheduler.pause("MyAgent");
 * scheduler.resume("MyAgent");
 * scheduler.cancel("MyAgent");
 * }</pre>
 * <p>通过 {@code QuartzAgentSchedulerRegistry} 将 Quartz Job 连接到调度器实例；{@code FIXED_DELAY} 在完成后手动重新创建触发器；同一 JVM 中多个调度器应显式配置不同 ID；构建器默认 ID 相同，不会自动生成唯一值。
 * <p>持久化作业记录不等于持久化所有运行时依赖，跨节点及重启后的实际执行仍依赖本地注册和可重建配置。
 * @see AgentScheduler
 * @see ScheduleAgentTask
 */
public class QuartzAgentScheduler implements AgentScheduler {

    private static final Logger logger = LoggerFactory.getLogger(QuartzAgentScheduler.class);

    private final Scheduler scheduler;
    private final Map<String, QuartzScheduleAgentTask> tasks = new ConcurrentHashMap<>();
    private final String schedulerId;

    private QuartzAgentScheduler(Scheduler scheduler, String schedulerId) {
        if (scheduler == null) {
            throw new IllegalArgumentException("Scheduler must not be null");
        }
        this.scheduler = scheduler;
        this.schedulerId = schedulerId;
    }

    /**
     * 将此调度器实例注册到注册表。
     */
    private void register() {
        QuartzAgentSchedulerRegistry.register(schedulerId, this);
    }

    /**
     * 创建 QuartzAgentScheduler 构建器。
     * @return 新 Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 使用默认调度配置安排智能体执行。
     * @param agentConfig 待调度智能体配置
     * @return 任务句柄
     */
    public ScheduleAgentTask schedule(AgentConfig agentConfig) {
        return this.schedule(agentConfig, ScheduleConfig.builder().build());
    }

    /**
     * 根据配置安排智能体执行，不提供初始输入。委托 {@link #schedule(AgentConfig, ScheduleConfig, Msg)}，传入 {@code null} 初始消息。
     * @param agentConfig 智能体配置
     * @param scheduleConfig 时间、模式等调度配置
     * @return 任务句柄
     * @throws RuntimeException 调度失败时
     */
    @Override
    public ScheduleAgentTask schedule(AgentConfig agentConfig, ScheduleConfig scheduleConfig) {
        return this.schedule(agentConfig, scheduleConfig, null);
    }

    /**
     * 根据配置及初始输入创建 Quartz Job 和 Trigger。
     * <ul><li>{@code CRON}：CronTrigger。</li><li>{@code FIXED_RATE}：无限重复的 SimpleTrigger。</li><li>{@code FIXED_DELAY}：单次触发，完成后重新调度。</li></ul>
     * <p>非空 {@code input} 序列化为 JSON 并存入 JobDataMap，使输入可用于 JDBCJobStore 等持久化／集群存储；其他运行时依赖仍须在执行节点可用。
     * @param agentConfig 智能体配置
     * @param scheduleConfig 时间、模式等调度配置
     * @param input 触发时的初始消息，可为 null
     * @return 任务句柄
     * @throws RuntimeException 调度或输入序列化失败时
     */
    @Override
    public ScheduleAgentTask schedule(
            AgentConfig agentConfig, ScheduleConfig scheduleConfig, Msg input) {
        if (agentConfig == null) {
            throw new IllegalArgumentException("AgentConfig must not be null");
        }
        if (scheduleConfig == null) {
            throw new IllegalArgumentException("ScheduleConfig must not be null");
        }

        RuntimeAgentConfig runtimeConfig =
                agentConfig instanceof RuntimeAgentConfig rc
                        ? rc
                        : RuntimeAgentConfig.builder()
                                .name(agentConfig.getName())
                                .modelConfig(agentConfig.getModelConfig())
                                .sysPrompt(agentConfig.getSysPrompt())
                                .build();

        String jobName = runtimeConfig.getName();
        if (jobName == null || jobName.trim().isEmpty()) {
            throw new IllegalArgumentException("Agent name must not be null or empty");
        }

        // 检查是否已调度
        if (tasks.containsKey(jobName)) {
            logger.warn("Task '{}' is already scheduled, returning existing task", jobName);
            return tasks.get(jobName);
        }

        JobKey jobKey = new JobKey(jobName, "agentscope-quartz");
        JobDetail jobDetail =
                JobBuilder.newJob(AgentQuartzJob.class)
                        .withIdentity(jobKey)
                        .storeDurably(true)
                        .usingJobData("schedulerId", schedulerId)
                        .usingJobData("taskName", jobName)
                        .build();

        if (input != null) {
            try {
                String msgJson = JsonUtils.getJsonCodec().toJson(input);
                jobDetail.getJobDataMap().put("inputMsg", msgJson);
            } catch (Exception e) {
                logger.error("Failed to serialize input message for task: {}", jobName, e);
                throw new RuntimeException("Serialization failed for input message", e);
            }
        }

        QuartzScheduleAgentTask task =
                new QuartzScheduleAgentTask(runtimeConfig, scheduleConfig, this, jobKey, scheduler);

        ScheduleMode mode = scheduleConfig.getScheduleMode();
        if (mode == ScheduleMode.FIXED_RATE) {
            if (scheduleConfig.getFixedRate() == null || scheduleConfig.getFixedRate() <= 0) {
                throw new IllegalArgumentException(
                        "Fixed rate must be a positive value for FIXED_RATE mode");
            }
        } else if (mode == ScheduleMode.FIXED_DELAY) {
            if (scheduleConfig.getFixedDelay() == null || scheduleConfig.getFixedDelay() <= 0) {
                throw new IllegalArgumentException(
                        "Fixed delay must be a positive value for FIXED_DELAY mode");
            }
        } else if (mode == ScheduleMode.CRON) {
            if (scheduleConfig.getCronExpression() == null
                    || scheduleConfig.getCronExpression().trim().isEmpty()) {
                throw new IllegalArgumentException(
                        "Cron expression must not be null or blank for CRON mode");
            }
        }

        Long initialDelay = scheduleConfig.getInitialDelay();
        Date startAt =
                initialDelay != null
                        ? new Date(System.currentTimeMillis() + initialDelay)
                        : new Date();

        try {
            if (mode == ScheduleMode.CRON) {
                CronScheduleBuilder csb =
                        CronScheduleBuilder.cronSchedule(scheduleConfig.getCronExpression());
                if (scheduleConfig.getZoneId() != null) {
                    csb = csb.inTimeZone(TimeZone.getTimeZone(scheduleConfig.getZoneId()));
                }
                Trigger trigger =
                        TriggerBuilder.newTrigger()
                                .withIdentity(jobKey.getName(), jobKey.getGroup())
                                .startAt(startAt)
                                .withSchedule(csb)
                                .build();
                scheduler.scheduleJob(jobDetail, trigger);
            } else if (mode == ScheduleMode.FIXED_RATE) {
                SimpleScheduleBuilder ssb =
                        SimpleScheduleBuilder.simpleSchedule()
                                .withIntervalInMilliseconds(scheduleConfig.getFixedRate())
                                .repeatForever();
                Trigger trigger =
                        TriggerBuilder.newTrigger()
                                .withIdentity(jobKey.getName(), jobKey.getGroup())
                                .startAt(startAt)
                                .withSchedule(ssb)
                                .build();
                scheduler.scheduleJob(jobDetail, trigger);
            } else if (mode == ScheduleMode.FIXED_DELAY) {
                Trigger trigger =
                        TriggerBuilder.newTrigger()
                                .withIdentity(jobKey.getName(), jobKey.getGroup())
                                .startAt(startAt)
                                .build();
                jobDetail.getJobDataMap().put("fixedDelay", scheduleConfig.getFixedDelay());
                scheduler.scheduleJob(jobDetail, trigger);
            }

            tasks.put(jobName, task);
            logger.info("Successfully scheduled task '{}' with mode {}", jobName, mode);

        } catch (SchedulerException e) {
            logger.error("Failed to schedule task '{}'", jobName, e);
            throw new RuntimeException("Failed to schedule task: " + jobName, e);
        }

        return task;
    }

    /**
     * 取消任务，从 Quartz 移除 Job 与 Trigger。
     * @param name 待取消智能体／任务名称
     * @return 找到并取消为 {@code true}，否则为 {@code false}
     */
    @Override
    public boolean cancel(String name) {
        if (name == null || name.trim().isEmpty()) {
            return false;
        }
        QuartzScheduleAgentTask task = getScheduledAgent(name);
        if (task == null) {
            logger.warn("Attempt to cancel non-existent task '{}'", name);
            return false;
        }
        try {
            scheduler.deleteJob(task.getJobKey());
            tasks.remove(name);
            logger.info("Successfully cancelled task '{}'", name);
            return true;
        } catch (SchedulerException e) {
            logger.error("Failed to cancel task '{}'", name, e);
            return false;
        }
    }

    /**
     * 按名称获取调度任务。
     * @param name 智能体名称
     * @return 任务句柄，未找到时为 {@code null}
     */
    @Override
    public QuartzScheduleAgentTask getScheduledAgent(String name) {
        if (name == null || name.trim().isEmpty()) {
            return null;
        }
        QuartzScheduleAgentTask task = tasks.get(name);
        if (task != null) {
            return task;
        }
        // 本地缓存中不存在时，
        // 尝试从 Quartz 加载任务信息，例如在集群
        // 模式下。
        return loadTaskFromQuartz(name);
    }

    /**
     * 获取本地缓存与 Quartz 中的全部任务。即使本地内存没有（例如由集群其他节点创建），也尝试从 Quartz 加载。
     * @return 此调度器管理的全部任务
     */
    @Override
    public List<ScheduleAgentTask> getAllScheduleAgentTasks() {
        List<ScheduleAgentTask> allTasks = new ArrayList<>();
        try {
            // 获取 "agentscope-quartz" 组的全部作业键
            Set<JobKey> jobKeys =
                    scheduler.getJobKeys(GroupMatcher.jobGroupEquals("agentscope-quartz"));

            for (JobKey jobKey : jobKeys) {
                String name = jobKey.getName();
                // 优先从本地缓存读取，保留完整配置
                QuartzScheduleAgentTask task = tasks.get(name);
                if (task == null) {
                    // 回退到从 Quartz 加载
                    task = loadTaskFromQuartz(name);
                }
                if (task != null) {
                    allTasks.add(task);
                }
            }
        } catch (SchedulerException e) {
            logger.error("Failed to retrieve all scheduled tasks from Quartz", e);
        }
        return allTasks;
    }

    /**
     * 关闭 Quartz 调度器并释放资源。
     * @see Scheduler#shutdown(boolean)
     */
    @Override
    public void shutdown() {
        logger.info("Shutting down QuartzAgentScheduler...");
        try {
            scheduler.shutdown(true);
            logger.info("QuartzAgentScheduler shutdown completed");
        } catch (SchedulerException e) {
            logger.error("Error occurred while shutting down QuartzAgentScheduler", e);
        } finally {
            QuartzAgentSchedulerRegistry.unregister(schedulerId);
        }
    }

    @Override
    public String getSchedulerType() {
        return "quartz";
    }

    /**
     * 暂停调度任务。
     * @param name 智能体／任务名称
     * @return 成功暂停为 {@code true}，否则为 {@code false}
     */
    public boolean pause(String name) {
        if (name == null || name.trim().isEmpty()) {
            return false;
        }
        QuartzScheduleAgentTask task = getScheduledAgent(name);
        if (task == null) {
            logger.warn("Attempt to pause non-existent task '{}'", name);
            return false;
        }
        try {
            scheduler.pauseJob(task.getJobKey());
            logger.info("Successfully paused task '{}'", name);
            return true;
        } catch (SchedulerException e) {
            logger.error("Failed to pause task '{}'", name, e);
            return false;
        }
    }

    /**
     * 恢复已暂停任务。
     * @param name 智能体／任务名称
     * @return 成功恢复为 {@code true}，否则为 {@code false}
     */
    public boolean resume(String name) {
        if (name == null || name.trim().isEmpty()) {
            return false;
        }
        QuartzScheduleAgentTask task = getScheduledAgent(name);
        if (task == null) {
            logger.warn("Attempt to resume non-existent task '{}'", name);
            return false;
        }
        try {
            scheduler.resumeJob(task.getJobKey());
            logger.info("Successfully resumed task '{}'", name);
            return true;
        } catch (SchedulerException e) {
            logger.error("Failed to resume task '{}'", name, e);
            return false;
        }
    }

    /**
     * 中断运行中的任务。
     * @param name 智能体／任务名称
     * @return 已发送中断信号为 {@code true}，否则为 {@code false}
     */
    public boolean interrupt(String name) {
        if (name == null || name.trim().isEmpty()) {
            return false;
        }
        QuartzScheduleAgentTask task = getScheduledAgent(name);
        if (task == null) {
            logger.warn("Attempt to interrupt non-existent task '{}'", name);
            return false;
        }
        try {
            scheduler.interrupt(task.getJobKey());
            logger.info("Interrupt signal sent for task '{}'", name);
            return true;
        } catch (SchedulerException e) {
            logger.error("Failed to interrupt task '{}'", name, e);
            return false;
        }
    }

    /**
     * 根据固定延迟安排下一次执行。
     * @param jobKey 任务 JobKey
     * @param delay 延迟毫秒数
     */
    void rescheduleNextFixedDelay(JobKey jobKey, long delay) {
        Date next = new Date(System.currentTimeMillis() + delay);
        TriggerKey triggerKey = new TriggerKey(jobKey.getName(), jobKey.getGroup());
        Trigger trigger =
                TriggerBuilder.newTrigger()
                        .withIdentity(triggerKey)
                        .forJob(jobKey)
                        .startAt(next)
                        .build();
        try {
            if (scheduler.rescheduleJob(triggerKey, trigger) == null) {
                scheduler.scheduleJob(trigger);
            }
            logger.debug("Rescheduled fixed delay task '{}' at {}", jobKey.getName(), next);
        } catch (SchedulerException e) {
            logger.error("Failed to reschedule fixed delay task '{}'", jobKey.getName(), e);
            throw new RuntimeException(e);
        }
    }

    /**
     * 获取任务当前状态。
     * @param name 智能体／任务名称
     * @return Quartz TriggerState，未找到时为 {@code null}
     */
    public TriggerState getStatus(String name) {
        if (name == null || name.trim().isEmpty()) {
            return null;
        }
        QuartzScheduleAgentTask task = getScheduledAgent(name);
        if (task == null) {
            return null;
        }
        TriggerKey triggerKey =
                new TriggerKey(task.getJobKey().getName(), task.getJobKey().getGroup());
        try {
            return scheduler.getTriggerState(triggerKey);
        } catch (SchedulerException e) {
            logger.error("Failed to get status for task '{}'", name, e);
            throw new RuntimeException(e);
        }
    }

    /**
     * 从 Quartz 加载任务信息并重建 QuartzScheduleAgentTask，使分布式或重启后没有本地缓存时仍能执行暂停、恢复、取消等控制。这里恢复的是最小控制配置，不代表运行模型和工具所需的完整配置已恢复。
     */
    private QuartzScheduleAgentTask loadTaskFromQuartz(String name) {
        JobKey jobKey = new JobKey(name, "agentscope-quartz");
        try {
            if (!scheduler.checkExists(jobKey)) {
                return null;
            }

            JobDetail jobDetail = scheduler.getJobDetail(jobKey);
            if (jobDetail == null) {
                return null;
            }

            // 从 Trigger 重建 ScheduleConfig
            List<? extends Trigger> triggers = scheduler.getTriggersOfJob(jobKey);
            ScheduleConfig scheduleConfig = null;
            if (triggers != null && !triggers.isEmpty()) {
                Trigger trigger = triggers.get(0);
                if (trigger instanceof CronTrigger ct) {
                    scheduleConfig =
                            ScheduleConfig.builder()
                                    .cron(ct.getCronExpression())
                                    .zoneId(
                                            ct.getTimeZone() != null
                                                    ? ct.getTimeZone().getID()
                                                    : null)
                                    .build();
                } else if (trigger instanceof SimpleTrigger st) {
                    scheduleConfig =
                            ScheduleConfig.builder().fixedRate(st.getRepeatInterval()).build();
                }
            }

            if (scheduleConfig == null) {
                scheduleConfig = ScheduleConfig.builder().build();
            }

            // 构造最小 RuntimeAgentConfig，
            // 仅用于支持暂停、恢复、取消等任务控制操作
            // 【学习提示】Quartz 保存的调度记录不是完整智能体快照；此回退模型返回 null，只能恢复管理句柄，不能据此保证重启后可推理。
            RuntimeAgentConfig agentConfig =
                    RuntimeAgentConfig.builder()
                            .name(name)
                            .modelConfig(
                                    new ModelConfig() {
                                        @Override
                                        public String getModelName() {
                                            return "unknown";
                                        }

                                        @Override
                                        public Model createModel() {
                                            return null;
                                        }
                                    })
                            .build();

            return new QuartzScheduleAgentTask(
                    agentConfig, scheduleConfig, this, jobKey, scheduler);

        } catch (SchedulerException e) {
            logger.error("Failed to load task '{}' from Quartz", name, e);
            return null;
        }
    }

    /**
     * {@link QuartzAgentScheduler} 的构建器。
     */
    public static class Builder {
        private boolean autoStart = true;
        private Scheduler scheduler;
        private String schedulerId = "default-scheduler";

        /**
         * 设置调度器 ID。
         * @param schedulerId 调度器 ID
         * @return 当前构建器
         */
        public Builder schedulerId(String schedulerId) {
            this.schedulerId = schedulerId;
            return this;
        }

        /**
         * 设置自定义 Quartz Scheduler。
         * @param scheduler 使用的 Scheduler
         * @return 当前构建器
         */
        public Builder scheduler(Scheduler scheduler) {
            this.scheduler = scheduler;
            return this;
        }

        /**
         * 设置构建时是否自动启动，默认 {@code true}。
         * @param autoStart 为 {@code true} 时自动启动
         * @return 当前构建器
         */
        public Builder autoStart(boolean autoStart) {
            this.autoStart = autoStart;
            return this;
        }

        /**
         * 构建 QuartzAgentScheduler。
         * @return 新实例
         * @throws RuntimeException 初始化失败时
         */
        public QuartzAgentScheduler build() {
            Scheduler scheduler = this.scheduler;
            boolean internal = false;
            try {
                if (scheduler == null) {
                    scheduler = new StdSchedulerFactory().getScheduler();
                    internal = true;
                }

                if (scheduler == null) {
                    throw new IllegalArgumentException("Scheduler must not be null");
                }

                if (autoStart) {
                    scheduler.start();
                    logger.info("Quartz scheduler started successfully");
                }
                QuartzAgentScheduler agentScheduler =
                        new QuartzAgentScheduler(scheduler, schedulerId);
                agentScheduler.register();
                return agentScheduler;
            } catch (SchedulerException e) {
                if (internal && scheduler != null) {
                    try {
                        scheduler.shutdown();
                    } catch (SchedulerException ex) {
                        logger.warn("Failed to shutdown scheduler on error", ex);
                    }
                }
                logger.error("Failed to build QuartzAgentScheduler", e);
                throw new RuntimeException(e);
            }
        }
    }
}
