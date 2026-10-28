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
import io.agentscope.core.util.JsonUtils;
import io.agentscope.extensions.scheduler.ScheduleAgentTask;
import io.agentscope.extensions.scheduler.config.ScheduleMode;
import org.quartz.InterruptableJob;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 执行 AgentScope 智能体任务的 Quartz Job。
 * <p>从作业数据找到对应 {@link QuartzAgentScheduler} 与 {@link QuartzScheduleAgentTask}，执行运行逻辑，并处理 {@code FIXED_DELAY} 的重新调度。
 */
public class AgentQuartzJob implements InterruptableJob {

    private static final Logger logger = LoggerFactory.getLogger(AgentQuartzJob.class);

    private volatile boolean interrupted;

    /**
     * 执行智能体任务。
     * @param context Quartz JobExecutionContext
     * @throws JobExecutionException 作业执行失败时
     */
    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        if (interrupted) {
            return;
        }
        String schedulerId = context.getJobDetail().getJobDataMap().getString("schedulerId");
        if (schedulerId == null || schedulerId.trim().isEmpty()) {
            schedulerId = "default-scheduler";
        }
        String taskName = context.getJobDetail().getJobDataMap().getString("taskName");
        QuartzAgentScheduler scheduler = QuartzAgentSchedulerRegistry.get(schedulerId);
        if (scheduler == null) {
            return;
        }
        QuartzScheduleAgentTask task = scheduler.getScheduledAgent(taskName);
        if (task == null) {
            return;
        }

        ScheduleAgentTask<Msg> t = task;
        Msg inputMsg = null;
        String msgJson = context.getJobDetail().getJobDataMap().getString("inputMsg");

        if (msgJson != null && !msgJson.trim().isEmpty()) {
            try {
                inputMsg = JsonUtils.getJsonCodec().fromJson(msgJson, Msg.class);
            } catch (Exception ex) {
                logger.error(
                        "Failed to deserialize inputMsg. schedulerId={}, taskName={}, jobKey={},"
                                + " triggerKey={}, msgLen={}",
                        schedulerId,
                        taskName,
                        context.getJobDetail().getKey(),
                        context.getTrigger().getKey(),
                        msgJson.length(),
                        ex);

                JobExecutionException jee =
                        new JobExecutionException(
                                "Invalid inputMsg JSON for scheduled task: " + taskName, ex, false);
                jee.setUnscheduleFiringTrigger(true);
                throw jee;
            }
        }

        try {
            // 这里可以阻塞，因为 Quartz 作业在自己的专用线程
            // 池中运行，
            // 以同步方式执行；需要等待响应式流水线
            // 完成。
            if (inputMsg != null) {
                t.run(inputMsg).block();
            } else {
                t.run().block();
            }
        } catch (Exception e) {
            logger.error("Error executing scheduled agent task: {}", taskName, e);
            throw new JobExecutionException(e);
        }

        if (interrupted) {
            return;
        }
        ScheduleMode mode = task.getScheduleConfig().getScheduleMode();
        // 【学习提示】固定延迟在调用正常完成后才安排下一次；异常已从上方抛出，不能假设失败也会进入此重调度分支。
        if (mode == ScheduleMode.FIXED_DELAY) {
            long delay = context.getJobDetail().getJobDataMap().getLongValue("fixedDelay");
            scheduler.rescheduleNextFixedDelay(context.getJobDetail().getKey(), delay);
        }
    }

    /**
     * 向当前作业设置中断标志；现有实现不直接取消已开始阻塞等待的响应式调用。
     */
    @Override
    public void interrupt() {
        interrupted = true;
    }
}
