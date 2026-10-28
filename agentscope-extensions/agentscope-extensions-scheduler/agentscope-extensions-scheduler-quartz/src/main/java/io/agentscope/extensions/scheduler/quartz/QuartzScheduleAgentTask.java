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

import io.agentscope.extensions.scheduler.BaseScheduleAgentTask;
import io.agentscope.extensions.scheduler.config.RuntimeAgentConfig;
import io.agentscope.extensions.scheduler.config.ScheduleConfig;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;

/**
 * Quartz 的 {@link io.agentscope.extensions.scheduler.ScheduleAgentTask} 实现，包装 {@link JobKey} 并提供底层 Quartz 调度器访问，以执行暂停、恢复等操作。
 */
class QuartzScheduleAgentTask extends BaseScheduleAgentTask {

    private final JobKey jobKey;
    private final Scheduler quartzScheduler;

    /**
     * 创建 QuartzScheduleAgentTask。
     * @param agentConfig 智能体配置
     * @param scheduleConfig 调度配置
     * @param scheduler 所属 QuartzAgentScheduler
     * @param jobKey Quartz JobKey
     * @param quartzScheduler Quartz Scheduler 实例
     */
    QuartzScheduleAgentTask(
            RuntimeAgentConfig agentConfig,
            ScheduleConfig scheduleConfig,
            QuartzAgentScheduler scheduler,
            JobKey jobKey,
            Scheduler quartzScheduler) {
        super(agentConfig, scheduleConfig, scheduler);
        this.jobKey = jobKey;
        this.quartzScheduler = quartzScheduler;
    }

    /**
     * 获取任务关联的 Quartz JobKey。
     * @return 任务 JobKey
     */
    JobKey getJobKey() {
        return jobKey;
    }

    /**
     * 暂停此任务对应的 Quartz 作业。
     */
    void pause() {
        try {
            quartzScheduler.pauseJob(jobKey);
        } catch (SchedulerException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 恢复此任务对应的 Quartz 作业。
     */
    void resume() {
        try {
            quartzScheduler.resumeJob(jobKey);
        } catch (SchedulerException e) {
            throw new RuntimeException(e);
        }
    }
}
